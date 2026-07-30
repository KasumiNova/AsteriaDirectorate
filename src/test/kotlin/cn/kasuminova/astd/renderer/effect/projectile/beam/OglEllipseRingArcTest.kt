package cn.kasuminova.astd.renderer.effect.projectile.beam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 弧段逐顶点 alpha 包络（[arcAlphaEnvelope]）测试：两端归零、峰值为 1、两侧各自单调。
 */
class OglEllipseRingArcTest {

    @Test
    fun `arc alpha envelope is zero at both ends and peaks at peak position`() {
        val peak = 0.65f
        assertEquals(0f, arcAlphaEnvelope(0f, peak), 1e-4f, "弧首必须归零（硬边收尾渐隐）")
        assertEquals(1f, arcAlphaEnvelope(peak, peak), 1e-4f, "峰值位置必须为 1")
        assertEquals(0f, arcAlphaEnvelope(1f, peak), 1e-4f, "弧尾必须归零（硬边收尾渐隐）")
    }

    @Test
    fun `arc alpha envelope is monotonic on both sides of the peak`() {
        val peak = 0.65f
        var prev = 0f
        for (i in 0..65) {
            val t = i / 100f
            val v = arcAlphaEnvelope(t, peak)
            assertTrue(v >= prev - 1e-6f, "[0,peak] 单调不减：t=$t v=$v prev=$prev")
            prev = v
        }
        for (i in 66..100) {
            val t = i / 100f
            val v = arcAlphaEnvelope(t, peak)
            assertTrue(v <= prev + 1e-6f, "[peak,1] 单调不增：t=$t v=$v prev=$prev")
            prev = v
        }
    }

    @Test
    fun `arc alpha envelope tolerates out of domain peak positions`() {
        // 峰值越界时 clamp 到 (0,1) 内部，不产生除零/负包络。
        assertEquals(1f, arcAlphaEnvelope(0.01f, 0f), 1e-4f, "peak=0 按 0.01 处理")
        assertEquals(1f, arcAlphaEnvelope(0.99f, 1f), 1e-4f, "peak=1 按 0.99 处理")
    }
}
