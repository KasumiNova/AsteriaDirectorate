package cn.kasuminova.astd.combat.shipsystems

import kotlin.math.pow
import kotlin.math.sqrt

internal object StarwingShiftMath {
    fun easeInOutExpo(progress: Float): Float = when {
        progress <= 0f -> 0f
        progress >= 1f -> 1f
        progress < 0.5f -> 2f.pow(20f * progress - 10f) / 2f
        else -> (2f - 2f.pow(-20f * progress + 10f)) / 2f
    }

    fun distanceAfterShift(distance: Float, shift: Float, approach: Float): Float =
        sqrt((distance * distance + shift * shift - 2f * distance * shift * approach).coerceAtLeast(0f))

    fun radialImpulse(sourceMass: Float, targetMass: Float, outwardSpeed: Float, falloff: Float): Float {
        val speed = (200f * sqrt(sourceMass / targetMass.coerceAtLeast(1f))).coerceIn(50f, 450f) * falloff
        if (speed <= 0f) return 0f
        return maxOf(speed, speed - outwardSpeed)
    }
}
