# 引力透镜级重做 · 阶段二（机制细化 + Shader 视觉）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在阶段一基座上完成引力透镜级的全部机制（舰船系统 D 回声定影、插件②视差甲板、插件③渗透潮汐、标记闭环收割端），每个机制连同其 Shader 战斗特效一并交付，并以 SSOptimizer 实机集成测试做视觉+逻辑验证。

**Architecture:** 逻辑层复用阶段一的 `LensMarks`（误差/深水标记，StackingShipBuffs 框架）与 `ASTDLensArrayCoreHullMod`（双模式分发）。每个机制是一个 every-frame 驱动（hullmod.advanceInCombat 或 ship-system stats/AI），调用 `LensMarks.apply*` 铺标记、读 `LensMarks.*Stacks` 收割。视觉层全部经 `CombatShaderRuntime`（world-quad + 内嵌 GLSL + keyed upsert，范式 = `ArcJetShockwaveRingEffect`）；残影回放因需贴图采样退回 BoxUtil；认知撕裂屏幕扭曲推迟（先用 BoxUtil 假扭曲，host 扩展 ScreenSpaceQuad 留 phase 2.5）。

**Tech Stack:** Kotlin, Starsector combat API, 阶段一 `cn.kasuminova.astd.combat.lens.*`，shader host `cn.kasuminova.astd.renderer.shader.*`（domain `ShipSystemShaderEffect` + runtime `CombatShaderRuntime`），BoxUtil `BoxUtilCombatVfx`，ship-system 范式 `ASTDArcFlareOverdriveSystem{Stats,AI}` / `CollapseShiftSystemAI`，automation 框架 `ASTDAutomationCombatPlugin` + `verify_ingame_vfx_automation.py`。单元测试用 `kotlin.test`（纯逻辑）+ shader effect spec 测试范式 `ArcJetShockwaveRingEffectTest`。

**权威源：** `docs/superpowers/specs/2026-06-20-gravitational-lens-redesign-design.md` 与 `docs/design/ships/purple/10-unique.md` §1（冲突以后者为准）。

**视觉取向（用户定）：** 战斗内特效优先 Shader 最大化表现；图标类静态美术继续占位（专业美术后补）。

**阶段二范围（明确不含）：** ScreenSpaceQuad host 扩展（认知撕裂真·屏幕扭曲）推迟 phase 2.5；图标/状态栏专属美术后补；copy-review 文案终审在机制定稿后单独走。

---

## 阶段一接缝（本计划接手点）

- `LensMarks.applyDriftMark/applyDeepWaterMark/driftStacks/deepWaterStacks` 已就绪；**缺 `clearAllLensMarks(engine)`**（渗透潮汐退潮需要）→ Task 1 补。
- `LensMarks.VS_LENS_DAMAGE_MULT_KEY` 深水标记写入但无读取端 → Task 3（认知撕裂/承伤）暂不依赖；保留给伤害接收侧，本阶段不强接（标注接缝）。
- 战术链路「全队对带误差标记目标额外增伤」收割端 → Task 2 实装。
- echo_fixation 系统现为 `PlaceholderShipSystemStats` 空存根（aiType=WEAPON_BOOST）→ Task 4-7 替换为真实系统。
- `ASTDLensArrayCoreHullMod` 已分发无人/载人；本阶段在其 advance 增加视觉驱动调用（标记高光/幽灵信号波）。

---

## 文件结构（阶段二）

**新建 — 逻辑：**
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkHarvest.kt` — 战术链路收割端：本队对带误差标记目标的额外增伤（on-hit/damage listener）。
- `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/EchoFixationSystemStats.kt` — 回声定影 ship-system stats（无人/载人共用基类，分版覆盖难度系数）。
- `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/EchoFixationCrewedSystemStats.kt` / `EchoFixationAutomatedSystemStats.kt` — 分版。
- `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/EchoFixationSystemAI.kt` — 区域落点 AI（敌群中心/前方）。
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationField.kt` — 定影场状态机（记录快照 → 回放认知撕裂），挂 engine.customData，由 every-frame 插件推进。
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationMath.kt` — 纯函数：站桩重合度→额外增伤（200%→25% 线性衰减）、认知撕裂承伤（50%~100% 难度缩放）、范围受系统射程缩放。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensParallaxDecksHullMod.kt` — 插件②视差甲板。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ParallaxDecksMath.kt` — 纯函数：出库隐形窗口、整备承伤减免范围判定、机群叠标记每敌每秒上限。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensPermeatingTideHullMod.kt` — 插件③渗透潮汐。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/PermeatingTideMath.kt` — 纯函数：按距离插值叠加间隔（1000su 最快~2500su 不叠）、难度缩放、过载退潮。

**新建 — Shader 特效（内嵌 GLSL，范式 = ArcJetShockwaveRingEffect）：**
- `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DriftMarkVisualEffect.kt` — 误差标记敌舰高光环。
- `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DeepWaterMarkVisualEffect.kt` — 深水标记敌舰高光环（冷色/区别误差）。
- `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffect.kt` — 幽灵信号径向干扰波。
- `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationFieldVisualEffect.kt` — 快照场圆形边界 + 脉动。
- `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffect.kt` — 潮汐场 FBM 涟漪（涨落）。
- `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationAfterimageRenderer.kt` — 残影回放（BoxUtil sprite，非 shader）。

**修改：**
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarks.kt` — 加 `clearAllLensMarks(engine)`。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt` — advance 增加标记高光 + 幽灵信号波视觉驱动；载人分支启用 `LensMarkHarvest`。
- `ss-csv/.../catalog/shipsystems/lens/Catalog_ShipSystems_LENS.kt` — echo_fixation 系统 statsScript/aiScript 改真实类、type 改合适类型。
- `ss-csv/.../catalog/hullmods/lens/Catalog_HullMods_LENS.kt` — 注册 `astd_lens_parallax_decks` + `astd_lens_permeating_tide`（astd_builtin/hidden）。
- `contents/data/hulls/astd_gravitational_lens.ship`（经 ss-csv 真相源）— builtInMods 加 parallax_decks + permeating_tide。
- `contents/data/strings/strings.json` — 新插件 tooltip i18n + 系统 status i18n。
- `ss-csv/.../i18n/zh-cn.properties` — echo_fixation 系统描述（type2 用「特殊」，已定）。
- `contents/data/config/astd_automation_scenarios.json` + `ASTDAutomationCombatPlugin.kt` + `tools/verify_ingame_vfx_automation.py` — 扩展 `lens_phase1_foundation`（或新增 `lens_phase2_mechanisms`）场景，加机制+视觉断言。

> **真相源纪律（阶段一教训）：** lens 的 hullmod/system/shipdata 权威源是 `ss-csv/.../Catalog_*_LENS.kt`，改完跑 `./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true` 写回 contents/，**不要直接手改 contents 下的 csv/system**（会被生成物覆盖）。.ship/.variant/strings.json/mission 是手写文件不归 ss-csv 管，可直接改。

---

## Task 1: clearAllLensMarks + 测试

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarks.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarksClearTest.kt`

渗透潮汐退潮需要清空全场敌舰两类标记。在 `LensMarks` 加 `clearAllLensMarks(engine)`，遍历 `engine.ships` 对每艘调用框架的清除（复用 StackingShipBuffs 的状态清除路径）。

- [ ] **Step 1: 读现有 LensMarks.kt + StackingShipBuffs.kt 确认清除 API**

Run: 阅读 `src/main/kotlin/cn/kasuminova/astd/combat/buffs/StackingShipBuffs.kt`，确认是否有对外的 per-ship 清除方法。若 `clearState` 是 private，则 `clearAllLensMarks` 通过把状态置为「已过期」让框架插件下一帧自然清理，或在 StackingShipBuffs 暴露一个 `clear(ship, buffId)` 公开方法。**优先复用框架既有机制**：若 `applyOrRefresh` 用 customData 存状态，最干净的是给 StackingShipBuffs 加一个 `fun clear(ship: ShipAPI, buffId: String)` 公开函数（unapply applier + 清 customData），再由 LensMarks 调用。

- [ ] **Step 2: Write failing test**

```kotlin
package cn.kasuminova.astd.combat.lens.marks

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * clearAllLensMarks 的纯逻辑可测部分：验证“给定一组标记层数，清除后归零”的契约。
 * 真正的 engine 遍历是集成层（战斗内验证），此处测可提取的清除判定。
 */
class LensMarksClearTest {
    @Test
    fun `clear contract resets both mark kinds to zero`() {
        // LensMarks.clearAllLensMarks 对每艘船清两类标记。
        // 可测契约：LensMarksClearLogic.shouldClear(...) 恒为 true（退潮无条件清全场）。
        assertEquals(true, LensMarksClearLogic.shouldClearOnEbb())
    }
}
```

> 说明：clearAllLensMarks 本体是 engine 遍历（集成层不单测）。为满足全局规范「纯逻辑用直接调用测试」，把唯一的纯判定（退潮是否清全场=无条件 true）提取为 `LensMarksClearLogic.shouldClearOnEbb()`。若觉得此判定过于平凡不值得单测，可跳过 Task 1 的测试步骤、仅做集成实现 + Task 12 实机验证 —— 但 clearAllLensMarks 本身必须实现。**实现者决策并在报告说明。**

- [ ] **Step 3: 实现 clearAllLensMarks**

在 `LensMarks` 加：
```kotlin
/** 渗透潮汐退潮：清空全场所有敌舰的误差+深水标记。 */
fun clearAllLensMarks(engine: CombatEngineAPI) {
    for (ship in engine.ships) {
        if (ship == null) continue
        StackingShipBuffs.clear(ship, LensMarkIds.DRIFT_BUFF_ID)
        StackingShipBuffs.clear(ship, LensMarkIds.DEEP_WATER_BUFF_ID)
    }
}
```
（若 StackingShipBuffs 无 `clear`，先加它：unapply 对应 applier + 清 customData state；参照其 private clearState。）

- [ ] **Step 4: 编译 + 测试**

Run: `./gradlew compileKotlin test --tests "cn.kasuminova.astd.combat.lens.marks.LensMarksClearTest"`
Expected: BUILD SUCCESSFUL（或若跳过测试则仅 compileKotlin SUCCESSFUL）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarks.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/buffs/StackingShipBuffs.kt \
        src/test/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarksClearTest.kt
git commit -m "feat(lens): add clearAllLensMarks for permeating tide ebb

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 战术链路收割端（全队对带误差标记目标增伤）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkHarvest.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt`（载人分支启用）

spec §3.1 战术链路：载人模式下，**全队**对带误差标记的目标造成额外伤害。阶段一只有误差标记自身的 `hullDamageTakenMult`（任何来源都吃）。本任务实现「**仅友军**对带标记目标的额外加成」——用 DamageListener 在命中时判定 source 是否友军 + target 是否带误差标记，叠加伤害。

> 注意：阶段一误差标记的 `hullDamageTakenMult` 已让所有来源对带标记目标增伤。spec §3.1 的「战术链路 +额外」若是叠加在标记本身之上的**独立友军专属层**，则本任务实现它；若设计上认为标记自身的增伤已等价于战术链路（不再额外区分友军），则本任务改为「在 tooltip/语义上明确战术链路=标记增伤的载人收割表述」并**不重复加伤**。**实现前必须确认**：读 spec §3.1 + 10-unique.md §1 战术链路条目，判定是「独立额外层」还是「等价于标记增伤」。若 spec 未明示独立数值，采用后者（避免双重增伤），并在报告说明决策。

- [ ] **Step 1: 确认 spec 语义**（读 spec §3.1 + 10-unique.md §1）

判定战术链路是独立友军增伤层还是等价标记增伤。记录决策。

- [ ] **Step 2: 实现**（按决策二选一）

**若独立层**：`LensMarkHarvest` 实现 `DamageListener`（参考项目现有 on-hit/damage listener 如 `ASTDPursuitVirtualParticleOnHitEffect`），在 `reportDamageApplied` 判定 `source.owner == lensOwner（友军）` 且 `target` 带误差标记，按层数额外加伤（数值取 spec，难度缩放）。由 `ASTDLensArrayCoreHullMod` 载人分支安装/卸载。
**若等价**：不加伤；在 `ASTDLensArrayCoreHullMod` tooltip/注释明确「战术链路 = 误差标记增伤的载人收割」，删除本任务的额外加伤代码，`LensMarkHarvest` 仅作语义占位或不创建。

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew compileKotlin`
```bash
git add -- src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkHarvest.kt \
           src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt
git commit -m "feat(lens): tactical link harvest for crewed mode mark damage

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 回声定影数学纯函数 + 测试

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationMath.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationMathTest.kt`

spec §2：认知撕裂增伤 50%~100%（难度缩放）；站桩重合最高额外 +200%、最远 +25% 线性衰减（基础范围 500su 受系统射程缩放）；场半径 ~700su 受系统射程缩放。提取为纯函数。

- [ ] **Step 1: Write failing test**

```kotlin
package cn.kasuminova.astd.combat.lens.system

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class EchoFixationMathTest {
    private fun approx(e: Float, a: Float, eps: Float = 1e-3f) =
        assertTrue(abs(e - a) <= eps, "expected ~$e but was $a")

    @Test fun `cognitive tear base damage taken scales with difficulty`() {
        approx(0.50f, EchoFixationMath.cognitiveTearBonus(difficultyFactor = 1f))   // m=1 -> +50%
        approx(1.00f, EchoFixationMath.cognitiveTearBonus(difficultyFactor = 2f))   // m=2 -> +100%
    }

    @Test fun `standstill bonus is max at overlap and decays to floor at edge`() {
        // 重合(dist=0) -> +200%; 边缘(dist>=range) -> +25%; 线性。
        approx(2.00f, EchoFixationMath.standstillBonus(distFromPast = 0f, baseRange = 500f, systemRangeMult = 1f))
        approx(0.25f, EchoFixationMath.standstillBonus(distFromPast = 500f, baseRange = 500f, systemRangeMult = 1f))
        approx(1.125f, EchoFixationMath.standstillBonus(distFromPast = 250f, baseRange = 500f, systemRangeMult = 1f)) // 中点
    }

    @Test fun `system range mult scales the standstill range`() {
        // systemRangeMult=2 -> 边缘在 1000su
        approx(2.00f, EchoFixationMath.standstillBonus(distFromPast = 0f, baseRange = 500f, systemRangeMult = 2f))
        approx(1.125f, EchoFixationMath.standstillBonus(distFromPast = 500f, baseRange = 500f, systemRangeMult = 2f))
    }

    @Test fun `field radius scales with system range`() {
        approx(700f, EchoFixationMath.fieldRadius(baseRadius = 700f, systemRangeMult = 1f))
        approx(1050f, EchoFixationMath.fieldRadius(baseRadius = 700f, systemRangeMult = 1.5f))
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew test --tests "cn.kasuminova.astd.combat.lens.system.EchoFixationMathTest"`
Expected: FAIL（未定义）。

- [ ] **Step 3: 实现**

```kotlin
package cn.kasuminova.astd.combat.lens.system

/**
 * 回声定影（Echo Fixation）纯数学（spec §2）。
 * 认知撕裂增伤、站桩重合衰减、范围随系统射程缩放——无副作用纯函数，便于单测与平衡。
 */
object EchoFixationMath {
    private const val TEAR_BONUS_AT_M1 = 0.50f   // m=1: +50%
    private const val TEAR_BONUS_AT_M2 = 1.00f   // m=2: +100%

    private const val STANDSTILL_MAX = 2.00f     // 重合: +200%
    private const val STANDSTILL_MIN = 0.25f     // 边缘: +25%

    /** 认知撕裂基础增伤（难度系数 m∈[1,2] 线性插值）。 */
    fun cognitiveTearBonus(difficultyFactor: Float): Float {
        val m = difficultyFactor.coerceIn(1f, 2f)
        return TEAR_BONUS_AT_M1 + (TEAR_BONUS_AT_M2 - TEAR_BONUS_AT_M1) * (m - 1f)
    }

    /** 站桩重合度增伤：dist=0 满效 STANDSTILL_MAX，dist>=有效范围 衰减到 STANDSTILL_MIN，线性。 */
    fun standstillBonus(distFromPast: Float, baseRange: Float, systemRangeMult: Float): Float {
        val range = (baseRange * systemRangeMult).coerceAtLeast(1f)
        val t = (distFromPast / range).coerceIn(0f, 1f)
        return STANDSTILL_MAX + (STANDSTILL_MIN - STANDSTILL_MAX) * t
    }

    /** 定影场半径随系统射程缩放。 */
    fun fieldRadius(baseRadius: Float, systemRangeMult: Float): Float =
        baseRadius * systemRangeMult.coerceAtLeast(0.01f)
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew test --tests "cn.kasuminova.astd.combat.lens.system.EchoFixationMathTest"`
Expected: PASS（4 测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationMath.kt \
        src/test/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationMathTest.kt
git commit -m "feat(lens): echo fixation math pure functions with tests

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 回声定影场状态机（定影→回放）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationField.kt`

定影场核心逻辑：施放时建场（中心+半径），定影期（4s）按固定间隔记录场内敌舰位置快照（设目标数+历史长度上限，超限 log 丢弃），结束瞬间回放——对每个记录敌舰的「过去坐标」附近的当前敌舰施加认知撕裂（叠误差标记 + 承伤 debuff，用 Task 3 数学算站桩衰减）。挂 engine.customData，由 every-frame 插件推进。

> 集成层（写 customData / 遍历 ships / 施加 debuff），不单测；逻辑正确性由 Task 3 纯函数测试 + Task 12 实机验证保证。

- [ ] **Step 1: 读范式**

读 `src/main/kotlin/cn/kasuminova/astd/combat/buffs/StackingShipBuffs.kt`（每帧插件 + customData 状态范式）、`src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDVectorThrustEngineManager.kt`（扫描式插件 + 每帧遍历 ships 范式）、`AffixCausalityLagHullMod`/`AffixRecordedLoopHullMod`（延迟/记录 debuff 思路）。

- [ ] **Step 2: 实现场状态机**

实现要点（无占位、Fail Fast、设上限）：
- `EchoFixationField(centerX, centerY, radius, fixateDuration)` 数据 + 推进方法。
- 定影期：按 `RECORD_INTERVAL`（如 0.25s）遍历场内敌舰，存 `Map<shipKey, List<(x,y,t)>>`，**每船历史上限 N（如 32）、场内目标上限 M（如 24），超限 log.warn 丢弃**（不静默截断）。
- 回放：场结束瞬间，对每个记录敌舰的最后/代表性「过去坐标」，找其半径内当前敌舰，调 `EchoFixationMath.standstillBonus` 算重合衰减，`LensMarks.applyDriftMark` 叠 1 层 + 施加认知撕裂承伤 debuff（hull/armorDamageTakenMult，时长 ~4s，倍率 = 1 + cognitiveTearBonus×standstillFactor）。
- 难度系数：从 source ship 上下文取（敌对单位 m∈[1,2]，玩家船 m=1），传给 Math。
- 安装入口：提供 `EchoFixationField.spawn(engine, source, centerX, centerY)` 供 ship-system stats 调用。

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew compileKotlin`
```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationField.kt
git commit -m "feat(lens): echo fixation field state machine (record snapshots, replay cognitive tear)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 回声定影 ship-system（stats + AI + 真实 .system）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/EchoFixationSystemStats.kt` (+ Crewed/Automated 分版)
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/EchoFixationSystemAI.kt`
- Modify: `ss-csv/.../catalog/shipsystems/lens/Catalog_ShipSystems_LENS.kt`（statsScript/aiScript 改真实类）
- Modify: `ss-csv/.../i18n/zh-cn.properties`（系统描述，type2=特殊 保持）

把阶段一的 `PlaceholderShipSystemStats` 占位替换为真实回声定影系统。stats 在 system 激活时调 `EchoFixationField.spawn`（落点由玩家鼠标 / AI 选择）。镜像 `ASTDArcFlareOverdriveSystem{Stats,AI}` / `CollapseShiftSystemAI` 范式。

> 集成层，不单测；落点/激活逻辑由 Task 12 实机验证。

- [ ] **Step 1: 读 ship-system 范式**

读 `ASTDArcFlareOverdriveSystemStats.kt`（apply/unapply/state/effectLevel）、`CollapseShiftSystemAI.kt`（区域系统 AI 落点）、一个用鼠标落点的系统（如 `astd_stasis_field` 若存在，看它怎么取鼠标世界坐标）。确认 `.system` 的 type 该用什么（区域施放可能 type=NO_REGEN_WHEN_IN_USE 或自定义；落点用 `ship.mouseTarget` for player）。

- [ ] **Step 2: 实现 stats + AI**

- `EchoFixationSystemStats`（基类）：`apply` 在 state 进入 IN/ACTIVE 时调用 `EchoFixationField.spawn(engine, ship, targetX, targetY)`（玩家用 `ship.mouseTarget`，AI 用 AI 算的落点；落点选择范围 ~2000su，spec §2.1）。难度系数从 ship 取。Crewed/Automated 分版覆盖难度/表现差异（参照 ARC 分版）。
- `EchoFixationSystemAI`：实现 `ShipSystemAIScript`，敌方旗舰在敌群密集处/前方落场（参照 CollapseShiftSystemAI）。

- [ ] **Step 3: ss-csv catalog 改真实类**

`Catalog_ShipSystems_LENS.kt`：把 `astd_echo_fixation_crewed/_automated` 的 statsScript 改为 `EchoFixationCrewedSystemStats`/`EchoFixationAutomatedSystemStats`，aiScript 设 `EchoFixationSystemAI`，type 设步骤1确认的值。i18n 描述补全（type2=特殊）。

- [ ] **Step 4: 写回 contents + 编译**

Run:
```bash
./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true
./gradlew compileKotlin
```
确认 `contents/data/shipsystems/astd_echo_fixation_*.system` 的 statsScript 已是真实类。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/EchoFixation*.kt \
        ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/shipsystems/lens/Catalog_ShipSystems_LENS.kt \
        ss-csv/src/main/resources/i18n/zh-cn.properties \
        contents/data/shipsystems/
git commit -m "feat(lens): real echo fixation ship system (stats + AI), replace placeholder

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 回声定影 Shader 特效（快照场边界）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationFieldVisualEffect.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationFieldVisualEffectTest.kt`
- Modify: `EchoFixationField.kt`（每帧驱动视觉）

定影场的圆形边界 + 脉动 shader（紫罗兰，呼应 ASTD_LENS）。镜像 `ArcJetShockwaveRingEffect` 完整范式（内嵌 GLSL + effectSpec + keyed upsert + spec 测试）。

- [ ] **Step 1: 读 shader 范式**

读 `src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ArcJetShockwaveRingEffect.kt`（全）+ 其测试 `ArcJetShockwaveRingEffectTest.kt` + `src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain/ShipSystemShaderEffect.kt` + `ShaderEffectSpec.kt` / `ShaderUniforms.kt`。

- [ ] **Step 2: 写 effect spec 测试**（范式照 ArcJetShockwaveRingEffectTest）

```kotlin
package cn.kasuminova.astd.renderer.effect.lens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EchoFixationFieldVisualEffectTest {
    @Test fun `effect spec has stable id and required uniforms`() {
        val spec = EchoFixationFieldVisualEffect.effectSpec
        assertEquals("astd_echo_fixation_field", spec.id.value)
        val keys = spec.uniformSchema.definitions.map { it.key }.toSet()
        assertTrue("radius" in keys, "expected radius uniform")
        assertTrue("pulse" in keys || "progress" in keys, "expected a progress/pulse uniform")
    }

    @Test fun `render radius matches field radius`() {
        // 几何渲染半径应覆盖场半径（含边缘羽化余量）。
        assertTrue(EchoFixationFieldVisualEffect.effectSpec.renderRadius >= 700f)
    }
}
```
> 按 ArcJetShockwaveRingEffectTest 的真实断言风格调整字段名（spec.id.value / uniformSchema.definitions 以实际 API 为准）。

- [ ] **Step 3: Run, verify fail** → `./gradlew test --tests "...EchoFixationFieldVisualEffectTest"` FAIL。

- [ ] **Step 4: 实现 effect**（内嵌 GLSL：圆形 SDF 边界 + 脉动 + 紫罗兰）

照 ArcJetShockwaveRingEffect 结构：`object EchoFixationFieldVisualEffect : ShipSystemShaderEffect`，effectSpec（id=astd_echo_fixation_field，program 内嵌 vertex/fragment，geometry WorldQuad，material Additive，uniforms[radius, progress, alpha...]，layer BelowParticles，keyed staleAfterSeconds）。fragment 做 circle outline SDF + 沿定影进度脉动。

- [ ] **Step 5: 驱动**

`EchoFixationField` 每帧（定影期）调 `EchoFixationFieldVisualEffect.upsert(...)` 提交场边界（center=场心，radius=场半径，progress=定影进度）。

- [ ] **Step 6: Run test + compile + commit**

Run: `./gradlew test --tests "...EchoFixationFieldVisualEffectTest"` PASS；`./gradlew compileKotlin`。
```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationFieldVisualEffect.kt \
        src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationFieldVisualEffectTest.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationField.kt
git commit -m "feat(lens): echo fixation field boundary shader effect

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 回声定影残影回放（BoxUtil）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationAfterimageRenderer.kt`
- Modify: `EchoFixationField.kt`（回放时驱动残影）

残影需要采样敌舰贴图（shader host 不支持纹理采样），用 BoxUtil sprite overlay。回放瞬间为每个记录敌舰在「过去坐标」绘制半透明敌舰影像（紫调），随时间淡出。

> 集成层（BoxUtil sprite），不单测；视觉由 Task 12 实机验证（残影帧计数）。

- [ ] **Step 1: 读 BoxUtil sprite 范式**

读 `src/main/kotlin/cn/kasuminova/astd/renderer/boxutil/BoxUtilCombatVfx.kt`，找 sprite/overlay 绘制 API（半透明、定位、淡出）。若 BoxUtil 无直接 sprite overlay，用 `MagicRender`/`SpriteAPI` + CombatLayeredRenderingPlugin（参照 `ASTDNegentropyChargeBarRenderer` 的 layered rendering 范式）。

- [ ] **Step 2: 实现残影渲染器**

`EchoFixationAfterimageRenderer.spawn(engine, ship, pastX, pastY, facing)`：取 `ship.spriteAPI`（或 hullSpec sprite），在过去坐标以半透明紫调绘制，~1s 淡出。设残影数量上限（防刷）。

- [ ] **Step 3: 驱动 + 计数**

`EchoFixationField` 回放时对每个记录敌舰调 spawn；加 telemetry 计数器 `echoFixationAfterimageFrames`（供 Task 12 验证）。

- [ ] **Step 4: 编译 + 提交**

Run: `./gradlew compileKotlin`
```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/EchoFixationAfterimageRenderer.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/lens/system/EchoFixationField.kt
git commit -m "feat(lens): echo fixation afterimage replay via BoxUtil sprite

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 标记高光 Shader 特效（误差 + 深水）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DriftMarkVisualEffect.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DeepWaterMarkVisualEffect.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/MarkVisualEffectTest.kt`
- Modify: `ASTDLensArrayCoreHullMod.kt`（advance 驱动：遍历带标记敌舰提交高光）

被误差/深水标记的敌舰周围显示高光环（误差=暖紫、深水=冷青蓝，区分两类）。强度按标记层数。镜像 ArcJetShockwave 范式（per-ship keyed upsert）。

- [ ] **Step 1: 写 effect spec 测试**（两 effect 的 id/uniform 断言，照 Task 6 风格）

- [ ] **Step 2: Run fail → 实现两个 effect**

内嵌 GLSL：环形 SDF + Fresnel 高光，uniform[markLevel, color...]，per-ship keyed。两个 effect 共用 fragment 结构、不同默认色。

- [ ] **Step 3: 驱动**

`ASTDLensArrayCoreHullMod.advance`：每帧遍历 `engine.ships`，对 `LensMarks.driftStacks(ship)>0` 的提交 DriftMarkVisualEffect、`deepWaterStacks>0` 的提交 DeepWaterMarkVisualEffect（keyed，per-ship）。设可见性/性能：仅对在场存活敌舰。

- [ ] **Step 4: test + compile + commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DriftMarkVisualEffect.kt \
        src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/DeepWaterMarkVisualEffect.kt \
        src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/MarkVisualEffectTest.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt
git commit -m "feat(lens): drift/deep-water mark highlight shader effects

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: 幽灵信号波 Shader 特效

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffect.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffectTest.kt`
- Modify: `ASTDLensArrayCoreHullMod.kt`（无人模式幽灵信号触发时提交波）

无人模式幽灵信号剥离导弹制导时，以本舰为中心放径向干扰波（电青蓝，呼应 ASTD_ARC？或紫，与 lens 一致——用紫）。one-shot 或低频 keyed。

- [ ] **Step 1: 测试（id/uniform 断言）→ fail → 实现**（内嵌 GLSL 径向扩张波）

- [ ] **Step 2: 驱动**：`ASTDLensArrayCoreHullMod` 的 `ghostSignal` 在剥离导弹时（或按节奏）`emit` 一次波（center=本舰，radius=2000su）。

- [ ] **Step 3: test + compile + commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffect.kt \
        src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/GhostSignalWaveEffectTest.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt
git commit -m "feat(lens): ghost signal radial wave shader effect

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: 插件②视差甲板（逻辑 + 出库相位视觉）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ParallaxDecksMath.kt` + test
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensParallaxDecksHullMod.kt`
- Modify: `Catalog_HullMods_LENS.kt`（注册 `astd_lens_parallax_decks`）+ `.ship` builtInMods（经 ss-csv）+ strings.json（tooltip）

spec §4：出库 ~2s 相位隐形 + 100% 时流；机群命中叠误差标记（每敌每秒上限 1~2）；整备返航在本舰 1000su 内 50% 承伤减免。fighter API 是最大风险（调研标记战斗层无先例）。

- [ ] **Step 1: 纯函数 + 测试**

`ParallaxDecksMath`：出库隐形窗口判定（出库时长<2s）、整备减伤范围判定（dist<1000）、机群叠标记每敌每秒上限。测试覆盖这些判定。

- [ ] **Step 2: fighter API 调研（关键风险）**

实机/源码确认：如何枚举本舰机群（`ship.getDeployedFighters`?）、读单机出库时长、读机群「整备/返航」意图、给 fighter 设相位隐形(`setPhased`?)/时流。**若某 API 不可达，报告 DONE_WITH_CONCERNS 说明，用最接近的替代（如出库隐形用短时 `setCollisionClass(NONE)`+alpha，时流用 fighter 的 mutableStats.timeMult），不留 TODO 占位。**

- [ ] **Step 3: 实现 hullmod**（advanceInCombat 驱动三效果）+ ss-csv 注册 + writeSsCsvToContents + strings tooltip。

- [ ] **Step 4: test + compile + commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensParallaxDecksHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ParallaxDecksMath.kt \
        src/test/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ParallaxDecksMathTest.kt \
        ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/hullmods/lens/Catalog_HullMods_LENS.kt \
        ss-csv/src/main/resources/i18n/zh-cn.properties \
        contents/data/hullmods/hull_mods.csv contents/data/hulls/ \
        contents/data/strings/strings.json
git commit -m "feat(lens): parallax decks hullmod (phase-launch, mark-on-hit, recovery)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: 插件③渗透潮汐（逻辑 + 潮汐场 Shader）

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/PermeatingTideMath.kt` + test
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensPermeatingTideHullMod.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffect.kt` + test
- Modify: `Catalog_HullMods_LENS.kt` + `.ship` builtInMods + strings.json

spec §5：~2500su 场，场内敌舰每 2.5s~5s 叠深水标记（越近越快，1000su 最快、2500su 不叠），难度 m∈[1,2]（最快 1.25s/层）；过载退潮清全场标记（调 Task 1 `clearAllLensMarks`）。潮汐场 shader（FBM 涟漪涨落）。

- [ ] **Step 1: 纯函数 + 测试**

`PermeatingTideMath`：按距离插值叠加间隔（dist<=1000→最快间隔，dist>=2500→不叠/∞，线性），难度缩放（m=2 时间隔×0.5 至 1.25s），过载退潮判定。测试覆盖距离插值边界 + 难度缩放。

- [ ] **Step 2: 潮汐场 shader effect + 测试**（内嵌 GLSL：径向 FBM 涟漪 + 涨落 alpha，紫罗兰，2500su quad，keyed）

- [ ] **Step 3: 实现 hullmod**

advanceInCombat：维护涨潮节奏（每帧/间隔遍历场内敌舰按 `PermeatingTideMath` 叠 `LensMarks.applyDeepWaterMark`）；过载（`ship.fluxTracker.isOverloaded`）时调 `LensMarks.clearAllLensMarks(engine)`；每帧 upsert 潮汐场视觉。ss-csv 注册 + writeSsCsvToContents + tooltip。

- [ ] **Step 4: test + compile + commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensPermeatingTideHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/PermeatingTideMath.kt \
        src/test/kotlin/cn/kasuminova/astd/combat/hullmods/lens/PermeatingTideMathTest.kt \
        src/main/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffect.kt \
        src/test/kotlin/cn/kasuminova/astd/renderer/effect/lens/PermeatingTideFieldEffectTest.kt \
        ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/hullmods/lens/Catalog_HullMods_LENS.kt \
        ss-csv/src/main/resources/i18n/zh-cn.properties \
        contents/data/hullmods/hull_mods.csv contents/data/hulls/ \
        contents/data/strings/strings.json
git commit -m "feat(lens): permeating tide hullmod + tide field shader effect

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: SSOptimizer 实机集成测试（机制 + 视觉验证）

**Files:**
- Modify: `contents/data/config/astd_automation_scenarios.json`（新场景 `lens_phase2_mechanisms`）
- Modify: `contents/data/missions/`（新建 `lens_phase2_mechanisms` mission，或复用 phase1 mission 加敌群）
- Modify: `src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt`（加场景 helper）
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt`（lens phase2 分支 + 证据采集）
- Modify: `tools/verify_ingame_vfx_automation.py`（lens phase2 验证分支）

镜像阶段一 `lens_phase1_foundation` 的 automation 范式。phase2 场景部署引力透镜级 + 敌群，自动施放回声定影系统、触发潮汐叠标记、机群叠标记，断言机制证据 + 视觉特效计数。

证据字段（机制）：
- `echoFixationFieldActive`（系统施放后场存在）、`echoFixationCognitiveTearApplied`（有敌舰被认知撕裂，承伤>1）、`echoFixationAfterimageFrames`>0（残影渲染）。
- `tideDeepWaterStacksOnEnemy`>0（潮汐对场内敌舰叠了深水标记）。
- `parallaxDriftStacksFromFighters`>0（机群叠了误差标记）—— 若 fighter 机制本回合难触发，降级为「插件挂载」断言 `parallaxDecksHullmod`==true。
- `tacticalLinkBonusApplied`（载人收割端生效，若 Task 2 选独立层）。
视觉计数（shader effect 提交计数器，照 arcJetShockwaveFrames 范式）：
- `echoFixationFieldVisualFrames`>0、`driftMarkVisualFrames`>0、`deepWaterMarkVisualFrames`>0、`ghostSignalWaveFrames`>0、`tideFieldVisualFrames`>0。

- [ ] **Step 1: 读阶段一 automation 范式**（`lens_phase1_foundation` 在 4 个文件里的完整实现，照搬结构）。
- [ ] **Step 2: 加场景定义 + mission + scenario helper**。
- [ ] **Step 3: combat plugin lens-phase2 分支**：部署引力透镜级（载人）+ 敌群（多敌舰供标记/定影）；脚本驱动施放回声定影系统（`ship.useSystem()` 或直接 `EchoFixationField.spawn`）、确保潮汐/机群运转；采集上述证据字段（机制状态 + 各 shader effect 的 telemetry 计数器）。
- [ ] **Step 4: verify 脚本 lens-phase2 分支**：断言机制证据 + 视觉计数全 >0/true。视觉计数验证「特效确实提交渲染」（这是「视觉验证」的可自动化部分——shader effect 被提交即证明视觉管线生效；像素级外观仍需人工/截图，记录为人工复核项）。
- [ ] **Step 5: 跑实机**

Run:
```bash
./gradlew compileKotlin
ASTD_AUTOMATION_SCENARIO=lens_phase2_mechanisms ./gradlew smokeTestGame
```
Expected: `PASS ASTD Gravitational Lens phase-2 ...`，BUILD SUCCESSFUL。各证据字段从真实游戏日志重建、全部满足。

> 若某视觉/机制证据 FAIL，按字段回溯对应 Task。若是 fighter 机制（Task 10）实机难稳定触发，按计划降级为挂载断言并 log 说明（不伪造）。**不伪造通过；测试基础设施问题报 BLOCKED。**

- [ ] **Step 6: Commit**

```bash
git add contents/data/config/astd_automation_scenarios.json \
        contents/data/missions/lens_phase2_mechanisms/ \
        contents/data/missions/mission_list.csv \
        src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt \
        tools/verify_ingame_vfx_automation.py
git commit -m "test(lens): phase-2 in-game integration scenario (mechanisms + shader vfx counts)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: 全量回归 + 真相源对齐 + 人工视觉复核清单

**Files:** 无新代码（验证 + 收尾）

- [ ] **Step 1: 全量测试**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL，所有新增纯函数测试 + shader spec 测试通过，既有测试（含 StandardVariantBuiltInHullmodDataTest / ArcProductionCopyReviewTest）不回归。

- [ ] **Step 2: 真相源对齐确认**

Run: `./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true` 后 `git status --short`
Expected: 无未提交的 contents 漂移（catalog 与 contents 一致、幂等）。若有漂移，提交对齐。

- [ ] **Step 3: 部署 + 两个 automation 场景回归**

Run:
```bash
./gradlew deployMod
ASTD_AUTOMATION_SCENARIO=lens_phase1_foundation ./gradlew smokeTestGame
ASTD_AUTOMATION_SCENARIO=lens_phase2_mechanisms ./gradlew smokeTestGame
ASTD_AUTOMATION_SCENARIO=arc_production_ships_vfx_tooltip ./gradlew smokeTestGame
```
Expected: 三个场景全 PASS（lens phase1/phase2 + arc 不回归）。

- [ ] **Step 4: 人工视觉复核清单（记录，非自动化）**

输出一份给用户的人工复核项（automation 只能验证「特效已提交」，外观需人眼）：
1. 回声定影：场边界圆环、4s 后残影回放、被撕裂敌舰视觉。
2. 误差/深水标记高光：两类颜色可区分、强度随层数。
3. 幽灵信号波：扩散观感。
4. 潮汐场：~2500su 涟漪涨落、过载退潮消散。
5. 整体紫线视觉一致性（与 ASTD_LENS 引擎尾焰、grav_lens 贴图协调）。

- [ ] **Step 5: Final commit（若有对齐/微调）**

```bash
git add -A 2>/dev/null; git status --short
# 仅 add 合理变更后提交
git commit -m "chore(lens): phase-2 regression alignment and verification" || echo "nothing to commit"
```

---

## 阶段三接缝（备忘，不在本计划）

- **认知撕裂真·屏幕扭曲**：需 shader host 扩展 ScreenSpaceQuad + FBO 屏幕纹理捕获（调研 Tier 3，★★★）。本阶段用 BoxUtil 残影 + 标记高光近似；phase 2.5 单独做 host 扩展。
- **深水标记深度梯度**（潮汐内按层数 per-pixel）：需 uniform array LUT；本阶段潮汐场只做形状涟漪，深度由被标记敌舰高光（Task 8）表现。
- **VS_LENS_DAMAGE_MULT_KEY 伤害接收端**：深水标记「−对透镜伤害」已写入 stat，若需在透镜受击侧实际读取生效，phase 3 接伤害修饰。
- **图标/状态栏专属美术**：专业美术后补（标记图标、状态栏图标、mission icon 当前占位）。
- **copy-review 文案终审**：机制定稿后单独走 copy-review 流程。
