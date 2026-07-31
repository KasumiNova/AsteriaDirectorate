package cn.kasuminova.astd.api.difficulty

/**
 * 单个机制数值的三锚点声明（难度双轨制 D13 的"逐项登记"落点）。
 *
 * 动机：全局数值规则要求"下限克制、上限放开"——凡概率 / 上限 / 浮动类数值，
 * 一律给出三锚点而非单值，最终值由固有缩放系数 k_s 经 [map] 派生。
 * 使用处在机制实现就地声明本 entry，即完成该项数值的登记；
 * 高档位会超线性收益的数值类型（叠乘 / 冷却转频率 / 概率转期望 / AoE 半径转面积），
 * 必须在 [map] 登记保守映射并写明理由，禁止裸用 [ScalingMap.LINEAR]。
 *
 * @property v1 克制下限（k_s = 1.0 时的值）
 * @property v2 设计基准（k_s = 2.0 时的值；存量改造时先把现值登记到这里）
 * @property v5 放开上限（k_s = 5.0 时的值；概率等语义上限不得突破）
 * @property map 映射策略，默认 [ScalingMap.LINEAR]
 */
data class ScalingEntry(
    val v1: Float,
    val v2: Float,
    val v5: Float,
    val map: ScalingMap = ScalingMap.LINEAR,
)
