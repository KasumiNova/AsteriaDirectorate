package cn.kasuminova.astd.combat.hullmods.arc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import cn.kasuminova.astd.testutil.RepoLayout

class ASTDPlasmaArmorShieldHullModTest {

    @Test
    fun `armor bonus penalty is applied through refit visible ship stats`() {
        val source = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt")!!,
        )
        val beforeCreation = source
            .substringAfter("override fun applyEffectsBeforeShipCreation")
            .substringBefore("override fun advanceInCombat")

        assertTrue(
            beforeCreation.contains("stats.variant?.hullSpec?.armorRating"),
            "refit and ship stat panels need the base armor from the variant hull spec during before-creation stat calculation",
        )
        assertTrue(
            beforeCreation.contains("applyFixedMaxArmorPenalty(stats, baseArmor, id)"),
            "fixed max armor penalty must be applied in applyEffectsBeforeShipCreation so the negative armor stat is visible in panels",
        )
        assertTrue(
            source.contains("private const val MAX_ARMOR_PENALTY_FRACTION = 0.50f"),
            "plasma armor shield should use a fixed 50% max armor penalty",
        )
        assertTrue(
            source.contains("private fun applyFixedMaxArmorPenalty(stats: MutableShipStatsAPI, baseArmor: Float, id: String)"),
            "combat and refit paths should share the same fixed max armor penalty implementation",
        )
        assertTrue(
            source.contains("stats.armorBonus.modifyMult(id, 1f - MAX_ARMOR_PENALTY_FRACTION)"),
            "fixed max armor penalty must be multiplicative so all armor bonuses are halved regardless of hullmod apply order",
        )
        assertFalse(
            source.contains("stats.armorBonus.modifyFlat(id, -effectiveArmor * MAX_ARMOR_PENALTY_FRACTION)"),
            "flat max armor penalties are order-dependent when vanilla armor hullmods apply before or after this hullmod",
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
    fun `plasma armor shield no longer special cases vanilla armor hullmods`() {
        val source = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt")!!,
        )

        assertFalse(source.contains("correctKnownArmorHullModBonuses"), "fixed max armor penalty should not depend on known vanilla armor hullmod ids")
        assertFalse(source.contains("knownArmorBonuses"), "fixed max armor penalty should not keep a known armor hullmod table")
        assertFalse(source.contains("armorHullSizeBonus"), "fixed max armor penalty should not hard-code heavy armor hull size values")
        assertFalse(source.contains("HullMods.HEAVYARMOR to"), "heavy armor should not be special-cased by the penalty")
        assertFalse(source.contains("HullMods.ARMOREDWEAPONS to"), "armored weapon mounts should not be special-cased by the penalty")
        assertFalse(
            source.contains("val desired = baseArmor + excess * ARMOR_BONUS_GAIN_FRACTION"),
            "old bonus-only correction is obsolete after switching to a fixed max armor penalty",
        )
    }

    @Test
    fun `plasma armor shield strips forbidden shield shunt in creation paths`() {
        val source = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt")!!,
        )

        assertTrue(source.contains("FORBIDDEN_HULLMOD_IDS = setOf("), "forbidden hullmods should be centralized")
        assertTrue(source.contains("HullMods.SHIELD_SHUNT"), "plasma armor shield must explicitly forbid vanilla shield shunt")
        assertTrue(source.contains("HullMods.HARDENED_SHIELDS"), "plasma armor shield must explicitly forbid vanilla hardened shields")
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
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt")!!,
        )

        assertTrue(source.contains("directionalArmorFraction(ship, hitPoint)"), "shield and armor mitigation should be based on hit direction")
        assertTrue(source.contains("FRONT_ARMOR_FRACTION = 0.30f"), "front 60 degree shield should get 30% armor calculation")
        assertTrue(source.contains("SIDE_ARMOR_FRACTION_MIN = 0.20f"), "side/front arc should start at 20% armor calculation")
        assertTrue(source.contains("SIDE_ARMOR_FRACTION_MAX = 0.30f"), "side/front arc should reach 30% armor calculation")
        assertTrue(source.contains("REAR_ARMOR_FRACTION_MIN = 0.10f"), "rear arc should reach 10% armor calculation at the stern")
        assertTrue(source.contains("REAR_ARMOR_FRACTION_MAX = 0.20f"), "rear arc should start at 20% armor calculation near side/front")
        assertTrue(source.contains("val boostMult = 1f + boostLevel(ship)"), "system boost should double directional armor calculation at full level")
        assertTrue(source.contains("MAX_ARMOR_PENALTY_FRACTION = 0.50f"), "maximum armor should be reduced by 50%")
        assertTrue(source.contains("!shieldHit"), "armor hits should also use the extra directional armor calculation")
        assertTrue(source.contains("addFloatingDamageText"), "prevented damage should be shown with vanilla floating damage text")
        assertTrue(source.contains("preventedDamageColor(ship)"), "prevented damage text should use shield-blue or boost-purple colors")
        assertFalse(source.contains("ARMOR_KINETIC_MULT"), "plasma armor shield should no longer apply armor damage type modifiers")
        assertFalse(source.contains("kineticArmorDamageTakenMult"), "armor damage should no longer get a kinetic-specific stat modifier")
        assertFalse(source.contains("plasmaGridState"), "virtual sector state should be removed from the hullmod")
        assertFalse(source.contains("ASTDPlasmaShieldGridState"), "virtual sector state should not drive plasma armor shield anymore")
        assertFalse(source.contains("maintainPlayerHud"), "old sector HUD should be removed with the sector mechanic")
        assertFalse(source.contains("applyAbsorbedDamage"), "directional armor calculation should not consume virtual armor")
    }

    @Test
    fun `plasma armor shield delegates mitigation to vanilla style armor reduction formula`() {
        val source = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt")!!,
        )
        val formula = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDArmorDamageReduction.kt")!!,
        )

        assertTrue(source.contains("ASTDArmorDamageReduction.compute("), "combat listener should use the shared tested armor formula")
        assertTrue(source.contains("ASTDArmorDamageReduction.hitStrength(dmg.type, dmg.baseDamage, isBeamDamage(param, dmg))"), "hit strength must use base weapon damage and beam half-strength rule")
        assertTrue(source.contains("ship.mutableStats.maxArmorDamageReduction.modifiedValue"), "max armor damage reduction cap must come from vanilla ship stats")
        assertTrue(source.contains("ship.mutableStats.minArmorFraction.modifiedValue"), "minimum armor floor must come from vanilla ship stats")
        assertTrue(source.contains("ship.mutableStats.effectiveArmorBonus.mult"), "effective armor bonus must affect the armor calculation")
        assertFalse(source.contains("armorStyleDamageMultiplier"), "old local approximation should not bypass the tested formula")
        assertFalse(source.contains("coerceIn(0.05f, 1f)"), "mitigation cap must not be hard-coded to 95% reduction")
        assertTrue(formula.contains("effectiveHitStrength / (effectiveHitStrength + effectiveArmor)"), "formula should remain hit strength over hit strength plus armor")
        assertTrue(formula.contains("1f - maxArmorDamageReduction.coerceIn(0f, 1f)"), "formula should clamp with the vanilla max armor damage reduction stat")
        assertTrue(formula.contains("DamageType.HIGH_EXPLOSIVE -> 2f"), "high explosive should use vanilla 200% armor effectiveness")
        assertTrue(formula.contains("DamageType.KINETIC -> 0.5f"), "kinetic should use vanilla 50% armor effectiveness")
        assertTrue(formula.contains("DamageType.FRAGMENTATION -> 0.25f"), "fragmentation should use vanilla 25% armor effectiveness")
    }

    @Test
    fun `plasma armor shield boost reduces shield damage spikes before directional armor calculation`() {
        val source = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt")!!,
        )

        assertTrue(source.contains("SPIKE_THRESHOLD_MAX_FLUX_FRACTION = 0.05f"), "boosted shield spike threshold should be 5% max flux")
        assertTrue(source.contains("SPIKE_EXCESS_DAMAGE_MULT = 0.50f"), "boosted shield spike excess damage should be reduced by 50%")
        assertTrue(source.contains("applyBoostedShieldSpikeReduction"), "shield spike damage reduction should be a named helper")
        assertTrue(
            source.contains("val incoming = applyBoostedShieldSpikeReduction(dmg, incomingBeforeSpikeReduction, shieldHit)"),
            "directional armor calculation should receive the post-spike-reduction raw damage",
        )
        assertTrue(
            source.contains("dmg.modifier.modifyMult(ASTDArcProductionShipIds.STAT_PLASMA_ARMOR_SHIELD_SPIKE, mult)"),
            "boosted shield spike reduction must modify the DamageAPI before later plasma armor mitigation",
        )
        assertTrue(
            source.contains("ship.fluxTracker.maxFlux * SPIKE_THRESHOLD_MAX_FLUX_FRACTION"),
            "shield spike threshold should use ship max flux",
        )
    }

    @Test
    fun `plasma shield arcs remember recent hit directions and maintain colors through pause and shutdown frames`() {
        val source = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt")!!,
        )

        assertTrue(source.contains("recordShieldArcBias(ship, hitPoint)"), "shield hits should feed the later decorative arc direction")
        assertTrue(source.contains("preferredShieldArcAngle(ship)"), "decorative shield arcs should prefer recent hit directions")
        assertTrue(
            source.contains("ASTDArcProductionVfx.emitPlasmaShieldArc(engine, ship, boostLevel > 0.05f, preferredShieldArcAngle(ship))"),
            "shield arc VFX should receive the biased combat direction instead of being fully random",
        )
        assertTrue(source.contains("PLASMA_SHIELD_VISUAL_GRACE_SECONDS"), "shield colors should be maintained briefly while the shield is closing")
        assertTrue(source.contains("astd_plasma_shield_visual_grace"), "shield color grace state should be stored per ship")
        assertFalse(
            source.substringAfter("private fun maintainShieldVisualsEvenWhenPaused").substringBefore("private fun renderOpenShieldArcs")
                .contains("if (!shield.isOn) return"),
            "paused-frame shield color maintenance must not return immediately on a closing shield",
        )
    }
}
