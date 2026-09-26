package cn.kasuminova.astd.combat.effect.arc.cuifeng

import com.fs.starfarer.api.Global

/**
 * 摧锋鱼雷命中结算与导弹 AI 的纯计算面（blue/30-superlative.md §机制）：
 * 二段式速度曲线 / 辐能自适应系数 / 舰体自适应增伤的全部数值推导，不触碰战斗引擎，
 * 供单测完整驱动。
 *
 * 0 值防线：
 * - [speedFactor] maxRange ≤ 0 或 NaN 时返回满速 1.0 并一次性 WARN（进程级去重；
 *   数据异常时宁失隐性机制不失追踪能力）；
 * - [fluxAdaptiveFactor] 入参越界一律 clamp 到 [0,1]；
 * - [hullAdaptiveBonus] 部署点差值只取正向（低于同级基准不扣减，设计案「差值增伤」语义）。
 */
object CuifengTorpedoMath {
    private val log = Global.getLogger(CuifengTorpedoMath::class.java)

    @Volatile
    private var warnedBadRange = false

    /**
     * 二段式速度系数（[progress] = 已飞航程 / 最大射程）：
     * [0, 0.25) → 0.5；[0.25, 0.5) → 0.5 线性升至 1.0；[0.5, ∞) → 1.0。
     */
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

    /** 已飞航程进度（已飞距离 / 最大射程）；maxRange 非法时按 1.0（满速段）处理并一次性 WARN。 */
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

    /**
     * 辐能自适应系数：目标辐能水平（currFlux/maxFlux，[0,1]）40% 起算、90% 满值线性。
     * 返回 [0,1] 的进度系数，乘难度倍率 x 与面板得增伤。
     */
    fun fluxAdaptiveFactor(fluxLevel: Float): Float {
        if (fluxLevel.isNaN()) return 0f
        return ((fluxLevel - 0.40f) / (0.90f - 0.40f)).coerceIn(0f, 1f)
    }

    /** 辐能自适应增伤（能量）：面板 × x × [fluxAdaptiveFactor]。 */
    fun fluxAdaptiveBonus(panel: Float, x: Float, fluxLevel: Float): Float =
        panel * x * fluxAdaptiveFactor(fluxLevel)

    /**
     * 舰体自适应增伤（能量）：面板 ×（舰体等级档位 pct + 正向部署点差值 × 每点 pct）。
     * [deployPoints] 为舰船基础部署点（不受减免影响）；[baseline] 为同级基准（5/10/20/40）；
     * 模块舰由调用方先取主舰体部署点再对结果减半（不在本函数内判定）。
     */
    fun hullAdaptiveBonus(panel: Float, sizePct: Float, deployPoints: Float, baseline: Float, dpPct: Float): Float {
        val dpDiff = (deployPoints - baseline).coerceAtLeast(0f)
        return panel * (sizePct + dpDiff * dpPct)
    }
}
