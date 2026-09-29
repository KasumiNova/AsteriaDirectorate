package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityPowerHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.util.DynamicStatsAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 奇点能源（[ASTDSingularityPowerHullMod]）装配期 stat 写入验证：
 * 强制排辐速率 ×1.2、过载持续时间 ×0.8、环境抗性（corona_resistance）×0、三类武器辐耗 ×0.9。
 */
class SingularityPowerHullModTest {

    private val hullmod = ASTDSingularityPowerHullMod()

    private class StatMap {
        val ventRate = MutableStat(1f)
        val overloadTime = StatBonus()
        val environment = MutableStat(1f)
        val ballisticFlux = StatBonus()
        val energyFlux = StatBonus()
        val missileFlux = StatBonus()
    }

    private fun stubStats(): Pair<MutableShipStatsAPI, StatMap> {
        val stats = mock(MutableShipStatsAPI::class.java)
        val map = StatMap()
        val dynamic = mock(DynamicStatsAPI::class.java)
        `when`(stats.dynamic).thenReturn(dynamic)
        `when`(dynamic.getStat(Stats.CORONA_EFFECT_MULT)).thenReturn(map.environment)
        `when`(stats.ventRateMult).thenReturn(map.ventRate)
        `when`(stats.overloadTimeMod).thenReturn(map.overloadTime)
        `when`(stats.ballisticWeaponFluxCostMod).thenReturn(map.ballisticFlux)
        `when`(stats.energyWeaponFluxCostMod).thenReturn(map.energyFlux)
        `when`(stats.missileWeaponFluxCostMod).thenReturn(map.missileFlux)
        return stats to map
    }

    @Test
    fun `创建前效果写入 排辐过载环境抗性与三类武器辐耗乘区`() {
        val (stats, map) = stubStats()

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.CRUISER, stats, "test")

        // 强制排辐速率 +20%
        assertEquals(1.2f, map.ventRate.modifiedValue, 1e-6f)
        // 过载持续时间 -20%
        assertEquals(80f, map.overloadTime.computeEffective(100f), 1e-3f)
        // 负面环境对战备值与峰值时间的影响 -100%（corona_resistance 归零，原版 SolarShielding 判例）
        assertEquals(0f, map.environment.modifiedValue, 1e-6f)
        // 武器辐能产出 -10%（实弹/能量/导弹三通道）
        assertEquals(90f, map.ballisticFlux.computeEffective(100f), 1e-3f)
        assertEquals(90f, map.energyFlux.computeEffective(100f), 1e-3f)
        assertEquals(90f, map.missileFlux.computeEffective(100f), 1e-3f)
    }
}
