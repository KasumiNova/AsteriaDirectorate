package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.skills.HullRestoration
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

class SkydomeReinforceSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_reinforce.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val points = SicShared.autoPointsFor(SicTuning.REINFORCE_AUTO_POINTS, isPlayer = true)
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            custom { SicShared.autoPointsHeader(it, 8f) }
            para(
                "ui.sic.skydome_reinforce.line.points",
                Misc.getHighlightColor(),
                8f,
                v("points", points),
            )
            para("ui.sic.skydome_reinforce.line.recovery", Misc.getHighlightColor())
            custom { SicShared.autoPointsFooter(it, 10f) }
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.skydome_reinforce.flavor"], 10f) }
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        if (!isAutomatedVariant(variant)) return
        stats.dynamic.getMod(Stats.INDIVIDUAL_SHIP_RECOVERY_MOD).modifyFlat(id, HullRestoration.RECOVERY_PROB)
    }

    override fun advance(data: SCData, amunt: Float) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.REINFORCE_AUTO_POINTS, data.isPlayer)
    }

    override fun onActivation(data: SCData) {
        if (data.isNPC) return
        SicShared.applyAutoPoints(spec.id, spec.name, SicTuning.REINFORCE_AUTO_POINTS, data.isPlayer)
    }

    override fun onDeactivation(data: SCData) {
        if (data.isNPC) return
        SicShared.removeAutoPoints(spec.id)
    }

    companion object {
        fun isAutomatedVariant(variant: ShipVariantAPI): Boolean = Misc.isAutomated(variant)
    }
}
