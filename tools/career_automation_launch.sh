#!/bin/bash
# 生涯自动化实机启动器：直启 java（ASTD acceptance agent 注入标题屏 hook），
# 自动从标题屏读档（ASTD_CAREER_SAVE_DIR）或程序化新开档（ASTD_CAREER_NEW_GAME=true），
# 隔离 saves/logs/screenshots 目录，可与其它正在运行的游戏实例并存。
#
# 用法：
#   bash tools/career_automation_launch.sh <gameDir> <timeoutSec> <phaseName>
#
# 环境变量：
#   ASTD_CAREER_WORK_DIR   自动化工作目录（默认 <gameDir>/career-automation/<phaseName>）
#   ASTD_CAREER_SAVE_DIR   读档路径（与 ASTD_CAREER_NEW_GAME 二选一）
#   ASTD_CAREER_NEW_GAME   =true 时程序化新开档（固定种子 astd-career-automation）
#   ASTD_CAREER_SAVES_ROOT 隔离存档根目录（默认 <gameDir>/saves-career-automation）
#   ASTD_ACCEPTANCE_AGENT_JAR 验收 agent jar（默认 mods/ASTD/jars/*-acceptance-agent.jar）
#
# 退出码：0 = 无致命错误（断言结果由 python 驱动侧判定）；1 = 致命错误/启动失败。
set -euo pipefail

GAME_DIR="${1:?gameDir required}"
TIMEOUT_SEC="${2:-600}"
PHASE="${3:-run}"

WORK_DIR="${ASTD_CAREER_WORK_DIR:-$GAME_DIR/career-automation/$PHASE}"
SAVES_ROOT="${ASTD_CAREER_SAVES_ROOT:-$GAME_DIR/saves-career-automation}"
LOGS_DIR="$GAME_DIR/career-automation-logs/$PHASE"
SCREENSHOTS_DIR="$GAME_DIR/career-automation-screenshots/$PHASE"
AGENT_JAR="${ASTD_ACCEPTANCE_AGENT_JAR:-$GAME_DIR/mods/ASTD/jars/AsteriaDirectorate-1.0-SNAPSHOT-acceptance-agent.jar}"
PROCESS_LOG="$WORK_DIR/process.log"
GAME_LOG="$LOGS_DIR/starsector.log"
SESSION_DONE="$WORK_DIR/session-done"

GAME_PID=""
GAME_PGID=""

FATAL_LOG_PATTERN="ERROR .*com\\.fs\\.starfarer\\.combat\\.CombatMain|Ship hull spec \\[astd_[^]]+\\] not found|Weapon spec \\[astd_[^]]+\\] not found|Hullmod spec \\[astd_[^]]+\\] not found|RuntimeException: .*astd_|Exception in thread .*astd_|NoClassDefFoundError: .*asteriadirectorate|ClassNotFoundException: .*asteriadirectorate|ClassFormatError|VerifyError|LinkageError|NoSuchMethodError|NoSuchFieldError|A fatal error has been detected by the Java Runtime Environment|SIGSEGV|core dumped|FATAL"

echo "=== ASTD Career Automation Launch ==="
echo "Game dir:  $GAME_DIR"
echo "Phase:     $PHASE"
echo "Timeout:   ${TIMEOUT_SEC}s"
echo "Work dir:  $WORK_DIR"
echo "Saves:     $SAVES_ROOT"
echo "Logs dir:  $LOGS_DIR"

if [[ ! -d "$GAME_DIR" ]]; then
    echo "FAIL: game dir not found: $GAME_DIR"
    exit 1
fi
if [[ ! -f "$AGENT_JAR" ]]; then
    echo "FAIL: acceptance agent jar not found: $AGENT_JAR"
    exit 1
fi

mkdir -p "$WORK_DIR" "$SAVES_ROOT" "$LOGS_DIR" "$SCREENSHOTS_DIR"
# 清理必须尽量靠前：python 驱动在 Popen 后立刻轮询 status.json，
# 若此处晚于驱动首轮轮询，残留心跳会让驱动提前写 command.txt 再被这里删掉
rm -f "$SESSION_DONE" "$WORK_DIR"/command.txt "$WORK_DIR"/command.txt.tmp "$WORK_DIR"/result-*.json "$WORK_DIR"/result-*.json.tmp
rm -f "$GAME_LOG" "$WORK_DIR"/status.json "$WORK_DIR"/status.json.tmp
: > "$PROCESS_LOG"

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

JAVA_EXE="$(resolve_java_25 || true)"
if [[ -z "$JAVA_EXE" ]]; then
    echo "FAIL: no Java 25 runtime found"
    exit 1
fi
echo "Java:      $JAVA_EXE"

CAREER_MODE_OPTS="-Dastd.careerAutomation=true -Dastd.careerAutomation.enabled=true -Dastd.careerAutomation.dir=$WORK_DIR"
if [[ "${ASTD_CAREER_NEW_GAME:-false}" == "true" ]]; then
    CAREER_MODE_OPTS="$CAREER_MODE_OPTS -Dastd.careerAutomation.newGame=true"
    echo "Mode:      new game (fixed seed)"
else
    if [[ -z "${ASTD_CAREER_SAVE_DIR:-}" ]]; then
        echo "FAIL: ASTD_CAREER_SAVE_DIR is required when ASTD_CAREER_NEW_GAME is not true"
        exit 1
    fi
    CAREER_MODE_OPTS="$CAREER_MODE_OPTS -Dastd.careerAutomation.saveDir=$ASTD_CAREER_SAVE_DIR"
    echo "Mode:      load save $ASTD_CAREER_SAVE_DIR"
fi

# 直启 StarfarerLauncher 会停在模组启动器界面等待人工点击；改用与实机一致的
# launch_nanoforge_ss.sh（NanoForge deobf 路径），并用原版内置直启开关
# （-DlaunchDirect + -DstartRes，见 StarfarerSettings.isLaunchDirect）跳过启动器 UI。
# 通过 EXTRA_JVM_FLAGS 追加覆盖（同名 -D 后写生效）：隔离 saves/logs/screenshots、
# 注入 ASTD acceptance agent、开启生涯自动化。
START_RES="${ASTD_CAREER_START_RES:-1920x1080}"
AUTO_OPTS="-DlaunchDirect=true -DstartRes=${START_RES} -DstartFS=false -DstartSound=true"

# NanoForge 内置 log4j2.xml 把文件日志硬编码到 ./starsector.log，会与正在运行的
# 其它实例互相污染；用自定义 log4j2 配置把文件输出改到本阶段隔离目录（控制台输出不变）。
LOG4J2_CONFIG="$WORK_DIR/log4j2-career-automation.xml"
cat > "$LOG4J2_CONFIG" <<EOF
<?xml version="1.0" ?>
<Configuration name="CareerAutomation">
    <Appenders>
        <Console name="ConsoleAppender" target="SYSTEM_OUT">
            <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]:(%c{1}) %m%n"/>
        </Console>
        <RollingFile name="file" fileName="$LOGS_DIR/starsector.log" filePattern="$LOGS_DIR/starsector.log.%i">
            <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]:(%c{2}) %m%n"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="50000KB"/>
            </Policies>
            <DefaultRolloverStrategy max="3" fileIndex="min"/>
        </RollingFile>
    </Appenders>
    <Loggers>
        <Root level="INFO">
            <AppenderRef ref="ConsoleAppender"/>
            <AppenderRef ref="file"/>
        </Root>
    </Loggers>
</Configuration>
EOF

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
trap cleanup_game EXIT INT TERM

cd "$GAME_DIR"
# 同名 -D 后写生效：EXTRA_JVM_FLAGS 在 launch_nanoforge_ss.sh 中位于内置路径配置之后。
export EXTRA_JVM_FLAGS="-javaagent:$AGENT_JAR \
-Dcom.fs.starfarer.settings.paths.saves=$SAVES_ROOT \
-Dcom.fs.starfarer.settings.paths.screenshots=$SCREENSHOTS_DIR \
-Dcom.fs.starfarer.settings.paths.logs=$LOGS_DIR \
-Dlog4j2.configurationFile=file:$LOG4J2_CONFIG \
$AUTO_OPTS $CAREER_MODE_OPTS"
# shellcheck disable=SC2086
setsid ./launch_nanoforge_ss.sh > "$PROCESS_LOG" 2>&1 &
GAME_PID=$!
GAME_PGID=$(ps -o pgid= -p "$GAME_PID" 2>/dev/null | tr -d ' ' || true)
if [[ -z "$GAME_PGID" ]]; then
    GAME_PGID="$GAME_PID"
fi

echo "Game PID: $GAME_PID (PGID $GAME_PGID)"

log_contains() {
    grep -q -E "$1" "$GAME_LOG" "$PROCESS_LOG" 2>/dev/null
}

STOP_REASON="timeout"
for ((elapsed = 0; elapsed < TIMEOUT_SEC; elapsed++)); do
    sleep 1

    if ! kill -0 "$GAME_PID" 2>/dev/null && ! pgrep -g "$GAME_PGID" >/dev/null 2>&1; then
        STOP_REASON="process-exited"
        break
    fi
    if log_contains "$FATAL_LOG_PATTERN"; then
        STOP_REASON="fatal-marker"
        break
    fi
    if [[ -f "$SESSION_DONE" ]]; then
        STOP_REASON="session-done"
        sleep 2
        break
    fi
    if ((elapsed % 15 == 0)); then
        local_log_size=$(stat -c%s "$GAME_LOG" 2>/dev/null || echo 0)
        echo "[career] elapsed=${elapsed}s/${TIMEOUT_SEC}s log_bytes=$local_log_size"
    fi
done

echo "Stop reason: $STOP_REASON (elapsed ${elapsed}s)"
cleanup_game
trap - EXIT INT TERM

echo ""
echo "=== Log Analysis ($PHASE) ==="
PASS=true

if log_contains "$FATAL_LOG_PATTERN"; then
    echo "FAIL: fatal marker found"
    grep -n -E "$FATAL_LOG_PATTERN" "$GAME_LOG" "$PROCESS_LOG" 2>/dev/null | head -20 || true
    PASS=false
fi

if grep -q "\[ASTD\] Asteria Directorate loaded" "$GAME_LOG" 2>/dev/null; then
    echo "OK: ASTD onApplicationLoad marker found"
else
    echo "FAIL: ASTD onApplicationLoad marker not found"
    PASS=false
fi

if grep -q "\[ASTD-Career\] 自动化检查脚本已启动" "$GAME_LOG" 2>/dev/null; then
    echo "OK: career automation script started"
else
    echo "FAIL: career automation script start marker not found"
    PASS=false
fi

echo ""
if $PASS; then
    echo "=== Career Automation Launch PASSED ($STOP_REASON) ==="
    exit 0
else
    echo "=== Career Automation Launch FAILED ($STOP_REASON) ==="
    exit 1
fi
