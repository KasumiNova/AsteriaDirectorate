package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixThermodynamicExchangeHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val ventRate = 0.10f + 0.20f * k
        val dissipation = 0.08f + 0.15f * k
        val pptPenalty = 0.10f + 0.20f * k

        ship.mutableStats.ventRateMult.modifyMult(id, 1f + ventRate)
        ship.mutableStats.fluxDissipation.modifyMult(id, 1f + dissipation)
        ship.mutableStats.peakCRDuration.modifyMult(id, 1f - pptPenalty)
    }
}
