package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.roundToInt

object GravStormTuning {

    const val SYSTEM_ID = "astd_grav_storm"

    const val BASE_RANGE = 1200f

    const val CONE_HALF_ANGLE_DEG = 30f

    const val MAX_CHARGE_SECONDS = 4f
    const val MIN_CHARGE_SECONDS = 2f

    const val PHASE_LOCKOUT_SECONDS = MIN_CHARGE_SECONDS

    // setCooldownRemaining 会直接进入 COOLDOWN；强制 IDLE 会在同帧被相位按键重新激活。
    const val PHASE_LOCKOUT_COOLDOWN_REMAINING = 0.1f

    const val RELEASE_WINDOW_SECONDS = 1.5f

    const val COOLDOWN_SECONDS = 24f

    const val ACTIVATION_FLUX_FRACTION = 0.2f

    const val TARGET_COUNT_PENALTY_PER_EXTRA = 0.10f
    const val TARGET_COUNT_PENALTY_CAP = 0.50f

    val ARC_ENERGY_DAMAGE = ScalingEntry(150f, 300f, 600f)

    val ARC_EMP_DAMAGE = ScalingEntry(300f, 600f, 1200f)

    val DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.25f, 0.50f, 0.90f)

    val OVERLOAD_FRIGATE = ScalingEntry(0.5f, 1f, 1.5f)
    val OVERLOAD_DESTROYER = ScalingEntry(1f, 2f, 3f)
    val OVERLOAD_CRUISER = ScalingEntry(1f, 2f, 3f)
    val OVERLOAD_CAPITAL = ScalingEntry(2f, 3f, 6f)

    data class Values(
        val arcEnergyDamage: Float,
        val arcEmpDamage: Float,
        val damageTakenReduction: Float,
        val overloadFrigate: Float,
        val overloadDestroyer: Float,
        val overloadCruiser: Float,
        val overloadCapital: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        arcEnergyDamage = tuning.valueFor(ARC_ENERGY_DAMAGE, isPlayer),
        arcEmpDamage = tuning.valueFor(ARC_EMP_DAMAGE, isPlayer),
        damageTakenReduction = tuning.valueFor(DAMAGE_TAKEN_REDUCTION, isPlayer),
        overloadFrigate = tuning.valueFor(OVERLOAD_FRIGATE, isPlayer),
        overloadDestroyer = tuning.valueFor(OVERLOAD_DESTROYER, isPlayer),
        overloadCruiser = tuning.valueFor(OVERLOAD_CRUISER, isPlayer),
        overloadCapital = tuning.valueFor(OVERLOAD_CAPITAL, isPlayer),
    )

    fun arcBaseCountRange(hullSize: ShipAPI.HullSize?): IntRange? = when (hullSize) {
        ShipAPI.HullSize.FIGHTER -> 2..4
        ShipAPI.HullSize.FRIGATE -> 2..4
        ShipAPI.HullSize.DESTROYER -> 4..8
        ShipAPI.HullSize.CRUISER -> 8..16
        ShipAPI.HullSize.CAPITAL_SHIP -> 12..24
        else -> null
    }

    fun overloadAnchor(values: Values, hullSize: ShipAPI.HullSize?): Float? = when (hullSize) {
        ShipAPI.HullSize.FIGHTER -> values.overloadFrigate
        ShipAPI.HullSize.FRIGATE -> values.overloadFrigate
        ShipAPI.HullSize.DESTROYER -> values.overloadDestroyer
        ShipAPI.HullSize.CRUISER -> values.overloadCruiser
        ShipAPI.HullSize.CAPITAL_SHIP -> values.overloadCapital
        else -> null
    }

    const val FIGHTER_DAMAGE_MULT = 0.5f

    fun hardToSoftPerSecond(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.FIGHTER -> 0.0025f
        ShipAPI.HullSize.FRIGATE -> 0.01f
        ShipAPI.HullSize.DESTROYER -> 0.02f
        ShipAPI.HullSize.CRUISER -> 0.03f
        ShipAPI.HullSize.CAPITAL_SHIP -> 0.04f
        else -> 0f
    }

    fun hardToSoftRatio(hullSize: ShipAPI.HullSize?, overloadSeconds: Float): Float =
        hardToSoftPerSecond(hullSize) * overloadSeconds

    const val CHARGE_ARC_COUNT_MIN = 2
    const val CHARGE_ARC_COUNT_MAX = 8

    const val CHARGE_ARC_INTERVAL_MAX_SECONDS = 0.5f
    const val CHARGE_ARC_INTERVAL_MIN_SECONDS = 0.1f

    fun chargeArcCount(progress: Float): Int =
        (CHARGE_ARC_COUNT_MIN + (CHARGE_ARC_COUNT_MAX - CHARGE_ARC_COUNT_MIN) * progress.coerceIn(0f, 1f)).roundToInt()

    fun chargeArcInterval(progress: Float): Float =
        CHARGE_ARC_INTERVAL_MAX_SECONDS + (CHARGE_ARC_INTERVAL_MIN_SECONDS - CHARGE_ARC_INTERVAL_MAX_SECONDS) * progress.coerceIn(0f, 1f)

    fun targetCountMultiplier(targetCount: Int): Float =
        (1f - TARGET_COUNT_PENALTY_PER_EXTRA * (targetCount - 1).coerceAtLeast(0))
            .coerceAtLeast(1f - TARGET_COUNT_PENALTY_CAP)

    fun finalArcCount(baseCount: Int, targetCount: Int): Int =
        (baseCount * targetCountMultiplier(targetCount)).roundToInt().coerceAtLeast(1)

    fun chargeNorm(chargeSeconds: Float): Float =
        ((chargeSeconds - MIN_CHARGE_SECONDS) / (MAX_CHARGE_SECONDS - MIN_CHARGE_SECONDS)).coerceIn(0f, 1f)

    fun overloadDuration(anchor: Float, chargeNorm: Float): Float =
        anchor * (0.5f + 0.5f * chargeNorm)

    fun phaseLockoutActive(chargeSeconds: Float): Boolean = chargeSeconds < PHASE_LOCKOUT_SECONDS

    fun isInCone(angleDiffDeg: Float): Boolean = angleDiffDeg <= CONE_HALF_ANGLE_DEG
}
