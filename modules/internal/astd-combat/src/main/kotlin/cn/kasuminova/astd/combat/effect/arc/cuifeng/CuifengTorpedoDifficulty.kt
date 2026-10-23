package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

object CuifengTorpedoDifficulty {

    val FLUX_ADAPTIVE_X = ScalingEntry(0.25f, 0.50f, 1.25f, ScalingMap.LINEAR)

    val HARD_FLUX_Y = ScalingEntry(0.02f, 0.04f, 0.10f, ScalingMap.LINEAR)

    val HULL_FRIGATE = ScalingEntry(0.00f, 0.05f, 0.20f, ScalingMap.LINEAR)

    val HULL_DESTROYER = ScalingEntry(0.10f, 0.20f, 0.50f, ScalingMap.LINEAR)

    val HULL_CRUISER = ScalingEntry(0.20f, 0.40f, 1.00f, ScalingMap.LINEAR)

    val HULL_CAPITAL = ScalingEntry(0.30f, 0.60f, 1.50f, ScalingMap.LINEAR)

    val DP_PCT = ScalingEntry(0.02f, 0.04f, 0.10f, ScalingMap.LINEAR)

    val DP_BASELINE_FRIGATE = 5f
    val DP_BASELINE_DESTROYER = 10f
    val DP_BASELINE_CRUISER = 20f
    val DP_BASELINE_CAPITAL = 40f

    const val AOE_RADIUS = 150f

    const val RETARGET_INTERVAL = 0.25f

    const val SLOW_PHASE_END = 0.25f
    const val RAMP_PHASE_END = 0.50f
    const val SLOW_SPEED_FACTOR = 0.50f

    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        tuning.valueFor(entry, sourceOwner == 0)
}
