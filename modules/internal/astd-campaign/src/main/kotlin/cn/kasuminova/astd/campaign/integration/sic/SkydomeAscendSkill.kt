package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.combat.skills.ASTDCombatIntelSkill
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

class SkydomeAscendSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_ascend.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val points = SicShared.autoPointsFor(SicTuning.ASCEND_AUTO_POINTS, isPlayer = true)
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            custom { SicShared.autoPointsHeader(it, 8f) }
            para(
                "ui.sic.skydome_ascend.line.points",
                Misc.getHighlightColor(),
                8f,
                v("points", points),
            )
            para(
                "ui.sic.skydome_ascend.line.dp",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.ASCEND_DP_REDUCTION, true))),
            )
            para("ui.sic.skydome_ascend.line.combat_intel", Misc.getHighlightColor())
            custom { SicShared.autoPointsFooter(it, 10f) }
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.skydome_ascend.flavor"], 10f) }
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        val manufacturer = (variant.hullSpec.baseHull ?: variant.hullSpec).manufacturer
        if (!SicTuning.isWanxingManufacturer(manufacturer)) return
        stats.dynamic.getMod(Stats.DEPLOYMENT_POINTS_MOD)
            .modifyMult(id, 1f - DifficultyTuningImpl.valueFor(SicTuning.ASCEND_DP_REDUCTION, data.isPlayer))
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        val captain = ship.captain ?: return
        if (!captain.isAICore) return
        val coreId = captain.aiCoreId ?: return
        if (StandardCores.byCommodity(coreId) != null) return

        val stats = ship.mutableStats
        val hullSize = ship.hullSize
        ASTDCombatIntelSkill.Level1().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level2().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level3().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level4().apply(stats, hullSize, id, 1f)
        ASTDCombatIntelSkill.Level5().apply(stats, hullSize, id, 1f)
    }

    override fun advance(data: SCData, amunt: Float) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.ASCEND_AUTO_POINTS, data.isPlayer)
    }

    override fun onActivation(data: SCData) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.ASCEND_AUTO_POINTS, data.isPlayer)
    }

    override fun onDeactivation(data: SCData) {
        if (data.isNPC) return
        SicShared.removeAutoPoints(spec.id)
    }
}
