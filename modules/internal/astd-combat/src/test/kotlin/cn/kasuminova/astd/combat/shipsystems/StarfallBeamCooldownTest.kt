package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarfallBeamCooldownTest {
    @Test
    fun `中途激活按已经等待的秒数扣减`() {
        val late = StarfallBeamWindow(original = 8f, burstLength = 0.4f, skipIdle = false)
        assertFalse(late.advance(0.02f, burstLeft = 0.4f, nativeLeft = 4f, emitting = false))
        assertEquals(0f, late.remaining)

        val early = StarfallBeamWindow(original = 8f, burstLength = 0.4f, skipIdle = false)
        assertTrue(early.advance(0.02f, burstLeft = 0.4f, nativeLeft = 7.7f, emitting = false))
        assertEquals(1.7f, early.remaining, 0.00001f)
        assertTrue(early.advance(0.2f, burstLeft = 0.4f, nativeLeft = 1.5f, emitting = false))
        assertEquals(1.5f, early.remaining, 0.00001f)
    }

    @Test
    fun `前一帧跨过束段末尾时只把溢出时间算进冷却`() {
        for (length in listOf(0.06f, 1.5f)) {
            val window = StarfallBeamWindow(original = 9f, burstLength = length, skipIdle = false)
            assertFalse(window.advance(0.03f, burstLeft = 0.025f, nativeLeft = 0f, emitting = true))
            assertTrue(window.advance(0.05f, burstLeft = length, nativeLeft = 8.925f, emitting = true))
            assertEquals(2.225f, window.remaining, 0.00001f)
            assertFalse(window.advance(2.23f, burstLeft = length, nativeLeft = 0f, emitting = false))
        }
    }

    @Test
    fun `零间隔束同帧重启时借助亮度区分新束段和空闲`() {
        val window = StarfallBeamWindow(original = 0f, burstLength = 0.06f, skipIdle = true)
        assertFalse(window.advance(0.02f, 0.06f, 0f, false))
        assertTrue(window.advance(0.02f, 0.06f, 0f, true))
        assertTrue(window.advance(0.04f, 0.02f, 0f, true))
        assertTrue(window.advance(0.03f, 0.06f, 0f, false))
        assertEquals(0.49f, window.remaining, 0.00001f)
        assertTrue(window.advance(0.48f, 0.06f, 0f, false))
        assertFalse(window.advance(0.011f, 0.06f, 0f, false))
    }

    @Test
    fun `一帧已结束的点射只在实际扣弹时开始等待`() {
        for (seconds in listOf(0.05f, 0.1f)) {
            val window = StarfallBeamWindow(original = 0f, burstLength = 0.02f, skipIdle = false)
            assertFalse(window.advance(seconds, 0.02f, 0f, false))
            assertTrue(window.advance(seconds, 0.02f, 0f, false, ammoSpent = true))
            assertEquals(0.5f - (seconds - 0.02f), window.remaining, 0.00001f)
            assertFalse(window.advance(0.5f, 0.02f, 0f, false))
        }
    }

    @Test
    fun `射速改变时按有效目标舰秒扣除已经等待的时间`() {
        val window = StarfallBeamWindow(original = 8f, burstLength = 0.2f, skipIdle = false)
        assertTrue(window.advance(0.01f, 0.2f, 7.6f, false, rate = 0.5f))
        assertEquals(3.2f, window.remaining, 0.00001f)
        assertTrue(window.advance(0.01f, 0.2f, 1.59f, false, rate = 2f))
        assertEquals(0.19f, window.remaining, 0.00001f)
        assertFalse(window.advance(0.2f, 0.2f, 0f, false, rate = 2f))

        val fast = StarfallBeamWindow(original = 8f, burstLength = 0.2f, skipIdle = false)
        assertTrue(fast.advance(0.01f, 0.2f, 7.6f, false, rate = 2f))
        assertEquals(0.8f, fast.remaining, 0.00001f)
    }

    @Test
    fun `上一束渐灭期间等待到期后不追加第二段冷却`() {
        val window = StarfallBeamWindow(original = 2.8f, burstLength = 0.3f, skipIdle = false)
        assertFalse(window.advance(0.01f, 0.01f, 0f, true))
        assertTrue(window.advance(0.02f, 0.3f, 2.77f, true))
        assertFalse(window.advance(0.7f, 0.3f, 0.01f, true))
        assertEquals(0f, window.remaining)
        assertFalse(window.advance(0.3f, 0.3f, 0f, false))
        assertFalse(window.advance(0.01f, 0.29f, 0f, true))
    }
}
