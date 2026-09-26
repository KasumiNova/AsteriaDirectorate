package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap

/**
 * “引力坍缩炮”通用配置（按武器 id 分发）。
 *
 * 系列差异收敛在：
 * - 光束/环尺寸（[beamScale]）
 * - 持续命中额外 AOE 的半径（[aoeRadiusBase]）
 * - 难度缩放数值（[aoeDamageRatio] / [mobilityReduction] / [mobilityDuration] / [armorPierceMult]）
 *
 * 难度锚点口径（线性数值，k_s=1 取下限 / k_s=5 取上限，v2 锚点落在两点线性插值上）：
 * 范围高爆伤害比例（大/中/小/PD）20％/25％/33％/50％ ~ 40％/50％/66％/100％；
 * 航速机动性降低 50％/40％/30％/20％ ~ 75％/60％/45％/30％，持续 2s ~ 4s；
 * 穿甲力度（计算装甲减伤的伤害值 = 单次打击伤害 × 倍率）150％ ~ 500％（k_s 整数档 150/200/300/400/500％）。
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

    /** 范围高爆伤害比例（相对面板总伤害的 tick 折算值）三锚点。 */
    val aoeDamageRatio: ScalingEntry,
    /** 最大航速与机动性降低比例三锚点。 */
    val mobilityReduction: ScalingEntry,
    /** 机动抑制持续时间（秒）三锚点。 */
    val mobilityDuration: ScalingEntry,
    /** 穿甲力度三锚点：计算装甲减伤时的伤害值 = 单次打击伤害 × 本倍率。 */
    val armorPierceMult: ScalingEntry,
)

internal object GravityCollapseWeaponSpecs {

    /** 全系列共用的机动抑制时长锚点：2s（迟暮）~ 4s（破晓）。 */
    private val MOBILITY_DURATION = ScalingEntry(2f, 2.5f, 4f, ScalingMap.LINEAR)

    /**
     * 全系列共用的穿甲力度锚点：150％（迟暮）~ 500％（破晓），v2 设计基准 200％。
     * 分段线性在 k_s 整数档恰好命中 150/200/300/400/500％（k2→k5 段步进 100％/档）。
     */
    private val ARMOR_PIERCE_MULT = ScalingEntry(1.50f, 2.00f, 5.00f, ScalingMap.LINEAR)

    /** PD 规格（astd_gcp2 / 战机版 astd_gcp_fighter 共用：战机版数据全量复用舰装版）。 */
    private val GCP_PD_SPEC = GravityCollapseWeaponSpec(
        beamScale = 0.55f,
        beamWidthMul = 0.455f,
        aoeRadiusBase = 110f * 1.5f,
        aoeRequireDamageTarget = false,
        aoeAffectAlliesAndNeutral = true,
        aoeAffectNonShips = true,
        aoeAffectHulks = true,
        aoeDamageRatio = ScalingEntry(0.50f, 0.625f, 1.00f, ScalingMap.LINEAR),
        mobilityReduction = ScalingEntry(0.20f, 0.225f, 0.30f, ScalingMap.LINEAR),
        mobilityDuration = MOBILITY_DURATION,
        armorPierceMult = ARMOR_PIERCE_MULT,
    )

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
            aoeDamageRatio = ScalingEntry(0.20f, 0.25f, 0.40f, ScalingMap.LINEAR),
            mobilityReduction = ScalingEntry(0.50f, 0.5625f, 0.75f, ScalingMap.LINEAR),
            mobilityDuration = MOBILITY_DURATION,
            armorPierceMult = ARMOR_PIERCE_MULT,
        ),
        "astd_gcp8" to GravityCollapseWeaponSpec(
            beamScale = 0.85f,
            beamWidthMul = 0.49f,
            aoeRadiusBase = 185f * 1.5f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
            aoeDamageRatio = ScalingEntry(0.25f, 0.3125f, 0.50f, ScalingMap.LINEAR),
            mobilityReduction = ScalingEntry(0.40f, 0.45f, 0.60f, ScalingMap.LINEAR),
            mobilityDuration = MOBILITY_DURATION,
            armorPierceMult = ARMOR_PIERCE_MULT,
        ),
        "astd_gcp4" to GravityCollapseWeaponSpec(
            beamScale = 0.70f,
            beamWidthMul = 0.49f,
            aoeRadiusBase = 145f * 1.5f,
            aoeRequireDamageTarget = false,
            aoeAffectAlliesAndNeutral = true,
            aoeAffectNonShips = true,
            aoeAffectHulks = true,
            aoeDamageRatio = ScalingEntry(0.33f, 0.4125f, 0.66f, ScalingMap.LINEAR),
            mobilityReduction = ScalingEntry(0.30f, 0.3375f, 0.45f, ScalingMap.LINEAR),
            mobilityDuration = MOBILITY_DURATION,
            armorPierceMult = ARMOR_PIERCE_MULT,
        ),
        "astd_gcp2" to GCP_PD_SPEC,
        // 战机版引力坍缩炮 PD：数据与特效全量复用舰装版 astd_gcp2（仅不渲染武器贴图）
        "astd_gcp_fighter" to GCP_PD_SPEC,
    )

    fun forWeaponId(weaponId: String?): GravityCollapseWeaponSpec? {
        if (weaponId.isNullOrBlank()) return null
        return specs[weaponId]
    }
}
