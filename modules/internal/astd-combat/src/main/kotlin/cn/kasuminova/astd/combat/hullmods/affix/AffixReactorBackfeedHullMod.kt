package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixReactorBackfeedHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val overloadReduction = 0.10f + 0.15f * k
        val ventBonus = 0.08f + 0.12f * k
        val hullDmgTaken = 0.05f + 0.10f * k

        ship.mutableStats.overloadTimeMod.modifyMult(id, 1f - overloadReduction)
        ship.mutableStats.ventRateMult.modifyMult(id, 1f + ventBonus)
        ship.mutableStats.hullDamageTakenMult.modifyMult(id, 1f + hullDmgTaken)
    }
}
