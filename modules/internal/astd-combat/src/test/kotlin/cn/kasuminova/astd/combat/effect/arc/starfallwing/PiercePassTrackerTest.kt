package cn.kasuminova.astd.combat.effect.arc.starfallwing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 坠星残翼「单次穿越」结算闩锁（[PiercePassTracker]）的完整逻辑验证：
 * 非舰船目标一次性标记、脱离接触一帧后新穿越重置、首触判定、主弹振频适应全局闩锁。
 */
class PiercePassTrackerTest {

    @Test
    fun `非舰船目标一次性结算闩锁`() {
        val tracker = PiercePassTracker()
        val missile = Any()
        assertTrue(tracker.trySettleOnce(missile))
        assertFalse(tracker.trySettleOnce(missile))
        tracker.retainContacts(emptySet())
        assertTrue(tracker.trySettleOnce(missile), "脱离接触后新穿越重新结算")
    }

    @Test
    fun `首触判定 未接触目标为首触 接触登记后非首触`() {
        val tracker = PiercePassTracker()
        val a = Any()
        val b = Any()
        assertTrue(tracker.isFirstContact(a))
        tracker.touch(a)
        assertFalse(tracker.isFirstContact(a))
        assertTrue(tracker.isFirstContact(b), "换目标时新目标仍是首触（首触补拍依据）")
        tracker.retainContacts(setOf(b))
        assertTrue(tracker.isFirstContact(a), "脱离接触的 A 再次接触算首触")
    }

    @Test
    fun `振频适应全局闩锁 首个目标附加后任何目标不再附加 且不随穿越重置`() {
        val tracker = PiercePassTracker()
        assertTrue(tracker.tryLatchAdaptation(), "首次附加放行")
        assertFalse(tracker.tryLatchAdaptation(), "闩锁后不再附加")
        // 目标脱离接触（新穿越）不重置全局闩锁
        tracker.retainContacts(emptySet())
        assertFalse(tracker.tryLatchAdaptation(), "穿越重置不得影响振频适应闩锁")
    }
}
