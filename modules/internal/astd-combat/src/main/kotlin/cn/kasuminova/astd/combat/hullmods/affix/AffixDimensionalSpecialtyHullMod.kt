package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

class AffixDimensionalSpecialtyHullMod : BaseHullMod() {

    data class Bonuses(
        val peakDurationMult: Float,
        val crLossMult: Float,
        val systemCooldownMult: Float,
        val systemRegenMult: Float,
    )

    companion object {
        val PEAK_DURATION = ScalingEntry(v1 = 2.0f, v2 = 2.5f, v5 = 3.0f)
        val CR_LOSS = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)
        val SYSTEM_COOLDOWN = ScalingEntry(v1 = 0.90f, v2 = 0.85f, v5 = 0.80f)

        val SYSTEM_CHARGE_TIME_REDUCTION = ScalingEntry(v1 = 0.10f, v2 = 0.15f, v5 = 0.20f)

        fun bonuses(tuning: DifficultyTuning): Bonuses {
            val chargeReduction = tuning.value(SYSTEM_CHARGE_TIME_REDUCTION)
            return Bonuses(
                peakDurationMult = tuning.value(PEAK_DURATION),
                crLossMult = tuning.value(CR_LOSS),
                systemCooldownMult = tuning.value(SYSTEM_COOLDOWN),
                systemRegenMult = 1f / (1f - chargeReduction),
            )
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.peakCRDuration.modifyMult(id, b.peakDurationMult)
        stats.crLossPerSecondPercent.modifyMult(id, b.crLossMult)
        stats.systemCooldownBonus.modifyMult(id, b.systemCooldownMult)
        stats.systemRegenBonus.modifyMult(id, b.systemRegenMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_dimensional_specialty.desc")
        }
    }
}
