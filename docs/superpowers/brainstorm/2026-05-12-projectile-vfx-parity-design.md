# Projectile VFX 预览/游戏内完全一致设计

日期：2026-05-12
范围：Asteria Directorate projectile VFX preview tool 与游戏内 runtime 统一渲染模型

## 目标

游戏内渲染效果与预览工具使用同一套导出参数，达成完全一致的视觉效果。AOD-7 作为第一套完整基准 preset。

完整一致的定义：

- 同一份导出 preset 同时驱动预览工具和游戏 runtime。
- 主 trail、头部亮核、glow、mist、side wisps、ribbon decoration、生命周期 dissolve 的形态、颜色、亮度、长度、动画相位一致。
- 预览工具字段缺失或语义错误时，同步修正预览工具和游戏 runtime。
- 游戏内实现以 BoxUtil 优先；BoxUtil 存在不可消除的视觉偏差时，允许新增 ASTD 自定义高性能渲染实现。

## 背景与问题

当前 AOD-7 适配失败的根因是渲染模型理解错误：

- 预览工具最终画面由 WebGL trail 与 `previewOverlayRenderer.ts` 的多层 overlay 共同形成。
- 现有游戏内实现只映射了部分 `TrailEntity` 字段，忽略了 overlay 层。
- 曾把导出型 TrailEntity 当作 projectile history 世界坐标多节点面片，导致游戏内出现巨大蓝色梯形/三角片。
- 通过单元测试验证字段存在，无法证明视觉一致。

新的设计将预览 overlay 从“预览专用画法”升级为正式可导出的 runtime render graph。

## 非目标

- 不继续做“看起来差不多”的近似实现。
- 不引入原版渲染 API 作为长期双实现降级路径。
- 不把 preview-only 字段直接塞进游戏 runtime。
- 不为兼容探测引入反射或动态 class lookup。

## 总体方案

采用统一 render graph：

```text
GameProjectileVfxPreset
  ├─ trailEntities[]          // BoxUtil TrailEntity 主体
  ├─ headLayers[]             // 弹头亮核/尖头
  ├─ glowLayers[]             // 多层 glow stroke
  ├─ mistLayers[]             // 尾部雾化/能量云
  ├─ sideWispLayers[]         // 侧向 wisps
  ├─ ribbonDecorations[]      // 装饰线/噪声路径
  ├─ lifecycle                // flight / dissolve / fade
  └─ samplingPolicy           // runtime 采样与相位
```

预览工具和游戏 runtime 分别实现同一份 render graph：

- 预览侧：WebGL renderer + overlay renderer 均由导出模型驱动。
- 游戏侧：BoxUtil renderer 优先；偏差层使用 ASTD 自定义高性能 renderer。

## 共享渲染模型

### 1. Trail 主体

字段来源：现有 `GameTrailEntityConfig`。

必须保留并统一语义：

- `startColor` / `endColor`
- `startEmissive` / `endEmissive`
- `startWidth` / `endWidth`
- `texturePixels` / `textureSpeed` / `uvOffset`
- `fillStartAlpha` / `fillEndAlpha`
- `fillStartFactor` / `fillEndFactor`
- `jitterPower`
- `flick` / `syncFlick` / `flickWhenPaused` / `flickMixValue` / `flickerSyncCode`
- `stripLineMode`
- `flowWhenPaused`
- `blendMode`

新增/显式化字段：

- `length`：局部 beam 长度，替代隐式 preview 轨迹长度。
- `diffuseSpritePath`：默认 `graphics/fx/beamcoreb.png`。
- `emissiveSpritePath`：默认 `graphics/fx/beamfringeb.png`。
- `orientationMode`：`projectileVelocity` / `projectileFacing` / `custom`。
- `anchorMode`：`headLocked`，AOD-7 使用弹头锁定局部 beam。

游戏内实现：

- AOD-7 主体使用局部双节点 `TrailEntity`。
- 节点语义：`node[0] = (length, 0)`，`node[1] = (0, 0)`，运行时 `setStateVanilla(projectile.location, facing + 180f)`。
- `startWidth` 对应尾端，`endWidth` 对应弹头端时需由导出语义明确；模型中用 `headWidth`/`tailWidth` 做语义别名，导出时生成 BoxUtil 需要的 start/end。

### 2. Head 亮核/尖头

从 `previewOverlayRenderer.drawProjectileHead` 与 `drawBeamShape` 提取为正式参数。

建议模型：

```kotlin
data class ASTDProjectileVfxHeadLayerSpec(
    val enabled: Boolean,
    val length: Float,
    val width: Float,
    val shoulderRatio: Float,
    val rearRatio: Float,
    val shellColorStart: ASTDColor,
    val shellColorMid: ASTDColor,
    val shellColorEnd: ASTDColor,
    val blur: Float,
    val alphaScale: Float,
    val blendMode: String,
)
```

游戏内实现路线：

- 优先尝试 BoxUtil `SpriteEntity` 或 `TrailEntity` 组合。
- 若无法复刻预览的尖头多边形/二次曲线轮廓，新增 ASTD projectile head renderer。
- renderer 使用预分配 buffer，按 projectile 更新 transform 与参数。

### 3. Glow layers

从 `drawTrailGlowLayers` / `drawGlowStroke` 提取。

建议模型：

```kotlin
data class ASTDProjectileVfxGlowLayerSpec(
    val widthScale: Float,
    val alphaScale: Float,
    val blur: Float,
    val yOffset: Float,
    val colorMixTail: Float,
    val colorMixHead: Float,
    val gradientStops: List<ASTDColorStopSpec>,
)
```

游戏内实现路线：

- 优先用多条 BoxUtil `TrailEntity` 表达 glow stroke。
- 每条 glow 是局部双节点，跟随 projectile。
- 需要与主 trail 共享 lifecycle alpha。

### 4. Mist layers

从 `drawTrailMist` 提取。

建议模型：

```kotlin
data class ASTDProjectileVfxMistLayerSpec(
    val enabled: Boolean,
    val blobCount: Int,
    val lengthScale: Float,
    val widthScale: Float,
    val rxRange: FloatRangeSpec,
    val ryRange: FloatRangeSpec,
    val alphaRange: FloatRangeSpec,
    val noiseScale: Float,
    val driftSpeed: Float,
    val colorStart: ASTDColor,
    val colorEnd: ASTDColor,
)
```

游戏内实现路线：

- 优先使用 BoxUtil instance rendering 或 `SimpleParticleControlData`。
- 粒子位置使用确定性 hash/noise，由 projectile id + layer id + sample index 决定。
- 禁止每帧随机生成导致画面漂移；动画相位由 `timeSeconds` 驱动。

### 5. Side wisps

从 `drawSideWisps` 提取。

建议模型：

```kotlin
data class ASTDProjectileVfxSideWispLayerSpec(
    val offsets: List<Float>,
    val widthScale: Float,
    val alphaScale: Float,
    val blur: Float,
    val lengthStartRatio: Float,
    val lengthEndRatio: Float,
    val color: ASTDColor,
)
```

游戏内实现路线：

- 优先使用 BoxUtil `TrailEntity` 或 `SegmentEntity`。
- 固定局部节点，跟随 projectile transform。

### 6. Ribbon decoration

现有 ribbon decoration 字段保留，但语义与预览保持一致。

必须统一：

- `renderMode`
- `startOffset`
- `endOffset`
- `thickness`
- `alphaScale`
- `lengthScale`
- `nodeCountScale`
- `waveAmplitude`
- `waveFrequency`
- `waveSpeed`
- `waveType`
- `noiseScale`
- `blur`
- `colorGradient`

游戏内实现路线：

- 以 projectile 位置历史作为采样源，匹配预览 `sampleHistoryAt`。
- 噪声函数要在预览和游戏内共享算法或共享测试向量。
- 曲线连接、line width、gradient stop 采样与预览一致。
- BoxUtil `TrailEntity` 无法完全复刻 blur/gradient/曲线时，使用 ASTD ribbon renderer。

### 7. Lifecycle / Flight track

从预览常量迁移为导出字段：

- `durationSeconds`
- `flightEndRatio`
- `dissolveStartRatio`
- `preDissolveFraction`
- `projectileHeadSizeScale`
- `historySampleMultiplier`
- `historySmoothingPasses`
- `ribbonWaveSoftening`

游戏内 runtime 需要维护：

- `elapsed`
- `flightProgress`
- `dissolve`
- `visibleLength`
- `beamAlpha`
- `history`

对于真实 projectile：

- `location` 来自游戏 projectile。
- `facing` 优先使用速度向量。
- `visibleLength` 可由导出 `length` 与 projectile 生命周期共同决定。

## 预览工具改造

### 模型层

- 扩展 `GameProjectileVfxPreset`，包含 trail/head/glow/mist/sideWisp/ribbon/lifecycle。
- 将 `previewOverlayRenderer.ts` 的硬编码常量移入默认 preset。
- 保留 preview camera / simulation 作为预览专用字段，但导出 runtime preset 时剥离。
- runtime 相关字段全部在 `model/gameExport.ts` 中显式定义。

### 渲染层

- `WebGLTrailRenderer` 继续负责主 trail。
- overlay renderer 改为逐层读取 render graph。
- 每一层都提供开关，用于视觉验收时单独对照。

### 导出层

- `gameExport.ts` 输出完整 render graph。
- `kotlinExport.ts` 输出 Kotlin runtime spec。
- 添加导出测试：完整字段、禁止 preview-only 字段、默认 preset 稳定性。

## 游戏 runtime 改造

### 数据模型

扩展 `ASTDProjectileVfxPreset`：

```kotlin
data class ASTDProjectileVfxPreset(
    val id: String,
    val layers: List<ASTDProjectileVfxLayer>,
    val samplingPolicy: ASTDProjectileVfxSamplingPolicy,
    val fadePolicy: ASTDProjectileVfxFadePolicy,
    val trailEntities: List<ASTDTrailEntitySpec> = emptyList(),
    val headLayers: List<ASTDProjectileVfxHeadLayerSpec> = emptyList(),
    val glowLayers: List<ASTDProjectileVfxGlowLayerSpec> = emptyList(),
    val mistLayers: List<ASTDProjectileVfxMistLayerSpec> = emptyList(),
    val sideWispLayers: List<ASTDProjectileVfxSideWispLayerSpec> = emptyList(),
    val lifecycle: ASTDProjectileVfxLifecycleSpec = ASTDProjectileVfxLifecycleSpec(),
)
```

### Runtime 状态

`ASTDProjectileVfxRuntime` 负责：

- projectile alive/fading/removed 生命周期。
- 位置、速度、facing、elapsed。
- history 采样、平滑、distance sampling。
- render graph 层实例创建、更新、删除。
- fade/dissolve alpha 传递。

### Renderer 分层

新增 renderer 包：

```text
cn.kasuminova.astd.renderer.projectile.runtime
  ASTDProjectileVfxRenderGraph.kt
  ASTDProjectileVfxRenderLayer.kt
  ASTDProjectileVfxTrailRenderer.kt
  ASTDProjectileVfxHeadRenderer.kt
  ASTDProjectileVfxGlowRenderer.kt
  ASTDProjectileVfxMistRenderer.kt
  ASTDProjectileVfxRibbonRenderer.kt
  ASTDProjectileVfxSideWispRenderer.kt
```

接口：

```kotlin
interface ASTDProjectileVfxRenderLayer {
    fun create(engine: CombatEngineAPI, context: ASTDProjectileVfxRenderContext): Boolean
    fun advance(engine: CombatEngineAPI, context: ASTDProjectileVfxRenderContext, amount: Float)
    fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float)
    fun delete()
}
```

### BoxUtil 优先策略

- Trail 主体：BoxUtil `TrailEntity`。
- Glow strokes：BoxUtil `TrailEntity`。
- Side wisps：BoxUtil `TrailEntity` / `SegmentEntity`。
- Mist：BoxUtil instance renderer。
- Head / ribbon：先评估 BoxUtil 表达能力；视觉偏差不可接受时使用 ASTD 自定义 renderer。

### 自定义 renderer 条件

满足任一条件时使用 ASTD renderer：

- BoxUtil shader 与 WebGL shader 的 alpha/fill/edge 公式导致明显偏差。
- 需要二次曲线多边形 head shape。
- 需要 gradient path + blur + noise ribbon，BoxUtil `TrailEntity` 无法表达。
- 性能要求需要批处理同类 layer。

## AOD-7 基准 preset

AOD-7 的导出参数作为首个完整用例：

- Trail 主体：cyan/white emissive，长度约 420，宽头细尾，beamcore/beamfringe。
- Head：白色高亮尖头，cyan 外缘。
- Glow：4 层 glow stroke。
- Mist：长尾微弱蓝雾。
- Ribbon：白蓝细线，noise/sine 空间波动。
- Lifecycle：fadeOut 0.15s，history fps 60。

AOD-7 验收步骤：

1. 预览工具加载导出 preset。
2. 游戏内加载同一 Kotlin spec。
3. 关闭所有 layer，只开 trail，截图对照。
4. 逐层打开 head/glow/mist/ribbon/sideWisp，截图对照。
5. 全部打开，截图对照。
6. 高速移动、暂停、淡出、命中/过期分别对照。

## 测试与验收

### 单元测试

- `GameProjectileVfxPreset` 默认值稳定。
- `kotlinExport` 输出完整 layer。
- runtime spec 禁止 preview-only 字段。
- lifecycle/dissolve 数学函数提供共享测试向量。
- ribbon noise/path sampling 提供共享测试向量。

### 集成测试

- `ASTDProjectileVfxPresetCatalogTest` 覆盖 AOD-7 完整 render graph。
- `ASTDProjectileVfxTrailEntitiesTest` 覆盖 BoxUtil 节点方向与材质绑定。
- 新增 renderer create/delete 生命周期测试。
- registry 测试确认 `astd_aod7_shot -> aod7_shot`。

### 视觉验收

- 必须使用游戏内截图与预览截图对照。
- 每层可单独开关。
- 不以“测试通过”替代视觉验收。
- 明显偏差包括：三角面片、长度错误、宽端反向、颜色过饱和、亮核缺失、ribbon 相位错误、尾部雾化缺失。

## 实施顺序

### 第一轮：统一模型与导出

- 扩展 preview `GameProjectileVfxPreset`。
- 抽出 overlay 常量为可导出字段。
- 更新 `gameExport.ts` / `kotlinExport.ts`。
- 更新 runtime data class。
- 让 AOD-7 catalog 使用完整 spec。

### 第二轮：Trail/Glow/Head

- 重写导出型 TrailEntity runtime 为局部 beam 跟随。
- 实现 glow layers。
- 实现 head renderer。
- AOD-7 单层截图验收。

### 第三轮：Mist/Ribbon/SideWisps/Lifecycle

- 实现 mist instance layer。
- 实现 ribbon path renderer。
- 实现 side wisps。
- 统一 dissolve/fade。
- AOD-7 完整截图验收。

### 第四轮：清理与防回归

- 移除错误近似实现。
- 添加风险扫描：反射、try/catch 风暴、预览专用字段泄漏。
- 文档更新：skill 中记录“预览/游戏统一导出模型”约定。
- 完整 `./gradlew test` 与 preview `npm test`。

## 风险与控制

- **BoxUtil shader 与 WebGL shader 不完全一致**：通过共享测试向量和截图分层对照定位；必要时 ASTD 自定义 renderer。
- **性能风险**：mist/ribbon/head 批处理，避免每帧创建大量实体。
- **参数膨胀**：默认值集中定义，AOD-7 只覆盖必要字段。
- **生命周期分叉**：runtime context 统一计算 elapsed/dissolve/alpha。
- **误把预览字段带入游戏**：明确 runtime/export schema，测试禁止 preview camera/simulation 等字段泄漏。

## 完成标准

AOD-7 在游戏内与预览工具使用同一份导出参数后：

- 主体长度、宽度、头尾方向一致。
- 弹头亮核形态一致。
- glow 层级亮度一致。
- mist 尾雾密度与分布一致。
- ribbon decoration 路径、噪声、颜色渐变一致。
- 暂停、淡出、过期表现一致。
- 视觉验收截图确认无明显偏差。
