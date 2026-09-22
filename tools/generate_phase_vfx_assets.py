#!/usr/bin/env python3
"""生成引力相位（astd_gravity_phase）视觉资产：

红色 bloom 变体：<ship>_bloom_red.png
将 bloom 层贴图中紫色系（色相 230°~330°）像素旋转到红色（0°），其余像素（既有红/橙光点）保持不变。
由 ShipGlowRenderer 的相位换色（setRecolor）加载使用。

（描边发光已由运行期 BoxUtil genLegacySDF 动态生成，不再产出预生成贴图。）

用法：python3 tools/generate_phase_vfx_assets.py <hull_sprite_name>...
示例：python3 tools/generate_phase_vfx_assets.py astd_zw_002 astd_zw_103
（输入为 contents/graphics/ships/ 下的贴图基名，输出到同目录。）
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image

SHIPS_DIR = Path(__file__).resolve().parent.parent / "contents" / "graphics" / "ships"

# 紫色色相区间（度），该区间内像素转到红色
PURPLE_HUE_MIN = 230.0
PURPLE_HUE_MAX = 330.0
TARGET_HUE = 0.0


def generate_red_bloom(bloom_path: Path) -> Path:
    image = Image.open(bloom_path).convert("RGBA")
    hsv = np.asarray(image.convert("HSV"), dtype=np.float32)
    hue = hsv[..., 0] * (360.0 / 255.0)
    mask = (hue >= PURPLE_HUE_MIN) & (hue <= PURPLE_HUE_MAX) & (hsv[..., 1] > 0)
    # 色相差映射到目标红：保持相对偏移（紫内部色阶保留）
    hsv[..., 0][mask] = ((hue[mask] - PURPLE_HUE_MIN) / (PURPLE_HUE_MAX - PURPLE_HUE_MIN) * 20.0 + TARGET_HUE) * (255.0 / 360.0)

    red = Image.fromarray(hsv.astype(np.uint8), "HSV").convert("RGBA")
    red.putalpha(image.getchannel("A"))

    out_path = bloom_path.with_name(bloom_path.stem + "_red.png")
    red.save(out_path)
    return out_path


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    for base in sys.argv[1:]:
        bloom_path = SHIPS_DIR / f"{base}_bloom.png"
        if bloom_path.exists():
            red = generate_red_bloom(bloom_path)
            print(f"红色 bloom -> {red.name}")
        else:
            print(f"跳过 {base}：缺少 {bloom_path}")


if __name__ == "__main__":
    main()
