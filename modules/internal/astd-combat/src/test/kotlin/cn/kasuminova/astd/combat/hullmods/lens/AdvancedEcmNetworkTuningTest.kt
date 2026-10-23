package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvancedEcmNetworkTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun syntheticValues() = AdvancedEcmNetworkTuning.Values(
        selfEcm = 10f,
        allyEcmFrigate = 1f,
        allyEcmDestroyer = 2f,
        allyEcmCruiser = 3f,
        allyEcmCapital = 4f,
        maxEffectBoost = 1f,
        pointMaxBoost = 0.3f,
        captureRateMult = 3f,
    )

    @Test
    fun `友军分档贡献求和 自身加各档数量乘档值`() {
        val values = syntheticValues()
        assertEquals(
            27f,
            AdvancedEcmNetworkTuning.totalOwnEcm(values, frigates = 2, destroyers = 1, cruisers = 3, capitals = 1),
            1e-6f,
        )
    }

    @Test
    fun `无友军时仅自身强度`() {
        val values = syntheticValues()
        assertEquals(
            values.selfEcm,
            AdvancedEcmNetworkTuning.totalOwnEcm(values, 0, 0, 0, 0),
            1e-6f,
        )
    }

    @Test
    fun `友军计数负值按零处理`() {
        val values = syntheticValues()
        assertEquals(
            values.selfEcm,
            AdvancedEcmNetworkTuning.totalOwnEcm(values, -1, -2, -3, -4),
            1e-6f,
        )
    }

    @Test
    fun `敌方贡献上限舰级映射 未知档与护卫舰同档 档位随舰级递增`() {
        assertEquals(
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.FRIGATE),
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.DEFAULT),
        )
        assertEquals(
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.FRIGATE),
            AdvancedEcmNetworkTuning.enemyCapFor(null),
        )
        assertEquals(
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.FRIGATE),
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.FIGHTER),
        )
        assertTrue(
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.FRIGATE) <
                AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.DESTROYER),
        )
        assertTrue(
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.DESTROYER) <
                AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.CRUISER),
        )
        assertTrue(
            AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.CRUISER) <
                AdvancedEcmNetworkTuning.enemyCapFor(ShipAPI.HullSize.CAPITAL_SHIP),
        )
    }

    @Test
    fun `clamp 修正量 超上限截到上限 未超不写入`() {
        val cap = 8f
        val current = 20f
        val delta = AdvancedEcmNetworkTuning.clampDelta(current, cap)
        assertEquals(cap, current + delta, 1e-6f)
        assertEquals(0f, AdvancedEcmNetworkTuning.clampDelta(cap, cap), 1e-6f)
        assertEquals(0f, AdvancedEcmNetworkTuning.clampDelta(3f, cap), 1e-6f)
        assertEquals(0f, AdvancedEcmNetworkTuning.clampDelta(0f, cap), 1e-6f)
    }

    @Test
    fun `指挥官增幅 基础提升加占领点加计`() {
        val values = syntheticValues()
        assertEquals(10f, AdvancedEcmNetworkTuning.commanderBoost(values, 0), 1e-6f)
        assertEquals(16f, AdvancedEcmNetworkTuning.commanderBoost(values, 2), 1e-6f)
        assertEquals(10f, AdvancedEcmNetworkTuning.commanderBoost(values, -3), 1e-6f)
    }

    @Test
    fun `实际效果复算 总额取整 上限截断 对抗折扣 半进位取整`() {
        assertEquals(8, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 8f, ownMax = 10f, enemyTotal = 0f))
        assertEquals(10, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 25f, ownMax = 10f, enemyTotal = 0f))
        assertEquals(20, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 25f, ownMax = 20f, enemyTotal = 0f))
        assertEquals(23, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 30f, ownMax = 35f, enemyTotal = 10f))
        assertEquals(3, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 10f, ownMax = 35f, enemyTotal = 30f))
        assertEquals(23, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 30.7f, ownMax = 35f, enemyTotal = 10f))
        assertEquals(20, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 25f, ownMax = 20.9f, enemyTotal = 0f))
        assertEquals(0, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 0f, ownMax = 10f, enemyTotal = 5f))
        assertEquals(0, AdvancedEcmNetworkTuning.penaltyDealt(ownTotal = 10f, ownMax = 0f, enemyTotal = 0f))
    }

    @Test
    fun `减益幅度 实际效果百分数直接作比例`() {
        assertEquals(0.10f, AdvancedEcmNetworkTuning.debuffMagnitude(10), 1e-6f)
        assertEquals(0.20f, AdvancedEcmNetworkTuning.debuffMagnitude(20), 1e-6f)
        assertEquals(0f, AdvancedEcmNetworkTuning.debuffMagnitude(0), 1e-6f)
    }

    @Test
    fun `残留识别 新旧前缀均命中 无关来源不命中`() {
        val sources = listOf(
            "astd_advanced_ecm_network:ship_a",
            "astd_grav_ew_field:ship_b",
            "astd_grav_em_field:ship_c",
            "other_mod:x",
        )
        assertEquals(
            listOf("astd_advanced_ecm_network:ship_a", "astd_grav_ew_field:ship_b"),
            AdvancedEcmNetworkTuning.fieldModSources(sources),
        )
        assertTrue(AdvancedEcmNetworkTuning.fieldModSources(emptyList()).isEmpty())
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        val v1 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        assertEquals(15f, v1.selfEcm, 1e-6f)
        assertEquals(2f, v1.allyEcmFrigate, 1e-6f)
        assertEquals(4f, v1.allyEcmDestroyer, 1e-6f)
        assertEquals(6f, v1.allyEcmCruiser, 1e-6f)
        assertEquals(8f, v1.allyEcmCapital, 1e-6f)
        assertEquals(0.5f, v1.maxEffectBoost, 1e-6f)
        assertEquals(0.2f, v1.pointMaxBoost, 1e-6f)
        assertEquals(2f, v1.captureRateMult, 1e-6f)

        DifficultyTuningImpl.installScaleForTests(2f)
        val v2 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        assertEquals(20f, v2.selfEcm, 1e-6f)
        assertEquals(4f, v2.allyEcmFrigate, 1e-6f)
        assertEquals(8f, v2.allyEcmDestroyer, 1e-6f)
        assertEquals(12f, v2.allyEcmCruiser, 1e-6f)
        assertEquals(16f, v2.allyEcmCapital, 1e-6f)
        assertEquals(1.0f, v2.maxEffectBoost, 1e-6f)
        assertEquals(0.3f, v2.pointMaxBoost, 1e-6f)
        assertEquals(3f, v2.captureRateMult, 1e-6f)

        DifficultyTuningImpl.installScaleForTests(5f)
        val v5 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        assertEquals(35f, v5.selfEcm, 1e-6f)
        assertEquals(8f, v5.allyEcmFrigate, 1e-6f)
        assertEquals(16f, v5.allyEcmDestroyer, 1e-6f)
        assertEquals(24f, v5.allyEcmCruiser, 1e-6f)
        assertEquals(32f, v5.allyEcmCapital, 1e-6f)
        assertEquals(2.5f, v5.maxEffectBoost, 1e-6f)
        assertEquals(0.6f, v5.pointMaxBoost, 1e-6f)
        assertEquals(6f, v5.captureRateMult, 1e-6f)
    }

    @Test
    fun `难度解析 敌方随 k_s 单调不减 玩家固定我方档位`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        val v1 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        DifficultyTuningImpl.installScaleForTests(5f)
        val v5 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        assertTrue(v1.selfEcm < v5.selfEcm)
        assertTrue(v1.allyEcmFrigate < v5.allyEcmFrigate)
        assertTrue(v1.allyEcmCapital < v5.allyEcmCapital)
        assertTrue(v1.captureRateMult < v5.captureRateMult)

        val playerAt1 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        DifficultyTuningImpl.installScaleForTests(5f)
        val playerAt5 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        assertEquals(playerAt1, playerAt5)
    }
}
