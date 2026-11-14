#!/usr/bin/env python3
"""将灰度流纹原图转换为贯虹突进使用的横向循环 RGBA 纹理。"""

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SIZE = (1024, 512)


def smoothstep(lo: float, hi: float, value: np.ndarray) -> np.ndarray:
    t = np.clip((value - lo) / (hi - lo), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def periodic_blend(gray: np.ndarray) -> np.ndarray:
    width = gray.shape[1]
    phase = np.arange(width, dtype=np.float32) / width
    weight = np.sin(np.pi * phase) ** 2
    return gray * weight + np.roll(gray, width // 2, axis=1) * (1.0 - weight)


def periodic_blur(gray: np.ndarray, radius: float) -> np.ndarray:
    tiled = np.tile(gray, (1, 3))
    image = Image.fromarray(np.uint8(np.clip(tiled, 0.0, 1.0) * 255))
    blurred = np.asarray(image.filter(ImageFilter.GaussianBlur(radius)), dtype=np.float32) / 255
    width = gray.shape[1]
    return blurred[:, width:width * 2]


def save_texture(alpha: np.ndarray, path: Path) -> None:
    rgba = np.full((*alpha.shape, 4), 255, dtype=np.uint8)
    rgba[:, :, 3] = np.rint(np.clip(alpha, 0.0, 1.0) * 255).astype(np.uint8)
    Image.fromarray(rgba).save(path, optimize=True)
    edge_delta = np.abs(rgba[:, 0, 3].astype(float) - rgba[:, -1, 3]).mean()
    adjacent_delta = np.abs(np.diff(rgba[:, :, 3].astype(float), axis=1)).mean()
    print(f"{path.name}: {path.stat().st_size} bytes; alpha mean={rgba[:, :, 3].mean():.2f}, "
          f"seam delta={edge_delta:.2f}, adjacent delta={adjacent_delta:.2f}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path, default=ROOT / "tools/vfx-assets/gression-flow-source.png")
    parser.add_argument("--output", type=Path, default=ROOT / "contents/graphics/fx")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with Image.open(args.source) as source:
        gray = np.asarray(source.convert("L").resize(SIZE, Image.Resampling.LANCZOS), dtype=np.float32) / 255
    gray = periodic_blend(gray)
    v = np.linspace(0.0, 1.0, SIZE[1], dtype=np.float32)[:, None]
    edge = smoothstep(0.0, 0.07, v) * (1.0 - smoothstep(0.93, 1.0, v))
    intensity = np.clip((gray - 0.012) / 0.85, 0.0, 1.0) ** 0.72
    flow = intensity * edge
    soft = periodic_blur(np.roll(intensity, 271, axis=1), 3.5)
    broad = periodic_blur(np.roll(intensity, 103, axis=1), 15.0)
    bands = np.exp(-((v - 0.16) / 0.13) ** 2) + np.exp(-((v - 0.84) / 0.13) ** 2)
    mist = np.clip((soft * 0.8 + broad * 1.4) * (0.12 + bands * 1.8), 0.0, 0.85) * edge
    save_texture(flow, args.output / "astd_gression_flow.png")
    save_texture(mist, args.output / "astd_gression_mist.png")


if __name__ == "__main__":
    main()
