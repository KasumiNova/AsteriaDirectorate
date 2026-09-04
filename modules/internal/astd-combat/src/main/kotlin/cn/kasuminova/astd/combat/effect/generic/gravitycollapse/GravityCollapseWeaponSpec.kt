package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

/**
 * “引力坍缩炮”通用配置（按武器 id 分发）。
 *
 * 目前仅把“系列差异”收敛在：
 * - 光束/环尺寸（[beamScale]）
 * - 持续命中额外 AOE 的半径（[aoeRadiusBase]）
 *
 * 其余观感与机制保持一致，便于后续统一调参。
 */
internal data class GravityCollapseWeaponSpec(
    /** 视觉缩放：影响束宽、环尺寸、炮口爆发等的“整体尺寸感”。 */
    val beamScale: Float = 1f,
    /** 仅影响“主束宽度感”的倍率（不缩放环等附件）。 */
    val beamWidthMul: Float = 1f,
    /** 额外 AOE 的基础半径（su）；会再乘上 intensity 相关的系数。 */
    val aoeRadiusBase: Float = 190f,

    /** 是否要求 beam 命中（damageTarget != null）才触发 AOE。 */
    val aoeRequireDamageTarget: Boolean = true,
    /** AOE 是否允许伤害友军/中立。 */
    val aoeAffectAlliesAndNeutral: Boolean = false,
    /** AOE 是否影响非 Ship 实体（陨石/残骸/导弹等）。 */
    val aoeAffectNonShips: Boolean = false,
    /** AOE 是否影响残骸（ShipAPI.isHulk）。 */
    val aoeAffectHulks: Boolean = false,
)

internal object GravityCollapseWeaponSpecs {

    private val specs: Map<String, GravityCollapseWeaponSpec> = mapOf(
        // GCP（Gravity Collapse Projector）系列
        // Large / Medium / Small / PD：差异主要在伤害潜力（weapon_data）、束体尺寸与射程。
        "astd_gcp12" to GravityCollapseWeaponSpec(
            beamScale = 1.0f,
            beamWidthMul = 0.80f,
            aoeRadiusBase = 225f * 1.5f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
        ),
        "astd_gcp8" to GravityCollapseWeaponSpec(
            beamScale = 0.85f,
            beamWidthMul = 0.49f,
            aoeRadiusBase = 185f * 1.5f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
        ),
        "astd_gcp4" to GravityCollapseWeaponSpec(
            beamScale = 0.70f,
            beamWidthMul = 0.49f,
            aoeRadiusBase = 145f * 1.5f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
        ),
        "astd_gcp2" to GravityCollapseWeaponSpec(
            beamScale = 0.55f,
            beamWidthMul = 0.455f,
            aoeRadiusBase = 110f * 1.5f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
        ),
    )

    fun forWeaponId(weaponId: String?): GravityCollapseWeaponSpec? {
        if (weaponId.isNullOrBlank()) return null
        return specs[weaponId]
    }
}
