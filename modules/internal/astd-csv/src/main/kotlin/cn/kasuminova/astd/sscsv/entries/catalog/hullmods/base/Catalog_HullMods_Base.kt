package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.base

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.PLACEHOLDER_SCRIPT
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/**
 * ASTD 通用（跨设计系）HullMod 注册（原始数据来自 `contents/data/hullmods/hull_mods.csv`）。
 *
 * 目前仅含通用双模式切换器 [HullMod_astd_dual_mode_switcher]：astd_zw_001 等
 * 双模式舰共用同一个切换器 hullmod（脚本 ASTDDualModeSwitcherHullMod）。
 *
 * 真相源纪律：此处是通用切换器在 hull_mods.csv 的**唯一**注册点。lens（Task 4）在此注册，
 * arc（Task 5）复用同一行，**不得重复注册**。各舰旧的自造切换器（如 astd_lens_mode_switcher）
 * 在各自 catalog 中移除其注册条目。
 */

/**
 * 通用双模式切换器。
 *
 * - id 必须与 [cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeSwitcherIds.SWITCHER_ID] 完全一致。
 * - hidden + 不可解锁 + 不掉落：切换器只经代码分发（.ship builtInMods / 拆即切自动加回），
 *   不进 refit 选择器、不可内置、不掉蓝图/战利品；已装时仍显示在已装列表（hiddenEverywhere=false），
 *   玩家拆下它即轮换载人/无人模式。
 * - tags 不打 astd_builtin：切换器并非「内置分发不可拆」语义，玩家可拆是核心交互。
 */
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

/**
 * 通用双模式·载人模式（舰船无关）。
 *
 * - id 必须与 [cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeGenericIds.MODE_CREWED] 完全一致。
 * - 舰船无关：未显式注册专属双模式配置的 ASTD 舰在激活载人模式时统一挂它（permaMod），
 *   由脚本 ASTDCrewedModeHullMod 提供拆即切与可选系统互换。
 * - hidden + hiddenEverywhere：纯状态标记，不进选择器也不显示在已装船插列表；
 *   当前模式由切换器 tooltip 动态说明承担展示职责。
 */
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

/** 通用双模式·无人模式（舰船无关），见 [HullMod_astd_mode_crewed]。 */
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
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dual_mode_switcher_crew.png"
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
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_dual_mode_switcher_auto.png"
}

/**
 * 智能联结标记船插（SIC 联动「智能联结」天赋改造的永久标记，
 * docs/design/skills/10-sic-skills.md 阶段四）。
 *
 * - id 必须与 cn.kasuminova.astd.campaign.integration.sic.SicTuning.INTEL_LINK_HULLMOD_ID
 *   完全一致（改造按钮 addPermaMod 与技能入场判定共用）；
 * - hidden + 不可解锁 + 不掉落：只经改造按钮代码分发（addPermaMod），不进 refit 选择器、
 *   不掉蓝图/战利品；已装时仍显示在已装列表（hiddenEverywhere=false），玩家可见标记；
 * - 纯标记：脚本只渲染描述（说明「需要舰队副官天赋【智能联结】生效」与效果内容），
 *   效果由 SIC 技能 IntelLinkSkill 驱动。
 */
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
