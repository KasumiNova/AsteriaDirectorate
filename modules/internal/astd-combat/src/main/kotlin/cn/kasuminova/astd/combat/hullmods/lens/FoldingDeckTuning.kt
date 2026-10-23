package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import kotlin.math.roundToInt

object FoldingDeckTuning {

    const val OP_COST_MULT = 2.0f

    val WING_SIZE_BONUS = ScalingEntry(1.0f, 1.5f, 3.0f)

    val REFIT_RATE_BONUS = ScalingEntry(0.2f, 0.4f, 1.0f)

    const val EXTRA_FIGHTER_DURATION = 1e7f

    fun resolveWingSizeMult(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        tuning.valueFor(WING_SIZE_BONUS, isPlayer)

    fun refitTimeMult(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        1f / (1f + tuning.valueFor(REFIT_RATE_BONUS, isPlayer))

    fun wingSizeLimit(baseNum: Int, bonusMult: Float): Int =
        (baseNum * (1f + bonusMult)).roundToInt().coerceAtLeast(baseNum)
}
