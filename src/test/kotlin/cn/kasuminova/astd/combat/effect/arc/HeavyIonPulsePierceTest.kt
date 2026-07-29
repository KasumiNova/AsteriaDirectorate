package cn.kasuminova.astd.combat.effect.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 规格 02 §4.1 用例 4~8：纯函数 [HeavyIonPulseTuning.empPierceExtra] 贯穿补伤三档
 * （<0.1 / =0.1 / >0.1，90 计划 §2.5 指定）与 mult=0 防线。
 */
class HeavyIonPulsePierceTest {

    @Test
    fun `用例4 mult 小于 0_1 按比例补伤`() {
        // 750 × (0.1 − 0.05) / 0.1 = 375
        assertEquals(375f, HeavyIonPulseTuning.empPierceExtra(750f, 0.05f), 1e-6f)
    }

    @Test
    fun `用例5 mult 恰等于 0_1 边界不补`() {
        assertEquals(0f, HeavyIonPulseTuning.empPierceExtra(750f, 0.1f), 1e-6f)
    }

    @Test
    fun `用例6 mult 大于 0_1 不补`() {
        assertEquals(0f, HeavyIonPulseTuning.empPierceExtra(750f, 0.6f), 1e-6f)
        assertEquals(0f, HeavyIonPulseTuning.empPierceExtra(750f, 1.0f), 1e-6f)
    }

    @Test
    fun `用例7 mult 等于 0 公式自然退化整发等值 无除零不静默恒零`() {
        assertEquals(750f, HeavyIonPulseTuning.empPierceExtra(750f, 0f), 1e-6f)
    }

    @Test
    fun `用例8 mult 无限接近 0_1 下方 补伤趋近于零但为正`() {
        val extra = HeavyIonPulseTuning.empPierceExtra(750f, 0.099f)
        assertTrue(extra > 0f, "0.099 应产出正补伤")
        assertEquals(750f * 0.01f, extra, 1e-4f, "0.099 应约等于 emp × 0.01")
    }
}
