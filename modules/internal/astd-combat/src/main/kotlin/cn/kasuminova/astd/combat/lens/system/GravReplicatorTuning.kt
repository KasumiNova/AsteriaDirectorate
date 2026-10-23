package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

object GravReplicatorTuning {

    const val COPY_COUNT = 2

    const val FIRST_COPY_DELAY = 0.5f

    const val COPY_INTERVAL = 0.5f

    val REPLICA_DAMAGE_RATIO = ScalingEntry(0.35f, 0.50f, 1.00f)

    val REPLICA_FLUX_RATIO = ScalingEntry(0.60f, 0.50f, 0.20f)

    data class Values(
        val damageRatio: Float,
        val fluxRatio: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        damageRatio = tuning.valueFor(REPLICA_DAMAGE_RATIO, isPlayer),
        fluxRatio = tuning.valueFor(REPLICA_FLUX_RATIO, isPlayer),
    )

    fun copyDueTime(copyIndex: Int): Float = FIRST_COPY_DELAY + copyIndex * COPY_INTERVAL

    fun replicaDamage(baseDamage: Float, ratio: Float): Float = baseDamage * ratio

    fun replicaFlux(fluxPerShot: Float, ratio: Float): Float = fluxPerShot * ratio
}
