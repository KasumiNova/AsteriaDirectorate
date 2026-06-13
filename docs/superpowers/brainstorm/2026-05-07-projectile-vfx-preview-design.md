# 独立弹体拖尾预览工具设计

日期：2026-05-07

## 目标

创建一个独立网页单页工具，用于在游戏外逐帧观察 ASTD 弹体拖尾与装饰特效。工具服务于快速调参、截图对比和配置导出，降低每次修改都进入完整战斗烟测的成本。

## 交付形态

- 技术栈：TypeScript + Vite + React。
- 渲染：Canvas/WebGL，渲染语义贴近 BoxUtil 管线。
- 位置：建议放在 `tools/projectile-vfx-preview/`。
- 范围：第一版聚焦弹体拖尾、烟雾线、装饰 sprite、线段/短弧特效。

## 界面结构

- 主体：大画布预览区，优先展示拖尾效果。
- 右侧：可折叠参数面板，按 BoxUtil 实体分组。
- 左上：配置粘贴抽屉，用于输入 JSON preset。
- 底部：时间轴与观察控制区。

底部控制包括：

- 播放 / 暂停。
- 单帧前进 / 后退。
- 时间轴拖拽。
- 版本对比。
- 截图导出。

## 输入与数据流

输入使用 JSON，字段贴近 ASTD Kotlin preset 与 BoxUtil API。导入流程：

1. 粘贴 JSON 配置。
2. 执行字段校验。
3. 转换为内部预览模型。
4. 驱动 WebGL 预览。
5. 从同一个模型导出配置。

内部模型建议命名为 `BoxUtilPreviewPreset`，包含：

- `trailEntities: TrailEntityConfig[]`
- `spriteEntities: SpriteEntityConfig[]`
- `segmentEntities: SegmentEntityConfig[]`
- `timeline`
- `previewCamera`
- `simulation`

## BoxUtil API 对齐字段

### TrailEntityConfig

第一版覆盖真实 `TrailEntity` 关键字段：

- `nodes`
- `startColor`
- `endColor`
- `startEmissive`
- `endEmissive`
- `startWidth`
- `endWidth`
- `texturePixels`
- `textureSpeed`
- `uvOffset`
- `fillStartAlpha`
- `fillEndAlpha`
- `fillStartFactor`
- `fillEndFactor`
- `jitterPower`
- `flick`
- `syncFlick`
- `stripLineMode`
- `flowWhenPaused`
- `flickWhenPaused`
- `flickMixValue`
- `flickerSyncCode`

语义：节点链与累计距离模拟 `TrailEntity.submitNodes()` 的距离数据；shader 侧用距离、纹理参数和 UV 偏移产生流动拖尾。

### SpriteEntityConfig

第一版覆盖装饰 sprite 所需字段：

- `diffuse`
- `emissive`
- `baseSizePerTiles`
- `tileSize`
- `currentTileCount`
- `randomTile`
- `randomTileEachInstance`
- `startingIndex`
- `uvStart`
- `uvEnd`
- `randomSeedCode`
- `transform`
- `lifetime`
- `color`
- `emissiveColor`

用途：烟雾团、闪丝、局部高光、短寿命装饰层。

### SegmentEntityConfig

第一版覆盖线段/短弧装饰字段：

- `nodes`
- `texturePixels`
- `textureSpeed`
- `uvOffset`
- `fillStartAlpha`
- `fillEndAlpha`
- `fillStartFactor`
- `fillEndFactor`
- `interpolation`
- `flowWhenPaused`

用途：两侧细线、短弧、能量裂纹、强调线。

## 渲染核心

渲染器按 BoxUtil 风格组织，而非 MagicTrail CSV 风格组织：

- `TrailRenderer`：读取 `TrailEntityConfig`，生成相邻节点组成的拖尾网格。
- `SpriteRenderer`：读取 `SpriteEntityConfig`，绘制装饰 sprite 和烟雾贴图。
- `SegmentRenderer`：读取 `SegmentEntityConfig`，绘制线段和短弧。
- `ShaderPass`：统一处理混合、发光、透明度、噪声、UV 流动、jitter、flick。

### Trail 计算规则

- 节点按顺序形成 trail。
- 每个节点保留位置和累计距离。
- 宽度从 `startWidth` 到 `endWidth` 插值。
- 颜色从 `startColor` 到 `endColor` 插值。
- 发光从 `startEmissive` 到 `endEmissive` 插值。
- UV 使用 `texturePixels`、`textureSpeed`、`uvOffset` 计算。
- `fillStartAlpha` / `fillEndAlpha` 和 `fillStartFactor` / `fillEndFactor` 控制头尾收束。
- `jitterPower` 在垂直于 trail 的方向扰动 UV 或采样位置。
- `flick` / `syncFlick` 控制全局或分段闪烁。

### Shader 近似目标

第一版 shader 覆盖：

- 普通混合与加法混合。
- diffuse / emissive 近似。
- 透明度包络。
- UV 流动。
- 噪声扰动。
- jitter。
- flick / syncFlick。

## 配置导出

导出 BoxUtil API 风格 JSON。第一版导出 JSON，便于人工回填到 ASTD Kotlin preset。后续可以追加 Kotlin 代码片段导出。

## 示例工作流

1. 从 ASTD preset 手动复制一段配置语义。
2. 粘贴到预览工具。
3. 校验字段并生成预览模型。
4. 播放弹体飞行与拖尾生成。
5. 使用单帧控制查看烟雾、线条、UV 流动和 flick。
6. 修改参数并保存版本。
7. 使用版本对比判断差异。
8. 导出最终 JSON。
9. 人工回填到 ASTD preset。

## 第一版实现范围

必须实现：

- React 页面布局。
- JSON 粘贴与校验。
- `TrailEntityConfig` 模型。
- WebGL trail 渲染。
- 播放、暂停、单帧、时间轴。
- 配置导出。

建议实现：

- `SpriteEntityConfig` 预览。
- `SegmentEntityConfig` 预览。
- 截图导出。
- 版本对比。

暂缓实现：

- 完整战斗场景模拟。
- CSV 弹体定义链路。
- 自动生成 Kotlin 代码。
- 游戏运行时数据采集。

## 验证标准

- 工具能独立启动。
- 示例 preset 可渲染出可见拖尾。
- 时间轴与单帧控制稳定。
- 修改 `textureSpeed`、`texturePixels`、`uvOffset` 能产生可观察差异。
- 修改颜色、发光、宽度、透明度能产生可观察差异。
- 导出 JSON 可保留输入字段结构。

## 推荐下一步

创建 `tools/projectile-vfx-preview/`，初始化 Vite React TypeScript 项目，并先实现 `TrailEntityConfig`、参数校验、基础 WebGL trail 渲染与时间轴控制。
