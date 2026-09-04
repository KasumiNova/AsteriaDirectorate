package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDArcAdvancedFireControlHullMod : BaseHullMod() {

    companion object {
        private const val BASELINE_WEAPON_FLUX_MULT = 1.20f
        private const val RAMP_DURATION = 6f
        private const val TARGET_FULL_RAMP_WEAPON_FLUX_MULT = 0.60f
        private const val MAX_WEAPON_BOOST = 0.20f

        private const val BASELINE_STAT_ID = "astd_arc_advanced_fire_control_baseline"
        private const val RAMP_KEY = "astd_arc_advanced_fire_control_ramp:"

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(150, 232, 255),
            borderColor = Color(90, 180, 255),
            headerBackground = Color(20, 52, 82, 180),
            sectionBackground = Color(14, 36, 58, 120),
            accentColor = Color(60, 140, 220),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.ballisticWeaponFluxCostMod.modifyMult(BASELINE_STAT_ID, BASELINE_WEAPON_FLUX_MULT)
        stats.energyWeaponFluxCostMod.modifyMult(BASELINE_STAT_ID, BASELINE_WEAPON_FLUX_MULT)
        stats.beamWeaponFluxCostMult.modifyMult(BASELINE_STAT_ID, BASELINE_WEAPON_FLUX_MULT)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk || ship.hitpoints <= 0f) return

        val key = "$RAMP_KEY${System.identityHashCode(ship)}"
        val current = (engine.customData[key] as? Float ?: 0f).coerceIn(0f, 1f)
        val target = if (hasSustainedLargeFire(ship)) 1f else 0f
        val delta = amount / RAMP_DURATION
        val ramp = if (target > current) {
            (current + delta).coerceAtMost(1f)
        } else {
            (current - delta).coerceAtLeast(0f)
        }

        if (ramp <= 0.001f) {
            engine.customData.remove(key)
            unapplyCombatRamp(ship.mutableStats)
            return
        }

        engine.customData[key] = ramp
        applyCombatRamp(ship.mutableStats, ramp)
    }

    private fun applyCombatRamp(stats: MutableShipStatsAPI, ramp: Float) {
        val id = ASTDArcProductionShipIds.STAT_ARC_ADVANCED_FIRE_CONTROL
        val fluxMult = 1f + ((TARGET_FULL_RAMP_WEAPON_FLUX_MULT / BASELINE_WEAPON_FLUX_MULT) - 1f) * ramp
        val boostMult = 1f + MAX_WEAPON_BOOST * ramp

        stats.ballisticWeaponFluxCostMod.modifyMult(id, fluxMult)
        stats.energyWeaponFluxCostMod.modifyMult(id, fluxMult)
        stats.beamWeaponFluxCostMult.modifyMult(id, fluxMult)

        stats.ballisticRoFMult.modifyMult(id, boostMult)
        stats.energyRoFMult.modifyMult(id, boostMult)
        stats.ballisticProjectileSpeedMult.modifyMult(id, boostMult)
        stats.energyProjectileSpeedMult.modifyMult(id, boostMult)
        stats.ballisticWeaponDamageMult.modifyMult(id, boostMult)
        stats.energyWeaponDamageMult.modifyMult(id, boostMult)
        stats.beamWeaponDamageMult.modifyMult(id, boostMult)
    }

    private fun unapplyCombatRamp(stats: MutableShipStatsAPI) {
        val id = ASTDArcProductionShipIds.STAT_ARC_ADVANCED_FIRE_CONTROL
        stats.ballisticWeaponFluxCostMod.unmodify(id)
        stats.energyWeaponFluxCostMod.unmodify(id)
        stats.beamWeaponFluxCostMult.unmodify(id)
        stats.ballisticRoFMult.unmodify(id)
        stats.energyRoFMult.unmodify(id)
        stats.ballisticProjectileSpeedMult.unmodify(id)
        stats.energyProjectileSpeedMult.unmodify(id)
        stats.ballisticWeaponDamageMult.unmodify(id)
        stats.energyWeaponDamageMult.unmodify(id)
        stats.beamWeaponDamageMult.unmodify(id)
    }

    private fun hasSustainedLargeFire(ship: ShipAPI): Boolean {
        val weapons = try { ship.allWeapons } catch (_: Throwable) { null } ?: return false
        return weapons.any { weapon ->
            weapon.isEligibleLargeMainWeapon() &&
                (safeIsFiring(weapon) || safeChargeLevel(weapon) > 0.2f || safeBurstFireTime(weapon) > 0f)
        }
    }

    private fun WeaponAPI.isEligibleLargeMainWeapon(): Boolean {
        if (safeIsDecorative(this)) return false
        if (safeType(this) == WeaponAPI.WeaponType.MISSILE) return false
        if (safeSize(this) != WeaponAPI.WeaponSize.LARGE) return false
        return !safeHasHint(this, WeaponAPI.AIHints.PD) &&
            !safeHasHint(this, WeaponAPI.AIHints.PD_ONLY) &&
            !safeHasHint(this, WeaponAPI.AIHints.PD_ALSO) &&
            !safeHasHint(this, WeaponAPI.AIHints.ANTI_FTR)
    }

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.arcAdvancedFireControl.blocks,
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun safeIsFiring(weapon: WeaponAPI): Boolean = try { weapon.isFiring } catch (_: Throwable) { false }
    private fun safeChargeLevel(weapon: WeaponAPI): Float = try { weapon.chargeLevel } catch (_: Throwable) { 0f }
    private fun safeBurstFireTime(weapon: WeaponAPI): Float = try { weapon.burstFireTimeRemaining } catch (_: Throwable) { 0f }
    private fun safeIsDecorative(weapon: WeaponAPI): Boolean = try { weapon.isDecorative } catch (_: Throwable) { false }
    private fun safeType(weapon: WeaponAPI): WeaponAPI.WeaponType? = try { weapon.type } catch (_: Throwable) { null }
    private fun safeSize(weapon: WeaponAPI): WeaponAPI.WeaponSize? = try { weapon.size } catch (_: Throwable) { null }
    private fun safeHasHint(weapon: WeaponAPI, hint: WeaponAPI.AIHints): Boolean = try { weapon.hasAIHint(hint) } catch (_: Throwable) { false }
}
