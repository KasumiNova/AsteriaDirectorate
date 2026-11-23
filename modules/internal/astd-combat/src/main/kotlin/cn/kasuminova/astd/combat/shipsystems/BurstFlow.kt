package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.effect.joint.BurstFlowTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import cn.kasuminova.astd.renderer.effect.system.StarfallShardWake
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.plugins.ShipSystemStatsScript.State
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

internal enum class BurstFlow {
    ORIGINAL, STARFALL;

    private val activationKey = "astd_burst_flow_activation_$name"

    fun apply(stats: MutableShipStatsAPI, id: String, state: State) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        if (state != State.ACTIVE || !ship.isAlive) {
            unapply(stats)
            if (!engine.isPaused && ship.isAlive && (state == State.IN || state == State.OUT)) {
                jitter(ship, id, if (state == State.IN) 0.5f else 0.45f)
            }
            return
        }
        if (engine.isPaused) return
        val active = ship.customData[activationKey] as? Activation ?: run {
            val values = when (this) {
                ORIGINAL -> BurstFlowTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
                STARFALL -> BurstFlowTuning.starfall(DifficultyTuningImpl, ship.owner == 0)
            }
            Activation(this, engine, ship, id, values).also {
                ship.setCustomData(activationKey, it)
                engine.addPlugin(it)
                it.begin()
            }
        }
        active.applyFrame()
    }

    fun unapply(stats: MutableShipStatsAPI) {
        val ship = stats.entity as? ShipAPI ?: return
        (ship.customData[activationKey] as? Activation)?.finish()
    }

    private class Activation(
        private val mode: BurstFlow,
        private val engine: CombatEngineAPI,
        private val ship: ShipAPI,
        private val id: String,
        private val values: BurstFlowTuning.Values,
    ) : BaseEveryFrameCombatPlugin() {
        private val stats = ship.mutableStats
        private val playerTimeId = "astd_burst_flow_player_${ship.id}_$id"
        private val previousJitterShields = ship.isJitterShields
        private val weapons = if (mode == STARFALL) StarfallWeaponCooldowns(ship) else null
        private var wakeTimer = if (mode == STARFALL) WAKE_INTERVAL else 0f

        fun begin() {
            stats.timeMult.modifyMult(id, values.timeMult)
            stats.acceleration.modifyMult(id, values.speedManeuverMult)
            stats.deceleration.modifyMult(id, values.speedManeuverMult)
            stats.fluxDissipation.modifyMult(id, values.fluxDissipationMult)
            stats.ballisticAmmoRegenMult.modifyMult(id, values.ammoRegenMult)
            stats.energyAmmoRegenMult.modifyMult(id, values.ammoRegenMult)
            if (mode == ORIGINAL) {
                stats.maxTurnRate.modifyMult(id, 0f)
                stats.turnAcceleration.modifyMult(id, 0f)
                stats.missileAmmoRegenMult.modifyMult(id, values.ammoRegenMult)
            }
            val impulse = direction()
            impulse.scale(stats.maxSpeed.modifiedValue * values.momentumMult)
            Vector2f.add(ship.velocity, impulse, ship.velocity)
            ship.isJitterShields = false
            updatePlayerTime()
        }

        fun applyFrame() {
            val shipSeconds = engine.elapsedInLastFrame * stats.timeMult.modifiedValue
            weapons?.apply(shipSeconds)
            jitter(ship, id, 1f)
            wakeTimer += if (mode == STARFALL) shipSeconds else engine.elapsedInLastFrame
            if (wakeTimer < WAKE_INTERVAL) return
            wakeTimer %= WAKE_INTERVAL
            ASTDAfterimageEffect.spawnShip(
                engine, ship, Vector2f(ship.location), AFTERIMAGE_COLOR,
                startAlpha = 0.95f, duration = 0.8f, growth = 0.05f,
            )
            if (mode == STARFALL) StarfallShardWake.emit(engine, ship, direction())
        }

        private fun direction(): Vector2f {
            val velocity = Vector2f(ship.velocity)
            if (velocity.lengthSquared() <= 0f || (mode == ORIGINAL && velocity.lengthSquared() < 25f)) {
                return MathUtils.getPointOnCircumference(null, 1f, ship.facing)
            }
            velocity.normalise()
            return velocity
        }

        private fun updatePlayerTime() {
            if (ship === engine.playerShip) engine.timeMult.modifyMult(playerTimeId, 1f / values.timeMult)
            else engine.timeMult.unmodifyMult(playerTimeId)
        }

        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
            if (ship.customData[mode.activationKey] !== this) return
            if (ship.isAlive && engine.isEntityInPlay(ship)) updatePlayerTime() else finish()
        }

        fun finish() {
            stats.timeMult.unmodifyMult(id)
            stats.acceleration.unmodifyMult(id)
            stats.deceleration.unmodifyMult(id)
            stats.fluxDissipation.unmodifyMult(id)
            stats.ballisticAmmoRegenMult.unmodifyMult(id)
            stats.energyAmmoRegenMult.unmodifyMult(id)
            if (mode == ORIGINAL) {
                stats.maxTurnRate.unmodifyMult(id)
                stats.turnAcceleration.unmodifyMult(id)
                stats.missileAmmoRegenMult.unmodifyMult(id)
            }
            weapons?.restore()
            engine.timeMult.unmodifyMult(playerTimeId)
            ship.isJitterShields = previousJitterShields
            ship.removeCustomData(mode.activationKey)
            engine.removePlugin(this)
        }
    }

    private companion object {
        const val WAKE_INTERVAL = 0.2f
        val AFTERIMAGE_COLOR = Color(105, 210, 255, 96)
        val JITTER_UNDER = Color(90, 165, 255, 155)
        val JITTER = Color(90, 165, 255, 55)

        fun jitter(ship: ShipAPI, id: String, level: Float) {
            ship.setJitterUnder(id, JITTER_UNDER, level, 25, 0f, 7f)
            ship.setJitter(id, JITTER, 0.6f * level, 3, 0f, 0f)
        }
    }
}
