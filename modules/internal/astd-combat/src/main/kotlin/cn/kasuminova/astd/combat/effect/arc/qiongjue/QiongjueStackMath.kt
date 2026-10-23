package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueStackMath.switchRetainStacks
import kotlin.math.floor
import kotlin.math.round

object QiongjueStackMath {

    internal const val STACK_EPS = 1e-4f

    fun maxStacks(armamentUpgrade: Boolean): Int =
        if (armamentUpgrade) QiongjuePhaseRailgunDifficulty.SIC_UPGRADE_MAX_STACKS
        else QiongjuePhaseRailgunDifficulty.MAX_STACKS

    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, owner: Int): Float =
        tuning.valueFor(entry, owner == 0)

    fun switchRetainStacks(stacks: Int, retainPct: Float): Int =
        floor(stacks.coerceAtLeast(0) * retainPct.coerceAtLeast(0f)).toInt()

    fun stacksAfterHit(
        stacks: Int,
        oldTargetValid: Boolean,
        sameTarget: Boolean,
        retainPct: Float,
        maxStacks: Int = QiongjuePhaseRailgunDifficulty.MAX_STACKS,
    ): Int {
        val base = if (oldTargetValid && !sameTarget) switchRetainStacks(stacks, retainPct) else stacks
        return (base + 1).coerceIn(0, maxStacks.coerceAtLeast(0))
    }

    fun mult(stacks: Int, perStack: Float): Float = 1f + stacks.coerceAtLeast(0) * perStack

    fun isDecayRateValid(decayRate: Float): Boolean = !decayRate.isNaN() && decayRate > 0f

    data class DecayStep(
        val stacks: Int,
        val pendingDecay: Float,
        val rateInvalid: Boolean,
    )

    fun decayAdvance(
        stacks: Int,
        pendingDecay: Float,
        secondsSinceLastHit: Float,
        amount: Float,
        decayRate: Float,
        windowSeconds: Float = QiongjuePhaseRailgunDifficulty.DECAY_WINDOW_SECONDS,
    ): DecayStep {
        if (!isDecayRateValid(decayRate)) return DecayStep(stacks.coerceAtLeast(0), 0f, rateInvalid = true)
        if (secondsSinceLastHit <= windowSeconds) return DecayStep(stacks.coerceAtLeast(0), 0f, rateInvalid = false)

        var s = stacks.coerceAtLeast(0)
        var pending = (pendingDecay + decayRate * amount).coerceAtMost(s + 1f)
        while (s > 0 && pending + STACK_EPS >= 1f) {
            s -= 1
            pending -= 1f
        }
        return DecayStep(s, pending.coerceAtLeast(0f), rateInvalid = false)
    }

    // 原版状态栏字体不渲染全角 ％，百分比必须用 ASCII %。
    fun formatPercent(value: Float): String {
        val rounded = round(value * 10f) / 10f
        return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
    }
}
