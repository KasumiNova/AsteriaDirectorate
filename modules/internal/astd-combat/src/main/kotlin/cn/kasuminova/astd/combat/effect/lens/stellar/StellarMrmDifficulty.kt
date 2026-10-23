package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

object StellarMrmDifficulty {

    val FIGHTER_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    val WEAPON_EMP = ScalingEntry(2f, 4f, 10f, ScalingMap.LINEAR)

    val EXPLOSION_MULT = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    val LINE_CROSS_H = ScalingEntry(1.5f, 3.0f, 7.5f, ScalingMap.LINEAR)

    const val EXPLOSION_RADIUS = 50f

    const val RETARGET_INTERVAL = 0.25f

    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        tuning.valueFor(entry, sourceOwner == 0)
}
