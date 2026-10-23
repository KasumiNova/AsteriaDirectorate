package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry

object ImaginaryWingsTuning {

    const val WINDOW_SECONDS = 3f

    val SPEED_BOOST_PERCENT = ScalingEntry(75f, 100f, 175f)

    val FULL_SPEED_BOOST_PERCENT = ScalingEntry(25f, 50f, 125f)

    val OVER_CAP_DAMAGE_PER_PERCENT = ScalingEntry(0.5f, 1f, 2.5f)

    fun speedBonusPercent(elapsedSeconds: Float, peakPercent: Float): Float =
        if (elapsedSeconds < 0f || elapsedSeconds >= WINDOW_SECONDS) {
            0f
        } else {
            peakPercent * (1f - elapsedSeconds / WINDOW_SECONDS)
        }

    fun restBoostPercent(fullSpeedPercent: Float): Float =
        (fullSpeedPercent / 2f - 50f).coerceAtMost(0f)

    fun sustainedMult(ratio: Float, fullSpeedPercent: Float): Float {
        val r = ratio.coerceIn(0f, 1f)
        val rest = restBoostPercent(fullSpeedPercent)
        return 1f + (rest + (fullSpeedPercent - rest) * r) / 100f
    }

    fun overCapDamageMult(ratio: Float, overCapPerPercent: Float): Float {
        val r = ratio.coerceAtLeast(0f)
        if (r <= 1f) return 1f
        return 1f + (r - 1f) * overCapPerPercent
    }
}
