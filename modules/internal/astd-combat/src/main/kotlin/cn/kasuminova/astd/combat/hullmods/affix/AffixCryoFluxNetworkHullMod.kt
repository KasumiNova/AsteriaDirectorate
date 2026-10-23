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

class AffixCryoFluxNetworkHullMod : BaseHullMod() {

    data class Bonuses(
        val dissipationMult: Float,
        val ventRateMult: Float,
        val empDamageTakenMult: Float,
    )

    companion object {
        val DISSIPATION = ScalingEntry(v1 = 1.10f, v2 = 1.15f, v5 = 1.20f)
        val VENT_RATE = ScalingEntry(v1 = 1.20f, v2 = 1.30f, v5 = 1.40f)
        val EMP_TAKEN = ScalingEntry(v1 = 0.80f, v2 = 0.70f, v5 = 0.60f)

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            dissipationMult = tuning.value(DISSIPATION),
            ventRateMult = tuning.value(VENT_RATE),
            empDamageTakenMult = tuning.value(EMP_TAKEN),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.fluxDissipation.modifyMult(id, b.dissipationMult)
        stats.ventRateMult.modifyMult(id, b.ventRateMult)
        stats.empDamageTakenMult.modifyMult(id, b.empDamageTakenMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_cryo_flux_network.desc")
        }
    }
}
