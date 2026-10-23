package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityPowerHullMod
import cn.kasuminova.astd.combat.hullmods.base.signedPercentOf
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.util.DynamicStatsAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SingularityPowerHullModTest {

    private val hullmod = ASTDSingularityPowerHullMod()

    @AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private class StatMap {
        val ventRate = MutableStat(1f)
        val overloadTime = StatBonus()
        val environment = MutableStat(1f)
        val ballisticFlux = StatBonus()
        val energyFlux = StatBonus()
        val missileFlux = StatBonus()
    }

    private fun stubStats(map: StatMap): MutableShipStatsAPI {
        val stats = mock(MutableShipStatsAPI::class.java)
        val dynamic = mock(DynamicStatsAPI::class.java)
        `when`(stats.dynamic).thenReturn(dynamic)
        `when`(dynamic.getStat(Stats.CORONA_EFFECT_MULT)).thenReturn(map.environment)
        `when`(stats.ventRateMult).thenReturn(map.ventRate)
        `when`(stats.overloadTimeMod).thenReturn(map.overloadTime)
        `when`(stats.ballisticWeaponFluxCostMod).thenReturn(map.ballisticFlux)
        `when`(stats.energyWeaponFluxCostMod).thenReturn(map.energyFlux)
        `when`(stats.missileWeaponFluxCostMod).thenReturn(map.missileFlux)
        return stats
    }

    private fun stubShip(owner: Int, stats: MutableShipStatsAPI): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.mutableStats).thenReturn(stats)
        return ship
    }

    @Test
    fun `装配期只写环境抗性归零`() {
        val map = StatMap()
        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.CRUISER, stubStats(map), "test")

        assertEquals(0f, map.environment.modifiedValue, 1e-6f)
        assertEquals(1f, map.ventRate.modifiedValue, 1e-6f)
        assertEquals(100f, map.overloadTime.computeEffective(100f), 1e-3f)
        assertEquals(100f, map.ballisticFlux.computeEffective(100f), 1e-3f)
    }

    @Test
    fun `战斗内玩家侧按我方档位取值（默认砺刃 v2）`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        val map = StatMap()
        hullmod.advanceInCombat(stubShip(owner = 0, stubStats(map)), 0.016f)

        assertEquals(1.2f, map.ventRate.modifiedValue, 1e-6f)
        assertEquals(80f, map.overloadTime.computeEffective(100f), 1e-3f)
        assertEquals(90f, map.ballisticFlux.computeEffective(100f), 1e-3f)
        assertEquals(90f, map.energyFlux.computeEffective(100f), 1e-3f)
        assertEquals(90f, map.missileFlux.computeEffective(100f), 1e-3f)
    }

    @Test
    fun `战斗内敌方按 k_s 三锚点映射`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        val map = StatMap()
        hullmod.advanceInCombat(stubShip(owner = 1, stubStats(map)), 0.016f)

        assertEquals(1.1f, map.ventRate.modifiedValue, 1e-6f)
        assertEquals(90f, map.overloadTime.computeEffective(100f), 1e-3f)
        assertEquals(95f, map.ballisticFlux.computeEffective(100f), 1e-3f)

        DifficultyTuningImpl.installScaleForTests(5f)
        val mapV5 = StatMap()
        hullmod.advanceInCombat(stubShip(owner = 1, stubStats(mapV5)), 0.016f)

        assertEquals(1.5f, mapV5.ventRate.modifiedValue, 1e-6f)
        assertEquals(50f, mapV5.overloadTime.computeEffective(100f), 1e-3f)
        assertEquals(75f, mapV5.ballisticFlux.computeEffective(100f), 1e-3f)
    }

    @Test
    fun `百分数格式化带符号且整值去小数`() {
        assertEquals("+20%", signedPercentOf(0.2f))
        assertEquals("-10%", signedPercentOf(-0.1f))
        assertEquals("+12.5%", signedPercentOf(0.125f))
        assertEquals("-50%", signedPercentOf(-0.5f))
        assertEquals("0%", signedPercentOf(0f))
    }
}
