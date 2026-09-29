package cn.kasuminova.astd.campaign.bounty.core

/**
 * 赏金 ASTD 变体的核心军官技能表。
 *
 * 源自实机装配导出的核心军官 dump（素材：按 hullId+displayName 分组，组内技能一致，
 * 原 dump 全为 omega 9 级配置）。键为 contents/data/variants/bounty/ 下导入变体 id；
 * 技能全 2 级照用，军官等级不随表——按实际装舰核心档定（BountyFleetTunerImpl.assignCrew）。
 * 非本表变体（余晖等）不覆盖技能，使用核心插件默认技能。
 */
object BountyOfficerSkills {

    val TABLES: Map<String, List<String>> = mapOf(
        "astd_lh_001_Standard_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_lh_001_Missile_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_lh_002_Standard_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_lh_002_Omega_Bounty" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "missile_specialization", "omega_ecm", "systems_expertise", "target_analysis",
        ),
        "astd_xc_001_Standard_Bounty" to listOf(
            "combat_endurance", "damage_control", "field_modulation", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_001_Omega_Bounty" to listOf(
            "combat_endurance", "damage_control", "field_modulation", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_002_Standard_Bounty" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_101_Standard_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "ordnance_expert", "systems_expertise", "target_analysis",
        ),
        "astd_xc_102_Standard_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_xc_102_Combat_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "ordnance_expert", "polarized_armor", "systems_expertise",
        ),
        "astd_xc_103_Standard_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_002_Standard_Bounty" to listOf(
            "combat_endurance", "field_modulation", "helmsmanship", "impact_mitigation",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_002_Omega_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_101_Standard_Bounty" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "impact_mitigation", "omega_ecm", "systems_expertise", "target_analysis",
        ),
        "astd_zw_102_Fighter_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "point_defense", "polarized_armor", "systems_expertise",
        ),
        "astd_zw_102_Bomber_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "point_defense", "polarized_armor", "systems_expertise",
        ),
        "astd_zw_102_Hybrid_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "point_defense", "polarized_armor", "systems_expertise",
        ),
        "astd_zw_103_Standard_Bounty" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise", "target_analysis",
        ),
        "astd_zw_103_Strike_Bounty" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "missile_specialization", "omega_ecm", "polarized_armor", "systems_expertise",
        ),
    )

    /** 变体技能表（无登记返回 null，调用方保持核心插件默认技能）。 */
    fun forVariant(variantId: String?): List<String>? = TABLES[variantId]
}
