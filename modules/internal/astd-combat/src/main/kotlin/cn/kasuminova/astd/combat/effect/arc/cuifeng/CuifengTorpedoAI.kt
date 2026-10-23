package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeMath
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

class CuifengTorpedoAI(
    private val missile: MissileAPI,
    private val launchLoc: Vector2f,
    private val maxRange: Float,
) : MissileAIPlugin {

    private val log = Global.getLogger(CuifengTorpedoAI::class.java)

    private var target: ShipAPI? = null
    private var reselectTimer = 0f

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (missile.isFading || missile.isExpired) return

        reselectTimer -= amount
        if (reselectTimer <= 0f) {
            reselectTimer = CuifengTorpedoDifficulty.RETARGET_INTERVAL
            val current = target
            val locked = lockedTarget(engine)
            val picked = when {
                locked != null -> locked
                current == null || !isValidTarget(engine, current) -> selectTarget(engine)
                else -> current
            }
            if (picked !== current) {
                target = picked
                if (picked != null) {
                    bump(engine, TELE_TARGET_SELECTED)
                    log.info(
                        "[摧锋] 鱼雷选定目标：hull=${picked.hullSpec?.hullId} " +
                                "dist=${MathUtils.getDistance(missile.location, picked.location).toInt()}" +
                                if (picked === locked) "（遵循发射舰锁定）" else "",
                    )
                } else {
                    log.info("[摧锋] 鱼雷无可选目标（候选全空/全越射程），直飞")
                }
            }
        }

        val t = target
        if (t != null && isValidTarget(engine, t)) {
            val dist = MathUtils.getDistance(missile.location, t.location)
            val lead = StellarMrmStrikeMath.leadPoint(t.location, t.velocity, dist, missile.maxSpeed)
            val angleTo = VectorUtils.getAngle(missile.location, lead)
            val diff = MathUtils.getShortestRotation(missile.facing, angleTo)
            if (abs(diff) > 1f) {
                missile.giveCommand(if (diff > 0f) ShipCommand.TURN_LEFT else ShipCommand.TURN_RIGHT)
            }
        }
        missile.giveCommand(ShipCommand.ACCELERATE)

        // MissileAPI 无 setMaxSpeed，引擎持续推进，超帽部分直接截速。
        val maxSpeed = missile.maxSpeed
        if (maxSpeed <= 0f) return
        val progress = CuifengTorpedoMath.progressOf(MathUtils.getDistance(launchLoc, missile.location), maxRange)
        val factor = CuifengTorpedoMath.speedFactor(progress)
        val cap = maxSpeed * factor
        val vel = missile.velocity
        val speed = vel.length()
        if (speed > cap) {
            vel.scale(cap / speed)
            bump(engine, TELE_GOVERNED_FRAMES)
        }
        engine.customData[TELE_LAST_SPEED_FACTOR] = factor
    }

    private fun lockedTarget(engine: CombatEngineAPI): ShipAPI? {
        val locked = missile.source?.shipTarget ?: return null
        if (locked.isFighter || locked.isDrone) return null
        return if (isValidTarget(engine, locked)) locked else null
    }

    private fun selectTarget(engine: CombatEngineAPI): ShipAPI? {
        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (ship in engine.ships) {
            if (!isValidTarget(engine, ship)) continue
            if (ship.isFighter || ship.isDrone) continue
            val dist = MathUtils.getDistance(missile.location, ship.location)
            if (dist > maxRange * ACQUIRE_RANGE_MULT) continue
            if (dist < bestDist) {
                bestDist = dist
                best = ship
            }
        }
        return best
    }

    private fun isValidTarget(engine: CombatEngineAPI, t: ShipAPI): Boolean =
        engine.isEntityInPlay(t) && t.isAlive && !t.isHulk && t.owner != missile.owner

    companion object {
        private const val ACQUIRE_RANGE_MULT = 1.25f

        const val TELE_TARGET_SELECTED = "astd_cuifeng_tele_target_selected"

        const val TELE_GOVERNED_FRAMES = "astd_cuifeng_tele_governed_frames"

        const val TELE_LAST_SPEED_FACTOR = "astd_cuifeng_tele_last_speed_factor"

        private fun bump(engine: CombatEngineAPI, key: String) {
            engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
        }
    }
}
