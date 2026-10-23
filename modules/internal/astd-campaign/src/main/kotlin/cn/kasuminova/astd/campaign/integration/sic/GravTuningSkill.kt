package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

class GravTuningSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.grav_tuning.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val deepDive = data.isSkillActive(SicTuning.DEEP_DIVE_SKILL_ID)

        val peakCr = SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_PEAK_CR_PERCENT, true) / 100f)
        val timeFlow = SicShared.formatPercent(
            DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_TIME_FLOW_TABLE, true) * SicTuning.GRAV_PHASE_TIME_FLOW_BASE,
        )
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            if (deepDive) {
                custom {
                    SicShared.grayLine(
                        it,
                        I18n.t(I18n.Categories.MOD, "ui.sic.grav_tuning.line.peak_cr_suppressed", "pct" to peakCr),
                        8f,
                        peakCr,
                    )
                }
                custom {
                    SicShared.grayLine(
                        it,
                        I18n.t(I18n.Categories.MOD, "ui.sic.grav_tuning.line.time_flow_suppressed", "pct" to timeFlow),
                        8f,
                        timeFlow,
                    )
                }
            } else {
                para("ui.sic.grav_tuning.line.peak_cr", Misc.getHighlightColor(), 8f, v("pct", peakCr))
                para("ui.sic.grav_tuning.line.time_flow", Misc.getHighlightColor(), 8f, v("pct", timeFlow))
            }

            para(
                "ui.sic.grav_tuning.line.flux_threshold",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_FLUX_THRESHOLD_PERCENT, true) / 100f)),
            )
            para(
                "ui.sic.grav_tuning.line.upkeep",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_UPKEEP_REDUCTION, true))),
            )
            para(
                "ui.sic.grav_tuning.line.cr_drain",
                Misc.getHighlightColor(),
                8f,
                v("pct", SicShared.formatPercent(DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_CR_REDUCTION, true))),
            )
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.grav_tuning.flavor"], 10f) }
        }
    }

    override fun applyEffectsBeforeShipCreation(
        data: SCData,
        stats: MutableShipStatsAPI,
        variant: ShipVariantAPI,
        hullSize: ShipAPI.HullSize,
        id: String,
    ) {
        if (!isPhaseVariant(variant)) return

        if (!data.isSkillActive(SicTuning.DEEP_DIVE_SKILL_ID)) {
            stats.peakCRDuration.modifyPercent(id, DifficultyTuningImpl.valueFor(SicTuning.GRAV_PEAK_CR_PERCENT, data.isPlayer))
            val flowTableValue = DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_TIME_FLOW_TABLE, data.isPlayer)
            stats.dynamic.getStat(Stats.PHASE_TIME_BONUS_MULT)
                .modifyFlat(id, -SicTuning.GRAV_PHASE_TIME_FLOW_BASE * flowTableValue)
        }

        stats.dynamic.getMod(Stats.PHASE_CLOAK_FLUX_LEVEL_FOR_MIN_SPEED_MOD)
            .modifyPercent(id, DifficultyTuningImpl.valueFor(SicTuning.GRAV_FLUX_THRESHOLD_PERCENT, data.isPlayer))
        stats.phaseCloakUpkeepCostBonus
            .modifyMult(id, 1f - DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_UPKEEP_REDUCTION, data.isPlayer))
    }

    override fun applyEffectsAfterShipCreation(data: SCData, ship: ShipAPI, variant: ShipVariantAPI, id: String) {
        if (!isPhaseVariant(variant)) return
        if (!ship.hasListenerOfClass(PhaseCrCompensationListener::class.java)) {
            ship.addListener(PhaseCrCompensationListener(ship, data.isPlayer))
        }
    }

    companion object {
        fun isPhaseVariant(variant: ShipVariantAPI): Boolean =
            (variant.hullSpec.baseHull ?: variant.hullSpec).isPhase
    }

    /** ShipAPI.setCurrentCR 逐帧回写会触发 CR 变更回调，CR 降速只走 crLossPerSecondPercent 乘区。 */
    private class PhaseCrCompensationListener(
        private val ship: ShipAPI,
        private val isPlayer: Boolean,
    ) : AdvanceableListener {

        private var lastTimeDeployed: Float = ship.timeDeployedForCRReduction

        override fun advance(amount: Float) {
            val crLoss = ship.mutableStats.crLossPerSecondPercent
            crLoss.unmodifyMult(CR_LOSS_MOD_ID)

            if (ship.isHulk || !ship.isAlive) {
                ship.removeListener(this)
                return
            }

            val reduction = DifficultyTuningImpl.valueFor(SicTuning.GRAV_PHASE_CR_REDUCTION, isPlayer)
            if (ship.isPhased && reduction > 0f) {
                val now = ship.timeDeployedForCRReduction
                val delta = now - lastTimeDeployed
                if (delta > 0f) {
                    ship.setTimeDeployed((now - delta * reduction).coerceAtLeast(0f))
                }
                crLoss.modifyMult(CR_LOSS_MOD_ID, 1f - reduction)
            }

            lastTimeDeployed = ship.timeDeployedForCRReduction
        }
    }
}

private const val CR_LOSS_MOD_ID = "astd_sc_grav_tuning_phase_cr"
