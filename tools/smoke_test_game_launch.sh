#!/bin/bash
set -euo pipefail

GAME_DIR="${1:-/mnt/store/Games/Starsector098-linux}"
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

is_java_25() {
    local candidate="$1"
    if [[ ! -x "$candidate" ]]; then
        return 1
    fi
    "$candidate" -version 2>&1 | grep -Eq 'version "25([."]|$)|openjdk version "25([."]|$)'
}

find_java_25_under() {
    local search_root="$1"
    local candidate
    if [[ ! -d "$search_root" ]]; then
        return 0
    fi

    while IFS= read -r candidate; do
        if is_java_25 "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done < <(find "$search_root" -type f -name java 2>/dev/null)
}

resolve_java_25() {
    local candidate

    candidate=$(find_java_25_under "$GAME_DIR" | head -n 1 || true)
    if [[ -n "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return 0
    fi

    if [[ -n "${JAVA_HOME:-}" ]] && is_java_25 "$JAVA_HOME/bin/java"; then
        printf '%s\n' "$JAVA_HOME/bin/java"
        return 0
    fi

    local system_root
    for system_root in /usr/lib/jvm /usr/lib64/jvm /usr/java /opt/java /opt/jdk /opt/jdks; do
        candidate=$(find_java_25_under "$system_root" | head -n 1 || true)
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

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

resolve_acceptance_save_dir() {
    if [[ -n "${ASTD_ACCEPTANCE_SAVE_DIR:-}" ]]; then
        echo "$ASTD_ACCEPTANCE_SAVE_DIR"
        return 0
    fi

    local saves_root="${ASTD_ACCEPTANCE_SAVES_ROOT:-$GAME_DIR/saves}"
    local best_dir=""
    local best_mtime=0
    local candidate mtime

    shopt -s nullglob
    for candidate in "$saves_root"/save_Dev_*; do
        [[ -d "$candidate" ]] || continue
        [[ -f "$candidate/descriptor.xml" ]] || continue
        [[ -f "$candidate/campaign.xml" || -f "$candidate/campaign.xml.zip" ]] || continue

        mtime=$(stat -c%Y "$candidate" 2>/dev/null || echo 0)
        if ((mtime > best_mtime)); then
            best_mtime="$mtime"
            best_dir="$candidate"
        fi
    done
    shopt -u nullglob

    if [[ -z "$best_dir" ]]; then
        echo "FAIL: no save_Dev_* campaign save found under $saves_root" >&2
        exit 1
    fi

    echo "$best_dir"
}

launch_campaign_acceptance_direct() {
    local java_exe="$1"
    local acceptance_agent_jar="$2"
    local acceptance_save_dir="$3"
    shift 3

    LAUNCH_CMD=(
        "$java_exe"
        -javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar
        "-javaagent:$acceptance_agent_jar"
        -Dfile.encoding=UTF-8 \
        -noverify \
        -XX:+UnlockDiagnosticVMOptions \
        -XX:+ShowCodeDetailsInExceptionMessages \
        -XX:+PrintCommandLineFlags \
        -XX:+TieredCompilation \
        -XX:+DisableExplicitGC \
        -XX:+AlwaysPreTouch \
        -XX:+ParallelRefProcEnabled \
        -XX:+UseZGC \
        -XX:ReservedCodeCacheSize=256m \
        -XX:CompilerDirectivesFile=./compiler_directives.txt \
        -Djdk.xml.maxElementDepth=10000 \
        -XX:-BytecodeVerificationLocal \
        -XX:-BytecodeVerificationRemote \
        -Djava.util.Arrays.useLegacyMergeSort=true \
        --enable-preview \
        --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
        --add-opens=java.base/java.nio=ALL-UNNAMED \
        --add-opens=java.base/java.nio.Buffer.UNSAFE=ALL-UNNAMED \
        --add-opens=java.base/java.util=ALL-UNNAMED \
        --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
        --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
        --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED \
        --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
        --add-opens=java.base/java.lang.ref=ALL-UNNAMED \
        --add-opens=java.base/java.text=ALL-UNNAMED \
        --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
        --add-opens=java.desktop/java.awt.Rectangle=ALL-UNNAMED \
        --add-opens=java.desktop/java.awt=ALL-UNNAMED \
        --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
        --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
        --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
        -Xms24g \
        -Xmx24g \
        -Xss4m \
        -Dcom.fs.starfarer.settings.paths.saves=./saves \
        -Dcom.fs.starfarer.settings.paths.screenshots=./screenshots \
        -Dcom.fs.starfarer.settings.paths.mods=./mods \
        -Dcom.fs.starfarer.settings.paths.logs=. \
        -Djava.library.path=./native/linux \
        -Dssoptimizer.font.ttf.enable=true \
        -Dlog4j.configuration=file:./log4j.properties \
        -Dcom.fs.starfarer.settings.linux=true \
        -Dastd.devStorageAcceptance=true \
        "-Dastd.devStorageAcceptanceSaveDir=$acceptance_save_dir" \
        "$@" \
        -classpath janino.jar:commons-compiler.jar:commons-compiler-jdk.jar:starfarer.api.jar:starfarer_obf.jar:jogg-0.0.7.jar:jorbis-0.0.15.jar:json.jar:lwjgl.jar:jinput.jar:log4j-1.2.9.jar:lwjgl_util.jar:fs.sound_obf.jar:fs.common_obf.jar:xstream-1.4.21_miko.jar:txw2-3.0.2.jar:jaxb-api-2.4.0-b180830.0359.jar:webp-imageio-0.1.6.jar \
        com.fs.starfarer.StarfarerLauncher
    )
}

trap cleanup_game EXIT INT TERM

: > "$LOG_FILE" 2>/dev/null || true
: > "$PROCESS_LOG_FILE" 2>/dev/null || true

if [[ "$MODE" == "game" || "$MODE" == "automation" || "$MODE" == "campaign-acceptance" ]]; then
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
        AUTOMATION_SCENARIO="${ASTD_AUTOMATION_SCENARIO:-arc_flare_aod7_basic}"
        EXTRA_OPTS="$EXTRA_OPTS -Dssoptimizer.automation.enabled=true -Dssoptimizer.automation.scenario=${AUTOMATION_SCENARIO} -Dssoptimizer.automation.outputDir=${AUTOMATION_OUTPUT_DIR} -Dssoptimizer.automation.requireScreenshotFile=true"
    fi
    if [[ "$MODE" == "campaign-acceptance" ]]; then
        ACCEPTANCE_SAVE_DIR="$(resolve_acceptance_save_dir)"
        ASTD_ACCEPTANCE_AGENT_JAR="${ASTD_ACCEPTANCE_AGENT_JAR:-$GAME_DIR/mods/asteria_directorate/jars/AsteriaDirectorate-1.0-SNAPSHOT-acceptance-agent.jar}"
        ACCEPTANCE_JAVA_EXE="${ASTD_ACCEPTANCE_JAVA_EXE:-$(resolve_java_25 || true)}"
        if [[ ! -f "$ASTD_ACCEPTANCE_AGENT_JAR" ]]; then
            echo "FAIL: ASTD acceptance agent jar not found: $ASTD_ACCEPTANCE_AGENT_JAR" >&2
            exit 1
        fi
        if [[ -z "$ACCEPTANCE_JAVA_EXE" ]]; then
            echo "FAIL: no Java 25 runtime found for direct campaign acceptance launch" >&2
            exit 1
        fi
        launch_campaign_acceptance_direct "$ACCEPTANCE_JAVA_EXE" "$ASTD_ACCEPTANCE_AGENT_JAR" "$ACCEPTANCE_SAVE_DIR" $EXTRA_OPTS
        echo "Campaign acceptance save: ${ACCEPTANCE_SAVE_DIR}"
        echo "Campaign acceptance agent: ${ASTD_ACCEPTANCE_AGENT_JAR}"
        echo "Campaign acceptance Java: ${ACCEPTANCE_JAVA_EXE}"
    fi
    if [[ "$MODE" != "campaign-acceptance" ]]; then
        if [[ -n "${JAVA_TOOL_OPTIONS:-}" ]]; then
            export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} $EXTRA_OPTS"
        else
            export JAVA_TOOL_OPTIONS="${EXTRA_OPTS}"
        fi
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

    if [[ "$MODE" == "campaign-acceptance" ]] && log_contains "Dev storage acceptance passed|Dev storage acceptance failed"; then
        echo "Campaign acceptance marker detected, stopping early"
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
    if [[ "$MODE" == "game" || "$MODE" == "campaign-acceptance" ]]; then
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

if [[ "$MODE" == "campaign-acceptance" ]]; then
    if grep -q "Dev storage acceptance passed" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null; then
        echo "OK: dev storage acceptance marker found"
        print_log_matches "Dev storage acceptance passed"
    elif grep -q "Dev storage acceptance failed" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null; then
        echo "FAIL: dev storage acceptance failure marker found"
        print_log_matches "Dev storage acceptance failed"
        PASS=false
    else
        echo "FAIL: dev storage acceptance marker not found; campaign save was not loaded within timeout"
        PASS=false
    fi
fi

echo ""
if $PASS; then
    echo "=== Smoke Test PASSED ==="
    exit 0
else
    echo "=== Smoke Test FAILED ==="
    exit 1
fi
