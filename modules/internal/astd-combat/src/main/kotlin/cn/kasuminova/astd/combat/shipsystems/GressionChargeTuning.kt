package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.combat.hullmods.arc.VectorBoostThrusterTuning
import kotlin.math.sqrt

internal object GressionChargeTuning {

    val CONSERVATIVE: ScalingMap = ScalingMap { k, v1, v2, v5 ->
        when {
            k <= 2f -> v1 + (v2 - v1) * (k - 1f).coerceIn(0f, 1f)
            else -> {
                val t = sqrt(((k - 2f) / 3f).coerceIn(0f, 1f))
                v2 + (v5 - v2) * t
            }
        }
    }

    val SPEED_PERCENT = ScalingEntry(150f, 200f, 350f)

    val DAMAGE_REDUCTION = ScalingEntry(0.40f, 0.50f, 0.80f)

    val MASS_BONUS = ScalingEntry(1f, 2f, 5f, CONSERVATIVE)

    val CONE_ARC_DEG = ScalingEntry(40f, 60f, 140f, CONSERVATIVE)

    val CONE_RANGE = ScalingEntry(400f, 450f, 600f, CONSERVATIVE)

    val CONE_DAMAGE = ScalingEntry(600f, 1000f, 2200f, CONSERVATIVE)

    const val TURN_RETAIN = 0.2f

    const val IMPACT_COLLISION_DAMAGE = 60f

    const val IMPACT_PUSH_MULT = 0.4f

    fun speedPercent(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        tuning.valueFor(SPEED_PERCENT, isPlayer)

    fun damageTakenMult(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        1f - tuning.valueFor(DAMAGE_REDUCTION, isPlayer).coerceIn(0f, 1f)

    fun halfAngleDeg(arcDeg: Float): Float = GressionChargeMath.halfAngleDeg(arcDeg)

    fun massMultiplier(bonusFraction: Float): Float = GressionChargeMath.massMultiplier(bonusFraction)

    fun coneRange(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        tuning.valueFor(CONE_RANGE, isPlayer)

    fun coneDamage(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        tuning.valueFor(CONE_DAMAGE, isPlayer)

    fun claimBaseMass(customData: MutableMap<String, Any?>, shipId: String, currentMass: Float): Float? {
        val key = VectorBoostThrusterTuning.massBaseKey(shipId)
        val cached = customData[key] as? Float
        if (cached != null && cached > 0f) return cached
        if (currentMass.isNaN() || currentMass <= 0f) return null
        customData[key] = currentMass
        return currentMass
    }

    fun scaledMass(baseMass: Float, multiplier: Float): Float = baseMass * multiplier

    fun massOwnerKey(shipId: String): String = VectorBoostThrusterTuning.massOwnerKey(shipId)

}
