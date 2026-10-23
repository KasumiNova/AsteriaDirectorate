package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import com.fs.starfarer.api.combat.ShipAPI

object ASTDCombatIntelTuning {

    const val GRAV_PHASE_SYSTEM_ID = "astd_gravity_phase"
    const val ADAPTIVE_COILS_HULLMOD_ID = "adaptive_coils"

    // 原版 dynamic stat key，PhaseCloakStats.getDisruptionLevel 消费
    const val FLUX_THRESHOLD_MOD = "phase_cloak_flux_level_for_min_speed_mod"

    // 原版 dynamic stat key，PhaseCloakStats.getMaxTimeMult 消费
    const val PHASE_TIME_MULT_MOD = "phase_time_mult"

    // PhaseCloakStats.BASE_FLUX_LEVEL_FOR_MIN_SPEED
    const val VANILLA_FLUX_THRESHOLD_BASE = 0.5f

    // AdaptivePhaseCoils.FLUX_THRESHOLD_INCREASE_PERCENT
    const val ADAPTIVE_COILS_THRESHOLD_PERCENT = 50f

    const val GRAV_THRESHOLD_NO_COILS = 0.75f
    const val GRAV_THRESHOLD_WITH_COILS = 0.90f
    const val PHASE_TIME_FLOW_MULT = 0.75f

    const val SHIELD_DAMAGE_FLOOR = 10f
    const val ARMOR_DAMAGE_FLOOR = 5f

    const val STAT_SURFACE_ARMOR = "astd_combat_intel_surface_armor"
    const val STAT_FLAT_REDUCTION = "astd_combat_intel_flat_reduction"

    val PEAK_CR_BONUS = ScalingEntry(0.10f, 0.20f, 0.50f)
    val SURFACE_ARMOR_FRIGATE = ScalingEntry(500f, 625f, 1000f)
    val SURFACE_ARMOR_DESTROYER = ScalingEntry(1000f, 1250f, 2000f)
    val SURFACE_ARMOR_CRUISER = ScalingEntry(1500f, 1875f, 3000f)
    val SURFACE_ARMOR_CAPITAL = ScalingEntry(2000f, 2500f, 4000f)
    val SURFACE_ARMOR_DECAY = ScalingEntry(1.5f, 2f, 3.5f)
    val SHIELD_FLAT_REDUCTION = ScalingEntry(5f, 10f, 25f)
    val ARMOR_FLAT_REDUCTION = ScalingEntry(10f, 20f, 50f)
    val ELITE_RANGE_THRESHOLD = ScalingEntry(700f, 800f, 1100f)
    val ELITE_RANGE_BONUS = ScalingEntry(100f, 200f, 500f)
    val ELITE_FLUX_REDUCTION = ScalingEntry(0.05f, 0.10f, 0.25f)

    data class Values(
        val peakCrBonus: Float,
        val surfaceArmorFrigate: Float,
        val surfaceArmorDestroyer: Float,
        val surfaceArmorCruiser: Float,
        val surfaceArmorCapital: Float,
        val surfaceArmorDecay: Float,
        val shieldFlatReduction: Float,
        val armorFlatReduction: Float,
        val eliteRangeThreshold: Float,
        val eliteRangeBonus: Float,
        val eliteFluxReduction: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        peakCrBonus = tuning.valueFor(PEAK_CR_BONUS, isPlayer),
        surfaceArmorFrigate = tuning.valueFor(SURFACE_ARMOR_FRIGATE, isPlayer),
        surfaceArmorDestroyer = tuning.valueFor(SURFACE_ARMOR_DESTROYER, isPlayer),
        surfaceArmorCruiser = tuning.valueFor(SURFACE_ARMOR_CRUISER, isPlayer),
        surfaceArmorCapital = tuning.valueFor(SURFACE_ARMOR_CAPITAL, isPlayer),
        surfaceArmorDecay = tuning.valueFor(SURFACE_ARMOR_DECAY, isPlayer),
        shieldFlatReduction = tuning.valueFor(SHIELD_FLAT_REDUCTION, isPlayer),
        armorFlatReduction = tuning.valueFor(ARMOR_FLAT_REDUCTION, isPlayer),
        eliteRangeThreshold = tuning.valueFor(ELITE_RANGE_THRESHOLD, isPlayer),
        eliteRangeBonus = tuning.valueFor(ELITE_RANGE_BONUS, isPlayer),
        eliteFluxReduction = tuning.valueFor(ELITE_FLUX_REDUCTION, isPlayer),
    )

    enum class DefenseBranch { PHASE, SHIELDED, UNSHIELDED }

    fun defenseBranch(isPhase: Boolean, hasShield: Boolean): DefenseBranch = when {
        isPhase -> DefenseBranch.PHASE
        hasShield -> DefenseBranch.SHIELDED
        else -> DefenseBranch.UNSHIELDED
    }

    fun surfaceArmorFor(values: Values, hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> values.surfaceArmorCapital
        ShipAPI.HullSize.CRUISER -> values.surfaceArmorCruiser
        ShipAPI.HullSize.DESTROYER -> values.surfaceArmorDestroyer
        else -> values.surfaceArmorFrigate
    }

    fun surfaceArmorAt(initial: Float, decay: Float, elapsed: Float): Float {
        if (initial <= 0f || decay <= 0f) return 0f
        if (elapsed < 0f || elapsed >= decay) return 0f
        return initial * (1f - elapsed / decay)
    }

    fun bonusArmorDamageRatio(
        hitStrength: Float,
        armor: Float,
        minArmor: Float,
        effectiveArmorMult: Float,
        maxArmorDamageReduction: Float,
        bonusArmor: Float,
    ): Float {
        if (bonusArmor <= 0f) return 1f
        val base = ASTDArmorDamageReduction.compute(
            damageAmount = 1f,
            hitStrength = hitStrength,
            armorValue = armor,
            minArmorValue = minArmor,
            effectiveArmorMult = effectiveArmorMult,
            maxArmorDamageReduction = maxArmorDamageReduction,
        ).damageMultiplier
        if (base <= 1e-6f) return 1f
        val boosted = ASTDArmorDamageReduction.compute(
            damageAmount = 1f,
            hitStrength = hitStrength,
            armorValue = armor + bonusArmor,
            minArmorValue = minArmor,
            effectiveArmorMult = effectiveArmorMult,
            maxArmorDamageReduction = maxArmorDamageReduction,
        ).damageMultiplier
        return (boosted / base).coerceIn(0f, 1f)
    }

    fun applyFlatReduction(damage: Float, reduction: Float, floor: Float): Float {
        val d = damage.coerceAtLeast(0f)
        return maxOf(d - reduction.coerceAtLeast(0f), minOf(d, floor))
    }

    fun eliteRangeFlatBonus(baseRange: Float, threshold: Float, maxBonus: Float): Float =
        if (baseRange >= threshold) 0f else minOf(maxBonus.coerceAtLeast(0f), threshold - baseRange)

    // modifyPercent 以原版基准 0.5 反推百分比，并扣除线圈自带 +50%（同一百分比区叠加）
    fun fluxThresholdPercent(hasAdaptiveCoils: Boolean): Float {
        val target = if (hasAdaptiveCoils) GRAV_THRESHOLD_WITH_COILS else GRAV_THRESHOLD_NO_COILS
        val others = if (hasAdaptiveCoils) ADAPTIVE_COILS_THRESHOLD_PERCENT else 0f
        return (target / VANILLA_FLUX_THRESHOLD_BASE - 1f) * 100f - others
    }
}
