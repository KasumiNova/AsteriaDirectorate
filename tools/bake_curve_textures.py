#!/usr/bin/env python3
"""烘焙弹体曲线带（CurveEntity）用的灰度包络贴图。

产出 `contents/graphics/fx/astd_curve_core.png`：
- RGB 纯白（着色交给 CurveEntity 逐节点颜色），alpha 承载横向（带宽方向）衰减包络；
- 纵向（U，沿带长）恒定，长度方向的明暗由逐节点颜色表达，避免贴图平铺产生条纹；
- 包络 = 窄亮芯（弹芯）+ 宽淡晕（外辉光），即原 body/glow/shadow 三层合并后的横向剖面。

重新生成：`python3 tools/bake_curve_textures.py`
"""

import math
import os

from PIL import Image

OUT_PATH = os.path.join(os.path.dirname(__file__), "..", "contents", "graphics", "fx", "astd_curve_core.png")

TEX_U = 16    # 沿带长（恒定）
TEX_V = 128   # 沿带宽（衰减包络）


def envelope(t: float) -> float:
    """t ∈ [-1, 1]，0 为带中心。窄亮芯 + 宽淡晕的高斯复合。"""
    core = math.exp(-((t * 3.2) ** 2))
    halo = math.exp(-((t * 1.3) ** 2)) * 0.45
    return min(1.0, core + halo)


def main() -> None:
    img = Image.new("RGBA", (TEX_U, TEX_V))
    pixels = img.load()
    for v in range(TEX_V):
        t = v / (TEX_V - 1) * 2.0 - 1.0
        alpha = round(envelope(t) * 255)
        for u in range(TEX_U):
            pixels[u, v] = (255, 255, 255, alpha)
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    img.save(OUT_PATH)
    print(f"baked {OUT_PATH} ({TEX_U}x{TEX_V})")


if __name__ == "__main__":
    main()
