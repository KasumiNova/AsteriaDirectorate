package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap

internal data class GravityCollapseWeaponSpec(
    val beamScale: Float = 1f,
    val beamWidthMul: Float = 1f,
    val aoeRadius: Float = 200f,

    val aoeRequireDamageTarget: Boolean = true,
    val aoeAffectAlliesAndNeutral: Boolean = false,
    val aoeAffectNonShips: Boolean = false,
    val aoeAffectHulks: Boolean = false,

    val armorPierceDamageMult: Float = 2f,

    val phasedDamageRatio: ScalingEntry = DEFAULT_PHASED_DAMAGE_RATIO,

    val aoeDamageRatio: ScalingEntry,
    val hullDamageRatio: ScalingEntry,
    val mobilityReduction: ScalingEntry,
    val mobilityDuration: ScalingEntry,
) {
    companion object {
        val DEFAULT_PHASED_DAMAGE_RATIO = ScalingEntry(0.10f, 0.20f, 0.50f, ScalingMap.LINEAR)
    }
}

internal object GravityCollapseWeaponSpecs {

    private val MOBILITY_DURATION = ScalingEntry(2f, 2.5f, 4f, ScalingMap.LINEAR)

    private val HULL_RATIO_LARGE = ScalingEntry(0.02f, 0.04f, 0.10f, ScalingMap.LINEAR)
    private val HULL_RATIO_MEDIUM = ScalingEntry(0.015f, 0.03f, 0.075f, ScalingMap.LINEAR)
    private val HULL_RATIO_SMALL = ScalingEntry(0.01f, 0.02f, 0.05f, ScalingMap.LINEAR)

    private val MOBILITY_SMALL_PD = ScalingEntry(0.25f, 0.30f, 0.45f, ScalingMap.LINEAR)

    private val GCP_PD_SPEC = GravityCollapseWeaponSpec(
        beamScale = 0.55f,
        beamWidthMul = 0.455f,
        aoeRadius = 200f,
        aoeRequireDamageTarget = false,
        aoeAffectAlliesAndNeutral = true,
        aoeAffectNonShips = true,
        aoeAffectHulks = true,
        armorPierceDamageMult = 2f,
        aoeDamageRatio = ScalingEntry(0.40f, 0.50f, 0.80f, ScalingMap.LINEAR),
        hullDamageRatio = HULL_RATIO_SMALL,
        mobilityReduction = MOBILITY_SMALL_PD,
        mobilityDuration = MOBILITY_DURATION,
    )

    private val specs: Map<String, GravityCollapseWeaponSpec> = mapOf(
        "astd_gcp12" to GravityCollapseWeaponSpec(
            beamScale = 1.0f,
            beamWidthMul = 0.80f,
            aoeRadius = 400f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
            armorPierceDamageMult = 5f,
            aoeDamageRatio = ScalingEntry(0.15f, 0.20f, 0.35f, ScalingMap.LINEAR),
            hullDamageRatio = HULL_RATIO_LARGE,
            mobilityReduction = ScalingEntry(0.45f, 0.50f, 0.65f, ScalingMap.LINEAR),
            mobilityDuration = MOBILITY_DURATION,
        ),
        "astd_gcp8" to GravityCollapseWeaponSpec(
            beamScale = 0.85f,
            beamWidthMul = 0.49f,
            aoeRadius = 300f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
            armorPierceDamageMult = 4f,
            aoeDamageRatio = ScalingEntry(0.20f, 0.25f, 0.30f, ScalingMap.LINEAR),
            hullDamageRatio = HULL_RATIO_MEDIUM,
            mobilityReduction = ScalingEntry(0.35f, 0.40f, 0.55f, ScalingMap.LINEAR),
            mobilityDuration = MOBILITY_DURATION,
        ),
        "astd_gcp4" to GravityCollapseWeaponSpec(
            beamScale = 0.70f,
            beamWidthMul = 0.49f,
            aoeRadius = 200f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
            armorPierceDamageMult = 3f,
            aoeDamageRatio = ScalingEntry(0.22f, 0.33f, 0.66f, ScalingMap.LINEAR),
            hullDamageRatio = HULL_RATIO_SMALL,
            mobilityReduction = MOBILITY_SMALL_PD,
            mobilityDuration = MOBILITY_DURATION,
        ),
        "astd_gcp2" to GCP_PD_SPEC,
        "astd_gcp_fighter" to GCP_PD_SPEC,
    )

    fun forWeaponId(weaponId: String?): GravityCollapseWeaponSpec? {
        if (weaponId.isNullOrBlank()) return null
        return specs[weaponId]
    }
}
