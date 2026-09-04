package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixCausalityLagHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val systemCooldown = 0.10f + 0.20f * k
        val timeMult = 0.03f + 0.07f * k
        val shieldTurnPenalty = 0.08f + 0.15f * k

        ship.mutableStats.systemCooldownBonus.modifyMult(id, 1f - systemCooldown)
        ship.mutableStats.timeMult.modifyMult(id, 1f + timeMult)
        ship.mutableStats.shieldTurnRateMult.modifyMult(id, 1f - shieldTurnPenalty)
    }
}
