package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * S-05 引擎超频（affixes.md v3.0）：
 * - 按难度系数提升 25%~50% 最大航速（最终乘区）；
 * - 降低 25% 机动性。
 */
class AffixEngineOverclockHullMod : BaseHullMod() {

    companion object {
        val MAX_SPEED_BONUS = ScalingEntry(v1 = 0.25f, v2 = 0.375f, v5 = 0.50f)

        /** 机动性惩罚。 */
        const val MANEUVER_MULT = 0.75f

        fun apply(stats: MutableShipStatsAPI, id: String, tuning: DifficultyTuning) {
            stats.maxSpeed.modifyMult(id, 1f + tuning.value(MAX_SPEED_BONUS))
            stats.acceleration.modifyMult(id, MANEUVER_MULT)
            stats.deceleration.modifyMult(id, MANEUVER_MULT)
            stats.maxTurnRate.modifyMult(id, MANEUVER_MULT)
            stats.turnAcceleration.modifyMult(id, MANEUVER_MULT)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        apply(stats, id, DifficultyTuningImpl)
    }
}
