package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixLogicGateHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val ecm = 0.05f + 0.10f * k
        val sensorBonus = 100f + 200f * k
        val missileSpeed = 0.06f + 0.12f * k

        ship.mutableStats.dynamic.getMod("opad_ecm_rating").modifyFlat(id, ecm)
        ship.mutableStats.sensorStrength.modifyFlat(id, sensorBonus)
        ship.mutableStats.missileMaxSpeedBonus.modifyMult(id, 1f - missileSpeed)
    }
}
