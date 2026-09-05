package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-07 相位线圈调谐（affixes.md v3.0）：
 * - 按难度系数提升 50%~100% 相位状态下的时间流速（最终乘区，逐帧仅在相位时挂乘区）；
 * - 按难度系数提升 100%~200% 峰值时间、降低 25%~50% CR 削减速率（最终乘区）；
 * - 仅相位舰船可搭载；与 S-08 相位线圈降频互斥（互斥表见 AffixRegistry）。
 */
class AffixPhaseCoilTuningHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_phase_coil_tuning"
        private const val ENGINE_TIME_MOD_ID = "astd_affix_phase_coil_tuning_engine"

        /** 相位状态时间流速提升（最终乘区增量）。 */
        val PHASE_TIME_FLOW_BONUS = ScalingEntry(v1 = 0.50f, v2 = 0.75f, v5 = 1.0f)
        val PEAK_CR_BONUS = ScalingEntry(v1 = 1.0f, v2 = 1.5f, v5 = 2.0f)
        val CR_LOSS_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        fun applyStatic(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.peakCRDuration.modifyMult(id, 1f + tuning.value(PEAK_CR_BONUS))
            stats.crLossPerSecondPercent.modifyMult(id, 1f - tuning.value(CR_LOSS_REDUCTION))
        }

        fun phaseTimeFlowMult(tuning: DifficultyTuning): Float = 1f + tuning.value(PHASE_TIME_FLOW_BONUS)
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        applyStatic(stats, id, DifficultyTuningImpl)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = com.fs.starfarer.api.Global.getCombatEngine()
        if (ship.isPhased) {
            val mult = phaseTimeFlowMult(DifficultyTuningImpl)
            ship.mutableStats.timeMult.modifyMult(HULLMOD_ID, mult)
            if (engine?.playerShip === ship) {
                engine.timeMult.modifyMult(ENGINE_TIME_MOD_ID, 1f / mult)
            }
        } else {
            ship.mutableStats.timeMult.unmodify(HULLMOD_ID)
            engine?.timeMult?.unmodify(ENGINE_TIME_MOD_ID)
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = AffixUtil.isPhaseShip(ship)
}
