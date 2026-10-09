package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 引力电子干扰力场数值规格：原版电子战实际效果复算（[GravEwFieldTuning.penaltyDealt]，含
 * 上限截断、双方对抗折扣与原版取整口径）、附加减益缩放系数（[GravEwFieldTuning.debuffScale]），
 * 以及难度解析链路（经 [DifficultyTuningImpl.installScaleForTests] 走完整映射）。
 */
class GravEwFieldTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    @Test
    fun `无对抗时效果取总额与上限的较小值`() {
        // 总额低于上限：全额生效
        assertEquals(8, GravEwFieldTuning.penaltyDealt(ownTotal = 8f, ownMax = 10f, enemyTotal = 0f))
        // 总额高于上限：按上限截断
        assertEquals(10, GravEwFieldTuning.penaltyDealt(ownTotal = 25f, ownMax = 10f, enemyTotal = 0f))
        // 提升后的上限同样生效（力场增幅语义：上限 10+10）
        assertEquals(20, GravEwFieldTuning.penaltyDealt(ownTotal = 25f, ownMax = 20f, enemyTotal = 0f))
    }

    @Test
    fun `双方对抗时按我方占比折扣并取整`() {
        // 我方 30 / 敌方 10：min(30, 35) × 30/40 = 22.5 → Math.round 半进位 23（与原版口径一致）
        assertEquals(23, GravEwFieldTuning.penaltyDealt(ownTotal = 30f, ownMax = 35f, enemyTotal = 10f))
        // 我方 10 / 敌方 30：10 × 10/40 = 2.5 → 3
        assertEquals(3, GravEwFieldTuning.penaltyDealt(ownTotal = 10f, ownMax = 35f, enemyTotal = 30f))
    }

    @Test
    fun `总额上限先按原版 int 截断再参与结算`() {
        // 30.7 先截断为 30，再 × 30/40 = 22.5 → 23
        assertEquals(23, GravEwFieldTuning.penaltyDealt(ownTotal = 30.7f, ownMax = 35f, enemyTotal = 10f))
        // 上限 20.9 截断为 20：min(25, 20) = 20
        assertEquals(20, GravEwFieldTuning.penaltyDealt(ownTotal = 25f, ownMax = 20.9f, enemyTotal = 0f))
    }

    @Test
    fun `零总额或零上限不产生效果`() {
        assertEquals(0, GravEwFieldTuning.penaltyDealt(ownTotal = 0f, ownMax = 10f, enemyTotal = 5f))
        assertEquals(0, GravEwFieldTuning.penaltyDealt(ownTotal = 10f, ownMax = 0f, enemyTotal = 0f))
    }

    @Test
    fun `减益缩放 实际与最大效果之比 满值为满额`() {
        assertEquals(1f, GravEwFieldTuning.debuffScale(penalty = 20, max = 20f), 1e-6f)
        assertEquals(0.5f, GravEwFieldTuning.debuffScale(penalty = 10, max = 20f), 1e-6f)
        assertEquals(0f, GravEwFieldTuning.debuffScale(penalty = 0, max = 20f), 1e-6f)
        // 最大效果缺失（无指挥官）时归零
        assertEquals(0f, GravEwFieldTuning.debuffScale(penalty = 10, max = 0f), 1e-6f)
        // 实际效果不超过上限时比例不越界
        assertEquals(1f, GravEwFieldTuning.debuffScale(penalty = 25, max = 20f), 1e-6f)
    }

    @Test
    fun `难度解析 敌方随 k_s 单调不减 玩家固定我方档位`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        val v1 = GravEwFieldTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        DifficultyTuningImpl.installScaleForTests(5f)
        val v5 = GravEwFieldTuning.resolve(DifficultyTuningImpl, isPlayer = false)
        assertTrue(v1.maxEffectBonus < v5.maxEffectBonus)

        // 玩家口径与敌方 k_s 无关
        val playerAt1 = GravEwFieldTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        DifficultyTuningImpl.installScaleForTests(5f)
        val playerAt5 = GravEwFieldTuning.resolve(DifficultyTuningImpl, isPlayer = true)
        assertEquals(playerAt1, playerAt5)
    }

    @Test
    fun `安全网句柄筛选 仅命中力场前缀`() {
        val sources = listOf(
            "astd_grav_ew_field:ship_001",
            "astd_grav_ew_field:ship_002",
            "astd_advanced_ecm_network:ship_001",
            "electronic_warfare_penalty",
            "astd_grav_ew_fieldX",
        )
        assertEquals(
            listOf("astd_grav_ew_field:ship_001", "astd_grav_ew_field:ship_002"),
            GravEwFieldTuning.fieldModSources(sources),
        )
        assertTrue(GravEwFieldTuning.fieldModSources(emptyList()).isEmpty())
    }
}
