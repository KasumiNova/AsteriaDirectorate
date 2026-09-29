package cn.kasuminova.astd.campaign.bounty.core

/**
 * 赏金 ASTD 变体的核心军官技能表：赏金舰队军官技能组成的真相来源。
 *
 * 源自实机装配导出的核心军官 dump（素材：按 hullId+displayName 分组，组内技能一致，
 * 原 dump 全为 omega 9 级配置）。键为 contents/data/variants/ 下正式 stock variant id；
 * 技能全 2 级照用，军官等级不随表——按实际装舰核心档定（BountyFleetTunerImpl.assignCrew）。
 * 核心档位技能位 N 小于表长时，由全局技能优先级表提供取舍顺序
 * （BountyFleetTunerImpl.resolveOfficerSkills）；未登记变体（余晖等）退回全局优先级表取前 N。
 */
object BountyOfficerSkills {

    val TABLES: Map<String, List<String>> = mapOf(
        "astd_lh_001_Standard" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_lh_001_Missile" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_lh_002_Standard" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_lh_002_Omega" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "missile_specialization", "systems_expertise", "target_analysis",
        ),
        "astd_xc_001_Standard" to listOf(
            "combat_endurance", "damage_control", "field_modulation", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_001_Omega" to listOf(
            "combat_endurance", "damage_control", "field_modulation", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_002_Standard" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_101_Standard" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "ordnance_expert", "systems_expertise", "target_analysis",
        ),
        "astd_xc_102_Standard" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_102_Combat" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "ordnance_expert", "polarized_armor", "systems_expertise",
        ),
        "astd_xc_103_Standard" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_002_Standard" to listOf(
            "combat_endurance", "field_modulation", "helmsmanship", "impact_mitigation",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_002_Omega" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_101_Standard" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "impact_mitigation", "systems_expertise", "target_analysis",
        ),
        "astd_zw_102_Fighter" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "point_defense", "polarized_armor", "systems_expertise",
        ),
        "astd_zw_102_Bomber" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "point_defense", "polarized_armor", "systems_expertise",
        ),
        "astd_zw_102_Hybrid" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "point_defense", "polarized_armor", "systems_expertise",
        ),
        "astd_zw_103_Standard" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_103_Strike" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "missile_specialization", "polarized_armor", "systems_expertise",
        ),
    )

    /** 变体技能表（无登记返回 null，调用方退回全局优先级表取前 N）。 */
    fun forVariant(variantId: String?): List<String>? = TABLES[variantId]
}
