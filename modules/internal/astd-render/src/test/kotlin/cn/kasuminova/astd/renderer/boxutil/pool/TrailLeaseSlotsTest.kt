package cn.kasuminova.astd.renderer.boxutil.pool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TrailLeaseSlots 纯逻辑测试：租约认领/代数失效、按需扩容与 maxCapacity 拒发不抢占、
 * 一次性包络到期泊车、显式释放快照淡出、看门狗停更触发淡出与触活复活。
 */
class TrailLeaseSlotsTest {

    private fun claimEnvelope(slots: TrailLeaseSlots, fadeIn: Float = 0.01f, full: Float = 0.06f, fadeOut: Float = 0.22f): Int =
        slots.claim(
            TrailLeaseEnvelope(fadeIn, full, fadeOut),
            watchdogHeartbeat = 0f, watchdogFadeOut = 0f,
            startAlpha = 0.04f, endAlpha = 0.4f,
            startEmissiveAlpha = 0.25f, endEmissiveAlpha = 2.0f,
        )

    private fun claimManual(slots: TrailLeaseSlots, heartbeat: Float = 0.35f, fadeOut: Float = 0.16f): Int =
        slots.claim(
            null,
            watchdogHeartbeat = heartbeat, watchdogFadeOut = fadeOut,
            startAlpha = 0.8f, endAlpha = 1.0f,
            startEmissiveAlpha = 0.2f, endEmissiveAlpha = 0.4f,
        )

    @Test
    fun `claim leases distinct free slots and tracks count`() {
        val slots = TrailLeaseSlots(3)
        val a = claimEnvelope(slots)
        val b = claimManual(slots)
        assertTrue(a >= 0 && b >= 0 && a != b, "两次认领必须落在不同槽位")
        assertEquals(2, slots.leasedCount)
        assertTrue(slots.isHeld(a, slots.slots[a].generation))
        assertTrue(slots.isHeld(b, slots.slots[b].generation))
    }

    @Test
    fun `full pool rejects new lease without preempting leased slots`() {
        val slots = TrailLeaseSlots(2, maxCapacity = 2)
        val a = claimEnvelope(slots)
        val b = claimEnvelope(slots)
        val genA = slots.slots[a].generation
        val genB = slots.slots[b].generation

        val c = claimEnvelope(slots)
        assertEquals(-1, c, "触及 maxCapacity 必须拒发新租约")
        assertEquals(1, slots.overflowCount, "拒发必须累计（绑定层节流 WARN 数据源）")
        assertEquals(2, slots.leasedCount, "拒发不得改变在租数")
        // 在租槽位不被抢占：代数与归属保持不变
        assertEquals(genA, slots.slots[a].generation)
        assertEquals(genB, slots.slots[b].generation)
        assertTrue(slots.isHeld(a, genA))
        assertTrue(slots.isHeld(b, genB))
    }

    @Test
    fun `full pool grows on demand and new claim succeeds`() {
        val slots = TrailLeaseSlots(1) // maxCapacity 默认 8×
        val a = claimEnvelope(slots)
        assertEquals(1, slots.capacity)

        val b = claimManual(slots)
        assertTrue(b >= 0, "初始容量满必须自动扩容而非拒发")
        assertTrue(slots.capacity > 1, "扩容后槽位数必须增长")
        assertEquals(0, slots.overflowCount, "扩容路径不得计入拒发")
        assertEquals(2, slots.leasedCount)
        // 新租约落在扩容出的尾部槽位，旧租约不受影响
        assertEquals(a + 1, b)
        assertTrue(slots.isHeld(b, slots.slots[b].generation))
    }

    @Test
    fun `growth does not disturb held lease generation and lifecycle`() {
        val slots = TrailLeaseSlots(1)
        val a = claimManual(slots, heartbeat = 0.35f, fadeOut = 0.16f)
        val genA = slots.slots[a].generation

        claimEnvelope(slots) // 触发扩容
        assertTrue(slots.isHeld(a, genA), "扩容不得影响在租槽位的代数与归属")
        assertEquals(genA, slots.slots[a].generation, "扩容不得重置在租槽位代数")

        // 在租租约生命周期语义不变：逐帧触活后停更仍按看门狗触发
        slots.touch(a, genA)
        assertTrue(slots.advance(0.02f).isEmpty())
        val events = slots.advance(0.36f)
        assertTrue(events.any { it is TrailLeaseEvent.BeginReleaseFade && it.index == a }, "扩容后看门狗必须照常触发")
    }

    @Test
    fun `claim rejected only after growth reaches max capacity`() {
        val slots = TrailLeaseSlots(1, maxCapacity = 2)
        claimEnvelope(slots)
        val b = claimEnvelope(slots)
        assertTrue(b >= 0, "未达 maxCapacity 必须扩容放行")
        assertEquals(2, slots.capacity, "扩容不得超过 maxCapacity")

        val c = claimEnvelope(slots)
        assertEquals(-1, c, "触及 maxCapacity 必须拒发")
        assertEquals(1, slots.overflowCount)
        assertEquals(2, slots.leasedCount, "拒发不得改变在租数")
        assertEquals(2, slots.capacity, "拒发不得再扩容")
    }

    @Test
    fun `envelope expiry parks slot and frees for reclaim with stale generation rejected`() {
        val slots = TrailLeaseSlots(1)
        val a = claimEnvelope(slots)
        val genA = slots.slots[a].generation
        // 总寿命 0.29s：推进 0.3s 后到期泊车
        val events = slots.advance(0.3f)
        assertTrue(events.any { it is TrailLeaseEvent.Parked && it.index == a }, "包络走完必须发泊车事件")
        assertFalse(slots.isHeld(a, genA), "到期后旧句柄不得再持有槽位")
        assertEquals(0, slots.leasedCount)
        assertEquals(0f, slots.alphaMulAt(a), 1e-4f, "泊车槽位 alpha 乘数必须为 0")

        // 旧代数的操作全部失效（防串租约）
        slots.touch(a, genA)
        slots.refreshEnvelope(a, genA)
        assertFalse(slots.release(a, genA, 0.5f), "旧代数 release 必须无效")

        val b = claimEnvelope(slots)
        assertEquals(a, b, "回收后的槽位必须可被再次认领")
        assertTrue(slots.slots[b].generation != genA, "再认领必须换代数")
        assertEquals(0f, slots.slots[b].age, 1e-4f, "再认领年龄必须归零")
    }

    @Test
    fun `envelope alpha follows three phase envelope while active`() {
        val slots = TrailLeaseSlots(1)
        val a = claimEnvelope(slots)
        assertEquals(0f, slots.alphaMulAt(a), 1e-4f, "fadeIn 起点 alpha=0")
        slots.advance(0.03f)
        assertEquals(1f, slots.alphaMulAt(a), 1e-4f, "full 段 alpha=1")
        slots.advance(0.14f) // age=0.17，进入 fadeOut：afterFull=0.10（0.22s 线性）
        val expected = 1f - 0.10f / 0.22f
        assertEquals(expected, slots.alphaMulAt(a), 1e-4f, "fadeOut 段必须线性衰减")
    }

    @Test
    fun `refreshEnvelope resets envelope age`() {
        val slots = TrailLeaseSlots(1)
        val a = claimEnvelope(slots, fadeIn = 0f, full = 0.06f, fadeOut = 0.22f)
        val gen = slots.slots[a].generation
        slots.advance(0.05f)
        slots.refreshEnvelope(a, gen)
        assertEquals(0f, slots.slots[a].age, 1e-4f, "refresh 必须重置包络年龄")
        slots.advance(0.05f)
        assertEquals(1f, slots.alphaMulAt(a), 1e-4f, "refresh 后 full 段重新计时不应到期")
    }

    @Test
    fun `manual slot is pool silent until explicit release`() {
        val slots = TrailLeaseSlots(1)
        val a = slots.claim(
            null, watchdogHeartbeat = 0f, watchdogFadeOut = 0f,
            startAlpha = 0.8f, endAlpha = 1.0f, startEmissiveAlpha = 0.2f, endEmissiveAlpha = 0.4f,
        )
        // 纯手动（无看门狗）：推进不触发任何事件，alpha 乘数恒 1（调用方全权）
        val events = slots.advance(10f)
        assertTrue(events.isEmpty(), "纯手动槽位不得产生池事件")
        assertEquals(1f, slots.alphaMulAt(a), 1e-4f)
        assertTrue(slots.isHeld(a, slots.slots[a].generation))
    }

    @Test
    fun `explicit release fades from snapshot then parks`() {
        val slots = TrailLeaseSlots(1)
        val a = claimManual(slots)
        val gen = slots.slots[a].generation

        val needSnapshot = slots.release(a, gen, 0.5f)
        assertTrue(needSnapshot, "带淡出的释放必须要求快照当前 alpha")
        assertTrue(slots.isHeld(a, gen), "淡出途中租约仍持有槽位")
        slots.snapshotReleaseAlphas(a, 0.6f, 0.7f, 0.8f, 0.9f)
        assertEquals(0.6f, slots.slots[a].startAlpha, 1e-4f, "快照必须写入槽位作为淡出基准")

        slots.advance(0.25f)
        assertEquals(0.5f, slots.alphaMulAt(a), 1e-4f, "淡出中点乘数必须为 0.5")
        val events = slots.advance(0.25f)
        assertTrue(events.any { it is TrailLeaseEvent.Parked && it.index == a }, "淡出走完必须泊车")
        assertFalse(slots.isHeld(a, gen))
        assertEquals(0, slots.leasedCount)
    }

    @Test
    fun `immediate release frees slot without fade`() {
        val slots = TrailLeaseSlots(1)
        val a = claimManual(slots)
        val gen = slots.slots[a].generation
        val needSnapshot = slots.release(a, gen, 0f)
        assertFalse(needSnapshot, "立即释放不得要求快照")
        assertFalse(slots.isHeld(a, gen), "立即释放后槽位必须空闲")
        assertEquals(0, slots.leasedCount)
    }

    @Test
    fun `watchdog triggers release fade after heartbeat without touch`() {
        val slots = TrailLeaseSlots(1)
        val a = claimManual(slots, heartbeat = 0.35f, fadeOut = 0.16f)
        val gen = slots.slots[a].generation

        // 逐帧触活 0.4s：看门狗不得触发
        repeat(20) {
            slots.touch(a, gen)
            assertTrue(slots.advance(0.02f).isEmpty(), "触活期间看门狗不得触发（第 $it 帧）")
        }
        assertEquals(1f, slots.alphaMulAt(a), 1e-4f, "触活期间 alpha 乘数恒 1（调用方全权）")

        // 停更 0.35s：看门狗触发快照淡出
        val events = slots.advance(0.36f)
        assertTrue(events.any { it is TrailLeaseEvent.BeginReleaseFade && it.index == a }, "停更超心跳必须触发快照淡出")
        assertTrue(slots.isHeld(a, gen), "看门狗淡出途中租约仍持有槽位")

        // 淡出 0.16s 走完自动泊车
        val parked = slots.advance(0.17f)
        assertTrue(parked.any { it is TrailLeaseEvent.Parked && it.index == a }, "看门狗淡出走完必须泊车")
        assertFalse(slots.isHeld(a, gen))
    }

    @Test
    fun `touch during release fade revives lease to manual`() {
        val slots = TrailLeaseSlots(1)
        val a = claimManual(slots, heartbeat = 0.35f, fadeOut = 0.16f)
        val gen = slots.slots[a].generation

        slots.advance(0.36f) // 看门狗触发 RELEASING
        assertEquals(TrailLeaseSlots.Mode.RELEASING, slots.slots[a].mode)

        slots.touch(a, gen)
        assertEquals(TrailLeaseSlots.Mode.MANUAL, slots.slots[a].mode, "淡出途中触活必须复活回手动（对齐重钉 timer 打断淡出）")
        assertEquals(1f, slots.alphaMulAt(a), 1e-4f, "复活后 alpha 乘数回 1（调用方本帧重写）")
        assertEquals(0f, slots.slots[a].sinceTouch, 1e-4f, "复活后看门狗计时归零")

        // 复活后继续逐帧触活：不再触发
        repeat(20) {
            slots.touch(a, gen)
            slots.advance(0.02f)
        }
        assertEquals(TrailLeaseSlots.Mode.MANUAL, slots.slots[a].mode)
    }

    @Test
    fun `zero watchdog fade out parks immediately without NaN alpha`() {
        val slots = TrailLeaseSlots(1)
        // 看门狗 fadeOut=0：触发后 alpha 乘数必须视为立即完成（0），不得除零产 NaN
        val a = claimManual(slots, heartbeat = 0.35f, fadeOut = 0f)
        val gen = slots.slots[a].generation

        val events = slots.advance(0.36f)
        assertTrue(events.any { it is TrailLeaseEvent.BeginReleaseFade && it.index == a }, "停更超心跳必须触发淡出")
        val mul = slots.alphaMulAt(a)
        assertEquals(0f, mul, 1e-4f, "fadeOut=0 的释放淡出必须视为立即完成")

        val parked = slots.advance(0.01f)
        assertTrue(parked.any { it is TrailLeaseEvent.Parked && it.index == a }, "fadeOut=0 必须下一拍立即泊车")
        assertFalse(slots.isHeld(a, gen))
    }

    @Test
    fun `claim after watchdog park reuses slot with updated alpha bases`() {
        val slots = TrailLeaseSlots(1)
        val a = claimManual(slots)
        val genA = slots.slots[a].generation
        slots.advance(0.36f) // 触发看门狗
        slots.advance(0.17f) // 淡出走完泊车
        assertFalse(slots.isHeld(a, genA))

        val b = slots.claim(
            null, watchdogHeartbeat = 0f, watchdogFadeOut = 0f,
            startAlpha = 0.5f, endAlpha = 0.6f, startEmissiveAlpha = 0.7f, endEmissiveAlpha = 0.8f,
        )
        assertEquals(a, b, "泊车后的槽位必须可再认领")
        assertEquals(0.5f, slots.slots[b].startAlpha, 1e-4f, "再认领必须更新 alpha 基准")
        assertEquals(0.8f, slots.slots[b].endEmissiveAlpha, 1e-4f)
        assertEquals(0f, slots.slots[b].sinceTouch, 1e-4f, "再认领看门狗计时归零")
    }
}
