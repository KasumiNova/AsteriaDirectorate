#!/bin/bash
set -euo pipefail

GAME_DIR="${1:-/mnt/windows_data/Games/Starsector098-linux}"
TIMEOUT_SEC="${2:-30}"
MODE="${3:-launcher}"
LOG_FILE="$GAME_DIR/starsector.log"
PROCESS_LOG_FILE="$GAME_DIR/astd-smoke-process.log"
GAME_PID=""
GAME_PGID=""
LAST_LOG_SIZE=0

FATAL_LOG_PATTERN="ERROR .*com\\.fs\\.starfarer\\.combat\\.CombatMain|Ship hull spec \\[astd_[^]]+\\] not found|Weapon spec \\[astd_[^]]+\\] not found|Hullmod spec \\[astd_[^]]+\\] not found|RuntimeException: .*astd_|Exception in thread .*astd_|NoClassDefFoundError: .*asteriadirectorate|ClassNotFoundException: .*asteriadirectorate|ClassFormatError|VerifyError|LinkageError|NoSuchMethodError|NoSuchFieldError|A fatal error has been detected by the Java Runtime Environment|SIGSEGV|core dumped|FATAL"

echo "=== Asteria Directorate Game Launch Smoke Test ==="
echo "Game dir: $GAME_DIR"
echo "Timeout:  ${TIMEOUT_SEC}s"
echo "Mode:     $MODE"

if [[ ! -d "$GAME_DIR" ]]; then
    echo "FAIL: game dir not found: $GAME_DIR"
    exit 1
fi

if [[ -x "$GAME_DIR/launch_injected_ss.sh" ]]; then
    LAUNCH_CMD=("./launch_injected_ss.sh")
    echo "Launcher: launch_injected_ss.sh (SSOptimizer agent path)"
elif [[ -x "$GAME_DIR/starsector.sh" ]]; then
    LAUNCH_CMD=("./starsector.sh")
    echo "Launcher: starsector.sh"
else
    echo "FAIL: no launch_injected_ss.sh or starsector.sh found in $GAME_DIR"
    exit 1
fi

cleanup_game() {
    local pid="${GAME_PID:-}"
    local pgid="${GAME_PGID:-}"

    if [[ -n "$pgid" ]]; then
        kill -TERM -- "-$pgid" 2>/dev/null || true
        sleep 1
        kill -KILL -- "-$pgid" 2>/dev/null || true
    fi

    if [[ -n "$pid" ]]; then
        pkill -TERM -P "$pid" 2>/dev/null || true
        sleep 1
        pkill -KILL -P "$pid" 2>/dev/null || true
        kill -TERM "$pid" 2>/dev/null || true
        sleep 1
        kill -KILL "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
}

log_size_bytes() {
    if [[ -f "$LOG_FILE" ]]; then
        stat -c%s "$LOG_FILE" 2>/dev/null || echo 0
    else
        echo 0
    fi
}

log_age_seconds() {
    if [[ ! -f "$LOG_FILE" ]]; then
        echo "n/a"
        return 0
    fi
    local mtime now
    mtime=$(stat -c%Y "$LOG_FILE" 2>/dev/null || echo 0)
    now=$(date +%s)
    if [[ "$mtime" -le 0 ]]; then
        echo "n/a"
        return 0
    fi
    echo $((now - mtime))
}

has_live_game_process() {
    if [[ -n "$GAME_PID" ]] && kill -0 "$GAME_PID" 2>/dev/null; then
        return 0
    fi
    if [[ -n "$GAME_PGID" ]] && pgrep -g "$GAME_PGID" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

resolve_active_game_pid() {
    local pid=""
    if [[ -n "$GAME_PGID" ]]; then
        pid=$(pgrep -g "$GAME_PGID" -f 'com\.fs\.starfarer\.(StarfarerLauncher|combat\.CombatMain)' | head -n 1 || true)
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi
        pid=$(pgrep -g "$GAME_PGID" -f '/(jre_linux|zulu25_linux)/bin/java|/java ' | head -n 1 || true)
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi
    fi
    if [[ -n "$GAME_PID" ]] && kill -0 "$GAME_PID" 2>/dev/null; then
        echo "$GAME_PID"
        return 0
    fi
    return 1
}

print_progress() {
    local elapsed="$1"
    local active_pid="$2"
    local log_size log_age delta log_age_display
    log_size=$(log_size_bytes)
    log_age=$(log_age_seconds)
    delta=$((log_size - LAST_LOG_SIZE))
    if ((delta < 0)); then
        delta=$log_size
    fi
    LAST_LOG_SIZE="$log_size"
    if [[ "$log_age" == "n/a" ]]; then
        log_age_display="n/a"
    else
        log_age_display="${log_age}s"
    fi
    echo "[smoke] elapsed=${elapsed}s/${TIMEOUT_SEC}s active_pid=${active_pid:-none} pgid=${GAME_PGID:-none} log_bytes=${log_size} delta=${delta} log_age=${log_age_display}"
}

log_contains() {
    local pattern="$1"
    grep -q -E "$pattern" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null
}

print_log_matches() {
    local pattern="$1"
    grep -n -E "$pattern" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null || true
}

trap cleanup_game EXIT INT TERM

: > "$LOG_FILE" 2>/dev/null || true
: > "$PROCESS_LOG_FILE" 2>/dev/null || true

ORIGINAL_JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
if [[ "$MODE" == "game" || "$MODE" == "automation" ]]; then
    if [[ "$MODE" == "automation" ]]; then
        START_RES="${ASTD_SMOKE_START_RES:-2560x1440}"
    else
        START_RES="${ASTD_SMOKE_START_RES:-1920x1080}"
    fi
    START_FS="${ASTD_SMOKE_START_FS:-false}"
    START_SOUND="${ASTD_SMOKE_START_SOUND:-true}"
    EXTRA_OPTS="-Dssoptimizer.launcher.autostart=true -Dssoptimizer.launcher.autostart.res=${START_RES} -Dssoptimizer.launcher.autostart.fullscreen=${START_FS} -Dssoptimizer.launcher.autostart.sound=${START_SOUND} -DstartRes=${START_RES} -DstartFS=${START_FS} -DstartSound=${START_SOUND}"
    if [[ "$MODE" == "automation" ]]; then
        AUTOMATION_OUTPUT_DIR="${ASTD_AUTOMATION_OUTPUT_DIR:-$GAME_DIR/ssoptimizer-automation-output}"
        EXTRA_OPTS="$EXTRA_OPTS -Dssoptimizer.automation.enabled=true -Dssoptimizer.automation.scenario=arc_flare_aod7_basic -Dssoptimizer.automation.outputDir=${AUTOMATION_OUTPUT_DIR} -Dssoptimizer.automation.requireScreenshotFile=true"
    fi
    if [[ -n "$ORIGINAL_JAVA_TOOL_OPTIONS" ]]; then
        export JAVA_TOOL_OPTIONS="$ORIGINAL_JAVA_TOOL_OPTIONS $EXTRA_OPTS"
    else
        export JAVA_TOOL_OPTIONS="$EXTRA_OPTS"
    fi
    echo "Auto-enter game: enabled (${START_RES}, fullscreen=${START_FS}, sound=${START_SOUND})"
fi

cd "$GAME_DIR"
if command -v setsid >/dev/null 2>&1; then
    setsid "${LAUNCH_CMD[@]}" > "$PROCESS_LOG_FILE" 2>&1 &
    GAME_PID=$!
else
    "${LAUNCH_CMD[@]}" > "$PROCESS_LOG_FILE" 2>&1 &
    GAME_PID=$!
fi

GAME_PGID=$(ps -o pgid= -p "$GAME_PID" 2>/dev/null | tr -d ' ' || true)
if [[ -z "$GAME_PGID" ]]; then
    GAME_PGID="$GAME_PID"
fi

echo "Game PID: $GAME_PID"
echo "Game PGID: $GAME_PGID"
echo "Waiting ${TIMEOUT_SEC}s for startup..."
for ((elapsed = 0; elapsed < TIMEOUT_SEC; elapsed++)); do
    sleep 1
    ACTIVE_PID=$(resolve_active_game_pid || true)
    print_progress "$((elapsed + 1))" "$ACTIVE_PID"

    if ! has_live_game_process; then
        echo "Game process tree exited before timeout"
        break
    fi

    if log_contains "$FATAL_LOG_PATTERN"; then
        echo "Fatal marker detected in log, stopping early"
        break
    fi
done

cleanup_game
trap - EXIT INT TERM

echo ""
echo "=== Log Analysis ==="
PASS=true

if log_contains "$FATAL_LOG_PATTERN"; then
    echo "FAIL: fatal startup marker found"
    print_log_matches "$FATAL_LOG_PATTERN"
    PASS=false
fi

if grep -q "\[ASTD\] Asteria Directorate loaded" "$LOG_FILE" 2>/dev/null; then
    echo "OK: ASTD onApplicationLoad marker found"
else
    if [[ "$MODE" == "game" ]]; then
        echo "FAIL: ASTD onApplicationLoad marker not found within timeout"
        PASS=false
    else
        echo "WARN: ASTD onApplicationLoad marker not found within timeout"
    fi
fi

if grep -q "\[SSOptimizer\] Agent loaded" "$LOG_FILE" 2>/dev/null; then
    echo "OK: SSOptimizer agent loaded"
else
    echo "INFO: SSOptimizer agent marker not found"
fi

echo ""
if $PASS; then
    echo "=== Smoke Test PASSED ==="
    exit 0
else
    echo "=== Smoke Test FAILED ==="
    exit 1
fi
