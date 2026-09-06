package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-06：维度专长（[AffixRegistry.ID_DIMENSIONAL_SPECIALTY]）。
 * 按难度系数：提升 100%~200% 峰值时间；降低 25%~50% CR 削减速率（最终乘区）；
 * 降低舰船系统 10%~20% 充能时间与 10%~20% 冷却时间（最终乘区）。
 */
class AffixDimensionalSpecialtyHullMod : BaseHullMod() {

    data class Bonuses(
        val peakDurationMult: Float,
        val crLossMult: Float,
        val systemCooldownMult: Float,
        /** 系统充能速率倍率（充能时间 -x 换算为速率 ×1/(1-x)）。 */
        val systemRegenMult: Float,
    )

    companion object {
        val PEAK_DURATION = ScalingEntry(v1 = 2.0f, v2 = 2.5f, v5 = 3.0f)
        val CR_LOSS = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)
        val SYSTEM_COOLDOWN = ScalingEntry(v1 = 0.90f, v2 = 0.85f, v5 = 0.80f)

        /** 系统充能时间缩减比例：10%~20%。 */
        val SYSTEM_CHARGE_TIME_REDUCTION = ScalingEntry(v1 = 0.10f, v2 = 0.15f, v5 = 0.20f)

        fun bonuses(tuning: DifficultyTuning): Bonuses {
            val chargeReduction = tuning.value(SYSTEM_CHARGE_TIME_REDUCTION)
            return Bonuses(
                peakDurationMult = tuning.value(PEAK_DURATION),
                crLossMult = tuning.value(CR_LOSS),
                systemCooldownMult = tuning.value(SYSTEM_COOLDOWN),
                systemRegenMult = 1f / (1f - chargeReduction),
            )
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.peakCRDuration.modifyMult(id, b.peakDurationMult)
        stats.crLossPerSecondPercent.modifyMult(id, b.crLossMult)
        stats.systemCooldownBonus.modifyMult(id, b.systemCooldownMult)
        stats.systemRegenBonus.modifyMult(id, b.systemRegenMult)
    }
}
