package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import java.awt.Color

class ASTDLimitTemporalThrusterSystemStats : BaseShipSystemScript() {

    companion object {
        private const val TIME_MULT = 2f
        private const val MAX_SPEED_MULT = 1.5f
        private const val LATERAL_MANEUVER_MULT = 2f
        private const val AFTERIMAGE_INTERVAL = 0.12f
        private const val PLAYER_TIME_MULT_OWNER_KEY = "astd_limit_temporal_thruster_player_time_mult_owner"
        private val AFTERIMAGE_COLOR = Color(105, 210, 255, 82)
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        val level = if (state == ShipSystemStatsScript.State.ACTIVE) 1f else 0f
        if (level <= 0f) {
            unapply(stats, id)
            return
        }

        val timeMult = 1f + (TIME_MULT - 1f) * level
        stats.timeMult.modifyMult(id, timeMult)
        stats.maxSpeed.modifyMult(id, 1f + (MAX_SPEED_MULT - 1f) * level)
        stats.acceleration.modifyMult(id, 1f + (LATERAL_MANEUVER_MULT - 1f) * level)
        stats.deceleration.modifyMult(id, 1f + (LATERAL_MANEUVER_MULT - 1f) * level)
        stats.maxTurnRate.modifyMult(id, 1f + (LATERAL_MANEUVER_MULT - 1f) * level)
        stats.turnAcceleration.modifyMult(id, 1f + (LATERAL_MANEUVER_MULT - 1f) * level)

        val engine = Global.getCombatEngine()
        if (ship != null && engine != null && !engine.isPaused) {
            if (ship === engine.playerShip) {
                engine.timeMult.modifyMult("${id}_player", 1f / timeMult)
                engine.customData[PLAYER_TIME_MULT_OWNER_KEY] = System.identityHashCode(ship)
            }
            renderTemporalStreak(ship, id, state)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.timeMult.unmodify(id)
        stats.maxSpeed.unmodify(id)
        stats.acceleration.unmodify(id)
        stats.deceleration.unmodify(id)
        stats.maxTurnRate.unmodify(id)
        stats.turnAcceleration.unmodify(id)
        val ship = stats.entity as? ShipAPI
        val engine = Global.getCombatEngine()
        if (ship != null && engine?.customData?.get(PLAYER_TIME_MULT_OWNER_KEY) == System.identityHashCode(ship)) {
            engine.timeMult.unmodify("${id}_player")
            engine.customData.remove(PLAYER_TIME_MULT_OWNER_KEY)
        }
        ship ?: return
        val baseKey = "${ASTDArcProductionShipIds.STAT_LIMIT_TEMPORAL_THRUSTER}:${System.identityHashCode(ship)}"
        engine?.customData?.remove("$baseKey:pulse")
        engine?.customData?.remove("$baseKey:afterimage")
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
            I18n[I18n.Categories.MOD, "system.limit_temporal_thruster.status.default.$suffix"],
            false,
        )
    }

    private fun renderTemporalStreak(ship: ShipAPI, id: String, state: ShipSystemStatsScript.State) {
        val engine = Global.getCombatEngine() ?: return
        val baseKey = "${ASTDArcProductionShipIds.STAT_LIMIT_TEMPORAL_THRUSTER}:${System.identityHashCode(ship)}"
        if (state == ShipSystemStatsScript.State.IN) {
            val pulseKey = "$baseKey:pulse"
            if (engine.customData[pulseKey] != true) {
                engine.customData[pulseKey] = true
                ASTDArcProductionVfx.emitTemporalThrusterAfterimage(engine, ship, 1f)
            }
        }
        if (state == ShipSystemStatsScript.State.IDLE) {
            engine.customData.remove("$baseKey:pulse")
            engine.customData.remove("$baseKey:afterimage")
            return
        }

        val elapsed = (engine.customData["$baseKey:afterimage"] as? Float ?: 0f) + engine.elapsedInLastFrame
        if (elapsed < AFTERIMAGE_INTERVAL) {
            engine.customData["$baseKey:afterimage"] = elapsed
            return
        }
        engine.customData["$baseKey:afterimage"] = elapsed - AFTERIMAGE_INTERVAL
        ship.addAfterimage(
            AFTERIMAGE_COLOR,
            0f,
            0f,
            -ship.velocity.x * 0.08f,
            -ship.velocity.y * 0.08f,
            0f,
            0.05f,
            0.18f,
            0.45f,
            true,
            false,
            false,
        )
    }
}
