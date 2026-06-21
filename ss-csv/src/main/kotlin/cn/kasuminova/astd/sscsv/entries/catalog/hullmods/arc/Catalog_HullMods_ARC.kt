package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.arc

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_DESC
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SCRIPT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SHORT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** ARC 设计系 HullMod（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。 */

object HullMod_astd_arc_loop_interface : HullModEntry() {
    override val id: String = "astd_arc_loop_interface"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcLoopInterfaceHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

object HullMod_astd_virtual_particle_lattice_web : HullModEntry() {
    override val id: String = "astd_virtual_particle_lattice_web"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDVirtualParticleLatticeWebHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

object HullMod_astd_transient_potential_manifold : HullModEntry() {
    override val id: String = "astd_transient_potential_manifold"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDTransientPotentialManifoldHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_vectorized_jet_array.png"
}

object HullMod_astd_thermodynamic_exchange : HullModEntry() {
    override val id: String = "astd_thermodynamic_exchange"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_thermodynamic_exchange.png"
}

object HullMod_astd_damage_clamp_field : HullModEntry() {
    override val id: String = "astd_damage_clamp_field"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_damage_clamp_field.png"
}

object HullMod_astd_nano_restoration_protocol : HullModEntry() {
    override val id: String = "astd_nano_restoration_protocol"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDNanoRestorationProtocolHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_nano_restoration_protocol.png"
}

// 注：arc 自造切换器 astd_arc_flare_mode_switcher 已废弃，改用通用切换器 astd_dual_mode_switcher
// （见 entries/catalog/hullmods/base/Catalog_HullMods_Base.kt）。原条目已移除。

object HullMod_astd_arc_flare_mode_crewed : HullModEntry() {
    override val id: String = "astd_arc_flare_mode_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = "ARC"
    override val tags: String = ""
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcFlareCrewedModeHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_nano_restoration_protocol.png"
}

object HullMod_astd_arc_flare_mode_automated : HullModEntry() {
    override val id: String = "astd_arc_flare_mode_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = "ARC"
    override val tags: String = ""
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcFlareAutomatedModeHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_vectorized_jet_array.png"
}

object HullMod_astd_arc_flare_mode_next_crewed : HullModEntry() {
    override val id: String = "astd_arc_flare_mode_next_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 0
    override val rarity: Int = 0
    override val tech: String = "astd_hidden"
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

object HullMod_astd_arc_flare_mode_next_automated : HullModEntry() {
    override val id: String = "astd_arc_flare_mode_next_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 0
    override val rarity: Int = 0
    override val tech: String = "astd_hidden"
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

object HullMod_astd_vectorized_jet_array : HullModEntry() {
    override val id: String = "astd_vectorized_jet_array"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_vectorized_jet_array.png"
}

object HullMod_astd_arch_stabilizer : HullModEntry() {
    override val id: String = "astd_arch_stabilizer"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_arch_stabilizer.png"
}

object HullMod_astd_redundant_vents : HullModEntry() {
    override val id: String = "astd_redundant_vents"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT
    override val sprite: String = "graphics/hullmods/astd_redundant_vents.png"
}

object HullMod_astd_arc_advanced_fire_control : HullModEntry() {
    override val id: String = "astd_arc_advanced_fire_control"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAdvancedFireControlHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

object HullMod_astd_arc_shared_tactical_network : HullModEntry() {
    override val id: String = "astd_arc_shared_tactical_network"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcSharedTacticalNetworkHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

object HullMod_astd_plasma_armor_shield : HullModEntry() {
    override val id: String = "astd_plasma_armor_shield"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDPlasmaArmorShieldHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arch_stabilizer.png"
}

object HullMod_astd_ionized_recoil_accumulator : HullModEntry() {
    override val id: String = "astd_ionized_recoil_accumulator"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDIonizedRecoilAccumulatorHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_thermodynamic_exchange.png"
}

object HullMod_astd_arc_advanced_targeting_system : HullModEntry() {
    override val id: String = "astd_arc_advanced_targeting_system"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAdvancedTargetingSystemHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

object HullMod_astd_distributed_pursuit_network : HullModEntry() {
    override val id: String = "astd_distributed_pursuit_network"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDDistributedPursuitNetworkHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_vectorized_jet_array.png"
}
