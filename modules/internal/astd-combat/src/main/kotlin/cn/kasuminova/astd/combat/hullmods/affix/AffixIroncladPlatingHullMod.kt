package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-01 铁甲重装（affixes.md v3.0）：
 * - 按难度系数提升 15%~45% 最大装甲值（最终乘区）；
 * - 按舰船大小与难度系数提升最小装甲计算值 50~100 / 100~200 / 200~400 / 300~600；
 * - 降低 15% 最大航速与机动性（最终乘区）。
 */
class AffixIroncladPlatingHullMod : BaseHullMod() {

    companion object {
        /** 最大装甲值提升（最终乘区增量）。 */
        val ARMOR_BONUS = ScalingEntry(v1 = 0.15f, v2 = 0.30f, v5 = 0.45f)

        /** 最小装甲计算值提升（按舰船大小分档，护卫舰/驱逐舰/巡洋舰/主力舰）。 */
        val MIN_ARMOR_FLAT: Map<ShipAPI.HullSize, ScalingEntry> = mapOf(
            ShipAPI.HullSize.FRIGATE to ScalingEntry(v1 = 50f, v2 = 75f, v5 = 100f),
            ShipAPI.HullSize.DESTROYER to ScalingEntry(v1 = 100f, v2 = 150f, v5 = 200f),
            ShipAPI.HullSize.CRUISER to ScalingEntry(v1 = 200f, v2 = 300f, v5 = 400f),
            ShipAPI.HullSize.CAPITAL_SHIP to ScalingEntry(v1 = 300f, v2 = 450f, v5 = 600f),
        )

        /** 最大航速与机动性惩罚（最终乘区）。 */
        const val MOBILITY_MULT = 0.85f

        fun apply(stats: MutableShipStatsAPI, hullSize: ShipAPI.HullSize, id: String, tuning: DifficultyTuning) {
            stats.armorBonus.modifyMult(id, 1f + tuning.value(ARMOR_BONUS))

            val armorRating = stats.variant?.hullSpec?.armorRating ?: 0f
            if (armorRating > 0f) {
                val entry = MIN_ARMOR_FLAT[hullSize] ?: MIN_ARMOR_FLAT.getValue(ShipAPI.HullSize.CAPITAL_SHIP)
                stats.minArmorFraction.modifyFlat(id, tuning.value(entry) / armorRating)
            }

            stats.maxSpeed.modifyMult(id, MOBILITY_MULT)
            stats.acceleration.modifyMult(id, MOBILITY_MULT)
            stats.deceleration.modifyMult(id, MOBILITY_MULT)
            stats.maxTurnRate.modifyMult(id, MOBILITY_MULT)
            stats.turnAcceleration.modifyMult(id, MOBILITY_MULT)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, hullSize, id, DifficultyTuningImpl)
    }
}
