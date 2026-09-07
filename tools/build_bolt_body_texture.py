#!/usr/bin/env python3
"""生成 astd_bolt_body.png：Box 螺栓弹头贴图。

以原版 projbody.png（32×16，彗形白图，alpha 沿全长近似不透明）为基底，
把原版 ProjectileRenderer 的逐顶点 alpha 梯度（头角全亮 -> 中段减半 -> 尾端透明）
与几何收窄（头全宽 -> 尾半宽）烘焙进 alpha 通道，供 BoltRenderComponent
以单颗 SpriteEntity 均匀染色渲染出原版螺栓观感。

输出：contents/graphics/fx/astd_bolt_body.png（128×32，X=带长向，头在右/u=1——
SpriteEntity 的 u=0 映射局部 -X 即飞行后方，与原版 texCoord 方向相反，故成品水平翻转）
"""

from PIL import Image

SRC = "/mnt/store/Games/Starsector098-linux/graphics/fx/projbody.png"
DST = "contents/graphics/fx/astd_bolt_body.png"
OUT_W, OUT_H = 128, 32

# 原版纵向顶点梯度：头角 alpha=1 -> 带体中点 0.5 -> 尾端 0（线性）
def longitudinal_ramp(u: float) -> float:
    return max(0.0, 1.0 - u)

# 原版几何收窄：头全宽 -> 尾半宽；v ∈ [-1, 1] 为横向归一化坐标
def vertical_window(u: float, v: float) -> float:
    half = 1.0 - 0.5 * u  # 尾部可用半宽收窄到 0.5
    av = abs(v)
    if av >= half:
        return 0.0
    # 边缘柔和过渡，避免硬切
    soft = min(1.0, (half - av) / 0.25)
    return soft


def main() -> None:
    src = Image.open(SRC).convert("RGBA").resize((OUT_W, OUT_H), Image.BILINEAR)
    out = Image.new("RGBA", (OUT_W, OUT_H))
    for x in range(OUT_W):
        u = x / (OUT_W - 1)
        ramp = longitudinal_ramp(u)
        for y in range(OUT_H):
            v = (y / (OUT_H - 1)) * 2.0 - 1.0
            r, g, b, a = src.getpixel((x, y))
            na = int(a * ramp * vertical_window(u, v) + 0.5)
            out.putpixel((x, y), (r, g, b, na))
    out = out.transpose(Image.FLIP_LEFT_RIGHT)  # SpriteEntity u=0 朝飞行后方：头翻转到文件右/u=1
    out.save(DST)
    print(f"written {DST} ({OUT_W}x{OUT_H})")


if __name__ == "__main__":
    main()
