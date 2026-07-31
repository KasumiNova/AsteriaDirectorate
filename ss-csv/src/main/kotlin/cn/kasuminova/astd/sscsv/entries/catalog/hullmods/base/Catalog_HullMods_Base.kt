package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.base

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/**
 * ASTD 通用（跨设计系）HullMod 注册（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。
 *
 * 目前仅含通用双模式切换器 [HullMod_astd_dual_mode_switcher]：arc_flare / gravitational_lens 等
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
