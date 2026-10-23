package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

object GravPhaseDeckTuning {

    val FLUX_RETURN_RATIO = ScalingEntry(0.4f, 0.5f, 0.8f)

    const val MOTHERSHIP_FLUX_LEVEL_DISABLE = 0.8f

    fun resolveReturnRatio(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        tuning.valueFor(FLUX_RETURN_RATIO, isPlayer)

    fun returnAmount(fluxDelta: Float, ratio: Float): Float =
        if (fluxDelta <= 0f) 0f else fluxDelta * ratio
}
