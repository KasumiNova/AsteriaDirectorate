package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingTable

object StarfallEchoTuning {

    const val BURST_SIZE = 5

    const val RESONANCE_MAX_STACKS = 4

    const val EXPLOSION_BASE_RADIUS = 150f

    const val FINAL_STACK_DAMAGE_BONUS = 0.5f

    const val FINAL_DAMAGE_MULT = 2f

    const val FINAL_SIZE_MULT = 2f

    const val FINAL_FLUX_EXTRA = 2250f

    const val AMMO_GATE = 5

    const val BURST_RESET_SECONDS = 0.4f

    val RESONANCE_VULN_PER_STACK = ScalingTable(0.05f, 0.10f, 0.125f, 0.15f, 0.20f)

    val EXPLOSION_DAMAGE_MULT = ScalingTable(0.75f, 1.00f, 1.25f, 1.50f, 2.00f)

    data class Values(
        val vulnPerStack: Float,
        val explosionDamageMult: Float,
        val isPlayer: Boolean,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        vulnPerStack = tuning.valueFor(RESONANCE_VULN_PER_STACK, isPlayer),
        explosionDamageMult = tuning.valueFor(EXPLOSION_DAMAGE_MULT, isPlayer),
        isPlayer = isPlayer,
    )

    fun explosionRadius(stacks: Int): Float = EXPLOSION_BASE_RADIUS * (stacks.coerceAtLeast(0) + 1)

    fun finalShotDamage(finalDamage: Float, stacks: Int): Float =
        finalDamage * (1f + FINAL_STACK_DAMAGE_BONUS * stacks.coerceAtLeast(0))

    fun finalShotBonusDamage(finalDamage: Float, stacks: Int): Float =
        finalDamage * FINAL_STACK_DAMAGE_BONUS * stacks.coerceAtLeast(0)

    fun explosionDamage(finalDamage: Float, stacks: Int, mult: Float): Float =
        finalShotDamage(finalDamage, stacks) * mult

    fun canFire(ammo: Int, inBurst: Boolean): Boolean = inBurst || ammo >= AMMO_GATE

    fun nextBurstOrdinal(prevOrdinal: Int, lastFireTime: Float, now: Float): Int =
        if (now - lastFireTime > BURST_RESET_SECONDS) 1 else prevOrdinal + 1
}
