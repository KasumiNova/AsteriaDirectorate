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

    @Test
    fun `plasma armor shield strips forbidden shield shunt in creation paths`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt"),
        )

        assertTrue(source.contains("FORBIDDEN_HULLMOD_IDS = setOf("), "forbidden hullmods should be centralized")
        assertTrue(source.contains("HullMods.SHIELD_SHUNT"), "plasma armor shield must explicitly forbid vanilla shield shunt")
        assertTrue(
            source.contains("override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String)"),
            "invalid refit or save states must be cleaned after ship creation",
        )
        assertTrue(
            source.contains("private fun stripForbiddenHullMods(variant: ShipVariantAPI?)"),
            "forbidden hullmod stripping should be shared by refit and combat creation paths",
        )
        assertTrue(source.contains("variant.removeMod(forbiddenId)"), "ordinary installed forbidden hullmods must be removed")
        assertTrue(source.contains("variant.removePermaMod(forbiddenId)"), "built-in or S-modded forbidden hullmods must be removed")
        assertTrue(source.contains("variant.getSMods().remove(forbiddenId)"), "S-mod bookkeeping must be cleared with the forbidden hullmod")
        assertTrue(source.contains("variant.getSModdedBuiltIns().remove(forbiddenId)"), "built-in S-mod bookkeeping must be cleared with the forbidden hullmod")
        assertFalse(
            source.contains("MagicIncompatibleHullmods"),
            "do not depend on warning hullmod fallback for hard-forbidden plasma arch hullmods",
        )
    }

    @Test
    fun `plasma armor shield uses directional armor calculation without virtual shield sectors`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt"),
        )

        assertTrue(source.contains("directionalArmorFraction(ship, hitPoint)"), "shield and armor mitigation should be based on hit direction")
        assertTrue(source.contains("FRONT_ARMOR_FRACTION = 0.15f"), "front 60 degree shield should get 15% armor calculation")
        assertTrue(source.contains("SIDE_ARMOR_FRACTION_MIN = 0.10f"), "side/front arc should start at 10% armor calculation")
        assertTrue(source.contains("SIDE_ARMOR_FRACTION_MAX = 0.15f"), "side/front arc should reach 15% armor calculation")
        assertTrue(source.contains("REAR_ARMOR_FRACTION_MIN = 0.05f"), "rear arc should start at 5% armor calculation")
        assertTrue(source.contains("REAR_ARMOR_FRACTION_MAX = 0.10f"), "rear arc should reach 10% armor calculation")
        assertTrue(source.contains("val boostMult = 1f + boostLevel(ship)"), "system boost should double directional armor calculation at full level")
        assertTrue(source.contains("!shieldHit"), "armor hits should also use the extra directional armor calculation")
        assertTrue(source.contains("addFloatingDamageText"), "prevented damage should be shown with vanilla floating damage text")
        assertTrue(source.contains("preventedDamageColor(ship)"), "prevented damage text should use shield-blue or boost-purple colors")
        assertFalse(source.contains("plasmaGridState"), "virtual sector state should be removed from the hullmod")
        assertFalse(source.contains("ASTDPlasmaShieldGridState"), "virtual sector state should not drive plasma armor shield anymore")
        assertFalse(source.contains("maintainPlayerHud"), "old sector HUD should be removed with the sector mechanic")
        assertFalse(source.contains("applyAbsorbedDamage"), "directional armor calculation should not consume virtual armor")
    }
}
