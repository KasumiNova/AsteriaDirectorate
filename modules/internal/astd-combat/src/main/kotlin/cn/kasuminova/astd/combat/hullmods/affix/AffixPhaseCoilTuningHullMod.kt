package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-07：相位线圈调谐（[AffixRegistry.ID_PHASE_COIL_TUNING]）。
 * 按难度系数：提升 50%~100% 相位状态下的时间流速（最终乘区）；
 * 提升 100%~200% 峰值时间；降低 25%~50% CR 削减速率（最终乘区）。
 * 仅相位舰船可搭载；与相位线圈降频互斥（抽取时强制）。
 */
class AffixPhaseCoilTuningHullMod : BaseHullMod() {

    data class Bonuses(
        /** 相位时间流速动态修正（phase_time_mult）倍率。 */
        val phaseTimeMultMod: Float,
        val peakDurationMult: Float,
        val crLossMult: Float,
    )

    companion object {
        /**
         * 原版相位时间流速公式：maxTimeMult = 1 + (3-1) × phase_time_mult（默认 1 → 3 倍）。
         * 要让结果提升 x，dynamic mod 需乘 (1 + 1.5x)。
         */
        val PHASE_TIME_BONUS = ScalingEntry(v1 = 0.50f, v2 = 0.75f, v5 = 1.00f)
        val PEAK_DURATION = ScalingEntry(v1 = 2.0f, v2 = 2.5f, v5 = 3.0f)
        val CR_LOSS = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)

        const val PHASE_TIME_MULT_MOD = "phase_time_mult"

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            phaseTimeMultMod = 1f + 1.5f * tuning.value(PHASE_TIME_BONUS),
            peakDurationMult = tuning.value(PEAK_DURATION),
            crLossMult = tuning.value(CR_LOSS),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        if (!AffixShared.isPhaseShip(stats)) return
        val b = bonuses(AffixShared.tuning)
        stats.dynamic.getMod(PHASE_TIME_MULT_MOD).modifyMult(id, b.phaseTimeMultMod)
        stats.peakCRDuration.modifyMult(id, b.peakDurationMult)
        stats.crLossPerSecondPercent.modifyMult(id, b.crLossMult)
    }
}
