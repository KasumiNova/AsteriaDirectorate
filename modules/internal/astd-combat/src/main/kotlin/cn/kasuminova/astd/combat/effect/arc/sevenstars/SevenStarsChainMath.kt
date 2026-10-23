package cn.kasuminova.astd.combat.effect.arc.sevenstars

import com.fs.starfarer.api.Global

object SevenStarsChainMath {
    private val log = Global.getLogger(SevenStarsChainMath::class.java)

    enum class ChainStep {
        JUMP,

        TERMINAL,
    }

    fun flashMult(tuning: SevenStarsDifficulty.SevenStarsTuning, hitIndex: Int): Float {
        val index = if (hitIndex < 1) {
            log.warn("“七星”flashMult hitIndex=$hitIndex 非法（应 >= 1），clamp 到 1")
            1
        } else {
            hitIndex
        }
        val bonus = ((index - 1) * tuning.perJumpBonus).coerceAtMost(tuning.bonusCap)
        return tuning.firstHitMult * (1f + bonus)
    }

    fun terminalDamageFractions(multi: Boolean, jumps: Int): List<Float> {
        if (!multi) return listOf(SevenStarsDifficulty.TERMINAL_BASE_FRACTION)
        val segments = jumps.coerceAtLeast(1)
        return (0 until segments).map { i ->
            (SevenStarsDifficulty.TERMINAL_BASE_FRACTION + SevenStarsDifficulty.TERMINAL_STEP_FRACTION * i)
                .coerceAtMost(SevenStarsDifficulty.TERMINAL_MAX_FRACTION)
        }
    }

    fun jumpRange(weaponRange: Float): Float {
        if (weaponRange.isNaN() || weaponRange <= 0f) {
            log.warn("“七星”武器射程非法（$weaponRange），折跃范围按 0 处理：候选恒空，首发直接进终结判定")
            return 0f
        }
        return weaponRange * SevenStarsDifficulty.JUMP_RANGE_MULT
    }

    fun nextChainStep(jumps: Int, hasPdCandidates: Boolean): ChainStep = when {
        jumps >= SevenStarsDifficulty.MAX_JUMPS -> ChainStep.TERMINAL
        !hasPdCandidates -> ChainStep.TERMINAL
        else -> ChainStep.JUMP
    }
}
