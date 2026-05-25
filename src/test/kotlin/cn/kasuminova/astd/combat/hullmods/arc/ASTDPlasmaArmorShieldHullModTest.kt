package cn.kasuminova.astd.combat.hullmods.arc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDPlasmaArmorShieldHullModTest {

    @Test
    fun `armor bonus penalty is applied through refit visible ship stats`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt"),
        )
        val beforeCreation = source
            .substringAfter("override fun applyEffectsBeforeShipCreation")
            .substringBefore("override fun advanceInCombat")

        assertTrue(
            beforeCreation.contains("stats.variant?.hullSpec?.armorRating"),
            "refit and ship stat panels need the base armor from the variant hull spec during before-creation stat calculation",
        )
        assertTrue(
            beforeCreation.contains("correctPositiveArmorBonuses(stats, baseArmor, id)"),
            "positive armor bonus correction must be applied in applyEffectsBeforeShipCreation so the negative armor stat is visible in panels",
        )
        assertTrue(
            source.contains("private fun correctPositiveArmorBonuses(stats: MutableShipStatsAPI, baseArmor: Float, id: String)"),
            "combat and refit paths should share the same armor correction implementation",
        )
        assertTrue(
            source.contains("ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD"),
            "combat refresh should use the installed hullmod id as the same stat source used by refit panels",
        )
        assertFalse(
            source.contains("private fun correctPositiveArmorBonuses(ship: ShipAPI)"),
            "combat-only armor correction leaves the refit and ship stat panels without the negative armor modifier",
        )
        assertFalse(
            source.contains("ARMOR_BONUS_CORRECTION_ID"),
            "panel-visible armor correction should use the installed hullmod id as the stat source",
        )
    }
}
