package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarfallWeaponCooldownsTest {
    @Test
    fun `有弹匣的单发和连发都按连射间隔计算`() {
        for (burstSize in listOf(1, 4)) {
            assertEquals(0.05f, StarfallWeaponCooldowns.projectileCooldown(true, burstSize, 0f, 2f))
            assertEquals(0.12f, StarfallWeaponCooldowns.projectileCooldown(true, burstSize, 0.12f, 2f))
        }
    }

    @Test
    fun `无弹匣连发取连射间隔 单发按原冷却的四分之一计算并应用独立下限`() {
        assertEquals(0.05f, StarfallWeaponCooldowns.projectileCooldown(false, 4, 0.02f, 3f))
        assertEquals(0.15f, StarfallWeaponCooldowns.projectileCooldown(false, 4, 0.15f, 3f))
        assertEquals(0.75f, StarfallWeaponCooldowns.projectileCooldown(false, 1, 0.9f, 3f))
        assertEquals(0.25f, StarfallWeaponCooldowns.projectileCooldown(false, 1, 0.9f, 0.1f))
    }

    @Test
    fun `齐射中途启动和退出都不改写相邻两发的计时器`() {
        val changes = mutableListOf<Pair<Float, Float?>>()
        val window = StarfallRefireWindow(0.1f) { delay, remaining -> changes += delay to remaining }
        window.apply(inBurst = true, delay = 0.04f, remainingFraction = 0.75f)
        assertTrue(changes.isEmpty())

        window.apply(inBurst = false, delay = 3.6f, remainingFraction = 1f)
        window.restore(inBurst = true, delay = 0.1f, remainingFraction = 0.5f)
        assertEquals(listOf<Pair<Float, Float?>>(0.1f to null), changes)
    }

    @Test
    fun `原始冷却短于规定间隔的射弹也须走完新的齐射后等待`() {
        val changes = mutableListOf<Pair<Float, Float?>>()
        val window = StarfallRefireWindow(0.1f) { delay, remaining -> changes += delay to remaining }
        window.apply(false, 0.05f, 1f)
        window.apply(false, 0.1f, 0.1f)
        assertEquals(listOf<Pair<Float, Float?>>(0.1f to null), changes)
    }

    @Test
    fun `启动时保留已耗冷却秒数 超过新时长的部分不带入下一轮`() {
        for (elapsed in listOf(0.0333f, 0.1167f, 2.0167f)) {
            var duration = 3.6f
            var cursor = elapsed
            val window = StarfallRefireWindow(0.1f) { next, left ->
                duration = next
                if (left != null) cursor = next - left
            }
            window.apply(false, duration, 1f - cursor / duration)
            assertEquals(minOf(elapsed, 0.1f), cursor, 0.00001f)
            assertEquals((0.1f - elapsed).coerceAtLeast(0f), duration - cursor, 0.00001f)
        }
    }

    @Test
    fun `退出时恢复原轮间冷却并保留本轮即将走完的余量`() {
        var duration = 3.6f
        var cursor = 0f
        val window = StarfallRefireWindow(0.1f) { next, left ->
            duration = next
            if (left != null) cursor = next - left
        }
        window.apply(false, duration, 0f)
        cursor = 0.095f
        window.restore(false, duration, 1f - cursor / duration)
        assertEquals(3.6f, duration)
        assertEquals(0.005f, duration - cursor, 0.00001f)
    }

    @Test
    fun `齐射中激活且未改写计时器时退出不覆盖原版恢复的时长`() {
        val changes = mutableListOf<Pair<Float, Float?>>()
        val window = StarfallRefireWindow(0.1f) { next, left -> changes += next to left }
        window.apply(true, 0.1f, 0.4f)
        window.restore(false, 0.1f, 1f)
        assertTrue(changes.isEmpty())

        window.apply(false, 3.6f, 0f)
        window.restore(false, 3.6f, 1f)
        assertEquals(listOf<Pair<Float, Float?>>(0.1f to null), changes)
    }
}
