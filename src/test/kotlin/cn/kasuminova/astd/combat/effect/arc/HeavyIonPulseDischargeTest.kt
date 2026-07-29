package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.impl.combat.CombatRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规格 02 §4.1 用例 9：泄放判定边界（[HeavyIonPulseTuning.shouldDischarge] 严格小于口径）。
 * roll 由固定 seed 的 [CombatRandom] 序列喂入（共享基建件，此处只断言映射，不重复测基建）。
 */
class HeavyIonPulseDischargeTest {

    @Test
    fun `用例9 泄放判定边界 严格小于口径`() {
        assertFalse(HeavyIonPulseTuning.shouldDischarge(0f, 0f), "chance=0 恒 false")
        assertFalse(HeavyIonPulseTuning.shouldDischarge(0.999f, 0f), "chance=0 恒 false")
        assertTrue(HeavyIonPulseTuning.shouldDischarge(0.499f, 0.5f), "chance=0.5 时 roll=0.499 触发")
        assertFalse(HeavyIonPulseTuning.shouldDischarge(0.5f, 0.5f), "roll == chance 边界不触发（< 口径）")

        // 固定 seed 的确定性序列喂入：roll ∈ [0, 1)，chance=1 全触发、chance=0 全不触发；同 callIndex 恒同值。
        val seed = CombatRandom.seedOf("astd_test_ship", "WS 003")
        val rolls = (0 until 8).map { CombatRandom.nextFloatIn(seed, it, 0f..1f) }
        rolls.forEachIndexed { index, roll ->
            assertTrue(roll >= 0f && roll < 1f, "roll[$index] 应在 [0, 1)：$roll")
            assertTrue(HeavyIonPulseTuning.shouldDischarge(roll, 1f))
            assertFalse(HeavyIonPulseTuning.shouldDischarge(roll, 0f))
            assertEquals(roll, CombatRandom.nextFloatIn(seed, index, 0f..1f), 1e-7f, "同 (seed, callIndex) 恒同值")
        }
    }
}
