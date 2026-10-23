package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.effect.joint.BurstFlowTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class ASTDBurstFlowSystemStats : BaseShipSystemScript() {

    companion object {
        private const val AFTERIMAGE_INTERVAL = 0.1f
        private const val PLAYER_TIME_MULT_OWNER_KEY = "astd_burst_flow_player_time_mult_owner"
        private const val AFTERIMAGE_TIMER_KEY_PREFIX = "astd_burst_flow_afterimage:"
        private const val MOMENTUM_LATCH_KEY_PREFIX = "astd_burst_flow_momentum:"

        private const val MIN_SPEED_FOR_DIRECTION = 5f
        private val AFTERIMAGE_COLOR = Color(105, 210, 255, 96)
        private val JITTER_UNDER = Color(90, 165, 255, 155)
        private val JITTER = Color(90, 165, 255, 55)
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        val level = if (state == ShipSystemStatsScript.State.ACTIVE) 1f else 0f
        val engine = Global.getCombatEngine()
        if (ship != null && engine != null && !engine.isPaused) {
            renderBurstStreak(ship, id, state)
        }
        if (level <= 0f) {
            unapply(stats, id)
            return
        }

        val values = BurstFlowTuning.resolve(DifficultyTuningImpl, isPlayer = ship?.owner == 0)
        stats.timeMult.modifyMult(id, values.timeMult)
        stats.acceleration.modifyMult(id, values.speedManeuverMult)
        stats.deceleration.modifyMult(id, values.speedManeuverMult)
        stats.maxTurnRate.modifyMult(id, 0f)
        stats.turnAcceleration.modifyMult(id, 0f)
        stats.fluxDissipation.modifyMult(id, values.fluxDissipationMult)
        stats.ballisticAmmoRegenMult.modifyMult(id, values.ammoRegenMult)
        stats.energyAmmoRegenMult.modifyMult(id, values.ammoRegenMult)
        stats.missileAmmoRegenMult.modifyMult(id, values.ammoRegenMult)

        if (ship != null && engine != null && !engine.isPaused) {
            applyMomentumOnce(engine, ship, stats, values)
        }

        if (ship != null && engine != null && !engine.isPaused && ship === engine.playerShip) {
            engine.timeMult.modifyMult("${id}_player", 1f / values.timeMult)
            engine.customData[PLAYER_TIME_MULT_OWNER_KEY] = System.identityHashCode(ship)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.timeMult.unmodifyMult(id)
        stats.acceleration.unmodifyMult(id)
        stats.deceleration.unmodifyMult(id)
        stats.maxTurnRate.unmodifyMult(id)
        stats.turnAcceleration.unmodifyMult(id)
        stats.fluxDissipation.unmodifyMult(id)
        stats.ballisticAmmoRegenMult.unmodifyMult(id)
        stats.energyAmmoRegenMult.unmodifyMult(id)
        stats.missileAmmoRegenMult.unmodifyMult(id)
        val ship = stats.entity as? ShipAPI
        val engine = Global.getCombatEngine()
        if (ship != null && engine?.customData?.get(PLAYER_TIME_MULT_OWNER_KEY) == System.identityHashCode(ship)) {
            engine.timeMult.unmodifyMult("${id}_player")
            engine.customData.remove(PLAYER_TIME_MULT_OWNER_KEY)
        }
        ship ?: return
        ship.isJitterShields = false
        engine?.customData?.remove(AFTERIMAGE_TIMER_KEY_PREFIX + System.identityHashCode(ship))
        engine?.customData?.remove(MOMENTUM_LATCH_KEY_PREFIX + ship.id)
    }

    private fun applyMomentumOnce(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        stats: MutableShipStatsAPI,
        values: BurstFlowTuning.Values,
    ) {
        val key = MOMENTUM_LATCH_KEY_PREFIX + ship.id
        if (engine.customData[key] == true) return
        engine.customData[key] = true
        val direction = Vector2f(ship.velocity)
        if (direction.lengthSquared() < MIN_SPEED_FOR_DIRECTION * MIN_SPEED_FOR_DIRECTION) {
            direction.set(MathUtils.getPointOnCircumference(null, 1f, ship.facing))
        } else {
            direction.normalise()
        }
        direction.scale(stats.maxSpeed.modifiedValue * values.momentumMult)
        Vector2f.add(ship.velocity, direction, ship.velocity)
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
            I18n[I18n.Categories.MOD, "system.burst_flow.status.default.$suffix"],
            false,
        )
    }

    private fun renderBurstStreak(ship: ShipAPI, id: String, state: ShipSystemStatsScript.State) {
        val engine = Global.getCombatEngine() ?: return
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
    }
}
