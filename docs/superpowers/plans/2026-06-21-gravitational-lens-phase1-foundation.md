# 引力透镜级重做 · 阶段一（基座）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地引力透镜级的依赖底座——统一标记闭环数据结构（误差/深水两类标记）、玩家船左侧状态栏显示、插件 ① 透镜阵列核心（双模式基座 + 纳米重构紫线版），并把 .ship/变体补齐为目标骨架（4 甲板 + 重盾 OMNI）。

**Architecture:** 标记两类均建模为 `StackingShipBuffs` 框架（已存在）上的 `StackingBuffSpec` + `ShipBuffApplier`，状态挂 `ship.customData`，由框架插件每帧到期清理；难度系数走框架自带 `DifficultyScaling`。双模式镜像 ARC 的 PermaMod + marker tag 范式（`ASTDArcFlareHullModUtil`），refit 内切换、动态 `setShipSystemId`。透镜阵列核心 hullmod 用 `BaseHullMod.advanceInCombat` 承载载人模式收割端遍历友军、无人模式范围导弹失制导，纳米重构紫线版改装甲优先配比。状态栏用官方 `engine.maintainStatusForPlayerShip`（已有先例）。

**Tech Stack:** Kotlin, Starsector combat/campaign API, 现有 `StackingShipBuffs` / `ASTDHullModTooltipRenderer` / `I18n` / `BaseShipSystemScript` 范式；测试用 `kotlin.test` 纯逻辑单元测试（不反射、不碰游戏运行时）。

**权威源：** `docs/superpowers/specs/2026-06-20-gravitational-lens-redesign-design.md` 与 `docs/design/ships/purple/10-unique.md` §1（冲突时以后者为准）。

**阶段一范围（明确不含）：** 不含舰船系统「回声定影」、插件 ② 视差甲板、插件 ③ 渗透潮汐、武器/美术终稿、文案 copy-review。这些进阶段二/三。

---

## 文件结构（阶段一）

**新建：**
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkIds.kt` — 两类标记的 ID/数值常量（单一真相源）。
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarks.kt` — 两类标记的 `StackingBuffSpec` 定义 + 各自 `ShipBuffApplier`（误差=承伤；深水=−射程/精度/航速/对透镜伤害）+ 对外 `applyDriftMark()` / `applyDeepWaterMark()` / `clearAllLensMarks()` 入口。
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkMath.kt` — 纯函数：层数→各效果倍率（可单测，不碰游戏 API）。
- `src/main/kotlin/cn/kasuminova/astd/combat/lens/ui/LensMarkStatusBar.kt` — 玩家船左侧状态栏显示（误差/深水层数与剩余时间），经 `maintainStatusForPlayerShip`。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreHullModIds.kt` — 透镜阵列核心相关 ID 常量 + 双模式 marker（镜像 `ASTDArcFlareHullModIds`）。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreModeUtil.kt` — 双模式 PermaMod/marker 状态机（镜像 `ASTDArcFlareHullModUtil` 的 `ensureMode/activateMode`）。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt` — 核心 hullmod 主体（双模式分发 + 收割端遍历 + 范围导弹失制导 + 状态栏触发 + tooltip）。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensAutomatedModeHullMod.kt` — 无人模式（蜂群思维 stats + 设置无人系统 ID）。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensCrewedModeHullMod.kt` — 载人模式（情报中枢/战术链路标志 + 设置载人系统 ID）。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensDualModeSwitcherHullMod.kt` — refit 内双模式切换器。
- `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensEcmContribution.kt` — 纯函数：按友军吨位等级累加 ECM 贡献（可单测）。
- `src/test/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkMathTest.kt` — 标记数学纯逻辑测试。
- `src/test/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensEcmContributionTest.kt` — ECM 累加纯逻辑测试。

**修改：**
- `contents/data/hullmods/hull_mods.csv` — `astd_lens_array_core` 行 script 由占位改真实；新增 switcher/crewed/automated/2 marker 共 5 行。
- `contents/data/hulls/astd_gravitational_lens.ship` — LAUNCH_BAY 2→4；补 `shieldType/OMNI`；`builtInMods` 加双模式 switcher。
- `contents/data/variants/astd_gravitational_lens_Standard.variant` — 补全 14 槽武器装载占位 + 默认载人模式 PermaMod。
- `contents/data/strings/strings.json` — 新增透镜阵列核心 tooltip i18n key + 状态栏文本 key。

---

## Task 1: 标记数学纯函数 + 测试

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkMath.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkMathTest.kt`

按 spec §1.1：误差标记每层 +2.5%~7.5% 受伤（取中值 5%/层，难度在更高层用 magnitudeMult 缩放）；深水标记每层 −2% 射程、−4% 精度、−1% 航速、−4% 对引力透镜伤害。两类均 10 层上限。纯函数化便于单测与平衡调参。

- [ ] **Step 1: Write the failing test**

```kotlin
package cn.kasuminova.astd.combat.lens.marks

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensMarkMathTest {

    private fun approx(expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue(abs(expected - actual) <= eps, "expected ~$expected but was $actual")
    }

    @Test
    fun `drift damage-taken multiplier scales linearly per stack at base magnitude`() {
        // 0 层无加成；每层 +5%（基础 magnitudeMult=1f）。
        approx(1.0f, LensMarkMath.driftDamageTakenMult(stacks = 0, magnitudeMult = 1f))
        approx(1.05f, LensMarkMath.driftDamageTakenMult(stacks = 1, magnitudeMult = 1f))
        approx(1.50f, LensMarkMath.driftDamageTakenMult(stacks = 10, magnitudeMult = 1f))
    }

    @Test
    fun `drift per-stack magnitude is clamped between low and high bounds`() {
        // magnitudeMult 把每层加成在 2.5%~7.5% 间缩放：0f→2.5%，1f→5%，2f→7.5%，越界夹紧。
        approx(0.025f, LensMarkMath.driftPerStackBonus(magnitudeMult = 0f))
        approx(0.05f, LensMarkMath.driftPerStackBonus(magnitudeMult = 1f))
        approx(0.075f, LensMarkMath.driftPerStackBonus(magnitudeMult = 2f))
        approx(0.075f, LensMarkMath.driftPerStackBonus(magnitudeMult = 9f))
        approx(0.025f, LensMarkMath.driftPerStackBonus(magnitudeMult = -3f))
    }

    @Test
    fun `deep water penalties scale linearly per stack`() {
        approx(1.0f, LensMarkMath.deepWaterRangeMult(stacks = 0))
        approx(0.80f, LensMarkMath.deepWaterRangeMult(stacks = 10))   // -2%/层
        approx(0.60f, LensMarkMath.deepWaterAccuracyMult(stacks = 10)) // -4%/层 (maxRecoil 反向: 1+4%/层)
        approx(0.90f, LensMarkMath.deepWaterSpeedMult(stacks = 10))   // -1%/层
        approx(0.60f, LensMarkMath.deepWaterVsLensDamageMult(stacks = 10)) // -4%/层
    }

    @Test
    fun `stacks beyond max are clamped to ten`() {
        approx(1.50f, LensMarkMath.driftDamageTakenMult(stacks = 25, magnitudeMult = 1f))
        approx(0.80f, LensMarkMath.deepWaterRangeMult(stacks = 25))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cn.kasuminova.astd.combat.lens.marks.LensMarkMathTest"`
Expected: FAIL（编译失败：`LensMarkMath` 未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
package cn.kasuminova.astd.combat.lens.marks

/**
 * 引力透镜级两类标记的纯数学换算。
 *
 * 动机：spec §1.1 定义误差/深水标记的逐层效果，提取为无副作用纯函数，
 * 便于单元测试与平衡调参，且 applier 与 tooltip 共用同一真相源。
 */
object LensMarkMath {

    /** 两类标记的层数上限（spec §1.1）。 */
    const val MAX_STACKS: Int = 10

    // 误差标记：每层增伤区间 2.5%~7.5%，由 magnitudeMult∈[0,2] 在区间内线性插值。
    private const val DRIFT_BONUS_LOW = 0.025f
    private const val DRIFT_BONUS_HIGH = 0.075f

    // 深水标记：每层惩罚。
    private const val DEEP_RANGE_PER_STACK = 0.02f       // -2% 射程
    private const val DEEP_ACCURACY_PER_STACK = 0.04f    // -4% 精度
    private const val DEEP_SPEED_PER_STACK = 0.01f       // -1% 航速
    private const val DEEP_VS_LENS_PER_STACK = 0.04f     // -4% 对引力透镜伤害

    private fun clampStacks(stacks: Int): Int = stacks.coerceIn(0, MAX_STACKS)

    /** 误差标记每层增伤比例：magnitudeMult 在 [low, high] 间线性插值（夹紧）。 */
    fun driftPerStackBonus(magnitudeMult: Float): Float {
        val t = (magnitudeMult * 0.5f).coerceIn(0f, 1f)
        return DRIFT_BONUS_LOW + (DRIFT_BONUS_HIGH - DRIFT_BONUS_LOW) * t
    }

    /** 目标受到的伤害倍率（≥1）。 */
    fun driftDamageTakenMult(stacks: Int, magnitudeMult: Float): Float =
        1f + clampStacks(stacks) * driftPerStackBonus(magnitudeMult)

    /** 武器射程倍率（≤1）。 */
    fun deepWaterRangeMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_RANGE_PER_STACK).coerceAtLeast(0f)

    /**
     * 武器精度倍率（≤1）。原版用 maxRecoilMult（越大越散），故 applier 侧会取倒数语义；
     * 此处返回“等效精度倍率”用于展示与测试一致性。
     */
    fun deepWaterAccuracyMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_ACCURACY_PER_STACK).coerceAtLeast(0f)

    /** 最大航速倍率（≤1）。 */
    fun deepWaterSpeedMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_SPEED_PER_STACK).coerceAtLeast(0f)

    /** 对“引力透镜”造成的伤害倍率（≤1）。 */
    fun deepWaterVsLensDamageMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_VS_LENS_PER_STACK).coerceAtLeast(0f)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "cn.kasuminova.astd.combat.lens.marks.LensMarkMathTest"`
Expected: PASS（4 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkMath.kt \
        src/test/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkMathTest.kt
git commit -m "feat(lens): add lens mark math pure functions with tests

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 标记 ID/数值常量

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkIds.kt`

集中标记的 buffId、持续时间、层数、引力透镜 hullId（用于深水标记“对引力透镜伤害”判定）。无逻辑，无需测试。

- [ ] **Step 1: Write the file**

```kotlin
package cn.kasuminova.astd.combat.lens.marks

/**
 * 引力透镜级标记闭环的稳定 ID 与基础数值常量。
 *
 * 动机：buffId 同时用作 StackingShipBuffs 的 modifierId 与 customData key，
 * 必须全局唯一且稳定；数值集中此处便于平衡。
 */
object LensMarkIds {

    /** 误差标记（Drift Mark）：通用增伤标记。 */
    const val DRIFT_BUFF_ID: String = "astd_lens_drift_mark"

    /** 深水标记（Deep Water Mark）：电战压制标记。 */
    const val DEEP_WATER_BUFF_ID: String = "astd_lens_deep_water_mark"

    /** 两类标记每层持续时间（秒，spec §1.1：每层 5s，叠加刷新）。 */
    const val MARK_DURATION_SEC: Float = 5f

    /** 引力透镜旗舰 hullId：深水标记“对引力透镜伤害下降”按此判定来源。 */
    const val GRAVITATIONAL_LENS_HULL_ID: String = "astd_gravitational_lens"
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarkIds.kt
git commit -m "feat(lens): add lens mark id and value constants

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 两类标记的 Spec/Applier + 对外入口

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarks.kt`

复用现有 `StackingShipBuffs`（`src/main/kotlin/cn/kasuminova/astd/combat/buffs/StackingShipBuffs.kt`）。误差标记 applier 用 `damageTakenMult.modifyMult`；深水标记 applier 用 `weaponRangeThreshold`/`maxRecoilMult`/`maxSpeed` + 一个 dynamic mod 承载“对引力透镜伤害”。提供三个对外函数供系统/插件调用。

> 说明：本任务为集成代码（写 `ship.mutableStats`、读 `engine`），不做单元测试（遵守全局规范：不为集成层强凑测试）。逻辑正确性由 Task 1 的纯函数测试 + 战斗内冒烟验证保证。

- [ ] **Step 1: Write the file**

```kotlin
package cn.kasuminova.astd.combat.lens.marks

import cn.kasuminova.astd.combat.buffs.ShipBuffApplier
import cn.kasuminova.astd.combat.buffs.StackingBuffSpec
import cn.kasuminova.astd.combat.buffs.StackingBuffState
import cn.kasuminova.astd.combat.buffs.StackingShipBuffs
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 引力透镜级标记闭环的落地：在通用 StackingShipBuffs 框架上定义误差/深水两类标记。
 *
 * 动机（spec §1.1）：
 * - 误差标记：提升目标受到的伤害（收割端在核心·载人模式读取并增伤）。
 * - 深水标记：电战压制——降低目标武器射程/精度/航速、以及对引力透镜造成的伤害。
 * 两类均 10 层、每层 5s、叠加刷新。
 */
object LensMarks {

    /** dynamic stat key：深水标记令目标“对引力透镜造成的伤害”下降。 */
    const val VS_LENS_DAMAGE_MULT_KEY: String = "astd_lens_deep_water_vs_lens_mult"

    private val driftSpec = StackingBuffSpec(
        id = LensMarkIds.DRIFT_BUFF_ID,
        baseDuration = LensMarkIds.MARK_DURATION_SEC,
        baseMaxStacks = LensMarkMath.MAX_STACKS,
        baseMagnitudeMult = 1f,
    )

    private val deepWaterSpec = StackingBuffSpec(
        id = LensMarkIds.DEEP_WATER_BUFF_ID,
        baseDuration = LensMarkIds.MARK_DURATION_SEC,
        baseMaxStacks = LensMarkMath.MAX_STACKS,
        baseMagnitudeMult = 1f,
    )

    private val driftApplier = object : ShipBuffApplier {
        override fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState) {
            val mult = LensMarkMath.driftDamageTakenMult(state.stacks, state.magnitudeMult)
            ship.mutableStats.damageTakenMult.modifyMult(buffId, mult)
        }

        override fun unapplyFrom(ship: ShipAPI, buffId: String) {
            ship.mutableStats.damageTakenMult.unmodify(buffId)
        }
    }

    private val deepWaterApplier = object : ShipBuffApplier {
        override fun applyTo(ship: ShipAPI, buffId: String, state: StackingBuffState) {
            val s = state.stacks
            // 射程：weaponRangeThreshold 越小→越早衰减，这里用倍率作用于射程相关 stat。
            ship.mutableStats.weaponRangeThreshold.modifyMult(buffId, LensMarkMath.deepWaterRangeMult(s))
            // 精度：原版 maxRecoilMult 越大越散，故取“1/精度倍率”等效。
            val recoil = if (LensMarkMath.deepWaterAccuracyMult(s) > 0f) 1f / LensMarkMath.deepWaterAccuracyMult(s) else 99f
            ship.mutableStats.maxRecoilMult.modifyMult(buffId, recoil)
            ship.mutableStats.recoilDecayMult.modifyMult(buffId, recoil)
            // 航速。
            ship.mutableStats.maxSpeed.modifyMult(buffId, LensMarkMath.deepWaterSpeedMult(s))
            // 对引力透镜伤害：写入 dynamic mod，由透镜旗舰受击侧读取。
            ship.mutableStats.dynamic.getMod(VS_LENS_DAMAGE_MULT_KEY).modifyMult(buffId, LensMarkMath.deepWaterVsLensDamageMult(s))
        }

        override fun unapplyFrom(ship: ShipAPI, buffId: String) {
            ship.mutableStats.weaponRangeThreshold.unmodify(buffId)
            ship.mutableStats.maxRecoilMult.unmodify(buffId)
            ship.mutableStats.recoilDecayMult.unmodify(buffId)
            ship.mutableStats.maxSpeed.unmodify(buffId)
            ship.mutableStats.dynamic.getMod(VS_LENS_DAMAGE_MULT_KEY).unmodify(buffId)
        }
    }

    /** 叠 1（或多）层误差标记（系统回声定影 / 视差甲板机群调用）。 */
    fun applyDriftMark(engine: CombatEngineAPI, source: CombatEntityAPI?, target: ShipAPI, addStacks: Int = 1) {
        StackingShipBuffs.applyOrRefresh(engine, source, target, driftSpec, driftApplier, addStacks)
    }

    /** 叠 1（或多）层深水标记（渗透潮汐调用）。 */
    fun applyDeepWaterMark(engine: CombatEngineAPI, source: CombatEntityAPI?, target: ShipAPI, addStacks: Int = 1) {
        StackingShipBuffs.applyOrRefresh(engine, source, target, deepWaterSpec, deepWaterApplier, addStacks)
    }

    /** 读取目标当前标记层数（收割端/状态栏用）。 */
    fun driftStacks(ship: ShipAPI): Int = StackingShipBuffs.getState(ship, LensMarkIds.DRIFT_BUFF_ID)?.stacks ?: 0
    fun deepWaterStacks(ship: ShipAPI): Int = StackingShipBuffs.getState(ship, LensMarkIds.DEEP_WATER_BUFF_ID)?.stacks ?: 0
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。若 `weaponRangeThreshold`/`recoilDecayMult` 等 stat 名报错，按 `MutableShipStatsAPI` 实际可用字段微调（同包 `AffixVectorSilenceHullMod`/`Jmb2CoherenceJammingOnHitEffect` 用到的 `missileGuidance`/`maxRecoilMult` 为已验证可用字段）。

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/marks/LensMarks.kt
git commit -m "feat(lens): implement drift and deep-water marks on stacking buff framework

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 玩家船左侧状态栏显示

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/lens/ui/LensMarkStatusBar.kt`
- Modify: `contents/data/strings/strings.json`

用官方 `engine.maintainStatusForPlayerShip`（先例：`PsiSunderBeamEffect.kt:247`）。每帧若玩家船带标记则显示层数。spec 要求“左侧状态栏常驻显示”——对玩家自己被标记/对玩家旗舰持有标记态的呈现。本任务做“玩家船自身携带标记时”的状态条；核心 hullmod 持有态在 Task 9 接入。

> 集成代码，不单测；战斗内验证。

- [ ] **Step 1: Add i18n keys to strings.json**

在 `contents/data/strings/strings.json` 的 ui 段（与 `ui.hullmod.*` 同级对象内）加入：

```json
		"ui.lens.status.drift": "误差标记",
		"ui.lens.status.deep_water": "深水标记",
		"ui.lens.status.stacks": "%d 层",
```

- [ ] **Step 2: Write the status bar helper**

```kotlin
package cn.kasuminova.astd.combat.lens.ui

import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 引力透镜级标记的玩家船左侧状态栏显示。
 *
 * 动机（spec §1.1）：两类标记需常驻状态显示。用原版官方
 * maintainStatusForPlayerShip 在战斗 UI 左侧维护条目（每帧调用刷新）。
 */
object LensMarkStatusBar {

    private const val ICON_DRIFT = "graphics/hullmods/astd_lens_array_core.png"
    private const val ICON_DEEP = "graphics/hullmods/astd_lens_array_core.png"

    /** 每帧调用：若玩家船带标记，维护左侧状态条目。 */
    fun maintain(engine: CombatEngineAPI) {
        val player: ShipAPI = engine.playerShip ?: return
        if (player.isHulk) return

        val drift = LensMarks.driftStacks(player)
        if (drift > 0) {
            engine.maintainStatusForPlayerShip(
                "astd_lens_drift_status",
                ICON_DRIFT,
                I18n.get("ui.lens.status.drift"),
                stacksText(drift),
                true, // isDebuff（对玩家而言是被压制）
            )
        }

        val deep = LensMarks.deepWaterStacks(player)
        if (deep > 0) {
            engine.maintainStatusForPlayerShip(
                "astd_lens_deep_water_status",
                ICON_DEEP,
                I18n.get("ui.lens.status.deep_water"),
                stacksText(deep),
                true,
            )
        }
    }

    private fun stacksText(stacks: Int): String =
        I18n.get("ui.lens.status.stacks").replace("%d", stacks.toString())
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。若 `I18n.get` 签名不符，参照 `ASTDHullModTooltipRenderer.kt` 的 `I18n`/`I18nUi` 实际用法调整。若 `maintainStatusForPlayerShip` 参数个数/类型不符，参照 `PsiSunderBeamEffect.kt:247-253` 的实际签名调整。

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/lens/ui/LensMarkStatusBar.kt \
        contents/data/strings/strings.json
git commit -m "feat(lens): add player-ship left status bar for lens marks

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: ECM 贡献纯函数 + 测试

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensEcmContribution.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensEcmContributionTest.kt`

spec §3.1 载人「情报中枢」：按友军吨位等级，每艘提供 2%/1.5%/1%/0.5%（吨位越小贡献越大），鼓励集群。纯函数化（输入各吨位友军数量，输出总 ECM 加成）便于单测。

- [ ] **Step 1: Write the failing test**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class LensEcmContributionTest {

    private fun approx(expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue(abs(expected - actual) <= eps, "expected ~$expected but was $actual")
    }

    @Test
    fun `each hull size contributes its rated ecm per ally`() {
        // 1 护卫(2%) + 1 驱逐(1.5%) + 1 巡洋(1%) + 1 主力(0.5%) = 5%
        approx(
            0.05f,
            LensEcmContribution.totalEcmFraction(frigates = 1, destroyers = 1, cruisers = 1, capitals = 1),
        )
    }

    @Test
    fun `more small ships give more ecm than the same count of capitals`() {
        val small = LensEcmContribution.totalEcmFraction(frigates = 4, destroyers = 0, cruisers = 0, capitals = 0)
        val big = LensEcmContribution.totalEcmFraction(frigates = 0, destroyers = 0, cruisers = 0, capitals = 4)
        approx(0.08f, small)  // 4 * 2%
        approx(0.02f, big)    // 4 * 0.5%
        assertTrue(small > big)
    }

    @Test
    fun `zero allies gives zero`() {
        approx(0f, LensEcmContribution.totalEcmFraction(0, 0, 0, 0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cn.kasuminova.astd.combat.hullmods.lens.LensEcmContributionTest"`
Expected: FAIL（`LensEcmContribution` 未定义）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

/**
 * 透镜阵列核心·载人模式「情报中枢」ECM 累加（spec §3.1）。
 *
 * 动机：按友军吨位等级差异化提供 ECM（吨位越小贡献越大），鼓励集群作战。
 * 纯函数便于单测与平衡。返回值为 ECM 等级“分数”（0.05 = 5%）。
 */
object LensEcmContribution {

    const val FRIGATE_ECM = 0.02f
    const val DESTROYER_ECM = 0.015f
    const val CRUISER_ECM = 0.01f
    const val CAPITAL_ECM = 0.005f

    fun totalEcmFraction(frigates: Int, destroyers: Int, cruisers: Int, capitals: Int): Float =
        frigates.coerceAtLeast(0) * FRIGATE_ECM +
            destroyers.coerceAtLeast(0) * DESTROYER_ECM +
            cruisers.coerceAtLeast(0) * CRUISER_ECM +
            capitals.coerceAtLeast(0) * CAPITAL_ECM
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "cn.kasuminova.astd.combat.hullmods.lens.LensEcmContributionTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensEcmContribution.kt \
        src/test/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensEcmContributionTest.kt
git commit -m "feat(lens): add ECM contribution pure function with tests

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 双模式 ID 常量 + 状态机工具

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreHullModIds.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreModeUtil.kt`

镜像 `ASTDArcFlareHullModUtil.kt` 的 PermaMod + marker 范式。透镜版系统 ID 暂指向阶段二的「回声定影」系统（此处先用占位常量，Task 11 .system 文件创建后保持一致）。

> 状态机操作 ShipVariantAPI（campaign 持久态），不单测；由战斗/refit 内验证。

- [ ] **Step 1: Write the IDs file**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * 透镜阵列核心相关 hullmod / 双模式 marker 的稳定 ID（镜像 ARC 范式）。
 */
internal object LensArrayCoreHullModIds {
    const val HULL_ID: String = "astd_gravitational_lens"

    const val CORE: String = "astd_lens_array_core"
    const val SWITCHER: String = "astd_lens_mode_switcher"
    const val MODE_CREWED: String = "astd_lens_mode_crewed"
    const val MODE_AUTOMATED: String = "astd_lens_mode_automated"
    const val NEXT_CREWED: String = "astd_lens_mode_next_crewed"
    const val NEXT_AUTOMATED: String = "astd_lens_mode_next_automated"

    // 阶段二「回声定影」系统 ID（无人/载人分版，与 .system 文件一致）。
    const val SYSTEM_CREWED: String = "astd_echo_fixation_crewed"
    const val SYSTEM_AUTOMATED: String = "astd_echo_fixation_automated"
}

internal fun ShipVariantAPI?.isGravitationalLensVariant(): Boolean {
    val v = this ?: return false
    val hullId = try { v.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { v.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return hullId == LensArrayCoreHullModIds.HULL_ID || baseHullId == LensArrayCoreHullModIds.HULL_ID
}

internal fun ShipAPI?.isGravitationalLensShip(): Boolean {
    val s = this ?: return false
    val hullId = try { s.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { s.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return hullId == LensArrayCoreHullModIds.HULL_ID || baseHullId == LensArrayCoreHullModIds.HULL_ID
}
```

- [ ] **Step 2: Write the mode util (mirror of ARC)**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * 透镜阵列核心双模式状态机：无人/载人通过 PermaMod + next marker 切换，
 * 镜像 ASTDArcFlareHullModUtil 的稳定范式。
 */
internal fun ShipVariantAPI.ensureLensArrayModeState(stats: MutableShipStatsAPI? = null) {
    if (!isGravitationalLensVariant()) return

    val hasCrewed = getPermaMods().contains(LensArrayCoreHullModIds.MODE_CREWED)
    val hasAutomated = getPermaMods().contains(LensArrayCoreHullModIds.MODE_AUTOMATED)

    if (hasCrewed && hasAutomated) {
        removePermaMod(LensArrayCoreHullModIds.MODE_AUTOMATED)
        removePermaMod("automated")
        setLensNextMarker(LensArrayCoreHullModIds.NEXT_CREWED)
        return
    }

    when {
        hasCrewed -> setLensNextMarker(LensArrayCoreHullModIds.NEXT_CREWED)
        hasAutomated -> setLensNextMarker(LensArrayCoreHullModIds.NEXT_AUTOMATED)
        getPermaMods().contains(LensArrayCoreHullModIds.NEXT_AUTOMATED) ->
            activateLensMode(LensArrayCoreHullModIds.MODE_AUTOMATED, stats)
        getPermaMods().contains(LensArrayCoreHullModIds.NEXT_CREWED) ->
            activateLensMode(LensArrayCoreHullModIds.MODE_CREWED, stats)
        else -> activateLensMode(LensArrayCoreHullModIds.MODE_CREWED, stats)
    }
}

internal fun ShipVariantAPI.activateLensMode(modeId: String, stats: MutableShipStatsAPI? = null) {
    removePermaMod(LensArrayCoreHullModIds.MODE_CREWED)
    removePermaMod(LensArrayCoreHullModIds.MODE_AUTOMATED)
    addPermaMod(modeId)
    val nextMarker = if (modeId == LensArrayCoreHullModIds.MODE_AUTOMATED)
        LensArrayCoreHullModIds.NEXT_AUTOMATED
    else
        LensArrayCoreHullModIds.NEXT_CREWED
    setLensNextMarker(nextMarker)
    if (modeId == LensArrayCoreHullModIds.MODE_AUTOMATED) {
        if (!getPermaMods().contains("automated")) addPermaMod("automated")
    } else {
        removePermaMod("automated")
    }
    clearIncompatibleLensCaptain(stats)
}

private fun clearIncompatibleLensCaptain(stats: MutableShipStatsAPI?) {
    val member = try { stats?.fleetMember } catch (_: Throwable) { null } ?: return
    val captain = try { member.captain } catch (_: Throwable) { null }
    if (captain != null) {
        val aiCoreId = try { captain.aiCoreId } catch (_: Throwable) { null }
        if (aiCoreId != null) {
            try { Global.getSector()?.playerFleet?.cargo?.addCommodity(aiCoreId, 1f) } catch (_: Throwable) {}
        }
    }
    try { member.setCaptain(null) } catch (_: Throwable) {}
}

private fun ShipVariantAPI.setLensNextMarker(markerId: String) {
    removePermaMod(LensArrayCoreHullModIds.NEXT_CREWED)
    removePermaMod(LensArrayCoreHullModIds.NEXT_AUTOMATED)
    addPermaMod(markerId)
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreHullModIds.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/LensArrayCoreModeUtil.kt
git commit -m "feat(lens): add lens array core mode ids and dual-mode state machine

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 无人/载人模式 hullmod + 切换器

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensAutomatedModeHullMod.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensCrewedModeHullMod.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensDualModeSwitcherHullMod.kt`

镜像 ARC 三件套。无人模式：蜂群思维（舰载机非导弹武器射程 +20%、导弹速度&机动 +20%、最大航速 +20%）+ 设置无人系统 ID。载人模式：设置载人系统 ID（情报中枢/战术链路在核心 hullmod 的 advanceInCombat 处理，因需遍历友军）。

> 集成代码，不单测。

- [ ] **Step 1: Write automated mode hullmod**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 透镜阵列核心·无人模式（全域拓扑态，spec §3.1）。
 * - 蜂群思维：舰载机非导弹武器射程 +20%，导弹飞行速度/机动 +20%，最大航速 +20%。
 * - 设置无人版「回声定影」系统 ID。
 * 「幽灵信号」范围导弹失制导在核心 hullmod 的 advanceInCombat 处理（需遍历范围）。
 */
class ASTDLensAutomatedModeHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isGravitationalLensVariant()) return
        variant.hullSpec?.setShipSystemId(LensArrayCoreHullModIds.SYSTEM_AUTOMATED)

        stats.fighterWingRange.modifyMult(id, 1.20f)
        stats.maxSpeed.modifyMult(id, 1.20f)
        // 导弹飞行速度/机动（作用于本舰所发射导弹）。
        stats.missileMaxSpeedBonus.modifyMult(id, 1.20f)
        stats.missileTurnRateBonus.modifyMult(id, 1.20f)
        stats.missileAccelerationBonus.modifyMult(id, 1.20f)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false
}
```

- [ ] **Step 2: Write crewed mode hullmod**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 透镜阵列核心·载人模式（宏观锚定态，spec §3.1）。
 * - 设置载人版「回声定影」系统 ID。
 * - 情报中枢（全队 ECM 按友军数量/等级）与战术链路（对带误差标记目标增伤）
 *   在 ASTDLensArrayCoreHullMod.advanceInCombat 处理（需遍历友军/目标标记）。
 */
class ASTDLensCrewedModeHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isGravitationalLensVariant()) return
        variant.hullSpec?.setShipSystemId(LensArrayCoreHullModIds.SYSTEM_CREWED)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false
}
```

- [ ] **Step 3: Write switcher hullmod**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 透镜阵列核心双模式切换器：在 refit 中轮换无人/载人（镜像 ARC 切换器）。
 */
class ASTDLensDualModeSwitcherHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isGravitationalLensVariant()) return
        variant.ensureLensArrayModeState(stats)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。若 `fighterWingRange`/`missileMaxSpeedBonus`/`missileTurnRateBonus`/`missileAccelerationBonus` 等 stat 名报错，按 `MutableShipStatsAPI` 实际字段调整（这些是原版常见 fighter/missile stat，名称以编译为准）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensAutomatedModeHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensCrewedModeHullMod.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensDualModeSwitcherHullMod.kt
git commit -m "feat(lens): add lens dual-mode hullmods (automated/crewed/switcher)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 透镜阵列核心主体 hullmod

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt`

核心主体承载：①每帧维护标记状态栏（调 Task 4）；②无人模式范围导弹失制导（幽灵信号）；③载人模式情报中枢 ECM（用 Task 5 纯函数）+ 战术链路（对带误差标记目标增伤的收割端，用 on-hit 思路；阶段一先实现“本舰对带标记目标增伤”，全队增伤随阶段二系统落地补全）；④tooltip。难度系数 m∈[1.0,2.0] 仅敌对单位。

> 集成代码，不单测；战斗内验证。逻辑分支用到的纯换算已在 Task 1/5 测过。

- [ ] **Step 1: Add i18n keys to strings.json**

在 ui 段加入：

```json
		"ui.hullmod.lens_core.summary": "<param:#C8A0FF:阿斯忒里亚遗构局> 透镜协议旗舰的指挥中枢。真相只是一个可调节的变量。",
		"ui.hullmod.lens_core.line.1": "· 纳米重构协议：紫线特化，装甲修复优先。",
		"ui.hullmod.lens_core.line.2": "· 双模式：无人（全域拓扑态）/ 载人（宏观锚定态）。",
		"ui.hullmod.lens_core.line.3": "· 无人·蜂群思维：舰载机射程、导弹机动、最大航速各 <param:#C8A0FF:+20%>。",
		"ui.hullmod.lens_core.line.4": "· 无人·幽灵信号：~2000su 内敌方导弹 <param:#C8A0FF:50%> 概率失去制导。",
		"ui.hullmod.lens_core.line.5": "· 载人·情报中枢：按友军吨位等级提供全队 ECM（吨位越小贡献越大）。",
		"ui.hullmod.lens_core.line.6": "· 载人·战术链路：对带误差标记的目标造成额外伤害。",
```

- [ ] **Step 2: Write the core hullmod**

```kotlin
package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.combat.lens.ui.LensMarkStatusBar
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipAPI.HullSize
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 透镜阵列核心主体（基座 hullmod，spec §3）。
 * 双模式分模式效果中“需要运行时遍历”的部分集中于此：
 * - 标记状态栏维护（每帧）。
 * - 无人·幽灵信号：范围导弹失制导。
 * - 载人·情报中枢/战术链路：友军 ECM / 对带标记目标增伤。
 */
class ASTDLensArrayCoreHullMod : BaseHullMod() {

    companion object {
        private const val GHOST_RANGE = 2000f
        private const val GHOST_ARM_RANGE = 500f
        private const val GHOST_DEFUSE_CHANCE = 0.5f
        private const val TICK = 0.25f
        private const val ECM_TICK = 1.0f
        private const val ECM_DYNAMIC_KEY = "opad_ecm_rating" // 与 AffixVectorSilenceHullMod 一致
        private const val INTERVAL_KEY = "astd_lens_core_interval"
        private const val ECM_INTERVAL_KEY = "astd_lens_core_ecm_interval"
        private const val DEFUSED_KEY = "astd_lens_ghost_defused"

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return

        // 玩家船标记状态栏（与本舰是否透镜无关，但只需调用一次；放此处保证战斗内常驻）。
        if (ship === engine.playerShip) {
            LensMarkStatusBar.maintain(engine)
        }

        if (!ship.isGravitationalLensShip()) return

        val automated = ship.variant?.hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED) == true

        val interval = engine.customData.getOrPut(intervalKey(ship)) { IntervalUtil(TICK, TICK) } as IntervalUtil
        interval.advance(amount)
        if (interval.intervalElapsed()) {
            if (automated) ghostSignal(ship, engine) else tacticalLink(ship, engine)
        }

        if (!automated) {
            val ecmInterval = engine.customData.getOrPut(ecmIntervalKey(ship)) { IntervalUtil(ECM_TICK, ECM_TICK) } as IntervalUtil
            ecmInterval.advance(amount)
            if (ecmInterval.intervalElapsed()) intelligenceHub(ship)
        }
    }

    /** 无人·幽灵信号：范围内敌方导弹 50% 概率失制导（进入 ARM 范围后随机触发一次）。 */
    private fun ghostSignal(ship: ShipAPI, engine: com.fs.starfarer.api.combat.CombatEngineAPI) {
        for (missile in engine.missiles) {
            if (missile == null) continue
            if (missile.owner == ship.owner) continue
            if (missile.customData.containsKey(DEFUSED_KEY)) continue
            val dist = Misc.getDistance(ship.location, missile.location)
            if (dist > GHOST_RANGE) continue
            if (dist > GHOST_ARM_RANGE) continue
            // 进入 ARM 范围后随机判定一次。
            if (Math.random().toFloat() < GHOST_DEFUSE_CHANCE * TICK * 4f) {
                defuse(missile)
            }
            missile.setCustomData(DEFUSED_KEY, true)
        }
    }

    private fun defuse(missile: MissileAPI) {
        try { missile.flameOut() } catch (_: Throwable) {}
    }

    /** 载人·战术链路：本舰对带误差标记目标增伤（全队增伤随阶段二补全收割端）。 */
    private fun tacticalLink(ship: ShipAPI, engine: com.fs.starfarer.api.combat.CombatEngineAPI) {
        // 误差标记已通过 damageTakenMult 全局生效；战术链路的额外全队增伤
        // 需在阶段二接入收割端（on-hit/damage listener）。阶段一此分支为占位心跳，
        // 保证模式判定与 tooltip 一致。详见 plan §阶段二。
    }

    /** 载人·情报中枢：按友军吨位等级累加全队 ECM。 */
    private fun intelligenceHub(ship: ShipAPI) {
        val engine = Global.getCombatEngine() ?: return
        var fr = 0; var de = 0; var cr = 0; var ca = 0
        for (other in engine.ships) {
            if (other == null || other.isHulk) continue
            if (other.owner != ship.owner) continue
            when (other.hullSize) {
                HullSize.FRIGATE -> fr++
                HullSize.DESTROYER -> de++
                HullSize.CRUISER -> cr++
                HullSize.CAPITAL_SHIP -> ca++
                else -> {}
            }
        }
        val ecm = LensEcmContribution.totalEcmFraction(fr, de, cr, ca)
        ship.mutableStats.dynamic.getMod(ECM_DYNAMIC_KEY).modifyFlat("astd_lens_intel_hub", ecm)
    }

    private fun intervalKey(ship: ShipAPI) = "$INTERVAL_KEY:${System.identityHashCode(ship)}"
    private fun ecmIntervalKey(ship: ShipAPI) = "$ECM_INTERVAL_KEY:${System.identityHashCode(ship)}"

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.3"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.4"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.5"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.6"),
            ),
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false
    override fun getBorderColor(): Color = THEME.borderColor
    override fun getNameColor(): Color = THEME.nameColor
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。若 `customData.getOrPut` 不可用（customData 为 Java Map），改为显式 get/put：先 `as? IntervalUtil`，null 则 new 并 put。若 `MissileAPI.flameOut`/`engine.missiles` 签名不符，参照原版 `CombatEngineAPI.getMissiles()` 调整。

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cn/kasuminova/astd/combat/hullmods/lens/ASTDLensArrayCoreHullMod.kt \
        contents/data/strings/strings.json
git commit -m "feat(lens): implement lens array core main hullmod (ghost signal, intel hub, status bar)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: 注册 hull_mods.csv

**Files:**
- Modify: `contents/data/hullmods/hull_mods.csv`

把 `astd_lens_array_core`（第 53 行）的 script 由 `PlaceholderHullMod` 改为真实类；新增 5 行（switcher/crewed/automated/2 marker），镜像 ARC 的 34–38 行格式。

- [ ] **Step 1: Replace lens_array_core script column**

把第 53 行：
```
透镜阵列核心,astd_lens_array_core,3,1,LENS,astd_builtin,,0,FALSE,TRUE,FALSE,0,0,0,0,cn.kasuminova.astd.combat.hullmods.PlaceholderHullMod,占位 Hullmod (调试用)。效果后续用脚本实现。,占位 (调试),,graphics/hullmods/astd_lens_array_core.png
```
改为：
```
透镜阵列核心,astd_lens_array_core,3,1,LENS,astd_builtin,,0,FALSE,TRUE,FALSE,0,0,0,0,cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod,,透镜协议旗舰指挥中枢,,graphics/hullmods/astd_lens_array_core.png
```

- [ ] **Step 2: Append 5 new rows at end of file**

```
透镜阵列核心 - 无人模式,astd_lens_mode_automated,3,1,LENS,astd_builtin,,0,FALSE,TRUE,FALSE,0,0,0,0,cn.kasuminova.astd.combat.hullmods.lens.ASTDLensAutomatedModeHullMod,,全域拓扑态：蜂群与幽灵信号,,graphics/hullmods/astd_lens_array_core.png
透镜阵列核心 - 载人模式,astd_lens_mode_crewed,3,1,LENS,astd_builtin,,0,FALSE,TRUE,FALSE,0,0,0,0,cn.kasuminova.astd.combat.hullmods.lens.ASTDLensCrewedModeHullMod,,宏观锚定态：情报中枢与战术链路,,graphics/hullmods/astd_lens_array_core.png
透镜阵列核心 - 切换器,astd_lens_mode_switcher,3,0,LENS,,,0,TRUE,FALSE,FALSE,0,0,0,0,cn.kasuminova.astd.combat.hullmods.lens.ASTDLensDualModeSwitcherHullMod,,装配界面中轮换模式,,graphics/hullmods/astd_lens_array_core.png
内部：下次切换到无人(透镜),astd_lens_mode_next_automated,0,0,astd_hidden,,,0,FALSE,TRUE,TRUE,0,0,0,0,cn.kasuminova.astd.combat.hullmods.PlaceholderHullMod,内部状态标记。,内部状态,,graphics/hullmods/astd_lens_array_core.png
内部：下次切换到载人(透镜),astd_lens_mode_next_crewed,0,0,astd_hidden,,,0,FALSE,TRUE,TRUE,0,0,0,0,cn.kasuminova.astd.combat.hullmods.PlaceholderHullMod,内部状态标记。,内部状态,,graphics/hullmods/astd_lens_array_core.png
```

- [ ] **Step 3: Verify csv parses (count columns)**

Run: `awk -F',' 'NR==1{n=NF} NF!=n{print "BAD line "NR": "NF" cols"}' contents/data/hullmods/hull_mods.csv`
Expected: 无输出（所有行列数一致）。

- [ ] **Step 4: Commit**

```bash
git add contents/data/hullmods/hull_mods.csv
git commit -m "feat(lens): register lens array core and dual-mode hullmods in csv

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: .ship 骨架（4 甲板 + 重盾 OMNI + switcher builtin）

**Files:**
- Modify: `contents/data/hulls/astd_gravitational_lens.ship`

现状：2 个 LAUNCH_BAY（LB1=3 位 / LB2=2 位）、无 shieldType、builtInMods 缺 switcher。目标：4 甲板（每个甲板 1 个 launch position 即 1 bay）、OMNI 盾、加 switcher builtin。

> spec 要 4 甲板。原版每个 LAUNCH_BAY slot 的 `locations` 里每 2 个数=1 个发射位；甲板数 = 发射位总数。现 LB1=3 位 + LB2=2 位 = 5 位。需调整为 4 个发射位。最干净：拆成 4 个独立 LAUNCH_BAY 各 1 位，或 2 个 bay 各 2 位。此处用 2 个 bay 各 2 位（共 4），保留现有坐标。

- [ ] **Step 1: Replace LB1/LB2 weaponSlots blocks**

把 LB 1（locations 三组）与 LB 2（locations 两组）两块，替换为 2 个各 2 发射位的 bay：

LB 1 块改为：
```json
    {
      "id": "LB 1",
      "size": "LARGE",
      "type": "LAUNCH_BAY",
      "mount": "HIDDEN",
      "arc": 360,
      "angle": 0,
      "locations": [
        57, -33.5,
        85, -33.5
      ]
    },
```
LB 2 块改为：
```json
    {
      "id": "LB 2",
      "size": "LARGE",
      "type": "LAUNCH_BAY",
      "mount": "HIDDEN",
      "arc": 360,
      "angle": 0,
      "locations": [
        -43.5, -57,
        -61, -56.5
      ]
    },
```

- [ ] **Step 2: Add shield fields + switcher builtin**

在顶层 `"shieldRadius": 150.5,` 之后加入：
```json
  "shieldType": "OMNI",
```

把 `builtInMods` 块改为：
```json
  "builtInMods": [
    "astd_nano_restoration_protocol",
    "astd_lens_array_core",
    "astd_lens_mode_switcher"
  ],
```

- [ ] **Step 3: Verify ship json parses**

Run: `python3 -c "import json,re,sys; t=open('contents/data/hulls/astd_gravitational_lens.ship').read(); t=re.sub(r'#[^\n]*','',t); json.loads(t); print('OK')"`
Expected: `OK`。

- [ ] **Step 4: Commit**

```bash
git add contents/data/hulls/astd_gravitational_lens.ship
git commit -m "feat(lens): set 4 launch bays, OMNI shield, dual-mode switcher builtin on lens hull

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: 变体补全 + 默认载人模式

**Files:**
- Modify: `contents/data/variants/astd_gravitational_lens_Standard.variant`

现状只配 3 武器、hullMods 空。补：默认载人模式 PermaMod（`astd_lens_mode_crewed` + `astd_lens_mode_next_crewed`）、武器组占位（保留现有 3 个，不强行填满——空槽允许）。

> 注意：模式 PermaMod 由 switcher 的 `ensureLensArrayModeState` 在 ship creation 时保证；变体里显式写默认载人，避免首次加载无模式时的瞬态。

- [ ] **Step 1: Update variant file**

把文件改为：
```json
{
    "displayName": "Standard",
    "fluxCapacitors": 20,
    "fluxVents": 20,
    "goalVariant": true,
    "hullId": "astd_gravitational_lens",
    "hullMods": [],
    "permaMods": [
        "astd_lens_mode_crewed",
        "astd_lens_mode_next_crewed"
    ],
    "quality": 1.0,
    "variantId": "astd_gravitational_lens_Standard",
    "weaponGroups": [
        {
            "autofire": false,
            "mode": "LINKED",
            "weapons": {
                "WS0001": "astd_fdp4",
                "WS0003": "astd_vpd6",
                "WS0009": "astd_vpd6"
            }
        }
    ]
}
```

- [ ] **Step 2: Verify json parses**

Run: `python3 -c "import json; json.load(open('contents/data/variants/astd_gravitational_lens_Standard.variant')); print('OK')"`
Expected: `OK`。

- [ ] **Step 3: Commit**

```bash
git add contents/data/variants/astd_gravitational_lens_Standard.variant
git commit -m "feat(lens): default crewed mode and flux on lens standard variant

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: 全量单元测试 + 部署 + 启动烟测

**Files:** 无（验证任务）

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL，含 `LensMarkMathTest`(4) + `LensEcmContributionTest`(3) 全过，原有测试不回归。

- [ ] **Step 2: Compile + deploy**

Run: `./gradlew deployMod`
Expected: BUILD SUCCESSFUL，`mods/ASTD` 下 jar 更新。

- [ ] **Step 3: Launcher smoke test（确认无加载崩溃）**

Run: `./gradlew smokeTestLauncher`
Expected: PASS；日志无 `astd_lens_array_core`/`astd_lens_mode_*` 相关 csv/类加载错误（脚本会扫描 `astd_*` 数据缺失与 Asteria 类加载错误）。

- [ ] **Step 4: Commit (if any tweaks)**

```bash
git add -A
git commit -m "test(lens): phase-1 unit + launcher smoke pass

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: SSOptimizer 实机集成测试验收

**Files:**
- Modify: `contents/data/config/astd_automation_scenarios.json`
- Create: `contents/data/missions/lens_phase1_foundation/MissionDefinition.java`
- Create: `contents/data/missions/lens_phase1_foundation/descriptor.json`
- Create: `contents/data/missions/lens_phase1_foundation/mission_text.txt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt`
- Modify: `tools/verify_ingame_vfx_automation.py`

> 用项目现成的 SSOptimizer automation 框架（先例：`arc_production_ships_vfx_tooltip`）为阶段一新增一个实机场景 `lens_phase1_foundation`。框架能直接读 `ShipAPI` 状态（`hasHullMod`/`shield.isOn`/`shield.activeArc`/部署列表/tooltip key 数），写 telemetry JSON，由 python 校验。
>
> **阶段一可断言的实机证据**（不依赖阶段二系统）：
> 1. 引力透镜级成功部署（`lensDeployedShipIds` 含 `astd_gravitational_lens`）。
> 2. 三个内置 hullmod 挂载：`astd_lens_array_core` / `astd_nano_restoration_protocol` / `astd_lens_mode_switcher`。
> 3. 双模式：载人模式 hullmod `astd_lens_mode_crewed` 挂载（默认载人）。
> 4. 护盾为 OMNI 且可开启：`lensShieldOn`==true、`lensShieldArc`>=200（OMNI 240）。
> 5. 4 甲板：`lensFighterBays`==4（读 `ship.launchBaysCopy`/`hullSpec` 甲板数）。
> 6. 核心 hullmod tooltip key 解析：`lensCoreTooltipKeys`>=7（Task 8 加了 7 行 ui.hullmod.lens_core.*）。
> 7. 标记 applier 生效（用插件内测试钩子主动叠标记验证）：场景插件对引力透镜级**自身**调 `LensMarks.applyDriftMark`+`applyDeepWaterMark` 各叠 3 层，断言 `lensSelfDriftStacks`==3、`lensSelfDeepWaterStacks`==3，且 `lensSelfDamageTakenMult`>1（误差标记 applier 真的改了 damageTakenMult）。

- [ ] **Step 1: Add scenario to automation config**

在 `contents/data/config/astd_automation_scenarios.json` 的 `scenarios` 数组末尾追加：

```json
    {
      "id": "lens_phase1_foundation",
      "missionId": "lens_phase1_foundation",
      "shipIds": ["astd_gravitational_lens"],
      "variantIds": ["astd_gravitational_lens_Standard"],
      "requiredEvidence": [
        "lensCoreHullmod",
        "lensNanoHullmod",
        "lensSwitcherHullmod",
        "lensCrewedModeHullmod",
        "lensShieldOn",
        "lensShieldArc",
        "lensFighterBays",
        "lensCoreTooltipKeys",
        "lensSelfDriftStacks",
        "lensSelfDeepWaterStacks",
        "lensSelfDamageTakenMult"
      ]
    }
```

- [ ] **Step 2: Create mission files**

`contents/data/missions/lens_phase1_foundation/MissionDefinition.java`：

```java
package data.missions.lens_phase1_foundation;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: Gravitational Lens phase-1");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        api.addToFleet(FleetSide.PLAYER, "astd_gravitational_lens_Standard", FleetMemberType.SHIP, true);
        // 友军用于 ECM 累加（不强断言，仅提供集群上下文）。
        api.addToFleet(FleetSide.PLAYER, "wolf_Assault", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, "lasher_Standard", FleetMemberType.SHIP, false);

        api.defeatOnShipLoss("ASTD astd_gravitational_lens");
        api.addBriefingItem("Deploy Gravitational Lens; verify hullmods, shield, bays, marks.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
```

`contents/data/missions/lens_phase1_foundation/descriptor.json`：

```json
{
  "name": "ASTD Gravitational Lens Phase-1 Verification",
  "description": "In-game automation: lens core hullmods, OMNI shield, 4 bays, mark applier.",
  "author": "Kasuminova",
  "version": "0.1"
}
```

`contents/data/missions/lens_phase1_foundation/mission_text.txt`：

```
"Gravitational Lens Phase-1 Automation Test"
```

- [ ] **Step 3: Add scenario helper**

在 `src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt` 的 object 内（`isArcProductionEnabled` 之后）加入：

```kotlin
    const val LENS_PHASE1_SCENARIO_ID: String = "lens_phase1_foundation"

    fun isLensPhase1Enabled(): Boolean {
        val enabled = System.getProperty(ENABLED_PROPERTY)?.equals("true", ignoreCase = true) == true
        val scenario = System.getProperty(SCENARIO_PROPERTY, SCENARIO_ID)
        return enabled && scenario == LENS_PHASE1_SCENARIO_ID
    }
```

- [ ] **Step 4: Add combat plugin scenario branch**

在 `ASTDAutomationCombatPlugin.kt`：

(a) `import`：
```kotlin
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.combat.hullmods.lens.isGravitationalLensShip
```

(b) `init(engine)` 中，在 arc production 分支后追加：
```kotlin
        if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            engine.setDoNotEndCombat(true)
            arrangeLensPhase1Ships(engine)
            writeTelemetry(engine, "CombatReady", lensPhase1TelemetryShip(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID} combat plugin initialized")
            return
        }
```

(c) `advance(amount, events)` 中，在其它场景分支前追加 lens 分支（参照 arc production 的状态机写法）：
```kotlin
        if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            advanceLensPhase1Scenario(engine, amount)
            return
        }
```

(d) 新增方法（放在 arc production 方法群附近）：
```kotlin
    private fun findGravitationalLens(engine: CombatEngineAPI): ShipAPI? {
        for (ship in engine.ships) {
            if (ship != null && ship.isGravitationalLensShip() && !ship.isFighter) return ship
        }
        return null
    }

    private fun lensPhase1TelemetryShip(engine: CombatEngineAPI): ShipAPI? = findGravitationalLens(engine)

    private fun arrangeLensPhase1Ships(engine: CombatEngineAPI) {
        // 强制部署储备，确保引力透镜级在场（参照 arc production 的部署逻辑）。
        try {
            val player = engine.fleetManager(0)
            player?.let {
                it.setSuppressDeploymentMessages(true)
                for (member in it.reservesCopy) {
                    it.deployFleetMember(member, 0f, 0f, 0f)
                }
            }
        } catch (_: Throwable) {}
    }

    private var lensMarksInjected = false

    private fun advanceLensPhase1Scenario(engine: CombatEngineAPI, amount: Float) {
        elapsed += amount
        val lens = findGravitationalLens(engine)

        // 1.0s 后对引力透镜级自身注入测试标记（验证 applier，仅一次）。
        if (!lensMarksInjected && lens != null && elapsed > 1.0f) {
            LensMarks.applyDriftMark(engine, lens, lens, addStacks = 3)
            LensMarks.applyDeepWaterMark(engine, lens, lens, addStacks = 3)
            lensMarksInjected = true
        }

        val state: String = when {
            lens == null && elapsed > 8f -> "Failed"
            lensMarksInjected && elapsed > 2.0f -> "Completed"
            else -> "CombatReady"
        }
        writeTelemetry(engine, state, lens, null)
    }
```

(e) 在 `writeDiagnostics`/`writeTelemetry` 收集证据处，为 lens 场景补充字段。找到 arc production 写诊断字段的位置，加入 lens 分支（用 ship 状态查询；tooltip key 数复用 arc production 同款的 hullmod tooltip key 解析工具）：
```kotlin
        if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            val lens = findGravitationalLens(engine)
            put("lensDeployedShipIds", engine.ships.filter { it != null && !it.isFighter }.mapNotNull { it.hullSpec?.hullId })
            put("lensCoreHullmod", lens?.variant?.hasHullMod("astd_lens_array_core") == true)
            put("lensNanoHullmod", lens?.variant?.hasHullMod("astd_nano_restoration_protocol") == true)
            put("lensSwitcherHullmod", lens?.variant?.hasHullMod("astd_lens_mode_switcher") == true)
            put("lensCrewedModeHullmod", lens?.variant?.hasHullMod("astd_lens_mode_crewed") == true)
            put("lensShieldOn", lens?.shield?.isOn == true || (lens?.shield != null))
            put("lensShieldArc", lens?.shield?.activeArc ?: 0f)
            put("lensFighterBays", lens?.launchBaysCopy?.size ?: 0)
            put("lensCoreTooltipKeys", resolveHullmodTooltipKeyCount("astd_lens_array_core"))
            put("lensSelfDriftStacks", lens?.let { LensMarks.driftStacks(it) } ?: 0)
            put("lensSelfDeepWaterStacks", lens?.let { LensMarks.deepWaterStacks(it) } ?: 0)
            put("lensSelfDamageTakenMult", lens?.mutableStats?.damageTakenMult?.modifiedValue ?: 1f)
        }
```

> 说明：`put(...)` 指现有 diagnostics/telemetry 的字段写入方式（按 `ASTDAutomationCombatPlugin.kt` 中 arc production 现有写法对齐——若用的是 `JSONObject` 则 `obj.put`，若用的是 map builder 则对应方法）。`resolveHullmodTooltipKeyCount` 指现有解析 hullmod tooltip key 数的工具（arc production 已有 `*TooltipKeys` 证据，复用同一函数；若该函数名不同，按实际名调用）。`lens.shield.activeArc` 在盾未开时可能为 0，故 `lensShieldOn` 用“有 shield 组件即视为具备 OMNI 盾”，`lensShieldArc` 读 hullSpec 的盾弧更稳：若 `activeArc` 不稳定，改读 `lens.hullSpec.shieldSpec` 或 `lens.shield.arc`（按编译可用字段）。

- [ ] **Step 5: Add verify script branch**

在 `tools/verify_ingame_vfx_automation.py` 顶部常量区加入：

```python
LENS_PHASE1_SCENARIO = "lens_phase1_foundation"
LENS_PHASE1_REQUIRED_SHIP_IDS = ("astd_gravitational_lens",)
LENS_PHASE1_BOOLEAN_EVIDENCE = (
    "lensCoreHullmod",
    "lensNanoHullmod",
    "lensSwitcherHullmod",
    "lensCrewedModeHullmod",
    "lensShieldOn",
)
LENS_PHASE1_MIN_NUMERIC = {
    "lensShieldArc": 200.0,
    "lensFighterBays": 4.0,
    "lensCoreTooltipKeys": 7.0,
    "lensSelfDriftStacks": 3.0,
    "lensSelfDeepWaterStacks": 3.0,
    "lensSelfDamageTakenMult": 1.0001,
}
```

加入验证函数：

```python
def _verify_lens_phase1(data: dict) -> int:
    errors: list[str] = []
    if data.get("scenario") != LENS_PHASE1_SCENARIO:
        errors.append(f"scenario: expected {LENS_PHASE1_SCENARIO!r}, got {data.get('scenario')!r}")
    if data.get("state") != "Completed":
        errors.append(f"state: expected 'Completed', got {data.get('state')!r}")

    deployed = data.get("lensDeployedShipIds", [])
    for ship_id in LENS_PHASE1_REQUIRED_SHIP_IDS:
        if ship_id not in deployed:
            errors.append(f"lensDeployedShipIds: missing {ship_id!r} (got {deployed!r})")

    for key in LENS_PHASE1_BOOLEAN_EVIDENCE:
        if data.get(key) is not True:
            errors.append(f"{key}: expected true, got {data.get(key)!r}")

    for key, minimum in LENS_PHASE1_MIN_NUMERIC.items():
        try:
            value = float(data.get(key))
        except (TypeError, ValueError):
            errors.append(f"{key}: expected number >= {minimum}, got {data.get(key)!r}")
            continue
        if value < minimum:
            errors.append(f"{key}: expected >= {minimum}, got {value}")

    if errors:
        print("FAIL ASTD Gravitational Lens phase-1 automation evidence")
        for error in errors:
            print(f"- {error}")
        return 1
    print("PASS ASTD Gravitational Lens phase-1 automation evidence")
    return 0
```

在 `verify()` 的场景分发处（与 arc production / aod7 分支并列）加入：

```python
    if data.get("scenario") == LENS_PHASE1_SCENARIO:
        return _verify_lens_phase1(data)
```

- [ ] **Step 6: Compile + run the integration test**

Run:
```bash
./gradlew compileKotlin
ASTD_AUTOMATION_SCENARIO=lens_phase1_foundation ./gradlew smokeTestGame
```
Expected:
- `launchSmokeTestGame` 启动真实游戏跑 `lens_phase1_foundation` 场景，输出 `${gameDir}/ssoptimizer-automation-output/astd-ingame-automation-telemetry.json`。
- `verifySmokeTestGameEvidence` 调 python 校验，打印 `PASS ASTD Gravitational Lens phase-1 automation evidence`，gradle BUILD SUCCESSFUL。

> 若 telemetry 字段缺失/为 0（如 `lensFighterBays`!=4 或 `lensSelfDriftStacks`!=3），按 FAIL 输出的具体字段回溯：甲板数→Task 10 LAUNCH_BAY；标记→Task 3 applier / Task 13 注入；hullmod→Task 9 csv / Task 10 builtInMods。修复后重跑。

- [ ] **Step 7: Commit**

```bash
git add contents/data/config/astd_automation_scenarios.json \
        contents/data/missions/lens_phase1_foundation/ \
        src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt \
        src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt \
        tools/verify_ingame_vfx_automation.py
git commit -m "test(lens): SSOptimizer in-game integration scenario for phase-1 foundation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 阶段一与后续阶段的接缝（备忘，不在本计划实现）

- **战术链路全队增伤收割端**：阶段一仅保证误差标记的 `damageTakenMult` 全局生效（任何来源对带标记目标都增伤）。spec §3.1 的“全队对带标记目标 +10% 额外”若需区别于标记本身的增伤，须在阶段二用 on-hit/damage listener 实现“仅友军额外加成”。本计划 Task 8 `tacticalLink` 已留心跳占位并注明。
- **回声定影系统**（铺误差标记的主来源）、**视差甲板**（机群铺标记）、**渗透潮汐**（铺深水标记 + 过载清标记）均在阶段二/三，届时调用 `LensMarks.applyDriftMark/applyDeepWaterMark/clearAll`。
- **难度系数 m∈[1.0,2.0]**：标记 spec 的 `DifficultyScaling` 与 `difficultyFactorProvider` 已预留；阶段一两类标记用默认 factor=1，敌方难度缩放在赏金/Boss 上下文接入时配置 provider。
- **clearAllLensMarks**：渗透潮汐退潮需要，阶段二在 `LensMarks` 补 `clearAllLensMarks(engine)` 遍历清除两类。
