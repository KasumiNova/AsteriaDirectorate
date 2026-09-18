package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 战机引力联结器数值规格（purple/20-production.md §1）：时流 +100%~200%、减伤 25%~75%
 * 三锚点折算（玩家固定 v2）、软辐能产出 = 基础最大辐能 × 7%/s、软→硬转化的当前软辐能折算。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 ChargeNeedleTuningTest 先例）。
 */
class FighterGravLinkTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): FighterGravLinkTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return FighterGravLinkTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `三锚点精确取档 k_s 1 2 5`() {
        resolveAt(1f).let { v ->
            assertEquals(2.0f, v.timeMult, 1e-6f)
            assertEquals(0.75f, v.damageTakenMult, 1e-6f)
        }
        resolveAt(2f).let { v ->
            assertEquals(2.5f, v.timeMult, 1e-6f)
            assertEquals(0.5f, v.damageTakenMult, 1e-6f)
        }
        resolveAt(5f).let { v ->
            assertEquals(3.0f, v.timeMult, 1e-6f)
            assertEquals(0.25f, v.damageTakenMult, 1e-6f)
        }
    }

    @Test
    fun `玩家固定 v2 与 k_s 无关`() {
        resolveAt(1f, isPlayer = true).let { v ->
            assertEquals(2.5f, v.timeMult, 1e-6f)
            assertEquals(0.5f, v.damageTakenMult, 1e-6f)
        }
        resolveAt(5f, isPlayer = true).let { v ->
            assertEquals(2.5f, v.timeMult, 1e-6f)
            assertEquals(0.5f, v.damageTakenMult, 1e-6f)
        }
    }

    @Test
    fun `软辐能产出速率 飞蓬 16000 基线`() {
        // 飞蓬基础最大辐能 16000 × 7% = 1120 su/s。
        assertEquals(1120f, FighterGravLinkTuning.softFluxPerSecond(16000f), 1e-6f)
        assertEquals(0f, FighterGravLinkTuning.softFluxPerSecond(0f), 1e-6f)
    }

    @Test
    fun `当前软辐能折算`() {
        assertEquals(3000f, FighterGravLinkTuning.softFluxNow(5000f, 2000f), 1e-6f)
        assertEquals(0f, FighterGravLinkTuning.softFluxNow(2000f, 2000f), 1e-6f)
        // 防御浮点抖动：硬辐能恒 ≤ 当前辐能，异常倒挂时下限 0。
        assertEquals(0f, FighterGravLinkTuning.softFluxNow(1999.9f, 2000f), 1e-3f)
    }
}
