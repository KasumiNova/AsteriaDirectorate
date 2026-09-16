package cn.kasuminova.astd.api.difficulty

import kotlin.math.roundToInt

/**
 * 单个机制数值的五档难度查表声明（k_s = 1..5 逐档精确取值）。
 *
 * 动机：三锚点 [ScalingEntry] + 分段线性插值只能表达连续线性刻度；当设计给出
 * 逐档精确表（如 75%/100%/125%/150%/200% 这类 k5 跳变档）时线性映射会在中间档失真，
 * 此类数值改用本类型逐档查表。
 *
 * 自定义非整数 k_s（LunaLib 自定义档）：就近取整到最近档位（half-up，如 2.5 → v3），
 * clamp 到 [1, 5]；查表不插值，保证逐档语义不被中间态稀释。
 *
 * 玩家侧口径与 [ScalingEntry] 一致：固定取 [v2]（砺刃档 = 设计基准）。
 */
data class ScalingTable(
    /** k_s = 1（迟暮档）取值。 */
    val v1: Float,
    /** k_s = 2（砺刃档，设计基准；玩家侧固定取此值）取值。 */
    val v2: Float,
    /** k_s = 3（远征档）取值。 */
    val v3: Float,
    /** k_s = 4（自定义中间档）取值。 */
    val v4: Float,
    /** k_s = 5（破晓档）取值。 */
    val v5: Float,
) {
    /** 按固有缩放系数取档：clamp [1, 5] 后就近取整（half-up）；NaN 属上游配置异常，直接抛出不留静默兜底。 */
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
