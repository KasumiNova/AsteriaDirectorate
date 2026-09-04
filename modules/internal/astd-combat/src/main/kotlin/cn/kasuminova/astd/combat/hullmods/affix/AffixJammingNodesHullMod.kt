package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixJammingNodesHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val speed = 0.06f + 0.10f * k
        val turn = 0.10f + 0.25f * k
        val aim = 0.10f + 0.25f * k

        ship.mutableStats.maxSpeed.modifyMult(id, 1f + speed)
        ship.mutableStats.acceleration.modifyMult(id, 1f + turn)
        ship.mutableStats.deceleration.modifyMult(id, 1f + turn)
        ship.mutableStats.turnAcceleration.modifyMult(id, 1f + turn)
        ship.mutableStats.maxTurnRate.modifyMult(id, 1f + (0.08f + 0.18f * k))

        ship.mutableStats.autofireAimAccuracy.modifyFlat(id, aim)
    }
}
