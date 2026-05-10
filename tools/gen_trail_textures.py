#!/usr/bin/env python3
"""[DEPRECATED] MagicTrail trail-texture generator.

本模组已按需求完全移除 MagicTrail 路线，AOD-7 等弹体拖尾统一回滚为 BoxUtil 的 TrailEntity 渲染。

历史上该脚本会在 contents/graphics/fx/ 下生成 smd_trail_aod7_*.png 供 MagicTrail 使用。
现在这些纹理不再被代码引用；脚本保留为占位符（避免外部文档/个人工作流的路径引用失效）。
"""


def main() -> None:
    print(
        "MagicTrail 已移除：该脚本不再生成纹理。\n"
        "如需修改拖尾外观，请调整 Kotlin 侧 BoxUtilProjectileTrails 的 style 参数。"
    )


if __name__ == "__main__":
    main()
