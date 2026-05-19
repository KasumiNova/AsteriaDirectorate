#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

import numpy as np


def _load_verifier_module():
    verifier_path = Path(__file__).with_name("verify_ingame_vfx_automation.py")
    spec = importlib.util.spec_from_file_location("verify_ingame_vfx_automation", verifier_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"failed to load verifier module: {verifier_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProjectileStructureMetricsTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
