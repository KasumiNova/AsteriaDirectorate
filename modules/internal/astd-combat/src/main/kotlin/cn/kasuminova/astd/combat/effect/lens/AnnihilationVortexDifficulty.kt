package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexDifficulty.ABSORB_RADIUS_MIN
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexDifficulty.ABSORB_RADIUS_MUL
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexDifficulty.COLLAPSE_RAD_MUL
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexDifficulty.POOL_FLOOR
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

object AnnihilationVortexDifficulty {

    val RADIUS = ScalingEntry(150f, 187.5f, 300f, ScalingMap.LINEAR)

    val AOE_MULT = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    val ABSORB_LIMIT = ScalingEntry(3200f, 8800f, 16000f, ScalingMap.LINEAR)

    const val EXCESS_RATIO = 0.25f

    const val POOL_FLOOR = 500f

    const val COLLAPSE_RAD_MUL = 1.5f

    const val PULL_ACCEL_MAX = 1200f

    const val ABSORB_RADIUS_MIN = 30f

    const val ABSORB_RADIUS_MUL = 0.25f

    val CONVERSION: Map<com.fs.starfarer.api.combat.DamageType, Float> = mapOf(
        com.fs.starfarer.api.combat.DamageType.ENERGY to 1.0f,
        com.fs.starfarer.api.combat.DamageType.HIGH_EXPLOSIVE to 0.5f,
        com.fs.starfarer.api.combat.DamageType.KINETIC to 0.5f,
        com.fs.starfarer.api.combat.DamageType.FRAGMENTATION to 0.25f,
    )

    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        tuning.valueFor(entry, sourceOwner == 0)

    fun absorbRadiusFor(radius: Float): Float =
        maxOf(ABSORB_RADIUS_MIN, radius.coerceAtLeast(ABSORB_RADIUS_MIN) * ABSORB_RADIUS_MUL)

    fun collapseDamage(poolTotal: Float, aoeMult: Float): Float =
        maxOf(poolTotal, POOL_FLOOR) * aoeMult

    fun collapseRadiusFor(radius: Float): Float = radius * COLLAPSE_RAD_MUL
}
