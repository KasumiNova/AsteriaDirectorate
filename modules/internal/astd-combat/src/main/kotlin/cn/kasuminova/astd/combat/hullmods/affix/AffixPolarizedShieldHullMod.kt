package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-04 极化护盾发生器（affixes.md v3.0）：
 * - 按难度系数降低 25%~50% 护盾受到的伤害（最终乘区）；
 * - 提升 50% 舰船过载时间。
 */
class AffixPolarizedShieldHullMod : BaseHullMod() {

    companion object {
        val SHIELD_DAMAGE_TAKEN_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 过载时间惩罚。 */
        const val OVERLOAD_TIME_MULT = 1.50f

        fun apply(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.shieldDamageTakenMult.modifyMult(id, 1f - tuning.value(SHIELD_DAMAGE_TAKEN_REDUCTION))
            stats.overloadTimeMod.modifyMult(id, OVERLOAD_TIME_MULT)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, id, DifficultyTuningImpl)
    }
}
