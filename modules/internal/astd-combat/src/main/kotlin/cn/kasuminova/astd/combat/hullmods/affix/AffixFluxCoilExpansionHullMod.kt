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

class AffixFluxCoilExpansionHullMod : BaseHullMod() {

    data class Bonuses(
        val capacityMult: Float,
    )

    companion object {
        val CAPACITY = ScalingEntry(v1 = 1.20f, v2 = 1.30f, v5 = 1.40f)

        const val DISSIPATION_MULT = 0.80f

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            capacityMult = tuning.value(CAPACITY),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.fluxCapacity.modifyMult(id, b.capacityMult)
        stats.fluxDissipation.modifyMult(id, DISSIPATION_MULT)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_flux_coil_expansion.desc")
        }
    }
}
