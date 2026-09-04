package cn.kasuminova.astd.api.difficulty

/**
 * 轨一（固有缩放系数 k_s → 最终值）的映射策略。
 *
 * 动机：难度双轨制（D13）下，每个受缩放的机制数值都要从 k_s 派生最终值。
 * 默认分段线性（[LINEAR]）能满足大多数数值；但叠乘类属性、冷却转频率、
 * 概率转期望、AoE 半径转面积等类型在高档位会产生超线性收益，必须用更保守的映射。
 * 本接口声明为 fun interface，使用处可用 lambda 就地登记自定义映射（需注明理由），
 * 也可新增命名实现类沉淀为可复用策略。
 *
 * 映射函数属于内部实现细节，对玩家不可见——玩家只看到应用后的最终数值。
 */
fun interface ScalingMap {

    /**
     * 由固有缩放系数与三锚点计算最终值。
     *
     * @param k 固有缩放系数 k_s，取值范围 [1.0, 5.0]
     * @param v1 克制下限（k = 1.0 时的值）
     * @param v2 设计基准（k = 2.0 时的值，平衡实测以此为准）
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
