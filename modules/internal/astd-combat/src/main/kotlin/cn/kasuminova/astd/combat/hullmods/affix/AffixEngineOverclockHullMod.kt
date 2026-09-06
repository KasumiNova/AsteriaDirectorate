package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀 S-05：引擎超频（[AffixRegistry.ID_ENGINE_OVERCLOCK]）。
 * 按难度系数：提升 25%~50% 最大航速（最终乘区）；降低 25% 机动性（固定值）。
 */
class AffixEngineOverclockHullMod : BaseHullMod() {

    data class Bonuses(
        val maxSpeedMult: Float,
    )

    companion object {
        val MAX_SPEED = ScalingEntry(v1 = 1.25f, v2 = 1.375f, v5 = 1.50f)

        /** 机动性惩罚（固定 -25%）。 */
        const val MANEUVER_MULT = 0.75f

        fun bonuses(tuning: DifficultyTuning): Bonuses = Bonuses(
            maxSpeedMult = tuning.value(MAX_SPEED),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val b = bonuses(AffixShared.tuning)
        stats.maxSpeed.modifyMult(id, b.maxSpeedMult)
        stats.acceleration.modifyMult(id, MANEUVER_MULT)
        stats.deceleration.modifyMult(id, MANEUVER_MULT)
        stats.turnAcceleration.modifyMult(id, MANEUVER_MULT)
        stats.maxTurnRate.modifyMult(id, MANEUVER_MULT)
    }
}
