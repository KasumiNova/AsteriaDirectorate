package cn.kasuminova.astd.api.difficulty

/**
 * 轨二（进程动态系数）读取面：玩家战力评估。
 *
 * 动机：难度双轨制（D13）的轨二——模组根据玩家舰队组成、军官、技能等数据
 * 自动评估玩家当前战力，用于赏金舰队的生成表决策（规模预算 / 旗舰出场阈值 /
 * 词缀池数量与品质）。评估对玩家不可见，且**绝不触碰机制数值**（那是轨一的职责）。
 *
 * 评估在赏金生成时取快照，同一赏金生命周期内不变；连续生成由实现侧做平滑防抖。
 *
 * 实现：`cn.kasuminova.astd.campaign.bounty.FleetStrengthAssessmentImpl`。
 */
interface FleetStrengthAssessment {

    /**
     * 对当前玩家舰队做战力评估快照。
     *
     * @param referenceFP 该赏金威胁档位的基准战力（tier 基准 FP），作为 S_ref 参与压缩
     * @return 评估快照 [StrengthSnapshot]
     */
    fun assess(referenceFP: Float): StrengthSnapshot
}

/**
 * 一次战力评估的快照结果。
 *
 * @property score 玩家战力分 S（舰船部署点×品级修正 + 军官 + 战斗技能）
 * @property reference 基准战力 S_ref
 * @property p 进程系数：sqrt(S / S_ref)，封顶 [0.85, 2.2]（边际递减，防堆料惩罚）
 * @property k 归一化进程系数 k_p ∈ [0, 1]，词缀池与旗舰阈值的直接输入
 * @property breakdown 逐项贡献分解（标签 + 数值），供调试日志与测试断言
 */
data class StrengthSnapshot(
    val score: Float,
    val reference: Float,
    val p: Float,
    val k: Float,
    val breakdown: List<Component>,
) {
    /**
     * 战力评估的单项贡献。
     *
     * @property label 贡献项标签（如 "ships" / "officers" / "skills" / "quality"）
     * @property value 该项的数值贡献
     */
    data class Component(val label: String, val value: Float)
}
