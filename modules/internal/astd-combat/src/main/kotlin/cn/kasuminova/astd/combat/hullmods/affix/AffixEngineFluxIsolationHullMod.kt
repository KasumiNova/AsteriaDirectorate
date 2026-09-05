package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * M-12 引擎辐能网隔离（affixes.md v3.0）：
 * - 按难度系数提升 20%~40% 零辐能加速阈值；
 * - 按难度系数提升 25%~50% 零辐能加速的航速增益（最终乘区）。
 */
class AffixEngineFluxIsolationHullMod : BaseHullMod() {

    companion object {
        val ZERO_FLUX_THRESHOLD_BONUS = ScalingEntry(v1 = 0.20f, v2 = 0.30f, v5 = 0.40f)
        val ZERO_FLUX_SPEED_BONUS = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        fun apply(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.zeroFluxMinimumFluxLevel.modifyMult(id, 1f + tuning.value(ZERO_FLUX_THRESHOLD_BONUS))
            stats.zeroFluxSpeedBoost.modifyMult(id, 1f + tuning.value(ZERO_FLUX_SPEED_BONUS))
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, id, DifficultyTuningImpl)
    }
}
