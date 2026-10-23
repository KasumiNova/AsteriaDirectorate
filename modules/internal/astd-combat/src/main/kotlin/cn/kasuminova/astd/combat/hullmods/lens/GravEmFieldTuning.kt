package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAuraUtil

object GravEmFieldTuning {

    val RANGE = ScalingEntry(1250f, 1500f, 2250f)

    val EMP_RESIST_REDUCTION = ScalingEntry(0.25f, 0.50f, 1.25f)

    val STAT_PENALTY = ScalingEntry(0.10f, 0.20f, 0.50f)

    val ACCURACY_PENALTY = ScalingEntry(0.20f, 0.40f, 1.00f)

    val EDGE_SCALE = ScalingEntry(0.10f, 0.20f, 0.50f)

    const val FULL_EFFECT_FRACTION = 0.5f

    const val WAVE_INTERVAL = 0.2f

    const val WAVE_COUNT_MIN = 2
    const val WAVE_COUNT_MAX = 4

    data class Values(
        val range: Float,
        val empResistReduction: Float,
        val statPenalty: Float,
        val accuracyPenalty: Float,
        val edgeScale: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        range = tuning.valueFor(RANGE, isPlayer),
        empResistReduction = tuning.valueFor(EMP_RESIST_REDUCTION, isPlayer),
        statPenalty = tuning.valueFor(STAT_PENALTY, isPlayer),
        accuracyPenalty = tuning.valueFor(ACCURACY_PENALTY, isPlayer),
        edgeScale = tuning.valueFor(EDGE_SCALE, isPlayer),
    )

    fun effectScale(distance: Float, range: Float, edgeScale: Float): Float =
        ASTDArcAuraUtil.distanceFalloff(distance, range * FULL_EFFECT_FRACTION, range, edgeScale)
}
