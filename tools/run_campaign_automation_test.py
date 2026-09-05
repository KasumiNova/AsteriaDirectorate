#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_campaign_automation as runner

ROOT = Path(__file__).resolve().parents[1]

FAKE_GAME = r'''#!/usr/bin/env python3
import json, os, shlex, struct, sys, time, zlib
from pathlib import Path
props = dict(token[2:].split("=", 1) for token in shlex.split(os.environ["JAVA_TOOL_OPTIONS"]) if token.startswith("-D"))
prefix = "astd.campaignAutomation."
run_id, scenario, phase = [props[prefix + key] for key in ("runId", "scenario", "phase")]
output, save = [Path(props[prefix + key]) for key in ("outputDir", "saveDir")]
mode = Path("mode").read_text()
identity = f"[ASTD-CampaignAutomation-IO] runId={run_id} scenario={scenario} phase={phase} "
print(identity + "loaded saveDir=" + str(save), flush=True)
if mode == "timeout":
    time.sleep(60)
    sys.exit(0)
if mode == "nonzero":
    sys.exit(3)
if mode == "stale":
    run_id = "00000000-0000-0000-0000-000000000000"
record = {
    "runId": run_id, "scenario": scenario, "phase": phase, "state": "Completed",
    "stage": "checkpoint.completed" if phase == "run" else "reload.completed",
    "failureCode": None, "frame": 200, "stageStartFrame": 100,
    "evidence": {key: True for key in Path("evidence").read_text().splitlines()},
}
if phase == "run":
    print(f"[ASTD-CampaignAutomation] checkpoint requested runId={run_id}", flush=True)
    if mode != "no_save":
        (save / "campaign.xml").write_text("saved:" + run_id)
    print(identity + "checkpoint saved", flush=True)
else:
    if (save / "campaign.xml").read_text() != "saved:" + run_id:
        sys.exit(4)
    record["evidence"].update(persisted=True, checkpointIdentity=True, businessStateRestored=True)
    if mode == "bad_reload":
        record["evidence"]["businessStateRestored"] = False
    print(identity + "capture completed", flush=True)
if mode != "no_screenshot":
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    image = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
    image += chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\x00\xff\x00" * 2)) + chunk(b"IEND", b"")
    (output / ("checkpoint.png" if phase == "run" else "capture.png")).write_bytes(image)
if mode != "missing":
    print("[ASTD-CampaignAutomation] telemetry json=" + json.dumps(record), flush=True)
'''


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.base = Path(self.temp.name)
        self.game = self.base / "fake game"
        self.game.mkdir()
        for mod in ("asteria_directorate", "IndEvo"):
            directory = self.game / "mods" / mod
            directory.mkdir(parents=True)
            (directory / "mod_info.json").write_text(json.dumps({"id": mod, "jars": []}))
        (self.game / "mods/enabled_mods.json").write_text(json.dumps({"enabledMods": ["asteria_directorate", "IndEvo"]}))
        (self.game / "launch_injected_ss.sh").write_text(FAKE_GAME)
        (self.game / "launch_injected_ss.sh").chmod(0o755)
        (self.game / "mode").write_text("pass")
        (self.game / "evidence").write_text("\n".join(runner.SCENARIO_EVIDENCE_KEYS["campaign_world_indevo"]))
        (self.game / "starsector.log").write_text("baseline log must be retained\n")
        self.source = self.base / "baseline"
        self.source.mkdir()
        (self.source / "descriptor.xml").write_text("descriptor")
        (self.source / "campaign.xml").write_text("baseline campaign")
        (self.source / "other").mkdir()
        (self.source / "other/mod-data.json").write_text("nested mod save")
        self.agent = self.base / "agent.jar"
        with zipfile.ZipFile(self.agent, "w") as jar:
            for name in ("CampaignAutomationIo", "AsteriaDevStorageAcceptanceAgent", "AsteriaTitleScreenAdvanceTransformer", "AsteriaDevStorageAcceptanceTitleHook"):
                jar.writestr(runner.AGENT_OWNER + name + ".class", b"test fixture")
            for name in ("org/objectweb/asm/ClassReader.class", "org/objectweb/asm/commons/AdviceAdapter.class"):
                jar.writestr(name, b"test fixture")
        self.run_id = str(uuid4())
        self.output = ROOT / "build/campaign-automation" / self.run_id
        self.env = {key: value for key, value in os.environ.items() if key not in {
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "EXTRA_JVM_FLAGS",
        }}

    def tearDown(self):
        if self.output.exists():
            shutil.rmtree(self.output)
        self.temp.cleanup()

    def command(self):
        return [sys.executable, str(ROOT / "tools/run_campaign_automation.py"),
                "--game-dir", str(self.game), "--save-dir", str(self.source),
                "--scenario", "campaign_world_indevo", "--run-id", self.run_id,
                "--agent-jar", str(self.agent), "--timeout", "0.8"]

    def execute(self, mode="pass"):
        (self.game / "mode").write_text(mode)
        return subprocess.run(self.command(), cwd=ROOT, env=self.env, capture_output=True, text=True, timeout=15)

    def test_full_two_process_roundtrip_preserves_inputs_and_hashes_logs(self):
        before = runner.tree_hashes(self.source)
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS", result.stdout)
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual("PASS", manifest["result"])
        self.assertNotEqual(manifest["phases"][0]["pid"], manifest["phases"][1]["pid"])
        self.assertEqual(before, runner.tree_hashes(self.source))
        self.assertEqual("nested mod save", (self.output / "save/other/mod-data.json").read_text())
        self.assertEqual("baseline log must be retained\n", (self.game / "starsector.log").read_text())
        for phase in ("run", "reload"):
            entry = next(item for item in manifest["phases"] if item["phase"] == phase)
            self.assertEqual(runner.sha256_file(self.output / phase / "process.log"), entry["files"]["process.log"])
            self.assertEqual("false", entry["properties"]["ssoptimizer.automation.enabled"])
            self.assertEqual(str(self.output / "save"), entry["properties"][runner.PREFIX + "saveDir"])
        telemetry = json.loads((self.output / "telemetry.json").read_text())
        self.assertEqual("reload.completed", telemetry["stage"])
        self.assertTrue(telemetry["evidence"]["businessStateRestored"])

    def test_no_reuse_of_existing_workspace(self):
        self.output.mkdir(parents=True)
        marker = self.output / "keep"
        marker.write_text("keep")
        result = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("existing run workspace", result.stderr)
        self.assertEqual("keep", marker.read_text())

    def test_missing_mod_fails_without_mod_list_edit(self):
        path = self.game / "mods/enabled_mods.json"
        path.write_text('{"enabledMods":["asteria_directorate"]}')
        before = path.read_bytes()
        result = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("IndEvo must already be enabled", result.stderr)
        self.assertEqual(before, path.read_bytes())

    def test_explicit_save_required(self):
        args = self.command()
        index = args.index("--save-dir")
        del args[index:index + 2]
        result = subprocess.run(args, env=self.env, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("--save-dir", result.stderr)

    def test_incomplete_or_stale_results_fail(self):
        for mode in ("stale", "missing", "no_save", "no_screenshot", "bad_reload", "nonzero", "timeout"):
            with self.subTest(mode=mode):
                self.run_id = str(uuid4())
                self.output = ROOT / "build/campaign-automation" / self.run_id
                result = self.execute(mode)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertNotIn("PASS", result.stdout)
                manifest = json.loads((self.output / "manifest.json").read_text())
                self.assertEqual("FAIL", manifest["result"])
                self.assertTrue((self.output / "run/process.log").exists())
                shutil.rmtree(self.output)

    def test_symlink_in_baseline_is_rejected(self):
        (self.source / "link").symlink_to(self.base)
        result = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("non-regular save entry", result.stderr)

    def test_unrelated_process_is_not_terminated(self):
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
        try:
            result = self.execute("timeout")
            self.assertNotEqual(0, result.returncode)
            self.assertIsNone(process.poll())
        finally:
            process.terminate()
            process.wait(timeout=5)

    def test_other_game_is_refused_and_not_terminated(self):
        program = self.base / "java"
        program.write_text('#!/bin/sh\nexec -a java sleep 30\n')
        # The kernel process name is set without starting any real game or JVM.
        code = "import ctypes,time; ctypes.CDLL(None).prctl(15,b'java',0,0,0); time.sleep(30)"
        process = subprocess.Popen([sys.executable, "-c", code, "com.fs.starfarer.StarfarerLauncher"], start_new_session=True)
        try:
            import time
            time.sleep(0.1)
            result = self.execute()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("another Starsector", result.stderr)
            self.assertIsNone(process.poll())
        finally:
            process.terminate()
            process.wait(timeout=5)


class LogContractTest(unittest.TestCase):
    def test_foreign_identity_cannot_satisfy_io(self):
        log = runner.PhaseLog(str(uuid4()), "campaign_world_indevo", "run")
        log.accept("[ASTD-CampaignAutomation-IO] runId=other scenario=campaign_world_indevo phase=run checkpoint saved")
        self.assertFalse(log.saved)
        self.assertFalse(log.ready())

    def test_running_checkpoint_is_not_final_success(self):
        log = runner.PhaseLog(str(uuid4()), "campaign_world_indevo", "reload")
        log.loaded = log.captured = True
        log.telemetry = {"state": "Running", "stage": "checkpoint.requested"}
        self.assertFalse(log.ready())

    def test_bad_json_is_explicit_failure(self):
        log = runner.PhaseLog(str(uuid4()), "campaign_world_indevo", "run")
        with self.assertRaises(runner.RunnerError):
            log.accept(runner.TELEMETRY_MARKER + "{broken}")


if __name__ == "__main__":
    unittest.main()
