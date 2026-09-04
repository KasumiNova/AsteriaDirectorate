package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixRealitySieveHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val timeMult = 0.04f + 0.08f * k
        val projSpeed = 0.05f + 0.10f * k
        val turnDown = 0.05f + 0.10f * k

        ship.mutableStats.timeMult.modifyMult(id, 1f + timeMult)
        ship.mutableStats.projectileSpeedMult.modifyMult(id, 1f + projSpeed)
        ship.mutableStats.maxTurnRate.modifyMult(id, 1f - turnDown)
    }
}
