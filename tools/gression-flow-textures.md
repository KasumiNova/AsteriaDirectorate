# 贯虹突进流纹素材

原图为本任务使用 `gpt-image-2.6-sunburst` 生成的灰度能量流纹，存放在 [gression-flow-source.png](vfx-assets/gression-flow-source.png)。原图只用于素材处理，不随模组资源打包。

执行以下命令重新生成两张运行时贴图，需要 Python、NumPy 和 Pillow：

```bash
python3 tools/generate_gression_flow_textures.py
```

输出：

- [astd_gression_flow.png](../contents/graphics/fx/astd_gression_flow.png)：细丝状主流纹。
- [astd_gression_mist.png](../contents/graphics/fx/astd_gression_mist.png)：两侧较强的柔和雾纹。

两张贴图均为 1024×512 RGBA，RGB 为白色，Alpha 表示流纹强度。U 轴沿包络纵向循环流动，V 轴横跨包络宽度。颜色由游戏渲染参数设置。

处理工具通过周期权重混合原图与半周期偏移图，使横向边界连续；雾纹模糊使用横向三次平铺后裁取中心区域，避免引入边缘断层。上下边缘平滑降至透明。脚本输出透明度均值、横向接缝变化和普通相邻列变化，用于检查素材处理结果。

原图生成要求：黑色背景上的灰白细丝和云雾，主要沿水平方向延伸，保留透明化所需的暗区，不含舰船、场景、粒子、圆环、透视或文字。
