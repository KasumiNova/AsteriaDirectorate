package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class ASTDSuppressionModeSystemStats : BaseShipSystemScript() {

    companion object {
        // 硬辐能基线 2%/s 由原版 CSV 结算（f/s (base cap) + hardFlux），脚本只补爬坡超出基线的增量
        private const val CSV_BASELINE_FLUX_FRACTION = 0.02f
        private const val AFTERIMAGE_INTERVAL = 0.1f
        private const val ACTIVE_ELAPSED_KEY_PREFIX = ASTDArcProductionShipIds.STAT_SUPPRESSION_MODE + "_active_elapsed:"
        private const val AFTERIMAGE_TIMER_KEY_PREFIX = ASTDArcProductionShipIds.STAT_SUPPRESSION_MODE + "_afterimage:"
        private val JITTER_UNDER = Color(90, 165, 255, 155)
        private val JITTER = Color(90, 165, 255, 55)
        private val AFTERIMAGE_COLOR = Color(105, 210, 255, 96)
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        val engine = Global.getCombatEngine()
        if (ship != null && engine != null && !engine.isPaused) {
            renderSuppressionStreak(ship, engine, id, state)
        }

        val level = effectLevel.coerceIn(0f, 1f)
        if (level <= 0f) {
            unapply(stats, id)
            return
        }

        val values = SuppressionModeTuning.resolve(DifficultyTuningImpl, isPlayer = ship?.owner == 0)
        val speedManeuverMult = 1f - (1f - values.speedManeuverMult) * level
        stats.maxSpeed.modifyMult(id, speedManeuverMult)
        stats.acceleration.modifyMult(id, speedManeuverMult)
        stats.deceleration.modifyMult(id, speedManeuverMult)
        stats.maxTurnRate.modifyMult(id, speedManeuverMult)
        stats.turnAcceleration.modifyMult(id, speedManeuverMult)

        val weaponFluxMult = 1f - (1f - values.weaponFluxMult) * level
        stats.ballisticWeaponFluxCostMod.modifyMult(id, weaponFluxMult)
        stats.energyWeaponFluxCostMod.modifyMult(id, weaponFluxMult)
        stats.missileWeaponFluxCostMod.modifyMult(id, weaponFluxMult)

        val rangePercent = values.rangeBonusPercent * 100f * level
        stats.ballisticWeaponRangeBonus.modifyPercent(id, rangePercent)
        stats.energyWeaponRangeBonus.modifyPercent(id, rangePercent)
        stats.beamWeaponRangeBonus.modifyPercent(id, rangePercent)
        stats.missileWeaponRangeBonus.modifyPercent(id, rangePercent)

        val shieldDamageTakenMult = 1f - (1f - values.shieldDamageTakenMult) * level
        stats.shieldDamageTakenMult.modifyMult(id, shieldDamageTakenMult)

        val rofPercent = values.rofBonusPercent * 100f * level
        stats.ballisticRoFMult.modifyPercent(id, rofPercent)
        stats.energyRoFMult.modifyPercent(id, rofPercent)
        stats.missileRoFMult.modifyPercent(id, rofPercent)

        if (ship != null && engine != null && !engine.isPaused) {
            val elapsedKey = ACTIVE_ELAPSED_KEY_PREFIX + System.identityHashCode(ship)
            val activeSeconds = (engine.customData[elapsedKey] as? Float ?: 0f) + engine.elapsedInLastFrame
            engine.customData[elapsedKey] = activeSeconds
            settleHardFlux(ship, stats.fluxCapacity.baseValue, activeSeconds, engine.elapsedInLastFrame, level)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.maxSpeed.unmodifyMult(id)
        stats.acceleration.unmodifyMult(id)
        stats.deceleration.unmodifyMult(id)
        stats.maxTurnRate.unmodifyMult(id)
        stats.turnAcceleration.unmodifyMult(id)
        stats.ballisticWeaponFluxCostMod.unmodifyMult(id)
        stats.energyWeaponFluxCostMod.unmodifyMult(id)
        stats.missileWeaponFluxCostMod.unmodifyMult(id)
        stats.ballisticWeaponRangeBonus.unmodifyPercent(id)
        stats.energyWeaponRangeBonus.unmodifyPercent(id)
        stats.beamWeaponRangeBonus.unmodifyPercent(id)
        stats.missileWeaponRangeBonus.unmodifyPercent(id)
        stats.shieldDamageTakenMult.unmodifyMult(id)
        stats.ballisticRoFMult.unmodifyPercent(id)
        stats.energyRoFMult.unmodifyPercent(id)
        stats.missileRoFMult.unmodifyPercent(id)
        val ship = stats.entity as? ShipAPI ?: return
        ship.isJitterShields = false
        val engine = Global.getCombatEngine()
        engine?.customData?.remove(ACTIVE_ELAPSED_KEY_PREFIX + System.identityHashCode(ship))
        engine?.customData?.remove(AFTERIMAGE_TIMER_KEY_PREFIX + System.identityHashCode(ship))
    }

    fun settleHardFlux(ship: ShipAPI, baseMaxFlux: Float, activeSeconds: Float, elapsed: Float, level: Float) {
        if (elapsed <= 0f || level <= 0f || baseMaxFlux <= 0f) return
        val tracker = ship.fluxTracker ?: return
        val excessFraction = SuppressionModeTuning.hardFluxFractionPerSecond(activeSeconds) - CSV_BASELINE_FLUX_FRACTION
        val amount = baseMaxFlux * excessFraction.coerceAtLeast(0f) * elapsed * level
        if (amount > 0f) {
            tracker.increaseFlux(amount, true)
        }
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT -> "out"
            else -> return null
        }
        return ShipSystemStatsScript.StatusData(
            I18n[I18n.Categories.MOD, "system.suppression_mode.status.default.$suffix"],
            false,
        )
    }

    private fun renderSuppressionStreak(ship: ShipAPI, engine: CombatEngineAPI, id: String, state: ShipSystemStatsScript.State) {
        val timerKey = AFTERIMAGE_TIMER_KEY_PREFIX + System.identityHashCode(ship)
        val level = when (state) {
            ShipSystemStatsScript.State.IN -> 0.5f
            ShipSystemStatsScript.State.ACTIVE -> 1f
            ShipSystemStatsScript.State.OUT -> 0.45f
            else -> 0f
        }
        if (level > 0f) {
            ship.isJitterShields = false
            ship.setJitterUnder(id, JITTER_UNDER, level, 25, 0f, 7f)
            ship.setJitter(id, JITTER, 0.6f * level, 3, 0f, 0f)
        }
        if (state == ShipSystemStatsScript.State.IDLE) {
            engine.customData.remove(timerKey)
            return
        }

        val elapsed = (engine.customData[timerKey] as? Float ?: 0f) + engine.elapsedInLastFrame
        if (elapsed < AFTERIMAGE_INTERVAL) {
            engine.customData[timerKey] = elapsed
            return
        }
        engine.customData[timerKey] = elapsed - AFTERIMAGE_INTERVAL
        ASTDAfterimageEffect.spawn(
            engine,
            ASTDAfterimageEffect.Snapshot(
                spritePath = ship.hullSpec.spriteName,
                location = Vector2f(ship.location),
                facing = ship.facing,
                width = ship.spriteAPI.width,
                height = ship.spriteAPI.height,
                color = AFTERIMAGE_COLOR,
                startAlpha = 0.95f,
                duration = 0.5f,
                growth = 0.05f,
            ),
        )
        ASTDArcProductionVfx.incrementCounter(engine, ASTDArcProductionVfx.TELEMETRY_XC_103_SYSTEM_AFTERIMAGES)
    }
}
