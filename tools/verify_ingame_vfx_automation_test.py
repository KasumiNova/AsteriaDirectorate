#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image


def _load_verifier_module():
    verifier_path = Path(__file__).with_name("verify_ingame_vfx_automation.py")
    spec = importlib.util.spec_from_file_location("verify_ingame_vfx_automation", verifier_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"failed to load verifier module: {verifier_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProjectileStructureMetricsTest(unittest.TestCase):
    def _arc_production_telemetry(self, tmp_path: Path, screenshot: Path, frames: list[Path] | None = None) -> Path:
        data = {
            "scenario": "arc_production_ships_vfx_tooltip",
            "state": "Completed",
            "arcProductionMissingShips": [],
            "arcProductionDeployedShipIds": ["astd_xc_102", "astd_xc_101", "astd_xc_103"],
            "arcProductionDeployedVariantIds": [
                "astd_xc_102_Standard",
                "astd_xc_101_Standard",
                "astd_xc_103_Standard",
            ],
            "xc102ShockwaveFrames": 1,
            "xc102ShockwaveRadius": 405,
            "xc102ShockwaveFluxPressure": 1,
            "xc101ShieldOpen": 1,
            "xc101SystemActive": 1,
            "xc101ShieldArcEmissions": 1,
            "xc103SystemAfterimages": 1,
            "xc102Tooltip": True,
            "xc101Tooltip": True,
            "xc103Tooltip": True,
            "xc102TooltipKeys": 20,
            "xc101TooltipKeys": 44,
            "xc103TooltipKeys": 27,
            "screenshotPath": str(screenshot),
            "screenshotFrames": [str(frame) for frame in frames] if frames else None,
        }
        telemetry = tmp_path / "telemetry.json"
        telemetry.write_text(json.dumps(data), encoding="utf-8")
        return telemetry

    def _write_arc_production_image(self, path: Path, deployment_overlay: bool) -> None:
        width, height = 1900, 1000
        rgb = np.zeros((height, width, 3), dtype=np.uint8)
        rgb[:, :] = [10, 12, 18]
        if deployment_overlay:
            left, top = int(width * 0.30), int(height * 0.30)
            right, bottom = int(width * 0.70), int(height * 0.83)
            rgb[top:bottom, left:right] = [0, 0, 0]
            rgb[top:top + 6, left:right] = [20, 100, 115]
            rgb[bottom - 6:bottom, left:right] = [20, 100, 115]
            rgb[top:bottom, left:left + 6] = [20, 100, 115]
            rgb[top:bottom, right - 6:right] = [20, 100, 115]
        for roi in ((0.02, 0.26, 0.42, 0.70), (0.28, 0.24, 0.64, 0.78), (0.48, 0.22, 0.88, 0.72)):
            left, top = int(width * roi[0]), int(height * roi[1])
            right, bottom = int(width * roi[2]), int(height * roi[3])
            region_width = right - left
            region_height = bottom - top
            for step in range(180):
                x = left + int(region_width * (0.12 + 0.76 * step / 179))
                y = top + int(region_height * (0.18 + 0.64 * step / 179))
                rgb[max(top, y - 4):min(bottom, y + 5), max(left, x - 4):min(right, x + 5)] = [90, 160, 255]
        Image.fromarray(rgb, "RGB").save(path)

    def _write_arc_production_flat_color_blocks(self, path: Path) -> None:
        width, height = 1900, 1000
        rgb = np.zeros((height, width, 3), dtype=np.uint8)
        rgb[:, :] = [10, 12, 18]
        for roi in ((0.02, 0.26, 0.42, 0.70), (0.28, 0.24, 0.64, 0.78), (0.48, 0.22, 0.88, 0.72)):
            left, top = int(width * roi[0]), int(height * roi[1])
            rgb[top:top + 30, left:left + 50] = [90, 160, 255]
        Image.fromarray(rgb, "RGB").save(path)

    def test_bright_core_metrics_ignore_bright_background_outliers(self) -> None:
        verifier = _load_verifier_module()
        rgb = np.zeros((80, 240, 3), dtype=np.uint8)
        rgb[38:42, 20:220] = [32, 80, 160]
        rgb[35:45, 150:210] = [235, 245, 255]
        rgb[5:25, 180:186] = [255, 255, 255]

        metrics = verifier._projectile_structure_metrics(rgb)

        self.assertLessEqual(metrics["bright_height"], 12.0)
        self.assertGreaterEqual(metrics["bright_width"], 58.0)

    def test_visible_profile_width_includes_soft_disconnected_tail_energy(self) -> None:
        verifier = _load_verifier_module()
        rgb = np.zeros((80, 300, 3), dtype=np.uint8)
        rgb[39:42, 20:120] = [32, 55, 82]
        rgb[35:45, 132:260] = [130, 165, 220]

        metrics = verifier._projectile_structure_metrics(rgb)

        self.assertLess(metrics["bbox_width"], 150.0)
        self.assertGreater(metrics["profile_width"], 220.0)
        self.assertGreater(metrics["profile_aspect"], metrics["aspect"])

    def test_arc_production_rejects_attempt_only_screenshot_evidence(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            attempt = tmp_path / "attempt.txt"
            attempt.write_text("attempted", encoding="utf-8")
            telemetry = tmp_path / "telemetry.json"
            data = {
                "scenario": "arc_production_ships_vfx_tooltip",
                "state": "Completed",
            "xc102ShockwaveFrames": 1,
            "xc102ShockwaveRadius": 405,
            "xc102ShockwaveFluxPressure": 1,
            "xc101ShieldOpen": 1,
            "xc101SystemActive": 1,
            "xc101ShieldArcEmissions": 1,
            "xc103SystemAfterimages": 1,
                "xc102Tooltip": True,
                "xc101Tooltip": True,
                "xc103Tooltip": True,
                "xc102TooltipKeys": 12,
                "xc101TooltipKeys": 12,
                "xc103TooltipKeys": 16,
                "screenshotAttemptPath": str(attempt),
            }
            telemetry.write_text(json.dumps(data), encoding="utf-8")

            rc = verifier.verify(telemetry, require_screenshot_file=False)

        self.assertEqual(1, rc)

    def test_arc_production_selects_clean_combat_frame_over_deployment_overlay(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            primary = tmp_path / "primary.jpg"
            clean = tmp_path / "frame-02.jpg"
            self._write_arc_production_image(primary, deployment_overlay=True)
            self._write_arc_production_image(clean, deployment_overlay=False)
            telemetry = self._arc_production_telemetry(tmp_path, primary, [primary, clean])

            rc = verifier.verify(telemetry, require_screenshot_file=True)

        self.assertEqual(0, rc)

    def test_arc_production_overlay_score_allows_dark_combat_space_without_panel_border(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "dark-combat.jpg"
            width, height = 1900, 1000
            rgb = np.zeros((height, width, 3), dtype=np.uint8)
            rgb[:, :] = [0, 0, 0]
            left, top = int(width * 0.02), int(height * 0.26)
            center_x, center_y = left + 260, top + 220
            for radius in (170, 230, 290):
                for angle in np.linspace(0.0, np.pi * 2.0, 900):
                    x = int(center_x + np.cos(angle) * radius)
                    y = int(center_y + np.sin(angle) * radius)
                    if 0 <= x < width and 0 <= y < height:
                        rgb[max(0, y - 2):min(height, y + 3), max(0, x - 2):min(width, x + 3)] = [32, 120, 150]
            Image.fromarray(rgb, "RGB").save(path)

            score = verifier._arc_production_deployment_overlay_score(path)

        self.assertLess(score, 0.90)

    def test_arc_production_rejects_deployment_overlay_only_screenshot(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            primary = tmp_path / "primary.jpg"
            self._write_arc_production_image(primary, deployment_overlay=True)
            telemetry = self._arc_production_telemetry(tmp_path, primary, [primary])

            rc = verifier.verify(telemetry, require_screenshot_file=True)

        self.assertEqual(1, rc)

    def test_arc_production_rejects_missing_deployed_ship_and_variant_lists(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            primary = tmp_path / "primary.jpg"
            self._write_arc_production_image(primary, deployment_overlay=False)
            telemetry = self._arc_production_telemetry(tmp_path, primary)
            data = json.loads(telemetry.read_text(encoding="utf-8"))
            data.pop("arcProductionDeployedShipIds")
            data.pop("arcProductionDeployedVariantIds")
            telemetry.write_text(json.dumps(data), encoding="utf-8")

            rc = verifier.verify(telemetry, require_screenshot_file=True)

        self.assertEqual(1, rc)

    def test_arc_production_rejects_flat_color_block_screenshot_substitutes(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            primary = tmp_path / "primary.jpg"
            self._write_arc_production_flat_color_blocks(primary)
            telemetry = self._arc_production_telemetry(tmp_path, primary)

            rc = verifier.verify(telemetry, require_screenshot_file=True)

        self.assertEqual(1, rc)


class DefaultScenarioLogFallbackTest(unittest.TestCase):
    """默认场景（EXPECTED）在 SSOptimizer 独立助手遥测身份字段过期时，回落 starsector.log 重建证据。"""

    def _write_default_scenario_log(self, path: Path) -> None:
        diagnostics = {
            "source": "ASTD",
            "scenario": "xc_001_starfall_echo_basic",
            "state": "Completed",
            "runtimeTrackedCount": 1,
            "runtimeLastProjectileSpecId": "astd_starfall_echo_shot",
        }
        path.write_text(
            "[ASTD-Automation] diagnostics state=Completed json=" + json.dumps(diagnostics) + "\n"
            + "[ASTD-Automation] Completed: xc_001/starfall_echo/astd_starfall_echo_shot/VFX observed\n",
            encoding="utf-8",
        )

    def _stale_ssoptimizer_telemetry(self, tmp_path: Path, screenshot: Path) -> Path:
        data = {
            "source": "SSOptimizer",
            "scenario": "arc_flare_aod7_basic",
            "state": "Completed",
            "shipId": "astd_arc_flare",
            "weaponId": "astd_aod7",
            "projectileSpecId": "astd_aod7_shot",
            "vfxPresetId": "aod7_shot",
            "screenshotPath": str(screenshot),
        }
        telemetry = tmp_path / "telemetry.json"
        telemetry.write_text(json.dumps(data), encoding="utf-8")
        return telemetry

    def _write_beam_image(self, path: Path) -> None:
        width, height = 2560, 1440
        rgb = np.zeros((height, width, 3), dtype=np.uint8)
        rgb[:, :] = [8, 10, 14]
        # 舰船区域亮块（满足 ship visible pixels 检查；右缘压在弹体 ROI 左缘 0.36 之内，避免粘连）
        left, top = int(width * 0.06), int(height * 0.30)
        right, bottom = int(width * 0.34), int(height * 0.75)
        rgb[top:bottom, left:right] = [120, 130, 140]
        # 弹体区域长亮条（满足弹体结构检查）
        pl, pt = int(width * 0.45), int(height * 0.46)
        pr, pb = int(width * 0.80), int(height * 0.50)
        rgb[pt:pb, pl:pr] = [140, 180, 255]
        rgb[pt + 4:pb - 4, pl + 100:pr] = [240, 248, 255]
        Image.fromarray(rgb, "RGB").save(path)

    def test_default_scenario_falls_back_to_log_when_ssoptimizer_identity_is_stale(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            screenshot = tmp_path / "screenshot.jpg"
            self._write_beam_image(screenshot)
            telemetry = self._stale_ssoptimizer_telemetry(tmp_path, screenshot)
            log = tmp_path / "starsector.log"
            self._write_default_scenario_log(log)

            rc = verifier.verify(telemetry, require_screenshot_file=False, log_path=log)

        self.assertEqual(0, rc)

    def test_default_scenario_rejects_log_without_matching_projectile_spec(self) -> None:
        verifier = _load_verifier_module()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            screenshot = tmp_path / "screenshot.jpg"
            self._write_beam_image(screenshot)
            telemetry = self._stale_ssoptimizer_telemetry(tmp_path, screenshot)
            log = tmp_path / "starsector.log"
            self._write_default_scenario_log(log)
            log.write_text(
                log.read_text(encoding="utf-8").replace("astd_starfall_echo_shot", "astd_other_shot"),
                encoding="utf-8",
            )

            rc = verifier.verify(telemetry, require_screenshot_file=False, log_path=log)

        self.assertEqual(1, rc)


if __name__ == "__main__":
    unittest.main()
