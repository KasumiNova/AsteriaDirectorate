package cn.kasuminova.astd.api.buff

/**
 * 带整数层数的 Buff。
 *
 * 动机：电荷针刺淤积（连续衰减）、穷距持续演算（命中窗口衰减）
 * 及后续叠层机制的统一读写面；层数增减统一走 [addStacks] 的 clamp 路径，
 * 调用侧不直接改 [stacks]，保证「叠层上限」只有一处判定。
 *
 * 衰减语义由实现按 [decayMode] 在 [Buff.advance] 内落实：
 * 0 值防线——`stacksPerSecond <= 0` 或 `maxStacks <= 0` 属配置错误，实现必须 clamp 到下限并记 WARN，
 * 不得静默恒零或除零。
 */
interface StackableBuff : Buff {
    /**
     * 当前层数，[0, maxStacks]。
     */
    val stacks: Int

    /**
     * 层数上限（实现侧固定或按规则动态裁定；产出压力折算由实现自行承担，不在本接口面）。
     */
    val maxStacks: Int

    /**
     * 叠加 [n] 层并返回实际生效层数变化（受上限 clamp）。
     * [n] 可为负（强制扣层），实现内部统一走同一 clamp 路径。
     */
    fun addStacks(n: Int): Int

    /**
     * 衰减模式：决定 [Buff.advance] 的默认语义。
     */
    val decayMode: StackDecayMode
}

/**
 * 叠层 Buff 的衰减模式：决定 advance 的默认语义。
 */
enum class StackDecayMode {
    /**
     * 连续流失：按秒连续扣减（速率由实现裁定，如电荷针刺「当前层数 3%/s、下限 2 层/s」）。
     */
    CONTINUOUS,

    /**
     * 窗口后流失：最近刷新起静默 windowSeconds 后按 stacksPerSecond 扣减（穷距 3s 窗口）。
     */
    WINDOWED,

    /**
     * 到期清零：durationSeconds 后整 Buff 移除（对齐 StackingShipBuffs 语义，供后续迁移用）。
     */
    EXPIRE_ALL,
}
