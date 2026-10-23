package cn.kasuminova.astd.combat.effect.arc.cuifeng

import com.fs.starfarer.api.Global

object CuifengTorpedoMath {
    private val log = Global.getLogger(CuifengTorpedoMath::class.java)

    @Volatile
    private var warnedBadRange = false

    fun speedFactor(progress: Float): Float {
        if (progress.isNaN()) return CuifengTorpedoDifficulty.SLOW_SPEED_FACTOR
        return when {
            progress < CuifengTorpedoDifficulty.SLOW_PHASE_END -> CuifengTorpedoDifficulty.SLOW_SPEED_FACTOR
            progress < CuifengTorpedoDifficulty.RAMP_PHASE_END ->
                CuifengTorpedoDifficulty.SLOW_SPEED_FACTOR +
                        (progress - CuifengTorpedoDifficulty.SLOW_PHASE_END) /
                        (CuifengTorpedoDifficulty.RAMP_PHASE_END - CuifengTorpedoDifficulty.SLOW_PHASE_END) *
                        (1f - CuifengTorpedoDifficulty.SLOW_SPEED_FACTOR)
            else -> 1f
        }
    }

    fun progressOf(traveled: Float, maxRange: Float): Float {
        if (maxRange <= 0f || maxRange.isNaN()) {
            if (!warnedBadRange) {
                warnedBadRange = true
                log.warn("摧锋鱼雷 maxRange 异常（$maxRange），二段式速度曲线失效按满速处理（后续同类异常不再重复告警）")
            }
            return 1f
        }
        return (traveled / maxRange).coerceAtLeast(0f)
    }

    fun fluxAdaptiveFactor(fluxLevel: Float): Float {
        if (fluxLevel.isNaN()) return 0f
        return ((fluxLevel - 0.40f) / (0.90f - 0.40f)).coerceIn(0f, 1f)
    }

    fun fluxAdaptiveBonus(panel: Float, x: Float, fluxLevel: Float): Float =
        panel * x * fluxAdaptiveFactor(fluxLevel)

    fun hullAdaptiveBonus(panel: Float, sizePct: Float, deployPoints: Float, baseline: Float, dpPct: Float): Float {
        val dpDiff = (deployPoints - baseline).coerceAtLeast(0f)
        return panel * (sizePct + dpDiff * dpPct)
    }
}
