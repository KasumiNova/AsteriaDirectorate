#!/usr/bin/env python3
"""Verify ASTD first-version in-game VFX automation evidence.

The script accepts a telemetry JSON produced by ASTDAutomationCombatPlugin and
checks the minimum acceptance contract for the Arc Flare + AOD-7 scenario.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Callable

import cv2
import numpy as np
from PIL import Image

EXPECTED = {
    "scenario": "arc_flare_aod7_basic",
    "shipId": "astd_arc_flare",
    "weaponId": "astd_aod7",
    "projectileSpecId": "astd_aod7_shot",
    "vfxPresetId": "aod7_shot",
}
ARC_PRODUCTION_SCENARIO = "arc_production_ships_vfx_tooltip"
ARC_PRODUCTION_REQUIRED_EVIDENCE = (
    "arcJetShockwaveFrames",
    "arcJetShockwaveRadius",
    "arcJetShockwaveFluxPressure",
    "plasmaArchShieldOpen",
    "plasmaArchSystemActive",
    "plasmaArchShieldArcEmissions",
    "radiationBeltSystemAfterimages",
    "arcJetTooltip",
    "plasmaArchTooltip",
    "radiationBeltTooltip",
)
ARC_PRODUCTION_REQUIRED_SHIP_IDS = ("astd_arc_jet", "astd_plasma_arch", "astd_radiation_belt")
ARC_PRODUCTION_REQUIRED_VARIANT_IDS = ("astd_arc_jet_Standard", "astd_plasma_arch_Standard", "astd_radiation_belt_Standard")
ARC_PRODUCTION_TOOLTIP_KEY_MINIMUMS = {
    "arcJetTooltipKeys": 20,
    "plasmaArchTooltipKeys": 44,
    "radiationBeltTooltipKeys": 26,
}
LENS_PHASE1_SCENARIO = "lens_phase1_foundation"
LENS_PHASE1_REQUIRED_SHIP_ID = "astd_gravitational_lens"
LENS_PHASE1_BOOL_EVIDENCE = (
    "lensCoreHullmod",
    "lensNanoHullmod",
    "lensSwitcherHullmod",
    "lensCrewedModeHullmod",
    "lensShieldOn",
)
LENS_PHASE1_NUMERIC_MINIMUMS = {
    "lensShieldArc": 200.0,
    "lensFighterBays": 4.0,
    "lensCoreTooltipKeys": 7.0,
    "lensSelfDriftStacks": 3.0,
    "lensSelfDeepWaterStacks": 3.0,
    "lensSelfHullDamageTakenMult": 1.0001,
}
ARC_PRODUCTION_SCREENSHOT_REGIONS = (
    ("arc jet shockwave ring", (0.02, 0.26, 0.42, 0.70), 900),
    ("plasma arch shield arcs", (0.28, 0.24, 0.64, 0.78), 550),
    ("radiation belt temporal afterimages", (0.48, 0.22, 0.88, 0.72), 350),
)

ASTD_TELEMETRY_FILE = "astd-ingame-automation-astd-telemetry.json"
LOG_COMPLETED_PATTERN = re.compile(r"\[ASTD-Automation]\s+Completed: arc_flare/aod7/astd_aod7_shot/VFX observed")
ASTD_DIAGNOSTICS_PATTERN = re.compile(r"\[ASTD-Automation]\s+diagnostics state=(?P<state>\S+)\s+json=(?P<json>\{.*\})")

MIN_SCREENSHOT_WIDTH = 2550
MIN_SCREENSHOT_HEIGHT = 1430
MAX_SCREENSHOT_WIDTH = 2570
MAX_SCREENSHOT_HEIGHT = 1450
EXPECTED_ROTATED_SHIP_ASPECT = 476 / 374
MAX_SHIP_ASPECT_ERROR = 0.18
MAX_SHIP_TEMPLATE_SCALE_RATIO_ERROR = 0.10
SHIP_SPRITE_PATH = Path(__file__).resolve().parents[1] / "contents/graphics/ships/astd_arc_flare.png"

PixelPredicate = Callable[[int, int, int], bool]


def _projectile_mask(rgb: np.ndarray) -> np.ndarray:
    luma = (0.299 * rgb[:, :, 0]) + (0.587 * rgb[:, :, 1]) + (0.114 * rgb[:, :, 2])
    blue = (rgb[:, :, 2] > 45) & (rgb[:, :, 1] > 35) & (rgb[:, :, 2] >= rgb[:, :, 0] + 5)
    white_core = luma > 120
    return blue | white_core


def _projectile_component_bbox(mask: np.ndarray, min_area: int) -> tuple[int, int, int, int, int] | None:
    labels_count, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8) * 255, 8)
    best: tuple[int, int, int, int, int] | None = None
    best_score = -1.0
    image_width = mask.shape[1]
    for label in range(1, labels_count):
        x, y, width, height, area = stats[label]
        if area < min_area:
            continue
        aspect = float(width) / max(float(height), 1.0)
        center_x = float(x + width * 0.5) / max(float(image_width), 1.0)
        if aspect < 3.0:
            continue
        score = float(area) * min(aspect, 18.0) * (0.65 + center_x * 0.7)
        if score > best_score:
            best_score = score
            best = (int(area), int(x), int(y), int(x + width - 1), int(y + height - 1))
    return best


def _suppress_preview_grid_lines(rgb: np.ndarray) -> np.ndarray:
    """Remove editor grid strokes before projectile component extraction."""
    cleaned = rgb.copy()
    luma = (0.299 * cleaned[:, :, 0]) + (0.587 * cleaned[:, :, 1]) + (0.114 * cleaned[:, :, 2])
    grid_like = (
        (cleaned[:, :, 2] > 25)
        & (cleaned[:, :, 1] > 14)
        & (cleaned[:, :, 0] < 35)
        & (cleaned[:, :, 2] >= cleaned[:, :, 0] + 8)
        & (luma < 55)
    )
    projectile_like = _projectile_mask(rgb)
    horizontal = cv2.morphologyEx(grid_like.astype(np.uint8), cv2.MORPH_OPEN, np.ones((1, 49), np.uint8)).astype(bool)
    vertical = cv2.morphologyEx(grid_like.astype(np.uint8), cv2.MORPH_OPEN, np.ones((49, 1), np.uint8)).astype(bool)
    cleaned[horizontal | vertical, :] = 0
    return cleaned


def _suppress_screenshot_tactical_lines(rgb: np.ndarray) -> np.ndarray:
    """Remove combat tactical range/targeting guide strokes before projectile metrics."""
    cleaned = rgb.copy()
    luma = (0.299 * cleaned[:, :, 0]) + (0.587 * cleaned[:, :, 1]) + (0.114 * cleaned[:, :, 2])
    yellow_line = (
        (cleaned[:, :, 0] > 40)
        & (cleaned[:, :, 1] > 34)
        & (cleaned[:, :, 2] < 34)
        & (cleaned[:, :, 0] >= cleaned[:, :, 2] + 18)
        & (cleaned[:, :, 1] >= cleaned[:, :, 2] + 14)
        & (luma < 95)
    )
    horizontal = cv2.morphologyEx(yellow_line.astype(np.uint8), cv2.MORPH_OPEN, np.ones((1, 55), np.uint8)).astype(bool)
    vertical = cv2.morphologyEx(yellow_line.astype(np.uint8), cv2.MORPH_OPEN, np.ones((55, 1), np.uint8)).astype(bool)
    cleaned[horizontal | vertical, :] = 0
    return cleaned


def _main_projectile_mask(rgb: np.ndarray, min_area: int = 80) -> np.ndarray:
    mask = _projectile_mask(rgb)
    labels_count, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8) * 255, 8)
    best_label = 0
    best_score = -1.0
    image_width = mask.shape[1]
    for label in range(1, labels_count):
        x, y, width, height, area = stats[label]
        if area < min_area:
            continue
        aspect = float(width) / max(float(height), 1.0)
        if aspect < 3.0:
            continue
        center_x = float(x + width * 0.5) / max(float(image_width), 1.0)
        score = float(area) * min(aspect, 24.0) * (0.65 + center_x * 0.7)
        if score > best_score:
            best_score = score
            best_label = label
    if best_label == 0:
        return mask
    component = labels == best_label
    # Include close soft pixels only around the selected long component, avoiding stars/UI elsewhere in the ROI.
    dilated = cv2.dilate(component.astype(np.uint8), np.ones((7, 19), np.uint8), iterations=1).astype(bool)
    return mask & dilated


def _crop_projectile_roi(
    path: Path,
    roi: tuple[float, float, float, float],
    suppress_grid_lines: bool = False,
) -> tuple[np.ndarray, tuple[int, int, int, int, int]] | None:
    with Image.open(path) as loaded:
        image = loaded.convert("RGB")
    width, height = image.size
    left = int(width * roi[0])
    top = int(height * roi[1])
    right = int(width * roi[2])
    bottom = int(height * roi[3])
    rgb = np.array(image.crop((left, top, right, bottom)))
    component_rgb = _suppress_preview_grid_lines(rgb) if suppress_grid_lines else rgb
    bbox = _projectile_component_bbox(_projectile_mask(component_rgb), min_area=300)
    if bbox is None:
        return None
    area, min_x, min_y, max_x, max_y = bbox
    pad_x = max(8, int((max_x - min_x + 1) * 0.08))
    pad_y = max(8, int((max_y - min_y + 1) * 0.35))
    min_x = max(0, min_x - pad_x)
    min_y = max(0, min_y - pad_y)
    max_x = min(rgb.shape[1] - 1, max_x + pad_x)
    max_y = min(rgb.shape[0] - 1, max_y + pad_y)
    return component_rgb[min_y:max_y + 1, min_x:max_x + 1], (area, left + min_x, top + min_y, left + max_x, top + max_y)


def _crop_projectile_roi_by_bright_core(
    path: Path,
    roi: tuple[float, float, float, float],
    tail_scale: float,
    head_scale: float,
    vertical_scale: float,
    suppress_grid_lines: bool = False,
) -> tuple[np.ndarray, tuple[int, int, int, int, int]] | None:
    with Image.open(path) as loaded:
        image = loaded.convert("RGB")
    width, height = image.size
    left = int(width * roi[0])
    top = int(height * roi[1])
    right = int(width * roi[2])
    bottom = int(height * roi[3])
    rgb = np.array(image.crop((left, top, right, bottom)))
    component_rgb = _suppress_preview_grid_lines(rgb) if suppress_grid_lines else _suppress_screenshot_tactical_lines(rgb)
    luma = (0.299 * component_rgb[:, :, 0]) + (0.587 * component_rgb[:, :, 1]) + (0.114 * component_rgb[:, :, 2])
    bright = luma > 150
    labels_count, labels, stats, _ = cv2.connectedComponentsWithStats(bright.astype(np.uint8) * 255, 8)
    best: tuple[int, int, int, int, int] | None = None
    best_score = -1.0
    for label in range(1, labels_count):
        x, y, w, h, area = stats[label]
        if area < 80:
            continue
        aspect = float(w) / max(float(h), 1.0)
        if aspect < 2.0:
            continue
        score = float(area) * min(aspect, 24.0)
        if score > best_score:
            best_score = score
            best = (int(area), int(x), int(y), int(x + w - 1), int(y + h - 1))
    if best is None:
        return _crop_projectile_roi(path, roi, suppress_grid_lines=suppress_grid_lines)

    area, min_x, min_y, max_x, max_y = best
    bright_width = max_x - min_x + 1
    bright_height = max_y - min_y + 1
    min_x = max(0, min_x - int(bright_width * tail_scale))
    max_x = min(component_rgb.shape[1] - 1, max_x + int(bright_width * head_scale))
    pad_y = max(10, int(max(bright_height, bright_width * 0.08) * vertical_scale))
    mid_y = (min_y + max_y) // 2
    min_y = max(0, mid_y - pad_y)
    max_y = min(component_rgb.shape[0] - 1, mid_y + pad_y)
    crop = component_rgb[min_y:max_y + 1, min_x:max_x + 1]
    mask = _projectile_mask(crop)
    mask_area = int(mask.sum())
    return crop, (mask_area, left + min_x, top + min_y, left + max_x, top + max_y)


def _projectile_structure_metrics(rgb: np.ndarray) -> dict[str, float]:
    luma = (0.299 * rgb[:, :, 0]) + (0.587 * rgb[:, :, 1]) + (0.114 * rgb[:, :, 2])
    mask = _main_projectile_mask(rgb)
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return {}

    bright = (luma > 150) & mask
    bright_y, bright_x = np.where(bright)
    height, width = rgb.shape[:2]
    x_profile = mask.sum(axis=0).astype(np.float32)
    y_profile = mask.sum(axis=1).astype(np.float32)
    if x_profile.max() > 0:
        x_profile /= x_profile.max()
    if y_profile.max() > 0:
        y_profile /= y_profile.max()
    visible_energy = _projectile_visible_energy(rgb, luma)
    energy_profile = visible_energy.sum(axis=0).astype(np.float32)
    if energy_profile.max() > 0:
        active_energy_columns = np.where(energy_profile > energy_profile.max() * 0.01)[0]
    else:
        active_energy_columns = np.array([], dtype=np.int64)
    profile_width = float(active_energy_columns.max() - active_energy_columns.min() + 1) if len(active_energy_columns) else float(xs.max() - xs.min() + 1)
    centered = mask[:, int(width * 0.18):int(width * 0.86)]
    vertical_counts = centered.sum(axis=1)
    active_rows = int((vertical_counts > max(2, centered.shape[1] * 0.012)).sum())
    upper = mask[: height // 2, :].sum()
    lower = mask[height // 2 :, :].sum()
    asymmetry = abs(float(upper) - float(lower)) / max(float(upper + lower), 1.0)

    edges = cv2.Canny((luma.astype(np.uint8)), 30, 110)
    edge_density = float((edges > 0).sum()) / max(float(mask.sum()), 1.0)

    return {
        "mask_pixels": float(mask.sum()),
        "bbox_width": float(xs.max() - xs.min() + 1),
        "bbox_height": float(ys.max() - ys.min() + 1),
        "aspect": float((xs.max() - xs.min() + 1) / max((ys.max() - ys.min() + 1), 1)),
        "profile_width": profile_width,
        "profile_aspect": float(profile_width / max((ys.max() - ys.min() + 1), 1)),
        "bright_pixels": float(bright.sum()),
        "bright_width": float(bright_x.max() - bright_x.min() + 1) if len(bright_x) else 0.0,
        "bright_height": float(bright_y.max() - bright_y.min() + 1) if len(bright_y) else 0.0,
        "glow_height_ratio": float(active_rows / max(height, 1)),
        "vertical_asymmetry": asymmetry,
        "edge_density": edge_density,
        "x_profile": x_profile,
        "y_profile": y_profile,
    }


def _projectile_visible_energy(rgb: np.ndarray, luma: np.ndarray) -> np.ndarray:
    blue = (rgb[:, :, 2] > 35) & (rgb[:, :, 1] > 25) & (rgb[:, :, 2] >= rgb[:, :, 0] + 5)
    white = luma > 120
    return np.maximum(luma - 20, 0) * (blue | white)


def _resampled_profile_error(left: np.ndarray, right: np.ndarray, samples: int = 96) -> float:
    if left.size == 0 or right.size == 0:
        return 1.0
    x = np.linspace(0.0, 1.0, samples)
    left_x = np.linspace(0.0, 1.0, left.size)
    right_x = np.linspace(0.0, 1.0, right.size)
    left_sample = np.interp(x, left_x, left)
    right_sample = np.interp(x, right_x, right)
    return float(np.mean(np.abs(left_sample - right_sample)))


def _write_visual_compare(
    reference_rgb: np.ndarray,
    screenshot_rgb: np.ndarray,
    output_path: Path,
) -> None:
    ref_image = Image.fromarray(reference_rgb).convert("RGB")
    shot_image = Image.fromarray(screenshot_rgb).convert("RGB")
    target_height = max(ref_image.height, shot_image.height, 1)

    def resize_to_height(image: Image.Image) -> Image.Image:
        if image.height == target_height:
            return image
        width = max(1, round(image.width * target_height / max(image.height, 1)))
        return image.resize((width, target_height), Image.Resampling.LANCZOS)

    ref_resized = resize_to_height(ref_image)
    shot_resized = resize_to_height(shot_image)
    gap = 18
    label_height = 28
    canvas = Image.new(
        "RGB",
        (ref_resized.width + gap + shot_resized.width, label_height + target_height),
        (4, 8, 16),
    )
    canvas.paste(ref_resized, (0, label_height))
    canvas.paste(shot_resized, (ref_resized.width + gap, label_height))

    try:
        from PIL import ImageDraw

        draw = ImageDraw.Draw(canvas)
        draw.text((8, 6), "preview reference", fill=(210, 230, 255))
        draw.text((ref_resized.width + gap + 8, 6), "in-game screenshot", fill=(210, 230, 255))
    except Exception:
        pass

    output_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output_path)


def _check_preview_reference_parity(
    screenshot_path: Path,
    reference_path: Path,
    errors: list[str],
    visual_compare_output: Path | None = None,
) -> list[str]:
    details: list[str] = []
    if not reference_path.exists():
        errors.append(f"previewReference: file does not exist: {reference_path}")
        return details

    reference_roi = _crop_projectile_roi_by_bright_core(reference_path, (0.15, 0.30, 0.85, 0.70), 1.2, 0.22, 1.7, suppress_grid_lines=True)
    screenshot_roi = _crop_projectile_roi_by_bright_core(screenshot_path, (0.36, 0.32, 0.88, 0.70), 1.2, 0.22, 1.7)
    if reference_roi is None:
        errors.append("previewReference: projectile structure missing in reference image")
        return details
    if screenshot_roi is None:
        errors.append("screenshotPath: projectile structure missing for preview parity comparison")
        return details

    reference_rgb, reference_bbox = reference_roi
    screenshot_rgb, screenshot_bbox = screenshot_roi
    if visual_compare_output is not None:
        _write_visual_compare(reference_rgb, screenshot_rgb, visual_compare_output)
    ref = _projectile_structure_metrics(reference_rgb)
    shot = _projectile_structure_metrics(screenshot_rgb)
    if not ref or not shot:
        errors.append("preview parity: failed to measure projectile masks")
        return details

    body_aspect_ratio = shot["profile_aspect"] / max(ref["profile_aspect"], 0.001)
    bright_width_ratio = (shot["bright_width"] / max(shot["bbox_width"], 1.0)) / (ref["bright_width"] / max(ref["bbox_width"], 1.0))
    bright_height_ratio = (shot["bright_height"] / max(shot["bbox_height"], 1.0)) / (ref["bright_height"] / max(ref["bbox_height"], 1.0))
    glow_height_ratio = shot["glow_height_ratio"] / max(ref["glow_height_ratio"], 0.001)
    x_profile_error = _resampled_profile_error(ref["x_profile"], shot["x_profile"])
    y_profile_error = _resampled_profile_error(ref["y_profile"], shot["y_profile"])
    edge_density_ratio = shot["edge_density"] / max(ref["edge_density"], 0.001)

    if not (0.78 <= body_aspect_ratio <= 1.28):
        errors.append(
            "preview parity: body/trail aspect mismatch: "
            f"game/reference={body_aspect_ratio:.3f}",
        )
    if not (0.72 <= bright_width_ratio <= 1.35 and 0.65 <= bright_height_ratio <= 1.45):
        errors.append(
            "preview parity: bright head/core size mismatch: "
            f"width={bright_width_ratio:.3f}, height={bright_height_ratio:.3f}",
        )
    if not (0.72 <= glow_height_ratio <= 1.38):
        errors.append(
            "preview parity: bloom/glow envelope height mismatch: "
            f"game/reference={glow_height_ratio:.3f}",
        )
    if x_profile_error > 0.24:
        errors.append(f"preview parity: trail/body length profile mismatch: error={x_profile_error:.3f}")
    if y_profile_error > 0.30:
        errors.append(f"preview parity: glow/ribbon vertical profile mismatch: error={y_profile_error:.3f}")
    if not (0.45 <= edge_density_ratio <= 1.85):
        errors.append(
            "preview parity: ribbon noise/edge density mismatch: "
            f"game/reference={edge_density_ratio:.3f}",
        )

    details.extend(
        [
            f"preview reference bbox: {reference_bbox[3] - reference_bbox[1] + 1}x{reference_bbox[4] - reference_bbox[2] + 1}",
            f"game parity bbox: {screenshot_bbox[3] - screenshot_bbox[1] + 1}x{screenshot_bbox[4] - screenshot_bbox[2] + 1}",
            f"preview parity body aspect ratio: {body_aspect_ratio:.3f}",
            f"preview parity bright core ratio: {bright_width_ratio:.3f}x{bright_height_ratio:.3f}",
            f"preview parity glow height ratio: {glow_height_ratio:.3f}",
            f"preview parity profile errors: x={x_profile_error:.3f}, y={y_profile_error:.3f}",
            f"preview parity ribbon edge density ratio: {edge_density_ratio:.3f}",
            *( [f"visual compare output: {visual_compare_output}"] if visual_compare_output is not None else [] ),
        ],
    )
    return details


def _load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(f"FAIL telemetry missing: {path}")
    except json.JSONDecodeError as exc:
        raise SystemExit(f"FAIL telemetry is not valid JSON: {path}: {exc}")


def _load_preferred_telemetry(path: Path) -> tuple[dict, Path]:
    astd_path = path.with_name(ASTD_TELEMETRY_FILE)
    if astd_path.exists():
        astd_data = _load_json(astd_path)
        if astd_data.get("source") == "ASTD":
            return astd_data, astd_path

    return _load_json(path), path


def _data_from_log(log_path: Path) -> dict:
    try:
        log_text = log_path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        raise SystemExit(f"FAIL automation log missing: {log_path}")

    if not LOG_COMPLETED_PATTERN.search(log_text):
        raise SystemExit(f"FAIL ASTD automation completion marker missing in log: {log_path}")

    return {
        "scenario": EXPECTED["scenario"],
        "shipId": EXPECTED["shipId"],
        "weaponId": EXPECTED["weaponId"],
        "projectileSpecId": EXPECTED["projectileSpecId"],
        "vfxPresetId": EXPECTED["vfxPresetId"],
        "combatSceneObserved": True,
        "shipObserved": True,
        "weaponObserved": True,
        "projectileObserved": True,
        "vfxObserved": True,
        "fireCommandIssued": True,
        "state": "Completed",
        "logPath": str(log_path),
        "screenshotAttemptPath": str(log_path),
    }


def _merge_screenshot_evidence(astd_data: dict, sso_data: dict | None) -> dict:
    if sso_data is None:
        return dict(astd_data)

    merged = dict(astd_data)
    for key in ("screenshotPath", "screenshotFrames", "screenshotAttemptPath"):
        if key in sso_data and sso_data[key] not in (None, ""):
            merged.setdefault(key, sso_data[key])
    return merged


def _arc_production_data_from_log(log_path: Path, ssoptimizer_data: dict | None = None) -> dict | None:
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except FileNotFoundError:
        raise SystemExit(f"FAIL automation log missing: {log_path}")

    last_match: dict | None = None
    last_completed: dict | None = None
    for line in lines:
        match = ASTD_DIAGNOSTICS_PATTERN.search(line)
        if match is None:
            continue

        try:
            diagnostics = json.loads(match.group("json"))
        except json.JSONDecodeError:
            continue
        if diagnostics.get("scenario") != ARC_PRODUCTION_SCENARIO:
            continue

        diagnostics.setdefault("state", match.group("state"))
        diagnostics.setdefault("source", "ASTD")
        diagnostics.setdefault("logPath", str(log_path))
        last_match = diagnostics
        if diagnostics.get("state") == "Completed" or match.group("state") == "Completed":
            diagnostics["state"] = "Completed"
            last_completed = diagnostics

    selected = last_completed if last_completed is not None else last_match
    if selected is None:
        return None
    return _merge_screenshot_evidence(selected, ssoptimizer_data)


def _diagnostics_from_log(log_path: Path, scenario: str, ssoptimizer_data: dict | None = None) -> dict | None:
    """Reconstruct the latest diagnostics JSON for a scenario from starsector.log.

    SSOptimizer patches writeTelemetry to a no-op inside the script sandbox, so the
    only authoritative evidence is the diagnostics line emitted by writeDiagnostics.
    """
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except FileNotFoundError:
        raise SystemExit(f"FAIL automation log missing: {log_path}")

    last_match: dict | None = None
    last_completed: dict | None = None
    for line in lines:
        match = ASTD_DIAGNOSTICS_PATTERN.search(line)
        if match is None:
            continue
        try:
            diagnostics = json.loads(match.group("json"))
        except json.JSONDecodeError:
            continue
        if diagnostics.get("scenario") != scenario:
            continue
        diagnostics.setdefault("state", match.group("state"))
        diagnostics.setdefault("source", "ASTD")
        diagnostics.setdefault("logPath", str(log_path))
        last_match = diagnostics
        if diagnostics.get("state") == "Completed" or match.group("state") == "Completed":
            diagnostics["state"] = "Completed"
            last_completed = diagnostics

    selected = last_completed if last_completed is not None else last_match
    if selected is None:
        return None
    return _merge_screenshot_evidence(selected, ssoptimizer_data)


def _verify_lens_phase1(data: dict, actual_telemetry_path: Path) -> int:
    errors: list[str] = []
    if data.get("scenario") != LENS_PHASE1_SCENARIO:
        errors.append(f"scenario: expected {LENS_PHASE1_SCENARIO!r}, got {data.get('scenario')!r}")
    if data.get("state") != "Completed":
        errors.append(f"state: expected 'Completed', got {data.get('state')!r}")

    deployed = data.get("lensDeployedShipIds")
    if not isinstance(deployed, list):
        errors.append(f"lensDeployedShipIds: expected deployed ship id list, got {deployed!r}")
    elif LENS_PHASE1_REQUIRED_SHIP_ID not in deployed:
        errors.append(f"lensDeployedShipIds: missing {LENS_PHASE1_REQUIRED_SHIP_ID!r} in {deployed!r}")

    for key in LENS_PHASE1_BOOL_EVIDENCE:
        if data.get(key) is not True:
            errors.append(f"{key}: expected true, got {data.get(key)!r}")

    for key, minimum in LENS_PHASE1_NUMERIC_MINIMUMS.items():
        value = data.get(key)
        try:
            numeric = float(value)
        except (TypeError, ValueError):
            errors.append(f"{key}: expected numeric >= {minimum}, got {value!r}")
            continue
        if numeric < minimum:
            errors.append(f"{key}: expected >= {minimum}, got {numeric}")

    if errors:
        print("FAIL ASTD Gravitational Lens phase-1 automation evidence")
        for error in errors:
            print(f"- {error}")
        _print_lens_result(data, actual_telemetry_path)
        return 1

    print("PASS ASTD Gravitational Lens phase-1 automation evidence")
    _print_lens_result(data, actual_telemetry_path)
    return 0


def _print_lens_result(data: dict, telemetry_path: Path) -> None:
    print(f"- telemetry: {telemetry_path}")
    print(f"- scenario: {data.get('scenario')}")
    print(f"- state: {data.get('state')}")
    if data.get("logPath"):
        print(f"- log: {data['logPath']}")
    print(f"- lensDeployedShipIds: {data.get('lensDeployedShipIds')}")
    for key in LENS_PHASE1_BOOL_EVIDENCE:
        print(f"- {key}: {data.get(key)}")
    for key in LENS_PHASE1_NUMERIC_MINIMUMS:
        print(f"- {key}: {data.get(key)}")


def _check_equal(data: dict, key: str, expected: str, errors: list[str]) -> None:
    actual = data.get(key)
    if actual != expected:
        errors.append(f"{key}: expected {expected!r}, got {actual!r}")


def _check_bool(data: dict, key: str, errors: list[str]) -> None:
    if data.get(key) is not True:
        errors.append(f"{key}: expected true, got {data.get(key)!r}")


def _check_screenshot_frames(data: dict, errors: list[str]) -> None:
    frames = data.get("screenshotFrames")
    if frames is None:
        return
    if not isinstance(frames, list) or not frames:
        errors.append(f"screenshotFrames: expected non-empty list, got {frames!r}")
        return
    for frame in frames:
        if not isinstance(frame, str) or not frame:
            errors.append(f"screenshotFrames: invalid frame path {frame!r}")
        elif not Path(frame).exists():
            errors.append(f"screenshotFrames: file does not exist: {frame}")


def _existing_screenshot_paths(data: dict, screenshot_override: Path | None) -> list[Path]:
    if screenshot_override is not None:
        return [screenshot_override]

    paths: list[Path] = []
    seen: set[Path] = set()
    for key in ("screenshotPath", "screenshotFrames"):
        value = data.get(key)
        values = value if isinstance(value, list) else [value]
        for item in values:
            if not isinstance(item, str) or not item:
                continue
            path = Path(item)
            if path in seen or not path.exists():
                continue
            seen.add(path)
            paths.append(path)
    return paths


def _check_evidence_file(
    data: dict,
    errors: list[str],
    require_screenshot_file: bool,
    screenshot_override: Path | None,
) -> tuple[str | None, str | None, list[str]]:
    screenshot_details: list[str] = []
    screenshot = str(screenshot_override) if screenshot_override is not None else data.get("screenshotPath")
    screenshot_attempt = data.get("screenshotAttemptPath")
    if require_screenshot_file:
        if not screenshot:
            errors.append("screenshotPath: required but missing")
        elif not Path(screenshot).exists():
            errors.append(f"screenshotPath: file does not exist: {screenshot}")
        else:
            screenshot_details.append(f"screenshot pixels available: {screenshot}")
    elif screenshot:
        if not Path(screenshot).exists():
            errors.append(f"screenshotPath: declared file does not exist: {screenshot}")
        else:
            screenshot_details.append(f"screenshot pixels available: {screenshot}")
    elif screenshot_attempt:
        if not Path(screenshot_attempt).exists():
            errors.append(f"screenshotAttemptPath: file does not exist: {screenshot_attempt}")
    else:
        errors.append("evidence: expected screenshotPath or screenshotAttemptPath")
    _check_screenshot_frames(data, errors)
    return screenshot, screenshot_attempt, screenshot_details


def _verify_arc_production(
    data: dict,
    actual_telemetry_path: Path,
    require_screenshot_file: bool,
    screenshot_override: Path | None,
) -> int:
    errors: list[str] = []
    if data.get("scenario") != ARC_PRODUCTION_SCENARIO:
        errors.append(f"scenario: expected {ARC_PRODUCTION_SCENARIO!r}, got {data.get('scenario')!r}")
    if data.get("state") != "Completed":
        errors.append(f"state: expected 'Completed', got {data.get('state')!r}")
    require_screenshot_file = True
    missing_ships = data.get("arcProductionMissingShips")
    if missing_ships != []:
        errors.append(f"arcProductionMissingShips: expected [], got {missing_ships!r}")
    deployed_ship_ids = data.get("arcProductionDeployedShipIds")
    deployed_variant_ids = data.get("arcProductionSourceVariantIds") or data.get("arcProductionDeployedVariantIds")
    if not isinstance(deployed_ship_ids, list):
        errors.append(f"arcProductionDeployedShipIds: expected deployed ship id list, got {deployed_ship_ids!r}")
    else:
        for ship_id in ARC_PRODUCTION_REQUIRED_SHIP_IDS:
            if ship_id not in deployed_ship_ids:
                errors.append(f"arcProductionDeployedShipIds: missing {ship_id!r} in {deployed_ship_ids!r}")
    if not isinstance(deployed_variant_ids, list):
        errors.append(f"arcProductionDeployedVariantIds: expected deployed variant id list, got {deployed_variant_ids!r}")
    else:
        for variant_id in ARC_PRODUCTION_REQUIRED_VARIANT_IDS:
            if variant_id not in deployed_variant_ids:
                errors.append(f"arcProductionDeployedVariantIds: missing {variant_id!r} in {deployed_variant_ids!r}")

    for key in ARC_PRODUCTION_REQUIRED_EVIDENCE:
        value = data.get(key)
        if isinstance(value, bool):
            if value is not True:
                errors.append(f"{key}: expected true, got {value!r}")
        else:
            try:
                numeric = float(value)
            except (TypeError, ValueError):
                errors.append(f"{key}: expected positive counter or true, got {value!r}")
                continue
            if numeric <= 0:
                errors.append(f"{key}: expected positive counter, got {value!r}")
    for key, minimum in ARC_PRODUCTION_TOOLTIP_KEY_MINIMUMS.items():
        value = data.get(key)
        try:
            numeric = int(value)
        except (TypeError, ValueError):
            errors.append(f"{key}: expected resolved tooltip key count >= {minimum}, got {value!r}")
            continue
        if numeric < minimum:
            errors.append(f"{key}: expected resolved tooltip key count >= {minimum}, got {numeric}")

    screenshot, screenshot_attempt, screenshot_details = _check_evidence_file(
        data,
        errors,
        require_screenshot_file,
        screenshot_override,
    )
    if not screenshot:
        errors.append("screenshotPath: ARC production requires a concrete screenshotPath for pixel verification")
    if screenshot:
        selected = _select_arc_production_screenshot(data, Path(screenshot), screenshot_override, errors)
        if selected is not None:
            screenshot = str(selected)
            screenshot_details.extend(_check_arc_production_screenshot_pixels(selected, errors))

    if errors:
        print("FAIL ASTD ARC production automation evidence")
        for error in errors:
            print(f"- {error}")
        _print_result(data, actual_telemetry_path, screenshot_details, screenshot, screenshot_attempt)
        return 1

    print("PASS ASTD ARC production automation evidence")
    _print_result(data, actual_telemetry_path, screenshot_details, screenshot, screenshot_attempt)
    return 0


def _arc_colored_bright_mask(rgb: np.ndarray) -> np.ndarray:
    luma = (0.299 * rgb[:, :, 0]) + (0.587 * rgb[:, :, 1]) + (0.114 * rgb[:, :, 2])
    blue_cyan = (
        (rgb[:, :, 2] > 80)
        & (rgb[:, :, 1] > 55)
        & (rgb[:, :, 2] >= rgb[:, :, 0] + 12)
        & (luma > 70)
    )
    white_core = (
        (luma > 160)
        & (rgb[:, :, 1] >= rgb[:, :, 0] - 8)
        & (rgb[:, :, 2] >= rgb[:, :, 0] - 8)
    )
    return blue_cyan | white_core


def _arc_region_component_details(mask: np.ndarray) -> tuple[int, int, int, int, int, float] | None:
    labels_count, _, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8) * 255, 8)
    best: tuple[int, int, int, int, int, float] | None = None
    for label in range(1, labels_count):
        x, y, width, height, area = stats[label]
        aspect = float(width) / max(float(height), 1.0)
        candidate = (int(area), int(x), int(y), int(width), int(height), aspect)
        if best is None or candidate[0] > best[0]:
            best = candidate
    return best


def _arc_production_deployment_overlay_score(path: Path) -> float:
    with Image.open(path) as loaded:
        image = loaded.convert("RGB")
    width, height = image.size
    rgb = np.array(image)
    left = int(width * 0.30)
    top = int(height * 0.30)
    right = int(width * 0.70)
    bottom = int(height * 0.83)
    crop = rgb[top:bottom, left:right, :]
    luma = (0.299 * crop[:, :, 0]) + (0.587 * crop[:, :, 1]) + (0.114 * crop[:, :, 2])
    dark_fraction = float((luma < 8).mean())
    teal_border = (
        (crop[:, :, 1] > 45)
        & (crop[:, :, 2] > 50)
        & (crop[:, :, 0] < 85)
        & (crop[:, :, 2] >= crop[:, :, 0] + 8)
        & (luma > 35)
    )
    edge = np.zeros(teal_border.shape, dtype=bool)
    edge_width = max(4, int(min(teal_border.shape[:2]) * 0.025))
    edge[:edge_width, :] = True
    edge[-edge_width:, :] = True
    edge[:, :edge_width] = True
    edge[:, -edge_width:] = True
    edge_border_fraction = float((teal_border & edge).sum() / max(int(edge.sum()), 1))
    if dark_fraction < 0.72:
        return dark_fraction
    return min(1.0, edge_border_fraction / 0.08)


def _select_arc_production_screenshot(
    data: dict,
    primary: Path,
    screenshot_override: Path | None,
    errors: list[str],
) -> Path | None:
    candidates = _existing_screenshot_paths(data, screenshot_override)
    if not candidates:
        candidates = [primary]

    best_path: Path | None = None
    best_score = -1
    overlay_scores: dict[Path, float] = {}
    detail_errors: dict[Path, list[str]] = {}
    for candidate in candidates:
        local_errors: list[str] = []
        try:
            overlay_scores[candidate] = _arc_production_deployment_overlay_score(candidate)
            details = _check_arc_production_screenshot_pixels(candidate, local_errors)
        except Exception as exc:
            detail_errors[candidate] = [f"{candidate}: {exc}"]
            continue
        if overlay_scores[candidate] >= 0.90:
            local_errors.append(f"arc production deployment overlay still visible: score={overlay_scores[candidate]:.3f}")
        detail_errors[candidate] = local_errors
        if local_errors:
            continue
        score = sum(int(match.group(1)) for detail in details for match in [re.search(r":\s+(\d+)$", detail)] if match)
        if score > best_score:
            best_score = score
            best_path = candidate

    if best_path is not None:
        return best_path

    for candidate, local_errors in detail_errors.items():
        for error in local_errors:
            errors.append(f"screenshotPath: {candidate.name}: {error}")
    return None


def _check_arc_production_screenshot_pixels(path: Path, errors: list[str]) -> list[str]:
    details: list[str] = []
    try:
        with Image.open(path) as loaded:
            image = loaded.convert("RGB")
    except Exception as exc:
        errors.append(f"screenshotPath: failed to read ARC production image pixels: {path}: {exc}")
        return details

    width, height = image.size
    details.append(f"ARC production screenshot pixels: {width}x{height}")
    if width < 1900 or height < 1000:
        errors.append(f"screenshotPath: ARC production screenshot resolution too small: {width}x{height}")

    full_rgb = np.array(image)
    for label, roi, min_pixels in ARC_PRODUCTION_SCREENSHOT_REGIONS:
        left = int(width * roi[0])
        top = int(height * roi[1])
        right = int(width * roi[2])
        bottom = int(height * roi[3])
        crop = full_rgb[top:bottom, left:right, :]
        mask = _arc_colored_bright_mask(crop)
        count = int(mask.sum())
        largest = _arc_region_component_details(mask)
        details.append(f"arc production VFX bright colored pixels ({label}): {count}")
        if count < min_pixels:
            errors.append(
                "screenshotPath: arc production VFX bright colored pixels too sparse "
                f"for {label}: {count} < {min_pixels}",
            )
        if largest is None:
            errors.append(f"screenshotPath: arc production VFX structure missing for {label}")
            continue
        largest_area, _, _, largest_width, largest_height, largest_aspect = largest
        details.append(
            "arc production VFX largest connected component "
            f"({label}): area={largest_area}, size={largest_width}x{largest_height}, aspect={largest_aspect:.3f}",
        )
        if largest_area < min_pixels * 0.55:
            errors.append(
                "screenshotPath: arc production VFX connected structure too small "
                f"for {label}: {largest_area} < {int(min_pixels * 0.55)}",
            )
        if largest_width < 120 or largest_height < 120:
            errors.append(
                "screenshotPath: arc production VFX connected structure lacks combat-scale spread "
                f"for {label}: {largest_width}x{largest_height}",
            )

    return details


def _pixel_bbox(
    image: Image.Image,
    roi: tuple[float, float, float, float],
    predicate: PixelPredicate,
) -> tuple[int, int, int, int, int] | None:
    width, height = image.size
    left = int(width * roi[0])
    top = int(height * roi[1])
    right = int(width * roi[2])
    bottom = int(height * roi[3])
    min_x = right
    min_y = bottom
    max_x = left - 1
    max_y = top - 1
    count = 0

    pixels = image.load()
    for y in range(top, bottom):
        for x in range(left, right):
            r, g, b = pixels[x, y][:3]
            if predicate(r, g, b):
                count += 1
                min_x = min(min_x, x)
                min_y = min(min_y, y)
                max_x = max(max_x, x)
                max_y = max(max_y, y)

    if count == 0:
        return None
    return count, min_x, min_y, max_x, max_y


def _luma(r: int, g: int, b: int) -> float:
    return (0.299 * r) + (0.587 * g) + (0.114 * b)


def _load_rotated_ship_templates() -> list[tuple[str, np.ndarray, np.ndarray]]:
    try:
        source = Image.open(SHIP_SPRITE_PATH).convert("RGBA")
    except Exception as exc:
        raise RuntimeError(f"failed to load Arc Flare source sprite: {SHIP_SPRITE_PATH}: {exc}") from exc

    bbox = source.getbbox()
    if bbox is None:
        raise RuntimeError(f"Arc Flare source sprite has no non-transparent pixels: {SHIP_SPRITE_PATH}")

    cropped = source.crop(bbox)
    templates = []
    for name, angle in (("cw", -90), ("ccw", 90)):
        rotated = np.array(cropped.rotate(angle, expand=True))
        alpha = rotated[:, :, 3]
        if alpha.size == 0:
            continue
        actual_aspect = alpha.shape[1] / alpha.shape[0]
        aspect_error = abs(actual_aspect - EXPECTED_ROTATED_SHIP_ASPECT) / EXPECTED_ROTATED_SHIP_ASPECT
        if aspect_error > 0.01:
            raise RuntimeError(
                f"Arc Flare rotated template aspect drifted: {actual_aspect:.3f}, "
                f"expected {EXPECTED_ROTATED_SHIP_ASPECT:.3f}",
            )
        gray = cv2.cvtColor(rotated[:, :, :3], cv2.COLOR_RGB2GRAY)
        templates.append((name, gray, alpha))
    if not templates:
        raise RuntimeError("failed to build Arc Flare rotated ship templates")
    return templates


def _check_ship_template_shape(image: Image.Image, errors: list[str]) -> list[str]:
    details: list[str] = []
    width, height = image.size
    roi_box = (
        int(width * 0.02),
        int(height * 0.18),
        int(width * 0.50),
        int(height * 0.86),
    )
    roi = image.crop(roi_box)
    roi_gray = cv2.cvtColor(np.array(roi), cv2.COLOR_RGB2GRAY)
    roi_edges = cv2.Canny(roi_gray, 40, 120)

    try:
        templates = _load_rotated_ship_templates()
    except RuntimeError as exc:
        errors.append(f"screenshotPath: {exc}")
        return details

    best: tuple[float, str, float, float, int, int] | None = None

    def consider(name: str, template_gray: np.ndarray, template_alpha: np.ndarray, scale_x: float, scale_y: float) -> None:
        nonlocal best
        template_width = int(template_gray.shape[1] * scale_x)
        template_height = int(template_gray.shape[0] * scale_y)
        if template_width >= roi_gray.shape[1] or template_height >= roi_gray.shape[0]:
            return

        gray = cv2.resize(template_gray, (template_width, template_height), interpolation=cv2.INTER_AREA)
        alpha = cv2.resize(template_alpha, (template_width, template_height), interpolation=cv2.INTER_AREA)
        edges = cv2.Canny(gray, 30, 100)
        mask = (alpha > 32).astype(np.uint8) * 255
        try:
            result = cv2.matchTemplate(roi_edges, edges, cv2.TM_CCORR_NORMED, mask=mask)
        except cv2.error:
            result = cv2.matchTemplate(roi_edges, edges, cv2.TM_CCORR_NORMED)
        result = np.nan_to_num(result, nan=-1.0, posinf=-1.0, neginf=-1.0)
        _, score, _, _ = cv2.minMaxLoc(result)
        if best is None or score > best[0]:
            best = (score, name, float(scale_x), float(scale_y), template_width, template_height)

    for name, template_gray, template_alpha in templates:
        for scale_x in np.linspace(0.90, 2.30, 29):
            for scale_y in np.linspace(0.90, 2.30, 29):
                consider(name, template_gray, template_alpha, float(scale_x), float(scale_y))

    coarse_best = best
    if coarse_best is not None:
        _, best_name, coarse_x, coarse_y, _, _ = coarse_best
        for name, template_gray, template_alpha in templates:
            if name != best_name:
                continue
            for scale_x in np.linspace(max(0.80, coarse_x - 0.08), coarse_x + 0.08, 17):
                for scale_y in np.linspace(max(0.80, coarse_y - 0.08), coarse_y + 0.08, 17):
                    consider(name, template_gray, template_alpha, float(scale_x), float(scale_y))

    if best is None:
        errors.append("screenshotPath: ship template match failed in the expected Arc Flare region")
        return details

    score, name, scale_x, scale_y, template_width, template_height = best
    scale_ratio = scale_x / scale_y
    template_aspect = template_width / template_height
    ratio_error = abs(scale_ratio - 1.0)
    aspect_error = abs(template_aspect - EXPECTED_ROTATED_SHIP_ASPECT) / EXPECTED_ROTATED_SHIP_ASPECT
    if score >= 0.30 and ratio_error > MAX_SHIP_TEMPLATE_SCALE_RATIO_ERROR:
        errors.append(
            "screenshotPath: ship template scale ratio indicates non-uniform flattening: "
            f"x/y={scale_ratio:.3f}, expected approximately 1.000",
        )
    if aspect_error > MAX_SHIP_ASPECT_ERROR:
        errors.append(
            "screenshotPath: matched ship template aspect outside expected rotated sprite range: "
            f"{template_aspect:.3f}, expected approximately {EXPECTED_ROTATED_SHIP_ASPECT:.3f}",
        )
    details.append(
        "ship template scale ratio: "
        f"{scale_ratio:.3f}, aspect: {template_aspect:.3f}, score: {score:.3f}, orientation: {name}",
    )
    return details


def _check_screenshot_pixels(path: Path, errors: list[str]) -> list[str]:
    details: list[str] = []
    try:
        with Image.open(path) as loaded:
            image = loaded.convert("RGB")
    except Exception as exc:
        errors.append(f"screenshotPath: failed to read image pixels: {path}: {exc}")
        return details

    width, height = image.size
    details.append(f"screenshot pixels: {width}x{height}")
    if not (MIN_SCREENSHOT_WIDTH <= width <= MAX_SCREENSHOT_WIDTH and MIN_SCREENSHOT_HEIGHT <= height <= MAX_SCREENSHOT_HEIGHT):
        errors.append(
            f"screenshotPath: expected 2560x1440 automation screenshot within LWJGL tolerance, got {width}x{height}",
        )

    ship_bbox = _pixel_bbox(
        image,
        (0.04, 0.25, 0.48, 0.80),
        lambda r, g, b: _luma(r, g, b) > 90 and min(r, g, b) > 45 and max(r, g, b) - min(r, g, b) < 110,
    )
    if ship_bbox is None:
        errors.append("screenshotPath: ship visible pixels missing in the expected Arc Flare region")
    else:
        ship_pixels, min_x, min_y, max_x, max_y = ship_bbox
        ship_width = max_x - min_x + 1
        ship_height = max_y - min_y + 1
        ship_aspect = ship_width / ship_height
        if ship_pixels < 100_000:
            errors.append(f"screenshotPath: ship visible pixels too sparse: {ship_pixels}")
        details.append(f"ship visible pixels: {ship_pixels}, rough bbox aspect: {ship_aspect:.3f}")

    details.extend(_check_ship_template_shape(image, errors))

    projectile_roi = _crop_projectile_roi(path, (0.36, 0.32, 0.88, 0.70))
    if projectile_roi is None:
        errors.append("screenshotPath: projectile VFX structure missing in the expected AOD-7 region")
    else:
        projectile_rgb, projectile_bbox = projectile_roi
        metrics = _projectile_structure_metrics(projectile_rgb)
        if not metrics:
            errors.append("screenshotPath: projectile VFX structure could not be measured")
        else:
            if metrics["mask_pixels"] < 900:
                errors.append(f"screenshotPath: projectile VFX visible pixels too sparse: {metrics['mask_pixels']:.0f}")
            if metrics["bbox_width"] < 180 or metrics["aspect"] < 3.0:
                errors.append(
                    "screenshotPath: projectile VFX is not a long visible beam: "
                    f"{metrics['bbox_width']:.0f}x{metrics['bbox_height']:.0f}",
                )
            if metrics["bright_pixels"] < 80:
                errors.append(f"screenshotPath: projectile VFX bright head/core pixels too sparse: {metrics['bright_pixels']:.0f}")
            details.append(
                "projectile VFX dynamic ROI: "
                f"bbox={projectile_bbox[3] - projectile_bbox[1] + 1}x{projectile_bbox[4] - projectile_bbox[2] + 1}, "
                f"mask={metrics['mask_pixels']:.0f}, bright={metrics['bright_pixels']:.0f}, aspect={metrics['aspect']:.3f}",
            )

    return details


def verify(
    telemetry_path: Path,
    require_screenshot_file: bool,
    log_path: Path | None = None,
    preview_reference: Path | None = None,
    visual_compare_output: Path | None = None,
    screenshot_override: Path | None = None,
) -> int:
    if telemetry_path.exists() or telemetry_path.with_name(ASTD_TELEMETRY_FILE).exists():
        data, actual_telemetry_path = _load_preferred_telemetry(telemetry_path)
        if data.get("scenario") not in (ARC_PRODUCTION_SCENARIO, LENS_PHASE1_SCENARIO) and log_path is not None:
            lens_data = _diagnostics_from_log(log_path, LENS_PHASE1_SCENARIO, data)
            if lens_data is not None:
                data = lens_data
            else:
                arc_production_data = _arc_production_data_from_log(log_path, data)
                if arc_production_data is not None:
                    data = arc_production_data
    elif log_path is not None:
        data = _diagnostics_from_log(log_path, LENS_PHASE1_SCENARIO)
        if data is None:
            data = _arc_production_data_from_log(log_path)
        if data is None:
            data = _data_from_log(log_path)
        actual_telemetry_path = telemetry_path
    else:
        data = _load_json(telemetry_path)
        actual_telemetry_path = telemetry_path
    errors: list[str] = []
    screenshot_details: list[str] = []
    if data.get("scenario") == LENS_PHASE1_SCENARIO:
        return _verify_lens_phase1(data, actual_telemetry_path)
    if data.get("scenario") == ARC_PRODUCTION_SCENARIO:
        return _verify_arc_production(data, actual_telemetry_path, require_screenshot_file, screenshot_override)

    for key, expected in EXPECTED.items():
        _check_equal(data, key, expected, errors)

    for key in ("combatSceneObserved", "shipObserved", "weaponObserved", "projectileObserved", "vfxObserved", "fireCommandIssued"):
        _check_bool(data, key, errors)

    state = data.get("state")
    if state != "Completed":
        errors.append(f"state: expected 'Completed', got {state!r}")

    screenshot = str(screenshot_override) if screenshot_override is not None else data.get("screenshotPath")
    screenshot_attempt = data.get("screenshotAttemptPath")
    if require_screenshot_file:
        if not screenshot:
            errors.append("screenshotPath: required but missing")
        elif not Path(screenshot).exists():
            errors.append(f"screenshotPath: file does not exist: {screenshot}")
        else:
            screenshot_details = _check_screenshot_pixels(Path(screenshot), errors)
            if preview_reference is not None:
                screenshot_details.extend(
                    _check_preview_reference_parity(Path(screenshot), preview_reference, errors, visual_compare_output),
                )
    elif screenshot:
        if not Path(screenshot).exists():
            errors.append(f"screenshotPath: declared file does not exist: {screenshot}")
        else:
            screenshot_details = _check_screenshot_pixels(Path(screenshot), errors)
            if preview_reference is not None:
                screenshot_details.extend(
                    _check_preview_reference_parity(Path(screenshot), preview_reference, errors, visual_compare_output),
                )
    elif screenshot_attempt:
        if not Path(screenshot_attempt).exists():
            errors.append(f"screenshotAttemptPath: file does not exist: {screenshot_attempt}")
    else:
        errors.append("evidence: expected screenshotPath or screenshotAttemptPath")

    _check_screenshot_frames(data, errors)

    if errors:
        print("FAIL ASTD in-game automation evidence")
        for error in errors:
            print(f"- {error}")
        _print_result(data, actual_telemetry_path, screenshot_details, screenshot, screenshot_attempt)
        return 1

    print("PASS ASTD in-game automation evidence")
    _print_result(data, actual_telemetry_path, screenshot_details, screenshot, screenshot_attempt)
    return 0


def _print_result(
    data: dict,
    telemetry_path: Path,
    screenshot_details: list[str],
    screenshot: str | None,
    screenshot_attempt: str | None,
) -> None:
    print(f"- telemetry: {telemetry_path}")
    print(f"- scenario: {data.get('scenario')}")
    print(f"- ship: {data.get('shipId')}")
    print(f"- weapon: {data.get('weaponId')}")
    print(f"- projectile: {data.get('projectileSpecId')}")
    print(f"- vfx: {data.get('vfxPresetId')}")
    if data.get("logPath"):
        print(f"- log: {data['logPath']}")
    if screenshot:
        print(f"- screenshot: {screenshot}")
        for detail in screenshot_details:
            print(f"- {detail}")
        for frame in data.get("screenshotFrames") or []:
            print(f"- screenshot frame: {frame}")
    else:
        print(f"- screenshot attempt: {screenshot_attempt}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("telemetry", type=Path, help="Path to astd-ingame-automation-telemetry.json")
    parser.add_argument("--log", type=Path, help="Fallback starsector.log path used when script telemetry is blocked by sandbox")
    parser.add_argument("--require-screenshot-file", action="store_true", help="Require a concrete screenshotPath file instead of screenshotAttemptPath")
    parser.add_argument("--preview-reference", type=Path, help="Preview/editor reference image used for projectile structure parity")
    parser.add_argument("--visual-compare-output", type=Path, help="Optional side-by-side crop output for visual VFX review")
    parser.add_argument("--screenshot", type=Path, help="Explicit screenshot image used for manual visual review; telemetry still controls pass/fail state")
    args = parser.parse_args(argv)
    return verify(
        args.telemetry,
        args.require_screenshot_file,
        args.log,
        args.preview_reference,
        args.visual_compare_output,
        args.screenshot,
    )


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
