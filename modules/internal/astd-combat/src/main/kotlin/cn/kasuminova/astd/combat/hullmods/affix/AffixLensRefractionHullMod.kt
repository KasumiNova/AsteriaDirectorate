package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixLensRefractionHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val pdRange = 0.10f + 0.20f * k
        val pdDamage = 0.10f + 0.15f * k

        ship.mutableStats.nonBeamPDWeaponRangeBonus.modifyMult(id, 1f + pdRange)
        ship.mutableStats.beamPDWeaponRangeBonus.modifyMult(id, 1f + pdRange)
        ship.mutableStats.damageToMissiles.modifyMult(id, 1f + pdDamage)
        ship.mutableStats.damageToFighters.modifyMult(id, 1f + (pdDamage * 0.5f))
        ship.mutableStats.fluxDissipation.modifyMult(id, 1f + (0.03f + 0.05f * k))
    }
}
