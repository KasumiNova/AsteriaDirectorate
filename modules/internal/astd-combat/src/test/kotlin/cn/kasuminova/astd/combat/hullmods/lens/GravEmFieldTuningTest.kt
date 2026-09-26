package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力电磁力场数值规格：影响范围 / EMP 抗性降低 / 机动射程削减与开火辐能增加的三锚点折算
 * （v1 1250su·25%·10% / v2 1500su·50%·20% / v5 2000su·90%·50%，玩家固定 v2），
 * 以及距离衰减曲线（≤半射程满效，边缘线性衰减至 25%，出范围 0）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 GravPhaseDeckTuningTest 先例）。
 */
class GravEmFieldTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): GravEmFieldTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return GravEmFieldTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        val v1 = resolveAt(1f)
        assertEquals(1250f, v1.range, 1e-6f)
        assertEquals(0.25f, v1.empResistReduction, 1e-6f)
        assertEquals(0.10f, v1.statPenalty, 1e-6f)

        val v2 = resolveAt(2f)
        assertEquals(1500f, v2.range, 1e-6f)
        assertEquals(0.50f, v2.empResistReduction, 1e-6f)
        assertEquals(0.20f, v2.statPenalty, 1e-6f)

        val v5 = resolveAt(5f)
        assertEquals(2000f, v5.range, 1e-6f)
        assertEquals(0.90f, v5.empResistReduction, 1e-6f)
        assertEquals(0.50f, v5.statPenalty, 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        assertEquals(1500f, resolveAt(1f, isPlayer = true).range, 1e-6f)
        assertEquals(1500f, resolveAt(5f, isPlayer = true).range, 1e-6f)
        assertEquals(0.50f, resolveAt(5f, isPlayer = true).empResistReduction, 1e-6f)
    }

    @Test
    fun `距离衰减曲线 半射程满效 边缘四分之一 出范围归零`() {
        val range = 1500f
        // ≤ 半射程（750su）满效
        assertEquals(1f, GravEmFieldTuning.effectScale(0f, range), 1e-6f)
        assertEquals(1f, GravEmFieldTuning.effectScale(750f, range), 1e-6f)
        // 半射程到边缘中点：1 与 0.25 的中值
        assertEquals(0.625f, GravEmFieldTuning.effectScale(1125f, range), 1e-6f)
        // 边缘取最小效力 25%
        assertEquals(0.25f, GravEmFieldTuning.effectScale(1500f, range), 1e-6f)
        // 出范围归零
        assertEquals(0f, GravEmFieldTuning.effectScale(1501f, range), 1e-6f)
    }

    @Test
    fun `衰减曲线随难度范围联动 满效半径恒为半射程`() {
        // v1 口径：满效半径 625su
        assertEquals(1f, GravEmFieldTuning.effectScale(625f, 1250f), 1e-6f)
        assertEquals(0.25f, GravEmFieldTuning.effectScale(1250f, 1250f), 1e-6f)
        // v5 口径：满效半径 1000su
        assertEquals(1f, GravEmFieldTuning.effectScale(1000f, 2000f), 1e-6f)
        assertEquals(0.25f, GravEmFieldTuning.effectScale(2000f, 2000f), 1e-6f)
    }
}
