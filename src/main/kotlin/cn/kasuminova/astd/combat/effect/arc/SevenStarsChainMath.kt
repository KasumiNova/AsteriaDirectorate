package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global

/**
 * “七星”折跃发射器连跳状态机的纯计算核（规格 07 §2.2）：
 * 倍率链、终结段数表、折跃范围、闪光爆炸后决策——全部纯函数，单测直接调用，不触引擎。
 *
 * 动机：机制数值的唯一真相源。OnFire/ChainScript 只负责引擎交互与状态推进，
 * 任何「第 N 跳倍率多少 / 终结分几段 / 本跳后走哪条支路」的判定都收敛到本对象，
 * 防止脚本层散落重复公式。
 */
object SevenStarsChainMath {
    private val log = Global.getLogger(SevenStarsChainMath::class.java)

    /** 闪光爆炸后状态机支路（规格 §2.2 状态机）。 */
    enum class ChainDecision {
        /** 本轮摧毁 ≥1 且未达上限且仍有候选：0.33s 冷却后续跳。 */
        CONTINUE,

        /** 折跃次数达上限或无 PD 候选：进入对舰终结判定。 */
        TERMINAL,

        /** 本轮摧毁 = 0（安全闸：连跳必须击杀才能续段）：射弹消散，不触发终结。 */
        DISSIPATE,
    }

    /**
     * 第 [hitIndex] 跳（1 起）的闪光爆炸倍率 = 首发倍率 × (1 + min((hitIndex-1) × 每跳提升, 累计上限))。
     *
     * [hitIndex] < 1 属调用错误，clamp 到 1 并记 WARN（不静默产出错误倍率）。
     * 链式计算无除法：[bonusCap] 退化为 0 时 min 天然 clamp 到 0，倍率恒等于首发倍率（规格 §2.4）。
     */
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

    /**
     * 对舰终结段数与逐段伤害表（面板倍率）：
     * - 单段（玩家恒此）：[0.5]；
     * - v5 多段：max(1, [jumps]) 段，第 i 段（0 起）= min(0.5 + 0.25×i, 2.0)；
     *   段数 = jumps 不截断（jumps 越界输入时第 8 段起 clamp 在 2.0，防御性语义，规格 §4.1 用例 4）。
     */
    fun terminalDamageFractions(multi: Boolean, jumps: Int): List<Float> {
        if (!multi) return listOf(SevenStarsDifficulty.TERMINAL_BASE_FRACTION)
        val segments = jumps.coerceAtLeast(1)
        return (0 until segments).map { i ->
            (SevenStarsDifficulty.TERMINAL_BASE_FRACTION + SevenStarsDifficulty.TERMINAL_STEP_FRACTION * i)
                .coerceAtMost(SevenStarsDifficulty.TERMINAL_MAX_FRACTION)
        }
    }

    /**
     * 折跃范围（su）= 最终武器射程 × [SevenStarsDifficulty.JUMP_RANGE_MULT]（吃射程修正）。
     *
     * 0 值防线（规格 §2.4）：[weaponRange] <= 0（异常装配/hullmod 归零）记 WARN 并返回 0——
     * 候选恒空 → 首发直接进终结判定；不静默产出恒零折跃（距离比较用平方，无除零路径）。
     */
    fun jumpRange(weaponRange: Float): Float {
        if (weaponRange.isNaN() || weaponRange <= 0f) {
            log.warn("“七星”武器射程非法（$weaponRange），折跃范围按 0 处理：候选恒空，首发直接进终结判定")
            return 0f
        }
        return weaponRange * SevenStarsDifficulty.JUMP_RANGE_MULT
    }

    /**
     * 闪光爆炸后决策（规格 §2.2 状态机支路 + §2.4 边界矩阵）：
     * 1. [kills] = 0 → DISSIPATE（安全闸最优先，jumps 任意：未击杀断链不触发终结，首发空爆同路径）；
     * 2. [jumps] >= 最大折跃次数 → TERMINAL；
     * 3. 无 PD 候选（[hasPdCandidates] = false）→ TERMINAL；
     * 4. 其余 → CONTINUE。
     */
    fun decideAfterFlash(kills: Int, jumps: Int, hasPdCandidates: Boolean): ChainDecision = when {
        kills <= 0 -> ChainDecision.DISSIPATE
        jumps >= SevenStarsDifficulty.MAX_JUMPS -> ChainDecision.TERMINAL
        !hasPdCandidates -> ChainDecision.TERMINAL
        else -> ChainDecision.CONTINUE
    }
}
