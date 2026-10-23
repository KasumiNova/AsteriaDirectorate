package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedEcmNetworkHullModTest {

    private val hullmod = AdvancedEcmNetworkHullMod()

    private fun zw001Variant(): ShipVariantAPI {
        val hullSpec = mock(ShipHullSpecAPI::class.java)
        `when`(hullSpec.hullId).thenReturn("astd_zw_001")
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.hullSpec).thenReturn(hullSpec)
        `when`(variant.sMods).thenReturn(linkedSetOf())
        `when`(variant.sModdedBuiltIns).thenReturn(linkedSetOf())
        `when`(variant.suppressedMods).thenReturn(mutableSetOf())
        return variant
    }

    private fun statsOf(variant: ShipVariantAPI): MutableShipStatsAPI {
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.variant).thenReturn(variant)
        return stats
    }

    @Test
    fun `互斥清理 创建前只检测入队 drain后五路全清`() {
        IncompatibleHullmodStripper.drainPending()
        val sMods = linkedSetOf(HullMods.ECM)
        val sModdedBuiltIns = linkedSetOf(HullMods.ECCM)
        val variant = zw001Variant()
        `when`(variant.sMods).thenReturn(sMods)
        `when`(variant.sModdedBuiltIns).thenReturn(sModdedBuiltIns)

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.CRUISER, statsOf(variant), "test")

        verify(variant, never()).removeMod(HullMods.ECM)
        verify(variant, never()).removeMod(HullMods.ECCM)
        verify(variant, never()).removePermaMod(HullMods.ECM)
        assertTrue(sMods.contains(HullMods.ECM), "创建前只入队，S-mod 集合不变")

        IncompatibleHullmodStripper.drainPending()

        verify(variant).removeMod(HullMods.ECM)
        verify(variant).removeMod(HullMods.ECCM)
        verify(variant).removePermaMod(HullMods.ECM)
        verify(variant).removePermaMod(HullMods.ECCM)
        verify(variant).removeSuppressedMod(HullMods.ECM)
        assertTrue(sMods.isEmpty(), "S-mod 集合中的冲突船插被清空")
        assertTrue(sModdedBuiltIns.isEmpty(), "S-modded built-in 集合中的冲突船插被清空")
    }

    @Test
    fun `互斥清理 创建后入队并立即drain`() {
        IncompatibleHullmodStripper.drainPending()
        val variant = zw001Variant()
        `when`(variant.hasHullMod(HullMods.ECCM)).thenReturn(true)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.variant).thenReturn(variant)

        hullmod.applyEffectsAfterShipCreation(ship, "test")

        verify(variant).removeMod(HullMods.ECCM)
        verify(variant).removePermaMod(HullMods.ECCM)
        verify(variant).removeSuppressedMod(HullMods.ECCM)
    }

    @Test
    fun `互斥清理 非决明级变体不入队`() {
        IncompatibleHullmodStripper.drainPending()
        val hullSpec = mock(ShipHullSpecAPI::class.java)
        `when`(hullSpec.hullId).thenReturn("astd_zw_002")
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.hullSpec).thenReturn(hullSpec)
        `when`(variant.hasHullMod(HullMods.ECM)).thenReturn(true)

        hullmod.applyEffectsBeforeShipCreation(ShipAPI.HullSize.CRUISER, statsOf(variant), "test")
        IncompatibleHullmodStripper.drainPending()

        verify(variant, never()).removeMod(HullMods.ECM)
        verify(variant, never()).removePermaMod(HullMods.ECM)
    }

    @Test
    fun `软提示 装有冲突电子战组件时不可安装 洁净决明级可安装`() {
        val conflictVariant = zw001Variant()
        `when`(conflictVariant.hasHullMod(HullMods.ECM)).thenReturn(true)
        val conflictShip = mock(ShipAPI::class.java)
        `when`(conflictShip.variant).thenReturn(conflictVariant)
        assertFalse(hullmod.isApplicableToShip(conflictShip))

        val cleanVariant = zw001Variant()
        val cleanHullSpec = cleanVariant.hullSpec
        val cleanShip = mock(ShipAPI::class.java)
        `when`(cleanShip.variant).thenReturn(cleanVariant)
        `when`(cleanShip.hullSpec).thenReturn(cleanHullSpec)
        assertTrue(hullmod.isApplicableToShip(cleanShip))
    }
}
