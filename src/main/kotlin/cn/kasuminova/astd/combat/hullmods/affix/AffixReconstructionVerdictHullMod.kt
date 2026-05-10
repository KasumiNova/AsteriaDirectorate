package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixReconstructionVerdictHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val armorBonus = 0.08f + 0.15f * k
        val shieldHardness = 0.03f + 0.07f * k
        val maneuverPenalty = 0.06f + 0.10f * k

        ship.mutableStats.armorBonus.modifyMult(id, 1f + armorBonus)
        ship.mutableStats.shieldDamageTakenMult.modifyMult(id, 1f - shieldHardness)
        ship.mutableStats.maxTurnRate.modifyMult(id, 1f - maneuverPenalty)
        ship.mutableStats.turnAcceleration.modifyMult(id, 1f - maneuverPenalty)
    }
}
