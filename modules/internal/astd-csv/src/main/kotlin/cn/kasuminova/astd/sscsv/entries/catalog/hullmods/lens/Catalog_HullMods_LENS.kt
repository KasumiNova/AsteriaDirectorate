package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.lens

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_DESC
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SCRIPT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SHORT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** LENS 设计系 HullMod（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。 */

/**
 * 奇点能源·紫变体（紫菀系全舰内置）：与蓝变体共用效果脚本
 * [cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityPowerHullMod]，仅贴图与设计类型不同。
 */
object HullMod_astd_lens_singularity_power : HullModEntry() {
    override val id: String = "astd_lens_singularity_power"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityPowerHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_singularity_power.png"
}

object HullMod_astd_lens_array_core : HullModEntry() {
    override val id: String = "astd_lens_array_core"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
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
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensParallaxDecksHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_lens_permeating_tide : HullModEntry() {
    override val id: String = "astd_lens_permeating_tide"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensPermeatingTideHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

// 注：lens 自造切换器 astd_zw_001_mode_switcher 已废弃，改用通用切换器 astd_dual_mode_switcher
// （见 entries/catalog/hullmods/base/Catalog_HullMods_Base.kt）。原条目已移除。

object HullMod_astd_zw_001_mode_crewed : HullModEntry() {
    override val id: String = "astd_zw_001_mode_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val hiddenEverywhere: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensCrewedModeHullMod"
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_zw_001_mode_automated : HullModEntry() {
    override val id: String = "astd_zw_001_mode_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val hiddenEverywhere: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDLensAutomatedModeHullMod"
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_zw_001_mode_next_crewed : HullModEntry() {
    override val id: String = "astd_zw_001_mode_next_crewed"
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

object HullMod_astd_zw_001_mode_next_automated : HullModEntry() {
    override val id: String = "astd_zw_001_mode_next_automated"
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

object HullMod_astd_dimensional_folding_deck : HullModEntry() {
    override val id: String = "astd_dimensional_folding_deck"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDDimensionalFoldingDeckHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dimensional_folding_deck.png"
}

/**
 * 密蒙级内置船插「引力电磁力场」（purple/10-unique.md §2，2026-09 D27 全重做）。
 *
 * 机制脚本 [GravEmFieldHullMod]：1500su 光环削弱敌舰 EMP 抗性/航速机动/武器射程并抬高开火辐能，
 * 随距离衰减（≤750su 满效，边缘 25% 下限），全部最终乘区；舰船系统冷却期间力场消失。
 * 取代旧占位船插 astd_dark_tide_jammer（已随重做移除）。
 */
object HullMod_astd_grav_em_field : HullModEntry() {
    override val id: String = "astd_grav_em_field"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.GravEmFieldHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

/**
 * 舜华级内置船插「引力空间折跃器」（purple/20-production.md §3，2026-09 D27 全重做）。
 *
 * 机制脚本 [GravSpaceFoldHullMod]：碰撞圈 +200su 内敌方射弹/导弹概率折跃至舰体另一端
 * （基础概率 + 弹体伤害加计，双锚点缩放），并附固定光束减伤；相位状态或系统冷却期间失效。
 * 取代旧占位三件套 astd_echo_emitter / astd_echo_reentry_buffer / astd_phase_resonance_jamming
 * （已随重做移除）。
 */
object HullMod_astd_grav_space_fold : HullModEntry() {
    override val id: String = "astd_grav_space_fold"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.GravSpaceFoldHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

object HullMod_astd_grav_phase_deck : HullModEntry() {
    override val id: String = "astd_grav_phase_deck"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.ASTDGravPhaseDeckHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_grav_phase_deck.png"
}
