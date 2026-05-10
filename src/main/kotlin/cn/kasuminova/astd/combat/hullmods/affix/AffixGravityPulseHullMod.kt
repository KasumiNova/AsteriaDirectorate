package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixGravityPulseHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val projSpeed = 0.10f + 0.20f * k
        val recoil = 0.10f + 0.25f * k
        val weaponTurn = 0.08f + 0.15f * k
        val rangePenalty = 0.05f + 0.10f * k

        ship.mutableStats.projectileSpeedMult.modifyMult(id, 1f + projSpeed)
        ship.mutableStats.recoilPerShotMult.modifyMult(id, 1f - recoil)
        ship.mutableStats.weaponTurnRateBonus.modifyMult(id, 1f + weaponTurn)
        ship.mutableStats.ballisticWeaponRangeBonus.modifyMult(id, 1f - rangePenalty)
        ship.mutableStats.energyWeaponRangeBonus.modifyMult(id, 1f - rangePenalty * 0.6f)
    }
}
