package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

object GravTimeflowTuning {

    const val SYSTEM_ID = "astd_grav_timeflow_interference"

    const val BASE_RANGE = 2000f

    const val ACTIVE_SECONDS = 10f

    const val COOLDOWN_SECONDS = 30f

    val TIME_MULT = ScalingEntry(1.5f, 2.0f, 3.5f)

    val SYSTEM_COOLDOWN_REDUCTION = ScalingEntry(0.5f, 0.6f, 0.9f)

    data class Values(
        val timeMult: Float,
        val cooldownReduction: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        timeMult = tuning.valueFor(TIME_MULT, isPlayer),
        cooldownReduction = tuning.valueFor(SYSTEM_COOLDOWN_REDUCTION, isPlayer),
    )
}
