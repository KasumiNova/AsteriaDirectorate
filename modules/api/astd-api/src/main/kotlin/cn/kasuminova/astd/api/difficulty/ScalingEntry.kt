package cn.kasuminova.astd.api.difficulty

/**
 * 在难度系数为 1、2、5 时的数值及其映射策略。
 *
 * v2 是设计基准。叠乘、冷却转频率、概率转期望、半径转面积等在高档位产生超线性收益的数值
 * 禁止直接使用 [ScalingMap.LINEAR]，必须显式提供保守映射。
 *
 * @property v1 难度系数为 1 时的值
 * @property v2 难度系数为 2 时的值
 * @property v5 难度系数为 5 时的值
 * @property map 计算中间难度与越界难度的映射策略
 */
data class ScalingEntry(
    val v1: Float,
    val v2: Float,
    val v5: Float,
    val map: ScalingMap = ScalingMap.LINEAR,
)
