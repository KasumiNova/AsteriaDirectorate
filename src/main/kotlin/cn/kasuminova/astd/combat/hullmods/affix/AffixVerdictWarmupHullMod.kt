package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixVerdictWarmupHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val shieldDmgReduction = 0.06f + 0.12f * k
        val armorBonus = 0.08f + 0.15f * k
        val systemCooldown = 0.10f + 0.15f * k

        ship.mutableStats.shieldDamageTakenMult.modifyMult(id, 1f - shieldDmgReduction)
        ship.mutableStats.armorBonus.modifyMult(id, 1f + armorBonus)
        ship.mutableStats.systemCooldownBonus.modifyMult(id, 1f - systemCooldown)
    }
}
