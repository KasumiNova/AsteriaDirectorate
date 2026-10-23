package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

object FighterGravLinkTuning {

    val TIME_MULT_BONUS = ScalingEntry(1.0f, 1.5f, 2.0f)

    val DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.25f, 0.5f, 0.75f)

    const val NO_FIGHTER_CANCEL_GRACE_SECONDS = 1f

    data class Values(
        val timeMult: Float,
        val damageTakenMult: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        timeMult = 1f + tuning.valueFor(TIME_MULT_BONUS, isPlayer),
        damageTakenMult = 1f - tuning.valueFor(DAMAGE_TAKEN_REDUCTION, isPlayer),
    )

    fun softFluxNow(currFlux: Float, hardFlux: Float): Float = (currFlux - hardFlux).coerceAtLeast(0f)
}
