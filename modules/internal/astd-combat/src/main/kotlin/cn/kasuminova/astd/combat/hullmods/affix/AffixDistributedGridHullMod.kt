package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixDistributedGridHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val shieldEff = 0.05f + 0.10f * k
        val shieldUnfold = 0.15f + 0.25f * k
        val shieldArc = 15f + 30f * k
        val hullPenalty = 0.06f + 0.12f * k

        ship.mutableStats.shieldDamageTakenMult.modifyMult(id, 1f - shieldEff)
        ship.mutableStats.shieldUnfoldRateMult.modifyMult(id, 1f + shieldUnfold)
        ship.mutableStats.shieldArcBonus.modifyFlat(id, shieldArc)
        ship.mutableStats.hullDamageTakenMult.modifyMult(id, 1f + hullPenalty)
    }
}
