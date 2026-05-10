package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixFragmentedOrdersHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val dmgBurst = 0.06f + 0.10f * k
        val aimPenalty = 0.05f + 0.10f * k
        val speedBonus = 0.05f + 0.08f * k

        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(id, 1f + dmgBurst)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(id, 1f + dmgBurst)
        ship.mutableStats.autofireAimAccuracy.modifyFlat(id, -aimPenalty)
        ship.mutableStats.maxSpeed.modifyMult(id, 1f + speedBonus)
    }
}
