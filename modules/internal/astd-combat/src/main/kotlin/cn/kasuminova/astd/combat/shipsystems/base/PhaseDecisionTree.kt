package cn.kasuminova.astd.combat.shipsystems.base

internal interface PhaseRule<R> {
    val id: String
    fun evaluate(ctx: PhaseDecisionContext): R?
}

internal abstract class PriorityPhaseTree<R>(
    val id: String,
    private val none: R,
    private val rules: List<PhaseRule<R>>,
) {
    var lastFiredRuleId: String? = null
        private set

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

    protected open fun postGate(order: R, ctx: PhaseDecisionContext): R = order
}

internal class PhaseDecisionContext(
    val situation: PhaseSituation,
    val cloakOrder: PhaseOrder = PhaseOrder.NONE,
)
