package cn.kasuminova.astd.combat.shipsystems

import kotlin.math.abs

/**
 * 贯虹突进的起手判定。ACTIVE 期间舰速锁在舰首方向、转向只剩两成，起手瞬间的航向必须已经正确。
 */
internal object GressionChargeEngagement {

    data class Travel(
        val destDistance: Float,
        val headingDeltaDeg: Float,
        val accelerating: Boolean,
        val decelerating: Boolean,
        val enemyNearby: Boolean,
        val runningQuickly: Boolean,
        val retreating: Boolean,
    )

    data class Sample(
        val hasTarget: Boolean,
        val facingDeltaDeg: Float,
        val surfaceDistance: Float,
        val predictedSurfaceDistance: Float,
        val aimAngularRadiusDeg: Float,
        val sprintSpeed: Float,
        val hardFluxLevel: Float,
        val angularVelocityDeg: Float,
        val pursuing: Boolean,
        val travel: Travel?,
    )

    /** 必须与系统 ACTIVE 时长一致。 */
    const val SYSTEM_ACTIVE_SEC = 3f

    const val TRAVEL_ENEMY_CLEAR_RADIUS = 2200f

    const val SELECTION_FORWARD_HALF_ANGLE_DEG = 35f

    fun sprintDistance(sprintSpeed: Float): Float {
        if (sprintSpeed.isNaN() || sprintSpeed <= 0f) return 0f
        return sprintSpeed * SYSTEM_ACTIVE_SEC
    }

    fun leadTimeSec(surfaceDistance: Float, sprintSpeed: Float): Float {
        if (surfaceDistance.isNaN() || sprintSpeed.isNaN() || sprintSpeed <= 0f) return 0f
        return (surfaceDistance / sprintSpeed).coerceIn(0f, LEAD_MAX_SEC)
    }

    fun shouldActivate(sample: Sample): Boolean = shouldEngage(sample) || shouldTravel(sample)

    fun shouldEngage(sample: Sample): Boolean {
        if (!sample.hasTarget) return false
        if (!sample.isFinite()) return false
        if (sample.hardFluxLevel >= ENGAGE_HARD_FLUX_HOLD) return false
        if (abs(sample.angularVelocityDeg) > ENGAGE_MAX_ANGULAR_VELOCITY_DEG) return false
        if (sample.facingDeltaDeg > facingToleranceDeg(sample.aimAngularRadiusDeg)) return false
        val reach = sprintDistance(sample.sprintSpeed) * ENGAGE_REACH_SAFETY
        if (reach <= 0f) return false
        if (sample.surfaceDistance < ENGAGE_MIN_SURFACE_DISTANCE) return false
        if (sample.surfaceDistance > reach) return false
        if (sample.predictedSurfaceDistance > sample.surfaceDistance - ENGAGE_MIN_PREDICTED_GAIN) return false
        if (sample.predictedSurfaceDistance > reach) return false
        return true
    }

    fun shouldTravel(sample: Sample): Boolean {
        val travel = sample.travel ?: return false
        if (!sample.isFinite() || !travel.isFinite()) return false
        if (sample.hardFluxLevel >= TRAVEL_HARD_FLUX_HOLD) return false
        if (abs(sample.angularVelocityDeg) > TRAVEL_MAX_ANGULAR_VELOCITY_DEG) return false
        if (!travel.accelerating || travel.decelerating) return false
        if (travel.headingDeltaDeg > TRAVEL_FACING_TOLERANCE_DEG) return false
        if (travel.destDistance < TRAVEL_MIN_DEST_DISTANCE) return false
        val sprint = sprintDistance(sample.sprintSpeed)
        if (sprint <= 0f) return false
        if (travel.destDistance < sprint * TRAVEL_DEST_SPRINT_RATIO) return false
        if (sample.pursuing && !travel.retreating) return false
        if (travel.enemyNearby && !travel.runningQuickly && !travel.retreating) return false
        return true
    }

    private fun facingToleranceDeg(aimAngularRadiusDeg: Float): Float =
        ENGAGE_FACING_TOLERANCE_DEG + aimAngularRadiusDeg.coerceIn(0f, ENGAGE_ANGULAR_BONUS_CAP_DEG)

    private fun Sample.isFinite(): Boolean =
        finite(sprintSpeed) && finite(facingDeltaDeg) && finite(surfaceDistance) &&
            finite(predictedSurfaceDistance) && finite(aimAngularRadiusDeg) &&
            finite(hardFluxLevel) && finite(angularVelocityDeg)

    private fun Travel.isFinite(): Boolean =
        finite(destDistance) && finite(headingDeltaDeg)

    private fun finite(value: Float): Boolean = !value.isNaN() && !value.isInfinite()

    private const val ENGAGE_REACH_SAFETY = 0.8f
    private const val ENGAGE_FACING_TOLERANCE_DEG = 16f
    private const val ENGAGE_ANGULAR_BONUS_CAP_DEG = 8f
    private const val ENGAGE_MIN_SURFACE_DISTANCE = 120f
    private const val ENGAGE_MIN_PREDICTED_GAIN = 40f
    private const val ENGAGE_MAX_ANGULAR_VELOCITY_DEG = 15f

    private const val ENGAGE_HARD_FLUX_HOLD = 0.68f
    private const val LEAD_MAX_SEC = 0.6f
    private const val TRAVEL_FACING_TOLERANCE_DEG = 12f
    private const val TRAVEL_MAX_ANGULAR_VELOCITY_DEG = 10f
    private const val TRAVEL_MIN_DEST_DISTANCE = 1500f
    private const val TRAVEL_DEST_SPRINT_RATIO = 1.15f
    private const val TRAVEL_HARD_FLUX_HOLD = 0.75f
}
