package cn.kasuminova.astd.api.buff

/**
 * 带整数层数的 Buff。调用方通过 [addStacks] 修改层数，实现负责将结果限制在有效范围内，
 * 并按 [decayMode] 在 [Buff.advance] 中更新层数。
 */
interface StackableBuff : Buff {
    /** 当前层数，范围为 0 至 [maxStacks]。 */
    val stacks: Int

    /** 层数上限。 */
    val maxStacks: Int

    /** 增加 [n] 层，负数表示扣减；返回限制在有效范围后的实际变化量。 */
    fun addStacks(n: Int): Int

    /** 层数随时间减少的方式。 */
    val decayMode: StackDecayMode
}

/** 叠层 Buff 的衰减模式。 */
enum class StackDecayMode {
    /** 按实现指定的速率持续扣减。 */
    CONTINUOUS,

    /** 距最近刷新超过等待时间后开始扣减。 */
    WINDOWED,

    /** 到期后移除整个 Buff。 */
    EXPIRE_ALL,
}
