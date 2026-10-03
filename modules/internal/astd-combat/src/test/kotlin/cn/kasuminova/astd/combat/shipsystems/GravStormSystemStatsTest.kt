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

/**
 * 引力磁暴发生器硬辐能→软辐能转化的记账钉测：被转化的是**释放舰船自身**的硬辐能，
 * 所有被过载目标的「过载秒数 × 舰级档位」累加为总比例后**一次性**结算
 * （setHardFlux 不动 currFlux，软辐能 = currFlux − hardFlux 自动等额 +x，总辐能守恒），
 * 全程不得调 increaseFlux（其 hardFlux=true 分支会反向加硬辐能并抬总辐能）。
 */
class GravStormSystemStatsTest {

    @Test
    fun `硬转软：对自身一次性扣除累加比例、currFlux 不动、不调用 increaseFlux`() {
        val tracker = mock(FluxTrackerAPI::class.java)
        `when`(tracker.hardFlux).thenReturn(2000f)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.fluxTracker).thenReturn(tracker)

        // 巡洋舰 5s（0.15）+ 驱逐舰 4s（0.08）累加总比例 0.23 → 转化量 2000 × 0.23 = 460
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
        // 已过载目标跳过：既不重复过载也不贡献转化比例
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

        // 总比例 = 巡洋 3%×5s + 驱逐 2%×4s = 0.23（主力舰目标已过载不计）→ 2000 × 0.23 = 460
        verify(selfTracker, times(1)).hardFlux = 1540f
        verify(selfTracker, never()).increaseFlux(anyFloat(), anyBoolean())
        verify(selfTracker, never()).currFlux = anyFloat()
        // 目标自身辐能不被转化结算触碰
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

    /**
     * 断言点：ACTIVE 首帧相位态按「相位结束充能」口径处理——IN 末帧进入相位与充满同帧竞态时
     * （原版同一帧内 ChargeTracker.advance 先于脚本 apply，chargeTick 的相位分支来不及拦截），
     * 按满充能立即释放（releaseFn 收到 [GravStormTuning.MAX_CHARGE_SECONDS]）并 deactivate
     * 进冷却，不归位释放窗口。release 内含音效/扭曲等运行时静态调用，无头环境注入桩替代。
     */
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

    /**
     * 断言点：充能期间进入相位的统一口径（chargeTick 相位分支唯一出口）——
     * 立即按当前充能进度释放并进冷却，不得按取消处理（不释放直接进冷却的旧口径已作废）。
     */
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
