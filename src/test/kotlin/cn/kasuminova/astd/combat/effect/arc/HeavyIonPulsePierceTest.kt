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

    // A9 裁定方案 a（2026-07-29）：empPierceApplied 折算补偿链路——
    // 施加量经引擎 empDamageTakenMult 二次乘算后必须回补到 extra（显示值 = 实际结算量）。

    @Test
    fun `用例9 折算补偿 mult 正常区间 引擎二次乘算精确回补 extra`() {
        val extra = HeavyIonPulseTuning.empPierceExtra(750f, 0.05f) // = 375
        val applied = HeavyIonPulseTuning.empPierceApplied(extra, 0.05f)
        assertEquals(7500f, applied, 1e-3f, "375 / 0.05 = 7500")
        assertEquals(extra, applied * 0.05f, 1e-3f, "引擎再乘 mult 后实际结算必须等于 extra")
    }

    @Test
    fun `用例10 折算补偿 mult 低于下限 按 0_01 折算防爆炸 少量欠补属钳制`() {
        val extra = HeavyIonPulseTuning.empPierceExtra(750f, 0.005f) // = 712.5
        val applied = HeavyIonPulseTuning.empPierceApplied(extra, 0.005f)
        assertEquals(extra / 0.01f, applied, 1e-2f, "mult < 0.01 按 0.01 折算")
        assertEquals(extra * 0.5f, applied * 0.005f, 1e-2f, "欠补一半属防爆炸钳制的预期行为")
    }

    @Test
    fun `用例11 mult 等于 0 完全免疫 applied 为 0 整体跳过不弹假浮字`() {
        val extra = HeavyIonPulseTuning.empPierceExtra(750f, 0f) // = 750（公式自然退化）
        assertEquals(0f, HeavyIonPulseTuning.empPierceApplied(extra, 0f), "0 乘区补偿无意义，返回 0 由调用侧跳过")
    }

    @Test
    fun `用例12 extra 为 0 时 applied 恒 0 不产生施加与浮字`() {
        assertEquals(0f, HeavyIonPulseTuning.empPierceApplied(0f, 0.05f))
        assertEquals(0f, HeavyIonPulseTuning.empPierceApplied(0f, 0.6f))
    }
}
