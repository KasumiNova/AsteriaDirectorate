package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixCoherentLinkHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val shieldTurn = 0.15f + 0.25f * k
        val dissipation = 0.08f + 0.12f * k
        val armorWeakness = 0.04f + 0.08f * k

        ship.mutableStats.shieldTurnRateMult.modifyMult(id, 1f + shieldTurn)
        ship.mutableStats.fluxDissipation.modifyMult(id, 1f + dissipation)
        ship.mutableStats.shieldUnfoldRateMult.modifyMult(id, 1f + (0.10f + 0.20f * k))
        ship.mutableStats.armorDamageTakenMult.modifyMult(id, 1f + armorWeakness)
    }
}
