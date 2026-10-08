package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI

class AsteriaTestShieldCoverageHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID: String = "astd_test_shield_coverage"
    }

    private val SHIELD_ARC_MULT = 2f

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.shieldArcBonus.modifyMult(id, SHIELD_ARC_MULT)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, HullmodThemes.ARC, spec?.displayName) {
            para("ui.hullmod.test_shield_coverage.desc")
        }
    }
}
