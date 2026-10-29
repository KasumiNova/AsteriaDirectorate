package cn.kasuminova.astd.combat.automation.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseMotionEvidenceTest {
    @Test
    fun `持续绕圈会被真实轨迹识别`() {
        val evidence = PhaseMotionEvidence()
        for (second in 0..20) {
            val angle = second * (Math.PI * 2.0 / 12.0)
            evidence.sample(
                second.toFloat(),
                kotlin.math.cos(angle).toFloat() * 100f,
                kotlin.math.sin(angle).toFloat() * 100f,
            )
        }

        assertTrue(evidence.circling)
        assertTrue(evidence.windowPath >= 600f)
        assertTrue(evidence.windowDisplacement <= 150f)
    }

    @Test
    fun `直线推进与窗口外旧轨迹不会误判绕圈`() {
        val evidence = PhaseMotionEvidence()
        for (second in 0..20) {
            assertFalse(evidence.sample(second.toFloat(), second * 100f, 0f))
        }

        assertEquals(1500f, evidence.windowPath, 0.01f)
        assertEquals(1500f, evidence.windowDisplacement, 0.01f)
    }

    @Test
    fun `窗口路径只统计窗口内的线段`() {
        val evidence = PhaseMotionEvidence(windowSec = 2f)
        evidence.sample(0f, 0f, 0f)
        evidence.sample(1f, 100f, 0f)
        evidence.sample(2f, 200f, 0f)
        evidence.sample(3f, 300f, 0f)

        assertEquals(200f, evidence.windowPath, 0.01f)
        assertEquals(200f, evidence.windowDisplacement, 0.01f)
    }
}
