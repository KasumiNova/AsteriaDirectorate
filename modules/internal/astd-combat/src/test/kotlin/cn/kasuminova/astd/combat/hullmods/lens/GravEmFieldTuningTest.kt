package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals(1f, GravEmFieldTuning.effectScale(0f, range, edge), 1e-6f)
        assertEquals(1f, GravEmFieldTuning.effectScale(750f, range, edge), 1e-6f)
        assertEquals(0.6f, GravEmFieldTuning.effectScale(1125f, range, edge), 1e-6f)
        assertEquals(edge, GravEmFieldTuning.effectScale(1500f, range, edge), 1e-6f)
        assertEquals(0f, GravEmFieldTuning.effectScale(1501f, range, edge), 1e-6f)
    }

    @Test
    fun `衰减曲线随难度范围与边缘效力联动 满效半径恒为半射程`() {
        assertEquals(1f, GravEmFieldTuning.effectScale(625f, 1250f, 0.1f), 1e-6f)
        assertEquals(0.1f, GravEmFieldTuning.effectScale(1250f, 1250f, 0.1f), 1e-6f)
        assertEquals(1f, GravEmFieldTuning.effectScale(1125f, 2250f, 0.5f), 1e-6f)
        assertEquals(0.5f, GravEmFieldTuning.effectScale(2250f, 2250f, 0.5f), 1e-6f)
    }
}
