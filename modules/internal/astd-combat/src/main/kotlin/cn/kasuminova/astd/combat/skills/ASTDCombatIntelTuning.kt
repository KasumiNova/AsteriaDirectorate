package cn.kasuminova.astd.combat.skills

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArmorDamageReduction
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 菀星战斗智能（astd_combat_intel，docs/design/skills/00-ai-cores.md）的机制数值声明与纯函数。
 *
 * 三锚点查值集中在此声明，供技能效果在战斗中实时解析（LunaLib 设置变更即时生效），
 * 并由单元测试直接驱动。玩家来源（owner == 0）按我方档位取值（默认砺刃 v2），
 * 其余阵营按轨一 k_s 取值（DifficultyTuning.valueFor 口径）。
 *
 * 数值面：
 * - 相位舰船：峰值时间 +10%/20%/50%；上浮后按舰级获得快速衰减的额外装甲减伤值
 *   （护卫/驱逐/巡洋/主力 v1 500/1000/1500/2000 → v5 1000/2000/3000/4000，衰减 1.5/2/3.5 秒）；
 *   引力相位额外：线圈失速阈值 0.5 → 0.75（装自适应相位线圈 0.90），相位时流 ×0.75。
 * - 非相位舰船：护盾单次固定减免 5/10/25（下限 10）；无盾时装甲单次固定减免 10/20/50（下限 5）。
 * - 精英：能量武器基础射程低于 700/800/1100 时按至多 100/200/500 补足（不超过阈值）；
 *   能量武器辐能产出 -5%/10%/25%。
 */
object ASTDCombatIntelTuning {

    /** 引力相位系统 id（astd_gravity_phase.system）。 */
    const val GRAV_PHASE_SYSTEM_ID = "astd_gravity_phase"

    /** 自适应相位线圈船插 id（原版 adaptive_coils）。 */
    const val ADAPTIVE_COILS_HULLMOD_ID = "adaptive_coils"

    /** 原版相位线圈失速阈值的 dynamic stat key（PhaseCloakStats.getDisruptionLevel 消费）。 */
    const val FLUX_THRESHOLD_MOD = "phase_cloak_flux_level_for_min_speed_mod"

    /** 原版相位时间流速的 dynamic stat key（PhaseCloakStats.getMaxTimeMult 消费）。 */
    const val PHASE_TIME_MULT_MOD = "phase_time_mult"

    /** 原版失速阈值基准（PhaseCloakStats.BASE_FLUX_LEVEL_FOR_MIN_SPEED）。 */
    const val VANILLA_FLUX_THRESHOLD_BASE = 0.5f

    /** 原版自适应相位线圈对失速阈值的百分比提升（AdaptivePhaseCoils.FLUX_THRESHOLD_INCREASE_PERCENT）。 */
    const val ADAPTIVE_COILS_THRESHOLD_PERCENT = 50f

    /** 技能目标失速阈值：未装线圈 0.75 / 装线圈 0.90（00-ai-cores.md）。 */
    const val GRAV_THRESHOLD_NO_COILS = 0.75f
    const val GRAV_THRESHOLD_WITH_COILS = 0.90f

    /** 引力相位分支的相位时流乘区（额外时间流速 -25% → phase_time_mult ×0.75）。 */
    const val PHASE_TIME_FLOW_MULT = 0.75f

    /** 护盾单次固定减免的最终伤害下限。 */
    const val SHIELD_DAMAGE_FLOOR = 10f

    /** 装甲单次固定减免的最终伤害下限。 */
    const val ARMOR_DAMAGE_FLOOR = 5f

    /** 相位上浮减伤的 dmg.modifier key（DamageTakenModifier 写入）。 */
    const val STAT_SURFACE_ARMOR = "astd_combat_intel_surface_armor"

    /** 单次固定减免（护盾/装甲）的 dmg.modifier key。 */
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

    /** 一次结算所需的全部机制数值（难度解析结果；最终口径，直接可用）。 */
    data class Values(
        /** 峰值时间加成（乘区增量，0.10 = +10%）。 */
        val peakCrBonus: Float,
        /** 相位上浮额外装甲减伤初始值（按舰级分档）。 */
        val surfaceArmorFrigate: Float,
        val surfaceArmorDestroyer: Float,
        val surfaceArmorCruiser: Float,
        val surfaceArmorCapital: Float,
        /** 相位上浮减伤的衰减窗口时长（秒）。 */
        val surfaceArmorDecay: Float,
        /** 护盾单次固定减免值。 */
        val shieldFlatReduction: Float,
        /** 装甲单次固定减免值。 */
        val armorFlatReduction: Float,
        /** 精英：能量武器射程补足阈值。 */
        val eliteRangeThreshold: Float,
        /** 精英：能量武器射程补足上限。 */
        val eliteRangeBonus: Float,
        /** 精英：能量武器辐能产出削减（0.05 = -5%）。 */
        val eliteFluxReduction: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位映射，其余阵营按轨一 k_s 映射。 */
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

    /** 防御效果分派：相位舰船走上浮减伤分支（不吃固定减免），其余按有无护盾分护盾/装甲分支。 */
    enum class DefenseBranch { PHASE, SHIELDED, UNSHIELDED }

    fun defenseBranch(isPhase: Boolean, hasShield: Boolean): DefenseBranch = when {
        isPhase -> DefenseBranch.PHASE
        hasShield -> DefenseBranch.SHIELDED
        else -> DefenseBranch.UNSHIELDED
    }

    /** 相位上浮额外装甲减伤初始值按舰级查值（非标准档位按护卫舰处理）。 */
    fun surfaceArmorFor(values: Values, hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> values.surfaceArmorCapital
        ShipAPI.HullSize.CRUISER -> values.surfaceArmorCruiser
        ShipAPI.HullSize.DESTROYER -> values.surfaceArmorDestroyer
        else -> values.surfaceArmorFrigate
    }

    /** 上浮减伤的线性衰减：窗口内 initial → 0，窗口外恒 0（elapsed/decay 非正按 0 处理）。 */
    fun surfaceArmorAt(initial: Float, decay: Float, elapsed: Float): Float {
        if (initial <= 0f || decay <= 0f) return 0f
        if (elapsed < 0f || elapsed >= decay) return 0f
        return initial * (1f - elapsed / decay)
    }

    /**
     * 额外装甲减伤值的伤害乘区：以 ASTDArmorDamageReduction 的装甲公式分别计算
     * 「装甲 A」与「装甲 A + bonus」的伤害系数，取比值——无论监听器拿到的伤害在装甲
     * 结算前后，乘上该比值都恰好等价于按 A + bonus 结算（乘法交换律），不与原版装甲重复计数。
     */
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

    /**
     * 单次固定减免：damage - reduction，但不把最终伤害压到 floor 以下；
     * 已低于 floor 的伤害不受减免影响（也不会被抬升到 floor）。
     */
    fun applyFlatReduction(damage: Float, reduction: Float, floor: Float): Float {
        val d = damage.coerceAtLeast(0f)
        return maxOf(d - reduction.coerceAtLeast(0f), minOf(d, floor))
    }

    /** 精英射程补足：基础射程已达阈值不补，否则补 min(上限, 阈值 - 基础射程)。 */
    fun eliteRangeFlatBonus(baseRange: Float, threshold: Float, maxBonus: Float): Float =
        if (baseRange >= threshold) 0f else minOf(maxBonus.coerceAtLeast(0f), threshold - baseRange)

    /**
     * 引力相位失速阈值写入量（modifyPercent 口径）：以原版基准 0.5 反推使
     * computeEffective 终值命中目标的百分比，并扣除自适应相位线圈自带的 +50%
     * （线圈 modifyPercent 与本技能 modifyPercent 在同一百分比区叠加）。
     */
    fun fluxThresholdPercent(hasAdaptiveCoils: Boolean): Float {
        val target = if (hasAdaptiveCoils) GRAV_THRESHOLD_WITH_COILS else GRAV_THRESHOLD_NO_COILS
        val others = if (hasAdaptiveCoils) ADAPTIVE_COILS_THRESHOLD_PERCENT else 0f
        return (target / VANILLA_FLUX_THRESHOLD_BASE - 1f) * 100f - others
    }
}
