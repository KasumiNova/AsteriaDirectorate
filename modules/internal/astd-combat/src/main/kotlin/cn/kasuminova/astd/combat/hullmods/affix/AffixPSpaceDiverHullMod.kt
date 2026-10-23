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

class AffixPSpaceDiverHullMod : BaseHullMod() {

    companion object {
        const val FLUX_LEVEL_FOR_MIN_SPEED_MOD = "phase_cloak_flux_level_for_min_speed_mod"

        val REDUCTION = ScalingEntry(v1 = 0.50f, v2 = 0.75f, v5 = 1.00f)

        private const val IMMUNE_THRESHOLD_MULT = 1_000_000f

        // 原版相位降速强度为 hardFluxLevel / 阈值，放大阈值会等比例削弱降速。
        fun thresholdMult(tuning: DifficultyTuning): Float {
            val reduction = tuning.value(REDUCTION).coerceIn(0f, 1f)
            return if (reduction >= 0.999f) IMMUNE_THRESHOLD_MULT else 1f / (1f - reduction)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        if (!isPhaseShip(stats)) return
        stats.dynamic.getMod(FLUX_LEVEL_FOR_MIN_SPEED_MOD).modifyMult(id, thresholdMult(AffixShared.tuning))
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_pspace_diver.desc")
        }
    }
}
