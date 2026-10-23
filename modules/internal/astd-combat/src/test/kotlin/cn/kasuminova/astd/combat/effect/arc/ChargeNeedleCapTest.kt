package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargeNeedleCapTest {

    @Test
    fun `用例4 总和不超上限原样放行`() {
        assertEquals(1f, ChargeNeedleTuning.dissipationCapFactor(800f, 72f, 27f), 1e-6f)
    }

    @Test
    fun `用例5 超上限两项按比例同步压缩`() {
        assertEquals(0.5f, ChargeNeedleTuning.dissipationCapFactor(800f, 2400f, 800f), 1e-6f)
    }

    @Test
    fun `用例6 两项之和为零无产出无需压缩`() {
        assertEquals(1f, ChargeNeedleTuning.dissipationCapFactor(800f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `用例7 耗散为 0 异常态产出整体压没不静默恒零`() {
        assertEquals(0f, ChargeNeedleTuning.dissipationCapFactor(0f, 72f, 27f), 1e-6f)
    }

    @Test
    fun `用例8 恰等上限边界含端`() {
        assertEquals(1f, ChargeNeedleTuning.dissipationCapFactor(800f, 1200f, 400f), 1e-6f)
    }

    @Test
    fun `用例9 负产出非法输入按无产出放行`() {
        assertEquals(1f, ChargeNeedleTuning.dissipationCapFactor(800f, -10f, 0f), 1e-6f)
    }
}
