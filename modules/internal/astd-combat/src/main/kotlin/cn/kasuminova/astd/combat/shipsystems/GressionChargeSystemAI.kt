package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 贯虹突进 AI。ACTIVE 锁死舰首航向，起手瞬间的朝向决定整个冲刺的弹道。
 */
class GressionChargeSystemAI : ShipSystemAIScript {

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private var flags: ShipwideAIFlags? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_MIN, SCAN_INTERVAL_MAX)

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        this.flags = flags
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        val flags = this.flags ?: return
        if (engine.isPaused || ship.isHulk || ship.isPhased) return
        if (ship.fluxTracker?.isOverloadedOrVenting == true) return
        if (system.state != ShipSystemAPI.SystemState.IDLE) return
        if (!system.canBeActivated()) return

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        if (!GressionChargeEngagement.shouldActivate(readSample(ship, engine, flags, target))) return
        ship.useSystem()
    }

    private fun readSample(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        flags: ShipwideAIFlags,
        hinted: ShipAPI?,
    ): GressionChargeEngagement.Sample {
        val sprintSpeed = sprintSpeed(ship)
        val target = resolveTarget(ship, engine, flags, hinted, sprintSpeed)
        val travel = travelSample(ship, engine, flags)
        val hardFlux = hardFluxLevel(ship)
        val angularVelocity = ship.angularVelocity
        val pursuing = flags.hasFlag(ShipwideAIFlags.AIFlags.PURSUING)
        if (target == null) {
            return GressionChargeEngagement.Sample(
                hasTarget = false,
                facingDeltaDeg = 180f,
                surfaceDistance = 0f,
                predictedSurfaceDistance = 0f,
                aimAngularRadiusDeg = 0f,
                sprintSpeed = sprintSpeed,
                hardFluxLevel = hardFlux,
                angularVelocityDeg = angularVelocity,
                pursuing = pursuing,
                travel = travel,
            )
        }
        val aim = predictedAim(ship, target, sprintSpeed)
        return GressionChargeEngagement.Sample(
            hasTarget = true,
            facingDeltaDeg = aim.facingDeltaDeg,
            surfaceDistance = aim.surfaceDistance,
            predictedSurfaceDistance = aim.predictedSurfaceDistance,
            aimAngularRadiusDeg = aim.aimAngularRadiusDeg,
            sprintSpeed = sprintSpeed,
            hardFluxLevel = hardFlux,
            angularVelocityDeg = angularVelocity,
            pursuing = pursuing,
            travel = travel,
        )
    }

    private fun resolveTarget(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        flags: ShipwideAIFlags,
        hinted: ShipAPI?,
        sprintSpeed: Float,
    ): ShipAPI? {
        val reach = GressionChargeEngagement.sprintDistance(sprintSpeed)
        if (reach <= 0f) return null
        val explicit = listOfNotNull(ship.shipTarget, maneuverTarget(flags), hinted)
            .firstOrNull { isRamTarget(ship, it, reach) && isForward(ship, it) }
        if (explicit != null) return explicit
        var best: ShipAPI? = null
        var bestDistance = Float.MAX_VALUE
        for (other in engine.ships) {
            if (!isRamTarget(ship, other, reach)) continue
            if (!isForward(ship, other)) continue
            val distance = MathUtils.getDistance(ship.location, other.location)
            if (distance < bestDistance) {
                best = other
                bestDistance = distance
            }
        }
        return best
    }

    private fun maneuverTarget(flags: ShipwideAIFlags): ShipAPI? =
        flags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET) as? ShipAPI

    private fun isRamTarget(ship: ShipAPI, other: ShipAPI?, reach: Float): Boolean {
        if (other == null || other === ship) return false
        if (other.owner == ship.owner) return false
        if (other.owner == NEUTRAL_OWNER && ship.owner != NEUTRAL_OWNER) return false
        if (!other.isAlive || other.isHulk || other.isFighter || other.isDrone) return false
        if (other.isPhased || other.isStation || other.isStationModule) return false
        val centerDistance = MathUtils.getDistance(ship.location, other.location)
        return centerDistance <= reach + ship.collisionRadius + other.collisionRadius
    }

    private fun isForward(ship: ShipAPI, other: ShipAPI): Boolean {
        val dx = other.location.x - ship.location.x
        val dy = other.location.y - ship.location.y
        if (dx * dx + dy * dy <= 1f) return false
        return abs(MathUtils.getShortestRotation(ship.facing, bearingDeg(dx, dy))) <=
            GressionChargeEngagement.SELECTION_FORWARD_HALF_ANGLE_DEG
    }

    private fun predictedAim(ship: ShipAPI, target: ShipAPI, sprintSpeed: Float): PredictedAim {
        val shipRadius = ship.collisionRadius
        val targetRadius = target.collisionRadius
        val centerDistance = MathUtils.getDistance(ship.location, target.location)
        val surfaceDistance = (centerDistance - shipRadius - targetRadius).coerceAtLeast(0f)
        val lead = GressionChargeEngagement.leadTimeSec(surfaceDistance, sprintSpeed)
        val aimX = target.location.x + target.velocity.x * lead
        val aimY = target.location.y + target.velocity.y * lead
        val toAimX = aimX - ship.location.x
        val toAimY = aimY - ship.location.y
        val aimDistance = sqrt(toAimX * toAimX + toAimY * toAimY)
        val facingDelta = if (aimDistance <= 1f) {
            180f
        } else {
            abs(MathUtils.getShortestRotation(ship.facing, bearingDeg(toAimX, toAimY)))
        }
        val aimAngularRadius = Math.toDegrees(
            atan2((shipRadius + targetRadius).toDouble(), aimDistance.coerceAtLeast(1f).toDouble()),
        ).toFloat()
        val facingRad = Math.toRadians(ship.facing.toDouble())
        val ourX = ship.location.x + cos(facingRad).toFloat() * sprintSpeed * lead
        val ourY = ship.location.y + sin(facingRad).toFloat() * sprintSpeed * lead
        val predictedCenter = sqrt((aimX - ourX) * (aimX - ourX) + (aimY - ourY) * (aimY - ourY))
        val predictedSurface = (predictedCenter - shipRadius - targetRadius).coerceAtLeast(0f)
        return PredictedAim(facingDelta, surfaceDistance, predictedSurface, aimAngularRadius)
    }

    private fun travelSample(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        flags: ShipwideAIFlags,
    ): GressionChargeEngagement.Travel? {
        val dest = flags.getCustom(ShipwideAIFlags.AIFlags.MOVEMENT_DEST) as? Vector2f ?: return null
        val dx = dest.x - ship.location.x
        val dy = dest.y - ship.location.y
        val destDistance = sqrt(dx * dx + dy * dy)
        val headingDelta = if (destDistance <= 1f) {
            180f
        } else {
            abs(MathUtils.getShortestRotation(ship.facing, bearingDeg(dx, dy)))
        }
        val controller = ship.engineController
        return GressionChargeEngagement.Travel(
            destDistance = destDistance,
            headingDeltaDeg = headingDelta,
            accelerating = controller?.isAccelerating == true,
            decelerating = controller?.isDecelerating == true || controller?.isAcceleratingBackwards == true,
            enemyNearby = hasHostileCombatShipNear(ship, engine),
            runningQuickly = flags.hasFlag(ShipwideAIFlags.AIFlags.RUN_QUICKLY),
            retreating = ship.isRetreating(),
        )
    }

    private fun hasHostileCombatShipNear(ship: ShipAPI, engine: CombatEngineAPI): Boolean {
        val radius = GressionChargeEngagement.TRAVEL_ENEMY_CLEAR_RADIUS
        val radiusSq = radius * radius
        for (other in engine.ships) {
            if (other === ship || other.owner == ship.owner) continue
            if (other.owner == NEUTRAL_OWNER && ship.owner != NEUTRAL_OWNER) continue
            if (other.isHulk || other.isFighter || other.isDrone) continue
            val dx = other.location.x - ship.location.x
            val dy = other.location.y - ship.location.y
            if (dx * dx + dy * dy <= radiusSq) return true
        }
        return false
    }

    private fun sprintSpeed(ship: ShipAPI): Float {
        val current = ship.maxSpeed
        if (current.isNaN() || current <= 0f) return 0f
        val percent = GressionChargeTuning.speedPercent(DifficultyTuningImpl, ship.owner == 0)
        if (percent.isNaN()) return current
        val stat = ship.mutableStats.maxSpeed
        val projected = stat.createCopy()
        projected.modifyPercent(PREDICTION_MOD_SOURCE, percent)
        return current + projected.modifiedValue - stat.modifiedValue
    }

    private fun hardFluxLevel(ship: ShipAPI): Float {
        val tracker = ship.fluxTracker ?: return 0f
        val capacity = ship.maxFlux
        if (capacity <= 0f) return 0f
        return tracker.hardFlux / capacity
    }

    private fun bearingDeg(dx: Float, dy: Float): Float =
        Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

    private class PredictedAim(
        val facingDeltaDeg: Float,
        val surfaceDistance: Float,
        val predictedSurfaceDistance: Float,
        val aimAngularRadiusDeg: Float,
    )

    companion object {
        private const val SCAN_INTERVAL_MIN = 0.35f
        private const val SCAN_INTERVAL_MAX = 0.55f
        private const val NEUTRAL_OWNER = 100
        private const val PREDICTION_MOD_SOURCE = "astd_gression_ai_prediction"
    }
}
