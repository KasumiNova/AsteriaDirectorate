package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.internal

import cn.kasuminova.astd.sscsv.entries.HullModEntry

/**
 * 内部/隐藏 HullMods：词缀系统（v3 定稿，17 条）。
 *
 * 注意：这些 HullMod 不会在 UI 中显示（hiddenEverywhere），由词缀体系装上敌舰。
 * 词缀编目与抽取规则见 `cn.kasuminova.astd.combat.affix.AffixRegistry`（docs/design/affixes.md）。
 */
// —— S 型词缀（S-01 ~ S-08） ——

object Hm_astd_affix_ironclad_plating : HullModEntry() {
    override val id: String = "astd_affix_ironclad_plating"
    override val name: String = "铁甲重装（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixIroncladPlatingHullMod"
    override val short: String = "铁甲重装（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_cryo_flux_network : HullModEntry() {
    override val id: String = "astd_affix_cryo_flux_network"
    override val name: String = "六相冰辐能网络（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixCryoFluxNetworkHullMod"
    override val short: String = "六相冰辐能网络（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_flux_coil_expansion : HullModEntry() {
    override val id: String = "astd_affix_flux_coil_expansion"
    override val name: String = "极限辐能线圈扩容（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixFluxCoilExpansionHullMod"
    override val short: String = "辐能线圈扩容（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_polarized_shield : HullModEntry() {
    override val id: String = "astd_affix_polarized_shield"
    override val name: String = "极化护盾发生器（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPolarizedShieldHullMod"
    override val short: String = "极化护盾（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_engine_overclock : HullModEntry() {
    override val id: String = "astd_affix_engine_overclock"
    override val name: String = "引擎超频（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixEngineOverclockHullMod"
    override val short: String = "引擎超频（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_dimensional_specialty : HullModEntry() {
    override val id: String = "astd_affix_dimensional_specialty"
    override val name: String = "维度专长（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixDimensionalSpecialtyHullMod"
    override val short: String = "维度专长（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_phase_coil_tuning : HullModEntry() {
    override val id: String = "astd_affix_phase_coil_tuning"
    override val name: String = "相位线圈调谐（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPhaseCoilTuningHullMod"
    override val short: String = "相位调谐（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_phase_coil_detuning : HullModEntry() {
    override val id: String = "astd_affix_phase_coil_detuning"
    override val name: String = "相位线圈降频（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPhaseCoilDetuningHullMod"
    override val short: String = "相位降频（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

// —— M 型词缀（M-09 ~ M-14） ——

object Hm_astd_affix_recursive_targeting : HullModEntry() {
    override val id: String = "astd_affix_recursive_targeting"
    override val name: String = "递归式目标定位系统（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixRecursiveTargetingHullMod"
    override val short: String = "递归目标定位（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_reactive_flux_armor : HullModEntry() {
    override val id: String = "astd_affix_reactive_flux_armor"
    override val name: String = "反应式辐能装甲（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixReactiveFluxArmorHullMod"
    override val short: String = "反应辐能装甲（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_pspace_diver : HullModEntry() {
    override val id: String = "astd_affix_pspace_diver"
    override val name: String = "P空间深潜器（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPSpaceDiverHullMod"
    override val short: String = "P空间深潜器（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_engine_flux_isolation : HullModEntry() {
    override val id: String = "astd_affix_engine_flux_isolation"
    override val name: String = "引擎辐能网隔离（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixEngineFluxIsolationHullMod"
    override val short: String = "引擎辐能隔离（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_swarm_coordination : HullModEntry() {
    override val id: String = "astd_affix_swarm_coordination"
    override val name: String = "蜂群协同网络（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixSwarmCoordinationHullMod"
    override val short: String = "蜂群协同（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_plasma_armor_shield : HullModEntry() {
    override val id: String = "astd_affix_plasma_armor_shield"
    override val name: String = "等离子装甲护盾（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPlasmaArmorShieldHullMod"
    override val short: String = "等离子装甲护盾（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

// —— R 型词缀（R-15 ~ R-17） ——

object Hm_astd_affix_grid_deepening : HullModEntry() {
    override val id: String = "astd_affix_grid_deepening"
    override val name: String = "电网深化升级（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixGridDeepeningHullMod"
    override val short: String = "电网深化（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_aggressive_swarm_network : HullModEntry() {
    override val id: String = "astd_affix_aggressive_swarm_network"
    override val name: String = "激进式集群作战网络（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixAggressiveSwarmNetworkHullMod"
    override val short: String = "集群作战网络（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

object Hm_astd_affix_singularity_drive : HullModEntry() {
    override val id: String = "astd_affix_singularity_drive"
    override val name: String = "奇点驱动（词缀·隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod"
    override val short: String = "奇点驱动（隐藏）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}

// —— 测试用途 ——

object Hm_astd_test_shield_coverage : HullModEntry() {
    override val id: String = "astd_test_shield_coverage"
    override val name: String = "测试：护盾覆盖率（隐藏）"
    override val tech: String = "astd_hidden"
    override val uiTags: String = ""
    override val tags: String = ""
    override val hidden: Boolean = true
    override val hiddenEverywhere: Boolean = true
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.AsteriaTestShieldCoverageHullMod"
    override val short: String = "护盾覆盖（测试）"
    override val sprite: String = "graphics/hullmods/astd_zero_point_compute_core.png"
}
