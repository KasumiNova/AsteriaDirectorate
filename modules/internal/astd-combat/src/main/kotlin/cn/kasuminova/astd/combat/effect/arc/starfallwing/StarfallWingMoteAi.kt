package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

/**
 * 星羽追踪光矢制导：射出 [ImaginaryWingsTuning.MOTE_TRACK_DELAY] 秒后加速至
 * [ImaginaryWingsTuning.MOTE_PURSUIT_SPEED] 追踪目标。定位为点防武器，
 * 接敌优先级为敌方导弹 → 敌方战机 → 敌方舰船，仅在当前档位无目标时才落到下一档。
 */
class StarfallWingMoteAi(
    private val missile: MissileAPI,
    initialTarget: CombatEntityAPI?,
) : MissileAIPlugin {

    private var target: CombatEntityAPI? = initialTarget

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || missile.isFading || missile.isExpired) return
        missile.interruptContrail()
        missile.spriteAlphaOverride = 0f
        missile.glowRadius = 0f
        if (missile.flightTime < ImaginaryWingsTuning.MOTE_TRACK_DELAY) return
        val source = missile.source ?: return
        if (!isValidTarget(target, source)) {
            target = acquirePointDefenseTarget(engine, source, missile.location)
        }
        val t = target
        if (t != null && isValidTarget(t, source)) {
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
        val desired = MathUtils.getPointOnCircumference(null, ImaginaryWingsTuning.MOTE_PURSUIT_SPEED, facing)
        val lerp = (amount * ImaginaryWingsTuning.MOTE_TURN_AGILITY).coerceIn(0f, 1f)
        missile.velocity.x += (desired.x - missile.velocity.x) * lerp
        missile.velocity.y += (desired.y - missile.velocity.y) * lerp
    }

    private fun isValidTarget(t: CombatEntityAPI?, source: ShipAPI): Boolean {
        if (t == null) return false
        if (t.owner == source.owner) return false
        return when (t) {
            is MissileAPI -> !t.isExpired && !t.isFading
            is ShipAPI -> t.isAlive && !t.isHulk && !t.isPhased
            else -> false
        }
    }

    companion object {

        /** 点防接敌优先级：来袭导弹优先，其次战机，最后舰船；同一档取最近目标。 */
        fun acquirePointDefenseTarget(
            engine: CombatEngineAPI,
            source: ShipAPI,
            near: Vector2f,
        ): CombatEntityAPI? {
            val range = ImaginaryWingsTuning.MOTE_ENGAGE_RANGE
            return nearestMissile(engine, source, near, range)
                ?: nearestShip(engine, source, near, range, fightersOnly = true)
                ?: nearestShip(engine, source, near, range, fightersOnly = false)
        }

        fun nearestMissile(
            engine: CombatEngineAPI,
            source: ShipAPI,
            near: Vector2f,
            range: Float,
        ): MissileAPI? {
            var best: MissileAPI? = null
            var bestDistance = range
            for (candidate in engine.missiles) {
                if (candidate.owner == source.owner) continue
                if (candidate.isExpired || candidate.isFading) continue
                val distance = MathUtils.getDistance(near, candidate.location)
                if (distance < bestDistance) {
                    best = candidate
                    bestDistance = distance
                }
            }
            return best
        }

        fun nearestShip(
            engine: CombatEngineAPI,
            source: ShipAPI,
            near: Vector2f,
            range: Float,
            fightersOnly: Boolean,
        ): ShipAPI? {
            var best: ShipAPI? = null
            var bestDistance = range
            for (candidate in engine.ships) {
                val ship = candidate as? ShipAPI ?: continue
                if (ship === source || ship.owner == source.owner) continue
                if (!ship.isAlive || ship.isHulk || ship.isPhased) continue
                if (fightersOnly) {
                    if (!ship.isFighter && !ship.isDrone) continue
                } else {
                    if (ship.isFighter || ship.isDrone) continue
                }
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
