package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力相位甲板数值规格（purple/20-production.md §2）：战机辐能返还比例三锚点折算
 * （v1 50% / v2 60% / v5 90%，玩家固定 v2）、单帧返还量 = 战机辐能净增量 × 比例（下限 0）。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 FighterGravLinkTuningTest 先例）。
 */
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
        assertEquals(0.5f, resolveAt(1f), 1e-6f)
        assertEquals(0.6f, resolveAt(2f), 1e-6f)
        assertEquals(0.9f, resolveAt(5f), 1e-6f)
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        assertEquals(0.6f, resolveAt(1f, isPlayer = true), 1e-6f)
        assertEquals(0.6f, resolveAt(5f, isPlayer = true), 1e-6f)
    }

    @Test
    fun `单帧返还量为净增量乘比例且负增量归零`() {
        // 容差 1e-3：float 乘法存在末位抖动（100f × 0.6f = 60.000004）。
        assertEquals(60f, GravPhaseDeckTuning.returnAmount(100f, 0.6f), 1e-3f)
        assertEquals(0f, GravPhaseDeckTuning.returnAmount(0f, 0.6f), 1e-6f)
        // 净耗散/净下降帧不返还。
        assertEquals(0f, GravPhaseDeckTuning.returnAmount(-50f, 0.6f), 1e-6f)
    }
}
