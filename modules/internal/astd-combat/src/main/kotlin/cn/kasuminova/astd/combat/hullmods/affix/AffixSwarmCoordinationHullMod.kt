package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc

/**
 * M-13 蜂群协同网络（affixes.md v3.0，编队光环：逐帧友军扫描计数，过滤全自动舰）：
 * - 按难度系数，每存在一艘全自动友军舰船（判定口径：具有"全自动舰船"船插/标签，
 *   或舰船军官为 AI 核心），为自身额外提供 1%~2% 全伤害减免与非导弹武器射速提升；
 * - 上限 25%~50%（最终乘区）。
 */
class AffixSwarmCoordinationHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_swarm_coordination"

        /** 每艘全自动友军舰船提供的加成。 */
        val PER_ALLY_BONUS = ScalingEntry(v1 = 0.01f, v2 = 0.015f, v5 = 0.02f)

        /** 加成上限（最终乘区）。 */
        val BONUS_CAP = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 全自动舰船判定：Misc.isAutomated（automated 船插/标签）或舰长为 AI 核心。 */
        fun isFullyAutomated(ship: ShipAPI): Boolean =
            Misc.isAutomated(ship) || ship.captain?.isAICore == true

        /** 计入光环的全自动友军舰船：同阵营、非自身、非舰载机、存活且非残骸、全自动。 */
        fun countsAsAutomatedAlly(ship: ShipAPI, other: ShipAPI): Boolean =
            other !== ship && other.owner == ship.owner &&
                !other.isFighter && !other.isStationModule && other.isAlive && !other.isHulk &&
                isFullyAutomated(other)

        /** 由全自动友军计数计算加成（带上限）。 */
        fun bonus(allyCount: Int, tuning: DifficultyTuning): Float {
            if (allyCount <= 0) return 0f
            return (allyCount * tuning.value(PER_ALLY_BONUS)).coerceAtMost(tuning.value(BONUS_CAP))
        }

        fun apply(stats: MutableShipStatsAPI, id: String, bonus: Float) {
            if (bonus <= 0f) {
                unapplyAll(stats, id)
                return
            }
            stats.hullDamageTakenMult.modifyMult(id, 1f - bonus)
            stats.armorDamageTakenMult.modifyMult(id, 1f - bonus)
            stats.shieldDamageTakenMult.modifyMult(id, 1f - bonus)
            stats.ballisticRoFMult.modifyMult(id, 1f + bonus)
            stats.energyRoFMult.modifyMult(id, 1f + bonus)
        }

        fun unapplyAll(stats: MutableShipStatsAPI, id: String) {
            stats.hullDamageTakenMult.unmodify(id)
            stats.armorDamageTakenMult.unmodify(id)
            stats.shieldDamageTakenMult.unmodify(id)
            stats.ballisticRoFMult.unmodify(id)
            stats.energyRoFMult.unmodify(id)
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (!ship.isAlive || ship.isHulk) {
            unapplyAll(ship.mutableStats, HULLMOD_ID)
            return
        }
        var allies = 0
        for (other in engine.ships) {
            if (countsAsAutomatedAlly(ship, other)) allies++
        }
        apply(ship.mutableStats, HULLMOD_ID, bonus(allies, DifficultyTuningImpl))
    }
}
