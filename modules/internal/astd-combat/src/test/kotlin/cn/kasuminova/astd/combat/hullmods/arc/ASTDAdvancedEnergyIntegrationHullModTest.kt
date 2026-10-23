package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDAdvancedEnergyIntegrationHullModTest {

    private val hullmod = ASTDAdvancedEnergyIntegrationHullMod()

    @AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private class StatMap {
        val ballisticFluxCost = StatBonus()
        val energyFluxCost = StatBonus()
        val beamFluxCost = MutableStat(100f)
        val missileFluxCost = StatBonus()
        val ballisticProjSpeed = MutableStat(100f)
        val energyProjSpeed = MutableStat(100f)
        val weaponTurnRate = StatBonus()
    }

    private fun xc001Variant(): ShipVariantAPI {
        val hullSpec = mock(ShipHullSpecAPI::class.java)
        `when`(hullSpec.hullId).thenReturn("astd_xc_001")
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.hullSpec).thenReturn(hullSpec)
        `when`(variant.sMods).thenReturn(linkedSetOf())
        `when`(variant.sModdedBuiltIns).thenReturn(linkedSetOf())
        `when`(variant.suppressedMods).thenReturn(mutableSetOf())
        return variant
    }

    private fun stubStats(map: StatMap): MutableShipStatsAPI {
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.ballisticWeaponFluxCostMod).thenReturn(map.ballisticFluxCost)
        `when`(stats.energyWeaponFluxCostMod).thenReturn(map.energyFluxCost)
        `when`(stats.beamWeaponFluxCostMult).thenReturn(map.beamFluxCost)
        `when`(stats.missileWeaponFluxCostMod).thenReturn(map.missileFluxCost)
        `when`(stats.ballisticProjectileSpeedMult).thenReturn(map.ballisticProjSpeed)
        `when`(stats.energyProjectileSpeedMult).thenReturn(map.energyProjSpeed)
        `when`(stats.weaponTurnRateBonus).thenReturn(map.weaponTurnRate)
        return stats
    }

    private fun xc001Ship(stats: MutableShipStatsAPI, owner: Int): ShipAPI {
        val hullSpec = mock(ShipHullSpecAPI::class.java)
        `when`(hullSpec.hullId).thenReturn("astd_xc_001")
        val ship = mock(ShipAPI::class.java)
        `when`(ship.hullSpec).thenReturn(hullSpec)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isHulk).thenReturn(false)
        `when`(ship.mutableStats).thenReturn(stats)
        return ship
    }

    @Test
    fun `战斗内按 owner 结算难度缩放乘区 玩家侧默认砺刃 v2`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        val map = StatMap()
        val ship = xc001Ship(stubStats(map), owner = 0)

        hullmod.advanceInCombat(ship, 0.016f)

        assertEquals(85f, map.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(85f, map.energyFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(85f, map.beamFluxCost.modifiedValue, 1e-3f)
        assertEquals(85f, map.missileFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(120f, map.ballisticProjSpeed.modifiedValue, 1e-3f)
        assertEquals(120f, map.energyProjSpeed.modifiedValue, 1e-3f)
        assertEquals(150f, map.weaponTurnRate.computeEffective(100f), 1e-3f)
    }

    @Test
    fun `战斗内敌方按 k_s 映射 迟暮 v1 锚点`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        val map = StatMap()
        val ship = xc001Ship(stubStats(map), owner = 1)

        hullmod.advanceInCombat(ship, 0.016f)

        assertEquals(90f, map.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(115f, map.ballisticProjSpeed.modifiedValue, 1e-3f)
        assertEquals(125f, map.weaponTurnRate.computeEffective(100f), 1e-3f)
    }

    @Test
    fun `炮塔类禁装 创建前只入队 drain后五路清理`() {
        IncompatibleHullmodStripper.drainPending()
        val sMods = linkedSetOf(HullMods.TURRETGYROS)
        val sModdedBuiltIns = linkedSetOf(HullMods.ARMOREDWEAPONS)
        val variant = xc001Variant()
        `when`(variant.hasHullMod(HullMods.ARMOREDWEAPONS)).thenReturn(true)
        `when`(variant.sMods).thenReturn(sMods)
        `when`(variant.sModdedBuiltIns).thenReturn(sModdedBuiltIns)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.variant).thenReturn(variant)
        val dynamicStats = mock(com.fs.starfarer.api.util.DynamicStatsAPI::class.java)
        `when`(stats.dynamic).thenReturn(dynamicStats)
        `when`(dynamicStats.getMod(anyString())).thenReturn(mock(StatBonus::class.java))

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.CRUISER, stats, "test")

        verify(variant, never()).removeMod(anyString())
        assertTrue(sMods.contains(HullMods.TURRETGYROS))

        IncompatibleHullmodStripper.drainPending()

        verify(variant).removeMod(HullMods.ARMOREDWEAPONS)
        verify(variant).removeMod(HullMods.TURRETGYROS)
        verify(variant).removePermaMod(HullMods.ARMOREDWEAPONS)
        verify(variant).removePermaMod(HullMods.TURRETGYROS)
        verify(variant).removeSuppressedMod(HullMods.ARMOREDWEAPONS)
        verify(variant).removeSuppressedMod(HullMods.TURRETGYROS)
        assertFalse(sMods.contains(HullMods.TURRETGYROS))
        assertFalse(sModdedBuiltIns.contains(HullMods.ARMOREDWEAPONS))
    }

    @Test
    fun `非 xc_001 舰体 不入队不写乘区`() {
        IncompatibleHullmodStripper.drainPending()
        val hullSpec = mock(ShipHullSpecAPI::class.java)
        `when`(hullSpec.hullId).thenReturn("astd_xc_002")
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.hullSpec).thenReturn(hullSpec)
        `when`(variant.hasHullMod(HullMods.TURRETGYROS)).thenReturn(true)
        `when`(variant.sMods).thenReturn(linkedSetOf())
        `when`(variant.sModdedBuiltIns).thenReturn(linkedSetOf())
        `when`(variant.suppressedMods).thenReturn(mutableSetOf())
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.variant).thenReturn(variant)

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.CRUISER, stats, "test")
        IncompatibleHullmodStripper.drainPending()

        verify(variant, never()).removeMod(anyString())

        val map = StatMap()
        val stubbedStats = stubStats(map)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.hullSpec).thenReturn(hullSpec)
        `when`(ship.owner).thenReturn(0)
        `when`(ship.isHulk).thenReturn(false)
        `when`(ship.mutableStats).thenReturn(stubbedStats)

        DifficultyTuningImpl.installScaleForTests(5f)
        hullmod.advanceInCombat(ship, 0.016f)

        assertEquals(100f, map.ballisticFluxCost.computeEffective(100f), 1e-3f)
        assertEquals(100f, map.ballisticProjSpeed.modifiedValue, 1e-3f)
        assertEquals(100f, map.weaponTurnRate.computeEffective(100f), 1e-3f)
    }
}
