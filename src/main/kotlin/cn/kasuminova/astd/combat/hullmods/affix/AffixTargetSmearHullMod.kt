package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixTargetSmearHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val aimDown = 0.08f + 0.15f * k
        val rofBonus = 0.06f + 0.12f * k
        val rangeDown = 0.04f + 0.08f * k

        ship.mutableStats.autofireAimAccuracy.modifyFlat(id, -aimDown)
        ship.mutableStats.ballisticRoFMult.modifyMult(id, 1f + rofBonus)
        ship.mutableStats.energyRoFMult.modifyMult(id, 1f + rofBonus)
        ship.mutableStats.ballisticWeaponRangeBonus.modifyMult(id, 1f - rangeDown)
        ship.mutableStats.energyWeaponRangeBonus.modifyMult(id, 1f - rangeDown)
    }
}
