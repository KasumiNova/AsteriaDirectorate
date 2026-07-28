package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.buff.getOrCreateBuff
import cn.kasuminova.astd.api.buff.getOrCreateBuffByWeapon
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 规格 §1.4-1/2/4：复合键隔离与换装不可见、getOrCreate 幂等、同键覆盖 WARN。
 */
class BuffHostImplTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    @Test
    fun `weapon-level buffs with same buff id on different slots do not collide`() {
        val host = BuffHostImpl(HashMap())
        val buffA = StubBuff(id = "astd_test_weapon_buff")
        val buffB = StubBuff(id = "astd_test_weapon_buff")
        val weaponA = stubWeapon(slotId = "WS0001", weaponId = "astd_test_cannon")
        val weaponB = stubWeapon(slotId = "WS0002", weaponId = "astd_test_cannon")

        host.register(buffA, weaponA)
        host.register(buffB, weaponB)

        assertSame(buffA, host.findByWeapon("astd_test_weapon_buff", weaponA))
        assertSame(buffB, host.findByWeapon("astd_test_weapon_buff", weaponB))
        assertEquals(2, host.all().size)
    }

    @Test
    fun `weapon-level buff is not visible from another ship`() {
        val hostA = BuffHostImpl(HashMap())
        val hostB = BuffHostImpl(HashMap())
        val weapon = stubWeapon(slotId = "WS0001", weaponId = "astd_test_cannon")

        hostA.register(StubBuff(id = "astd_test_weapon_buff"), weapon)

        // 复合键不含 ship 引用：键级联在各自 customData 上，换船即查不到。
        assertNull(hostB.findByWeapon("astd_test_weapon_buff", weapon))
    }

    @Test
    fun `stale entry after weapon swap is invisible and gets recycled on re-register without warn`() {
        val capture = WarnCapture(BuffHostImpl::class.java).also { captures += it }
        val host = BuffHostImpl(HashMap())
        val oldBuff = StubBuff(id = "astd_test_weapon_buff")
        val oldWeapon = stubWeapon(slotId = "WS0001", weaponId = "astd_old_cannon")
        host.register(oldBuff, oldWeapon)

        // 同槽位换装：登记 weaponId 与当前武器不符，视为不存在。
        val newWeapon = stubWeapon(slotId = "WS0001", weaponId = "astd_new_cannon")
        assertNull(host.findByWeapon("astd_test_weapon_buff", newWeapon))

        // 换装属正常流程：重新注册替换残留时回收旧 Buff 但不记 WARN（WARN 只给程序错误的重复注册）。
        val newBuff = StubBuff(id = "astd_test_weapon_buff")
        host.register(newBuff, newWeapon)
        assertEquals(1, oldBuff.removeCalls)
        assertSame(newBuff, host.findByWeapon("astd_test_weapon_buff", newWeapon))
        assertTrue(capture.messages().isEmpty(), "换装替换不应产生 WARN: ${capture.messages()}")
    }

    @Test
    fun `getOrCreateBuff is idempotent and creator runs at most once`() {
        val ship = stubShip()
        var creatorCalls = 0

        val first = ship.getOrCreateBuff("astd_test_buff") {
            creatorCalls++
            StubBuff(id = "astd_test_buff")
        }
        val second = ship.getOrCreateBuff("astd_test_buff") {
            creatorCalls++
            StubBuff(id = "astd_test_buff")
        }

        assertSame(first, second)
        assertEquals(1, creatorCalls)
        // 幂等前提：buffHost 本身也幂等（同一 host 实例）。
        assertSame(ship.buffHost(), ship.buffHost())
    }

    @Test
    fun `getOrCreateBuffByWeapon is idempotent per slot`() {
        val ship = stubShip()
        val weapon = stubWeapon(slotId = "WS0001", weaponId = "astd_test_cannon")
        var creatorCalls = 0

        val first = ship.getOrCreateBuffByWeapon("astd_test_weapon_buff", weapon) {
            creatorCalls++
            StubBuff(id = "astd_test_weapon_buff")
        }
        val second = ship.getOrCreateBuffByWeapon("astd_test_weapon_buff", weapon) {
            creatorCalls++
            StubBuff(id = "astd_test_weapon_buff")
        }

        assertSame(first, second)
        assertEquals(1, creatorCalls)
    }

    @Test
    fun `duplicate ship-level register warns and recycles the overwritten buff`() {
        val capture = WarnCapture(BuffHostImpl::class.java).also { captures += it }
        val host = BuffHostImpl(HashMap())
        val old = StubBuff(id = "astd_test_buff")
        val new = StubBuff(id = "astd_test_buff")

        host.register(old)
        host.register(new)

        assertEquals(1, old.removeCalls, "被覆盖的旧 Buff 必须回收一次")
        assertSame(new, host.find("astd_test_buff"))
        assertTrue(
            capture.messages().any { it.contains("重复注册") && it.contains("astd_test_buff") },
            "同键覆盖必须输出 WARN: ${capture.messages()}",
        )
    }

    @Test
    fun `duplicate weapon-level register with same weapon warns`() {
        val capture = WarnCapture(BuffHostImpl::class.java).also { captures += it }
        val host = BuffHostImpl(HashMap())
        val weapon = stubWeapon(slotId = "WS0001", weaponId = "astd_test_cannon")
        val old = StubBuff(id = "astd_test_weapon_buff")
        val new = StubBuff(id = "astd_test_weapon_buff")

        host.register(old, weapon)
        host.register(new, weapon)

        assertEquals(1, old.removeCalls)
        assertSame(new, host.findByWeapon("astd_test_weapon_buff", weapon))
        assertTrue(
            capture.messages().any { it.contains("重复注册") },
            "同武器同键重复注册必须输出 WARN: ${capture.messages()}",
        )
    }
}
