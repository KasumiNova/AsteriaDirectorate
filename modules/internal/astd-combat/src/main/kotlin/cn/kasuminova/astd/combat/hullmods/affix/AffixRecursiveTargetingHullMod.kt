package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI

/**
 * M-09 递归式目标定位系统（affixes.md v3.0，编队光环：逐帧友军扫描计数）：
 * - 每艘友军舰船（不含自身与舰载机）按舰船大小与难度系数为自身额外提供
 *   1%~2% / 2%~4% / 3%~6% / 4%~8% 舰船武器最大射程，以及同数值的能量与实弹武器射弹飞行速度；
 * - 上限：射程最大 15%~30% / 20%~40% / 30%~60% / 40%~80%，射弹飞行速度最大 40%~80%（最终乘区）。
 */
class AffixRecursiveTargetingHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_recursive_targeting"

        /** 每艘友军舰船提供的射程/弹速增量（按舰船大小分档）。 */
        val PER_ALLY_BONUS: Map<ShipAPI.HullSize, ScalingEntry> = mapOf(
            ShipAPI.HullSize.FRIGATE to ScalingEntry(v1 = 0.01f, v2 = 0.015f, v5 = 0.02f),
            ShipAPI.HullSize.DESTROYER to ScalingEntry(v1 = 0.02f, v2 = 0.03f, v5 = 0.04f),
            ShipAPI.HullSize.CRUISER to ScalingEntry(v1 = 0.03f, v2 = 0.045f, v5 = 0.06f),
            ShipAPI.HullSize.CAPITAL_SHIP to ScalingEntry(v1 = 0.04f, v2 = 0.06f, v5 = 0.08f),
        )

        /** 射程加成上限（按舰船大小分档）。 */
        val RANGE_CAP: Map<ShipAPI.HullSize, ScalingEntry> = mapOf(
            ShipAPI.HullSize.FRIGATE to ScalingEntry(v1 = 0.15f, v2 = 0.225f, v5 = 0.30f),
            ShipAPI.HullSize.DESTROYER to ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f),
            ShipAPI.HullSize.CRUISER to ScalingEntry(v1 = 0.30f, v2 = 0.45f, v5 = 0.60f),
            ShipAPI.HullSize.CAPITAL_SHIP to ScalingEntry(v1 = 0.40f, v2 = 0.60f, v5 = 0.80f),
        )

        /** 射弹飞行速度加成上限（不分档）。 */
        val PROJ_SPEED_CAP = ScalingEntry(v1 = 0.40f, v2 = 0.60f, v5 = 0.80f)

        /** 计入光环的友军舰船：同阵营、非自身、非舰载机、存活且非残骸。 */
        fun countsAsAlly(ship: ShipAPI, other: ShipAPI): Boolean =
            other !== ship && other.owner == ship.owner &&
                !other.isFighter && !other.isStationModule && other.isAlive && !other.isHulk

        /** 由友军计数计算射程/弹速加成（带各自上限）。 */
        fun bonuses(
            hullSize: ShipAPI.HullSize,
            allyCount: Int,
            tuning: DifficultyTuning,
        ): Pair<Float, Float> {
            if (allyCount <= 0) return 0f to 0f
            val perAlly = tuning.value(PER_ALLY_BONUS[hullSize] ?: PER_ALLY_BONUS.getValue(ShipAPI.HullSize.CAPITAL_SHIP))
            val rangeCap = tuning.value(RANGE_CAP[hullSize] ?: RANGE_CAP.getValue(ShipAPI.HullSize.CAPITAL_SHIP))
            val projCap = tuning.value(PROJ_SPEED_CAP)
            val raw = allyCount * perAlly
            return raw.coerceAtMost(rangeCap) to raw.coerceAtMost(projCap)
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        val stats = ship.mutableStats
        if (!ship.isAlive || ship.isHulk) {
            unapplyAll(stats, HULLMOD_ID)
            return
        }
        var allies = 0
        for (other in engine.ships) {
            if (countsAsAlly(ship, other)) allies++
        }
        val (rangeBonus, projSpeedBonus) = bonuses(ship.hullSize, allies, DifficultyTuningImpl)
        if (rangeBonus <= 0f && projSpeedBonus <= 0f) {
            unapplyAll(stats, HULLMOD_ID)
            return
        }
        stats.ballisticWeaponRangeBonus.modifyPercent(HULLMOD_ID, rangeBonus * 100f)
        stats.energyWeaponRangeBonus.modifyPercent(HULLMOD_ID, rangeBonus * 100f)
        stats.missileWeaponRangeBonus.modifyPercent(HULLMOD_ID, rangeBonus * 100f)
        stats.ballisticProjectileSpeedMult.modifyMult(HULLMOD_ID, 1f + projSpeedBonus)
        stats.energyProjectileSpeedMult.modifyMult(HULLMOD_ID, 1f + projSpeedBonus)
    }

    private fun unapplyAll(stats: com.fs.starfarer.api.combat.MutableShipStatsAPI, id: String) {
        stats.ballisticWeaponRangeBonus.unmodify(id)
        stats.energyWeaponRangeBonus.unmodify(id)
        stats.missileWeaponRangeBonus.unmodify(id)
        stats.ballisticProjectileSpeedMult.unmodify(id)
        stats.energyProjectileSpeedMult.unmodify(id)
    }
}
