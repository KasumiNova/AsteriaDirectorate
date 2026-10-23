package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.lens

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

object HullMod_astd_lens_singularity_power : HullModEntry() {
    override val id: String = "astd_lens_singularity_power"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityPowerHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_singularity_power.png"
}

object HullMod_astd_lens_nano_restoration_protocol : HullModEntry() {
    override val id: String = "astd_lens_nano_restoration_protocol"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDNanoRestorationProtocolHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_nano_restoration_protocol_lens.png"
}

object HullMod_astd_dimensional_folding_deck : HullModEntry() {
    override val id: String = "astd_dimensional_folding_deck"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDDimensionalFoldingDeckHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dimensional_folding_deck.png"
}

object HullMod_astd_grav_em_field : HullModEntry() {
    override val id: String = "astd_grav_em_field"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.GravEmFieldHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_grav_em_field.png"
}

object HullMod_astd_grav_space_fold : HullModEntry() {
    override val id: String = "astd_grav_space_fold"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.GravSpaceFoldHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_grav_space_fold.png"
}

object HullMod_astd_grav_phase_deck : HullModEntry() {
    override val id: String = "astd_grav_phase_deck"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDGravPhaseDeckHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_grav_phase_deck.png"
}

object HullMod_astd_advanced_ecm_network : HullModEntry() {
    override val id: String = "astd_advanced_ecm_network"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.AdvancedEcmNetworkHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_advanced_ecm_network.png"
}
