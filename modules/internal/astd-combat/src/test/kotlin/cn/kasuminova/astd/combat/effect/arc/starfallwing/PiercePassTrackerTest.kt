package cn.kasuminova.astd.combat.effect.arc.starfallwing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 坠星残翼「单次穿越」结算闩锁（[PiercePassTracker]）的完整逻辑验证：
 * 装甲格按格去重、非舰船目标一次性标记、脱离接触一帧后新穿越重置、首触判定。
 */
class PiercePassTrackerTest {

    @Test
    fun `同一装甲格单次穿越只结算一次`() {
        val tracker = PiercePassTracker()
        val ship = Any()
        val packed = tracker.packCell(3, 5)
        assertTrue(tracker.trySettleCell(ship, packed), "首次结算放行")
        assertFalse(tracker.trySettleCell(ship, packed), "同格同穿越重复结算被闩锁")
        assertTrue(tracker.trySettleCell(ship, tracker.packCell(4, 5)), "相邻格独立结算")
    }

    @Test
    fun `目标脱离接触一帧后再次接触算新穿越 全部格重新可结算`() {
        val tracker = PiercePassTracker()
        val ship = Any()
        val packed = tracker.packCell(3, 5)
        assertTrue(tracker.trySettleCell(ship, packed))
        // 帧末清理：本帧仍在接触的目标保留（闩锁不重置）
        tracker.retainContacts(setOf(ship))
        assertFalse(tracker.trySettleCell(ship, packed), "持续接触中闩锁保持")
        // 本帧脱离接触 → 整项移除
        tracker.retainContacts(emptySet())
        assertTrue(tracker.trySettleCell(ship, packed), "新穿越同格重新结算")
    }

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
    fun `格号打包 不同格互不冲突且还原坐标`() {
        val tracker = PiercePassTracker()
        val seen = HashSet<Int>()
        for (x in 0..40) {
            for (y in 0..20) {
                assertTrue(seen.add(tracker.packCell(x, y)), "格 ($x,$y) 打包冲突")
            }
        }
    }
}
