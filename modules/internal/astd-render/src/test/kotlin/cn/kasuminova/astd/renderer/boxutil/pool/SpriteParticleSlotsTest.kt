package cn.kasuminova.astd.renderer.boxutil.pool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SpriteParticleSlots 纯逻辑测试：槽位认领/回收、CPU 侧积分（位置/自转）、
 * 三段包络 alpha 派生、容量耗尽时按游标覆盖最旧槽位。
 */
class SpriteParticleSlotsTest {

    private fun spawnDefault(slots: SpriteParticleSlots, fadeIn: Float = 0.1f, full: Float = 0.2f, fadeOut: Float = 0.3f): Int =
        slots.spawn(
            posX = 100f, posY = 200f,
            velX = 10f, velY = -20f,
            facingDeg = 30f, turnRateDeg = 90f,
            scaleX = 4f, scaleY = 3f,
            r = 1, g = 2, b = 3, a = 200,
            er = 5, eg = 6, eb = 7, ea = 100,
            fadeIn = fadeIn, full = full, fadeOut = fadeOut,
        )

    @Test
    fun `spawn claims slot and tracks active count`() {
        val slots = SpriteParticleSlots(4)
        assertEquals(0, slots.activeCount)
        val a = spawnDefault(slots)
        val b = spawnDefault(slots)
        assertEquals(2, slots.activeCount)
        assertTrue(a != b, "两次 spawn 必须认领不同槽位")
        assertTrue(slots.slots[a].active)
        assertTrue(slots.slots[b].active)
    }

    @Test
    fun `advance integrates position and facing on cpu side`() {
        val slots = SpriteParticleSlots(4)
        val index = spawnDefault(slots)
        slots.advance(0.5f)
        val s = slots.slots[index]
        assertEquals(105f, s.posX, 1e-4f, "posX = 100 + 10×0.5")
        assertEquals(190f, s.posY, 1e-4f, "posY = 200 - 20×0.5")
        assertEquals(75f, s.facingDeg, 1e-4f, "facing = 30 + 90×0.5")
        assertTrue(s.active, "总寿命 0.6s 内不得回收")
    }

    @Test
    fun `envelope alpha ramps through fadeIn full fadeOut`() {
        val slots = SpriteParticleSlots(4)
        spawnDefault(slots)

        // age=0：fadeIn 起点 alpha=0。
        slots.forEachActive { _, alpha -> assertEquals(0f, alpha, 1e-4f, "fadeIn 起点 alpha=0") }
        // age=0.05：fadeIn 半程 alpha=0.5。
        slots.advance(0.05f)
        slots.forEachActive { _, alpha -> assertEquals(0.5f, alpha, 1e-4f, "fadeIn 半程 alpha=0.5") }
        // age=0.15：full 段 alpha=1。
        slots.advance(0.10f)
        slots.forEachActive { _, alpha -> assertEquals(1f, alpha, 1e-4f, "full 段 alpha=1") }
        // age=0.45：fadeOut 半程 alpha=0.5。
        slots.advance(0.30f)
        slots.forEachActive { _, alpha -> assertEquals(0.5f, alpha, 1e-4f, "fadeOut 半程 alpha=0.5") }
        // age=0.60：包络走完，回收。
        slots.advance(0.15f)
        assertEquals(0, slots.activeCount, "包络走完必须回收")
        slots.forEachActive { _, _ -> throw AssertionError("回收后不得再有活跃槽位") }
    }

    @Test
    fun `full capacity overwrites oldest slot by cursor order`() {
        val slots = SpriteParticleSlots(2)
        val a = spawnDefault(slots)
        val b = spawnDefault(slots)
        assertEquals(2, slots.activeCount)

        // 池满：第三颗覆盖最旧槽位（a），活跃数不变。
        val c = spawnDefault(slots)
        assertEquals(a, c, "池满必须覆盖最旧槽位")
        assertEquals(2, slots.activeCount, "覆盖不增加活跃数")
        assertEquals(0f, slots.slots[c].age, 1e-4f, "被覆盖槽位年龄必须归零")

        val d = spawnDefault(slots)
        assertEquals(b, d, "再次覆盖按游标顺序轮到次旧槽位")
    }

    @Test
    fun `zero velocity slot stays put`() {
        val slots = SpriteParticleSlots(2)
        val index = slots.spawn(
            posX = 7f, posY = 8f, velX = 0f, velY = 0f,
            facingDeg = 0f, turnRateDeg = 0f, scaleX = 1f, scaleY = 1f,
            r = 0, g = 0, b = 0, a = 255, er = 0, eg = 0, eb = 0, ea = 255,
            fadeIn = 0f, full = 1f, fadeOut = 0f,
        )
        slots.advance(0.3f)
        val s = slots.slots[index]
        assertEquals(7f, s.posX, 1e-4f)
        assertEquals(8f, s.posY, 1e-4f)
        assertEquals(0f, s.facingDeg, 1e-4f)
    }

    @Test
    fun `zero fadeIn envelope starts at full alpha`() {
        assertEquals(1f, poolEnvelopeAlpha(0f, 0f, 0.5f, 0.5f), 1e-4f, "fadeIn=0 时 age=0 即满亮")
        assertFalse(poolEnvelopeExpired(0.9f, 0f, 0.5f, 0.5f))
        assertTrue(poolEnvelopeExpired(1.0f, 0f, 0.5f, 0.5f))
    }
}
