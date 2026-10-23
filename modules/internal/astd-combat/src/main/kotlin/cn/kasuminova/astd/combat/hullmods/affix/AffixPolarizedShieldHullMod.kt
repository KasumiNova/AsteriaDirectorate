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

class AffixPolarizedShieldHullMod : BaseHullMod() {

    data class Bonuses(
        val shieldDamageTakenMult: Float,
    )

    companion object {
        val SHIELD_TAKEN = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)

        const val OVERLOAD_TIME_MULT = 1.50f

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            shieldDamageTakenMult = tuning.value(SHIELD_TAKEN),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.shieldDamageTakenMult.modifyMult(id, b.shieldDamageTakenMult)
        stats.overloadTimeMod.modifyMult(id, OVERLOAD_TIME_MULT)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.affix_polarized_shield.desc")
        }
    }
}
