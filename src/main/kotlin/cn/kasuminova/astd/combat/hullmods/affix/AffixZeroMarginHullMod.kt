package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixZeroMarginHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val fluxCost = 0.08f + 0.12f * k
        val rof = 0.06f + 0.14f * k
        val pptLoss = 0.10f + 0.20f * k
        val crLoss = 0.05f + 0.10f * k

        ship.mutableStats.ballisticWeaponFluxCostMod.modifyMult(id, 1f - fluxCost)
        ship.mutableStats.energyWeaponFluxCostMod.modifyMult(id, 1f - fluxCost)
        ship.mutableStats.missileWeaponFluxCostMod.modifyMult(id, 1f - (fluxCost * 0.5f))

        ship.mutableStats.ballisticRoFMult.modifyMult(id, 1f + rof)
        ship.mutableStats.energyRoFMult.modifyMult(id, 1f + rof)

        ship.mutableStats.peakCRDuration.modifyMult(id, 1f - pptLoss)
        ship.mutableStats.crLossPerSecondPercent.modifyMult(id, 1f + crLoss)
    }
}
