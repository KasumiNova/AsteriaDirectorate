package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-01：铁甲重装（[AffixRegistry.ID_IRONCLAD_PLATING]）。
 * - 按难度系数：提升 15%~45% 最大装甲值（最终乘区）；
 * - 按舰船大小与难度系数：提升最小装甲计算值 50~100 / 100~200 / 200~400 / 300~600；
 * - 降低 15% 最大航速与机动性（最终乘区，固定值）。
 */
class AffixIroncladPlatingHullMod : BaseHullMod() {

    data class Bonuses(
        val armorMult: Float,
        val minArmorValue: Float,
    )

    companion object {
        /** 原版最小装甲比例（DamageUtil.MIN_ARMOR_FRACTION），用于把目标最小装甲值换算为比例增量。 */
        private const val BASE_MIN_ARMOR_FRACTION = 0.05f

        /** 最大装甲倍率：1.15 ~ 1.45（v2=区间中点 1.30）。 */
        val ARMOR_MULT = ScalingEntry(v1 = 1.15f, v2 = 1.30f, v5 = 1.45f)

        /** 最小装甲计算值（按舰船大小分档：护卫舰/驱逐舰/巡洋舰/主力舰）。 */
        val MIN_ARMOR_FRIGATE = ScalingEntry(v1 = 50f, v2 = 75f, v5 = 100f)
        val MIN_ARMOR_DESTROYER = ScalingEntry(v1 = 100f, v2 = 150f, v5 = 200f)
        val MIN_ARMOR_CRUISER = ScalingEntry(v1 = 200f, v2 = 300f, v5 = 400f)
        val MIN_ARMOR_CAPITAL = ScalingEntry(v1 = 300f, v2 = 450f, v5 = 600f)

        /** 航速与机动性惩罚（固定 -15%）。 */
        const val MANEUVER_MULT = 0.85f

        fun minArmorEntry(hullSize: ShipAPI.HullSize?): ScalingEntry = AffixShared.bySize(
            hullSize, MIN_ARMOR_FRIGATE, MIN_ARMOR_DESTROYER, MIN_ARMOR_CRUISER, MIN_ARMOR_CAPITAL,
        )

        /** 按给定难度读取面计算加成（完整逻辑，实现与测试共用）。 */
        fun bonuses(tuning: DifficultyTuning, hullSize: ShipAPI.HullSize?): Bonuses = Bonuses(
            armorMult = tuning.value(ARMOR_MULT),
            minArmorValue = tuning.value(minArmorEntry(hullSize)),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning, hullSize)

        stats.armorBonus.modifyMult(id, b.armorMult)

        val baseArmor = stats.variant?.hullSpec?.armorRating ?: 0f
        if (baseArmor > 0f) {
            val targetFraction = b.minArmorValue / baseArmor
            val delta = (targetFraction - BASE_MIN_ARMOR_FRACTION).coerceAtLeast(0f)
            if (delta > 0f) {
                stats.minArmorFraction.modifyFlat(id, delta)
            }
        }

        stats.maxSpeed.modifyMult(id, MANEUVER_MULT)
        stats.acceleration.modifyMult(id, MANEUVER_MULT)
        stats.deceleration.modifyMult(id, MANEUVER_MULT)
        stats.turnAcceleration.modifyMult(id, MANEUVER_MULT)
        stats.maxTurnRate.modifyMult(id, MANEUVER_MULT)
    }
}
