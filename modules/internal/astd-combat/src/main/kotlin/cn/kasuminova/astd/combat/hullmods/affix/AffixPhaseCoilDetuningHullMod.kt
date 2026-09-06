package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-08：相位线圈降频（[AffixRegistry.ID_PHASE_COIL_DETUNING]）。
 * 按难度系数：降低 25%~50% 相位状态下的时间流速（最终乘区）；
 * 降低 25%~50% 相位状态下的辐能产出；降低 25%~50% 相位线圈冷却时间。
 * 仅相位舰船可搭载；与相位线圈调谐互斥（抽取时强制）。
 */
class AffixPhaseCoilDetuningHullMod : BaseHullMod() {

    data class Bonuses(
        /** 相位时间流速动态修正（phase_time_mult）倍率。 */
        val phaseTimeMultMod: Float,
        val upkeepMult: Float,
        val cooldownMult: Float,
    )

    companion object {
        /** 相位时间流速降幅：25%~50%（dynamic mod 乘 1 - 1.5x，见调谐侧公式说明）。 */
        val PHASE_TIME_REDUCTION = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)
        val UPKEEP = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)
        val COOLDOWN = ScalingEntry(v1 = 0.75f, v2 = 0.625f, v5 = 0.50f)

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            phaseTimeMultMod = 1f - 1.5f * tuning.value(PHASE_TIME_REDUCTION),
            upkeepMult = tuning.value(UPKEEP),
            cooldownMult = tuning.value(COOLDOWN),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        if (!AffixShared.isPhaseShip(stats)) return
        val b = bonuses(AffixShared.tuning)
        stats.dynamic.getMod(AffixPhaseCoilTuningHullMod.PHASE_TIME_MULT_MOD).modifyMult(id, b.phaseTimeMultMod)
        stats.phaseCloakUpkeepCostBonus.modifyMult(id, b.upkeepMult)
        stats.phaseCloakCooldownBonus.modifyMult(id, b.cooldownMult)
    }
}
