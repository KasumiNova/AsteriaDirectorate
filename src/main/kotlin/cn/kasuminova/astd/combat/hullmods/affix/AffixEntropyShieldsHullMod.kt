package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

class AffixEntropyShieldsHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)

        val takeLess = 0.08f + 0.12f * k
        val upkeepMore = 0.10f + 0.20f * k

        ship.mutableStats.shieldDamageTakenMult.modifyMult(id, 1f - takeLess)
        ship.mutableStats.shieldUpkeepMult.modifyMult(id, 1f + upkeepMore)
    }
}
