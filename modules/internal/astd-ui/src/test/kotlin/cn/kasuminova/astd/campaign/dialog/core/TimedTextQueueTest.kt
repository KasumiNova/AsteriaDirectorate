package cn.kasuminova.astd.campaign.dialog.core

import cn.kasuminova.astd.campaign.dialog.DialogTestRig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimedTextQueueTest {

    @Test
    fun `entries emit in order respecting per-entry delay`() {
        val rig = DialogTestRig()
        val queue = TimedTextQueue(rig.text)
        queue.enqueue("a", delay = 0.5f)
        queue.enqueue("b", delay = 0.5f)
        queue.enqueue("c", delay = 1.0f)

        assertEquals(0, queue.advance(0.4f))
        assertEquals(listOf(), rig.paras)

        // acc=0.6：第一条到点输出，剩余 0.1 不够第二条
        assertEquals(1, queue.advance(0.2f))
        assertEquals(listOf("a"), rig.paras)

        // acc=0.6：第二条输出，剩余 0.1
        assertEquals(1, queue.advance(0.5f))
        assertEquals(listOf("a", "b"), rig.paras)

        // acc=1.1：第三条输出
        assertEquals(1, queue.advance(1.0f))
        assertEquals(listOf("a", "b", "c"), rig.paras)
        assertFalse(queue.hasPending)
    }

    @Test
    fun `flush emits everything immediately at full opacity`() {
        val rig = DialogTestRig()
        val queue = TimedTextQueue(rig.text)
        queue.enqueueFading("a", delay = 0.5f, fadeIn = 1f)
        queue.enqueue("b", delay = 0.5f)
        queue.enqueue("c", delay = 0.5f)

        assertEquals(3, queue.flush())
        assertEquals(listOf("a", "b", "c"), rig.paras)
        assertFalse(queue.hasPending)

        // flush = 快进：所有段落立刻满透明度，无视 fadeIn 配置
        for ((_, history) in rig.opacityHistory) {
            assertEquals(listOf(1f), history)
        }
    }

    @Test
    fun `fadeIn entry ramps opacity up and rests at max opacity`() {
        val rig = DialogTestRig()
        val queue = TimedTextQueue(rig.text)
        queue.enqueueFading("a", delay = 0f, fadeIn = 1.0f, maxOpacity = 0.8f)

        queue.advance(0.1f)
        val history = rig.opacityHistory.values.single()
        // 初始 0（淡入起点），随后按 age/fadeIn*max 推进
        assertEquals(0f, history.first())
        assertEquals(0.08f, history.last(), 1e-4f)

        queue.advance(0.5f)
        assertEquals(0.48f, history.last(), 1e-4f)

        // 淡入完成后停留在 maxOpacity（段落不得消失）
        queue.advance(0.5f)
        assertEquals(0.8f, history.last(), 1e-4f)

        // active 已移除：后续帧不再触碰该 Label
        val size = history.size
        queue.advance(1f)
        queue.advance(1f)
        assertEquals(size, history.size)
    }

    @Test
    fun `fadeOut entry fades to zero after hold`() {
        val rig = DialogTestRig()
        val queue = TimedTextQueue(rig.text)
        queue.enqueueFading("a", delay = 0f, fadeIn = 0f, hold = 0.5f, fadeOut = 0.5f)

        queue.advance(0.1f)
        val history = rig.opacityHistory.values.single()
        // 无 fadeIn：起点即 maxOpacity，停留期保持
        assertEquals(1f, history.first())
        assertEquals(1f, history.last())

        // age=0.6：进入淡出，u=0.2 → 0.8
        queue.advance(0.5f)
        assertEquals(0.8f, history.last(), 1e-4f)

        // age=1.1：淡出完毕归为 0
        queue.advance(0.5f)
        assertEquals(0f, history.last(), 1e-4f)
    }

    @Test
    fun `clear drops pending entries and active fades`() {
        val rig = DialogTestRig()
        val queue = TimedTextQueue(rig.text)
        queue.enqueue("a", delay = 10f)
        queue.enqueue("b", delay = 10f)
        queue.clear()

        assertFalse(queue.hasPending)
        assertEquals(0, queue.advance(20f))
        assertEquals(listOf(), rig.paras)
    }
}
