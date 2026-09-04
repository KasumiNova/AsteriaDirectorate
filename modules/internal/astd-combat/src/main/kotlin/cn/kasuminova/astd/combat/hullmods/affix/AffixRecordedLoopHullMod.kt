package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixRecordedLoopHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val maneuver = 0.08f + 0.15f * k
        val weaponTurn = 0.10f + 0.20f * k

        ship.mutableStats.turnAcceleration.modifyMult(id, 1f + maneuver)
        ship.mutableStats.maxTurnRate.modifyMult(id, 1f + maneuver * 0.8f)
        ship.mutableStats.weaponTurnRateBonus.modifyMult(id, 1f + weaponTurn)
        ship.mutableStats.effectiveArmorBonus.modifyFlat(id, -(50f + 150f * k))
    }
}
