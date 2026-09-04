package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import kotlin.math.floor
import kotlin.math.round

/**
 * “穷距”相位轨道炮持续演算的纯逻辑核心（规格 05 §2.1）：不依赖 Starsector API，
 * 叠层/折算/衰减/倍率/难度取值全部在此定义唯一语义，供 [QiongjueCalcStacks] 与
 * [QiongjuePhaseRailgunOnHitEffect] 调用，并由单元测试完整驱动。
 *
 * 0 值防线分工：纯函数只定返回值语义（不产生日志）；WARN 由调用侧按实例去重承担
 * （对齐 ChargeNeedleTuning 先例）。
 */
object QiongjueStackMath {

    /** 衰减累加的浮点容差（速率×时间在边界上常差 1e-7 量级；对齐基建 ReferenceStackableBuff.STACK_EPS 先例）。 */
    internal const val STACK_EPS = 1e-4f

    /**
     * 难度取值唯一入口：玩家来源（owner == 0）固定 v2，否则按轨一 k_s 三锚点映射。
     * 每次命中/每帧调用（不缓存），LunaLib 设置变更即时生效。
     */
    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, owner: Int): Float =
        if (owner == 0) entry.v2 else tuning.value(entry)

    /**
     * 异目标折算保留层数：`floor(stacks × retainPct)`。
     * [retainPct] ≤ 0（极端自定义 k_s）时保留 0 层——合法，无除零、无负层（规格 05 §2.4）。
     */
    fun switchRetainStacks(stacks: Int, retainPct: Float): Int =
        floor(stacks.coerceAtLeast(0) * retainPct.coerceAtLeast(0f)).toInt()

    /**
     * 命中后层数（唯一叠层语义入口，规格 05 §2.2 结算顺序）：
     * - 旧目标失效（hulk/离场，`oldTargetValid=false`）：视为无旧目标，**不折算**（规格裁定：
     *   打死目标后转火属正常行为，不吃切换惩罚）；
     * - 同目标：不折算；
     * - 异目标：先按 [switchRetainStacks] 折算；
     * 三分支之后本次命中仍计 +1 层（规格裁定），clamp 到 [maxStacks]。
     */
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

    /**
     * 伤害/射速乘区正算：`1 + stacks × perStack`（stacks=0 → 1.0）。
     * 乘区直接乘，不涉及从终值反推修正量，无除零点（规格 05 §2.4）。
     */
    fun mult(stacks: Int, perStack: Float): Float = 1f + stacks.coerceAtLeast(0) * perStack

    /**
     * 衰减速率有效性：[decayRate] ≤ 0 或 NaN 属配置异常——调用侧据此 WARN 一次并跳过衰减
     * （层数不衰减继续运行，配置异常必须可见，禁止静默恒零）。
     */
    fun isDecayRateValid(decayRate: Float): Boolean = !decayRate.isNaN() && decayRate > 0f

    /** 一次窗口衰减推进的结果（纯值语义）。 */
    data class DecayStep(
        /** 推进后的层数。 */
        val stacks: Int,
        /** 推进后的亚层衰减累加器。 */
        val pendingDecay: Float,
        /** 本次推进衰减速率非法（调用侧 WARN 一次）。 */
        val rateInvalid: Boolean,
    )

    /**
     * 窗口衰减推进（规格 05 §2.2 状态机第 3 步）：
     * - [secondsSinceLastHit] ≤ [windowSeconds]（恰在窗口边界含端）：不衰减，累加器清零；
     * - 越过窗口：累加器按 `decayRate × amount` 累积，每满 1 扣 1 层，扣到 0 即止；
     * - 累加器 clamp 到 `stacks + 1`（长时间挂机恢复后不会一次性狂扣，帧率抖动下扣层速率不失真）。
     */
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

    /**
     * HUD 百分比显示格式（ASCII %，对齐 01 验收判例：全角 ％ 原版状态栏字体不渲染）：
     * 整数去小数点，否则保留 1 位（如 62.5）。
     */
    fun formatPercent(value: Float): String {
        val rounded = round(value * 10f) / 10f
        return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
    }
}
