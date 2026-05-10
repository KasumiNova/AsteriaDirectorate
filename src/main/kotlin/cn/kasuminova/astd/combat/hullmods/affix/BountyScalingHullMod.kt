package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class BountyScalingHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)
        if (k <= 0f) return

        ship.mutableStats.hullBonus.modifyMult(id, 1f + 0.05f * k)
        ship.mutableStats.armorBonus.modifyMult(id, 1f + 0.05f * k)
        ship.mutableStats.fluxCapacity.modifyMult(id, 1f + 0.04f * k)
        ship.mutableStats.fluxDissipation.modifyMult(id, 1f + 0.06f * k)
        ship.mutableStats.maxSpeed.modifyMult(id, 1f + 0.02f * k)
    }
}
