package cn.kasuminova.astd.api.difficulty

import kotlin.math.roundToInt

/**
 * 在难度系数 1 至 5 各档使用的数值。
 *
 * [at] 将系数限制在 [1, 5] 后舍入到最近档位，不进行插值。
 */
data class ScalingTable(
    /** k_s = 1（迟暮档）取值。 */
    val v1: Float,
    /** 难度系数为 2 时的值，即设计基准。 */
    val v2: Float,
    /** 难度系数为 3 时的值。 */
    val v3: Float,
    /** 难度系数为 4 时的值。 */
    val v4: Float,
    /** 难度系数为 5 时的值。 */
    val v5: Float,
) {
    /** 返回最近档位的值；[k] 为 NaN 时抛出 [IllegalArgumentException]。 */
    fun at(k: Float): Float {
        require(!k.isNaN()) { "ScalingTable.at: k_s 为 NaN，难度配置上游异常（不允许静默取档）" }
        return when (k.coerceIn(1f, 5f).roundToInt()) {
            1 -> v1
            2 -> v2
            3 -> v3
            4 -> v4
            else -> v5
        }
    }
}
