package cn.kasuminova.astd.combat.effect.arc.eda

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorDifficulty.EXTRA_APPLY_THRESHOLD
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorDifficulty.FLUX_FULL_THRESHOLD
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorDifficulty.FLUX_ZERO_THRESHOLD
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorDifficulty.fluxDecayFactor
import cn.kasuminova.astd.combat.effect.arc.eda.ElectricDriveAcceleratorDifficulty.rangeBonusBase
import cn.kasuminova.astd.impl.combat.CombatRandom

object ElectricDriveAcceleratorDifficulty {

    val CHARGE_MAX_PCT = ScalingEntry(25f, 50f, 125f, ScalingMap.LINEAR)

    private val RANGE_BONUS_MAP = ScalingMap { k, v1, v2, v5 ->
        val mid = (v2 + v5) / 2f
        when {
            k <= 2f -> v1 + (v2 - v1) * (k - 1f).coerceIn(0f, 1f)
            k <= 3f -> v2 + (mid - v2) * (k - 2f)
            else -> mid + (v5 - mid) * ((k - 3f) / 2f).coerceIn(0f, 1f)
        }
    }

    val RANGE_BONUS_SU = ScalingEntry(100f, 200f, 400f, RANGE_BONUS_MAP)

    const val FLUX_FULL_THRESHOLD = 0.2f
    const val FLUX_ZERO_THRESHOLD = 0.4f

    const val EXTRA_APPLY_THRESHOLD = 1f

    fun chargeMaxPct(tuning: DifficultyTuning, owner: Int): Float =
        tuning.valueFor(CHARGE_MAX_PCT, owner == 0)

    fun rangeBonusBase(tuning: DifficultyTuning, owner: Int): Float =
        tuning.valueFor(RANGE_BONUS_SU, owner == 0)

    fun fluxDecayFactor(level: Float): Float {
        if (level.isNaN()) return 0f
        val clamped = level.coerceIn(0f, 1f)
        if (clamped <= FLUX_FULL_THRESHOLD) return 1f
        if (clamped >= FLUX_ZERO_THRESHOLD) return 0f
        return 1f - (clamped - FLUX_FULL_THRESHOLD) / (FLUX_ZERO_THRESHOLD - FLUX_FULL_THRESHOLD)
    }

    fun rangeBonus(tuning: DifficultyTuning, owner: Int, level: Float): Float =
        rangeBonusBase(tuning, owner) * fluxDecayFactor(level)

    fun extraDamage(baseDamage: Float, rollPct: Float): Float = baseDamage * rollPct / 100f

    fun chargeRollPct(maxPct: Float, armamentUpgrade: Boolean, roll: () -> Float): Float =
        if (armamentUpgrade) maxPct else roll()

    fun shouldApplyExtra(extra: Float): Boolean = extra >= EXTRA_APPLY_THRESHOLD

    fun seedOf(shipId: String, slotId: String): Long = CombatRandom.seedOf(shipId, slotId)
}
