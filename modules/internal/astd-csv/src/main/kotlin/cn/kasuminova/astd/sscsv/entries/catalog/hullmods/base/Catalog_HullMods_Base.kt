package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.base

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SCRIPT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/**
 * ASTD 通用（跨设计系）HullMod 注册（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。
 *
 * 目前仅含通用双模式切换器 [HullMod_astd_dual_mode_switcher]：astd_xc_001 / astd_zw_001 等
 * 所有双模式舰共用同一个切换器 hullmod（脚本 ASTDDualModeSwitcherHullMod）。
 *
 * 真相源纪律：此处是通用切换器在 hull_mods.csv 的**唯一**注册点。lens（Task 4）在此注册，
 * arc（Task 5）复用同一行，**不得重复注册**。各舰旧的自造切换器（如 astd_lens_mode_switcher）
 * 在各自 catalog 中移除其注册条目。
 */

/**
 * 通用双模式切换器。
 *
 * - id 必须与 [cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherIds.SWITCHER_ID] 完全一致。
 * - 可见（非隐藏、unlocked）：玩家需在 refit 选择器看到并拆下它来轮换载人/无人模式。
 * - tags 留空（不打 astd_builtin，避免被 HullModEntry.toRow 自动标 hidden）；
 *   它通过 .ship builtInMods 内置到具体舰，同时玩家可拆。
 */
object HullMod_astd_dual_mode_switcher : HullModEntry() {
    override val id: String = "astd_dual_mode_switcher"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = ""
    override val tags: String = ""
    override val unlocked: Boolean = true
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_lens_array_core.png"
}

/**
 * 纳米重构协议（舰船无关的通用内置船插）。
 *
 * - 原注册于 ARC catalog（tech=ARC），通用化后迁至 base：所有独特舰默认内置（.ship builtInMods），
 *   脚本实现迁至 cn.kasuminova.astd.combat.hullmods.base.ASTDNanoRestorationProtocolHullMod。
 * - 保留 astd_builtin：仅内置分发（自动 hidden + 不可解锁），玩家不可在 refit 选择器安装。
 */
object HullMod_astd_nano_restoration_protocol : HullModEntry() {
    override val id: String = "astd_nano_restoration_protocol"
    override val name: String = hullmodName(id)
    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = ""
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDNanoRestorationProtocolHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_nano_restoration_protocol.png"
}

/**
 * 通用双模式·载人模式（舰船无关）。
 *
 * - id 必须与 [cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeGenericIds.MODE_CREWED] 完全一致。
 * - 舰船无关：未显式注册专属双模式配置的 ASTD 舰在激活载人模式时统一挂它（permaMod），
 *   由脚本 ASTDCrewedModeHullMod 提供拆即切与可选系统互换。
 * - 与 xc_001 / zw_001 的专属模式行一致：对玩家可见（展示当前模式），但不可在选择器中手动拆装。
 */
object HullMod_astd_mode_crewed : HullModEntry() {
    override val id: String = "astd_mode_crewed"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = ""
    override val tags: String = ""
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDCrewedModeHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_nano_restoration_protocol.png"
}

/** 通用双模式·无人模式（舰船无关），见 [HullMod_astd_mode_crewed]。 */
object HullMod_astd_mode_automated : HullModEntry() {
    override val id: String = "astd_mode_automated"
    override val name: String = hullmodName(id)
    override val tier: Int = 3
    override val rarity: Int = 0
    override val tech: String = ""
    override val tags: String = ""
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDAutomatedModeHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_vectorized_jet_array.png"
}

/** 通用「下次激活载人」内部标记（舰船无关），镜像各舰专属 marker 行。 */
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
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

/** 通用「下次激活无人」内部标记（舰船无关），镜像各舰专属 marker 行。 */
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
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}
