package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-06 维度专长（affixes.md v3.0）：
 * - 按难度系数提升 100%~200% 峰值时间、降低 25%~50% CR 削减速率（最终乘区）；
 * - 按难度系数降低舰船系统 10%~20% 充能时间与 10%~20% 冷却时间（最终乘区）。
 *
 * 注：systemRegenBonus 表示充能速率，需换算为 1/(1-x)；systemCooldownBonus 表示冷却时长，直接使用 1-x。
 */
class AffixDimensionalSpecialtyHullMod : BaseHullMod() {

    companion object {
        val PEAK_CR_BONUS = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)
        val CR_LOSS_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 舰船系统充能/冷却时间降低比例。 */
        val SYSTEM_TIME_REDUCTION = ScalingEntry(v1 = 0.10f, v2 = 0.15f, v5 = 0.20f)

        /** 时间降低比例 → 速率乘区换算。 */
        fun timeReductionToRateMult(reduction: Float): Float = 1f / (1f - reduction.coerceIn(0f, 0.95f))

        fun apply(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.peakCRDuration.modifyMult(id, 1f + tuning.value(PEAK_CR_BONUS))
            stats.crLossPerSecondPercent.modifyMult(id, 1f - tuning.value(CR_LOSS_REDUCTION))
            val reduction = tuning.value(SYSTEM_TIME_REDUCTION)
            stats.systemRegenBonus.modifyMult(id, timeReductionToRateMult(reduction))
            stats.systemCooldownBonus.modifyMult(id, 1f - reduction)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, id, DifficultyTuningImpl)
    }
}
