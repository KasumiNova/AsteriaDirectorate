package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规格 01 §4.1 用例 1~3：三锚点精确命中、玩家固定 v2、k_s=3 线性插值。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 BountyScalingHullModTest 先例）。
 */
class ChargeNeedleTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): ChargeNeedleTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return ChargeNeedleTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `用例1 三锚点精确命中 k_s 1 2 5`() {
        resolveAt(1f).let { v ->
            assertEquals(0.01f, v.perStack, 1e-6f)
            assertEquals(0.25f, v.dischargeChance, 1e-6f)
            assertEquals(1.00f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(2f).let { v ->
            assertEquals(0.02f, v.perStack, 1e-6f)
            assertEquals(0.40f, v.dischargeChance, 1e-6f)
            assertEquals(1.75f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(5f).let { v ->
            assertEquals(0.05f, v.perStack, 1e-6f)
            assertEquals(1.00f, v.dischargeChance, 1e-6f)
            assertEquals(4.00f, v.dischargeEmpMult, 1e-6f)
        }
    }

    @Test
    fun `用例2 玩家固定 v2 与 k_s 无关`() {
        resolveAt(1f, isPlayer = true).let { v ->
            assertEquals(0.02f, v.perStack, 1e-6f)
            assertEquals(0.40f, v.dischargeChance, 1e-6f)
            assertEquals(1.75f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(5f, isPlayer = true).let { v ->
            assertEquals(0.02f, v.perStack, 1e-6f)
            assertEquals(0.40f, v.dischargeChance, 1e-6f)
            assertEquals(1.75f, v.dischargeEmpMult, 1e-6f)
        }
    }

    @Test
    fun `用例3 k_s 3 线性插值与 ScalingMap LINEAR 直算一致`() {
        val v = resolveAt(3f)
        assertEquals(0.03f, v.perStack, 1e-6f, "k_s=3 LINEAR 插值 perStack 应为 0.03")
        assertEquals(ScalingMap.LINEAR.value(3f, 0.01f, 0.02f, 0.05f), v.perStack, 1e-6f)
        assertEquals(ScalingMap.LINEAR.value(3f, 0.25f, 0.40f, 1.00f), v.dischargeChance, 1e-6f)
        assertEquals(ScalingMap.LINEAR.value(3f, 1.00f, 1.75f, 4.00f), v.dischargeEmpMult, 1e-6f)
    }
}
