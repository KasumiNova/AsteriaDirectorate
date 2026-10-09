package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 菀星战斗智能数值规格（[ASTDCombatIntelTuning]）：
 * 护盾/装甲单次固定减免的下限边界、精英射程补足 clamp、上浮减伤衰减采样、
 * 相位/护盾/装甲分派、额外装甲减伤乘区与装甲公式的等价性、引力相位失速阈值写入量，
 * 以及难度解析链路（经 [DifficultyTuningImpl.installScaleForTests] 走完整映射）。
 */
class ASTDCombatIntelTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
        DifficultyTuningImpl.installPlayerScaleForTests(null)
    }

    @Test
    fun `难度解析 敌方随轨一系数单调且玩家不受敌方系数影响`() {
        // 敌方 k_s=1 → k_s=5：全部缩放量单调不减且至少一项严格增大（链路真实走映射而非直返锚点）
        DifficultyTuningImpl.installScaleForTests(1f)
        val low = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        DifficultyTuningImpl.installScaleForTests(5f)
        val high = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        val pairs = listOf(
            low.peakCrBonus to high.peakCrBonus,
            low.surfaceArmorFrigate to high.surfaceArmorFrigate,
            low.surfaceArmorDestroyer to high.surfaceArmorDestroyer,
            low.surfaceArmorCruiser to high.surfaceArmorCruiser,
            low.surfaceArmorCapital to high.surfaceArmorCapital,
            low.surfaceArmorDecay to high.surfaceArmorDecay,
            low.shieldFlatReduction to high.shieldFlatReduction,
            low.armorFlatReduction to high.armorFlatReduction,
            low.eliteRangeThreshold to high.eliteRangeThreshold,
            low.eliteRangeBonus to high.eliteRangeBonus,
            low.eliteFluxReduction to high.eliteFluxReduction,
        )
        pairs.forEach { (l, h) -> assertTrue(h > l, "难度上调后数值应严格增大：$l -> $h") }

        // 玩家阵营：按我方档位解析，不随敌方系数变化；我方 2.0 档解析值低于敌方满档
        DifficultyTuningImpl.installScaleForTests(5f)
        DifficultyTuningImpl.installPlayerScaleForTests(2f)
        val player = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        assertTrue(player.peakCrBonus < high.peakCrBonus, "我方档位解析值应低于敌方满档")
        DifficultyTuningImpl.installScaleForTests(1f)
        val playerAtLowEnemy = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        assertEquals(player.peakCrBonus, playerAtLowEnemy.peakCrBonus, 1e-6f)
        assertEquals(player.shieldFlatReduction, playerAtLowEnemy.shieldFlatReduction, 1e-6f)
    }

    @Test
    fun `单次固定减免不使最终伤害低于下限且低伤不被抬升`() {
        // 常规减免：100 - 25 = 75
        assertEquals(75f, ASTDCombatIntelTuning.applyFlatReduction(100f, 25f, 10f), 1e-6f)
        // 触及下限：20 - 25 → 钳到 10（护盾口径）
        assertEquals(10f, ASTDCombatIntelTuning.applyFlatReduction(20f, 25f, 10f), 1e-6f)
        // 装甲口径下限 5：30 - 50 → 钳到 5
        assertEquals(5f, ASTDCombatIntelTuning.applyFlatReduction(30f, 50f, 5f), 1e-6f)
        // 已低于下限的伤害：不受减免影响，也不被抬升
        assertEquals(8f, ASTDCombatIntelTuning.applyFlatReduction(8f, 25f, 10f), 1e-6f)
        assertEquals(3f, ASTDCombatIntelTuning.applyFlatReduction(3f, 50f, 5f), 1e-6f)
        // 恰好等于下限：保持不变
        assertEquals(10f, ASTDCombatIntelTuning.applyFlatReduction(10f, 25f, 10f), 1e-6f)
    }

    @Test
    fun `精英射程补足按阈值与上限双重钳制`() {
        // 距阈值 50：只补 50，最终恰好 700 不越界
        assertEquals(50f, ASTDCombatIntelTuning.eliteRangeFlatBonus(650f, 700f, 100f), 1e-6f)
        // 距阈值 200 超出上限：补到上限 100
        assertEquals(100f, ASTDCombatIntelTuning.eliteRangeFlatBonus(500f, 700f, 100f), 1e-6f)
        // 已达/超过阈值：不补
        assertEquals(0f, ASTDCombatIntelTuning.eliteRangeFlatBonus(700f, 700f, 100f), 1e-6f)
        assertEquals(0f, ASTDCombatIntelTuning.eliteRangeFlatBonus(900f, 700f, 100f), 1e-6f)
        // 低于阈值的任意基础射程补足后不超过阈值
        var base = 0f
        while (base < 800f) {
            val bonus = ASTDCombatIntelTuning.eliteRangeFlatBonus(base, 800f, 200f)
            assertTrue(base + bonus <= 800f + 1e-4f, "base=$base 补足后越界：+ $bonus")
            assertTrue(bonus <= 200f, "base=$base 补足越出上限：$bonus")
            base += 25f
        }
    }

    @Test
    fun `上浮减伤线性衰减采样与窗口外归零`() {
        // initial 1000、窗口 2s：0s 满额 → 1s 半额 → 2s 起归零
        assertEquals(1000f, ASTDCombatIntelTuning.surfaceArmorAt(1000f, 2f, 0f), 1e-6f)
        assertEquals(500f, ASTDCombatIntelTuning.surfaceArmorAt(1000f, 2f, 1f), 1e-6f)
        assertEquals(750f, ASTDCombatIntelTuning.surfaceArmorAt(1000f, 2f, 0.5f), 1e-6f)
        assertEquals(0f, ASTDCombatIntelTuning.surfaceArmorAt(1000f, 2f, 2f), 1e-6f)
        assertEquals(0f, ASTDCombatIntelTuning.surfaceArmorAt(1000f, 2f, 10f), 1e-6f)
        // 非正输入安全归零
        assertEquals(0f, ASTDCombatIntelTuning.surfaceArmorAt(0f, 2f, 0f), 1e-6f)
        assertEquals(0f, ASTDCombatIntelTuning.surfaceArmorAt(1000f, 0f, 0f), 1e-6f)
        assertEquals(0f, ASTDCombatIntelTuning.surfaceArmorAt(1000f, 2f, -1f), 1e-6f)
    }

    @Test
    fun `上浮减伤初始值按舰级递增且非标准档归护卫舰档`() {
        DifficultyTuningImpl.installScaleForTests(2f)
        val values = ASTDCombatIntelTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        val frigate = ASTDCombatIntelTuning.surfaceArmorFor(values, ShipAPI.HullSize.FRIGATE)
        val destroyer = ASTDCombatIntelTuning.surfaceArmorFor(values, ShipAPI.HullSize.DESTROYER)
        val cruiser = ASTDCombatIntelTuning.surfaceArmorFor(values, ShipAPI.HullSize.CRUISER)
        val capital = ASTDCombatIntelTuning.surfaceArmorFor(values, ShipAPI.HullSize.CAPITAL_SHIP)
        assertTrue(frigate < destroyer, "护卫舰档应低于驱逐舰档")
        assertTrue(destroyer < cruiser, "驱逐舰档应低于巡洋舰档")
        assertTrue(cruiser < capital, "巡洋舰档应低于主力舰档")
        assertEquals(frigate, ASTDCombatIntelTuning.surfaceArmorFor(values, ShipAPI.HullSize.FIGHTER), 1e-6f)
        assertEquals(frigate, ASTDCombatIntelTuning.surfaceArmorFor(values, null), 1e-6f)
    }

    @Test
    fun `防御效果分派 相位优先于护盾判定`() {
        assertEquals(
            ASTDCombatIntelTuning.DefenseBranch.PHASE,
            ASTDCombatIntelTuning.defenseBranch(isPhase = true, hasShield = true),
        )
        assertEquals(
            ASTDCombatIntelTuning.DefenseBranch.PHASE,
            ASTDCombatIntelTuning.defenseBranch(isPhase = true, hasShield = false),
        )
        assertEquals(
            ASTDCombatIntelTuning.DefenseBranch.SHIELDED,
            ASTDCombatIntelTuning.defenseBranch(isPhase = false, hasShield = true),
        )
        assertEquals(
            ASTDCombatIntelTuning.DefenseBranch.UNSHIELDED,
            ASTDCombatIntelTuning.defenseBranch(isPhase = false, hasShield = false),
        )
    }

    @Test
    fun `额外装甲减伤乘区等价于按装甲加值结算`() {
        // 无加值：乘区恒 1
        assertEquals(
            1f,
            ASTDCombatIntelTuning.bonusArmorDamageRatio(500f, 500f, 75f, 1f, 0.85f, 0f),
            1e-6f,
        )
        // 具体算例：hs=500、armor=500、bonus=1000 → f(A)=0.5、f(A+B)=0.25，比值 0.5
        val ratio = ASTDCombatIntelTuning.bonusArmorDamageRatio(500f, 500f, 75f, 1f, 0.85f, 1000f)
        assertEquals(0.5f, ratio, 1e-6f)
        // 等价性：base × ratio == boosted（对多组参数成立）
        val cases = listOf(
            listOf(300f, 800f, 75f, 1f, 0.85f, 500f),
            listOf(1000f, 200f, 30f, 1.2f, 0.9f, 2000f),
            listOf(150f, 1200f, 100f, 0.8f, 0.85f, 3500f),
        )
        for (c in cases) {
            val base = ASTDArmorDamageReduction.compute(1f, c[0], c[1], c[2], c[3], c[4]).damageMultiplier
            val boosted = ASTDArmorDamageReduction.compute(1f, c[0], c[1] + c[5], c[2], c[3], c[4]).damageMultiplier
            val r = ASTDCombatIntelTuning.bonusArmorDamageRatio(c[0], c[1], c[2], c[3], c[4], c[5])
            assertEquals(boosted, base * r, 1e-5f, "乘区比值应等价于装甲加值结算：$c")
            assertTrue(r <= 1f, "减伤乘区不得放大伤害：$c")
        }
        // 装甲已达减伤上限时再加值无额外收益（base 触底，比值 1）
        val floored = ASTDCombatIntelTuning.bonusArmorDamageRatio(10f, 100000f, 75f, 1f, 0.85f, 5000f)
        assertEquals(1f, floored, 1e-6f)
    }

    @Test
    fun `引力相位失速阈值写入量使终值命中目标`() {
        // 未装线圈：computeEffective 语义 base × (1 + percent/100)，0.5 × 1.5 = 0.75
        val noCoils = ASTDCombatIntelTuning.fluxThresholdPercent(hasAdaptiveCoils = false)
        assertEquals(
            ASTDCombatIntelTuning.GRAV_THRESHOLD_NO_COILS,
            ASTDCombatIntelTuning.VANILLA_FLUX_THRESHOLD_BASE * (1f + noCoils / 100f),
            1e-5f,
        )
        // 装线圈：线圈自带 +50% 与本技能百分比同区叠加，0.5 × (1 + (30+50)/100) = 0.90
        val withCoils = ASTDCombatIntelTuning.fluxThresholdPercent(hasAdaptiveCoils = true)
        assertEquals(
            ASTDCombatIntelTuning.GRAV_THRESHOLD_WITH_COILS,
            ASTDCombatIntelTuning.VANILLA_FLUX_THRESHOLD_BASE *
                (1f + (withCoils + ASTDCombatIntelTuning.ADAPTIVE_COILS_THRESHOLD_PERCENT) / 100f),
            1e-5f,
        )
    }
}
