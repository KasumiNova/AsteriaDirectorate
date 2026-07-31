package cn.kasuminova.astd.combat.effect.arc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规格 01 §4.1 用例 4~9：耗散安全闸纯函数 [ChargeNeedleTuning.dissipationCapStacks] 全分支。
 * 0 值分支只断言返回值语义；WARN/ERROR 日志路径由调用侧（ChargeNeedleStacks）承担。
 */
class ChargeNeedleCapTest {

    @Test
    fun `用例4 常规闸 800 耗散 400 维持 2 每点`() {
        // floor(0.5 × 800 / (400 × 0.02)) = floor(50) = 50
        assertEquals(50, ChargeNeedleTuning.dissipationCapStacks(800f, 400f, 0.02f))
    }

    @Test
    fun `用例5 高耗散输入 clamp 到 200 层`() {
        assertEquals(200, ChargeNeedleTuning.dissipationCapStacks(100000f, 100f, 0.01f))
    }

    @Test
    fun `用例6 基础维持为 0 闸豁免返回 200`() {
        assertEquals(200, ChargeNeedleTuning.dissipationCapStacks(800f, 0f, 0.02f))
    }

    @Test
    fun `用例7 耗散为 0 异常收紧返回 0 不静默恒零`() {
        assertEquals(0, ChargeNeedleTuning.dissipationCapStacks(0f, 400f, 0.02f))
    }

    @Test
    fun `用例8 perStack 为 0 配置错误防御返回 200`() {
        assertEquals(200, ChargeNeedleTuning.dissipationCapStacks(800f, 400f, 0f))
    }

    @Test
    fun `用例9 恰整除边界 floor 含端`() {
        // floor(0.5 × 800 / (400 × 0.025)) = floor(40) = 40
        assertEquals(40, ChargeNeedleTuning.dissipationCapStacks(800f, 400f, 0.025f))
    }
}
