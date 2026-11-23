package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingTable

object StarfallEchoTuning {

    const val BURST_SIZE = 5

    const val RESONANCE_MAX_STACKS = 8
    const val RESONANCE_DECAY_INTERVAL = 2f

    const val EXPLOSION_BASE_RADIUS = 150f

    const val FINAL_STACK_DAMAGE_BONUS = 0.5f

    const val FINAL_DAMAGE_MULT = 2f

    const val FINAL_SIZE_MULT = 2f

    const val FINAL_FLUX_EXTRA = 2250f

    const val AMMO_GATE = BURST_SIZE

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

    // 原版先递增已发弹数，再同步调用 onFire，返回后才结束齐射；此处余时等于剩余发数乘连射间隔。
    fun isFinalShot(inBurst: Boolean, burstTimeRemaining: Float): Boolean = inBurst && burstTimeRemaining == 0f
}
