package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.affix

import cn.kasuminova.astd.sscsv.entries.HullModEntry

/**
 * 赏金词缀 HullMods（affixes.md v3.0，S-01~S-08 / M-09~M-14 / R-15~R-17）。
 *
 * 全部由赏金生成器装上敌舰：hidden + hiddenEverywhere，不进装配界面；
 * id 与 [AffixRegistry] 的 hullModId 约定（"astd_affix_" + 词缀 id）一一对应。
 */
private const val AFFIX_SPRITE = "graphics/hullmods/astd_zero_point_compute_core.png"

/** v3 词缀条目基座：统一隐藏口径，子类只需填 id/名称/描述/脚本。 */
abstract class AffixHullModEntry : HullModEntry() {
    final override val tech: String = "astd_hidden"
    final override val uiTags: String = ""
    final override val tags: String = ""
    final override val hidden: Boolean = true
    final override val hiddenEverywhere: Boolean = true
    final override val unlocked: Boolean = true
    final override val sprite: String = AFFIX_SPRITE
}

// ─── S 型词缀 ───

object Hm_astd_affix_ironclad_plating : AffixHullModEntry() {
    override val id: String = "astd_affix_ironclad_plating"
    override val name: String = "铁甲重装（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixIroncladPlatingHullMod"
    override val desc: String = "内部用途：赏金词缀 S-01。按难度系数提升最大装甲值与最小装甲计算值，降低最大航速与机动性。"
    override val short: String = "铁甲重装（隐藏）"
}

object Hm_astd_affix_cryo_flux_network : AffixHullModEntry() {
    override val id: String = "astd_affix_cryo_flux_network"
    override val name: String = "六相冰辐能网络（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixCryoFluxNetworkHullMod"
    override val desc: String = "内部用途：赏金词缀 S-02。提升辐能耗散与强制排辐速率，降低受到的 EMP 伤害。与极限辐能线圈扩容互斥。"
    override val short: String = "六相冰辐能网络（隐藏）"
}

object Hm_astd_affix_flux_coil_expansion : AffixHullModEntry() {
    override val id: String = "astd_affix_flux_coil_expansion"
    override val name: String = "极限辐能线圈扩容（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixFluxCoilExpansionHullMod"
    override val desc: String = "内部用途：赏金词缀 S-03。提升辐能容量，降低辐能耗散。与六相冰辐能网络、电网深化升级互斥。"
    override val short: String = "辐能线圈扩容（隐藏）"
}

object Hm_astd_affix_polarized_shield : AffixHullModEntry() {
    override val id: String = "astd_affix_polarized_shield"
    override val name: String = "极化护盾发生器（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPolarizedShieldHullMod"
    override val desc: String = "内部用途：赏金词缀 S-04。降低护盾受到的伤害，提升舰船过载时间。"
    override val short: String = "极化护盾（隐藏）"
}

object Hm_astd_affix_engine_overclock : AffixHullModEntry() {
    override val id: String = "astd_affix_engine_overclock"
    override val name: String = "引擎超频（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixEngineOverclockHullMod"
    override val desc: String = "内部用途：赏金词缀 S-05。提升最大航速，降低机动性。"
    override val short: String = "引擎超频（隐藏）"
}

object Hm_astd_affix_dimensional_specialty : AffixHullModEntry() {
    override val id: String = "astd_affix_dimensional_specialty"
    override val name: String = "维度专长（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixDimensionalSpecialtyHullMod"
    override val desc: String = "内部用途：赏金词缀 S-06。提升峰值时间、降低 CR 削减速率，缩短舰船系统充能与冷却。"
    override val short: String = "维度专长（隐藏）"
}

object Hm_astd_affix_phase_coil_tuning : AffixHullModEntry() {
    override val id: String = "astd_affix_phase_coil_tuning"
    override val name: String = "相位线圈调谐（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPhaseCoilTuningHullMod"
    override val desc: String = "内部用途：赏金词缀 S-07。提升相位状态时间流速与峰值时间。仅相位舰船可搭载；与相位线圈降频互斥。"
    override val short: String = "相位线圈调谐（隐藏）"
}

object Hm_astd_affix_phase_coil_detuning : AffixHullModEntry() {
    override val id: String = "astd_affix_phase_coil_detuning"
    override val name: String = "相位线圈降频（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPhaseCoilDetuningHullMod"
    override val desc: String = "内部用途：赏金词缀 S-08。降低相位状态时间流速与辐能产出，缩短相位线圈冷却。仅相位舰船可搭载；与相位线圈调谐互斥。"
    override val short: String = "相位线圈降频（隐藏）"
}

// ─── M 型词缀 ───

object Hm_astd_affix_recursive_targeting : AffixHullModEntry() {
    override val id: String = "astd_affix_recursive_targeting"
    override val name: String = "递归式目标定位系统（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixRecursiveTargetingHullMod"
    override val desc: String = "内部用途：赏金词缀 M-09。每艘友军舰船为自身额外提供射程与射弹飞行速度，设有上限。"
    override val short: String = "递归目标定位（隐藏）"
}

object Hm_astd_affix_reactive_flux_armor : AffixHullModEntry() {
    override val id: String = "astd_affix_reactive_flux_armor"
    override val name: String = "反应式辐能装甲（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixReactiveFluxArmorHullMod"
    override val desc: String = "内部用途：赏金词缀 M-10。强制排辐期间大幅降低受到的装甲与船体伤害，强制排辐速率降低。"
    override val short: String = "反应式辐能装甲（隐藏）"
}

object Hm_astd_affix_pspace_diver : AffixHullModEntry() {
    override val id: String = "astd_affix_pspace_diver"
    override val name: String = "P空间深潜器（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPspaceDiverHullMod"
    override val desc: String = "内部用途：赏金词缀 M-11。降低相位期间硬辐能水平提升导致的最大航速降低。仅相位舰船可搭载。"
    override val short: String = "P空间深潜器（隐藏）"
}

object Hm_astd_affix_engine_flux_isolation : AffixHullModEntry() {
    override val id: String = "astd_affix_engine_flux_isolation"
    override val name: String = "引擎辐能网隔离（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixEngineFluxIsolationHullMod"
    override val desc: String = "内部用途：赏金词缀 M-12。提升零辐能加速阈值与零辐能加速的航速增益。"
    override val short: String = "引擎辐能网隔离（隐藏）"
}

object Hm_astd_affix_swarm_coordination : AffixHullModEntry() {
    override val id: String = "astd_affix_swarm_coordination"
    override val name: String = "蜂群协同网络（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixSwarmCoordinationHullMod"
    override val desc: String = "内部用途：赏金词缀 M-13。每存在一艘全自动友军舰船，为自身提供全伤害减免与非导弹武器射速提升。"
    override val short: String = "蜂群协同网络（隐藏）"
}

object Hm_astd_affix_plasma_armor_shield : AffixHullModEntry() {
    override val id: String = "astd_affix_plasma_armor_shield"
    override val name: String = "等离子装甲护盾（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixPlasmaArmorShieldHullMod"
    override val desc: String = "内部用途：赏金词缀 M-14。舰船护盾获得装甲计算值减免（复用同名船插机制）。"
    override val short: String = "等离子装甲护盾（隐藏）"
}

// ─── R 型词缀 ───

object Hm_astd_affix_grid_deepening : AffixHullModEntry() {
    override val id: String = "astd_affix_grid_deepening"
    override val name: String = "电网深化升级（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixGridDeepeningHullMod"
    override val desc: String = "内部用途：赏金词缀 R-15。捆绑六相冰辐能网络与反应式辐能装甲效果，提升强制耗散与硬辐能耗散速率，并优化 AI 排辐决策。与极限辐能线圈扩容互斥。"
    override val short: String = "电网深化升级（隐藏）"
}

object Hm_astd_affix_aggressive_swarm_network : AffixHullModEntry() {
    override val id: String = "astd_affix_aggressive_swarm_network"
    override val name: String = "激进式集群作战网络（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixAggressiveSwarmNetworkHullMod"
    override val desc: String = "内部用途：赏金词缀 R-16。提升指挥点恢复速率；周期性发起歼灭指令，编队集火敌方最高部署点单位。"
    override val short: String = "集群作战网络（隐藏）"
}

object Hm_astd_affix_singularity_drive : AffixHullModEntry() {
    override val id: String = "astd_affix_singularity_drive"
    override val name: String = "奇点驱动（词缀·隐藏）"
    override val script: String = "cn.kasuminova.astd.combat.hullmods.affix.AffixSingularityDriveHullMod"
    override val desc: String = "内部用途：赏金词缀 R-17。巨幅提升峰值时间并免疫环境峰值削减，附带子系统「奇点爆发」。"
    override val short: String = "奇点驱动（隐藏）"
}
