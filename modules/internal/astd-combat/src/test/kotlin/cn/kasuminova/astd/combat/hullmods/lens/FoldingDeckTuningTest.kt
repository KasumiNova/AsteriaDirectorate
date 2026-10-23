package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FoldingDeckTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun wingSizeMultAt(scale: Float, isPlayer: Boolean = false): Float {
        DifficultyTuningImpl.installScaleForTests(scale)
        return FoldingDeckTuning.resolveWingSizeMult(DifficultyTuningImpl, isPlayer)
    }

    private fun refitTimeMultAt(scale: Float, isPlayer: Boolean = false): Float {
        DifficultyTuningImpl.installScaleForTests(scale)
        return FoldingDeckTuning.refitTimeMult(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        assertEquals(1.0f, wingSizeMultAt(1f), 1e-6f)
        assertEquals(1.5f, wingSizeMultAt(2f), 1e-6f)
        assertEquals(3.0f, wingSizeMultAt(5f), 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        assertEquals(1.5f, wingSizeMultAt(1f, isPlayer = true), 1e-6f)
        assertEquals(1.5f, wingSizeMultAt(5f, isPlayer = true), 1e-6f)
    }

    @Test
    fun `联队规模折算 共轭终端 2 机基线`() {
        assertEquals(4, FoldingDeckTuning.wingSizeLimit(2, 1.0f))
        assertEquals(5, FoldingDeckTuning.wingSizeLimit(2, 1.5f))
        assertEquals(8, FoldingDeckTuning.wingSizeLimit(2, 3.0f))
    }

    @Test
    fun `联队规模折算下限为基础编制`() {
        assertEquals(2, FoldingDeckTuning.wingSizeLimit(2, 0f))
        assertEquals(2, FoldingDeckTuning.wingSizeLimit(2, -0.4f))
        assertEquals(4, FoldingDeckTuning.wingSizeLimit(4, 0f))
    }

    @Test
    fun `战机补充速率换算为整备时长乘区`() {
        assertEquals(1f / 1.4f, refitTimeMultAt(2f), 1e-6f)
        assertEquals(1f / 1.2f, refitTimeMultAt(1f), 1e-6f)
        assertEquals(0.5f, refitTimeMultAt(5f), 1e-6f)
        assertEquals(1f / 1.4f, refitTimeMultAt(5f, isPlayer = true), 1e-6f)
    }
}
