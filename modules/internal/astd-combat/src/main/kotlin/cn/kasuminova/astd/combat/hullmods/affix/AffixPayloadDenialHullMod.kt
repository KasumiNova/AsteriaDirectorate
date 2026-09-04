package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixPayloadDenialHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val pdDmg = 0.08f + 0.15f * k
        val missileVuln = 0.06f + 0.12f * k

        ship.mutableStats.damageToMissiles.modifyMult(id, 1f + pdDmg)
        ship.mutableStats.damageToFighters.modifyMult(id, 1f + pdDmg * 0.5f)
        ship.mutableStats.nonBeamPDWeaponRangeBonus.modifyMult(id, 1f + (0.05f + 0.10f * k))
        ship.mutableStats.beamPDWeaponRangeBonus.modifyMult(id, 1f + (0.05f + 0.10f * k))
        ship.mutableStats.missileWeaponDamageMult.modifyMult(id, 1f + missileVuln)
    }
}
