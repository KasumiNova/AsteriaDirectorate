# AOD-7 Projectile VFX Parity 纠偏计划

日期：2026-05-12
触发原因：用户实测截图显示游戏内效果与编辑器预览偏差巨大。游戏内左侧出现巨大白色发光团与长框投影，右侧弹头很小；编辑器效果为右侧弹头、向左延伸的细长蓝白光束。

## 根因假设

### P0：BoxUtil Trail 头尾语义反转

当前 runtime 中 Trail/Glow 使用 `(length, 0) -> (0, 0)` 与 `renderFacing + 180f`，并把 `startWidth/startColor/startEmissive` 直接写入 BoxUtil start 端。AOD-7 的 start 语义是 head 端：`startWidth=80f`、白色高 emissive。BoxUtil 文档语义中 node index 0 是 trail endpoint，start 端被放到尾部后，左侧尾部变成巨大白团，右侧 head 端变细变暗。

### P1：preview 设计宽度被 runtime 当作世界宽度

Preview 使用 `widthBase = max(startWidth * 0.075, 3.5)`。AOD-7 的 `startWidth=80` 在 preview 中实际为 `widthBase=6`，glow0 宽度约 `32.4`。Runtime 直接 `setStartWidth(80)`，Glow 再乘 `5.4` 后达到 `432` 级别，形成截图中的过粗光团和矩形投影。

### P2：层间坐标契约分裂

Trail/Glow 使用 `renderFacing + 180f`，Head/Mist/SideWisp 使用 `renderFacing`。预览器统一使用 head `(0,0)`、尾迹沿负 X 延伸。Runtime 内部存在两套契约，导致束体与弹头错位。

### P3：Trail/Glow 未使用 visibleLength

Runtime context 中有 `visibleLength`，但 Trail/Glow local nodes 仍使用静态 `layer.length`。预览器使用随飞行和 dissolve 变化的可见长度，生命周期阶段会不同步。

### P4：Head/Beam 填充形态缺失

Preview 的 beam/head 是填充多边形、渐变、blur、shadow。Runtime head 当前用线段近似，P0/P1 修正后仍可能存在尖头亮核和主体填充差异。

## 必须修复项

1. 统一本地坐标契约：head 固定 `(0,0)`，尾迹沿负 X；所有层使用 `context.renderFacing`。
2. 统一 head/tail 语义映射：模型 start=head、end=tail；BoxUtil node0=tail、node1=head；BoxUtil start 写 tail 参数，end 写 head 参数。
3. 统一 preview 宽度换算：Kotlin runtime 使用 `widthBase=max(startWidth*0.075f,3.5f)` 并派生 glow/sideWisp/ribbon 宽度。
4. Trail/Glow 每帧使用 `context.visibleLength` 更新 local nodes。
5. 增加跨语言 layout helper 与固定向量测试。
6. 修正 legacy export handle 契约。
7. 补充分层 debug 开关，支持逐层游戏内截图。
8. 对 Beam/Head 使用 ASTD 自定义 shape renderer 表达 preview 填充语义。
9. 更新验收文档，记录本轮截图反馈、根因与修复后采集要求。

## 执行步骤

### Phase 0：纠偏护栏

新增测试：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxCoordinateContractTest.kt`
  - AOD-7 head = `(0,0)`，tail.x < 0。
  - facing `0f` 时尾部位于世界左侧。
  - Trail/Glow/Head/Mist/SideWisp 使用 `context.renderFacing`。
  - legacy export handle 使用同一契约。

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxWidthMappingTest.kt`
  - AOD-7 `widthBase=6f`。
  - glow0 width = `32.4f`。
  - sideWisp width = `1.2f`。
  - runtime 不再把 `80/432` 当作最终 beam/glow 宽度。

- `tools/projectile-vfx-preview/src/render/projectileVfxLayout.test.ts`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxLayoutParityTest.kt`
  - TS/Kotlin 对齐 `beamAlpha/dissolve/visibleLength/widthBase/glow endpoints/head vertices/side wisp paths`。

### Phase 1：共享 layout helper

新增/修改：

- `tools/projectile-vfx-preview/src/render/projectileVfxLayout.ts`
- `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.ts`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxLayout.kt`

抽出并同构实现：

- `widthBase`
- `computeFlightTrack` / `visibleLength`
- `trailLocalNodes(visibleLength, yOffset)`
- `glowLocalNodes(visibleLength, widthBase, glow)`
- `headVertices(...)`
- `sideWispLocalPaths(...)`
- `mistSamples(...)`

### Phase 2：Trail/Glow 修正

`ASTDProjectileVfxTrailRenderer.kt`：

- local nodes 改为负 X tail → head。
- `setStateVanilla(context.location, context.renderFacing)`。
- 每帧按 `context.visibleLength` 刷新节点。
- BoxUtil start 端写 tail width/color/emissive。
- BoxUtil end 端写 head width/color/emissive。
- 宽度使用 preview widthBase 派生值。

`ASTDProjectileVfxTrailEntities.kt`：

- `localBeamNodes()` 使用负 X。
- `updateExportHandle()` 使用 facing 原值。
- export graph mapping 与 TrailRenderer 一致。

`ASTDProjectileVfxGlowRenderer.kt`：

- 使用 preview `drawGlowStroke()` 等价 endpoints。
- width = `widthBase * widthScale`。
- 使用 `context.renderFacing`。
- tail/head color mapping 与 BoxUtil start/end 对齐。
- alpha = `context.beamAlpha * fade.alpha() * glow.alphaScale`。

### Phase 3：Head/Beam shape

`ASTDProjectileVfxHeadRenderer.kt`：

- head 顶点保持 preview 负 X 坐标。
- 测试覆盖 tip/rear/shoulder 与 alpha。

新增：

- `ASTDProjectileVfxBeamShapeRenderer.kt`
- `ASTDProjectileVfxHeadShapeRenderer.kt`
- `ASTDProjectileVfxShapeRenderLayer.kt`

要求：

- 移植 preview `drawBeamShape()` 和 `drawProjectileHead()` 的本地 polygon。
- 使用预分配 vertex/color buffer。
- AOD-7 all-layers 默认启用 shape layer。

### Phase 4：debug layer visibility

新增：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxDebug.kt`
- `contents/data/config/astd_projectile_vfx_debug.json`

支持：

- `trail`
- `head`
- `glow`
- `mist`
- `sideWisps`
- `ribbon`
- `logLayoutOnce`

配置缺失时全部启用。

### Phase 5：视觉验收

采集路径：

- Preview：`docs/dev-docs/projectile-vfx-parity/captures/preview/<matrix-id>.png`
- Game：`docs/dev-docs/projectile-vfx-parity/captures/game/<matrix-id>.png`

新增报告：

- `docs/dev-docs/projectile-vfx-parity/2026-05-12-aod7-correction-report.md`

通过标准：

- Head 位于右侧。
- Trail/Glow 向左延伸。
- 左端尾部收细、暗化、透明。
- 右端弹头与束体同轴。
- 无左侧巨大白团。
- 无 432 级别宽度膨胀和长框投影。
- All layers 呈现细长、平滑、蓝白渐变。

## 验证命令

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview
npm run test:run
npm run build
```

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests 'cn.kasuminova.astd.renderer.projectile.*'
./gradlew build
```

## Execute agent prompt

```text
task: 执行 AOD-7 projectile VFX parity 纠偏，实现游戏内与预览器同一导出模型视觉一致。

projectRoot:
- /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate

mustRead:
- .agents/superpower/brainstorm/2026-05-12-projectile-vfx-parity-design.md
- .agents/superpower/plan/2026-05-12-projectile-vfx-parity-plan.md
- .agents/superpower/plan/2026-05-12-projectile-vfx-parity-correction-plan.md
- .agents/skills/00-skill-index/SKILL.md
- .agents/skills/rendering-vfx-guidelines/SKILL.md
- .agents/skills/boxutil-guidelines/SKILL.md

context:
- 用户实测：游戏内左侧巨大白色发光团，长框/投影明显，头部在右侧很小。
- 预览器：右侧弹头、向左延伸的细长蓝白光束，整体更细、更平滑。
- 最高概率根因：BoxUtil start/end 与 head/tail 反转；runtime 直接使用 preview raw width；Trail/Glow 使用 renderFacing+180f；Trail/Glow 未使用 visibleLength；Head/Beam 填充多边形缺失。

readOnly:
- /home/hikari_nova/IdeaProjects/BoxUtil/api/src
- /home/hikari_nova/IdeaProjects/BoxUtil/shaders

editable:
- src/main/kotlin/cn/kasuminova/astd/renderer/projectile
- src/test/kotlin/cn/kasuminova/astd/renderer/projectile
- tools/projectile-vfx-preview/src/render
- tools/projectile-vfx-preview/src/model
- tools/projectile-vfx-preview/src/export
- tools/projectile-vfx-preview/src/**/*.test.ts
- docs/dev-docs/projectile-vfx-parity
- contents/data/config/astd_projectile_vfx_debug.json

constraints:
- BoxUtil 优先，BoxUtil 源码只读。
- 禁止反射和动态 class lookup。
- 完整实现；半成品视作失败。
- AOD-7 优先，所有修复必须保持现有 projectile VFX tests 通过。

implementationSteps:
1. 新增坐标契约测试，先失败。
2. 新增宽度映射测试，先失败。
3. 抽出 TS preview layout helper 与测试。
4. 新增 Kotlin layout helper 与 TS/Kotlin 向量对齐测试。
5. 修正 TrailRenderer：负 X local nodes、renderFacing 原值、BoxUtil tail/head mapping、visibleLength、widthBase。
6. 修正 TrailEntities legacy export handle。
7. 修正 GlowRenderer：preview endpoints、widthBase、renderFacing、tail/head color mapping。
8. 修正 Head/Beam：保留 preview 负 X 坐标，新增 shape renderer 表达填充多边形。
9. 新增 runtime debug layer visibility。
10. 运行 npm/Gradle 测试与 build。
11. 更新纠偏报告并标注需人工截图确认的项目。

returnFormat:
- 列出改动文件。
- 列出新增测试。
- 粘贴关键测试命令结果。
- 给出 layer-by-layer 验收状态。
- 标注仍需人工截图确认的项目。
```
