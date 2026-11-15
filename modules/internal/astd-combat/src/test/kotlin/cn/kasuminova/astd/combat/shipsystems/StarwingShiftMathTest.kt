package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarwingShiftMathTest {
    @Test
    fun `折跃终点距离保留斜向运动分量`() {
        assertEquals(1000f, StarwingShiftMath.distanceAfterShift(1400f, 400f, 1f), 1e-3f)
        assertEquals(1248.9996f, StarwingShiftMath.distanceAfterShift(1400f, 400f, 0.5f), 1e-3f)
        assertEquals(1800f, StarwingShiftMath.distanceAfterShift(1400f, 400f, -1f), 1e-3f)
        assertEquals(0f, StarwingShiftMath.distanceAfterShift(400f, 400f, 1f), 1e-3f)
    }
    @Test
    fun `位移曲线钳制端点且关于中点对称`() {
        assertEquals(0f, StarwingShiftMath.easeInOutExpo(-0.2f))
        assertEquals(1f, StarwingShiftMath.easeInOutExpo(1.2f))
        assertEquals(0.5f, StarwingShiftMath.easeInOutExpo(0.5f))
        for (i in 1..99) {
            val progress = i / 100f
            assertEquals(1f, StarwingShiftMath.easeInOutExpo(progress) + StarwingShiftMath.easeInOutExpo(1f - progress), 1e-5f)
            assertTrue(StarwingShiftMath.easeInOutExpo(progress) >= StarwingShiftMath.easeInOutExpo(progress - 0.01f))
        }
    }

    @Test
    fun `径向冲击能推开迎面目标并随距离衰减`() {
        val center = StarwingShiftMath.radialImpulse(400f, 400f, 0f, 1f)
        val edge = StarwingShiftMath.radialImpulse(400f, 400f, 0f, 0.5f)
        assertEquals(center * 0.5f, edge, 1e-5f)
        val approaching = StarwingShiftMath.radialImpulse(400f, 400f, -300f, 1f)
        assertEquals(center, approaching - 300f, 1e-5f)
        assertEquals(0f, StarwingShiftMath.radialImpulse(400f, 400f, -300f, 0f))
    }

    @Test
    fun `轻目标获得更高速度且零质量射弹结果有限`() {
        val heavy = StarwingShiftMath.radialImpulse(400f, 1600f, 0f, 1f)
        val light = StarwingShiftMath.radialImpulse(400f, 100f, 0f, 1f)
        assertTrue(light > heavy)
        assertTrue(StarwingShiftMath.radialImpulse(400f, 0f, 0f, 1f).isFinite())
    }
}
