package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * [GravStormSystemStats.convertHardToSoft] 辐能记账钉测：硬→软等额转化只允许动 hardFlux
 * （setHardFlux 不动 currFlux，软辐能 = currFlux − hardFlux 自动 +x，总辐能守恒），
 * 全程不得调 increaseFlux（其 hardFlux=true 分支会反向加硬辐能并抬总辐能）。
 */
class GravStormSystemStatsTest {

    @Test
    fun `硬转软：hardFlux 减 x、currFlux 不动、不调用 increaseFlux`() {
        val tracker = mock(FluxTrackerAPI::class.java)
        `when`(tracker.hardFlux).thenReturn(2000f)
        val target = mock(ShipAPI::class.java)
        `when`(target.fluxTracker).thenReturn(tracker)
        `when`(target.hullSize).thenReturn(ShipAPI.HullSize.CRUISER)

        // 巡洋舰 3%/s × 5s 过载 → x = 2000 × 0.15 = 300
        GravStormSystemStats().convertHardToSoft(target, 5f)

        verify(tracker).hardFlux = 1700f
        verify(tracker, never()).increaseFlux(anyFloat(), anyBoolean())
        verify(tracker, never()).currFlux = anyFloat()
    }

    @Test
    fun `硬转软：转化量为零时不写任何辐能状态`() {
        val tracker = mock(FluxTrackerAPI::class.java)
        `when`(tracker.hardFlux).thenReturn(0f)
        val target = mock(ShipAPI::class.java)
        `when`(target.fluxTracker).thenReturn(tracker)
        `when`(target.hullSize).thenReturn(ShipAPI.HullSize.CRUISER)

        GravStormSystemStats().convertHardToSoft(target, 5f)

        verify(tracker, never()).hardFlux = anyFloat()
        verify(tracker, never()).increaseFlux(anyFloat(), anyBoolean())
        verify(tracker, never()).currFlux = anyFloat()
    }
}
