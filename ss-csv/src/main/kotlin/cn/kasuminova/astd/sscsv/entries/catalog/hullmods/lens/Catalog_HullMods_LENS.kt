package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.lens

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_DESC
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SCRIPT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SHORT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** LENS 设计系 HullMod（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。 */

object HullMod_astd_lens_array_core : HullModEntry() {
    override val id: String = "astd_lens_array_core"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_lens_parallax_decks : HullModEntry() {
    override val id: String = "astd_lens_parallax_decks"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensParallaxDecksHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_lens_mode_switcher : HullModEntry() {
    override val id: String = "astd_lens_mode_switcher"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = "LENS"
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensDualModeSwitcherHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_lens_mode_crewed : HullModEntry() {
    override val id: String = "astd_lens_mode_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensCrewedModeHullMod"
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_lens_mode_automated : HullModEntry() {
    override val id: String = "astd_lens_mode_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensAutomatedModeHullMod"
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_lens_mode_next_crewed : HullModEntry() {
    override val id: String = "astd_lens_mode_next_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 0
    override val rarity: Int = 0
    override val tech: String = "astd_hidden"
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_lens_mode_next_automated : HullModEntry() {
    override val id: String = "astd_lens_mode_next_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 0
    override val rarity: Int = 0
    override val tech: String = "astd_hidden"
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_echo_emitter : HullModEntry() {
    override val id: String = "astd_echo_emitter"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_echo_emitter.png"
}

object HullMod_astd_echo_reentry_buffer : HullModEntry() {
    override val id: String = "astd_echo_reentry_buffer"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_echo_reentry_buffer.png"
}

object HullMod_astd_phase_resonance_jamming : HullModEntry() {
    override val id: String = "astd_phase_resonance_jamming"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_phase_resonance_jamming.png"
}

object HullMod_astd_singularity_displacement_armor : HullModEntry() {
    override val id: String = "astd_singularity_displacement_armor"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_singularity_displacement_armor.png"
}

object HullMod_astd_lens_horizon_suppression_mesh : HullModEntry() {
    override val id: String = "astd_lens_horizon_suppression_mesh"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_lens_horizon_suppression_mesh.png"
}

object HullMod_astd_fleet_coordination_relay : HullModEntry() {
    override val id: String = "astd_fleet_coordination_relay"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_fleet_coordination_relay.png"
}

object HullMod_astd_dark_tide_jammer : HullModEntry() {
    override val id: String = "astd_dark_tide_jammer"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_dark_tide_jammer.png"
}

object HullMod_astd_phase_diffraction_shield : HullModEntry() {
    override val id: String = "astd_phase_diffraction_shield"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "LENS"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_phase_diffraction_shield.png"
}
