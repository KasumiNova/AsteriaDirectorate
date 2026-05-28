package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.abs

class ASTDPlasmaArmorShieldHullMod : BaseHullMod() {

    companion object {
        private val FORBIDDEN_HULLMOD_IDS = setOf(HullMods.SHIELD_SHUNT, HullMods.HARDENED_SHIELDS)
        private const val MAX_ARMOR_PENALTY_FRACTION = 0.50f
        private const val SPIKE_THRESHOLD_MAX_FLUX_FRACTION = 0.05f
        private const val SPIKE_EXCESS_DAMAGE_MULT = 0.50f

        private const val FRONT_ARMOR_FRACTION = 0.30f
        private const val SIDE_ARMOR_FRACTION_MIN = 0.20f
        private const val SIDE_ARMOR_FRACTION_MAX = 0.30f
        private const val REAR_ARMOR_FRACTION_MIN = 0.10f
        private const val REAR_ARMOR_FRACTION_MAX = 0.20f

        private const val ENERGY_SHIELD_MULT = 0.85f
        private const val KINETIC_SHIELD_MULT = 0.67f
        private const val HE_SHIELD_MULT = 1.33f
        private const val FRAG_SHIELD_MULT = 1.20f
        private const val PLASMA_SHIELD_VISUAL_GRACE_SECONDS = 0.18f
        private const val SHIELD_ARC_BIAS_WEIGHT_DECAY = 0.90f
        private const val SHIELD_ARC_BIAS_MIN_WEIGHT = 0.08f
        private const val SHIELD_ARC_BIAS_ANGLE_KEY = "astd_plasma_shield_arc_bias_angle"
        private const val SHIELD_ARC_BIAS_WEIGHT_KEY = "astd_plasma_shield_arc_bias_weight"

        private val PREVENTED_DAMAGE_BLUE = Color(104, 212, 255, 235)
        private val PREVENTED_DAMAGE_PURPLE = Color(176, 112, 255, 238)

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(150, 232, 255),
            borderColor = Color(90, 180, 255),
            headerBackground = Color(20, 52, 82, 190),
            sectionBackground = Color(14, 36, 58, 135),
            accentColor = Color(88, 190, 255),
        )

        internal fun boostLevel(ship: ShipAPI): Float =
            (ship.customData[ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_BOOST_LEVEL] as? Float ?: 0f).coerceIn(0f, 1f)

        private fun directionalArmorFraction(ship: ShipAPI, hitPoint: Vector2f): Float {
            val hitAngle = Misc.getAngleInDegrees(ship.location, hitPoint)
            val relative = (((hitAngle - ship.facing) % 360f) + 540f) % 360f - 180f
            val offFront = abs(relative)
            return when {
                offFront <= 30f -> FRONT_ARMOR_FRACTION
                offFront <= 90f -> lerp(SIDE_ARMOR_FRACTION_MAX, SIDE_ARMOR_FRACTION_MIN, (offFront - 30f) / 60f)
                else -> lerp(REAR_ARMOR_FRACTION_MAX, REAR_ARMOR_FRACTION_MIN, (offFront - 90f) / 90f)
            }
        }

        private fun preventedDamageColor(ship: ShipAPI): Color =
            if (boostLevel(ship) > 0.05f) PREVENTED_DAMAGE_PURPLE else PREVENTED_DAMAGE_BLUE

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

        private fun recordShieldArcBias(ship: ShipAPI, hitPoint: Vector2f) {
            val hitAngle = Misc.getAngleInDegrees(ship.location, hitPoint)
            val existingWeight = (ship.customData[SHIELD_ARC_BIAS_WEIGHT_KEY] as? Float ?: 0f) * SHIELD_ARC_BIAS_WEIGHT_DECAY
            val existingAngle = ship.customData[SHIELD_ARC_BIAS_ANGLE_KEY] as? Float
            val newAngle = if (existingAngle == null || existingWeight <= SHIELD_ARC_BIAS_MIN_WEIGHT) {
                hitAngle
            } else {
                val shortest = MathUtils.getShortestRotation(existingAngle, hitAngle)
                existingAngle + shortest * (1f / (existingWeight + 1f)).coerceIn(0f, 1f)
            }
            ship.setCustomData(SHIELD_ARC_BIAS_ANGLE_KEY, newAngle)
            ship.setCustomData(SHIELD_ARC_BIAS_WEIGHT_KEY, (existingWeight + 1f).coerceAtMost(8f))
        }

        private fun preferredShieldArcAngle(ship: ShipAPI): Float? {
            val weight = (ship.customData[SHIELD_ARC_BIAS_WEIGHT_KEY] as? Float ?: 0f) * SHIELD_ARC_BIAS_WEIGHT_DECAY
            if (weight <= SHIELD_ARC_BIAS_MIN_WEIGHT) {
                ship.removeCustomData(SHIELD_ARC_BIAS_WEIGHT_KEY)
                ship.removeCustomData(SHIELD_ARC_BIAS_ANGLE_KEY)
                return null
            }
            ship.setCustomData(SHIELD_ARC_BIAS_WEIGHT_KEY, weight)
            return ship.customData[SHIELD_ARC_BIAS_ANGLE_KEY] as? Float
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stripForbiddenHullMods(stats.variant)
        stats.energyShieldDamageTakenMult.modifyMult(id, ENERGY_SHIELD_MULT)
        stats.kineticShieldDamageTakenMult.modifyMult(id, KINETIC_SHIELD_MULT)
        stats.highExplosiveShieldDamageTakenMult.modifyMult(id, HE_SHIELD_MULT)
        stats.fragmentationShieldDamageTakenMult.modifyMult(id, FRAG_SHIELD_MULT)
        val baseArmor = stats.variant?.hullSpec?.armorRating ?: return
        applyFixedMaxArmorPenalty(stats, baseArmor, id)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        stripForbiddenHullMods(ship.variant)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (!ASTDArcAuraUtil.isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)) return
        maintainShieldVisualsEvenWhenPaused(ship, engine)
        if (engine.isPaused || ship.isHulk || !ship.isAlive) return

        val shield = ship.shield
        if (shield?.isOn == true) {
            ASTDArcProductionVfx.setCounter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_OPEN, 1)
            ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, visualBoostLevel(ship))
        }
        if (!ship.hasListenerOfClass(PlasmaArmorShieldListener::class.java)) {
            ship.addListener(PlasmaArmorShieldListener(ship))
        }

        applyFixedMaxArmorPenalty(
            ship.mutableStats,
            ship.armorGrid.armorRating,
            ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
        )
        renderOpenShieldArcs(ship, engine, amount, visualBoostLevel(ship))
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) return false
        return ASTDArcAuraUtil.isArcProductionHull(ship, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)
    }

    override fun getUnapplicableReason(ship: ShipAPI): String? {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) {
            return I18n[I18n.Categories.MOD, "ui.hullmod.plasma_armor_shield.incompatible_shunt"]
        }
        return null
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.plasmaArmorShield.blocks,
            showTitle = false,
        )
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun hasForbiddenHullMod(variant: ShipVariantAPI): Boolean =
        FORBIDDEN_HULLMOD_IDS.any { forbiddenId ->
            variant.hasHullMod(forbiddenId) ||
                variant.getPermaMods().contains(forbiddenId) ||
                variant.getSMods().contains(forbiddenId) ||
                variant.getSModdedBuiltIns().contains(forbiddenId)
        }

    private fun stripForbiddenHullMods(variant: ShipVariantAPI?) {
        variant ?: return
        FORBIDDEN_HULLMOD_IDS.forEach { forbiddenId ->
            variant.removeMod(forbiddenId)
            variant.removePermaMod(forbiddenId)
            variant.getSMods().remove(forbiddenId)
            variant.getSModdedBuiltIns().remove(forbiddenId)
            variant.removeSuppressedMod(forbiddenId)
        }
    }

    private fun applyFixedMaxArmorPenalty(stats: MutableShipStatsAPI, baseArmor: Float, id: String) {
        stats.armorBonus.modifyMult(id, 1f - MAX_ARMOR_PENALTY_FRACTION)
    }

    private fun visualBoostLevel(ship: ShipAPI): Float {
        val stored = boostLevel(ship)
        val system = ship.system ?: return stored
        val systemActive = system.isActive || system.isOn || system.isStateActive
        val effectLevel = if (systemActive) system.effectLevel.coerceIn(0f, 1f) else 0f
        return maxOf(stored, effectLevel)
    }

    private fun maintainShieldVisualsEvenWhenPaused(ship: ShipAPI, engine: CombatEngineAPI) {
        val shield = ship.shield ?: return
        val now = engine.getTotalElapsedTime(false)
        val graceUntil = ship.customData["astd_plasma_shield_visual_grace"] as? Float ?: 0f
        val shouldMaintain = shield.isOn || now <= graceUntil
        if (!shouldMaintain) return
        ASTDArcProductionVfx.setCounter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_OPEN, 1)
        ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, visualBoostLevel(ship))
    }

    private fun renderOpenShieldArcs(ship: ShipAPI, engine: CombatEngineAPI, amount: Float, boostLevel: Float) {
        val shield = ship.shield ?: return
        if (!shield.isOn) return
        var timer = (ship.customData["astd_plasma_shield_arc_timer"] as? Float ?: 0f) - amount
        if (timer > 0f) {
            ship.setCustomData("astd_plasma_shield_arc_timer", timer)
            return
        }
        val interval = if (boostLevel > 0.05f) MathUtils.getRandomNumberInRange(0.25f, 0.5f) else MathUtils.getRandomNumberInRange(0.5f, 1f)
        timer = interval
        ship.setCustomData("astd_plasma_shield_arc_timer", timer)

        ASTDArcProductionVfx.emitPlasmaShieldArc(engine, ship, boostLevel > 0.05f, preferredShieldArcAngle(ship))
    }

    private class PlasmaArmorShieldListener(
        private val ship: ShipAPI,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (ship.isHulk || !ship.isAlive) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            val dmg = damage ?: return null
            val hitPoint = point ?: return null
            if (target !== ship || ship.isHulk || !ship.isAlive) return null
            if (!shieldHit && !isArmorHit(hitPoint)) return null

            val finalMaxArmor = ship.mutableStats.armorBonus.computeEffective(ship.armorGrid.armorRating).coerceAtLeast(1f)
            val boostMult = 1f + boostLevel(ship)
            val armor = finalMaxArmor * directionalArmorFraction(ship, hitPoint) * boostMult
            val incomingBeforeSpikeReduction = effectiveDamageAmount(param, dmg).coerceAtLeast(0f)
            val incoming = applyBoostedShieldSpikeReduction(dmg, incomingBeforeSpikeReduction, shieldHit)
            val reduction = ASTDArmorDamageReduction.compute(
                damageAmount = incoming,
                hitStrength = ASTDArmorDamageReduction.hitStrength(dmg.type, dmg.baseDamage, isBeamDamage(param, dmg)),
                armorValue = armor,
                minArmorValue = ship.armorGrid.armorRating * ship.mutableStats.minArmorFraction.modifiedValue,
                effectiveArmorMult = ship.mutableStats.effectiveArmorBonus.mult,
                maxArmorDamageReduction = ship.mutableStats.maxArmorDamageReduction.modifiedValue,
            )
            val mult = reduction.damageMultiplier
            if (mult < 0.999f) {
                dmg.modifier.modifyMult(ASTDArcProductionShipIds.STAT_PLASMA_ARMOR_SHIELD, mult)
                Global.getCombatEngine()?.addFloatingDamageText(hitPoint, reduction.preventedDamage, preventedDamageColor(ship), ship, null)
            }

            if (shieldHit) {
                recordShieldArcBias(ship, hitPoint)
                Global.getCombatEngine()?.let { engine ->
                    val boosted = boostLevel(ship)
                    ASTDArcProductionVfx.emitPlasmaShieldHit(engine, ship, hitPoint, boosted)
                }
            }

            return null
        }

        private fun applyBoostedShieldSpikeReduction(dmg: DamageAPI, incoming: Float, shieldHit: Boolean): Float {
            if (!shieldHit || boostLevel(ship) <= 0.05f || incoming <= 0f) return incoming
            val threshold = ship.fluxTracker.maxFlux * SPIKE_THRESHOLD_MAX_FLUX_FRACTION
            if (threshold <= 0f || incoming <= threshold) return incoming
            val reduced = threshold + (incoming - threshold) * SPIKE_EXCESS_DAMAGE_MULT
            val mult = (reduced / incoming).coerceIn(0f, 1f)
            if (mult < 0.999f) {
                dmg.modifier.modifyMult(ASTDArcProductionShipIds.STAT_PLASMA_ARMOR_SHIELD_SPIKE, mult)
            }
            return reduced
        }

        private fun isArmorHit(point: Vector2f): Boolean {
            val cell = ship.armorGrid.getCellAtLocation(point) ?: return false
            if (cell.size < 2) return false
            val armor = try { ship.armorGrid.getArmorValue(cell[0], cell[1]) } catch (_: Throwable) { 0f }
            return armor > 1f
        }

        private fun effectiveDamageAmount(param: Any?, damage: DamageAPI): Float {
            val duration = if (damage.isDps) damage.dpsDuration.coerceAtLeast(0f) else 1f
            return damage.damage.coerceAtLeast(0f) * duration
        }

        private fun isBeamDamage(param: Any?, damage: DamageAPI): Boolean =
            param is BeamAPI || damage.isDps
    }
}
