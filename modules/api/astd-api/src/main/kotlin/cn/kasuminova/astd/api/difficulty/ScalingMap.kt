package cn.kasuminova.astd.api.difficulty

import cn.kasuminova.astd.api.difficulty.ScalingMap.Companion.LINEAR


/**
 * 根据难度系数与三个数值计算最终值。实现不得修改外部状态。
 * 叠乘、冷却转频率、概率转期望、半径转面积等超线性收益的数值禁止直接使用 [LINEAR]，必须显式提供保守映射。
 */
fun interface ScalingMap {

    /**
     * 由固有缩放系数与三锚点计算最终值。
     *
     * @param k 固有缩放系数 k_s，取值范围 [1.0, 5.0]
     * @param v1 克制下限（k = 1.0 时的值）
     * @param v2 难度系数为 2 时的值
     * @param v5 放开上限（k = 5.0 时的值）
     * @return 应用映射后的最终数值
     */
    fun value(k: Float, v1: Float, v2: Float, v5: Float): Float

    companion object {
        /**
         * 默认映射：分段线性。
         * k ∈ [1, 2] 段在 v1 → v2 间插值；k ∈ [2, 5] 段在 v2 → v5 间插值。
         * 两段在 k = 2 处连续（均命中 v2）。
         */
        val LINEAR: ScalingMap = ScalingMap { k, v1, v2, v5 ->
            when {
                k <= 2f -> v1 + (v2 - v1) * (k - 1f).coerceIn(0f, 1f)
                else -> v2 + (v5 - v2) * ((k - 2f) / 3f).coerceIn(0f, 1f)
            }
        }
    }
}
