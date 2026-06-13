# Projectile VFX Parity 实施计划

日期：2026-05-12
设计来源：`.agents/superpower/brainstorm/2026-05-12-projectile-vfx-parity-design.md`
目标：预览工具与游戏内 runtime 使用同一份导出 render graph，并以 AOD-7 作为首个视觉一致基准。

## 总规则

- 后续所有任务以 subAgent 循环为准：plan → execute → verify。
- 主线程负责审核计划输出、写入文档、判断循环是否通过。
- execute agent 负责执行计划任务。
- verify agent 负责验收，并特别检查游戏内渲染预期效果是否与预览工具一致。
- 任一环节出现偏差视作循环失败，必须修正后继续。
- 只收完整产物；半成品视作失败。
- BoxUtil 源码 `/home/hikari_nova/IdeaProjects/BoxUtil/**` 只读。
- 禁止反射、动态 class lookup、try/catch 风暴、原版渲染长期降级路径。
- 导出型 trail entity 必须走局部 head-locked beam 语义，禁止再次把 projectile history 多节点作为单个导出 trail 面片。

## 文件边界

### 可修改

Preview 工具：

- `tools/projectile-vfx-preview/src/model/preset.ts`
- `tools/projectile-vfx-preview/src/model/gameExport.ts`
- `tools/projectile-vfx-preview/src/export/gameExport.ts`
- `tools/projectile-vfx-preview/src/export/kotlinExport.ts`
- `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.ts`
- `tools/projectile-vfx-preview/src/render/webglTrailRenderer.ts`
- `tools/projectile-vfx-preview/src/**/*.test.ts`
- `tools/projectile-vfx-preview/src/**/*.test.tsx`

游戏 runtime：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPreset.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxLayer.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntime.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailEntities.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/*.kt`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/*.kt`
- `src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/*.kt`

配置按需：

- `contents/data/config/astd_projectile_vfx.json`

### 只读参考

- `.agents/superpower/brainstorm/2026-05-12-projectile-vfx-parity-design.md`
- `.agents/skills/00-skill-index/SKILL.md`
- `.agents/skills/rendering-vfx-guidelines/SKILL.md`
- `.agents/skills/boxutil-guidelines/SKILL.md`
- `.agents/skills/prefix-guidelines/SKILL.md`
- `/home/hikari_nova/IdeaProjects/BoxUtil/**`

## Phase 0：基线与防偏差护栏

### 完成标准

- AOD-7 当前 preview/runtime 差异有明确基线。
- 建立“导出 trail entity = 局部双节点 head-locked beam”的测试护栏。
- 建立 preview-only 字段泄漏测试。
- 后续改动有稳定回归保护。

### Task 0.1：runtime 双节点语义测试

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailEntitiesTest.kt`

测试内容：

- `buildExportHandles()` 使用固定局部节点：
  - `node[0] = (length, yOffset)`
  - `node[1] = (0, yOffset)`
- `updateExportHandle()` 使用 `facing + 180f`。
- export trail entity 禁止使用 projectile history 多节点作为单个 runtime 面片。

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxTrailEntitiesTest
```

### Task 0.2：preview export schema 护栏测试

文件：

- `tools/projectile-vfx-preview/src/export/gameExport.test.ts`
- `tools/projectile-vfx-preview/src/export/kotlinExport.test.ts`

测试内容：

- runtime export 包含 render graph 字段：
  - `trailEntities`
  - `headLayers`
  - `glowLayers`
  - `mistLayers`
  - `sideWispLayers`
  - `ribbonDecorations`
  - `lifecycle`
  - `samplingPolicy`
- runtime export 排除 preview-only 字段：
  - `previewCamera`
  - `simulation`
  - `timeline.loop`
  - canvas/backdrop/grid 配置。

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/export/gameExport.test.ts src/export/kotlinExport.test.ts
```

### Task 0.3：护栏修正

涉及文件：

- `ASTDProjectileVfxTrailEntitiesTest.kt`
- `gameExport.test.ts`
- `kotlinExport.test.ts`

通过命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxTrailEntitiesTest
```

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/export/gameExport.test.ts src/export/kotlinExport.test.ts
```

## Phase 1：统一 preview/export/runtime 数据模型

### 完成标准

- Preview 与 Kotlin runtime 拥有同构 render graph schema。
- AOD-7 默认 preset 具备完整层级字段。
- Kotlin export 生成对象与 runtime data class 对齐。
- Preview-only 字段稳定留在 preview 侧。

### Task 1.1：TypeScript render graph 类型测试

文件：

- `tools/projectile-vfx-preview/src/model/preset.test.ts`
- `tools/projectile-vfx-preview/src/export/gameExport.test.ts`

新增断言：

- `createDefaultPreset()` 返回完整 overlay graph defaults。
- `toGameExportPreset(createDefaultPreset())` 输出：
  - `headLayers.length > 0`
  - `glowLayers.length === 4`
  - `mistLayers.length > 0`
  - `sideWispLayers.length > 0`
  - `trailEntities[0].length > 0`
  - `trailEntities[0].anchorMode === 'headLocked'`
  - `trailEntities[0].orientationMode === 'projectileVelocity'`

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/model/preset.test.ts src/export/gameExport.test.ts
```

### Task 1.2：实现 TypeScript schema

文件：

- `tools/projectile-vfx-preview/src/model/preset.ts`
- `tools/projectile-vfx-preview/src/model/gameExport.ts`

新增类型：

- `ProjectileVfxHeadLayerConfig`
- `ProjectileVfxGlowLayerConfig`
- `ProjectileVfxMistLayerConfig`
- `ProjectileVfxSideWispLayerConfig`
- `ProjectileVfxLifecycleConfig`
- `ProjectileVfxSamplingPolicyConfig`
- `ProjectileVfxOrientationMode`
- `ProjectileVfxAnchorMode`

关键字段：

- `length`
- `diffuseSpritePath`
- `emissiveSpritePath`
- `orientationMode`
- `anchorMode`
- `headLayers`
- `glowLayers`
- `mistLayers`
- `sideWispLayers`
- `lifecycle`
- `samplingPolicy`

### Task 1.3：Kotlin data class 测试

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetTest.kt`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalogTest.kt`

新增断言：

- `ASTDProjectileVfxPreset` 支持完整 render graph 字段。
- AOD-7 catalog preset 拥有：
  - `trailEntities`
  - `headLayers`
  - `glowLayers`
  - `mistLayers`
  - `sideWispLayers`
  - `lifecycle`
- AOD-7 主 trail：
  - `length` 约 `420f`
  - `anchorMode == HeadLocked`
  - `orientationMode == ProjectileVelocity`

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetCatalogTest
```

### Task 1.4：实现 Kotlin runtime spec

文件：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPreset.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailEntities.kt`

新增 data class / enum：

- `ASTDProjectileVfxHeadLayerSpec`
- `ASTDProjectileVfxGlowLayerSpec`
- `ASTDProjectileVfxMistLayerSpec`
- `ASTDProjectileVfxSideWispLayerSpec`
- `ASTDProjectileVfxLifecycleSpec`
- `ASTDProjectileVfxSamplingPolicy`
- `ASTDProjectileVfxOrientationMode`
- `ASTDProjectileVfxAnchorMode`
- `ASTDColorStopSpec`
- `ASTDFloatRangeSpec`

命名要求：

- 新增公共类型使用 `ASTD` 前缀。
- 数据 id 使用 `astd_` 前缀。

### Task 1.5：实现 export 映射

文件：

- `tools/projectile-vfx-preview/src/export/gameExport.ts`
- `tools/projectile-vfx-preview/src/export/kotlinExport.ts`

要求：

- `gameExport.ts` 输出完整 render graph JSON。
- `kotlinExport.ts` 输出 Kotlin runtime spec。
- Kotlin 生成物字段名与 `ASTDProjectileVfxPreset` 对齐。
- Rgba 导出为 `ASTDColor(...)`。
- enum 导出为 `ASTDProjectileVfxOrientationMode.ProjectileVelocity` 一类静态引用。

验证命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/export/gameExport.test.ts src/export/kotlinExport.test.ts
npm run build
```

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetCatalogTest
```

## Phase 2：Preview overlay 改为 render graph 驱动

### 完成标准

- `previewOverlayRenderer.ts` 的 head/glow/mist/sideWisp/ribbon 均读取 preset/export graph。
- 每一层支持单独开关，服务截图验收。
- AOD-7 preview 在默认参数下与目标视觉一致。
- overlay 常量迁移到默认 preset。

### Task 2.1：overlay graph 驱动测试

文件：

- `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.test.ts`
- `tools/projectile-vfx-preview/src/ui/PreviewCanvas.test.tsx`

测试内容：

- 关闭 `headLayers` 后，head draw path 被跳过。
- 关闭 `glowLayers` 后，glow stroke 被跳过。
- 修改 `mistLayers[0].blobCount` 后，mist draw 采样数量变化。
- 修改 `sideWispLayers[0].offsets` 后，side wisp path 数量变化。
- ribbon 使用 `ribbonDecorations` 字段，保留 `sampleHistoryAt` 语义。

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/render/previewOverlayRenderer.test.ts src/ui/PreviewCanvas.test.tsx
```

### Task 2.2：实现 overlay 参数化

文件：

- `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.ts`
- 可新增：`tools/projectile-vfx-preview/src/render/previewOverlayLayers.ts`

实施点：

- 将常量迁移到 `preset.lifecycle`：
  - `FLIGHT_END_RATIO`
  - `DISSOLVE_START_RATIO`
  - `PRE_DISSOLVE_FRACTION`
  - `PROJECTILE_HEAD_SIZE_SCALE`
  - `TRAIL_HISTORY_SMOOTHING_PASSES`
  - `TRAIL_HISTORY_SAMPLE_MULTIPLIER`
  - `RIBBON_WAVE_SOFTENING`
- 将 `drawProjectileHead` 参数映射到 `headLayers[]`。
- 将 `drawTrailGlowLayers` 参数映射到 `glowLayers[]`。
- 将 `drawTrailMist` 参数映射到 `mistLayers[]`。
- 将 `drawSideWisps` 参数映射到 `sideWispLayers[]`。
- 保持 `drawTrailRibbonDecorations` 的世界坐标采样语义。

### Task 2.3：添加 UI layer toggle 验收入口

文件：

- `tools/projectile-vfx-preview/src/ui/ConfigPanel.tsx`
- `tools/projectile-vfx-preview/src/ui/PresetEditor.tsx`
- 对应测试文件。

要求：

- 每个 layer group 支持启停：
  - Trail
  - Head
  - Glow
  - Mist
  - Side Wisps
  - Ribbon
- toggle 只影响预览可见性，runtime export 保留完整字段。

验证命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/render/previewOverlayRenderer.test.ts src/ui/ConfigPanel.test.tsx src/ui/PresetEditor.test.tsx
npm run build
```

## Phase 3：Runtime render graph 架构落地

### 完成标准

- `ASTDProjectileVfxRuntime` 只负责生命周期、context、history。
- 各 render layer 通过统一接口创建、更新、淡出、删除。
- Trail/Glow/SideWisp 使用 BoxUtil 优先路径。
- Head/Ribbon 具备独立 renderer 入口，允许切换到 ASTD 自定义高性能 renderer。
- 所有 layer 共享同一 `elapsed / dissolve / beamAlpha / facing / visibleLength`。

### Task 3.1：render graph 生命周期测试

文件：

- 新增：`src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRenderGraphTest.kt`
- 扩展：`src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimeTest.kt`

测试内容：

- `create()` 按 preset layer 顺序创建 render layers。
- `advance()` 给所有 layer 传递相同 context。
- `beginFadeOut()` 广播 fade reason。
- `delete()` 删除所有 render layer。
- projectile gone 后所有 layer 进入同一 fade timeline。

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRenderGraphTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeTest
```

### Task 3.2：新增 runtime render graph 包

文件：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRenderGraph.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRenderLayer.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRenderContext.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxFadeReason.kt`

接口：

```kotlin
interface ASTDProjectileVfxRenderLayer {
    fun create(engine: CombatEngineAPI, context: ASTDProjectileVfxRenderContext): Boolean
    fun advance(engine: CombatEngineAPI, context: ASTDProjectileVfxRenderContext, amount: Float)
    fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float)
    fun delete()
}
```

context 字段：

- `location`
- `velocityFacing`
- `projectileFacing`
- `renderFacing`
- `elapsed`
- `flightProgress`
- `dissolve`
- `visibleLength`
- `beamAlpha`
- `historyNodes`

### Task 3.3：重构 `ASTDProjectileVfxRuntime`

文件：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntime.kt`

要求：

- 使用 `ASTDProjectileVfxRenderGraph` 管理 layer handles。
- 保留 `advanceForTests()`。
- 保留 `historyNodesForTests()`。
- facing 优先从 projectile velocity 计算；速度过小时使用 projectile facing。
- fade reason 区分：
  - hit
  - expire
  - removed
  - dispose

验证命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeManagerTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimePluginTest
```

## Phase 4：Trail / Glow / SideWisp runtime parity

### 完成标准

- Trail 主体使用局部双节点 `TrailEntity`。
- Glow layers 使用多条局部双节点 `TrailEntity`。
- Side wisps 使用固定局部节点跟随 projectile transform。
- 运行时效果的头尾方向、长度、宽度、颜色、blend 与 preview 一致。
- AOD-7 分层截图完成 trail/glow/sideWisp 验收。

### Task 4.1：Trail renderer 测试

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailRendererTest.kt`

测试内容：

- local nodes count 恒为 2。
- `length` 控制 `node[0].x`。
- `headWidth/tailWidth` 映射到 BoxUtil start/end 语义。
- `anchorMode.HeadLocked` 使用 projectile location。
- `orientationMode.ProjectileVelocity` 使用 velocity facing。

### Task 4.2：实现 `ASTDProjectileVfxTrailRenderer`

文件：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxTrailRenderer.kt`

要求：

- 调用 `BoxUtilCombatVfx.ensureReady(engine)`。
- 添加实体使用 `BoxUtilCombatVfx.addEntity(...)`。
- `addEntity` 失败时立即 `delete()`，并记录 layer id / preset id / projectileSpecId。
- 使用 `TrailEntity.setStateVanilla(context.location, context.renderFacing + 180f)`。
- 生命周期 alpha 由 `context.beamAlpha` 统一缩放。

### Task 4.3：Glow renderer 测试

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxGlowRendererTest.kt`

测试内容：

- AOD-7 默认 glow layer 数量为 4。
- 每层 width/alpha/blur/yOffset 与 preview graph 一致。
- fade/dissolve 统一缩放 alpha。

### Task 4.4：实现 `ASTDProjectileVfxGlowRenderer`

文件：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxGlowRenderer.kt`

要求：

- 每个 glow layer 对应一条 BoxUtil `TrailEntity`。
- `yOffset` 写入 local nodes。
- gradient stops 按 head/tail 采样映射为 start/end colors。
- 保持 `CombatEngineLayers.ABOVE_PARTICLES` 或 spec 指定 layer。

### Task 4.5：SideWisp renderer 测试并实现

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxSideWispRendererTest.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxSideWispRenderer.kt`

测试内容：

- 默认 offsets 数量为 4。
- 每条 wisp 使用局部 3 节点或等价 segment。
- offsets 随 projectile transform 旋转。
- alpha 使用 `context.beamAlpha`。

阶段验证命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxTrailRendererTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxGlowRendererTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxSideWispRendererTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeTest
```

## Phase 5：Head / Mist / Ribbon runtime parity

### 完成标准

- Head 亮核支持 preview 的尖头多边形语义。
- Mist 使用确定性 hash/noise，稳定随时间漂移。
- Ribbon 使用与 preview 同源的 history sampling / wave / noise 测试向量。
- AOD-7 分层截图完成 head/mist/ribbon 验收。

### Task 5.1：共享数学测试向量

文件：

- `tools/projectile-vfx-preview/src/render/projectileVfxMath.test.ts`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxMathTest.kt`

测试向量：

- `smoothstep`
- `hermite01`
- `shaderNoise`
- `layeredNoise`
- `sampleHistoryAt`
- ribbon sine/noise/zigzag phase
- dissolve / beamAlpha / visibleLength

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/render/projectileVfxMath.test.ts
```

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxMathTest
```

### Task 5.2：实现 shared math

文件：

- `tools/projectile-vfx-preview/src/render/projectileVfxMath.ts`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxMath.kt`

要求：

- TypeScript 与 Kotlin 测试向量一致。
- 浮点误差容忍：`1e-4`。
- ribbon phase 使用 world coordinate + time，匹配 preview。

### Task 5.3：Head renderer 测试与实现

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxHeadRendererTest.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxHeadRenderer.kt`

实现路线：

- 第一选择：BoxUtil `TrailEntity` / `SpriteEntity` 组合表达亮核。
- 偏差触发条件：尖头轮廓、二次曲线肩部、shell gradient 明显失真。
- 偏差触发后：新增 ASTD 自定义 head renderer，按 projectile 批处理 buffer。

测试内容：

- `length / width / shoulderRatio / rearRatio` 生成稳定顶点。
- alpha 使用 `context.beamAlpha`。
- blend mode 与 preview `lighter/screen` 对应。

### Task 5.4：Mist renderer 测试与实现

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxMistRendererTest.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxMistRenderer.kt`

要求：

- 使用 BoxUtil instance rendering 或 `SimpleParticleControlData`。
- 粒子位置由 `projectile id + layer id + sample index` 决定。
- 每帧只更新时间相位与 transform。
- 每帧随机生成属于失败条件。

### Task 5.5：Ribbon renderer 测试与实现

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRibbonRendererTest.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRibbonRenderer.kt`

要求：

- 使用 projectile history 采样，匹配 preview `sampleHistoryAt`。
- 支持：
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
- BoxUtil `TrailEntity` 表达受限时使用 ASTD ribbon renderer。

阶段验证命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxMathTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadRendererTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxMistRendererTest --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRibbonRendererTest
```

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run -- src/render/projectileVfxMath.test.ts src/export/gameExport.test.ts src/export/kotlinExport.test.ts
```

## Phase 6：AOD-7 完整闭环接入

### 完成标准

- `astd_aod7_shot -> aod7_shot` registry 测试通过。
- AOD-7 使用完整 render graph。
- preview export 与 Kotlin runtime spec 字段一致。
- 游戏内截图与 preview 截图完成分层对照。

### Task 6.1：AOD-7 catalog/registry 测试

文件：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalogTest.kt`
- `src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileVfxRegistryRuntimeTest.kt`

断言：

- catalog 包含 `aod7_shot`。
- registry 映射包含 `astd_aod7_shot -> aod7_shot`。
- preset layer group 完整。
- trail/head/glow/mist/ribbon/sideWisp 均启用。

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetCatalogTest --tests cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxRegistryRuntimeTest
```

### Task 6.2：实现 AOD-7 preset

文件：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt`
- 按需：`contents/data/config/astd_projectile_vfx.json`

AOD-7 参数基准：

- Trail：cyan/white emissive，长度约 `420f`，宽头细尾。
- Head：白色亮核，cyan 外缘。
- Glow：4 层 glow stroke。
- Mist：长尾弱蓝雾，确定性漂移。
- Ribbon：白蓝细线，noise/sine 空间波动。
- Lifecycle：fade out `0.15s`，history fps `60`。

### Task 6.3：视觉验收流程

Preview 命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run build
npm run dev
```

游戏命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew build
./gradlew launchGame
```

截图矩阵：

1. Trail only
2. Trail + Head
3. Trail + Glow
4. Trail + Mist
5. Trail + Ribbon
6. Trail + SideWisps
7. All layers
8. Paused
9. Hit fade
10. Expire fade
11. High-speed movement
12. Curved movement preview parity sample

视觉验收标准：

- 主体长度、宽度、方向一致。
- 弹头亮核形态一致。
- glow 层级亮度一致。
- mist 尾雾密度、分布、漂移一致。
- ribbon 路径、相位、噪声、渐变一致。
- side wisps 偏移、长度、透明度一致。
- 淡出、暂停、过期表现一致。
- 画面无巨大三角片、梯形片、宽端反向、颜色过饱和、亮核缺失、尾雾缺失、ribbon 相位错位。

## Phase 7：清理、防回归、完整验收

### 完成标准

- 移除旧的近似路径和重复逻辑。
- 完整测试通过。
- Preview build 通过。
- Gradle build 通过。
- 约束扫描通过。

### Task 7.1：风险扫描

检查项：

- runtime 代码无反射/动态 class lookup。
- renderer 层无 try/catch 风暴。
- 无原版渲染 API 长期降级路径。
- BoxUtil addEntity 失败路径包含 delete + 定位日志。
- runtime schema 无 preview-only 字段。
- `/home/hikari_nova/IdeaProjects/BoxUtil` 无改动。

命令：

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
grep -R "Class.forName\|getDeclared\|java.lang.reflect" src/main/kotlin/cn/kasuminova/astd/renderer/projectile src/main/kotlin/cn/kasuminova/astd/renderer/boxutil
grep -R "addHitParticle\|addNebulaParticle\|addSmoothParticle" src/main/kotlin/cn/kasuminova/astd/renderer/projectile
git status --short /home/hikari_nova/IdeaProjects/BoxUtil
```

### Task 7.2：完整验证

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run
npm run build
```

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test
./gradlew build
```

## 失败回滚 / 纠偏策略

### Preview export 失败

- 回滚本阶段 `gameExport.ts` / `kotlinExport.ts` 改动。
- 保留新增测试。
- 先补齐 TypeScript schema，再恢复 export。
- 通过 `npm run test:run -- src/export/gameExport.test.ts src/export/kotlinExport.test.ts` 后进入下一任务。

### Runtime compile 失败

- 回滚最近一次 data class 或 renderer 接口改动。
- 保留测试。
- 先让 `ASTDProjectileVfxPresetTest` 通过，再恢复 catalog/runtime。
- 限制改动面，避免用无关重构掩盖编译错误。

### 视觉偏差

- 先关闭所有 layer。
- 按 Trail → Head → Glow → Mist → Ribbon → SideWisps 顺序逐层启用。
- 定位偏差层后只修改该 renderer/spec 映射。
- BoxUtil shader 表达能力造成偏差时，新增 ASTD 自定义 renderer，保持同一 spec 输入。

### 三角片 / 梯形片复发

- 立即检查 runtime 是否把 history 多节点传入 export trail entity。
- 恢复 `localBeamNodes(length)` 双节点语义。
- 补充测试覆盖触发 preset。

### 性能异常

- 检查每帧实体创建数量。
- Mist/Ribbon/Head 改为预分配或批处理。
- 每帧只更新 transform、alpha、phase、buffer 数据。

## Execute agent prompt 模板

```text
task: 执行 .agents/superpower/plan/2026-05-12-projectile-vfx-parity-plan.md 的下一阶段，严格按 TDD 顺序推进。

scope:
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview/src
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/src/main/kotlin/cn/kasuminova/astd/renderer/projectile
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/src/main/kotlin/cn/kasuminova/astd/renderer/boxutil
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/src/test/kotlin/cn/kasuminova/astd/renderer/projectile
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile

rules:
- 每个任务先写测试，运行并确认失败，再实现，再运行并确认通过。
- 每阶段必须形成完整可验收闭环。
- BoxUtil 源码只读。
- 禁止反射、动态 class lookup、try/catch 风暴、原版渲染长期降级路径。
- 导出型 trail entity 使用局部双节点 head-locked beam。
- AOD-7 作为基准 preset，所有 layer 使用同一份 render graph。
- 遇到视觉偏差，按 layer toggle 分层定位。

returnFormat:
- 修改文件列表。
- 每个测试命令及结果。
- 阶段完成标准逐项 pass/fail。
- 视觉验收材料路径或缺口。
- 偏差与纠偏记录。
```

## Verify agent prompt 模板

```text
task: 验收 projectile VFX preview/runtime parity 阶段结果，按计划逐项核验。

readOnlyScope:
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/.agents/superpower/plan/2026-05-12-projectile-vfx-parity-plan.md
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview/src
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/src/main/kotlin/cn/kasuminova/astd/renderer/projectile
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/src/test/kotlin/cn/kasuminova/astd/renderer/projectile
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile

checks:
- TypeScript tests pass.
- Kotlin tests pass.
- Preview build pass.
- Gradle build pass.
- Runtime export schema 无 preview-only 字段。
- AOD-7 preset 完整包含 trail/head/glow/mist/ribbon/sideWisp/lifecycle。
- Trail runtime 使用局部双节点，未使用 projectile history 多节点作为 export trail mesh。
- BoxUtil addEntity 失败路径执行 delete 并输出定位日志。
- 无反射、动态 class lookup、try/catch 风暴、原版渲染长期降级路径。
- 视觉截图按 layer matrix 完成。
- BoxUtil 工作区无改动。

returnFormat:
- pass/fail 表格。
- 失败项对应文件与证据。
- 必须修复项列表。
- 可后续优化项列表。
```
