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

## Task 12: 全量测试 + 部署冒烟

**Files:** 无（验证任务）

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL，含 `LensMarkMathTest`(4) + `LensEcmContributionTest`(3) 全过，原有测试不回归。

- [ ] **Step 2: Compile + deploy**

Run: `./gradlew deployMod`
Expected: BUILD SUCCESSFUL，`mods/ASTD` 下 jar 更新。

- [ ] **Step 3: Game-launch smoke test (dev mode)**

Run: `./gradlew runSmokeLaunch` （若该 task 存在；否则手动启动游戏到主菜单确认无加载崩溃——重点看 starsector.log 无 `astd_lens_*` 相关 csv/类加载错误）。
Expected: 进主菜单无崩溃；日志无 `astd_lens_array_core`/`astd_lens_mode_*` 加载报错。

- [ ] **Step 4: In-game manual verification checklist（记录结果，不通过则回报）**

逐项确认：
1. refit 引力透镜级：透镜阵列核心、纳米重构、双模式切换器三个内置插件可见。
2. 双模式切换器在 refit 可轮换无人/载人，系统名随之变化（系统本体阶段二做，此处确认模式 tag 切换不报错）。
3. 进战斗：4 个机库可部署舰载机（4 甲板）；护盾为 OMNI。
4. 给透镜旗舰挂上误差/深水标记的临时测试手段（阶段二系统前，可用 devmode 或临时挂 affix）——确认玩家船带标记时左侧状态栏出现“误差标记 N 层 / 深水标记 N 层”。
5. 载人模式下有友军在场时，透镜旗舰 ECM 等级随友军数量提升（看 tooltip/战斗 UI ECM 指示）。

> 第 4 项依赖阶段二系统才能自然铺标记；阶段一允许用临时手段验证状态栏与 applier。如无临时手段，记录“待阶段二系统联调验证”。

- [ ] **Step 5: Final commit (if any tweaks from smoke test)**

```bash
git add -A
git commit -m "fix(lens): phase-1 smoke test adjustments

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 阶段一与后续阶段的接缝（备忘，不在本计划实现）

- **战术链路全队增伤收割端**：阶段一仅保证误差标记的 `damageTakenMult` 全局生效（任何来源对带标记目标都增伤）。spec §3.1 的“全队对带标记目标 +10% 额外”若需区别于标记本身的增伤，须在阶段二用 on-hit/damage listener 实现“仅友军额外加成”。本计划 Task 8 `tacticalLink` 已留心跳占位并注明。
- **回声定影系统**（铺误差标记的主来源）、**视差甲板**（机群铺标记）、**渗透潮汐**（铺深水标记 + 过载清标记）均在阶段二/三，届时调用 `LensMarks.applyDriftMark/applyDeepWaterMark/clearAll`。
- **难度系数 m∈[1.0,2.0]**：标记 spec 的 `DifficultyScaling` 与 `difficultyFactorProvider` 已预留；阶段一两类标记用默认 factor=1，敌方难度缩放在赏金/Boss 上下文接入时配置 provider。
- **clearAllLensMarks**：渗透潮汐退潮需要，阶段二在 `LensMarks` 补 `clearAllLensMarks(engine)` 遍历清除两类。
