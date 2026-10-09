package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 先进电子对抗网络数值规格：自身 + 友军分档贡献求和（[AdvancedEcmNetworkTuning.totalOwnEcm]）、
 * 敌方每舰贡献上限的舰级映射与 clamp 修正量（[AdvancedEcmNetworkTuning.enemyCapFor] /
 * [AdvancedEcmNetworkTuning.clampDelta]），以及难度解析链路（经
 * [DifficultyTuningImpl.installScaleForTests] 走完整映射）。
 */
class AdvancedEcmNetworkTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    /** 合成一组互不相同的分档值，验证求和的组合逻辑而非具体数值。 */
    private fun syntheticValues() = AdvancedEcmNetworkTuning.Values(
        selfEcm = 10f,
        allyEcmFrigate = 1f,
        allyEcmDestroyer = 2f,
        allyEcmCruiser = 3f,
        allyEcmCapital = 4f,
    )

    @Test
    fun `友军分档贡献求和 自身加各档数量乘档值`() {
        val values = syntheticValues()
        // 2 护卫 + 1 驱逐 + 3 巡洋 + 1 主力：10 + 2×1 + 1×2 + 3×3 + 1×4 = 27
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
        // 映射等价：非标准档位（DEFAULT / null / 战机）回落到护卫舰档
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
        // 设计性质：上限随舰级严格递增
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
        // 超限：修正量恰好把当前值拉回上限
        val current = 20f
        val delta = AdvancedEcmNetworkTuning.clampDelta(current, cap)
        assertEquals(cap, current + delta, 1e-6f)
        // 等于/低于上限：不写入
        assertEquals(0f, AdvancedEcmNetworkTuning.clampDelta(cap, cap), 1e-6f)
        assertEquals(0f, AdvancedEcmNetworkTuning.clampDelta(3f, cap), 1e-6f)
        assertEquals(0f, AdvancedEcmNetworkTuning.clampDelta(0f, cap), 1e-6f)
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

        // 玩家口径与敌方 k_s 无关
        val playerAt1 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        DifficultyTuningImpl.installScaleForTests(5f)
        val playerAt5 = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        assertEquals(playerAt1, playerAt5)
    }
}
