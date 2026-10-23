package cn.kasuminova.astd.combat.effect.lens.iceshard

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

object IceShardMirvDifficulty {

    val DOT_RATIO = ScalingEntry(0.25f, 0.50f, 1.00f, ScalingMap.LINEAR)

    val AMP = ScalingEntry(0.15f, 0.25f, 0.50f, ScalingMap.LINEAR)

    const val SPLIT_RANGE = 600f

    const val SPLIT_IMMUNITY_SECONDS = 1f

    const val SHARD_COUNT = 15

    const val SPLIT_CONE_DEG = 20f

    const val TOTAL_DAMAGE = 6000f

    const val SHARD_DAMAGE_MIN = 200f
    const val SHARD_DAMAGE_MAX = 800f

    const val SHARD_BASE_SPEED = 1000f

    const val SHARD_RANGE = 1000f

    const val ATTACH_DURATION = 5f

    const val TICK_INTERVAL = 1f

    const val AMP_RADIUS = 15f

    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        tuning.valueFor(entry, sourceOwner == 0)
}
