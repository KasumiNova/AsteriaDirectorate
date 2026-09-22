#!/usr/bin/env python3
"""生成引力相位（astd_gravity_phase）视觉资产：

1. 描边发光掩码：<ship>_phase_outline.png
   由舰体贴图 alpha 通道外扩 2px 取环形（dilate - 原图），
   供 BoxUtil SpriteEntity emissive 层做描边发光（着色由代码侧给红色）。
2. 红色 bloom 变体：<ship>_bloom_red.png
   将 bloom 层贴图中紫色系（色相 230°~330°）像素旋转到红色（0°），其余像素（既有红/橙光点）保持不变。

用法：python3 tools/generate_phase_vfx_assets.py <hull_sprite_name>...
示例：python3 tools/generate_phase_vfx_assets.py astd_zw_002 astd_zw_103
（输入为 contents/graphics/ships/ 下的贴图基名，输出到同目录。）
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

SHIPS_DIR = Path(__file__).resolve().parent.parent / "contents" / "graphics" / "ships"

# 描边外扩半径（px）：MaxFilter 尺寸 = 2r+1
OUTLINE_RADIUS = 2
# 描边边缘柔化（高斯模糊半径，px），bloom 前的预柔化
OUTLINE_FEATHER = 0.8
# 紫色色相区间（度），该区间内像素转到红色
PURPLE_HUE_MIN = 230.0
PURPLE_HUE_MAX = 330.0
TARGET_HUE = 0.0


def generate_outline(sprite_path: Path) -> Path:
    image = Image.open(sprite_path).convert("RGBA")
    alpha = np.asarray(image.getchannel("A"), dtype=np.uint8)

    dilated = np.asarray(
        Image.fromarray(alpha).filter(ImageFilter.MaxFilter(OUTLINE_RADIUS * 2 + 1)),
        dtype=np.uint8,
    )
    # 外环 = 膨胀区 - 原实体区；沿原 alpha 比例衰减，锯齿边缘过渡自然
    ring = np.clip(dilated.astype(np.int16) - alpha.astype(np.int16), 0, 255).astype(np.uint8)
    if OUTLINE_FEATHER > 0:
        ring = np.asarray(
            Image.fromarray(ring).filter(ImageFilter.GaussianBlur(OUTLINE_FEATHER)),
            dtype=np.uint8,
        )

    outline = np.zeros((*alpha.shape, 4), dtype=np.uint8)
    outline[..., 0:3] = 255
    outline[..., 3] = ring

    out_path = sprite_path.with_name(sprite_path.stem + "_phase_outline.png")
    Image.fromarray(outline).save(out_path)
    return out_path


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
        sprite_path = SHIPS_DIR / f"{base}.png"
        bloom_path = SHIPS_DIR / f"{base}_bloom.png"
        if not sprite_path.exists():
            print(f"跳过 {base}：缺少舰体贴图 {sprite_path}")
            continue
        outline = generate_outline(sprite_path)
        print(f"描边掩码 -> {outline.name}")
        if bloom_path.exists():
            red = generate_red_bloom(bloom_path)
            print(f"红色 bloom -> {red.name}")
        else:
            print(f"跳过 {base} 红色 bloom：缺少 {bloom_path}")


if __name__ == "__main__":
    main()
