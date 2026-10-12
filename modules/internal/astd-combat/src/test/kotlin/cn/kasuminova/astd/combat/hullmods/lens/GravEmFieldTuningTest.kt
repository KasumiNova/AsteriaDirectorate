package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力电子干扰力场数值规格：影响范围 / EMP 抗性降低 / 机动射程削减 / 武器精度削减 /
 * 边缘最小效力的三锚点折算（v1 1250su·25%·10%·20%·10% / v2 1500su·50%·20%·40%·20% /
 * v5 2250su·125%·50%·100%·50%，玩家固定 v2），以及距离衰减曲线
 * （≤半射程满效，边缘线性衰减至难度解析的边缘效力，出范围 0）。
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
        assertEquals(0.20f, v1.accuracyPenalty, 1e-6f)
        assertEquals(0.10f, v1.edgeScale, 1e-6f)

        val v2 = resolveAt(2f)
        assertEquals(1500f, v2.range, 1e-6f)
        assertEquals(0.50f, v2.empResistReduction, 1e-6f)
        assertEquals(0.20f, v2.statPenalty, 1e-6f)
        assertEquals(0.40f, v2.accuracyPenalty, 1e-6f)
        assertEquals(0.20f, v2.edgeScale, 1e-6f)

        val v5 = resolveAt(5f)
        assertEquals(2250f, v5.range, 1e-6f)
        assertEquals(1.25f, v5.empResistReduction, 1e-6f)
        assertEquals(0.50f, v5.statPenalty, 1e-6f)
        assertEquals(1.00f, v5.accuracyPenalty, 1e-6f)
        assertEquals(0.50f, v5.edgeScale, 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        assertEquals(1500f, resolveAt(1f, isPlayer = true).range, 1e-6f)
        assertEquals(1500f, resolveAt(5f, isPlayer = true).range, 1e-6f)
        assertEquals(0.50f, resolveAt(5f, isPlayer = true).empResistReduction, 1e-6f)
        assertEquals(0.40f, resolveAt(1f, isPlayer = true).accuracyPenalty, 1e-6f)
    }

    @Test
    fun `距离衰减曲线 半射程满效 边缘保留难度解析效力 出范围归零`() {
        val range = 1500f
        val edge = 0.2f
        // ≤ 半射程（750su）满效
        assertEquals(1f, GravEmFieldTuning.effectScale(0f, range, edge), 1e-6f)
        assertEquals(1f, GravEmFieldTuning.effectScale(750f, range, edge), 1e-6f)
        // 半射程到边缘中点：1 与 0.2 的中值
        assertEquals(0.6f, GravEmFieldTuning.effectScale(1125f, range, edge), 1e-6f)
        // 边缘取最小效力
        assertEquals(edge, GravEmFieldTuning.effectScale(1500f, range, edge), 1e-6f)
        // 出范围归零
        assertEquals(0f, GravEmFieldTuning.effectScale(1501f, range, edge), 1e-6f)
    }

    @Test
    fun `衰减曲线随难度范围与边缘效力联动 满效半径恒为半射程`() {
        // v1 口径：满效半径 625su，边缘 10%
        assertEquals(1f, GravEmFieldTuning.effectScale(625f, 1250f, 0.1f), 1e-6f)
        assertEquals(0.1f, GravEmFieldTuning.effectScale(1250f, 1250f, 0.1f), 1e-6f)
        // v5 口径：满效半径 1125su，边缘 50%
        assertEquals(1f, GravEmFieldTuning.effectScale(1125f, 2250f, 0.5f), 1e-6f)
        assertEquals(0.5f, GravEmFieldTuning.effectScale(2250f, 2250f, 0.5f), 1e-6f)
    }
}
