package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GravPhaseDeckTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): Float {
        DifficultyTuningImpl.installScaleForTests(scale)
        return GravPhaseDeckTuning.resolveReturnRatio(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        assertEquals(0.4f, resolveAt(1f), 1e-6f)
        assertEquals(0.5f, resolveAt(2f), 1e-6f)
        assertEquals(0.8f, resolveAt(5f), 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        assertEquals(0.5f, resolveAt(1f, isPlayer = true), 1e-6f)
        assertEquals(0.5f, resolveAt(5f, isPlayer = true), 1e-6f)
    }

    @Test
    fun `单帧返还量为净增量乘比例且负增量归零`() {
        assertEquals(50f, GravPhaseDeckTuning.returnAmount(100f, 0.5f), 1e-3f)
        assertEquals(0f, GravPhaseDeckTuning.returnAmount(0f, 0.5f), 1e-6f)
        assertEquals(0f, GravPhaseDeckTuning.returnAmount(-50f, 0.5f), 1e-6f)
    }
}
