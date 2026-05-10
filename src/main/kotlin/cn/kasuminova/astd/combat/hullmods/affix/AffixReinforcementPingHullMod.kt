package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixReinforcementPingHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val refitReduction = 0.12f + 0.20f * k
        val pdPenalty = 0.08f + 0.15f * k

        ship.mutableStats.fighterRefitTimeMult.modifyMult(id, 1f - refitReduction)
        ship.mutableStats.fighterWingRange.modifyMult(id, 1f + (0.05f + 0.10f * k))
        ship.mutableStats.damageToMissiles.modifyMult(id, 1f - pdPenalty)
        ship.mutableStats.damageToFighters.modifyMult(id, 1f - pdPenalty * 0.5f)
    }
}
