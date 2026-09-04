package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixDecoySwarmHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val ecm = 0.08f + 0.12f * k
        val missileHP = 0.15f + 0.25f * k
        val rangePenalty = 0.03f + 0.05f * k

        ship.mutableStats.dynamic.getStat("eccm_chance").modifyFlat(id, ecm)
        ship.mutableStats.missileHealthBonus.modifyMult(id, 1f + missileHP)
        ship.mutableStats.fighterWingRange.modifyMult(id, 1f + (0.10f + 0.15f * k))
        ship.mutableStats.ballisticWeaponRangeBonus.modifyMult(id, 1f - rangePenalty)
        ship.mutableStats.energyWeaponRangeBonus.modifyMult(id, 1f - rangePenalty)
    }
}
