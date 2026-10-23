package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

object SuppressionModeTuning {

    val SPEED_MANEUVER_REDUCTION = ScalingEntry(0.40f, 0.50f, 0.80f)

    val WEAPON_FLUX_REDUCTION = ScalingEntry(0.40f, 0.50f, 0.80f)

    val RANGE_BONUS = ScalingEntry(0.20f, 0.30f, 0.60f)

    val SHIELD_DAMAGE_REDUCTION = ScalingEntry(0.20f, 0.30f, 0.60f)

    const val ROF_BONUS = 0.30f

    const val HARD_FLUX_FRACTION_START = 0.02f

    const val HARD_FLUX_FRACTION_MAX = 0.06f

    const val HARD_FLUX_RAMP_SECONDS = 4f

    data class Values(
        val speedManeuverMult: Float,
        val weaponFluxMult: Float,
        val rangeBonusPercent: Float,
        val shieldDamageTakenMult: Float,
        val rofBonusPercent: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        speedManeuverMult = 1f - tuning.valueFor(SPEED_MANEUVER_REDUCTION, isPlayer),
        weaponFluxMult = 1f - tuning.valueFor(WEAPON_FLUX_REDUCTION, isPlayer),
        rangeBonusPercent = tuning.valueFor(RANGE_BONUS, isPlayer),
        shieldDamageTakenMult = 1f - tuning.valueFor(SHIELD_DAMAGE_REDUCTION, isPlayer),
        rofBonusPercent = ROF_BONUS,
    )

    fun hardFluxFractionPerSecond(activeSeconds: Float): Float {
        val progress = (activeSeconds.coerceAtLeast(0f) / HARD_FLUX_RAMP_SECONDS).coerceAtMost(1f)
        return HARD_FLUX_FRACTION_START + (HARD_FLUX_FRACTION_MAX - HARD_FLUX_FRACTION_START) * progress
    }
}
