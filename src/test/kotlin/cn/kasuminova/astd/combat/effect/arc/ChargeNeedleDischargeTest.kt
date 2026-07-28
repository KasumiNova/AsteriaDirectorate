package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.impl.combat.CombatRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规格 01 §4.1 用例 13：泄放判定边界（[ChargeNeedleTuning.shouldDischarge] 严格小于口径）。
 * roll 由固定 seed 的 [CombatRandom] 序列喂入（共享基建件，此处只断言映射，不重复测基建）。
 */
class ChargeNeedleDischargeTest {

    @Test
    fun `用例13 泄放判定边界 严格小于口径`() {
        assertFalse(ChargeNeedleTuning.shouldDischarge(0f, 0f), "chance=0 恒 false")
        assertFalse(ChargeNeedleTuning.shouldDischarge(0.999f, 0f), "chance=0 恒 false")
        assertTrue(ChargeNeedleTuning.shouldDischarge(0.999f, 1f), "chance=1 时 roll=0.999 触发")
        assertFalse(ChargeNeedleTuning.shouldDischarge(0.4f, 0.4f), "roll == chance 边界不触发（< 口径）")
        assertTrue(ChargeNeedleTuning.shouldDischarge(0.399f, 0.4f), "roll < chance 触发")

        // 固定 seed 的确定性序列喂入：roll ∈ [0, 1)，chance=1 全触发、chance=0 全不触发；同 callIndex 恒同值。
        val seed = CombatRandom.seedOf("astd_test_ship", "WS001")
        val rolls = (0 until 8).map { CombatRandom.nextFloatIn(seed, it, 0f..1f) }
        rolls.forEachIndexed { index, roll ->
            assertTrue(roll >= 0f && roll < 1f, "roll[$index] 应在 [0, 1)：$roll")
            assertTrue(ChargeNeedleTuning.shouldDischarge(roll, 1f))
            assertFalse(ChargeNeedleTuning.shouldDischarge(roll, 0f))
            assertEquals(roll, CombatRandom.nextFloatIn(seed, index, 0f..1f), 1e-7f, "同 (seed, callIndex) 恒同值")
        }
    }
}
