package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.combat.hullmods.base.isPhaseShip
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

class AffixPhaseCoilTuningHullMod : BaseHullMod() {

    data class Bonuses(
        val phaseTimeMultMod: Float,
        val peakDurationMult: Float,
        val crLossMult: Float,
    )

    companion object {
        val PHASE_TIME_BONUS = ScalingEntry(v1 = 0.50f, v2 = 0.75f, v5 = 1.00f)
        val PEAK_DURATION = ScalingEntry(v1 = 2.0f, v2 = 2.5f, v5 = 3.0f)
        val CR_LOSS = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)

        const val PHASE_TIME_MULT_MOD = "phase_time_mult"

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            phaseTimeMultMod = 1f + 1.5f * tuning.value(PHASE_TIME_BONUS),
            peakDurationMult = tuning.value(PEAK_DURATION),
            crLossMult = tuning.value(CR_LOSS),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        if (!isPhaseShip(stats)) return
        val b = bonuses(AffixShared.tuning)
        // phase_time_mult 的消费点（PhaseCloakStats.getMaxTimeMult）用单参 getValue 读 stats map，必须写 getStat
        stats.dynamic.getStat(PHASE_TIME_MULT_MOD).modifyMult(id, b.phaseTimeMultMod)
        stats.peakCRDuration.modifyMult(id, b.peakDurationMult)
        stats.crLossPerSecondPercent.modifyMult(id, b.crLossMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_phase_coil_tuning.desc")
        }
    }
}
