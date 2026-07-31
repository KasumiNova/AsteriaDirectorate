package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.StackDecayMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规格 §1.4-3/5/7：addStacks clamp、CONTINUOUS/WINDOWED/EXPIRE_ALL 衰减语义、0 值防线。
 */
class StackableBuffDecayTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    @Test
    fun `addStacks clamps to cap, to zero, and reports actual delta`() {
        val buff = ReferenceStackableBuff(decayMode = StackDecayMode.CONTINUOUS, maxStacks = 5, stacksPerSecond = 10f)

        assertEquals(5, buff.addStacks(10), "超上限叠加返回实际生效增量")
        assertEquals(5, buff.stacks)

        assertEquals(0, buff.addStacks(3), "满层再叠加增量为 0")
        assertEquals(5, buff.stacks)

        assertEquals(-3, buff.addStacks(-3), "扣层返回实际扣减量")
        assertEquals(2, buff.stacks)

        assertEquals(-2, buff.addStacks(-99), "扣穿下限只扣到 0")
        assertEquals(0, buff.stacks)

        assertEquals(0, buff.addStacks(-1), "空层再扣增量为 0")
        assertEquals(0, buff.stacks)
    }

    @Test
    fun `continuous decay drains at rate per second with sub-stack accumulation`() {
        val buff = ReferenceStackableBuff(decayMode = StackDecayMode.CONTINUOUS, maxStacks = 10, stacksPerSecond = 10f)
        buff.addStacks(10)

        // 小数累积：两帧 0.05s 合计 0.1s * 10/s = 1 层。
        buff.advance(0.05f)
        assertEquals(10, buff.stacks)
        buff.advance(0.05f)
        assertEquals(9, buff.stacks)

        buff.advance(0.1f)
        assertEquals(8, buff.stacks)

        // 流失到底即停，不产生负层。
        repeat(20) { buff.advance(0.1f) }
        assertEquals(0, buff.stacks)
    }

    @Test
    fun `windowed decay is silent inside window and drains only the overshoot after exactly 3s`() {
        val buff = ReferenceStackableBuff(
            decayMode = StackDecayMode.WINDOWED,
            maxStacks = 20,
            stacksPerSecond = 10f,
            windowSeconds = 3f,
        )
        buff.addStacks(20)

        // 恰 3s（三帧 1s）：窗口边界上不衰减。
        repeat(3) { buff.advance(1f) }
        assertEquals(20, buff.stacks, "恰在窗口边界不得衰减")

        // 3s+0.1s：只计超出窗口的 0.1s，衰减 10/s * 0.1s = 1 层。
        buff.advance(0.1f)
        assertEquals(19, buff.stacks)

        // 窗口外整帧衰减：1s * 10/s = 10 层。
        buff.advance(1f)
        assertEquals(9, buff.stacks)
    }

    @Test
    fun `windowed decay timer refreshes on positive stack add`() {
        val buff = ReferenceStackableBuff(
            decayMode = StackDecayMode.WINDOWED,
            maxStacks = 20,
            stacksPerSecond = 10f,
            windowSeconds = 3f,
        )
        buff.addStacks(20)

        repeat(2) { buff.advance(1f) }
        // 第 3 秒前命中刷新：窗口重新计时。
        buff.addStacks(1)
        repeat(3) { buff.advance(1f) }
        assertEquals(20, buff.stacks, "刷新后恰 3s 内不得衰减")

        buff.advance(0.2f)
        assertEquals(18, buff.stacks, "刷新后越过窗口 0.2s 衰减 2 层")
    }

    @Test
    fun `expire-all removes the whole buff after duration through the host`() {
        val data = HashMap<String, Any?>()
        val host = BuffHostImpl(data)
        val buff = ReferenceStackableBuff(
            decayMode = StackDecayMode.EXPIRE_ALL,
            maxStacks = 5,
            durationSeconds = 3f,
            host = host,
        )
        host.register(buff)
        buff.addStacks(5)

        buff.advance(1f)
        buff.advance(1f)
        buff.advance(0.9f)
        assertFalse(buff.expired, "到期前不得移除")
        assertEquals(5, buff.stacks)

        // 恰满 3s：整 Buff 经 host.remove 移除（层数不做逐层流失）。
        buff.advance(0.1f)
        assertTrue(buff.expired)
        assertEquals(1, buff.removeCalls, "onRemove 恰好一次")
        assertNull(host.find("astd_test_stack"), "到期后 host 中不得残留")
    }

    @Test
    fun `zero max stacks is clamped to one with warn`() {
        val capture = WarnCapture(ReferenceStackableBuff::class.java).also { captures += it }
        val buff = ReferenceStackableBuff(decayMode = StackDecayMode.CONTINUOUS, maxStacks = 0, stacksPerSecond = 1f)

        assertEquals(1, buff.maxStacks, "maxStacks=0 clamp 到下限 1")
        assertEquals(1, buff.addStacks(5))
        assertEquals(1, buff.stacks)
        assertTrue(capture.messages().any { it.contains("maxStacks") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `zero stacks per second disables decay with warn instead of silent stall`() {
        val capture = WarnCapture(ReferenceStackableBuff::class.java).also { captures += it }
        val buff = ReferenceStackableBuff(decayMode = StackDecayMode.CONTINUOUS, maxStacks = 5, stacksPerSecond = 0f)
        buff.addStacks(5)

        repeat(10) { buff.advance(1f) }
        assertEquals(5, buff.stacks, "rate=0 明确不衰减")
        assertTrue(capture.messages().any { it.contains("stacksPerSecond") }, "必须记 WARN: ${capture.messages()}")
    }
}
