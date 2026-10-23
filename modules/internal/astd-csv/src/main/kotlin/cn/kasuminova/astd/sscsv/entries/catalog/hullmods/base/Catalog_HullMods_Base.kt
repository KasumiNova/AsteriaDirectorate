package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.base

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SCRIPT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

object HullMod_astd_dual_mode_switcher : HullModEntry() {
    override val id: String = "astd_dual_mode_switcher"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = ""
    override val tags: String = "no_drop, no_drop_salvage"
    override val unlocked: Boolean = false
    override val hidden: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dual_mode_switcher_crew.png"
}

object HullMod_astd_mode_crewed : HullModEntry() {
    override val id: String = "astd_mode_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDCrewedModeHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dual_mode_switcher_crew.png"
}

object HullMod_astd_mode_automated : HullModEntry() {
    override val id: String = "astd_mode_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDAutomatedModeHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dual_mode_switcher_auto.png"
}

object HullMod_astd_mode_next_crewed : HullModEntry() {
    override val id: String = "astd_mode_next_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 0
    override val rarity: Int = 0
    override val tech: String = "astd_hidden"
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = PLACEHOLDER_SCRIPT
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dual_mode_switcher_crew.png"
}

object HullMod_astd_mode_next_automated : HullModEntry() {
    override val id: String = "astd_mode_next_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 0
    override val rarity: Int = 0
    override val tech: String = "astd_hidden"
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = PLACEHOLDER_SCRIPT
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dual_mode_switcher_auto.png"
}

object HullMod_astd_sc_intel_link : HullModEntry() {
    override val id: String = "astd_sc_intel_link"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = ""
    override val tags: String = "no_drop, no_drop_salvage"
    override val unlocked: Boolean = false
    override val hidden: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.ASTDIntelLinkHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/icons/skills/astd_skill_combat_intel.png"
}
