package cn.kasuminova.astd.combat.effect.joint

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

object BurstFlowTuning {

    val TIME_MULT_BONUS = ScalingEntry(2.0f, 2.5f, 4.0f)

    val SPEED_MANEUVER_BONUS = ScalingEntry(1.0f, 1.5f, 2.5f)

    val AMMO_REGEN_BONUS = ScalingEntry(1.0f, 2.0f, 5.0f)

    val FLUX_DISSIPATION_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f)

    val MOMENTUM_BONUS = ScalingEntry(1.5f, 2.0f, 3.0f)

    data class Values(
        val timeMult: Float,
        val speedManeuverMult: Float,
        val ammoRegenMult: Float,
        val fluxDissipationMult: Float,
        val momentumMult: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        timeMult = 1f + tuning.valueFor(TIME_MULT_BONUS, isPlayer),
        speedManeuverMult = 1f + tuning.valueFor(SPEED_MANEUVER_BONUS, isPlayer),
        ammoRegenMult = 1f + tuning.valueFor(AMMO_REGEN_BONUS, isPlayer),
        fluxDissipationMult = 1f + tuning.valueFor(FLUX_DISSIPATION_BONUS, isPlayer),
        momentumMult = tuning.valueFor(MOMENTUM_BONUS, isPlayer),
    )
}
