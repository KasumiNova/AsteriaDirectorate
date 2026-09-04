package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀：超频线圈
 * - 能量武器射速提升
 * - 幅能耗散提升
 */
class AffixOverclockedCoilsHullMod : BaseHullMod() {

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        val k = AffixUtil.getK(ship)
        val rofBonus = 0.10f + 0.20f * k         // 10% ~ 30%
        val dissBonus = 0.10f + 0.15f * k        // 10% ~ 25%

        ship.mutableStats.energyRoFMult.modifyMult(id, 1f + rofBonus)
        ship.mutableStats.fluxDissipation.modifyMult(id, 1f + dissBonus)
        // 代价：武器幅耗略升，逼迫玩家抓窗口。
        ship.mutableStats.energyWeaponFluxCostMod.modifyMult(id, 1f + (0.04f + 0.06f * k))
    }
}
