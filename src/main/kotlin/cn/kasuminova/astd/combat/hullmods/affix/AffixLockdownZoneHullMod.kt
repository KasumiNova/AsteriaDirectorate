package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixLockdownZoneHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val sensorRange = 150f + 350f * k
        val sightRange = 0.05f + 0.10f * k
        val speedBonus = 0.04f + 0.08f * k

        ship.mutableStats.sensorStrength.modifyFlat(id, sensorRange)
        ship.mutableStats.sightRadiusMod.modifyMult(id, 1f + sightRange)
        ship.mutableStats.maxSpeed.modifyMult(id, 1f + speedBonus)
    }
}
