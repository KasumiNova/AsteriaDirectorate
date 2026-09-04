package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixPhaseInstabilityHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val upkeepMore = 0.15f + 0.35f * k
        val actMore = 0.10f + 0.25f * k
        ship.mutableStats.phaseCloakUpkeepCostBonus.modifyMult(id, 1f + upkeepMore)
        ship.mutableStats.phaseCloakActivationCostBonus.modifyMult(id, 1f + actMore)

        val dmg = 0.04f + 0.10f * k
        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(id, 1f + dmg)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(id, 1f + dmg)
    }
}
