package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.lens

import cn.kasuminova.astd.sscsv.entries.HullModEntry
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
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_singularity_power.png"
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
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_grav_phase_deck.png"
}

/**
 * 决明级内置船插「先进电子对抗网络」（purple/10-unique.md §1）。
 *
 * 机制脚本 [AdvancedEcmNetworkHullMod]：自身 5/10/25% ECM + 每个存活友军按舰级 2~4/3~6/4~8/5~10% ECM
 * （写本舰 electronic_warfare_flat），并将每艘存活敌舰的 ECM 贡献 clamp 到 4/8/12/16% 舰级上限。
 */
object HullMod_astd_advanced_ecm_network : HullModEntry() {
    override val id: String = "astd_advanced_ecm_network"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.AdvancedEcmNetworkHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

/**
 * 决明级内置船插「引力电子干扰力场」（purple/10-unique.md §1）。
 *
 * 机制脚本 [GravEwFieldHullMod]：我方电子战最大效果强度 +50%/+100%/+250%（10% → 15%/20%/35%，
 * 写本侧指挥官 electronic_warfare_max），并在 EW 压制生效时按 实际/最大 效果比例对全部敌舰
 * 施加最高 10% 的 EMP 抗性/护盾效率/航速机动降低与系统冷却/充能时间提升。
 */
object HullMod_astd_grav_ew_field : HullModEntry() {
    override val id: String = "astd_grav_ew_field"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "菀星设计局-紫菀"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.lens.GravEwFieldHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_singularity_power.png"
}
