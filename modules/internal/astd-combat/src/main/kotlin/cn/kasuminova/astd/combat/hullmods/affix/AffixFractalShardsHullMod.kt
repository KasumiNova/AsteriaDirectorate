package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixFractalShardsHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val explosionDmg = 0.08f + 0.15f * k
        val missileHp = 0.10f + 0.20f * k
        val hullBonus = 0.05f + 0.10f * k

        ship.mutableStats.missileWeaponDamageMult.modifyMult(id, 1f + explosionDmg)
        ship.mutableStats.missileHealthBonus.modifyMult(id, 1f + missileHp)
        ship.mutableStats.hullBonus.modifyMult(id, 1f + hullBonus)
    }
}
