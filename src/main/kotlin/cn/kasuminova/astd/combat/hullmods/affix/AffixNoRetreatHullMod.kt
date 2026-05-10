package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixNoRetreatHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val speedBonus = 0.06f + 0.12f * k
        val accelBonus = 0.10f + 0.20f * k
        val crLoss = 0.10f + 0.20f * k

        ship.mutableStats.maxSpeed.modifyMult(id, 1f + speedBonus)
        ship.mutableStats.acceleration.modifyMult(id, 1f + accelBonus)
        ship.mutableStats.deceleration.modifyMult(id, 1f + accelBonus * 0.8f)
        ship.mutableStats.crLossPerSecondPercent.modifyMult(id, 1f - crLoss)
    }
}
