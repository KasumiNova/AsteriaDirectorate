package cn.kasuminova.astd.combat.shipsystems.base

/**
 * 决策规则：一拍决策上下文 → 本规则结论；null = 弃权，放行下一规则。
 *
 * 实现约定：规则为无状态单例（object），结论三分语义由所属树定义
 * （触发指令 / 「无动作」闸门终止 / null 弃权）。规则 id 用于决策留痕日志。
 */
internal interface PhaseRule<R> {
    /** 规则名，用于决策留痕日志（写入 [PriorityPhaseTree.lastFiredRuleId]）。 */
    val id: String
    /** 评估本拍上下文；返回非 null 结论即终止本树评估，返回 null 弃权放行下一规则。 */
    fun evaluate(ctx: PhaseDecisionContext): R?
}

/**
 * 优先级决策树：规则按列表序即优先级，首个非弃权规则胜出；全部弃权返回哨兵 none。
 *
 * 设计动机：把原先单条 if 链隐式表达的优先级显式化为规则表——新增行为 = 向规则表
 * 插入新 [PhaseRule]，或新增一棵树注册进协调器，不再需要往单类 if 链里塞分支。
 *
 * 泛型 R 含「无动作」哨兵值，由各树以具体枚举定义（如 PhaseOrder.NONE）。
 * 树可在 [postGate] 挂收尾闸：规则层结论出来后、返回前的最后修正
 * （如落点重叠闸把 SURFACE 压成 NONE）。
 */
internal abstract class PriorityPhaseTree<R>(
    /** 树名，用于日志与调试辨识。 */
    val id: String,
    /** 全弃权时的回落结论（「无动作」哨兵）。 */
    private val none: R,
    /** 规则表，列表序即优先级（先评估者优先）。 */
    private val rules: List<PhaseRule<R>>,
) {
    /** 本拍命中规则的 id（全弃权或回落为 null）；供协调器写决策留痕日志。 */
    var lastFiredRuleId: String? = null
        private set

    /** 评估本拍：按优先级遍历规则，首个非弃权结论经 [postGate] 收尾后返回；全弃权回落 none。 */
    fun decide(ctx: PhaseDecisionContext): R {
        var result = none
        lastFiredRuleId = null
        for (rule in rules) {
            val conclusion = rule.evaluate(ctx)
            if (conclusion != null) {
                lastFiredRuleId = rule.id
                result = conclusion
                break
            }
        }
        return postGate(result, ctx)
    }

    /** 收尾闸：规则层结论返回前的最后修正，默认原样放行。 */
    protected open fun postGate(order: R, ctx: PhaseDecisionContext): R = order
}

/**
 * 一拍决策上下文：态势快照 + 高优先级树已决结论（树间互斥的唯一判据）。
 *
 * 协调器按 A（斗篷开关）→ B（绕后走位）→ C（vent 链路）顺序评估三棵树，
 * A 树结论经 [cloakOrder] 传入 B/C 的评估（A 自身评估时恒为 [PhaseOrder.NONE]）。
 */
internal class PhaseDecisionContext(
    /** 本拍态势快照。 */
    val situation: PhaseSituation,
    /** A 树（斗篷开关）已决结论；B/C 评估时可见，A 自身评估时为 [PhaseOrder.NONE]。 */
    val cloakOrder: PhaseOrder = PhaseOrder.NONE,
)
