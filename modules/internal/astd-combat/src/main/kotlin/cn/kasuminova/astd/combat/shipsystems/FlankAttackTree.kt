package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRule
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PriorityPhaseTree

internal enum class FlankOrder { NONE, BACKOFF, DRIVE, HOLD_SPOT }

// 原版相位 AI 被替换后 PHASE_ATTACK_RUN 系走位旗标无人管理，本树接管绕后/迫近意图期间的旗标组
internal class FlankAttackTree : PriorityPhaseTree<FlankOrder>(
    id = "FlankAttack",
    none = FlankOrder.NONE,
    rules = listOf(
        ForcedHoldBackoffRule,
        FlankDriveRule,
        FlankHoldSpotRule,
    ),
) {

    fun shouldArmIntent(s: PhaseSituation): Boolean = isFlankDive(s)

    companion object {
        internal const val FLANK_DIVE_WEAPONS_FRAC = 0.5f

        internal const val FLANK_DIVE_HARD_FLUX_MAX = 0.4f

        internal const val FLANK_INTENT_SEC = 12f

        internal const val FLANK_INTENT_REF_DIST = 1200f

        internal const val FLANK_INTENT_MAX_SEC = 24f

        internal fun isFlankDive(s: PhaseSituation): Boolean =
            !s.phased && s.cloakReady && s.targetLowMobility && !s.inTargetRearArc &&
                    s.engagedEnemyNear &&
                    s.weaponsReadyFrac >= FLANK_DIVE_WEAPONS_FRAC &&
                    s.hardFluxLevel < FLANK_DIVE_HARD_FLUX_MAX &&
                    s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull)

        internal fun flankIntentWindowSec(threatDistance: Float): Float =
            (FLANK_INTENT_SEC * threatDistance / FLANK_INTENT_REF_DIST)
                .coerceIn(FLANK_INTENT_SEC, FLANK_INTENT_MAX_SEC)

        internal fun maneuverDriveActive(s: PhaseSituation): Boolean =
            s.phased && (s.flankIntentActive || s.pressIntentActive) && s.forcedSurfaceHoldSec <= 0f

        internal fun pressIntentActive(
            remainingSec: Float,
            phased: Boolean,
            targetDistance: Float,
            effectiveWeaponRange: Float,
        ): Boolean =
            remainingSec > 0f && phased &&
                    effectiveWeaponRange > 0f &&
                    targetDistance < Float.MAX_VALUE &&
                    targetDistance > effectiveWeaponRange * PhaseToggleTree.PRESS_SURFACE_RANGE_FRAC

        internal fun flankDriveHoldSpot(s: PhaseSituation): Boolean =
            s.inTargetRearArc && !s.unphaseUnsafe && s.rearSurfaceDistanceSafe
    }
}

internal object ForcedHoldBackoffRule : PhaseRule<FlankOrder> {
    override val id = "ForcedHoldBackoff"

    override fun evaluate(ctx: PhaseDecisionContext): FlankOrder? {
        val s = ctx.situation
        if (s.phased && s.forcedSurfaceHoldSec > 0f) return FlankOrder.BACKOFF
        return null
    }
}

internal object FlankDriveRule : PhaseRule<FlankOrder> {
    override val id = "FlankDrive"

    override fun evaluate(ctx: PhaseDecisionContext): FlankOrder? {
        val s = ctx.situation
        if (FlankAttackTree.maneuverDriveActive(s) &&
            !(s.flankIntentActive && FlankAttackTree.flankDriveHoldSpot(s))
        ) {
            return FlankOrder.DRIVE
        }
        return null
    }
}

internal object FlankHoldSpotRule : PhaseRule<FlankOrder> {
    override val id = "FlankHoldSpot"

    override fun evaluate(ctx: PhaseDecisionContext): FlankOrder? {
        val s = ctx.situation
        if (FlankAttackTree.maneuverDriveActive(s) && s.flankIntentActive &&
            FlankAttackTree.flankDriveHoldSpot(s)
        ) {
            return FlankOrder.HOLD_SPOT
        }
        return null
    }
}
