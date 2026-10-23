package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.difficulty.ScalingEntry

internal data class GravityCollapseOnHitConfig(
    val tickInterval: Float = 0.5f,
    val aoeRadius: Float = 200f,

    val requireDamageTarget: Boolean = true,
    val affectAlliesAndNeutral: Boolean = false,
    val affectNonShips: Boolean = false,
    val affectHulks: Boolean = false,

    val vfxScale: Float = 1f,

    val armorPierceDamageMult: Float = 2f,

    val aoeDamageRatio: ScalingEntry,
    val hullDamageRatio: ScalingEntry,
    val mobilityReduction: ScalingEntry,
    val mobilityDuration: ScalingEntry,
    val phasedDamageRatio: ScalingEntry,
)
