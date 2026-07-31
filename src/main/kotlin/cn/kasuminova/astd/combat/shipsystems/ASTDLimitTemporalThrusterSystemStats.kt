package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.ArcFlareAfterimageManager
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class ASTDLimitTemporalThrusterSystemStats : BaseShipSystemScript() {

    companion object {
        private const val TIME_MULT = 3f
        private const val MAX_SPEED_MULT = 1.5f
        private const val LATERAL_MANEUVER_MULT = 2f
        private const val AFTERIMAGE_INTERVAL = 0.06f
        private const val PLAYER_TIME_MULT_OWNER_KEY = "astd_limit_temporal_thruster_player_time_mult_owner"
        private val AFTERIMAGE_COLOR = Color(105, 210, 255, 96)
        private val TEMPORAL_JITTER_UNDER = Color(90, 165, 255, 155)
        private val TEMPORAL_JITTER = Color(90, 165, 255, 55)
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        val level = if (state == ShipSystemStatsScript.State.ACTIVE) 1f else 0f
        val engine = Global.getCombatEngine()
        if (ship != null && engine != null && !engine.isPaused) {
            renderTemporalStreak(ship, id, state)
        }
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

        if (ship != null && engine != null && !engine.isPaused) {
            if (ship === engine.playerShip) {
                engine.timeMult.modifyMult("${id}_player", 1f / timeMult)
                engine.customData[PLAYER_TIME_MULT_OWNER_KEY] = System.identityHashCode(ship)
            }
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
        ship.setJitterShields(false)
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
        val level = when (state) {
            ShipSystemStatsScript.State.IN -> 0.5f
            ShipSystemStatsScript.State.ACTIVE -> 1f
            ShipSystemStatsScript.State.OUT -> 0.45f
            else -> 0f
        }
        if (level > 0f) {
            ship.setJitterShields(false)
            ship.setJitterUnder(id, TEMPORAL_JITTER_UNDER, level, 25, 0f, 7f)
            ship.setJitter(id, TEMPORAL_JITTER, 0.30f * level, 3, 0f, 0f)
        }
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
        ArcFlareAfterimageManager.spawn(
            engine,
            ArcFlareAfterimageManager.Snapshot(
                spritePath = ship.hullSpec.spriteName,
                location = Vector2f(ship.location),
                facing = ship.facing,
                width = ship.spriteAPI.width,
                height = ship.spriteAPI.height,
                color = AFTERIMAGE_COLOR,
                startAlpha = 0.42f,
                duration = 0.42f,
                growth = 0.035f,
            ),
        )
    }
}
