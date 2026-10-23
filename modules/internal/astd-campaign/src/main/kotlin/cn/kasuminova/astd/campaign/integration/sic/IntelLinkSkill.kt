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
        if (StandardCores.byCommodity(ship.captain?.aiCoreId) != null) return
        val stats = ship.mutableStats
        val hullSize = ship.hullSize
        ASTDCombatIntelSkill.Level6().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level7().apply(stats, hullSize, id, 1f)
    }
}
