package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixVectorSilenceHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val ecm = 0.06f + 0.12f * k
        val sensorBonus = 200f + 400f * k
        val sightRange = 0.08f + 0.15f * k

        ship.mutableStats.dynamic.getMod("opad_ecm_rating").modifyFlat(id, ecm)
        ship.mutableStats.sensorStrength.modifyFlat(id, sensorBonus)
        ship.mutableStats.sightRadiusMod.modifyMult(id, 1f + sightRange)
    }
}
