#!/usr/bin/env python3
"""Detect likely weapon slot decals on a ship sprite using template matching.

用途：
- 读取 `tools/weapon_slots.png` 中的槽位模板组件
- 对目标舰船贴图做多尺度模板匹配
- 按分数、去重、左右对称关系筛出候选点
- 输出预览图与 JSON，作为“自动定位 weaponSlots”的初始候选

说明：
- 这不是最终的 `.ship` weaponSlots 自动写回器，而是定位辅助器
- 对直接使用/轻度改色原版槽位 decal 的贴图效果最好
- 对重绘严重、缩放很大、旋转明显的资源，误报会增多
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import cv2
import numpy as np
from PIL import Image, ImageDraw


@dataclass(frozen=True)
class Template:
    id: int
    bbox: tuple[int, int, int, int]
    area: int
    gray: np.ndarray
    mask: np.ndarray


@dataclass
class Candidate:
    template_id: int
    score: float
    scale: float
    x: int
    y: int
    w: int
    h: int
    mirrored_score: float = 0.0

    @property
    def center(self) -> tuple[float, float]:
        return self.x + self.w / 2.0, self.y + self.h / 2.0

    def to_dict(self) -> dict:
        cx, cy = self.center
        return {
            "templateId": self.template_id,
            "score": round(self.score, 4),
            "mirroredScore": round(self.mirrored_score, 4),
            "scale": self.scale,
            "bbox": [self.x, self.y, self.w, self.h],
            "center": [round(cx, 2), round(cy, 2)],
        }


def extract_templates(sheet_path: Path, alpha_threshold: int = 10, min_area: int = 120) -> list[Template]:
    img = Image.open(sheet_path).convert("RGBA")
    arr = np.array(img)
    alpha = arr[:, :, 3]
    mask = np.where(alpha > alpha_threshold, 255, 0).astype(np.uint8)
    num, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)

    templates: list[Template] = []
    for i in range(1, num):
        x, y, w, h, area = [int(v) for v in stats[i]]
        if area < min_area:
            continue
        crop = arr[y : y + h, x : x + w]
        crop_gray = cv2.cvtColor(crop[:, :, :3], cv2.COLOR_RGB2GRAY)
        crop_mask = np.where(crop[:, :, 3] > alpha_threshold, 255, 0).astype(np.uint8)
        templates.append(Template(i, (x, y, w, h), area, crop_gray, crop_mask))
    return templates


def match_templates(
    ship_gray: np.ndarray,
    templates: Iterable[Template],
    scales: list[float],
    score_threshold: float,
    top_k_per_template: int,
) -> list[Candidate]:
    candidates: list[Candidate] = []
    for templ in templates:
        local: list[Candidate] = []
        for scale in scales:
            nw = max(4, int(round(templ.gray.shape[1] * scale)))
            nh = max(4, int(round(templ.gray.shape[0] * scale)))
            if nw >= ship_gray.shape[1] or nh >= ship_gray.shape[0]:
                continue
            resized = cv2.resize(
                templ.gray,
                (nw, nh),
                interpolation=cv2.INTER_AREA if scale < 1.0 else cv2.INTER_CUBIC,
            )
            resized_mask = cv2.resize(templ.mask, (nw, nh), interpolation=cv2.INTER_NEAREST)
            try:
                result = cv2.matchTemplate(ship_gray, resized, cv2.TM_SQDIFF_NORMED, mask=resized_mask)
            except cv2.error:
                continue
            result = np.nan_to_num(result, nan=1.0, posinf=1.0, neginf=1.0)
            flat = result.ravel()
            pick_count = min(top_k_per_template, flat.size)
            best_idx = np.argpartition(flat, pick_count - 1)[:pick_count]
            for flat_idx in best_idx:
                val = float(flat[flat_idx])
                score = 1.0 - val
                if score < score_threshold:
                    continue
                y, x = divmod(int(flat_idx), result.shape[1])
                local.append(Candidate(templ.id, score, scale, x, y, nw, nh))
        local.sort(key=lambda c: c.score, reverse=True)
        candidates.extend(local[:top_k_per_template])
    return candidates


def center_distance(a: Candidate, b: Candidate) -> float:
    ax, ay = a.center
    bx, by = b.center
    return math.hypot(ax - bx, ay - by)


def dedupe_candidates(candidates: list[Candidate], min_center_distance: float) -> list[Candidate]:
    kept: list[Candidate] = []
    for cand in sorted(candidates, key=lambda c: c.score, reverse=True):
        if any(center_distance(cand, other) < min_center_distance for other in kept):
            continue
        kept.append(cand)
    return kept


def annotate_symmetry(candidates: list[Candidate], axis_x: float, mirror_tol_x: float = 10.0, mirror_tol_y: float = 10.0) -> None:
    for cand in candidates:
        cx, cy = cand.center
        target_x = 2 * axis_x - cx
        best = 0.0
        for other in candidates:
            if other is cand:
                continue
            ox, oy = other.center
            if abs(ox - target_x) <= mirror_tol_x and abs(oy - cy) <= mirror_tol_y:
                best = max(best, other.score)
        cand.mirrored_score = best


def filter_by_symmetry(candidates: list[Candidate], min_mirrored_score: float, axis_band: float = 12.0, axis_x: float | None = None) -> list[Candidate]:
    filtered: list[Candidate] = []
    for cand in candidates:
        cx, _ = cand.center
        on_axis = axis_x is not None and abs(cx - axis_x) <= axis_band
        if on_axis or cand.mirrored_score >= min_mirrored_score:
            filtered.append(cand)
    return filtered


def draw_preview(ship_path: Path, output_path: Path, candidates: list[Candidate], axis_x: float) -> None:
    sprite = Image.open(ship_path).convert("RGBA")
    scale = 2
    canvas = Image.new("RGBA", (sprite.width * scale, sprite.height * scale), (20, 22, 35, 255))
    canvas.alpha_composite(
        sprite.resize((sprite.width * scale, sprite.height * scale), Image.Resampling.LANCZOS)
    )
    draw = ImageDraw.Draw(canvas, "RGBA")

    ax = axis_x * scale
    draw.line([(ax, 0), (ax, canvas.height)], fill=(255, 255, 0, 100), width=1)

    for i, cand in enumerate(candidates):
        x, y, w, h = [v * scale for v in (cand.x, cand.y, cand.w, cand.h)]
        line_color = (0, 255, 180, 235) if cand.mirrored_score > 0 else (255, 150, 0, 220)
        fill_color = (0, 255, 180, 40) if cand.mirrored_score > 0 else (255, 150, 0, 30)
        draw.rectangle([x, y, x + w, y + h], outline=line_color, fill=fill_color, width=2)
        label = f"{i}:{cand.template_id} {cand.score:.2f}"
        draw.text((x + 2, y - 12), label, fill=(255, 230, 120))

    draw.text((10, 10), f"slot candidates: {len(candidates)}", fill=(0, 255, 180))
    draw.text((10, 28), f"axis x={axis_x:.1f}", fill=(220, 220, 220))
    draw.text((10, 46), "green=paired by symmetry, orange=single-side candidate", fill=(220, 220, 220))
    canvas.convert("RGB").save(output_path)


def main() -> None:
    parser = argparse.ArgumentParser(description="Detect likely weapon slot decals using template matching.")
    parser.add_argument("ship_sprite", help="Path to ship sprite PNG")
    parser.add_argument("--template-sheet", default="tools/weapon_slots.png", help="Weapon slot template sheet")
    parser.add_argument("--out-dir", default="tools", help="Output directory")
    parser.add_argument("--score-threshold", type=float, default=0.72, help="Minimum template-match score")
    parser.add_argument("--min-center-distance", type=float, default=14.0, help="Deduplicate candidates by center distance")
    parser.add_argument("--min-mirrored-score", type=float, default=0.72, help="Require mirrored support score unless close to center axis")
    parser.add_argument("--top-k-per-template", type=int, default=12, help="Maximum candidates kept per template")
    parser.add_argument("--axis-x", type=float, default=None, help="Symmetry axis x; default uses image center")
    args = parser.parse_args()

    ship_path = Path(args.ship_sprite).resolve()
    template_path = Path(args.template_sheet).resolve()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    ship = Image.open(ship_path).convert("RGBA")
    ship_gray = cv2.cvtColor(np.array(ship)[:, :, :3], cv2.COLOR_RGB2GRAY)
    axis_x = args.axis_x if args.axis_x is not None else ship.width / 2.0

    templates = extract_templates(template_path)
    scales = [0.75, 0.9, 1.0, 1.1, 1.25, 1.5]
    candidates = match_templates(
        ship_gray=ship_gray,
        templates=templates,
        scales=scales,
        score_threshold=args.score_threshold,
        top_k_per_template=args.top_k_per_template,
    )
    candidates = dedupe_candidates(candidates, min_center_distance=args.min_center_distance)
    annotate_symmetry(candidates, axis_x=axis_x)
    candidates = filter_by_symmetry(candidates, min_mirrored_score=args.min_mirrored_score, axis_x=axis_x)
    candidates.sort(key=lambda c: (c.mirrored_score > 0, c.score + c.mirrored_score), reverse=True)

    stem = ship_path.stem
    preview_path = out_dir / f"{stem}_weapon_slot_matches.png"
    json_path = out_dir / f"{stem}_weapon_slot_matches.json"

    with json_path.open("w", encoding="utf-8") as f:
        json.dump(
            {
                "shipSprite": str(ship_path),
                "templateSheet": str(template_path),
                "axisX": axis_x,
                "candidateCount": len(candidates),
                "candidates": [c.to_dict() for c in candidates],
            },
            f,
            indent=4,
            ensure_ascii=False,
        )

    draw_preview(ship_path, preview_path, candidates, axis_x)

    print(f"templates: {len(templates)}")
    print(f"axis_x: {axis_x}")
    print(f"candidates: {len(candidates)}")
    for cand in candidates[:20]:
        print(json.dumps(cand.to_dict(), ensure_ascii=False))
    print(f"saved preview: {preview_path}")
    print(f"saved json: {json_path}")


if __name__ == "__main__":
    main()
