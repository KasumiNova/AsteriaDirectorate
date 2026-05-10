package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixPhaseDebtHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val phaseCost = 0.20f + 0.40f * k
        val dmgBonus = 0.10f + 0.20f * k
        val speedBonus = 0.06f + 0.12f * k

        ship.mutableStats.phaseCloakUpkeepCostBonus.modifyMult(id, 1f + phaseCost)
        ship.mutableStats.phaseCloakActivationCostBonus.modifyMult(id, 1f + phaseCost * 0.6f)
        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(id, 1f + dmgBonus)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(id, 1f + dmgBonus)
        ship.mutableStats.maxSpeed.modifyMult(id, 1f + speedBonus)
    }
}
