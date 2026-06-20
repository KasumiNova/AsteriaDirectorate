package cn.kasuminova.astd.combat.lens.marks

/**
 * 引力透镜级两类标记的纯数学换算。
 *
 * 动机：spec §1.1 定义误差/深水标记的逐层效果，提取为无副作用纯函数，
 * 便于单元测试与平衡调参，且 applier 与 tooltip 共用同一真相源。
 */
object LensMarkMath {

    /** 两类标记的层数上限（spec §1.1）。 */
    const val MAX_STACKS: Int = 10

    private const val DRIFT_BONUS_LOW = 0.025f
    private const val DRIFT_BONUS_HIGH = 0.075f

    private const val DEEP_RANGE_PER_STACK = 0.02f
    private const val DEEP_ACCURACY_PER_STACK = 0.04f
    private const val DEEP_SPEED_PER_STACK = 0.01f
    private const val DEEP_VS_LENS_PER_STACK = 0.04f

    private fun clampStacks(stacks: Int): Int = stacks.coerceIn(0, MAX_STACKS)

    /** 误差标记每层增伤比例：magnitudeMult 在 [low, high] 间线性插值（夹紧）。 */
    fun driftPerStackBonus(magnitudeMult: Float): Float {
        val t = (magnitudeMult * 0.5f).coerceIn(0f, 1f)
        return DRIFT_BONUS_LOW + (DRIFT_BONUS_HIGH - DRIFT_BONUS_LOW) * t
    }

    /** 目标受到的伤害倍率（≥1）。 */
    fun driftDamageTakenMult(stacks: Int, magnitudeMult: Float): Float =
        1f + clampStacks(stacks) * driftPerStackBonus(magnitudeMult)

    /** 武器射程倍率（≤1）。 */
    fun deepWaterRangeMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_RANGE_PER_STACK).coerceAtLeast(0f)

    /** 武器精度倍率（≤1，等效精度，用于展示/测试）。 */
    fun deepWaterAccuracyMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_ACCURACY_PER_STACK).coerceAtLeast(0f)

    /** 最大航速倍率（≤1）。 */
    fun deepWaterSpeedMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_SPEED_PER_STACK).coerceAtLeast(0f)

    /** 对"引力透镜"造成的伤害倍率（≤1）。 */
    fun deepWaterVsLensDamageMult(stacks: Int): Float =
        (1f - clampStacks(stacks) * DEEP_VS_LENS_PER_STACK).coerceAtLeast(0f)
}
