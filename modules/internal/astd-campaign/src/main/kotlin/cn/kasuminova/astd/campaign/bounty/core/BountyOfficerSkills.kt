package cn.kasuminova.astd.campaign.bounty.core

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
        "astd_zw_001_Standard" to listOf(
            "combat_endurance", "field_modulation", "gunnery_implants", "helmsmanship",
            "missile_specialization", "point_defense", "polarized_armor", "systems_expertise",
        ),
        "astd_zw_001_Combat" to listOf(
            "combat_endurance", "energy_weapon_mastery", "field_modulation", "gunnery_implants",
            "helmsmanship", "missile_specialization", "polarized_armor", "systems_expertise",
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

    fun forVariant(variantId: String?): List<String>? = TABLES[variantId]
}
