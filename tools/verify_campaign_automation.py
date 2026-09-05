#!/usr/bin/env python3
"""Verify ASTD campaign automation telemetry against the strict acceptance contract.

Telemetry contract (produced by the Kotlin campaign automation, consumed here):

- Top-level object with exactly these required fields:
  scenario, state, stage, failureCode, frame, stageStartFrame, evidence.
- scenario: one of the supported campaign scenario ids (see SCENARIO_EVIDENCE_KEYS).
- state: must be "Completed". "Failed"/"Running"/missing are explicit errors;
  when Failed, failureCode and stage describe where the run stopped.
- stage: non-empty string naming the stage the run reached.
- failureCode: null when Completed; non-empty string when Failed.
- frame / stageStartFrame: non-negative integers, frame >= stageStartFrame.
- evidence: object holding the per-scenario bool keys, all required and all true.

The script either validates a telemetry JSON file directly (--telemetry) or
extracts the latest `[ASTD-CampaignAutomation] telemetry json={...}` payload
from a starsector log (--log). Exit code 0 on PASS, 1 on any problem.
Pure standard library; no third-party dependencies.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SCENARIO_WORLD_INDEVO = "campaign_world_indevo"
SCENARIO_BOUNTY_BATTLE = "campaign_bounty_battle"
SCENARIO_TERMINAL = "campaign_terminal"
SCENARIO_MAINLINE_SMOKE = "campaign_mainline_smoke"
SCENARIO_AI_CORE = "campaign_ai_core"

SCENARIO_EVIDENCE_KEYS: dict[str, tuple[str, ...]] = {
    SCENARIO_WORLD_INDEVO: (
        "mainSystem",
        "starfallSystem",
        "asterSystem",
        "markets",
        "conditions",
        "indEvoLoaded",
        "mainArtillery",
        "starfallArtillery",
        "watchtowers",
        "idempotent",
    ),
    SCENARIO_BOUNTY_BATTLE: (
        "accepted",
        "enteredBattle",
        "enemiesDestroyed",
        "magicSucceeded",
        "assetCollected",
        "settled",
        "rewardGranted",
    ),
    SCENARIO_TERMINAL: (
        "opened",
        "selected",
        "accepted",
        "tracked",
        "delivered",
        "closed",
    ),
    SCENARIO_MAINLINE_SMOKE: (
        "prologue",
        "chapterOne",
        "chapterTwo",
        "archiveChoice",
        "executiveCore",
        "infiniteAvailable",
    ),
    SCENARIO_AI_CORE: (
        "coreSpecs",
        "rewardCargo",
        "combatCore",
        "adminCore",
        "persisted",
        "noDuplicate",
    ),
}

TOP_LEVEL_FIELDS = ("scenario", "state", "stage", "failureCode", "frame", "stageStartFrame", "evidence")

STATE_COMPLETED = "Completed"
STATE_FAILED = "Failed"
STATE_RUNNING = "Running"
KNOWN_STATES = (STATE_COMPLETED, STATE_FAILED, STATE_RUNNING)

LOG_MARKER = "[ASTD-CampaignAutomation]"
LOG_PATTERN = re.compile(r"\[ASTD-CampaignAutomation]\s+telemetry\s+json=(?P<json>\{)")


def extract_latest_telemetry(log_text: str) -> dict:
    """Return the latest telemetry JSON payload embedded in a starsector log.

    Raises ValueError with a clear message when no marker exists or the latest
    marker payload is not valid JSON.
    """
    latest: tuple[int, dict] | None = None
    latest_error: tuple[int, str] | None = None
    decoder = json.JSONDecoder()
    for line_number, line in enumerate(log_text.splitlines(), start=1):
        match = LOG_PATTERN.search(line)
        if match is None:
            continue
        payload_start = match.start("json")
        try:
            parsed, _ = decoder.raw_decode(line[payload_start:])
        except json.JSONDecodeError as exc:
            latest_error = (line_number, str(exc))
            latest = None
            continue
        if not isinstance(parsed, dict):
            latest_error = (line_number, "telemetry payload is not a JSON object")
            latest = None
            continue
        latest = (line_number, parsed)
        latest_error = None

    if latest is not None:
        return latest[1]
    if latest_error is not None:
        raise ValueError(
            f"{LOG_MARKER} telemetry payload at log line {latest_error[0]} is not valid JSON: {latest_error[1]}",
        )
    raise ValueError(f"{LOG_MARKER} telemetry marker missing in log")


def _check_frame_value(data: dict, key: str, errors: list[str]) -> int | None:
    value = data.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        errors.append(f"{key}: expected non-negative integer, got {value!r}")
        return None
    if value < 0:
        errors.append(f"{key}: expected non-negative integer, got {value!r}")
        return None
    return value


def validate_telemetry(data: object) -> list[str]:
    """Validate a telemetry object against the contract; return all problems found."""
    errors: list[str] = []
    if not isinstance(data, dict):
        return [f"telemetry: expected top-level JSON object, got {type(data).__name__}"]

    for field in TOP_LEVEL_FIELDS:
        if field not in data:
            errors.append(f"{field}: required field missing")

    scenario = data.get("scenario")
    if scenario is not None and scenario not in SCENARIO_EVIDENCE_KEYS:
        errors.append(
            f"scenario: unsupported {scenario!r}, expected one of {sorted(SCENARIO_EVIDENCE_KEYS)}",
        )

    state = data.get("state")
    if "state" in data:
        if state == STATE_COMPLETED:
            pass
        elif state == STATE_FAILED:
            errors.append(
                f"state: run reported Failed at stage {data.get('stage')!r} "
                f"with failureCode {data.get('failureCode')!r}",
            )
        elif state == STATE_RUNNING:
            errors.append(f"state: run still Running at stage {data.get('stage')!r}, telemetry is not final")
        elif state not in KNOWN_STATES:
            errors.append(f"state: expected 'Completed', got {state!r}")

    stage = data.get("stage")
    if "stage" in data and (not isinstance(stage, str) or not stage.strip()):
        errors.append(f"stage: expected non-empty string, got {stage!r}")

    if "failureCode" in data:
        failure_code = data.get("failureCode")
        if state == STATE_COMPLETED and failure_code is not None:
            errors.append(f"failureCode: expected null when state is Completed, got {failure_code!r}")
        if state == STATE_FAILED and (not isinstance(failure_code, str) or not failure_code.strip()):
            errors.append(f"failureCode: expected non-empty string when state is Failed, got {failure_code!r}")

    frame = _check_frame_value(data, "frame", errors) if "frame" in data else None
    stage_start_frame = _check_frame_value(data, "stageStartFrame", errors) if "stageStartFrame" in data else None
    if frame is not None and stage_start_frame is not None and frame < stage_start_frame:
        errors.append(f"frame: expected >= stageStartFrame ({stage_start_frame}), got {frame}")

    if "evidence" in data:
        evidence = data.get("evidence")
        if not isinstance(evidence, dict):
            errors.append(f"evidence: expected JSON object, got {type(evidence).__name__}")
        elif scenario in SCENARIO_EVIDENCE_KEYS:
            for key in SCENARIO_EVIDENCE_KEYS[scenario]:
                if key not in evidence:
                    errors.append(f"evidence.{key}: required bool evidence missing")
                    continue
                value = evidence[key]
                if not isinstance(value, bool):
                    errors.append(f"evidence.{key}: expected bool, got {type(value).__name__} ({value!r})")
                elif value is not True:
                    errors.append(f"evidence.{key}: expected true, got false")

    return errors


def _print_result(data: object, errors: list[str], source: str) -> None:
    print(f"- source: {source}")
    if isinstance(data, dict):
        print(f"- scenario: {data.get('scenario')}")
        print(f"- state: {data.get('state')}")
        print(f"- stage: {data.get('stage')}")
        print(f"- failureCode: {data.get('failureCode')}")
        print(f"- frame: {data.get('frame')}")
        print(f"- stageStartFrame: {data.get('stageStartFrame')}")
        evidence = data.get("evidence")
        scenario = data.get("scenario")
        if isinstance(evidence, dict) and scenario in SCENARIO_EVIDENCE_KEYS:
            for key in SCENARIO_EVIDENCE_KEYS[scenario]:
                print(f"- evidence.{key}: {evidence.get(key)}")


def verify(telemetry_path: Path | None = None, log_path: Path | None = None) -> int:
    """Validate telemetry from a JSON file or a starsector log; return 0/1."""
    if log_path is not None:
        try:
            log_text = log_path.read_text(encoding="utf-8", errors="replace")
        except FileNotFoundError:
            print(f"FAIL ASTD campaign automation telemetry: log file missing: {log_path}")
            return 1
        try:
            data = extract_latest_telemetry(log_text)
        except ValueError as exc:
            print("FAIL ASTD campaign automation telemetry")
            print(f"- {exc}: {log_path}")
            return 1
        source = str(log_path)
    else:
        assert telemetry_path is not None
        try:
            raw = telemetry_path.read_text(encoding="utf-8")
        except FileNotFoundError:
            print(f"FAIL ASTD campaign automation telemetry: telemetry file missing: {telemetry_path}")
            return 1
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            print("FAIL ASTD campaign automation telemetry")
            print(f"- telemetry file is not valid JSON: {telemetry_path}: {exc}")
            return 1
        source = str(telemetry_path)

    errors = validate_telemetry(data)
    if errors:
        print("FAIL ASTD campaign automation telemetry")
        for error in errors:
            print(f"- {error}")
        _print_result(data, errors, source)
        return 1

    print("PASS ASTD campaign automation telemetry")
    _print_result(data, errors, source)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--telemetry", type=Path, help="path to a campaign automation telemetry JSON file")
    source.add_argument("--log", type=Path, help="path to starsector.log containing [ASTD-CampaignAutomation] telemetry lines")
    args = parser.parse_args(argv)
    return verify(telemetry_path=args.telemetry, log_path=args.log)


if __name__ == "__main__":
    sys.exit(main())
