package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GravityCollapsePhaseBeamBlockerTest {

    @Test
    fun `正向命中圆——返回入射点距离`() {
        val t = GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(
            0f, 0f, 100f, 0f, 100f, 50f, 0f, 10f,
        )
        assertEquals(40f, t!!, 1e-3f)
    }

    @Test
    fun `偏离圆——不相交返回 null`() {
        val t = GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(
            0f, 0f, 100f, 0f, 100f, 50f, 50f, 10f,
        )
        assertNull(t)
    }

    @Test
    fun `圆在线段末端之外——返回 null`() {
        val t = GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(
            0f, 0f, 100f, 0f, 100f, 200f, 0f, 10f,
        )
        assertNull(t)
    }

    @Test
    fun `圆在起点后方——返回 null`() {
        val t = GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(
            0f, 0f, 100f, 0f, 100f, -50f, 0f, 10f,
        )
        assertNull(t)
    }

    @Test
    fun `起点在圆内——立即遮挡返回 0`() {
        val t = GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(
            0f, 0f, 100f, 0f, 100f, 5f, 0f, 10f,
        )
        assertEquals(0f, t!!, 1e-3f)
    }

    @Test
    fun `斜向命中——入射距离按几何关系计算`() {
        val expected = (40.0 * Math.sqrt(2.0)).toFloat()
        val len = (100.0 * Math.sqrt(2.0)).toFloat()
        val t = GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(
            0f, 0f, 100f, 100f, len, 50f, 50f, (10.0 * Math.sqrt(2.0)).toFloat(),
        )
        assertEquals(expected, t!!, 1e-2f)
    }

    @Test
    fun `退化输入——零长线段或零半径返回 null`() {
        assertNull(
            GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(0f, 0f, 0f, 0f, 0f, 0f, 0f, 10f)
        )
        assertNull(
            GravityCollapsePhaseBeamBlocker.segmentCircleEnterDistance(0f, 0f, 100f, 0f, 100f, 50f, 0f, 0f)
        )
    }
}
