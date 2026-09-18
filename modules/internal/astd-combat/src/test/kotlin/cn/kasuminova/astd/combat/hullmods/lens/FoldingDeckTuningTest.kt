package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 维度折叠甲板数值规格（purple/20-production.md §1）：联队规模三锚点折算
 * （v1 +50% / v2 +150% / v5 +250%）、玩家固定 v2、折算下限为基础编制（永不缩编）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 ChargeNeedleTuningTest 先例）。
 */
class FoldingDeckTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun wingSizeMultAt(scale: Float, isPlayer: Boolean = false): Float {
        DifficultyTuningImpl.installScaleForTests(scale)
        return FoldingDeckTuning.resolveWingSizeMult(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        assertEquals(0.5f, wingSizeMultAt(1f), 1e-6f)
        assertEquals(1.5f, wingSizeMultAt(2f), 1e-6f)
        assertEquals(2.5f, wingSizeMultAt(5f), 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        assertEquals(1.5f, wingSizeMultAt(1f, isPlayer = true), 1e-6f)
        assertEquals(1.5f, wingSizeMultAt(5f, isPlayer = true), 1e-6f)
    }

    @Test
    fun `联队规模折算 共轭终端 2 机基线`() {
        // 共轭终端联队 num=2：v1 → 3 机，v2 → 5 机，v5 → 7 机。
        assertEquals(3, FoldingDeckTuning.wingSizeLimit(2, 0.5f))
        assertEquals(5, FoldingDeckTuning.wingSizeLimit(2, 1.5f))
        assertEquals(7, FoldingDeckTuning.wingSizeLimit(2, 2.5f))
    }

    @Test
    fun `联队规模折算下限为基础编制`() {
        assertEquals(2, FoldingDeckTuning.wingSizeLimit(2, 0f))
        assertEquals(2, FoldingDeckTuning.wingSizeLimit(2, -0.4f))
        assertEquals(4, FoldingDeckTuning.wingSizeLimit(4, 0f))
    }

    @Test
    fun `装配点乘区固定 1_5`() {
        assertEquals(1.5f, FoldingDeckTuning.OP_COST_MULT, 1e-6f)
    }
}
