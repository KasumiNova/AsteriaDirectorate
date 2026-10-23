package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

class FrostFluxGridSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.frost_flux_grid.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            para(
                "ui.sic.frost_flux_grid.line.dissipation",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.FROST_DISSIPATION_PERCENT, true) / 100f)),
            )
            para(
                "ui.sic.frost_flux_grid.line.hard_dissipation",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.FROST_HARD_DISSIPATION_BONUS, true))),
            )
            custom {
                SicShared.subLine(
                    it,
                    I18n.t(
                        I18n.Categories.MOD, "ui.sic.frost_flux_grid.sub.hard_dissipation",
                        "start" to SicShared.formatPercent(SicTuning.FROST_RAMP_START),
                        "full" to SicShared.formatPercent(SicTuning.FROST_RAMP_START + SicTuning.FROST_RAMP_SPAN),
                    ),
                    2f,
                    SicShared.formatPercent(SicTuning.FROST_RAMP_START),
                    SicShared.formatPercent(SicTuning.FROST_RAMP_START + SicTuning.FROST_RAMP_SPAN),
                )
            }
            para(
                "ui.sic.frost_flux_grid.line.vent",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.FROST_VENT_RATE_PERCENT, true) / 100f)),
            )
            para(
                "ui.sic.frost_flux_grid.line.overload",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.FROST_OVERLOAD_REDUCTION, true))),
            )
            para(
                "ui.sic.frost_flux_grid.line.emp",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.FROST_EMP_REDUCTION, true))),
            )
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.frost_flux_grid.flavor"], 10f) }
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        stats.fluxDissipation.modifyPercent(id, DifficultyTuningImpl.valueFor(SicTuning.FROST_DISSIPATION_PERCENT, data.isPlayer))
        stats.ventRateMult.modifyPercent(id, DifficultyTuningImpl.valueFor(SicTuning.FROST_VENT_RATE_PERCENT, data.isPlayer))
        stats.overloadTimeMod.modifyMult(id, 1f - DifficultyTuningImpl.valueFor(SicTuning.FROST_OVERLOAD_REDUCTION, data.isPlayer))
        stats.empDamageTakenMult.modifyMult(id, 1f - DifficultyTuningImpl.valueFor(SicTuning.FROST_EMP_REDUCTION, data.isPlayer))
    }

    override fun advanceInCombat(data: SCData, ship: ShipAPI, amount: Float) {
        val stat = ship.mutableStats.hardFluxDissipationFraction
        stat.unmodifyMult(spec.id)

        val tracker = ship.fluxTracker ?: return
        val t = SicTuning.hardFluxRampT(SicTuning.hardFluxFraction(tracker.hardFlux, tracker.maxFlux))
        if (t <= 0f) return

        val bonus = DifficultyTuningImpl.valueFor(SicTuning.FROST_HARD_DISSIPATION_BONUS, data.isPlayer) * t
        stat.modifyMult(spec.id, 1f + bonus)
    }
}
