package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods

/**
 * 词缀 M-13：蜂群协同网络（[AffixRegistry.ID_SWARM_COORDINATION]）。
 * 编队光环：每存在一艘全自动友军舰船（具有"全自动舰船"船插，或舰船军官为 AI 核心），
 * 为自身额外提供 1%~2% 全伤害减免与非导弹武器射速提升；上限 25%~50%（最终乘区）。
 */
class AffixSwarmCoordinationHullMod : BaseHullMod() {

    data class Bonuses(
        val damageTakenMult: Float,
        val rofMult: Float,
    )

    companion object {
        val PER_ALLY = ScalingEntry(v1 = 0.01f, v2 = 0.015f, v5 = 0.02f)
        val CAP = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 战斗内逐帧修饰的 stat 源 id（advanceInCombat 无 id 形参）。 */
        private const val MOD_ID = "astd_affix_swarm_coordination"

        /** 全自动舰判定：具有"全自动舰船"船插，或舰长为 AI 核心。 */
        fun isAutomated(ship: ShipAPI): Boolean {
            if (ship.variant?.hasHullMod(HullMods.AUTOMATED) == true) return true
            return ship.fleetMember?.captain?.isAICore == true
        }

        /** 由全自动友舰数量计算减伤/射速倍率（完整逻辑，实现与测试共用）。 */
        fun bonuses(tuning: DifficultyTuning, automatedAllyCount: Int): Bonuses {
            val bonus = minOf(
                automatedAllyCount.coerceAtLeast(0) * tuning.value(PER_ALLY),
                tuning.value(CAP),
            )
            return Bonuses(
                damageTakenMult = 1f - bonus,
                rofMult = 1f + bonus,
            )
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val automated = AffixShared.aliveAllies(ship).count { isAutomated(it) }
        val b = bonuses(AffixShared.tuning, automated)
        val stats = ship.mutableStats
        stats.hullDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.armorDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.shieldDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.empDamageTakenMult.modifyMult(MOD_ID, b.damageTakenMult)
        stats.ballisticRoFMult.modifyMult(MOD_ID, b.rofMult)
        stats.energyRoFMult.modifyMult(MOD_ID, b.rofMult)
    }
}
