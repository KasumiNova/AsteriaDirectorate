package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.arc

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

object HullMod_astd_arc_singularity_power : HullModEntry() {
    override val id: String = "astd_arc_singularity_power"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityPowerHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_singularity_power.png"
}

object HullMod_astd_arc_nano_restoration_protocol : HullModEntry() {
    override val id: String = "astd_arc_nano_restoration_protocol"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDNanoRestorationProtocolHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_nano_restoration_protocol_arc.png"
}

object HullMod_astd_arc_advanced_energy_integration : HullModEntry() {
    override val id: String = "astd_arc_advanced_energy_integration"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDAdvancedEnergyIntegrationHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_advanced_energy_integration.png"
}

object HullMod_astd_imaginary_wings : HullModEntry() {
    override val id: String = "astd_imaginary_wings"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDImaginaryWingsHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_imaginary_wings.png"
}

object HullMod_astd_arc_advanced_fire_control : HullModEntry() {
    override val id: String = "astd_arc_advanced_fire_control"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAdvancedFireControlHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_advanced_fire_control.png"
}

object HullMod_astd_plasma_armor_shield : HullModEntry() {
    override val id: String = "astd_plasma_armor_shield"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDPlasmaArmorShieldHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_plasma_armor_shield.png"
}

object HullMod_astd_ionized_recoil_accumulator : HullModEntry() {
    override val id: String = "astd_ionized_recoil_accumulator"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDIonizedRecoilAccumulatorHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_ionized_recoil_accumulator.png"
}

object HullMod_astd_arc_advanced_targeting_system : HullModEntry() {
    override val id: String = "astd_arc_advanced_targeting_system"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-星坠"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAdvancedTargetingSystemHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_advanced_targeting_system.png"
}
