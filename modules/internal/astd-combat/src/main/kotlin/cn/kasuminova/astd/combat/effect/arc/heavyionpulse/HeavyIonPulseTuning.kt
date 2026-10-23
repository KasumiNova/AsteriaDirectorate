package cn.kasuminova.astd.combat.effect.arc.heavyionpulse

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingTable

object HeavyIonPulseTuning {

    val DISCHARGE_CHANCE = ScalingTable(0.25f, 0.50f, 0.667f, 0.833f, 1.00f)

    val DISCHARGE_EMP_MULT = ScalingTable(0.75f, 1.00f, 1.25f, 1.50f, 2.00f)

    val EMP_RESIST_PER_STACK = ScalingEntry(0.04f, 0.08f, 0.20f)

    const val RESIST_MAX_STACKS = 20

    const val ENERGY_VULN_DIVISOR = 5f

    fun splitEmpShift(shift: Float, armamentUpgrade: Boolean): Pair<Float, Float> {
        val s = shift.coerceAtLeast(0f)
        if (!armamentUpgrade) return s to 0f
        val empShift = minOf(s, 1f)
        val energyVuln = (s - 1f).coerceAtLeast(0f) / ENERGY_VULN_DIVISOR
        return empShift to energyVuln
    }

    const val RESIST_DECAY_PER_SECOND = 0.5f

    const val PIERCE_FLOOR = 0.1f

    const val PIERCE_COMPENSATION_FLOOR = 0.01f

    const val PIERCE_MIN_SCALE = 5f

    data class Values(
        val dischargeChance: Float,
        val dischargeEmpMult: Float,
        val empResistPerStack: Float,
        val isPlayer: Boolean,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        dischargeChance = tuning.valueFor(DISCHARGE_CHANCE, isPlayer),
        dischargeEmpMult = tuning.valueFor(DISCHARGE_EMP_MULT, isPlayer),
        empResistPerStack = tuning.valueFor(EMP_RESIST_PER_STACK, isPlayer),
        isPlayer = isPlayer,
    )

    fun pierceActive(isPlayer: Boolean, fixedScale: Float): Boolean = !isPlayer && fixedScale >= PIERCE_MIN_SCALE

    fun shouldDischarge(roll: Float, chance: Float): Boolean = roll < chance

    fun empPierceExtra(emp: Float, mult: Float): Float =
        if (mult >= PIERCE_FLOOR) 0f else emp * (PIERCE_FLOOR - mult) / PIERCE_FLOOR

    // applyDamage 会再次乘目标 empDamageTakenMult，补偿量必须先除以该乘区。
    fun empPierceApplied(extra: Float, mult: Float): Float =
        if (mult <= 0f) 0f else extra / maxOf(mult, PIERCE_COMPENSATION_FLOOR)
}
