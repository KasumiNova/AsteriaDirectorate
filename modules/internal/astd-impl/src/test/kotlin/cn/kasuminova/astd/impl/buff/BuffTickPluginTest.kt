package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.buffHost
import com.fs.starfarer.api.combat.CombatEngineAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.BeforeTest

/**
 * 规格 §1.4-6 与 §6 风险表：心跳回收（宿主失效/换装/hulk）、暂停跳过、advance 透传。
 */
class BuffTickPluginTest {

    @BeforeTest
    fun installBuffBackend() {
        // api 扩展函数族走 BuffBackends 桥：本模块测试装真后端（引擎为 null 时跳过心跳登记）。
        BuffInstall.install()
    }

    private fun stubEngine(ships: List<*>, paused: Boolean = false): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isPaused).thenReturn(paused)
        `when`(engine.ships).thenReturn(ships as List<com.fs.starfarer.api.combat.ShipAPI>)
        return engine
    }

    @Test
    fun `host-bound buff is recycled on the tick after host invalid and onRemove fires exactly once`() {
        val ship = stubShip()
        val buff = StubBuff(id = "astd_test_buff")
        ship.buffHost().register(buff)
        val plugin = BuffTickPlugin()
        val engine = stubEngine(listOf(ship))

        plugin.tick(engine, 0.1f)
        assertEquals(1, buff.advanceCalls, "宿主有效时 advance 透传")
        assertEquals(0.1f, buff.lastAmount)
        assertEquals(0, buff.removeCalls)

        buff.hostValid = false
        plugin.tick(engine, 0.1f)
        assertEquals(1, buff.removeCalls, "宿主失效后下一 tick 回收且 onRemove 恰好一次")
        assertEquals(1, buff.advanceCalls, "回收帧不再 advance")
        assertNull(ship.buffHost().find("astd_test_buff"))

        // 再次 tick：已移除的 Buff 不得二次回收。
        plugin.tick(engine, 0.1f)
        assertEquals(1, buff.removeCalls)
    }

    @Test
    fun `paused engine skips the whole tick`() {
        val ship = stubShip()
        val buff = StubBuff(id = "astd_test_buff")
        buff.hostValid = false
        ship.buffHost().register(buff)
        val plugin = BuffTickPlugin()
        val engine = stubEngine(listOf(ship), paused = true)

        plugin.tick(engine, 0.1f)
        assertEquals(0, buff.advanceCalls)
        assertEquals(0, buff.removeCalls)
        assertSame(buff, ship.buffHost().find("astd_test_buff"))
    }

    @Test
    fun `weapon-level buff is recycled by tick after the slot swaps to another weapon`() {
        val oldWeapon = stubWeapon(slotId = "WS0001", weaponId = "astd_old_cannon")
        val newWeapon = stubWeapon(slotId = "WS0001", weaponId = "astd_new_cannon")
        val ship = stubShip(weapons = listOf(newWeapon))
        val buff = StubBuff(id = "astd_test_weapon_buff")
        ship.buffHost().register(buff, oldWeapon)
        val plugin = BuffTickPlugin()
        val engine = stubEngine(listOf(ship))

        plugin.tick(engine, 0.1f)
        assertEquals(1, buff.removeCalls, "换装后心跳回收旧 Buff")
        assertNull(ship.buffHost().findByWeapon("astd_test_weapon_buff", newWeapon))
    }

    @Test
    fun `weapon-level buff is recycled when the slot becomes empty`() {
        val weapon = stubWeapon(slotId = "WS0001", weaponId = "astd_test_cannon")
        val ship = stubShip(weapons = emptyList())
        val buff = StubBuff(id = "astd_test_weapon_buff")
        ship.buffHost().register(buff, weapon)
        val plugin = BuffTickPlugin()
        val engine = stubEngine(listOf(ship))

        plugin.tick(engine, 0.1f)
        assertEquals(1, buff.removeCalls, "槽位空（武器被拆）后心跳回收")
    }

    @Test
    fun `hulk ship recycles host-bound buffs but self-managed ones keep advancing`() {
        val ship = stubShip(hulk = true)
        val hostBound = StubBuff(id = "astd_test_bound", lifetime = BuffLifetime.HOST_BOUND)
        val selfManaged = StubBuff(id = "astd_test_self", lifetime = BuffLifetime.SELF_MANAGED)
        ship.buffHost().register(hostBound)
        ship.buffHost().register(selfManaged)
        val plugin = BuffTickPlugin()
        val engine = stubEngine(listOf(ship))

        plugin.tick(engine, 0.1f)
        assertEquals(1, hostBound.removeCalls, "hulk 回收 HOST_BOUND")
        assertEquals(0, hostBound.advanceCalls)
        assertEquals(0, selfManaged.removeCalls, "SELF_MANAGED 自行管理，心跳不代为回收")
        assertEquals(1, selfManaged.advanceCalls, "SELF_MANAGED 仍收到 advance")
    }

    @Test
    fun `self-managed buff removing itself during advance does not break the tick`() {
        val ship = stubShip()
        val host = ship.buffHost()
        val other = StubBuff(id = "astd_test_other")
        val selfRemoving = object : StubBuff(id = "astd_test_self", lifetime = BuffLifetime.SELF_MANAGED) {
            override fun advance(amount: Float) {
                super.advance(amount)
                host.remove(this)
            }
        }
        host.register(selfRemoving)
        host.register(other)
        val plugin = BuffTickPlugin()
        val engine = stubEngine(listOf(ship))

        plugin.tick(engine, 0.1f)
        assertEquals(1, selfRemoving.removeCalls, "自行移除触发一次 onRemove")
        assertNull(host.find("astd_test_self"))
        assertEquals(1, other.advanceCalls, "同帧其他 Buff 不受影响")
    }

    @Test
    fun `ships without a registered host are skipped at zero cost`() {
        val ship = stubShip()
        val plugin = BuffTickPlugin()
        val engine = stubEngine(listOf(ship))

        // 未调用 buffHost()：customData 无 host 条目，tick 直接跳过（不抛错、不创建）。
        plugin.tick(engine, 0.1f)
        assertNull(ship.customData[BuffHostImpl.HOST_KEY])
    }
}
