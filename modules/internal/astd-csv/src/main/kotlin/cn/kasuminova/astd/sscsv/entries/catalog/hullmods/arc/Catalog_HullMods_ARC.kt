package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.arc

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** ARC 设计系 HullMod（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。 */

/**
 * 先进能量集成（XC-001 星坠内置船插，规格 10-unique §1）：
 * 能量射弹速度 +20%、能量武器辐能消耗 −15%、武器转向速率 +30%、非导弹武器 OP 折扣 −2/−4/−8。
 */
object HullMod_astd_arc_advanced_energy_integration : HullModEntry() {
    override val id: String = "astd_arc_advanced_energy_integration"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDAdvancedEnergyIntegrationHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

/**
 * 虚数之翼（XC-002 星翼内置船插，规格 blue/10-unique.md XC-002 节）：
 * 战术系统激活后 3s 内逐渐削减的最大航速/机动性加成；武器伤害随当前航速占最大航速
 * 比例缩放（0% 航速 −25%、100% 航速 +50%、超上限每 1% 再 +2%；难度三锚点见实现）。
 */
object HullMod_astd_imaginary_wings : HullModEntry() {
    override val id: String = "astd_imaginary_wings"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.arc.ASTDImaginaryWingsHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_vectorized_jet_array.png"
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
    override val sprite: String = "graphics/hullmods/astd_plasma_armor_shield.png"
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
    override val sprite: String = "graphics/hullmods/astd_ionized_recoil_accumulator.png"
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
