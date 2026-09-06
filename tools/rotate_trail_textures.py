#!/usr/bin/env python3
"""astd_trails_* 拖尾贴图转置（一次性迁移工具，2026-09 Static Trail 迁移）。

ASTD 旧自研 texTrail 约定：X=横向、Y=带长向（Y 向 REPEAT 平铺）。
BoxUtil Static Trail 约定：u(X)=带长向平铺、v(Y)=横向。
转置（transpose）后新图 X=带长向（原 Y，无缝循环保留）、Y=横向（原 X）。

注意：对同一文件重复执行会转置回原样，本脚本只做一次性迁移，跑完即归档。
"""
import sys
from pathlib import Path

from PIL import Image

FX_DIR = Path(__file__).resolve().parent.parent / "contents" / "graphics" / "fx"


def main() -> int:
    targets = sorted(FX_DIR.glob("astd_trails_*.png"))
    if not targets:
        print(f"未找到贴图：{FX_DIR}/astd_trails_*.png", file=sys.stderr)
        return 1
    for path in targets:
        with Image.open(path) as img:
            w, h = img.size
            out = img.transpose(Image.Transpose.TRANSPOSE)
            out.save(path)
        print(f"{path.name}: {w}x{h} -> {out.size[0]}x{out.size[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
