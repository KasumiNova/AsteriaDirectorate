package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.combat.skills.ASTDCombatIntelSkill
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

/**
 * 智能联结（astd_sc_intel_link）——影响舰队中所有舰船
 * （docs/design/skills/10-sic-skills.md 阶段四）。
 *
 * 机制落点：
 * - 改造本身不在本技能内：由 [IntelLinkRefitButton]（LunaLib「额外改造选项」）完成
 *   「集成一颗菀星制式 Beta 核心」的改造并添加永久标记船插 [SicTuning.INTEL_LINK_HULLMOD_ID]；
 * - 入场附加精英效果：applyEffectsAfterShipCreation 判定 variant 带标记船插时，
 *   直接实例化 [ASTDCombatIntelSkill] 的精英组 Level6/Level7 逐个 apply
 *   （能量武器射程补足 + 能量武器辐能产出乘区；Level6 内部 ensureShipListener 幂等挂监听器，
 *   重复 apply 安全）。场外（装配/战役层）不附加——ShipSkillEffect.apply 只在战斗 stats 上生效；
 * - 「必须保持具有本天赋才生效」的落点：精英效果经 SIC 控制器船插随 stats 刷新重放，
 *   天赋撤下后 SIC 不再回调本技能，下一场战斗自然失效（监听器随舰船实体生灭，无跨战斗残留）；
 *   已改造的标记船插本身不提供任何效果（纯标记）。
 */
class IntelLinkSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.intel_link.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            para("ui.sic.intel_link.line.effect", Misc.getHighlightColor())
            para("ui.sic.intel_link.sub.refit", Misc.getHighlightColor(), 2f)
            para("ui.sic.intel_link.sub.cost", Misc.getHighlightColor(), 2f)
            para("ui.sic.intel_link.sub.upkeep", Misc.getHighlightColor(), 2f)
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.intel_link.flavor"], 10f) }
        }
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        if (!variant.hasHullMod(SicTuning.INTEL_LINK_HULLMOD_ID)) return
        // 制式核心舰长已固有精英菀星战斗智能（StandardCores 技能表全 2 级）：
        // Level7 的能量辐能乘区按 source id 记账，重复 apply 会叠乘生效，跳过
        // （与天幕升格对非制式核心才附加非精英效果的口径一致）
        if (StandardCores.byCommodity(ship.captain?.aiCoreId) != null) return
        val stats = ship.mutableStats
        val hullSize = ship.hullSize
        ASTDCombatIntelSkill.Level6().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level7().apply(stats, hullSize, id, 1f)
    }
}
