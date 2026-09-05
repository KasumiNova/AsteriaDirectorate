package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-08 相位线圈降频（affixes.md v3.0）：
 * - 按难度系数降低 25%~50% 相位状态下的时间流速（最终乘区，逐帧仅在相位时挂乘区）；
 * - 按难度系数降低 25%~50% 相位状态下的辐能产出（相位线圈维持成本乘区）；
 * - 按难度系数降低 25%~50% 相位线圈冷却时间；
 * - 仅相位舰船可搭载；与 S-07 相位线圈调谐互斥（互斥表见 AffixRegistry）。
 */
class AffixPhaseCoilDetuningHullMod : BaseHullMod() {

    companion object {
        const val HULLMOD_ID = "astd_affix_phase_coil_detuning"
        private const val ENGINE_TIME_MOD_ID = "astd_affix_phase_coil_detuning_engine"

        /** 相位状态时间流速降低（最终乘区）。 */
        val PHASE_TIME_FLOW_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 相位状态辐能产出降低（作用于相位线圈维持成本乘区）。 */
        val PHASE_FLUX_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 相位线圈冷却时间降低。 */
        val PHASE_COOLDOWN_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        fun applyStatic(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.phaseCloakUpkeepCostBonus.modifyMult(id, 1f - tuning.value(PHASE_FLUX_REDUCTION))
            stats.phaseCloakCooldownBonus.modifyMult(id, 1f - tuning.value(PHASE_COOLDOWN_REDUCTION))
        }

        fun phaseTimeFlowMult(tuning: DifficultyTuning): Float = 1f - tuning.value(PHASE_TIME_FLOW_REDUCTION)
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
