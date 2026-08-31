---
name: "boxutil-guidelines"
description: "BoxUtil 使用指南（API 速览、调试建议、避坑点），并结合本项目的封装与落地实践。"
---

# Skill：BoxUtil 使用指南（API / 调试 / 避坑点）

## 适用范围

本指南适用于本项目中所有基于 BoxUtil 的渲染/VFX 逻辑，包括：

- 光束/拖尾（TrailEntity）
- 扭曲/透镜（DistortionEntity）
- Sprite/文本渲染（SpriteEntity / TextField）
- 曲线/段渲染（CurveEntity / SegmentEntity）
- 实例化粒子与大量实例化渲染（InstanceRenderAPI / SimpleParticleControlData）
- 需要接入 BoxUtil 渲染管理器的实体

> 本仓库对 BoxUtil 的源码目录默认 **只读**：`/mods/BoxUtil` 不要修改（除非用户明确要求）。

## 依赖与入口

- BoxUtil 位于：`/mods/BoxUtil/jars/BoxUtilMod.jar`（核心 API）
- 后端实现位于：`/mods/BoxUtil/jars/backends/BoxUtilImpl.jar`
- 完整 API 源码工程：`/home/hikari_nova/IdeaProjects/BoxUtil/api/src/`（只读参考）
- 示例战役代码：`/home/hikari_nova/IdeaProjects/BoxUtil/backends/src/data/missions/BUtilTestMission/MissionDefinition.java`
- 本仓库已集成 BoxUtil 的封装工具：
  - `src/main/kotlin/.../weapons/common/boxutil/BoxUtilCombatVfx.kt`
  - `src/main/kotlin/.../weapons/common/boxutil/BoxUtilProjectileTrails.kt`

## API 速览（常用）

### 1) 初始化与生命周期（必须遵循）

- `onApplicationLoad()` 里调用：`BoxUtilModPlugin.initPre()`
- 在**首个** `BaseEveryFrameCombatPlugin / BaseCombatLayeredRenderingPlugin` 中调用：`BoxUtilModPlugin.initLater()`（确保 OpenGL 上下文可用）
- 依赖 BoxUtil 状态的资源，建议在 `initLater()` 之后再初始化
- BoxUtil 会校验游戏主版本（0.98），版本不匹配会抛异常
- 本项目封装：`BoxUtilCombatVfx.ensureReady(engine)`（调用前兜底）

### 2) RenderDataAPI（通用渲染控制）

- 生命周期：`setGlobalTimer(fadeIn, full, fadeOut)`
- 位置/朝向：`setLocation(...)` / `setStateVanilla(...)` / `appendToEntity(...)`
- 矩阵：`getModelMatrix()` / `setModelMatrix(...)`
- 暂停行为：`setTimingWhenPaused(...)` / `setTimerPaused(...)`
- 混合：`setBlendFunc(...)` / `setBlendFuncSeparate(...)`

### 3) InstanceRenderAPI（大量实例化渲染）

- 数据提交：`setInstanceData(...)` / `addInstanceData(...)` / `submitInstance()`
- 刷新范围：`setInstanceDataRefreshIndex(...)` / `setInstanceDataRefreshSize(...)`
- 预分配显存：`mallocInstance(InstanceType, count)`
- 映射提交：`setMappingInstanceSubmit(true/false)`（高频更新谨慎开启）
- 固定/动态实例：`InstanceType.FIXED_*` 需手动维护位置与状态（性能更稳）

### 4) MaterialData（材质与贴图）

- 纹理层：Diffuse / Normal / Complex / Emissive / Tangent
- 发光控制：`setAlphaToEmissive(...)` / `setColorToEmissive(...)` / `setGlowPower(...)`
- 光照开关：`setIgnoreIllumination(...)`

### 5) 常用实体

- `TrailEntity`：拖尾/直线束体
  - `addNode(...)` / `submitNodes()`
  - `setStartWidth(...)` / `setEndWidth(...)`
  - `setStartColor(...)` / `setEndColor(...)`
  - `setStartEmissive(...)` / `setEndEmissive(...)`
  - `setMixFactor(...)` / `setGlobalTimer(...)` / `setLayer(...)`
- `SpriteEntity`：通用 Sprite / 粒子渲染
- `DistortionEntity`：扭曲/折射（后处理）
- `CurveEntity` / `SegmentEntity`：曲线/段渲染
- `TextFieldEntity` / `TextFieldObject`：高性能文本渲染

### 6) 渲染管理器

- `CombatRenderingManager.addEntity(...)` / `CampaignRenderingManager.addEntity(...)`
- `addRenderingPlugin(...)` / `addBackgroundRenderingPlugin(...)`
- `getCustomData()`：跨插件共享数据
- 注意：**最高层级不会应用后处理（Bloom/AA）**

### 7) 渲染工具

- `RenderingUtil.createBeamVisual(...)`
- `RenderingUtil.addCombatBeamVisual(...)` / `addCampaignBeamVisual(...)`
- `RenderingUtil.addCombatParticleField(...)` / `addCombatFlareField(...)`
- `RenderingUtil.createTextField(...)` / `debugText(...)`

### 8) 枚举与状态码

- `BoxEnum.ENTITY_*`：实体类型（如 `ENTITY_TRAIL`、`ENTITY_DISTORTION`）
- `BoxEnum.STATE_SUCCESS/STATE_FAILED`：添加实体结果

## 本项目的实践封装（建议优先使用）

### A) `BoxUtilCombatVfx`

- 负责：
  - 初始化 BoxUtil（`initLater()`）
  - 处理 CombatRenderingManager 未就绪的重试
  - 创建常用 “tapered beam trail”
- 可直接调用：
  - `createAndAddTaperedBeamTrail(...)`
  - `createAndAddTaperedBeamTrailFromCenter(...)`
  - `createAndAddTaperedBeamTrailFromCenterReversedU(...)`

### B) `BoxUtilProjectileTrails`

- 负责：
  - Projectile 的拖尾/锥形前束管理
  - 淡出/超射程“继续飞行”视觉补偿
  - （历史实现）在 BoxUtil 创建失败时可能会回退为纯粒子尾焰

> 规范提示：新写的渲染/VFX 逻辑不建议再引入“BoxUtil + 原版渲染”的双实现降级分支；BoxUtil 出问题应尽快报告并修复。

### C) 典型引用点

- `weapons/common/projectile/TaperedBeamTrailsVfx.kt`
- `weapons/shared/gravitycollapse/GravityCollapseVfx.kt`
- `weapons/arc/signature/stellarjet/*`（束体 + DistortionEntity）
- `weapons/common/projectile/ProjectileVfxPresets.kt`

## 调试建议

- **初始化是否成功**：
  - 检查 `BoxUtilModPlugin.isGlobalInitialized()`
  - 本项目会在 `BoxUtilCombatVfx` 中记录 initLater 失败日志

- **实体是否加入渲染队列**：
  - `CombatRenderingManager.addEntity(...)` 返回非 0 表示失败
  - 本项目会在 addEntity 失败时记录一次 warn；建议把失败信息做得更可定位（调用点/关键 id），以便及时修复

- **基础能力检查**：
  - `BoxConfigs.isShaderEnable()` / `BoxConfigs.isBaseGL43Supported()`
  - 若关闭或不支持，高级实体可能不可用（应优先修复环境/设置问题，而非复制一套原版渲染代码）

- **层级问题**：
  - 确保 `setLayer(CombatEngineLayers.*)` 正确
  - 可用 `RenderingUtil.getHighestCombatLayer()` / `getLowestCombatLayer()` 参考

- **调试可见性**：
  - `BoxUtilProjectileTrails` 提供 `debugForceVisible` 粒子模式
  - 若你观察到 fallback 粒子，通常意味着 BoxUtil 路径失败：应优先定位失败原因
  - `RenderingUtil.debugText(...)` 仅限开发期，发布版避免调用

## 性能与规模化渲染建议

- **大量粒子/实例**优先使用 `InstanceRenderAPI` 或 `SimpleParticleControlData`，避免每次创建新实体。
- **固定实例**（`InstanceType.FIXED_*`）适用于“CPU 侧明确控制位置/缩放”的场景，性能更稳定。
- **映射提交**（`setMappingInstanceSubmit(true)`）会引入同步成本，谨慎开启。

## 后台线程插件（高级用法）

- BoxUtil 提供后台线程插件接口（`BaseBackgroundEveryFramePlugin`），适合做不依赖主线程的视觉/逻辑更新。
- 建议配合 `TemporaryCleanupPlugin`，在战斗结束时做清理，避免资源泄漏。

## 避坑点清单（来自本项目经验）

1) **未初始化导致 addEntity 失败**
   - 先 `BoxUtilCombatVfx.ensureReady(engine)`，再创建/添加实体。

1.5) **传入负角度朝向会镜像反转（实锤 BUG）**
   - BoxUtil `TrigUtil.sinFormCosF` 从 cos(半角) 反推 sin(半角) 时只做 `angle > 180` 的符号修正，
     负角度（如 `atan2` 直出的 -90°）会拿到错误符号的 sin——等价于绕 x 轴镜像，
     实体朝向与侧向偏移整体反转（朝下开火时锥形/弧凸向翻转）。
   - 规范：所有进入 BoxUtil 实体变换（`setStateVanilla` / `createModelMatrixVanilla` /
     `createBeamVisual`）的朝向必须先过 `BoxUtilCombatVfx.normalizeFacingDeg`（归一化到 [0,360)）。
   - 注意随机散布也会把合法朝向推出负域（如 5° − 32°），不能只归一化基准值。
   - 本项目拖尾路径已有同款修复（`ProjectileVfxDriverImpl.computeRenderFacing`）。

2) **TrailEntity 的节点方向导致 UV/流向错觉**
   - `RenderingUtil.createBeamVisual()` 默认 node[0] 在 +length 方向；
   - 若出现“某方向看起来反向旋转/反向流动”，使用 `createTaperedBeamTrailFromCenterReversedU(...)` 叠加镜像。

3) **globalTimerOnce 会导致实体只渲染一帧**
   - 本项目固定使用长 `full` + 由调用方控制淡出；避免 setGlobalTimerOnce 误删。

4) **淡出时反复重置 timer 会引发末帧闪烁**
   - `BoxUtilProjectileTrails` 已避免在 FADING → REMOVED 时重置 globalTimer。

5) **弹体 facing 与速度方向不一致**
   - 对高速弹体，推荐使用速度向量计算朝向（项目中已有实现）。

6) **DistortionEntity 失败时要有回退方案**
   - 本项目在失败时回退到 nebula 粒子作为近似扭曲。

7) **最高层渲染不会触发 Bloom/AA**
  - 若需要后处理，避免使用最高层；必要时调整到合适 CombatEngineLayers。

8) **战役层渲染会在跳跃/换星系时清理**
  - 不要把渲染实体存入 Memory；重新进入场景时再创建。

## 参考资料

- 本仓库内部封装：
  - `weapons/common/boxutil/BoxUtilCombatVfx.kt`
  - `weapons/common/boxutil/BoxUtilProjectileTrails.kt`
- BoxUtil 本体：`/mods/BoxUtil/jars/BoxUtilMod.jar`
- BoxUtil API 源码：`/home/hikari_nova/IdeaProjects/BoxUtil/api/src/`
- 示例战役源码：`/home/hikari_nova/IdeaProjects/BoxUtil/backends/src/data/missions/BUtilTestMission/MissionDefinition.java`
- 内部文档（PDF）：`docs/dev-docs/【Modding进阶】BoxUtil 大致使用指南.pdf`

> 若需深挖 API 细节，可用 javap/反编译查看 BoxUtilMod.jar 的 public API；但请勿直接修改 BoxUtil 源码。