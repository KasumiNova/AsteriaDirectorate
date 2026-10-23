package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseOrder
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRule
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PriorityPhaseTree

internal enum class VentOrder { NONE, SUPPRESS, VENT }

internal class FluxVentTree : PriorityPhaseTree<VentOrder>(
    id = "FluxVent",
    none = VentOrder.NONE,
    rules = listOf(
        DiveOrderedSuppressRule,
        SoftFluxDumpVentHoldRule,
        TacticalVentRule,
    ),
) {

    companion object {
        internal const val SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC = 4f

        internal const val TACTICAL_VENT_FLUX_CAP = 0.15f

        internal const val TACTICAL_VENT_HARD_FRAC = 0.85f

        internal const val TACTICAL_VENT_MAX_SEC = 1.5f

        internal const val TACTICAL_VENT_SAFE_NEAR_FRAC = 0.5f

        internal fun isSoftFluxDumpVentHold(s: PhaseSituation): Boolean {
            if (s.systemActive ||
                s.friendlyCatchDamage >= PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN
            ) {
                return false
            }
            if (PhaseToggleTree.isSoftFluxDumpDive(s)) return true
            if (s.phased || s.cloakReady ||
                s.cloakCooldownRemaining > SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC
            ) {
                return false
            }
            return PhaseToggleTree.isSoftFluxDumpDive(s.copy(cloakReady = true))
        }

        internal fun isTacticalVent(s: PhaseSituation): Boolean =
            !s.phased && s.engagedEnemyNear && !s.retreating && !s.venting && !s.systemActive &&
                    s.fluxLevel > 0.01f &&
                    s.fluxLevel <= TACTICAL_VENT_FLUX_CAP * s.ventRateMult &&
                    s.hardFluxLevel >= s.fluxLevel * TACTICAL_VENT_HARD_FRAC &&
                    s.ventTimeSec <= TACTICAL_VENT_MAX_SEC &&
                    s.incomingSoonDamage + s.incomingFriendlySoonDamage <
                    PhaseToggleTree.diveSoonThreshold(s.maxHull) &&
                    s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull) *
                    TACTICAL_VENT_SAFE_NEAR_FRAC
    }
}

internal object DiveOrderedSuppressRule : PhaseRule<VentOrder> {
    override val id = "DiveOrderedSuppress"

    override fun evaluate(ctx: PhaseDecisionContext): VentOrder? {
        if (ctx.cloakOrder == PhaseOrder.DIVE) return VentOrder.SUPPRESS
        return null
    }
}

internal object SoftFluxDumpVentHoldRule : PhaseRule<VentOrder> {
    override val id = "SoftFluxDumpVentHold"

    override fun evaluate(ctx: PhaseDecisionContext): VentOrder? {
        if (FluxVentTree.isSoftFluxDumpVentHold(ctx.situation)) return VentOrder.SUPPRESS
        return null
    }
}

internal object TacticalVentRule : PhaseRule<VentOrder> {
    override val id = "TacticalVent"

    override fun evaluate(ctx: PhaseDecisionContext): VentOrder? {
        if (ctx.cloakOrder != PhaseOrder.NONE) return null
        if (FluxVentTree.isTacticalVent(ctx.situation)) return VentOrder.VENT
        return null
    }
}
