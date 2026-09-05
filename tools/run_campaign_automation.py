#!/usr/bin/env python3
"""Run ASTD campaign acceptance on a private save copy, then restart and verify reload.

No deployment, mod-list edits, baseline writes, shared-log truncation or global
process killing. Outputs live in build/campaign-automation/<runId>/.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import math
import os
import re
import shlex
import shutil
import signal
import struct
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from uuid import UUID, uuid4

from verify_campaign_automation import SCENARIO_EVIDENCE_KEYS, validate_telemetry

PREFIX = "astd.campaignAutomation."
AGENT_OWNER = "cn/kasuminova/astd/agent/"
TELEMETRY_MARKER = "[ASTD-CampaignAutomation] telemetry json="
CLASSPATH = (
    "janino.jar", "commons-compiler.jar", "commons-compiler-jdk.jar", "starfarer.api.jar",
    "starfarer_obf.jar", "jogg-0.0.7.jar", "jorbis-0.0.15.jar", "json.jar", "lwjgl.jar",
    "jinput.jar", "log4j-1.2.9.jar", "lwjgl_util.jar", "fs.sound_obf.jar", "fs.common_obf.jar",
    "xstream-1.4.21_miko.jar", "txw2-3.0.2.jar", "jaxb-api-2.4.0-b180830.0359.jar", "webp-imageio-0.1.6.jar",
)


class RunnerError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def tree_hashes(path: Path) -> dict[str, str]:
    result = {}
    for child in sorted(path.rglob("*")):
        if child.is_symlink() or (not child.is_dir() and not child.is_file()):
            raise RunnerError(f"refusing non-regular save entry: {child}")
        if child.is_file():
            result[child.relative_to(path).as_posix()] = sha256_file(child)
    return result


def require_directory(path: Path, label: str) -> Path:
    if not path.is_absolute() or not path.is_dir():
        raise RunnerError(f"{label} must name an existing absolute directory: {path}")
    return path.resolve()


def check_save(save_dir: Path) -> None:
    if not (save_dir / "descriptor.xml").is_file() or not any(
        (save_dir / name).is_file() for name in ("campaign.xml", "campaign.zip")
    ):
        raise RunnerError(f"save needs descriptor.xml and campaign.xml or campaign.zip: {save_dir}")
    tree_hashes(save_dir)


def read_game_json(path: Path) -> dict:
    # Starsector's configuration format explicitly permits hash comments and trailing commas.
    raw = path.read_text(encoding="utf-8-sig")
    raw = re.sub(r'"(?:\\.|[^"\\])*"|#[^\n]*',
                 lambda match: match[0] if match[0].startswith('"') else "", raw)
    raw = re.sub(r'"(?:\\.|[^"\\])*"|,(\s*[}\]])',
                 lambda match: match[0] if match[0].startswith('"') else match[1], raw)
    try:
        result = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RunnerError(f"invalid game configuration {path}: {exc}") from exc
    if not isinstance(result, dict):
        raise RunnerError(f"expected JSON object: {path}")
    return result


def assert_no_other_game() -> None:
    result = subprocess.run(["ps", "-eo", "pid=,comm=,args="], check=True, capture_output=True, text=True)
    matches = []
    for line in result.stdout.splitlines():
        fields = line.strip().split(None, 2)
        if len(fields) != 3 or int(fields[0]) == os.getpid():
            continue
        _, comm, args = fields
        is_java_game = comm.startswith("java") and (
            "com.fs.starfarer." in args or "starfarer_obf.jar" in args
            or "io.github.nanoforged.NanoForgeBootstrap" in args
        )
        launch_name = Path(args.split()[1]).name if len(args.split()) > 1 else ""
        is_launcher = (comm.startswith("launch_") or comm in {"sh", "bash"}) and launch_name in {
            "launch_injected_ss.sh", "launch_nanoforge_ss.sh", "starsector.sh",
        }
        if is_java_game or is_launcher:
            matches.append(line.strip())
    if matches:
        raise RunnerError("another Starsector game/launcher is running; close it explicitly first:\n" + "\n".join(matches))


def stop_group(process: subprocess.Popen) -> None:
    # start_new_session guarantees this PGID belongs to this launch, including surviving children.
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait()
        return
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        process.poll()
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            break
        time.sleep(0.05)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            print(f"process group {process.pid} exited during cleanup", file=sys.stderr)
    process.wait(timeout=5)


def write_json(path: Path, value: object) -> None:
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as stream:
        header = stream.read(33)
        stream.seek(-12, os.SEEK_END)
        ending = stream.read()
    if len(header) != 33 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR" or ending[4:8] != b"IEND":
        raise RunnerError(f"incomplete PNG screenshot: {path}")
    width, height = struct.unpack(">II", header[16:24])
    if width < 2 or height < 2:
        raise RunnerError(f"invalid screenshot dimensions: {path}: {width}x{height}")
    return width, height


class PhaseLog:
    def __init__(self, run_id: str, scenario: str, phase: str) -> None:
        self.run_id, self.scenario, self.phase = run_id, scenario, phase
        self.telemetry: dict | None = None
        self.requested = self.saved = self.captured = self.loaded = False
        self.positions: dict[Path, int] = {}
        self.pending: dict[Path, str] = {}

    def read(self, path: Path) -> None:
        if not path.exists():
            return
        position = self.positions.get(path, 0)
        if path.stat().st_size < position:
            raise RunnerError(f"run-local log unexpectedly truncated: {path}")
        with path.open("rb") as stream:
            stream.seek(position)
            chunk = stream.read()
            self.positions[path] = stream.tell()
        pending = self.pending.get(path, "") + chunk.decode("utf-8", "replace")
        lines = pending.split("\n")
        self.pending[path] = lines.pop()
        for line in lines:
            self.accept(line)

    def accept(self, line: str) -> None:
        if TELEMETRY_MARKER in line:
            raw = line.split(TELEMETRY_MARKER, 1)[1]
            try:
                record, _ = json.JSONDecoder().raw_decode(raw)
            except json.JSONDecodeError as exc:
                raise RunnerError(f"malformed telemetry in new log: {exc}") from exc
            if not isinstance(record, dict) or record.get("runId") != self.run_id or record.get("scenario") != self.scenario:
                return
            if record.get("phase", self.phase) != self.phase:
                return
            self.telemetry = record
            if record.get("state") == "Failed":
                raise RunnerError(f"{self.phase}: scenario Failed at {record.get('stage')}: {record.get('failureCode')}")
        requested = f"[ASTD-CampaignAutomation] checkpoint requested runId={self.run_id}"
        if re.search(re.escape(requested) + r"(?:\s|$)", line):
            self.requested = True
        identity = f"[ASTD-CampaignAutomation-IO] runId={self.run_id} scenario={self.scenario} phase={self.phase} "
        if identity in line:
            event = line.split(identity, 1)[1]
            self.saved |= event == "checkpoint saved"
            self.captured |= event == "capture completed"
            self.loaded |= event.startswith("loaded saveDir=")
            if event.startswith("io failed:"):
                raise RunnerError(event)
        if "[ASTD-Agent]" in line and ("failed" in line.lower() or "not found" in line.lower()):
            raise RunnerError(line)

    def ready(self) -> bool:
        if not self.loaded or self.telemetry is None:
            return False
        if self.phase == "run":
            if not (self.requested and self.saved) or self.telemetry.get("state") != "Completed":
                return False
        else:
            if self.telemetry.get("state") != "Completed":
                return False
            if self.telemetry.get("stage") != "reload.completed":
                raise RunnerError("reload requires stage=reload.completed, not a first-phase completion")
            evidence = self.telemetry.get("evidence", {})
            for key in ("persisted", "checkpointIdentity", "businessStateRestored"):
                if evidence.get(key) is not True:
                    raise RunnerError(f"reload evidence.{key} must be true")
        errors = validate_telemetry(self.telemetry)
        if errors:
            raise RunnerError("telemetry verification failed: " + "; ".join(errors))
        return True


class CampaignRunner:
    def __init__(self, args: argparse.Namespace) -> None:
        self.repo = Path(__file__).resolve().parents[1]
        self.game = require_directory(args.game_dir, "--game-dir")
        self.source = require_directory(args.save_dir, "--save-dir")
        check_save(self.source)
        try:
            self.run_id = str(UUID(args.run_id))
        except ValueError as exc:
            raise RunnerError("--run-id must be a UUID") from exc
        if self.run_id != args.run_id:
            raise RunnerError("--run-id must be a canonical lowercase UUID")
        self.scenario = args.scenario
        self.timeout = args.timeout
        if not math.isfinite(self.timeout) or self.timeout <= 0:
            raise RunnerError("--timeout must be finite and positive")
        self.base = self.repo / "build/campaign-automation"
        self.root = (args.output_dir.resolve() if args.output_dir is not None else self.base / self.run_id)
        if not self.root.is_absolute():
            raise RunnerError("--output-dir must be absolute")
        if self.root.exists() or self.root.is_symlink():
            raise RunnerError(f"refusing existing run workspace: {self.root}")
        if self.root.resolve().is_relative_to(self.source):
            raise RunnerError("source save cannot contain the run workspace")
        if self.root.resolve() != self.root:
            raise RunnerError("campaign workspace cannot traverse symlinks")
        self.save = self.root / "save"
        self.agent = (args.agent_jar or self.repo / "build/libs/AsteriaDirectorate-1.0-SNAPSHOT-acceptance-agent.jar").resolve()
        if not self.agent.is_file():
            raise RunnerError(f"agent jar missing: {self.agent}; run ./gradlew acceptanceAgentJar (no deployment is performed)")
        try:
            with zipfile.ZipFile(self.agent) as archive:
                required = {AGENT_OWNER + name + ".class" for name in (
                    "CampaignAutomationIo", "AsteriaDevStorageAcceptanceAgent", "AsteriaTitleScreenAdvanceTransformer",
                    "AsteriaDevStorageAcceptanceTitleHook",
                )} | {"org/objectweb/asm/ClassReader.class", "org/objectweb/asm/commons/AdviceAdapter.class"}
                if not required <= set(archive.namelist()):
                    raise RunnerError("agent jar lacks campaign IO or bundled ASM; rebuild acceptanceAgentJar")
        except zipfile.BadZipFile as exc:
            raise RunnerError(f"invalid agent jar: {self.agent}") from exc
        self.enabled_file = self.game / "mods/enabled_mods.json"
        self.enabled = read_game_json(self.enabled_file).get("enabledMods")
        if not isinstance(self.enabled, list) or not all(isinstance(mod, str) for mod in self.enabled):
            raise RunnerError("enabledMods must be a list of mod IDs")
        for required_mod in ("asteria_directorate", "IndEvo"):
            if required_mod not in self.enabled:
                raise RunnerError(f"{required_mod} must already be enabled; runner will not modify enabled_mods.json")
        self.mod_files: list[Path] = []
        resolved_ids = set()
        for info in sorted((self.game / "mods").glob("*/mod_info.json")):
            spec = read_game_json(info)
            if spec.get("id") not in self.enabled:
                continue
            if spec["id"] in resolved_ids:
                raise RunnerError(f"duplicate installed mod ID: {spec['id']}")
            resolved_ids.add(spec["id"])
            self.mod_files.append(info)
            for jar in spec.get("jars", []):
                file = info.parent / jar
                if not file.is_file():
                    raise RunnerError(f"enabled mod dependency missing: {file}")
                self.mod_files.append(file)
            self.mod_files.extend(path for path in (info.parent / "data/config").rglob("*") if path.is_file())
        missing = set(self.enabled) - resolved_ids
        if missing:
            raise RunnerError(f"enabled mod installations missing: {sorted(missing)}")
        self.launcher = next((self.game / name for name in
                              ("launch_injected_ss.sh", "launch_nanoforge_ss.sh", "starsector.sh")
                              if (self.game / name).is_file()), None)
        self.java = args.java or self.game / "zulu25_linux/bin/java"
        self.optimizer = self.game / "mods/ssoptimizer/jars/SSOptimizer.jar"
        if self.launcher is not None:
            if not os.access(self.launcher, os.X_OK):
                raise RunnerError(f"launcher is not executable: {self.launcher}")
            launcher_text = self.launcher.read_text(encoding="utf-8")
            if "ssoptimizer.automation.enabled=true" in launcher_text:
                raise RunnerError("launcher enables the competing SSOptimizer automation driver")
        else:
            # Exact Java-25/SSOptimizer path used by tools/smoke_test_game_launch.sh launch_campaign_acceptance_direct.
            for path in (self.java, self.optimizer, *(self.game / name for name in CLASSPATH)):
                if not path.is_file():
                    raise RunnerError(f"direct Java-25 launch dependency missing: {path}")
            result = subprocess.run([str(self.java), "-version"], check=True, capture_output=True, text=True)
            if not re.search(r'version "25(?:[.\"]|$)', result.stderr + result.stdout):
                raise RunnerError(f"direct campaign launcher requires Java 25: {self.java}")
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "EXTRA_JVM_FLAGS"):
            if os.environ.get(key, "").strip():
                raise RunnerError(f"unset inherited {key}; explicit isolated JVM configuration is required")
        self.manifest: dict = {"runId": self.run_id, "scenario": self.scenario, "result": "Running", "phases": []}
        self.named = args.named_jars
        if self.named is not None:
            self.named = require_directory(self.named, "--named-jars")
        self.resolution = args.resolution

    def prepare(self) -> None:
        self.root.mkdir()
        original = tree_hashes(self.source)
        shutil.copytree(self.source, self.save)
        if original != tree_hashes(self.save) or original != tree_hashes(self.source):
            raise RunnerError("save changed while being copied; the baseline must be idle")
        self.manifest.update({
            "createdAt": time.time(), "sourceSave": str(self.source), "saveCopy": str(self.save),
            "sourceHashes": original, "enabledMods": self.enabled,
            "gameDir": str(self.game), "timeoutSeconds": self.timeout,
        })
        files = [self.agent, self.enabled_file, *self.mod_files, *sorted(self.game.glob("*.jar")),
                 *sorted(path for path in (self.game / "data/config").rglob("*") if path.is_file())]
        files += [self.launcher] if self.launcher is not None else [self.java, self.optimizer, self.repo / "tools/smoke_test_game_launch.sh"]
        if self.named is not None:
            files += sorted(self.named.rglob("*.jar"))
        for config in ("log4j.properties", "compiler_directives.txt", "data/config/settings.json"):
            if (self.game / config).is_file():
                files.append(self.game / config)
        files += [Path(__file__).resolve(), Path(__file__).with_name("verify_campaign_automation.py").resolve()]
        self.manifest["inputs"] = {str(path): sha256_file(path) for path in sorted(set(files))}
        write_json(self.root / "manifest.json", self.manifest)

    def command(self, phase: str, output: Path) -> tuple[list[str], dict[str, str]]:
        props = {
            PREFIX + "enabled": "true", PREFIX + "scenario": self.scenario, PREFIX + "runId": self.run_id,
            PREFIX + "saveDir": str(self.save), PREFIX + "outputDir": str(output), PREFIX + "phase": phase,
            "com.fs.starfarer.settings.paths.saves": str(self.root),
            "com.fs.starfarer.settings.paths.logs": str(output),
            "com.fs.starfarer.settings.paths.screenshots": str(output),
            "ssoptimizer.launcher.autostart": "true", "ssoptimizer.launcher.autostart.res": self.resolution,
            "ssoptimizer.launcher.autostart.fullscreen": "false", "ssoptimizer.launcher.autostart.sound": "false",
            "startRes": self.resolution, "startFS": "false", "startSound": "false",
            "ssoptimizer.renderthread.enable": "false", "ssoptimizer.automation.enabled": "false",
            "log4j.configuration": (output / "log4j.properties").as_uri(),
        }
        # A private log4j destination preserves shared logs even when the game's own log path is overridden by a launcher.
        (output / "log4j.properties").write_text(
            "log4j.rootLogger=INFO, CONSOLE, FILE\n"
            "log4j.appender.CONSOLE=org.apache.log4j.ConsoleAppender\n"
            "log4j.appender.CONSOLE.layout=org.apache.log4j.PatternLayout\n"
            "log4j.appender.CONSOLE.layout.ConversionPattern=%d %-5p %c - %m%n\n"
            "log4j.appender.FILE=org.apache.log4j.FileAppender\n"
            f"log4j.appender.FILE.File={output / 'game.log'}\n"
            "log4j.appender.FILE.Append=true\n"
            "log4j.appender.FILE.layout=org.apache.log4j.PatternLayout\n"
            "log4j.appender.FILE.layout.ConversionPattern=%d %-5p %c - %m%n\n", encoding="utf-8")
        options = [f"-D{key}={value}" for key, value in props.items()]
        env = os.environ.copy()
        env["mesa_glthread"] = "false"
        if self.launcher is not None:
            launcher_options = [f"-javaagent:{self.agent}", *options]
            env["JAVA_TOOL_OPTIONS"] = shlex.join(launcher_options)
            env["EXTRA_JVM_FLAGS"] = shlex.join(options)
            command = [str(self.launcher)]
        else:
            command = [str(self.java), f"-javaagent:{self.optimizer}", f"-javaagent:{self.agent}",
                       "-Dfile.encoding=UTF-8", "-noverify", "-XX:+UnlockDiagnosticVMOptions",
                       "-XX:+DisableExplicitGC", "-XX:+ParallelRefProcEnabled", "-XX:+UseZGC",
                       "-XX:ReservedCodeCacheSize=256m", "-Djdk.xml.maxElementDepth=10000",
                       "-XX:-BytecodeVerificationLocal", "-XX:-BytecodeVerificationRemote",
                       "-Djava.util.Arrays.useLegacyMergeSort=true", "--enable-preview",
                       "--enable-native-access=ALL-UNNAMED", "-Xms4g", "-Xmx12g", "-Xss4m",
                       "-Dcom.fs.starfarer.settings.paths.mods=./mods", "-Djava.library.path=./native/linux",
                       "-Dssoptimizer.font.ttf.enable=true", "-Dcom.fs.starfarer.settings.linux=true"]
            for package in ("java.base/sun.nio.ch", "java.base/java.nio", "java.base/java.util",
                            "java.base/java.util.concurrent", "java.base/java.util.concurrent.locks",
                            "java.base/jdk.internal.ref", "java.base/java.lang.reflect", "java.base/java.lang.ref",
                            "java.base/java.text", "java.desktop/java.awt.font", "java.desktop/java.awt"):
                command += [f"--add-opens={package}=ALL-UNNAMED"]
            for package in ("java.base/jdk.internal.ref", "java.base/jdk.internal.misc", "java.base/sun.nio.ch"):
                command += [f"--add-exports={package}=ALL-UNNAMED"]
            command += options + ["-classpath", ":".join(CLASSPATH), "com.fs.starfarer.StarfarerLauncher"]
        self.manifest["phases"].append({"phase": phase, "command": command, "properties": props,
                                       "javaToolOptions": env.get("JAVA_TOOL_OPTIONS"), "result": "Running"})
        return command, env

    def run_phase(self, phase: str) -> dict:
        assert_no_other_game()
        output = self.root / phase
        output.mkdir()
        command, env = self.command(phase, output)
        record = self.manifest["phases"][-1]
        logs = PhaseLog(self.run_id, self.scenario, phase)
        shared_log = self.game / "starsector.log"
        shared_start = shared_log.stat().st_size if shared_log.is_file() else 0
        logs.positions[shared_log] = shared_start
        process = None
        started = time.monotonic()
        before = tree_hashes(self.save)
        try:
            with (output / "process.log").open("xb") as stream:
                process = subprocess.Popen(command, cwd=self.game, env=env, stdout=stream,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                record["pid"] = record["pgid"] = process.pid
                write_json(self.root / "manifest.json", self.manifest)
                while True:
                    for path in (output / "process.log", output / "game.log", shared_log):
                        logs.read(path)
                    if logs.telemetry is not None:
                        write_json(output / "telemetry.json", logs.telemetry)
                    code = process.poll()
                    if code not in (None, 0):
                        raise RunnerError(f"{phase} JVM exited with code {code}")
                    if logs.ready():
                        break
                    if code is not None:
                        raise RunnerError(f"{phase} process exited without all required telemetry/IO markers")
                    if time.monotonic() - started >= self.timeout:
                        raise RunnerError(f"{phase} timed out; missing valid telemetry/checkpoint/screenshot markers")
                    time.sleep(0.1)
            png_size(output / ("checkpoint.png" if phase == "run" else "capture.png"))
            if phase == "run":
                after = tree_hashes(self.save)
                if before == after or all(before.get(key) == after.get(key) for key in ("campaign.xml", "campaign.zip")):
                    raise RunnerError("checkpoint marker exists but campaign data did not change on disk")
                check_save(self.save)
                self.manifest["checkpointHashes"] = after
            record["result"] = "CheckpointSaved" if phase == "run" else "Completed"
            return logs.telemetry
        except BaseException as exc:
            record["result"], record["error"] = "FAIL", str(exc)
            raise
        finally:
            if process is not None:
                stop_group(process)
                record["exitCode"] = process.returncode
            record["elapsedSeconds"] = round(time.monotonic() - started, 3)
            record["files"] = tree_hashes(output)
            if logs.telemetry is not None:
                write_json(output / "telemetry.json", logs.telemetry)
            write_json(self.root / "manifest.json", self.manifest)

    def run(self) -> None:
        assert_no_other_game()
        self.prepare()
        try:
            self.run_phase("run")
            final = self.run_phase("reload")
            if tree_hashes(self.source) != self.manifest["sourceHashes"]:
                raise RunnerError("baseline changed during run")
            if sha256_file(self.enabled_file) != self.manifest["inputs"][str(self.enabled_file)]:
                raise RunnerError("enabled_mods.json changed during run")
            write_json(self.root / "telemetry.json", final)
            self.manifest["result"] = "PASS"
        except BaseException as exc:
            self.manifest["result"], self.manifest["error"] = "FAIL", str(exc)
            raise
        finally:
            self.manifest["finalSaveHashes"] = tree_hashes(self.save)
            write_json(self.root / "manifest.json", self.manifest)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--game-dir", type=Path, default=Path("/mnt/store/Games/Starsector098-linux"))
    parser.add_argument("--save-dir", required=True, type=Path, help="explicit absolute immutable baseline save directory")
    parser.add_argument("--scenario", required=True, choices=sorted(SCENARIO_EVIDENCE_KEYS))
    parser.add_argument("--run-id", default=str(uuid4()))
    parser.add_argument("--agent-jar", type=Path)
    parser.add_argument("--output-dir", type=Path, help="absolute run workspace; defaults to build/campaign-automation/<runId>")
    parser.add_argument("--java", type=Path, help="Java 25 for the direct SSOptimizer agent launch path")
    parser.add_argument("--named-jars", type=Path,
                        default=Path("/home/hikari_nova/VSCodeProjects/SourceSector/build/named-game-jars"),
                        help="named source/jar tree to hash as reference inputs")
    parser.add_argument("--timeout", type=float, default=600, help="timeout per JVM phase, in seconds")
    parser.add_argument("--resolution", default="1920x1080", choices=("1280x720", "1920x1080", "2560x1440"))
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    previous_handler = signal.getsignal(signal.SIGTERM)

    def interrupted(signum, frame):
        raise RunnerError(f"runner interrupted by signal {signum}")

    signal.signal(signal.SIGTERM, interrupted)
    try:
        base = Path(__file__).resolve().parents[1] / "build/campaign-automation"
        base.mkdir(parents=True, exist_ok=True)
        with (base / ".runner.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise RunnerError("another campaign runner owns the workspace lock") from exc
            runner = CampaignRunner(args)
            runner.run()
        print(f"PASS ASTD campaign automation runId={runner.run_id} scenario={runner.scenario}\n- workspace: {runner.root}")
        return 0
    except (RunnerError, OSError, ValueError, subprocess.SubprocessError, KeyboardInterrupt) as exc:
        print(f"FAIL ASTD campaign automation: {exc}", file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


if __name__ == "__main__":
    raise SystemExit(main())
