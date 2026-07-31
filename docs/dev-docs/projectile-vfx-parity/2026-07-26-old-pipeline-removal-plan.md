# P5 旧弹体 VFX 管线移除计划

> 状态：**已执行完成**（2026-07-26，提交 95af219 P5.2/P5.3 + 0e24dc2 删除搬迁合一，全量 379 测试绿）
> 前置：24/24 弹体 + 3 光束已全部迁到 `RenderEntity`+DSL 管线并进游戏目检通过；
> 两个 dispatcher 的 `isMigrated ? 新 : 旧` 分支恒走新路，旧管线已是零运行时流量。

## 1. 盘点结论

### 1.1 旧管线本体（删除对象）

主源：
- `renderer/projectile/ASTDProjectileVfxPreset.kt`（preset 定义本体；**同文件内含共享 spec 类型，须先拆出**，见 1.3）
- `renderer/projectile/ASTDProjectileVfxPresetCatalog.kt`
- `renderer/projectile/ASTDProjectileVfxPresetComponentAccess.kt`（无调用方，纯桥接）
- `renderer/projectile/ASTDProjectileVfxRuntime.kt` / `RuntimeManager.kt` / `RuntimePlugin.kt` / `RuntimeTelemetry.kt`
- `renderer/projectile/component/`（ComponentRegistry / ComponentContext / ComponentSpec，3 个文件）
- `renderer/projectile/reload/`（HotReloadManager / HotReloadSource，热重载空接缝）
- `renderer/projectile/runtime/ASTDProjectileVfxRenderGraph.kt`（旧渲染图；**同文件内含共享类型，须先拆出**）
- `renderer/projectile/runtime/ASTDProjectileVfxDebug.kt`（仅旧 Runtime/ComponentRegistry 引用）
- `combat/effect/generic/projectile/ProjectileVfxRegistry.kt`（presetId 路由表）

数据面：
- `contents/data/config/astd_projectile_vfx.json`（Registry 路由配置）
- `contents/data/config/astd_projectile_vfx_debug.json`（仅 Debug 类加载）
- `contents/data/config/astd_projectile_vfx_presets/`（已为空目录）

### 1.2 死代码簇（趁机一并删除）

`ProjectileVfxPresets.kt` / `CodeProjectileRenderer.kt` / `CompositeProjectileVisual.kt` / `ProjectileVfxEnhancer.kt`
——四者互相引用，**主源零外部调用方**（OldPathRemovalTest 的 allowlist 只是准许存在，不是活路）。

仅被该死簇引用、随之孤儿化的：
- `combat/effect/lens/signature/singularity/SingularityAccretionDiskVisual.kt`
- `SingularityRetargetPulseVisual.kt`
- `SingularityShotDownDetonationVisual.kt`

（注意：`SingularityDetonationFx` 是活的——`SingularityOnHitEffect` 在用，保留。）

### 1.3 新旧共享层（**保留**，建议随迁）

新管线（`impl/render`、`renderer/projectile/driver`）仍在用的几何/类型，不是旧管线：

| 文件 | 新管线用途 |
|---|---|
| `runtime/` 的 Body/Glow/Head/Mist/Ribbon/SideWisp/Trail 七个 Renderer + BodyRenderManager + Layout + Centerline + SoftMesh + Math | MeshRenderComponents 调其 `*ForTests` 纯网格数学；DriverImpl 用 Layout |
| `runtime/ASTDProjectileVfxRenderGraph.kt` 内的 `ASTDProjectileVfxRenderContext` / `ASTDProjectileVfxLayerFadeState` | 拆出保留，旧 RenderGraph 本体删除 |
| `ASTDProjectileVfxPreset.kt` 内的 `ASTDColor` / 各 LayerSpec / AnchorMode | 拆出保留，Preset 本体删除 |
| `ASTDProjectileVfxTrailEntities.kt`（TrailEntitySpec/TrailLayerSpec/RibbonDecorationSpec）、`ASTDProjectileVfxLayer.kt`、`ASTDProjectileHistory.kt` | 新旧共用的 spec 类型与历史采样 |
| `BoxUtilProjectileTrails` | 新 DriverImpl 在用 |

**搬迁方案**：上述共享类型/几何类从 `renderer/projectile/**` 迁入 `impl/render`（纯 package 移动 + import 更新，零逻辑改动），使 `renderer/projectile/` 整包可删。搬迁后七个 Renderer 上仅供旧 Runtime 调用的 OGL render 入口成为死方法，顺手剪掉，只留 `*ForTests` 数学与 `Handle`。

### 1.4 保留不动（活人用）

`ProjectileTracerManager`（OglEllipseRingRenderer/BoxUtilProjectileTrails 在用）、`OglEllipseRingRenderer`（多处战斗特效在用）、
`ProjectileMissileAiInjector`、`ProjectileVfxDispatchState`、`ProjectileVfxKeys`（两个 dispatcher 在用）。

### 1.5 唯一真实外部消费者：`ASTDAutomationCombatPlugin`

AOD-7 自动化场景（SSOptimizer 截图取证链路）引用：
- `ASTDProjectileVfxPresetCatalog.preset(VFX_PRESET_ID)` + `Layout.previewFlightTrack/previewFlightLayout`（参考轨迹/长度）
- `ASTDProjectileVfxRuntimeTelemetry.snapshot()`（trackedCount / lastPresetId / lastProjectileSpecId / lastVisibleLength / lastElapsed）

已 grep 确认 **SSOptimizer 不解析这些字段**，爆炸半径仅限本仓。迁移方式：
1. `ProjectileVfxDriverPlugin` 增加等价的最小遥测快照（同名五字段）。
2. 参考轨迹改从新 spec（`ProjectileVfxSpecs.build("astd_aod7_shot")` 的 policy）取 `primaryTrailStartWidth`/`layoutReferenceWidth`；新管线「存活期不溶解」，旧的 `dissolveStartRatio`/`preDissolveFraction`/`flightEndRatio` 入参失去意义，`Layout.preview*` 签名相应简化。
3. `writeDiagnostics` 的 JSON 字段同步换源。

## 2. 执行步骤

### P5.1 共享层搬迁（解锁删除）
- 拆出并迁 `impl/render`：1.3 表格全部内容；`renderer/projectile/` 下仅余待删文件。
- 七个 Renderer 剪掉旧 OGL 入口，留 `*ForTests` 数学。
- 几何数学测试（Body/Glow/Head/Mist/Ribbon/SideWisp/Trail/Centerline/Layout/Math/WidthMapping/CoordinateContract/TrailEntities/BodyRenderManager/History 共 15 个测试文件）仅改 import，不断言变化。

### P5.2 入口切换
- `ProjectileSpecOnFireDispatcher` / `ProjectileSpawnVfxDispatcher`：删 `ProjectileVfxRegistry.ensureLoaded()` 与 `presetFor` 门，`isMigrated` 分支改为唯一路径（未迁移 spec 即无 VFX，scanner 的 no-handler 日志保留）。
- `CombatVfxBootstrap`：删 `ASTDProjectileVfxRuntimePlugin.ensureInstalled`；顺手补齐该文件既有空 catch 的 log 输出（CLAUDE.md 规范，触碰即清）。
- 删 `ProjectileVfxRegistry.kt` + `astd_projectile_vfx.json`。

### P5.3 自动化场景迁移
按 1.5 三步走。验收：AOD-7 自动化场景在新遥测下能走到 `Completed`（进游戏跑一次 scenario 截图链路）。

### P5.4 删除 + 测试清理
- 删 1.1 主源全部、1.2 死代码簇全部、1.1 数据面三个配置。
- 测试处置（共 29 个受影响文件）：
  - **随删除一并移除**（验证对象消失）：PresetCatalogTest / PresetTest / ComponentMigrationTest / ComponentRegistryTest / ExportCompatibilityTest / RenderGraphTest / RuntimeManagerTest / RuntimePluginTest / RuntimeTest / HotReloadManagerTest / DebugTest / ProjectileVfxRegistryRuntimeTest。
  - **迁移 parity 测试使命完成，删除**：Aod7GoldenParityTest / GenericSpecParityTest（等价性已被进游戏目检确认；旧 Runtime 删除后无比较对象。新管线长期回归由 ProjectileVfxDriverTest / ProjectileVfxSpecsTest / BeamVfx 系列承担）。
  - **改写**：ProjectileVfxOldPathRemovalTest 改为「`renderer/projectile` 旧包不存在 + dispatcher 不引用 RuntimeManager/Registry」的守卫（防回潮）；ProjectileSpecOnFireDispatcherRuntimeTest 去掉旧 RuntimeManager 断言行。
  - **仅改 import**：P5.1 的 15 个几何测试。
- 全量 `./gradlew test` 绿 + 进游戏抽验 aod7 / tsm2 / spc3 各一发。

## 3. 风险与取舍

- **tsm2 奇点三视觉被删**：AccretionDisk/RetargetPulse/ShotDownDetonation 原是旧 Preset 给奇点导弹的在飞视觉，迁移时未进新 DSL spec，本次随死簇删除。目检已确认当前表现可接受；若日后想找回，从 git 历史恢复并改写为新管线节点。
- **parity 安全网消失**：删旧管线后即无「新旧等价」可测，几何回归靠保留的 15 个数学测试 + golden 拓扑测试。这是迁移收官的既定取舍，不是遗漏。
- ~~**搬迁 PR 与删除 PR 分开提交**~~：实际执行时删除与搬迁编译互相依赖（旧文件同包引用共享类型），合为一个提交（0e24dc2）并在提交信息中注明。

## 4. 执行偏差记录（实际 vs 计划）

- **执行顺序调整为** P5.2 入口切换 → P5.3 自动化遥测（95af219）→ P5.4a 删除 + P5.1 搬迁（交错合一）→ P5.4b 剪旧 OGL 入口（均落入 0e24dc2）。
- **1.2 死代码簇误判两处，执行中纠正**：
  - `ProjectileVfxEnhancer` / `CompositeProjectileVisual` 实为**活代码**（新管线 DriverImpl→BoxUtilProjectileTrails→ProjectileTracerManager→Enhancer.decorate 叠加通用烟雾层），误删后从 git 恢复，**保留**。
  - `ProjectileVfxPresets.kt` 内的 `spawnRing` 被 `HighFluxShieldPressureOnHitEffect`/`Fdp4DelayedFission` 使用，抽出为独立文件 `ProjectileVfxUtil.kt` 保留。教训：死簇分析须查文件内部每个 object 的外部引用，不能只查文件名。
- **scanner 相关（ProjectileVfxKeys 的 SCAN_* / DispatchState 计数）是死类**，随 P5.2 一并删除；DispatchState/CombatVfxBootstrap 的空 catch 全部补 log.warn（CLAUDE.md 规范）。
- **ASTDProjectileHistoryNode 归属微调**：数据类放 `api/render`（FrameState 在 api 层，避免 api→impl 反向依赖），采样器 `ASTDProjectileHistory` 在 `impl/render`。
- **自动化遥测**：新链为 `ProjectileVfxDriver.telemetry` → `ProjectileVfxDriverPlugin.telemetrySnapshot(engine)`（trackedCount/lastProjectileSpecId/lastElapsed/lastVisibleLength/lastBeamAlpha/lastWorldUnitsPerPixel）；diagnostics 删 `runtimeLastPresetId` 字段。`VFX_PRESET_ID = "aod7_shot"` 常量保留为 SSOptimizer 跨仓契约描述符（其 helper/verifier 硬编码该字面值，已加注释说明）。
- **ProjectileVfxOldPathRemovalTest 未改写为守卫测试**：属 CLAUDE.md 禁止的「验证功能是否删除」+ 纯源码 contain 测试，直接删除。
- **P5.4b 剪旧渲染入口**：7 个 renderer 的 `*RenderLayer` 层类、`ASTDProjectileVfxRenderLayer` 接口、`ASTDProjectileVfxFadeReason`、`TrailRenderer.createEntity` 全删；保留 `*ForTests` 纯数学、`TrailRenderer.applyLayer`（RenderEntityImpl 在用）、`BodyRenderManager.Handle`、`ASTDProjectileVfxLayerFadeState`（RenderEntityImpl/MeshRenderComponents 在用）。引用层类的 7 个测试方法随之删除（BodyRenderManager 覆盖由独立测试承担）。
- **几何测试夹具化**：13 个几何测试从已删 Catalog 取数改为 `GeometryTestFixtures.Aod7Fixture`（旧 aod7 preset 数值 1:1 硬编码）+ `testContext()`。
- **待办（进游戏验证）**：抽验 aod7 / tsm2 / spc3 各一发 + AOD-7 自动化场景在新遥测下走到 `Completed`。
