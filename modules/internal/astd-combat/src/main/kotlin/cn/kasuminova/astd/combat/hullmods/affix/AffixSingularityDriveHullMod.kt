package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod.SingularityBurstSubsystem.Companion.DECIDE_INTERVAL
import cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod.SingularityBurstSubsystem.Companion.ENGAGE_RANGE_MULT
import cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod.SingularityBurstSubsystem.Companion.FLUX_TRIGGER_LEVEL
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.magiclib.subsystems.MagicSubsystem
import org.magiclib.subsystems.MagicSubsystemsManager

class AffixSingularityDriveHullMod : BaseHullMod() {

    companion object {
        val PEAK_DURATION = ScalingEntry(v1 = 4.0f, v2 = 7.0f, v5 = 10.0f)
        val WEAPON_FLUX_COST = ScalingEntry(v1 = 0.80f, v2 = 0.70f, v5 = 0.60f)
        val OVERLOAD_TIME = ScalingEntry(v1 = 0.75f, v2 = 0.50f, v5 = 0.25f)

        private const val ENV_CANCEL_ID = "astd_affix_singularity_env_cancel"
        private const val PEAK_BONUS_ID = "astd_affix_singularity_peak"
        private const val SUBSYSTEM_INSTALLED_KEY = "astd_affix_singularity_subsystem"
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val tuning = AffixShared.tuning
        val weaponFlux = tuning.value(WEAPON_FLUX_COST)
        stats.ballisticWeaponFluxCostMod.modifyMult(id, weaponFlux)
        stats.energyWeaponFluxCostMod.modifyMult(id, weaponFlux)
        stats.overloadTimeMod.modifyMult(id, tuning.value(OVERLOAD_TIME))
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (ship.customData[SUBSYSTEM_INSTALLED_KEY] != true) {
            ship.setCustomData(SUBSYSTEM_INSTALLED_KEY, true)
            MagicSubsystemsManager.addSubsystemToShip(ship, SingularityBurstSubsystem(ship))
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ship.isAlive || ship.isHulk) return
        val stats = ship.mutableStats

        stats.peakCRDuration.unmodifyMult(ENV_CANCEL_ID)
        stats.peakCRDuration.unmodifyMult(PEAK_BONUS_ID)
        val base = ship.hullSpec?.noCRLossTime ?: 0f
        if (base > 0f) {
            val effectiveWithoutOurs = stats.peakCRDuration.computeEffective(base)
            if (effectiveWithoutOurs < base) {
                stats.peakCRDuration.modifyMult(ENV_CANCEL_ID, base / effectiveWithoutOurs)
            }
            stats.peakCRDuration.modifyMult(PEAK_BONUS_ID, AffixShared.tuning.value(PEAK_DURATION))
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_singularity_drive.desc")
        }
    }

    class SingularityBurstSubsystem(ship: ShipAPI) : MagicSubsystem(ship) {

        private val timeFlowBonus: Float = AffixShared.tuning.value(TIME_FLOW_BONUS)

        private val decideInterval = IntervalUtil(DECIDE_INTERVAL, DECIDE_INTERVAL)

        override fun getBaseActiveDuration(): Float = ACTIVE_DURATION

        override fun getBaseCooldownDuration(): Float = COOLDOWN_DURATION

        override fun getDisplayText(): String = I18n[AffixRegistry.CATEGORY, "affix.astd_affix_singularity_drive.subsystem"]

        override fun shouldActivateAI(amount: Float): Boolean {
            decideInterval.advance(amount)
            if (!decideInterval.intervalElapsed()) return false
            if (ship.fluxTracker.isOverloadedOrVenting) return false

            val target = ship.shipTarget
            if (target != null && target.isAlive && !target.isHulk) {
                val engageRange = longestWeaponRange() * ENGAGE_RANGE_MULT
                if (MathUtils.getDistance(ship, target) <= engageRange) return true
            }

            if (incomingThreat()) return true

            return ship.fluxTracker.fluxLevel >= FLUX_TRIGGER_LEVEL
        }

        override fun advance(amount: Float, isPaused: Boolean) {
            if (isPaused) return
            if (state == State.ACTIVE) {
                val progress = (stateInterval.elapsed / stateInterval.intervalDuration).coerceIn(0f, 1f)
                stats.timeMult.modifyMult(MOD_ID, 1f + timeFlowBonus * progress)
            } else {
                stats.timeMult.unmodifyMult(MOD_ID)
            }
        }

        private fun longestWeaponRange(): Float =
            ship.allWeapons
                .asSequence()
                .filter { !it.isDecorative && it.type != WeaponAPI.WeaponType.MISSILE }
                .map { it.range }
                .filter { it > 0f }
                .maxOrNull() ?: 700f

        private fun incomingThreat(): Boolean {
            val engine = Global.getCombatEngine() ?: return false
            return engine.projectiles.any { projectile ->
                projectile is DamagingProjectileAPI &&
                        projectile.owner != ship.owner &&
                        !projectile.didDamage() &&
                        MathUtils.getDistance(ship.location, projectile.location) <= THREAT_RANGE &&
                        projectile.damageAmount + projectile.empAmount * 0.25f >= THREAT_MIN_DAMAGE
            }
        }

        companion object {
            val TIME_FLOW_BONUS = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)

            const val ACTIVE_DURATION = 10f
            const val COOLDOWN_DURATION = 20f

            const val DECIDE_INTERVAL = 0.25f

            const val ENGAGE_RANGE_MULT = 1.1f

            const val FLUX_TRIGGER_LEVEL = 0.35f

            const val THREAT_RANGE = 700f
            const val THREAT_MIN_DAMAGE = 250f

            private const val MOD_ID = "astd_affix_singularity_burst"
        }
    }
}
