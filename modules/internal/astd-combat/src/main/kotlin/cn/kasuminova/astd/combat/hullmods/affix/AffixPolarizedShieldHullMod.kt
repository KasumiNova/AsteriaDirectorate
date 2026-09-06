package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-04：极化护盾发生器（[AffixRegistry.ID_POLARIZED_SHIELD]）。
 * 按难度系数：降低 25%~50% 护盾受到的伤害（最终乘区）；
 * 提升 50% 舰船过载时间（固定值）。
 */
class AffixPolarizedShieldHullMod : BaseHullMod() {

    data class Bonuses(
        val shieldDamageTakenMult: Float,
    )

    companion object {
        val SHIELD_TAKEN = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)

        /** 过载时间惩罚（固定 +50%）。 */
        const val OVERLOAD_TIME_MULT = 1.50f

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            shieldDamageTakenMult = tuning.value(SHIELD_TAKEN),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.shieldDamageTakenMult.modifyMult(id, b.shieldDamageTakenMult)
        stats.overloadTimeMod.modifyMult(id, OVERLOAD_TIME_MULT)
    }
}
