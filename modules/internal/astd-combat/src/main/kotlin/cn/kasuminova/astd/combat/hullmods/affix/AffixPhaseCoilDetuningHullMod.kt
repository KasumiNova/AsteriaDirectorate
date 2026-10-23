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

class AffixPhaseCoilDetuningHullMod : BaseHullMod() {

    data class Bonuses(
        val phaseTimeMultMod: Float,
        val upkeepMult: Float,
        val cooldownMult: Float,
    )

    companion object {
        val PHASE_TIME_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)
        val UPKEEP = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)
        val COOLDOWN = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            phaseTimeMultMod = 1f - 1.5f * tuning.value(PHASE_TIME_REDUCTION),
            upkeepMult = tuning.value(UPKEEP),
            cooldownMult = tuning.value(COOLDOWN),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        if (!isPhaseShip(stats)) return
        val b = bonuses(AffixShared.tuning)
        // phase_time_mult 的消费点（PhaseCloakStats.getMaxTimeMult）用单参 getValue 读 stats map，必须写 getStat
        stats.dynamic.getStat(AffixPhaseCoilTuningHullMod.PHASE_TIME_MULT_MOD).modifyMult(id, b.phaseTimeMultMod)
        stats.phaseCloakUpkeepCostBonus.modifyMult(id, b.upkeepMult)
        stats.phaseCloakCooldownBonus.modifyMult(id, b.cooldownMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_phase_coil_detuning.desc")
        }
    }
}
