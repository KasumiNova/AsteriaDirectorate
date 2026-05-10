#!/usr/bin/env python3
"""Generate Starsector ship bounds from sprite alpha.

思路：
1. 读取 `.ship` 文件中的 `spriteName` 与 `center`
2. 基于 alpha 阈值提取实体像素，并可选按中心轴强制左右对称
3. 从真实外轮廓中拆出“右半边路径”（top -> bottom），而不是扫描线取最外包络
4. 先用高精度路径保留真实 silhouette，再按 RDP + 小角度裁剪删掉冗余点
5. 删点时检查“新连线是否穿过过多透明像素”，避免直接跨过侧翼/凹口
6. 对局部 x 极值（侧翼尖端/凹口）设锚点保护，避免被错误合并
6. 镜像生成左侧轮廓，输出 Starsector `bounds`
7. 生成预览图：原始高精度轮廓 vs 优化后轮廓

适用前提：
- 舰船大致沿竖直方向摆正
- 对称舰建议开启 `--symmetric`
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Sequence

import cv2
import numpy as np
from PIL import Image, ImageDraw

Point = tuple[int, int]
FloatPoint = tuple[float, float]


def load_ship_data(ship_path: Path) -> dict:
    with ship_path.open("r", encoding="utf-8") as f:
        return json.load(f)


def ensure_mask_from_alpha(alpha: np.ndarray, threshold: int, axis_x: int | None, symmetric: bool) -> np.ndarray:
    working = alpha.copy()
    if symmetric and axis_x is not None:
        width = working.shape[1]
        sym = working.copy()
        for x in range(width):
            mx = 2 * axis_x - x
            if 0 <= mx < width:
                col = np.maximum(working[:, x], working[:, mx])
                sym[:, x] = col
                sym[:, mx] = col
        working = sym

    mask = np.where(working >= threshold, 255, 0).astype(np.uint8)
    # 轻微闭运算，主要是消除抗锯齿造成的细小缺口，不主动外扩轮廓。
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=1)
    return mask


def circular_slice(points: np.ndarray, start_idx: int, end_idx: int) -> list[Point]:
    if start_idx <= end_idx:
        section = points[start_idx : end_idx + 1]
    else:
        section = np.concatenate((points[start_idx:], points[: end_idx + 1]), axis=0)
    return [(int(x), int(y)) for x, y in section]


def choose_axis_nearest(points: np.ndarray, candidate_indices: np.ndarray, axis_x: int) -> int:
    best = min(candidate_indices.tolist(), key=lambda i: (abs(int(points[i, 0]) - axis_x), int(points[i, 1])))
    return int(best)


def extract_contour_half(mask: np.ndarray, axis_x: int) -> list[Point]:
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return []

    contour = max(contours, key=cv2.contourArea).reshape(-1, 2)

    min_y = int(contour[:, 1].min())
    max_y = int(contour[:, 1].max())
    top_candidates = np.where(contour[:, 1] == min_y)[0]
    bottom_candidates = np.where(contour[:, 1] == max_y)[0]

    top_idx = choose_axis_nearest(contour, top_candidates, axis_x)
    bottom_idx = choose_axis_nearest(contour, bottom_candidates, axis_x)

    path_a = circular_slice(contour, top_idx, bottom_idx)
    path_b = circular_slice(contour, bottom_idx, top_idx)
    path_b = list(reversed(path_b))

    mean_a = sum(x for x, _ in path_a) / len(path_a)
    mean_b = sum(x for x, _ in path_b) / len(path_b)
    return path_a if mean_a >= mean_b else path_b


def detect_x_extrema(points: Sequence[Point], smoothing_window: int = 9, prominence_px: float = 6.0) -> list[int]:
    if len(points) < 5:
        return []

    xs = np.array([p[0] for p in points], dtype=float)
    if smoothing_window > 1:
        kernel = np.ones(smoothing_window, dtype=float) / smoothing_window
        padded = np.pad(xs, (smoothing_window // 2, smoothing_window // 2), mode="edge")
        smoothed = np.convolve(padded, kernel, mode="valid")
    else:
        smoothed = xs

    candidates: list[tuple[int, float]] = []
    for i in range(1, len(points) - 1):
        left = smoothed[i - 1]
        curr = smoothed[i]
        right = smoothed[i + 1]
        peak_prominence = curr - min(left, right)
        valley_prominence = max(left, right) - curr
        if curr >= left and curr >= right and peak_prominence >= prominence_px:
            candidates.append((i, float(peak_prominence)))
        elif curr <= left and curr <= right and valley_prominence >= prominence_px:
            candidates.append((i, float(valley_prominence)))

    if not candidates:
        return []

    cluster_gap = max(6, (smoothing_window * 2) // 3)
    filtered: list[tuple[int, float]] = []
    for idx, score in candidates:
        if not filtered or idx - filtered[-1][0] > cluster_gap:
            filtered.append((idx, score))
            continue

        prev_idx, prev_score = filtered[-1]
        if score > prev_score:
            filtered[-1] = (idx, score)

    return [idx for idx, _ in filtered]


def remove_tiny_steps(points: Sequence[Point], min_segment_px: float = 2.0) -> list[Point]:
    if len(points) <= 2:
        return list(points)

    cleaned = [points[0]]
    for pt in points[1:-1]:
        if distance(cleaned[-1], pt) >= min_segment_px:
            cleaned.append(pt)
    cleaned.append(points[-1])
    return cleaned


def line_sample_points(a: Point, b: Point) -> list[Point]:
    steps = max(abs(b[0] - a[0]), abs(b[1] - a[1]), 1)
    samples: list[Point] = []
    for i in range(steps + 1):
        t = i / steps
        x = int(round(a[0] + (b[0] - a[0]) * t))
        y = int(round(a[1] + (b[1] - a[1]) * t))
        if not samples or samples[-1] != (x, y):
            samples.append((x, y))
    return samples


def line_transparency_stats(mask: np.ndarray, a: Point, b: Point, corridor_radius: int = 1) -> tuple[float, int]:
    h, w = mask.shape
    samples = line_sample_points(a, b)
    transparent = 0
    max_transparent_run = 0
    current_run = 0

    for x, y in samples:
        solid = False
        for oy in range(-corridor_radius, corridor_radius + 1):
            yy = y + oy
            if yy < 0 or yy >= h:
                continue
            for ox in range(-corridor_radius, corridor_radius + 1):
                xx = x + ox
                if 0 <= xx < w and mask[yy, xx] > 0:
                    solid = True
                    break
            if solid:
                break

        if solid:
            current_run = 0
        else:
            transparent += 1
            current_run += 1
            max_transparent_run = max(max_transparent_run, current_run)

    ratio = transparent / max(len(samples), 1)
    return ratio, max_transparent_run


def connection_is_supported(
    mask: np.ndarray,
    a: Point,
    b: Point,
    max_transparent_ratio: float,
    max_transparent_run: int,
    corridor_radius: int,
) -> bool:
    ratio, run = line_transparency_stats(mask, a, b, corridor_radius=corridor_radius)
    return ratio <= max_transparent_ratio and run <= max_transparent_run


def segmented_rdp(points: Sequence[Point], epsilon: float, anchors: Sequence[int]) -> list[Point]:
    if len(points) <= 2:
        return list(points)

    anchor_indices = sorted(set([0, len(points) - 1, *anchors]))
    simplified: list[Point] = []
    for start_idx, end_idx in zip(anchor_indices, anchor_indices[1:]):
        segment = points[start_idx : end_idx + 1]
        reduced = rdp(segment, epsilon) if len(segment) > 2 else list(segment)
        if simplified and reduced and simplified[-1] == reduced[0]:
            simplified.extend(reduced[1:])
        else:
            simplified.extend(reduced)
    return simplified


def distance(a: FloatPoint, b: FloatPoint) -> float:
    return math.hypot(b[0] - a[0], b[1] - a[1])


def point_line_distance(p: FloatPoint, a: FloatPoint, b: FloatPoint) -> float:
    if a == b:
        return distance(p, a)
    ax, ay = a
    bx, by = b
    px, py = p
    num = abs((by - ay) * px - (bx - ax) * py + bx * ay - by * ax)
    den = math.hypot(by - ay, bx - ax)
    return num / den


def turn_angle_delta_deg(prev: FloatPoint, curr: FloatPoint, nxt: FloatPoint) -> float:
    v1 = (curr[0] - prev[0], curr[1] - prev[1])
    v2 = (nxt[0] - curr[0], nxt[1] - curr[1])
    len1 = math.hypot(v1[0], v1[1])
    len2 = math.hypot(v2[0], v2[1])
    if len1 == 0 or len2 == 0:
        return 0.0
    dot = v1[0] * v2[0] + v1[1] * v2[1]
    cos_theta = max(-1.0, min(1.0, dot / (len1 * len2)))
    theta = math.degrees(math.acos(cos_theta))
    return abs(180.0 - theta)


def rdp(points: Sequence[Point], epsilon: float) -> list[Point]:
    if len(points) <= 2:
        return list(points)

    start = points[0]
    end = points[-1]
    max_dist = -1.0
    index = -1
    for i in range(1, len(points) - 1):
        d = point_line_distance(points[i], start, end)
        if d > max_dist:
            max_dist = d
            index = i

    if max_dist > epsilon:
        left = rdp(points[: index + 1], epsilon)
        right = rdp(points[index:], epsilon)
        return left[:-1] + right
    return [start, end]


def prune_small_turns(
    points: Sequence[Point],
    mask: np.ndarray,
    angle_threshold_deg: float,
    max_deviation_px: float,
    min_edge_px: float,
    max_transparent_ratio: float,
    max_transparent_run: int,
    corridor_radius: int,
    locked_points: set[Point] | None = None,
    iterations: int = 8,
) -> list[Point]:
    pts = list(points)
    if len(pts) <= 2:
        return pts

    locked_points = locked_points or set()

    for _ in range(iterations):
        changed = False
        new_pts = [pts[0]]
        i = 1
        while i < len(pts) - 1:
            prev = new_pts[-1]
            curr = pts[i]
            nxt = pts[i + 1]

            if curr in locked_points:
                new_pts.append(curr)
                i += 1
                continue

            deviation = point_line_distance(curr, prev, nxt)
            turn_delta = turn_angle_delta_deg(prev, curr, nxt)
            edge_a = distance(prev, curr)
            edge_b = distance(curr, nxt)

            removable = (
                (turn_delta <= angle_threshold_deg and deviation <= max_deviation_px)
                or (
                    deviation <= max_deviation_px * 0.65
                    and min(edge_a, edge_b) <= min_edge_px
                    and turn_delta <= angle_threshold_deg * 1.5
                )
            )

            if removable and not connection_is_supported(
                mask,
                prev,
                nxt,
                max_transparent_ratio=max_transparent_ratio,
                max_transparent_run=max_transparent_run,
                corridor_radius=corridor_radius,
            ):
                removable = False

            if removable:
                changed = True
            else:
                new_pts.append(curr)
            i += 1

        new_pts.append(pts[-1])
        pts = new_pts
        if not changed:
            break

    return pts


def compact_dense_vertices(
    points: Sequence[Point],
    mask: np.ndarray,
    locked_points: set[Point],
    max_cluster_edge_px: float,
    max_deviation_px: float,
    angle_threshold_deg: float,
    max_transparent_ratio: float,
    max_transparent_run: int,
    corridor_radius: int,
    iterations: int = 3,
) -> list[Point]:
    pts = list(points)
    if len(pts) <= 3:
        return pts

    for _ in range(iterations):
        changed = False
        result = [pts[0]]
        i = 1
        while i < len(pts) - 1:
            prev = result[-1]
            curr = pts[i]
            nxt = pts[i + 1]

            if curr in locked_points:
                result.append(curr)
                i += 1
                continue

            edge_a = distance(prev, curr)
            edge_b = distance(curr, nxt)
            deviation = point_line_distance(curr, prev, nxt)
            turn_delta = turn_angle_delta_deg(prev, curr, nxt)
            removable = (
                max(edge_a, edge_b) <= max_cluster_edge_px
                and deviation <= max_deviation_px
                and turn_delta <= angle_threshold_deg
                and connection_is_supported(
                    mask,
                    prev,
                    nxt,
                    max_transparent_ratio=max_transparent_ratio,
                    max_transparent_run=max_transparent_run,
                    corridor_radius=corridor_radius,
                )
            )

            if removable:
                changed = True
            else:
                result.append(curr)
            i += 1

        result.append(pts[-1])
        pts = result
        if not changed:
            break

    return pts


def mirror_points(points: Sequence[Point], axis_x: int) -> list[Point]:
    return [(2 * axis_x - x, y) for x, y in points]


def dedupe_polygon(points: Sequence[Point]) -> list[Point]:
    cleaned: list[Point] = []
    for pt in points:
        if not cleaned or cleaned[-1] != pt:
            cleaned.append(pt)
    if len(cleaned) > 1 and cleaned[0] == cleaned[-1]:
        cleaned.pop()
    return cleaned


def build_full_polygon_from_half(right_side: Sequence[Point], axis_x: int) -> list[Point]:
    left_side = list(reversed(mirror_points(right_side, axis_x)))
    full = list(right_side) + left_side
    return dedupe_polygon(full)


def to_starsector_bounds(points: Sequence[Point], center_x: int, center_y: int) -> list[int]:
    bounds: list[int] = []
    for px, py in points:
        bounds.extend([int(round(px - center_x)), int(round(center_y - py))])
    return bounds


def draw_preview(
    sprite: Image.Image,
    axis_x: int,
    center_y: int,
    raw_polygon: Sequence[Point],
    optimized_polygon: Sequence[Point],
    output_path: Path,
) -> None:
    scale = 2
    img_w, img_h = sprite.size
    canvas = Image.new("RGBA", (img_w * scale, img_h * scale), (18, 20, 32, 255))
    canvas.alpha_composite(sprite.resize((img_w * scale, img_h * scale), Image.LANCZOS))
    draw = ImageDraw.Draw(canvas, "RGBA")

    raw_scaled = [(x * scale, y * scale) for x, y in raw_polygon]
    opt_scaled = [(x * scale, y * scale) for x, y in optimized_polygon]

    if raw_scaled:
        draw.line(raw_scaled + [raw_scaled[0]], fill=(255, 140, 70, 155), width=1)
        for idx, (x, y) in enumerate(raw_scaled[:: max(1, len(raw_scaled) // 120 or 1)]):
            draw.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(255, 160, 90, 180))

    if opt_scaled:
        draw.polygon(opt_scaled, fill=(0, 220, 255, 40))
        draw.line(opt_scaled + [opt_scaled[0]], fill=(0, 220, 255, 235), width=2)
        for i, (x, y) in enumerate(opt_scaled):
            draw.ellipse([x - 4, y - 4, x + 4, y + 4], fill=(255, 80, 80), outline="white")
            draw.text((x + 6, y - 6), str(i), fill=(255, 230, 120))

    ax = axis_x * scale
    ay = center_y * scale
    draw.line([(ax, 0), (ax, img_h * scale)], fill=(255, 255, 0, 90), width=1)
    draw.line([(ax - 12, ay), (ax + 12, ay)], fill=(255, 230, 0), width=2)
    draw.line([(ax, ay - 12), (ax, ay + 12)], fill=(255, 230, 0), width=2)
    draw.ellipse([ax - 4, ay - 4, ax + 4, ay + 4], fill=(255, 230, 0))

    draw.text((10, 10), f"raw high-precision: {len(raw_polygon)} vertices", fill=(255, 160, 90))
    draw.text((10, 28), f"optimized: {len(optimized_polygon)} vertices", fill=(0, 220, 255))
    draw.text((10, 46), f"axis x={axis_x}  center=({axis_x},{center_y})", fill=(220, 220, 220))

    canvas.convert("RGB").save(output_path)


def resolve_paths(ship_path: Path, ship_data: dict) -> tuple[Path, Path]:
    mod_root = ship_path.parent.parent.parent.parent
    sprite_rel = ship_data["spriteName"]
    sprite_path = mod_root / "contents" / Path(sprite_rel)
    return mod_root, sprite_path


def resolve_center(ship_data: dict, sprite_size: tuple[int, int], center_mode: str) -> tuple[int, int]:
    if center_mode == "ship":
        return int(ship_data["center"][0]), int(ship_data["center"][1])

    width, height = sprite_size
    return width // 2, height // 2


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate symmetric-ish ship bounds from sprite alpha.")
    parser.add_argument("ship", help="Path to .ship file")
    parser.add_argument("--out-dir", default="tools", help="Output directory")
    parser.add_argument("--center-mode", choices=["image", "ship"], default="image", help="Use image center or .ship center")
    parser.add_argument("--alpha-threshold", type=int, default=24, help="Alpha threshold for solid pixels")
    parser.add_argument("--pre-rough-epsilon", type=float, default=1.8, help="Initial epsilon to collapse pixel stair-steps")
    parser.add_argument("--rdp-epsilon", type=float, default=6.0, help="RDP epsilon on half-profile")
    parser.add_argument("--angle-threshold", type=float, default=11.0, help="Remove vertices below this turn delta")
    parser.add_argument("--max-deviation", type=float, default=2.8, help="Max point-to-chord deviation for pruning")
    parser.add_argument("--min-edge", type=float, default=6.0, help="Short edge threshold used by pruning")
    parser.add_argument("--extrema-prominence", type=float, default=12.0, help="Prominence for preserving local x extrema")
    parser.add_argument("--max-transparent-ratio", type=float, default=0.18, help="Reject point removal if replacement edge crosses too much transparent space")
    parser.add_argument("--max-transparent-run", type=int, default=5, help="Reject point removal if replacement edge crosses a long transparent gap")
    parser.add_argument("--line-corridor-radius", type=int, default=1, help="Corridor radius for transparent-pixel edge validation")
    parser.add_argument("--dense-cluster-edge", type=float, default=5.5, help="Collapse dense points when both edges are shorter than this")
    parser.add_argument("--no-symmetric", action="store_true", help="Disable symmetry enforcement")
    args = parser.parse_args()

    ship_path = Path(args.ship).resolve()
    ship_data = load_ship_data(ship_path)
    mod_root, sprite_path = resolve_paths(ship_path, ship_data)
    out_dir = (mod_root / args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    sprite = Image.open(sprite_path).convert("RGBA")
    center_x, center_y = resolve_center(ship_data, sprite.size, args.center_mode)
    alpha = np.array(sprite)[:, :, 3].astype(np.uint8)
    mask = ensure_mask_from_alpha(
        alpha,
        threshold=args.alpha_threshold,
        axis_x=center_x,
        symmetric=not args.no_symmetric,
    )

    raw_half = extract_contour_half(mask, center_x)
    if len(raw_half) < 3:
        raise RuntimeError("提取到的轮廓点过少，请检查 alpha 阈值或 sprite 是否正确。")

    rough_half = rdp(raw_half, args.pre_rough_epsilon)
    rough_half = remove_tiny_steps(rough_half)
    extrema_indices = detect_x_extrema(rough_half, prominence_px=args.extrema_prominence)
    rdp_half = segmented_rdp(rough_half, args.rdp_epsilon, extrema_indices)
    locked_points = {rough_half[i] for i in extrema_indices if 0 <= i < len(rough_half)}
    optimized_half = prune_small_turns(
        rdp_half,
        mask=mask,
        angle_threshold_deg=args.angle_threshold,
        max_deviation_px=args.max_deviation,
        min_edge_px=args.min_edge,
        max_transparent_ratio=args.max_transparent_ratio,
        max_transparent_run=args.max_transparent_run,
        corridor_radius=args.line_corridor_radius,
        locked_points=locked_points,
    )
    optimized_half = compact_dense_vertices(
        optimized_half,
        mask=mask,
        locked_points=locked_points,
        max_cluster_edge_px=args.dense_cluster_edge,
        max_deviation_px=args.max_deviation * 0.8,
        angle_threshold_deg=args.angle_threshold * 0.9,
        max_transparent_ratio=args.max_transparent_ratio,
        max_transparent_run=args.max_transparent_run,
        corridor_radius=args.line_corridor_radius,
    )

    raw_polygon = build_full_polygon_from_half(raw_half, center_x)
    optimized_polygon = build_full_polygon_from_half(optimized_half, center_x)
    bounds = to_starsector_bounds(optimized_polygon, center_x, center_y)

    prefix = ship_path.stem
    json_path = out_dir / f"{prefix}_bounds.json"
    preview_path = out_dir / f"{prefix}_bounds_preview.png"

    with json_path.open("w", encoding="utf-8") as f:
        json.dump(
            {
                "shipFile": str(ship_path.relative_to(mod_root)),
                "spriteFile": str(sprite_path.relative_to(mod_root)),
                "center": [center_x, center_y],
                "originalShipCenter": ship_data["center"],
                "parameters": {
                    "centerMode": args.center_mode,
                    "alphaThreshold": args.alpha_threshold,
                    "preRoughEpsilon": args.pre_rough_epsilon,
                    "rdpEpsilon": args.rdp_epsilon,
                    "angleThreshold": args.angle_threshold,
                    "maxDeviation": args.max_deviation,
                    "minEdge": args.min_edge,
                    "extremaProminence": args.extrema_prominence,
                    "maxTransparentRatio": args.max_transparent_ratio,
                    "maxTransparentRun": args.max_transparent_run,
                    "lineCorridorRadius": args.line_corridor_radius,
                    "denseClusterEdge": args.dense_cluster_edge,
                    "symmetric": not args.no_symmetric,
                },
                "stats": {
                    "rawHalfVertices": len(raw_half),
                    "roughHalfVertices": len(rough_half),
                    "rdpHalfVertices": len(rdp_half),
                    "optimizedHalfVertices": len(optimized_half),
                    "rawFullVertices": len(raw_polygon),
                    "optimizedFullVertices": len(optimized_polygon),
                },
                "bounds": bounds,
            },
            f,
            indent=4,
            ensure_ascii=False,
        )

    draw_preview(
        sprite=sprite,
        axis_x=center_x,
        center_y=center_y,
        raw_polygon=raw_polygon,
        optimized_polygon=optimized_polygon,
        output_path=preview_path,
    )

    print(f"sprite: {sprite_path.relative_to(mod_root)}")
    print(f"center: [{center_x}, {center_y}]")
    print(f"original ship center: {ship_data['center']}")
    print(f"raw half vertices: {len(raw_half)}")
    print(f"rough half vertices: {len(rough_half)}")
    print(f"after RDP: {len(rdp_half)}")
    print(f"optimized half vertices: {len(optimized_half)}")
    print(f"optimized full vertices: {len(optimized_polygon)}")
    print()
    print("bounds:")
    print(json.dumps(bounds, ensure_ascii=False, indent=4))
    print()
    print(f"saved json: {json_path}")
    print(f"saved preview: {preview_path}")


if __name__ == "__main__":
    main()
