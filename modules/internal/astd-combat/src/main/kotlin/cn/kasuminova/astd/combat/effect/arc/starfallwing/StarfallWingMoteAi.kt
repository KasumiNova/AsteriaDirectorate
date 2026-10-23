package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

class StarfallWingMoteAi(
    private val missile: MissileAPI,
    initialTarget: ShipAPI?,
) : MissileAIPlugin {

    private var target: ShipAPI? = initialTarget

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || missile.isFading || missile.isExpired) return
        missile.interruptContrail()
        missile.spriteAlphaOverride = 0f
        missile.glowRadius = 0f
        if (!StarfallWingTuning.moteTrackingActive(missile.flightTime)) return
        val source = missile.source ?: return
        if (!isValidTarget(engine, target, source)) {
            target = nearestEnemyShip(engine, source, missile.location, TARGET_REFRESH_RANGE)
        }
        val t = target
        if (t != null && isValidTarget(engine, t, source)) {
            val angleTo = VectorUtils.getAngle(missile.location, t.location)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)
            missile.facing = angleTo
            missile.angularVelocity = 0f
            if (abs(diff) > 1.0f) missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            applyVelocityToward(amount, angleTo)
        }
        missile.giveCommand(ShipCommand.ACCELERATE)
    }

    private fun applyVelocityToward(amount: Float, facing: Float) {
        val desired = MathUtils.getPointOnCircumference(null, PURSUIT_SPEED, facing)
        val lerp = (amount * 14f).coerceIn(0f, 1f)
        missile.velocity.x += (desired.x - missile.velocity.x) * lerp
        missile.velocity.y += (desired.y - missile.velocity.y) * lerp
    }

    private fun isValidTarget(engine: CombatEngineAPI, t: ShipAPI?, source: ShipAPI): Boolean {
        if (t == null) return false
        if (!engine.isEntityInPlay(t)) return false
        if (!t.isAlive || t.isHulk || t.isFighter || t.isDrone) return false
        if (t.owner == source.owner) return false
        return true
    }

    companion object {
        private const val PURSUIT_SPEED = 900f

        private const val TARGET_REFRESH_RANGE = 1400f

        fun nearestEnemyShip(engine: CombatEngineAPI, source: ShipAPI, near: Vector2f, range: Float): ShipAPI? {
            var best: ShipAPI? = null
            var bestDistance = range
            for (candidate in engine.ships) {
                val ship = candidate as? ShipAPI ?: continue
                if (ship === source || ship.owner == source.owner) continue
                if (!ship.isAlive || ship.isHulk || ship.isFighter || ship.isDrone) continue
                val distance = MathUtils.getDistance(near, ship.location)
                if (distance < bestDistance) {
                    best = ship
                    bestDistance = distance
                }
            }
            return best
        }
    }
}
