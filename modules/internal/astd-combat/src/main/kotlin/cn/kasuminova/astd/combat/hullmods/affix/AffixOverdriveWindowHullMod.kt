package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixOverdriveWindowHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val rofBonus = 0.12f + 0.18f * k
        val dmgBonus = 0.08f + 0.12f * k
        val fluxCapPenalty = 0.06f + 0.10f * k

        ship.mutableStats.energyRoFMult.modifyMult(id, 1f + rofBonus)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(id, 1f + dmgBonus)
        ship.mutableStats.fluxCapacity.modifyMult(id, 1f - fluxCapPenalty)
    }
}
