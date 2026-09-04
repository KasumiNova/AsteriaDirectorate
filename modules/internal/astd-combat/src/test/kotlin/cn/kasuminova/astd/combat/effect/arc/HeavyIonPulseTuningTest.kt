package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规格 02 §4.1 用例 1~3：三锚点精确命中、玩家固定 v2、k_s=3 线性插值；贯穿激活条件钉死。
 * 经 [DifficultyTuningImpl.installScaleForTests] 走完整映射链路（对齐 BountyScalingHullModTest 先例）。
 */
class HeavyIonPulseTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun resolveAt(scale: Float, isPlayer: Boolean = false): HeavyIonPulseTuning.Values {
        DifficultyTuningImpl.installScaleForTests(scale)
        return HeavyIonPulseTuning.resolve(DifficultyTuningImpl, isPlayer)
    }

    @Test
    fun `用例1 三锚点精确命中 k_s 1 2 5`() {
        resolveAt(1f).let { v ->
            assertEquals(0.25f, v.dischargeChance, 1e-6f)
            assertEquals(1.00f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(2f).let { v ->
            assertEquals(0.3125f, v.dischargeChance, 1e-6f)
            assertEquals(1.25f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(5f).let { v ->
            assertEquals(0.50f, v.dischargeChance, 1e-6f)
            assertEquals(2.00f, v.dischargeEmpMult, 1e-6f)
        }
    }

    @Test
    fun `用例2 玩家固定 v2 与 k_s 无关`() {
        resolveAt(1f, isPlayer = true).let { v ->
            assertEquals(0.3125f, v.dischargeChance, 1e-6f)
            assertEquals(1.25f, v.dischargeEmpMult, 1e-6f)
        }
        resolveAt(5f, isPlayer = true).let { v ->
            assertEquals(0.3125f, v.dischargeChance, 1e-6f)
            assertEquals(1.25f, v.dischargeEmpMult, 1e-6f)
        }
    }

    @Test
    fun `用例3 k_s 3 线性插值与 ScalingMap LINEAR 直算一致`() {
        val v = resolveAt(3f)
        assertEquals(0.375f, v.dischargeChance, 1e-6f, "k_s=3 LINEAR 插值 chance 应为 0.375")
        assertEquals(ScalingMap.LINEAR.value(3f, 0.25f, 0.3125f, 0.50f), v.dischargeChance, 1e-6f)
        assertEquals(ScalingMap.LINEAR.value(3f, 1.00f, 1.25f, 2.00f), v.dischargeEmpMult, 1e-6f)
    }

    @Test
    fun `贯穿激活条件 破晓敌版限定 玩家恒排除`() {
        assertFalse(HeavyIonPulseTuning.pierceActive(isPlayer = false, fixedScale = 1f), "v1 无贯穿")
        assertFalse(HeavyIonPulseTuning.pierceActive(isPlayer = false, fixedScale = 2f), "v2 无贯穿")
        assertTrue(HeavyIonPulseTuning.pierceActive(isPlayer = false, fixedScale = 5f), "v5 敌版解锁")
        assertFalse(HeavyIonPulseTuning.pierceActive(isPlayer = true, fixedScale = 5f), "玩家 k_s=5 仍排除")
        assertFalse(HeavyIonPulseTuning.pierceActive(isPlayer = true, fixedScale = 1f), "玩家恒排除")
    }
}
