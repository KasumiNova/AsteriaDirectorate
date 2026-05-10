package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixDualValidationHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val dmgBonus = 0.06f + 0.12f * k
        val ecm = 0.04f + 0.08f * k
        val fluxCapBonus = 0.05f + 0.10f * k
        val pptPenalty = 0.08f + 0.15f * k

        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(id, 1f + dmgBonus)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(id, 1f + dmgBonus)
        ship.mutableStats.dynamic.getMod("opad_ecm_rating").modifyFlat(id, ecm)
        ship.mutableStats.fluxCapacity.modifyMult(id, 1f + fluxCapBonus)
        ship.mutableStats.peakCRDuration.modifyMult(id, 1f - pptPenalty)
    }
}
