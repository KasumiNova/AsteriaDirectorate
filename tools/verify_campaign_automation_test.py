#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path


def _load_verifier_module():
    verifier_path = Path(__file__).with_name("verify_campaign_automation.py")
    spec = importlib.util.spec_from_file_location("verify_campaign_automation", verifier_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"failed to load verifier module: {verifier_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _valid_telemetry(scenario: str, evidence_keys: tuple[str, ...]) -> dict:
    return {
        "scenario": scenario,
        "state": "Completed",
        "stage": "done",
        "failureCode": None,
        "frame": 1200,
        "stageStartFrame": 600,
        "evidence": {key: True for key in evidence_keys},
    }


class ValidateTelemetryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.verifier = _load_verifier_module()

    def test_valid_telemetry_passes_for_every_supported_scenario(self) -> None:
        for scenario, keys in self.verifier.SCENARIO_EVIDENCE_KEYS.items():
            with self.subTest(scenario=scenario):
                errors = self.verifier.validate_telemetry(_valid_telemetry(scenario, keys))
                self.assertEqual([], errors)

    def test_missing_top_level_fields_report_each_field(self) -> None:
        errors = self.verifier.validate_telemetry({"scenario": "campaign_terminal"})
        for field in ("state", "stage", "failureCode", "frame", "stageStartFrame", "evidence"):
                self.assertTrue(
                    any(error.startswith(f"{field}:") and "missing" in error for error in errors),
                    f"expected missing-field error for {field}, got {errors}",
                )

    def test_non_object_top_level_is_rejected(self) -> None:
        errors = self.verifier.validate_telemetry(["not", "an", "object"])
        self.assertEqual(1, len(errors))
        self.assertIn("top-level JSON object", errors[0])

    def test_unknown_scenario_is_rejected(self) -> None:
        data = _valid_telemetry("campaign_terminal", self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"])
        data["scenario"] = "campaign_unknown"
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any(error.startswith("scenario: unsupported") for error in errors), errors)

    def test_failed_state_reports_stage_and_failure_code(self) -> None:
        data = _valid_telemetry("campaign_terminal", self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"])
        data["state"] = "Failed"
        data["stage"] = "deliver"
        data["failureCode"] = "DELIVERY_TIMEOUT"
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(
            any("Failed" in error and "deliver" in error and "DELIVERY_TIMEOUT" in error for error in errors),
            errors,
        )

    def test_failed_state_requires_non_empty_failure_code(self) -> None:
        data = _valid_telemetry("campaign_terminal", self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"])
        data["state"] = "Failed"
        data["failureCode"] = None
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any(error.startswith("failureCode:") for error in errors), errors)

    def test_running_state_is_rejected_as_not_final(self) -> None:
        data = _valid_telemetry("campaign_ai_core", self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_ai_core"])
        data["state"] = "Running"
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any("Running" in error for error in errors), errors)

    def test_unknown_state_is_rejected(self) -> None:
        data = _valid_telemetry("campaign_ai_core", self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_ai_core"])
        data["state"] = "Aborted"
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any(error.startswith("state:") and "Aborted" in error for error in errors), errors)

    def test_completed_state_rejects_non_null_failure_code(self) -> None:
        data = _valid_telemetry("campaign_ai_core", self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_ai_core"])
        data["failureCode"] = "LEFTOVER"
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any(error.startswith("failureCode:") for error in errors), errors)

    def test_missing_evidence_key_is_reported_per_scenario(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_bounty_battle"]
        data = _valid_telemetry("campaign_bounty_battle", keys)
        del data["evidence"]["magicSucceeded"]
        errors = self.verifier.validate_telemetry(data)
        self.assertIn("evidence.magicSucceeded: required bool evidence missing", errors)

    def test_every_scenario_reports_each_missing_evidence_key(self) -> None:
        for scenario, keys in self.verifier.SCENARIO_EVIDENCE_KEYS.items():
            with self.subTest(scenario=scenario):
                data = _valid_telemetry(scenario, keys)
                data["evidence"] = {}
                errors = self.verifier.validate_telemetry(data)
                for key in keys:
                    self.assertIn(f"evidence.{key}: required bool evidence missing", errors)

    def test_evidence_bool_wrong_type_is_rejected(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_world_indevo"]
        for bad_value in (1, "true", None, [True]):
            with self.subTest(bad_value=bad_value):
                data = _valid_telemetry("campaign_world_indevo", keys)
                data["evidence"]["indEvoLoaded"] = bad_value
                errors = self.verifier.validate_telemetry(data)
                self.assertTrue(
                    any(error.startswith("evidence.indEvoLoaded: expected bool") for error in errors),
                    errors,
                )

    def test_evidence_false_is_rejected(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_mainline_smoke"]
        data = _valid_telemetry("campaign_mainline_smoke", keys)
        data["evidence"]["executiveCore"] = False
        errors = self.verifier.validate_telemetry(data)
        self.assertIn("evidence.executiveCore: expected true, got false", errors)

    def test_evidence_non_object_is_rejected(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"]
        data = _valid_telemetry("campaign_terminal", keys)
        data["evidence"] = "opened"
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any(error.startswith("evidence: expected JSON object") for error in errors), errors)

    def test_frame_must_be_strict_integer(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"]
        for bad_value in (True, 12.5, "1200"):
            with self.subTest(bad_value=bad_value):
                data = _valid_telemetry("campaign_terminal", keys)
                data["frame"] = bad_value
                errors = self.verifier.validate_telemetry(data)
                self.assertTrue(any(error.startswith("frame:") for error in errors), errors)

    def test_frame_before_stage_start_is_rejected(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"]
        data = _valid_telemetry("campaign_terminal", keys)
        data["frame"] = 100
        data["stageStartFrame"] = 600
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any(error.startswith("frame: expected >= stageStartFrame") for error in errors), errors)

    def test_empty_stage_is_rejected(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"]
        data = _valid_telemetry("campaign_terminal", keys)
        data["stage"] = "  "
        errors = self.verifier.validate_telemetry(data)
        self.assertTrue(any(error.startswith("stage:") for error in errors), errors)


class LogExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.verifier = _load_verifier_module()

    def _log_line(self, data: dict) -> str:
        return f"12345 [Thread-3] INFO astd  - [ASTD-CampaignAutomation] telemetry json={json.dumps(data)}"

    def test_extracts_latest_telemetry_from_log(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"]
        stale = _valid_telemetry("campaign_terminal", keys)
        stale["state"] = "Running"
        latest = _valid_telemetry("campaign_terminal", keys)
        log_text = "noise line\n" + self._log_line(stale) + "\nmore noise\n" + self._log_line(latest) + "\n"

        extracted = self.verifier.extract_latest_telemetry(log_text)

        self.assertEqual("Completed", extracted["state"])
        self.assertEqual(latest, extracted)

    def test_missing_marker_raises_clear_error(self) -> None:
        with self.assertRaises(ValueError) as ctx:
            self.verifier.extract_latest_telemetry("no automation lines here\n")
        self.assertIn("[ASTD-CampaignAutomation]", str(ctx.exception))

    def test_latest_marker_with_broken_json_fails_instead_of_falling_back(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"]
        good = self._log_line(_valid_telemetry("campaign_terminal", keys))
        broken = "[ASTD-CampaignAutomation] telemetry json={not valid json"
        with self.assertRaises(ValueError) as ctx:
            self.verifier.extract_latest_telemetry(good + "\n" + broken + "\n")
        self.assertIn("not valid JSON", str(ctx.exception))

    def test_marker_with_trailing_log_text_still_parses(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_terminal"]
        payload = json.dumps(_valid_telemetry("campaign_terminal", keys))
        line = f"[ASTD-CampaignAutomation] telemetry json={payload} trailing logger suffix"

        extracted = self.verifier.extract_latest_telemetry(line)

        self.assertEqual("campaign_terminal", extracted["scenario"])


class VerifyCliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.verifier = _load_verifier_module()

    def _run_main(self, argv: list[str]) -> tuple[int, str]:
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            rc = self.verifier.main(argv)
        return rc, stdout.getvalue()

    def test_valid_json_file_returns_zero(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_world_indevo"]
        with tempfile.TemporaryDirectory() as tmp:
            telemetry = Path(tmp) / "telemetry.json"
            telemetry.write_text(json.dumps(_valid_telemetry("campaign_world_indevo", keys)), encoding="utf-8")

            rc, output = self._run_main(["--telemetry", str(telemetry)])

        self.assertEqual(0, rc)
        self.assertIn("PASS", output)

    def test_invalid_json_file_returns_one_with_problem_output(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_bounty_battle"]
        with tempfile.TemporaryDirectory() as tmp:
            telemetry = Path(tmp) / "telemetry.json"
            data = _valid_telemetry("campaign_bounty_battle", keys)
            data["state"] = "Failed"
            data["failureCode"] = "BATTLE_LOST"
            telemetry.write_text(json.dumps(data), encoding="utf-8")

            rc, output = self._run_main(["--telemetry", str(telemetry)])

        self.assertEqual(1, rc)
        self.assertIn("FAIL", output)
        self.assertIn("BATTLE_LOST", output)

    def test_log_file_returns_zero_when_latest_payload_is_completed(self) -> None:
        keys = self.verifier.SCENARIO_EVIDENCE_KEYS["campaign_ai_core"]
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "starsector.log"
            payload = json.dumps(_valid_telemetry("campaign_ai_core", keys))
            log.write_text(
                f"boot noise\n[ASTD-CampaignAutomation] telemetry json={payload}\nshutdown noise\n",
                encoding="utf-8",
            )

            rc, output = self._run_main(["--log", str(log)])

        self.assertEqual(0, rc)
        self.assertIn("PASS", output)

    def test_log_file_without_marker_returns_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "starsector.log"
            log.write_text("nothing relevant\n", encoding="utf-8")

            rc, output = self._run_main(["--log", str(log)])

        self.assertEqual(1, rc)
        self.assertIn("telemetry marker missing", output)

    def test_missing_files_return_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            rc_json, output_json = self._run_main(["--telemetry", str(Path(tmp) / "missing.json")])
            rc_log, output_log = self._run_main(["--log", str(Path(tmp) / "missing.log")])

        self.assertEqual(1, rc_json)
        self.assertIn("telemetry file missing", output_json)
        self.assertEqual(1, rc_log)
        self.assertIn("log file missing", output_log)


if __name__ == "__main__":
    unittest.main()
