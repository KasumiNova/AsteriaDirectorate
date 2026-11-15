package cn.kasuminova.astd.combat.effect.arc.starfallwing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

internal object StarfallWingTargeting {
    private const val COAST_SECONDS = 0.5f
    private const val COAST_FRICTION = 0.5f
    private const val TIME_SCALE = 0.6f
    private const val ANGLE_SCALE = 75f
    private const val ANGULAR_SCALE = 0.15f
    private const val TIME_WEIGHT = 2f
    private const val ANGLE_WEIGHT = 1.5f
    private const val SIZE_WEIGHT = 1.2f
    private const val REACH_WEIGHT = 0.8f
    private const val TURN_WEIGHT = 1.6f
    private const val UNREACHABLE_FACTOR = 0.15f
    private const val SWITCH_MARGIN = 0.5f
    private const val LOCK_BONUS = 1f
    private const val EPSILON = 1e-4f

    fun coastDistance(speed: Float): Float = speed * COAST_SECONDS * COAST_FRICTION

    fun withLockBonus(score: Float, locked: Boolean): Float = if (locked) score + LOCK_BONUS else score

    fun outbids(candidateScore: Float, currentScore: Float): Boolean =
        candidateScore - currentScore > SWITCH_MARGIN

    fun classOf(isFighter: Boolean, locked: Boolean, inRange: Boolean): Int = when {
        isFighter -> StarfallWingShot.AVAILABILITY_FIGHTER
        locked && inRange -> StarfallWingShot.AVAILABILITY_LOCK
        inRange -> StarfallWingShot.AVAILABILITY_SHIP
        else -> StarfallWingShot.AVAILABILITY_SHIP_OUT_OF_RANGE
    }

    fun interceptTime(offsetX: Float, offsetY: Float, velocityX: Float, velocityY: Float, speed: Float): Float {
        val distanceSq = offsetX * offsetX + offsetY * offsetY
        if (distanceSq <= 0f) return 0f
        val quadratic = velocityX * velocityX + velocityY * velocityY - speed * speed
        val linear = 2f * (offsetX * velocityX + offsetY * velocityY)
        if (abs(quadratic) <= EPSILON) {
            if (linear >= 0f) return Float.NaN
            return distanceSq / -linear
        }
        val discriminant = linear * linear - 4f * quadratic * distanceSq
        if (discriminant < 0f) return Float.NaN
        val root = sqrt(discriminant)
        val near = (-linear - root) / (2f * quadratic)
        val far = (-linear + root) / (2f * quadratic)
        var earliest = Float.NaN
        if (near > 0f && near.isFinite()) earliest = near
        if (far > 0f && far.isFinite() && (earliest.isNaN() || far < earliest)) earliest = far
        return earliest
    }

    fun bearing(offsetX: Float, offsetY: Float): Float =
        Math.toDegrees(atan2(offsetY.toDouble(), offsetX.toDouble())).toFloat()

    fun leadFacing(offsetX: Float, offsetY: Float, velocityX: Float, velocityY: Float, time: Float): Float =
        bearing(offsetX + velocityX * time, offsetY + velocityY * time)

    fun angleBetween(from: Float, to: Float): Float = ((to - from) % 360f + 540f) % 360f - 180f

    fun aimScore(
        offsetX: Float,
        offsetY: Float,
        velocityX: Float,
        velocityY: Float,
        radius: Float,
        facing: Float,
        speed: Float,
        turnRate: Float,
        remainingRange: Float,
    ): Float {
        val distance = sqrt(offsetX * offsetX + offsetY * offsetY)
        val intercept = interceptTime(offsetX, offsetY, velocityX, velocityY, speed)
        val interceptable = !intercept.isNaN()
        val flightTime = if (interceptable) intercept else distance / maxOf(speed, 1f)
        val lead = if (interceptable) {
            leadFacing(offsetX, offsetY, velocityX, velocityY, flightTime)
        } else {
            bearing(offsetX, offsetY)
        }
        val travel = if (interceptable) speed * flightTime else distance
        val margin = remainingRange + radius - travel
        val angle = abs(angleBetween(facing, lead))
        val turnSeconds = angle / maxOf(turnRate, 1f)
        val overturn = (turnSeconds - flightTime).coerceAtLeast(0f)
        val angular = radius / maxOf(distance, 1f)
        val score = TIME_WEIGHT / (1f + flightTime / TIME_SCALE) +
            ANGLE_WEIGHT / (1f + angle / ANGLE_SCALE) +
            SIZE_WEIGHT * (angular / (angular + ANGULAR_SCALE)) +
            REACH_WEIGHT * (margin / maxOf(remainingRange, 1f)).coerceIn(0f, 1f) -
            TURN_WEIGHT * (overturn / (overturn + flightTime + EPSILON))
        val reachable = interceptable && margin >= -coastDistance(speed)
        return if (reachable) score else score * UNREACHABLE_FACTOR
    }
}

internal class StarfallWingTargetPool<K : Any> {
    private val claims = HashMap<K, StarfallWingShot>()

    fun isOpen(candidate: K, shot: StarfallWingShot): Boolean = claims[candidate].let { it == null || it === shot }

    fun claim(candidate: K, shot: StarfallWingShot) {
        claims[candidate] = shot
    }

    fun release(candidate: K?, shot: StarfallWingShot) {
        if (candidate != null && claims[candidate] === shot) claims.remove(candidate)
    }
}
