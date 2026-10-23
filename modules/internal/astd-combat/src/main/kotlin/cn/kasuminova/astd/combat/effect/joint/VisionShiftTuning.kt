package cn.kasuminova.astd.combat.effect.joint

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.ShipAPI

object VisionShiftTuning {

    const val SYSTEM_RANGE = 750f

    val SELF_TIME_BONUS = ScalingEntry(0.50f, 0.75f, 1.0f)

    val TARGET_TIME_REDUCTION_FRIGATE = ScalingEntry(0.40f, 0.60f, 0.80f)

    val TARGET_TIME_REDUCTION_DESTROYER = ScalingEntry(0.35f, 0.525f, 0.70f)

    val TARGET_TIME_REDUCTION_CRUISER = ScalingEntry(0.30f, 0.45f, 0.60f)

    val TARGET_TIME_REDUCTION_CAPITAL = ScalingEntry(0.25f, 0.375f, 0.50f)

    val DAMAGE_FROM_SELF_BONUS = ScalingEntry(0.25f, 0.625f, 1.0f)

    data class Values(
        val selfTimeMult: Float,
        val targetTimeMult: Float,
        val damageFromSelfMult: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean, targetHullSize: ShipAPI.HullSize?): Values = Values(
        selfTimeMult = 1f + tuning.valueFor(SELF_TIME_BONUS, isPlayer),
        targetTimeMult = 1f - tuning.valueFor(reductionForSize(targetHullSize), isPlayer),
        damageFromSelfMult = 1f + tuning.valueFor(DAMAGE_FROM_SELF_BONUS, isPlayer),
    )

    fun reductionForSize(hullSize: ShipAPI.HullSize?): ScalingEntry = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> TARGET_TIME_REDUCTION_CAPITAL
        ShipAPI.HullSize.CRUISER -> TARGET_TIME_REDUCTION_CRUISER
        ShipAPI.HullSize.DESTROYER -> TARGET_TIME_REDUCTION_DESTROYER
        else -> TARGET_TIME_REDUCTION_FRIGATE
    }
}
