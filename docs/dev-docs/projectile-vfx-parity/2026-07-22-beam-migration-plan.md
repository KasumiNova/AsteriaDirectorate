# 光束 VFX 迁移到 RenderEntity 管线（P7）

> 状态：**代码完成（P7.1–P7.4），待进游戏目检回归** · 日期：2026-07-22
>
> 落地进度：
> - **P7.1 ✅** 基座 + Psi：`BeamHost`/`BeamVfxDriver`(+Impl)/`BeamFrame`/`BeamCoreComponent`/`PsiHelixComponent`/`PsiSiphonComponent`；`PsiSunderBeamEffect` 改喂 `BeamFrame`；删 `PsiOmegaBeamVfx`。
> - **P7.2 ✅** GravityCollapse：`BeamCoreComponent` 泛化（`fadeMul` 收束/固定色/无端淡等 flag）+ 5 节点（`BeamRingComponent`/`BeamMuzzleComponent`/`BeamAmbientComponent`/`BeamMicroBeamComponent`/`BeamHelixParticleComponent`）；两宿主改喂 `BeamFrame`；清空 40+ 空 catch；删 `GravityCollapseBeamVfx`。
> - **P7.3 ✅** StellarJet：`BeamCoreComponent` 再泛化（`glowWidthRelativeToCore`/alpha·emissive ramp）+ 4 节点（`BeamHelixTrailComponent`/`StellarJetAmbientComponent`/`StellarJetMuzzleComponent`/`StellarJetImpactComponent`）；`FrameState` 加 `hitTarget`/`isShieldHit`（与 `endpoint` 同列，替代计划原定的 `BeamHost` 位置）；EMP `applyDamage`+raycast+能量弹+辐能后坐力留宿主；muzzle 短束自毁 plugin→节点内保留收缩列表；charge-up `DistortionEntity` 直用（删 nebula 兜底 + 空 catch）；删 `StellarJetBeamVfx`。
> - **P7.4 ✅（代码）** 三手搓类均删；无 beam 侧孤儿类；复用件（`BeamLineUtil`/`AttachedBeamSpriteRingRenderer`/`TaperedBeamTrailsVfx`）仍有调用方；`BeamVfxSpecsTest` 覆盖 Psi/GC/StellarJet 三树拓扑。`ProjectileVfxPresets` 属旧弹体管线**显式保留内部**（`ProjectileVfxOldPathRemovalTest` allowlist），非 beam 死代码。
> - **待办**：进游戏逐光束目检 firing/停火/复火/命中回归（无引擎环境无法自动验证像素级观感）。
>
> 原状态：设计定稿待评审 · 日期：2026-07-22
> 前置：[2026-07-22-render-entity-dsl-rewrite.md](./2026-07-22-render-entity-dsl-rewrite.md)（§2 目标"宿主中立、兼容光束"、D6、§9 P7）
> 背景：24/24 弹体已全迁至 `RenderEntity` + DSL 新管线；核心 `api/render`（`RenderEntity`/`RenderContext`/
> `FrameState`/`RenderHost`）从落地起就是宿主中立的（`RenderContext.host: RenderHost` 未钉死弹体，
> `FrameState` 每字段带弹体/光束双语义）。本文规划把三个手搓大型光束特效迁到同一轨道。

## 1. 现状：三个光束特效的形态

| 特效 | 行数 | 几何来源 | 驱动宿主 | 独有元素 |
|---|---|---|---|---|
| `PsiOmegaBeamVfx` | 1061 | 真实 `BeamAPI`（from/to/brightness/length） | `PsiSunderBeamEffect : BeamEffectPlugin`（挂 beam） | 命中"回流虹吸"粒子；wisp 用 `CurveEntity` |
| `GravityCollapseBeamVfx` | 1170 | from/to 点（来自 beam 或系统） | 两个宿主：`GravityCollapseBeamEveryFrameEffect`（真实 beam）+ `StasisFieldCollapseEmitterEveryFrameEffect`（系统侧） | SpriteEntity 光圈环流 + 几何渐长炮口锥 + 装饰微束 + 螺旋粒子 |
| `StellarJetBeamVfx` | 1340 | ship+weapon 自算 + 自建 raycast + 束长平滑 | `StellarJetEmitterEveryFrameEffect : EveryFrameWeaponEffectPlugin`（挂 weapon，含系统状态机） | charge-up（独立 `StellarJetChargeUpVfx` + `DistortionEntity`）；muzzle 短束带自毁 plugin；impact 段掺**真实 EMP 伤害** |

三者本质是同一模式（BoxUtil 常驻 `TrailEntity` 束体 + 每帧抛射的粒子/短束），却各写各的、无共享结构，且 `GravityCollapseBeamVfx` 已到不可维护地步。

### 1.1 共性模板（可作统一基座）

所有三个光束都有一组**完全同构**的常驻束体，是天然的公共节点：

- **直束 4 件套**：`core + coreMirroredU + glow + glowMirroredU`，唯一后端是 `BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter / ...ReversedU`（tapered `TrailEntity`）。
- **保留模式 + 每帧句柄更新**：本地坐标节点 `(0,0)-(length,0)` + `setStateVanilla(start, facing)`；mirror 版反转 node0/node1 做 UV 镜像。三者写法几乎逐行相同。
- **共享调参族**：`MAIN_TEX_PIXELS=512`、负号纹理速度（向前流动）、`initFlowParams`（jitter 0.03、关 flick）、`initEndFade(START=0.018/END=0.024)`、层 `ABOVE_SHIPS_AND_MISSILES`、`mixPower≈3`、tip≥base×0.75。

外围则是 5 类可选"覆盖"效果：`detail/wisp`、`muzzle` 炮口、`impact/hit` 命中、`ambient` 沿束、`charge-up`。

### 1.2 需要参数化/策略化的差异（迁移必须抽象的维度）

1. **几何来源**：真实 `BeamAPI` / ship+weapon 自算 raycast / from-to 点对 —— 三种。
2. **驱动宿主**：`BeamEffectPlugin`（挂 beam，生命周期随 beam）vs `EveryFrameWeaponEffectPlugin`（挂 weapon，含系统状态机 + gameplay）。
3. **detail 后端**：`CurveEntity` 双螺旋（Psi）/ 多节点 `TrailEntity` 螺旋（StellarJet）/ `SpriteEntity` 环 + 粒子（GravityCollapse）—— 三种后端。
4. **附加语义**：Psi 虹吸回流；StellarJet charge-up + **EMP 真实伤害**；GravityCollapse 光圈环 + 渐长炮口锥 + AOE（AOE 已外置在 `GravityCollapseOnHitHandler`）。
5. **淡出实现**：心跳/长定时器 + 复火重置定时器拉回（Psi/StellarJet）vs `reset()`+`fadeMul` 删除重建（GravityCollapse）。**统一采纳前者**（无重建、复火平滑），淘汰后者。

## 2. 目标与非目标

### 目标
- 三个光束共用一套 `RenderEntity` 树 / DSL / 生命周期，与弹体同轨；束体 4 件套收敛为一个公共节点。
- 每个概念独立的视觉效果 = 一个自管状态的 `RenderEntity` 节点；`GravityCollapseBeamVfx` 的 7 类混杂效果拆成 6 个节点 + 1 个无状态起手函数（见 §5）。
- 几何/firing/strength/fadeMul 由宿主插件算好喂进 `FrameState`，节点只读 `ctx.frame`，不各自读 `BeamAPI`/`weapon`。
- **gameplay 不进树**：EMP `applyDamage`、命中判定、firing 门控、raycast 留在宿主插件；树只拥有视觉节点。

### 非目标
- 不改三个光束的 gameplay（伤害/系统/CR 逻辑原样保留在各宿主插件）。
- 不改弹体管线；P5（删旧弹体管线）与本文独立并行（束节点复用 `BoxUtilCombatVfx` 工厂，不新增对旧 `runtime/*ForTests` 的依赖）。
- charge-up 的 `DistortionEntity` 不强行塞进 `RenderEntity` 后端；`StellarJetChargeUpVfx` 作为独立相位效果保留或单独迁（见 §6 P7.3）。

## 3. 核心新增（宿主中立接缝的落地）

核心 `api/render` 不动。新增全部落在 `impl/render` 与一个新的 `renderer/beam/driver` 包：

### 3.1 `BeamHost`（api/render，接口）
```kotlin
/** 光束宿主：一棵光束特效树依附的对象（设计 D6）。几何/firing/strength 每帧经 FrameState 提供，
 *  本接口只暴露少数 root component 需要的宿主特有查询（命中目标 / 接触点）。 */
interface BeamHost : RenderHost {
    /** 命中的目标实体；未命中为 null。供 impact 类节点做目标点选取（如 StellarJet EMP 弧）。 */
    val hitTarget: CombatEntityAPI?
    /** 是否护盾命中。影响命中特效表现。 */
    val isShieldHit: Boolean
}
```
`BeamHostImpl` 包裹宿主每帧算出的接触信息。几何本身（start/facing/length/endpoint）走 `FrameState`，**不**放 host —— 与弹体一致，节点 95% 只读 `ctx.frame`。

### 3.2 `BeamVfxDriver`（renderer/beam/driver，接口 + Impl）
弹体驱动从 `projectile.location` **自己算**几何；光束几何却由宿主插件（含 raycast/平滑/BeamAPI 读取 + gameplay）**算好传入**。故光束驱动比弹体驱动更薄：不持有 `ASTDProjectileHistory`，每帧接收一个几何+状态输入，产出 `FrameState`、推进树生命周期。
```kotlin
/** 光束每帧输入：宿主插件算好后传入。start/facing/length/endpoint 是几何，firing/strength/fadeMul 是状态。 */
class BeamFrame(
    val start: Vector2f, val facing: Float, val length: Float, val endpoint: Vector2f?,
    val firing: Boolean, val strength: Float, val fadeMul: Float,
)
interface BeamVfxDriver {
    val state: BeamVfxDriverState
    /** 逐帧推进：把 BeamFrame 折成 FrameState（origin=start / endpoint / active=firing / intensity=strength×fadeMul），
     *  驱动树 onAttach/advance；firing→false 且 fadeMul→0 触发 beginFadeOut；淡完 detach。 */
    fun advance(engine: CombatEngineAPI, frame: BeamFrame, amount: Float)
    fun dispose()
}
```
- `active=firing`、`intensity=strength×fadeMul` 直接命中 `FrameState` 既有语义（"光束 firing / 光束 strength"），复火拉回 = 宿主再传 `firing=true` 即可，无重建（淘汰 GravityCollapse 的 reset+rebuild）。
- 与弹体一样按宿主身份去重、`state==Removed` 回收。Psi 一条 beam 一个 driver；StellarJet/GravityCollapse 一把 weapon 一个 driver。

### 3.3 束体公共节点 `BeamCoreComponent`（impl/render）
把三处逐行重复的 4 件套收敛成一个节点：持 `core/coreU/glow/glowU` 四个 tapered `TrailEntity`，`onAttachSelf` 经 `BoxUtilCombatVfx` 工厂建、`advanceSelf` 每帧改 node/宽度/emissive + `setStateVanilla(frame.origin, frame.facing)`、`beginFadeOutSelf` 走既有 `ASTDProjectileVfxLayerFadeState`。作者面参数：核心/辉光的色、宽、mixPower、endFade。**这是本次收益最大的公共资产**——三个光束的束体从此一份实现。

### 3.4 detail / muzzle / impact / ambient 节点（impl/render）
- detail 三后端各一个节点（都是 root component 形态，读 `ctx.frame` 的 origin/facing/length 构中线）：`BeamHelixCurveComponent`（Psi，`CurveEntity`）、`BeamHelixTrailComponent`（StellarJet，多节点 `TrailEntity`）、`BeamRingComponent`（GravityCollapse，复用 `AttachedBeamSpriteRingRenderer`）。三者不强求合并——后端不同、语义不同，各自独立节点即可（符合"一节点一效果"）。
- `BeamAmbientComponent`：沿束 smooth 粒子 + nebula，每帧按 `length/50` 累积器抛射。StellarJet 与 GravityCollapse 的 ambient 是复制粘贴关系（后者注释明写"复用 StellarJet 策略"）→ **合并为一个可配置节点**。
- `BeamMuzzleComponent`：炮口锥/粒子，动态 spawn。
- `BeamImpactComponent`：命中点粒子/弧，读 `frame.endpoint` + `host.hitTarget`。
- 每帧抛射类节点的粒子仍走原版 `engine.addSmoothParticle/addNebula*/spawnEmpArcVisual`（这些不是保留模式句柄，无泄漏问题），节点只负责"firing 时按节流抛射"的编排。

### 3.5 光束 DSL `beamVfx(id){ core{}; helix{}|rings{}; muzzle{}; impact{}; ambient{}; fade{} }`
与 `projectileVfx` 同构的作者面，产出 `BeamVfx(tree, policy)`。每个光束一个构建函数（`psiOmega()` / `gravityCollapse()` / `stellarJet()`），登记在 `BeamVfxSpecs`（与 `ProjectileVfxSpecs` 对称）。

## 4. 命名与规范红线（迁移必须遵守）

- **禁用词**：不得出现 `Manager/Service/Controller/Runtime`。驱动叫 `BeamVfxDriver`（逻辑层，接口 + Impl）。既有 `ASTDProjectileVfxBodyRenderManager` 属保留件不改名，新代码不得再引入。
- **空 catch 清零**：`GravityCollapseBeamVfx` 有 40+ 个 `catch (_: Throwable) {}` 静默吞异常（违反规范：出问题必须日志）。迁移**不得平移**这些空 catch；BoxUtil `addEntity` 失败等须 `log.warn` + 释放句柄（沿用 `TrailComponent`/`MistComponent` 既有约定：`state!=0 → delete + warn + return false`）。
- **不留兼容/死路径**：`GravityCollapseBeamVfx.reset()` 里清理 `AttachedBeamEllipseRingRenderer` 与 `sub130` 等换后端后的残留 key（防御性垃圾），迁移时不带走。
- **接口先行**：`BeamHost`/`BeamVfxDriver` 进 api/接口层，Impl 内部使用；节点对外只暴露 DSL setter。
- **测试**：真实逻辑验证——`BeamVfxSpecsTest`（DSL→树拓扑/策略字段）、`BeamVfxDriverTest`（喂 `BeamFrame` 序列，断言 `FrameState` 映射 + firing→fade→detach 生命周期，用 headless 安全的 engine=null 路径）。不做源码 contain、不做"验证未迁移"。

## 5. GravityCollapseBeamVfx 拆解（重点）

现状：纯视觉类，对外 3 方法（`reset/onStart/advance`），`advance()` 一个方法横跨 **240 行**，把 7 类互不依赖的效果线性堆在一起，夹着几何计算、门控 if、内联螺旋循环；另有 `upsertPersistentBeam`（226 行，内嵌两个共享 20+ 局部变量的大闭包）、`spawnMuzzleConeSpray`（108 行）等超长方法。7 类效果间**无共享状态**（只共用 `BeamLine` 几何 + `scale/level/fade`），可直接按效果切成独立节点：

| 现有效果（file:line） | 迁移到的节点 | 后端 |
|---|---|---|
| (A) PersistentBeam 4 件套（`upsertPersistentBeam:874-1100`） | `BeamCoreComponent`（§3.3 公共节点） | tapered `TrailEntity` |
| (B) MuzzleCone burst+spray+grow（`:551-797`，三段散落） | `BeamMuzzleComponent`（burst/spray 去重为带 `registerGrow` 的私有 spawn；grow 用节点内保留列表） | tapered `TrailEntity` |
| (C) 起手火花/闪光（`onStart:282-304`） | 无状态 `beamStartupBurst()` 一次性函数，并入 muzzle 节点 `onAttach` | `spawnExplosion`/`addSmoothParticle` |
| (D) 主环+子环+炮口脉冲环（`advance:372-486` 内联） | `BeamRingComponent`（三环共享 key 前缀，一个节点） | `AttachedBeamSpriteRingRenderer`（`SpriteEntity`） |
| (E) 沿束 ambient nebula（`emitAmbientBeamNebula:1102-1169`） | `BeamAmbientComponent`（与 StellarJet 合并，§3.4） | `addNebulaSmokeParticle` |
| (F) 装饰微束（`spawnDecorativeMicroBeams:799-872`） | `BeamMicroBeamComponent` | `TaperedBeamTrailsVfx.spawn` |
| (G) 螺旋粒子（`advance:508-548` 内联 40 行） | `BeamHelixParticleComponent`（内联块提成节点） | `addSmoothParticle` |

拆完后父类退化为**编排器**：算好 `BeamGeometry`（含束长渐长 reach）与 `strength/fadeMul` 塞进 `FrameState`，分发给各节点。`advance` 从 240 行降到十几行转发，散落各处的门控（`fade>0.999f`、`ringFade>0.001f` 等）落到各节点内部。收益最大、风险最低的两步可先行：(B) burst/spray 去重、(A) 四实体参数表格化。

## 6. 迁移阶段（每阶段完成即完成，不留半成品）

- **P7.1 · 基座 + 迁 Psi（最简，端到端验证模板）。** 落地 `BeamHost`/`BeamVfxDriver`(+Impl)/`BeamFrame`/`beamVfx` DSL/`BeamCoreComponent` + `BeamHelixCurveComponent` + `BeamImpactComponent`（虹吸回流）。`PsiSunderBeamEffect` 保留几何/伤害，改为算 `BeamFrame` 喂 driver。Psi 无 muzzle/ambient/charge-up、几何来自真实 BeamAPI、生命周期挂 beam，是验证公共模板的最小闭环。自检：`BeamVfxDriverTest` 断言 firing→停火→复火拉回不重建。
- **P7.2 · 迁 GravityCollapse（readability 主战场）。** 按 §5 拆成 6 节点 + 起手函数，两个宿主（真实 beam / 系统侧）都改为喂 `BeamFrame`（`level` 分别恒 1 / 传 intensity）。复用 `AttachedBeamSpriteRingRenderer`/`TaperedBeamTrailsVfx`。清空 40+ 空 catch。
- **P7.3 · 迁 StellarJet（最难）。** 束体+wisp+ambient+muzzle+impact 迁节点；**EMP `applyDamage` 与 raycast 留在 `StellarJetEmitterEveryFrameEffect`**（gameplay 不进树），只把算好的 `BeamFrame`+contact 喂 driver。muzzle 短束的自毁 plugin 改为节点内保留列表（与 GrowingCone 同法）。charge-up：`StellarJetChargeUpVfx`（含 `DistortionEntity`）作为独立相位效果——系统 `IN` 相位单独驱动，可暂不进 `RenderEntity` 树（`DistortionEntity` 非本后端），或作为 P7.3 尾声单独节点化。原版 beam 仍由 `StellarJetBeamEffect` 画透明（保留）。
- **P7.4 · 删除 + verify。** 删 `PsiOmegaBeamVfx`/`GravityCollapseBeamVfx`/`StellarJetBeamVfx` 三个手搓类及其死代码（如无调用方的 `ProjectileVfxPresets.StellarJetBolt`）。进游戏逐光束目检 + firing/停火/复火/命中回归。

## 7. 风险

- **观感 parity**：三个光束都是高辨识度签名武器，束体/ endFade/mixPower 数值须逐字段照搬（`BeamCoreComponent` 的默认值直接取自现有常量族）。
- **detail 后端多样性**：`CurveEntity` 与多节点 `TrailEntity` 螺旋的中线采样（`CurveUtil.getPointOnCurve`）须原样复用，不手抄。
- **StellarJet gameplay 耦合**：EMP 伤害与 raycast 必须留在宿主插件；迁移只搬视觉，误搬会破坏伤害。
- **charge-up 的 `DistortionEntity`**：非 BoxUtil/GL-mesh 后端，是否纳入 `RenderEntity` 待 P7.3 定；倾向保留独立，避免为单一后端污染节点模型。
