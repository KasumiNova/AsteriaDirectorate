package cn.kasuminova.astd.combat.shipsystems

import kotlin.math.sqrt

internal object GressionChargeMath {

    enum class Phase {
        IDLE,
        CHARGING,
        SPRINTING,
        SETTLING,
        DONE,
    }

    data class Sprint(
        val phase: Phase = Phase.IDLE,
        val settled: Boolean = false,
    )

    fun halfAngleDeg(arcDeg: Float): Float {
        if (arcDeg.isNaN() || arcDeg <= 0f) return 0f
        return arcDeg / 2f
    }

    fun massMultiplier(bonusFraction: Float): Float {
        if (bonusFraction.isNaN() || bonusFraction < 0f) return 1f
        return 1f + bonusFraction
    }

    fun beginSprint(sprint: Sprint): Sprint {
        if (sprint.settled || sprint.phase == Phase.DONE) return sprint
        return sprint.copy(phase = Phase.SPRINTING)
    }

    fun onCollision(sprint: Sprint, heavyCollision: Boolean): Sprint {
        if (sprint.phase != Phase.SPRINTING || sprint.settled || !heavyCollision) return sprint
        return sprint.copy(phase = Phase.SETTLING, settled = true)
    }

    fun isHeavyCollision(noProjectile: Boolean, collisionType: Boolean, continuous: Boolean, damage: Float, threshold: Float): Boolean =
        noProjectile && collisionType && !continuous && damage.isFinite() && damage >= threshold

    fun pushImpulse(forwardSpeed: Float, pushSpeed: Float): Float =
        maxOf(pushSpeed, pushSpeed - forwardSpeed)

    fun pushSpeed(sourceMass: Float, targetMass: Float): Float {
        if (!sourceMass.isFinite() || !targetMass.isFinite() || sourceMass <= 0f || targetMass < 0f) return 0f
        return (260f * sqrt(sourceMass / targetMass.coerceAtLeast(1f))).coerceIn(140f, 450f)
    }

    fun onSprintEnd(sprint: Sprint): Sprint {
        if (sprint.phase != Phase.SPRINTING && sprint.phase != Phase.SETTLING) return sprint
        if (sprint.settled) return sprint.copy(phase = Phase.DONE)
        return sprint.copy(phase = Phase.DONE, settled = true)
    }

}
