package cn.kasuminova.astd.combat.hullmods

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

class AsteriaTestShieldCoverageHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID: String = "astd_test_shield_coverage"
    }

    private val SHIELD_ARC_MULT = 2f

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.shieldArcBonus.modifyMult(id, SHIELD_ARC_MULT)
    }
}
