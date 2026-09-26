package cn.kasuminova.astd.renderer.boxutil.pool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TrailSlots 纯逻辑测试：认领返回与实体数组对齐的稳定索引、包络到期回收泊车、
 * 池满按游标覆盖最旧、逐槽位 alpha 基准随 spawn 更新。
 */
class TrailSlotsTest {

    private fun claimDefault(slots: TrailSlots, tailAlpha: Float = 0.04f): Int =
        slots.claim(
            fadeIn = 0.01f, full = 0.06f, fadeOut = 0.22f,
            tailAlpha = tailAlpha, headAlpha = 0.4f,
            tailEmissiveAlpha = 0.25f, headEmissiveAlpha = 2.0f,
        )

    @Test
    fun `claim returns stable increasing indexes`() {
        val slots = TrailSlots(4)
        val a = claimDefault(slots)
        val b = claimDefault(slots)
        assertEquals(2, slots.activeCount)
        assertTrue(a != b, "两次认领必须落在不同槽位")
        assertTrue(slots.slots[a].active)
        assertTrue(slots.slots[b].active)
    }

    @Test
    fun `expired slot parks and frees for reclaim`() {
        val slots = TrailSlots(1)
        val a = claimDefault(slots)
        // 总寿命 0.29s：推进 0.3s 后到期泊车。
        slots.advance(0.3f)
        assertFalse(slots.slots[a].active, "包络走完必须泊车")
        assertEquals(0f, slots.alphaAt(a), 1e-4f, "泊车槽位 alpha 必须为 0")
        assertEquals(0, slots.activeCount)

        val b = claimDefault(slots)
        assertEquals(a, b, "回收后的槽位必须可被再次认领")
        assertEquals(0f, slots.slots[b].age, 1e-4f, "再认领年龄必须归零")
    }

    @Test
    fun `alpha follows envelope while active`() {
        val slots = TrailSlots(2)
        val a = claimDefault(slots)
        // fadeIn=0.01 起点 alpha=0。
        assertEquals(0f, slots.alphaAt(a), 1e-4f)
        // age=0.03（full 段）alpha=1。
        slots.advance(0.03f)
        assertEquals(1f, slots.alphaAt(a), 1e-4f, "full 段 alpha=1")
    }

    @Test
    fun `full capacity overwrites oldest slot`() {
        val slots = TrailSlots(2)
        val a = claimDefault(slots)
        val b = claimDefault(slots)
        val c = claimDefault(slots)
        assertEquals(a, c, "池满必须覆盖最旧槽位")
        assertEquals(2, slots.activeCount, "覆盖不增加活跃数")
        val d = claimDefault(slots)
        assertEquals(b, d, "再次覆盖按游标顺序轮到次旧槽位")
    }

    @Test
    fun `per spawn alpha bases update on reclaim`() {
        val slots = TrailSlots(1)
        claimDefault(slots, tailAlpha = 0.04f)
        slots.advance(10f)
        val index = slots.claim(
            fadeIn = 0f, full = 1f, fadeOut = 0f,
            tailAlpha = 0.5f, headAlpha = 0.6f,
            tailEmissiveAlpha = 0.7f, headEmissiveAlpha = 0.8f,
        )
        val s = slots.slots[index]
        assertEquals(0.5f, s.tailAlpha, 1e-4f, "再认领必须更新 alpha 基准")
        assertEquals(0.8f, s.headEmissiveAlpha, 1e-4f)
    }
}
