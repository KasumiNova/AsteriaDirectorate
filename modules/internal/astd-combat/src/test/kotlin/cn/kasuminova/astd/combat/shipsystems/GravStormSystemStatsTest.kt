package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.lens.system.GravStormTuning
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class GravStormSystemStatsTest {

    @Test
    fun `硬转软：对自身一次性扣除累加比例、currFlux 不动、不调用 increaseFlux`() {
        val tracker = mock(FluxTrackerAPI::class.java)
        `when`(tracker.hardFlux).thenReturn(2000f)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.fluxTracker).thenReturn(tracker)

        GravStormSystemStats().convertHardToSoft(ship, 0.23f)

        verify(tracker, times(1)).hardFlux = 1540f
        verify(tracker, never()).increaseFlux(anyFloat(), anyBoolean())
        verify(tracker, never()).currFlux = anyFloat()
    }

    @Test
    fun `硬转软：转化量为零时不写任何辐能状态`() {
        val tracker = mock(FluxTrackerAPI::class.java)
        `when`(tracker.hardFlux).thenReturn(0f)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.fluxTracker).thenReturn(tracker)

        GravStormSystemStats().convertHardToSoft(ship, 0.23f)

        verify(tracker, never()).hardFlux = anyFloat()
        verify(tracker, never()).increaseFlux(anyFloat(), anyBoolean())
        verify(tracker, never()).currFlux = anyFloat()
    }

    @Test
    fun `过载结算：多目标比例累加后对自身统一扣除，已过载目标不贡献比例`() {
        val engine = mock(CombatEngineAPI::class.java)

        val selfTracker = mock(FluxTrackerAPI::class.java)
        `when`(selfTracker.hardFlux).thenReturn(2000f)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.fluxTracker).thenReturn(selfTracker)

        val cruiserTracker = mock(FluxTrackerAPI::class.java)
        val cruiser = mockOverloadTarget(ShipAPI.HullSize.CRUISER, cruiserTracker)
        val destroyerTracker = mock(FluxTrackerAPI::class.java)
        val destroyer = mockOverloadTarget(ShipAPI.HullSize.DESTROYER, destroyerTracker)
        val ventingTracker = mock(FluxTrackerAPI::class.java)
        `when`(ventingTracker.isOverloadedOrVenting).thenReturn(true)
        val venting = mockOverloadTarget(ShipAPI.HullSize.CAPITAL_SHIP, ventingTracker)

        val plans = listOf(
            GravStormSystemStats.TargetPlan(cruiser, 0, 1f, 5f),
            GravStormSystemStats.TargetPlan(destroyer, 0, 1f, 4f),
            GravStormSystemStats.TargetPlan(venting, 0, 1f, 6f),
        )
        GravStormSystemStats().applyOverloads(engine, ship, plans)

        verify(cruiserTracker).beginOverloadWithTotalBaseDuration(5f)
        verify(destroyerTracker).beginOverloadWithTotalBaseDuration(4f)
        verify(ventingTracker, never()).beginOverloadWithTotalBaseDuration(anyFloat())

        verify(selfTracker, times(1)).hardFlux = 1540f
        verify(selfTracker, never()).increaseFlux(anyFloat(), anyBoolean())
        verify(selfTracker, never()).currFlux = anyFloat()
        verify(cruiserTracker, never()).hardFlux = anyFloat()
        verify(destroyerTracker, never()).hardFlux = anyFloat()
    }

    private fun mockOverloadTarget(hullSize: ShipAPI.HullSize, tracker: FluxTrackerAPI): ShipAPI {
        val target = mock(ShipAPI::class.java)
        `when`(target.isAlive).thenReturn(true)
        `when`(target.hullSize).thenReturn(hullSize)
        `when`(target.fluxTracker).thenReturn(tracker)
        return target
    }

    @Test
    fun `ACTIVE 首帧相位态按满充能立即释放并进冷却`() {
        val engine = mock(CombatEngineAPI::class.java)
        val customData = HashMap<String, Any>()
        `when`(engine.customData).thenReturn(customData)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn("zw002")
        `when`(ship.isPhased).thenReturn(true)
        val system = mock(ShipSystemAPI::class.java)

        val releasedCharges = ArrayList<Float>()
        GravStormSystemStats().onActiveEntered(engine, ship, system) { _, _, chargeSeconds ->
            releasedCharges += chargeSeconds
        }

        assertEquals(listOf(GravStormTuning.MAX_CHARGE_SECONDS), releasedCharges, "相位结束充能必须按满充能立即释放")
        verify(system).deactivate()
        verify(system, never()).forceState(any(), anyFloat())
    }

    @Test
    fun `相位结束充能 按当前充能进度释放并进冷却`() {
        val engine = mock(CombatEngineAPI::class.java)
        val ship = mock(ShipAPI::class.java)
        val system = mock(ShipSystemAPI::class.java)

        val releasedCharges = ArrayList<Float>()
        GravStormSystemStats().endChargeByPhase(engine, ship, system, 3f) { _, _, chargeSeconds ->
            releasedCharges += chargeSeconds
        }

        assertEquals(listOf(3f), releasedCharges, "相位结束充能必须按当前充能进度原样释放")
        verify(system).deactivate()
    }
}
