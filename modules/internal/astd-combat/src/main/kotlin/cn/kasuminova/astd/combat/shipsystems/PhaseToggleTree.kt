package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseOrder
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRule
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PriorityPhaseTree

internal class PhaseToggleTree : PriorityPhaseTree<PhaseOrder>(
    id = "PhaseToggle",
    none = PhaseOrder.NONE,
    rules = listOf(
        ForcedSurfaceRule,
        MinPhaseTimeRule,
        SurfaceSafetyGateRule,
        SoonHitGateRule,
        FlankIntentRule,
        PressIntentRule,
        NoThreatSurfaceRule,
        RearSurfaceRule,
        CoverageGateRule,
        TacticalSurfaceRule,
        WeaponsReadySurfaceRule,
        CloakReadyGateRule,
        EmergencyDiveRule,
        SystemActiveGateRule,
        FriendlyCatchGateRule,
        SoftFluxDumpDiveRule,
        StaggerGateRule,
        PressApproachDiveRule,
        TravelDiveRule,
        RetreatDiveRule,
        FluxPressureDiveRule,
        FlankDiveRule,
        ThreatDiveRule,
        RechargeDiveRule,
        RepairDiveRule,
    ),
) {

    // 与他舰深度重叠时原版斗篷拒绝退相位（canBeDeactivated=false，toggle 空发），按住一切上浮指令
    override fun postGate(order: PhaseOrder, ctx: PhaseDecisionContext): PhaseOrder {
        if (order == PhaseOrder.SURFACE && ctx.situation.phased && ctx.situation.unphaseUnsafe) {
            return PhaseOrder.NONE
        }
        return order
    }

    companion object {
        internal const val SCAN_INTERVAL_SEC = 0.2f

        internal fun diveSoonThreshold(maxHull: Float): Float =
            maxOf(300f, maxHull * 0.05f)

        internal fun diveNearThreshold(maxHull: Float): Float =
            maxOf(600f, maxHull * 0.10f)

        internal const val LETHAL_SOON_HULL_FRACTION = 0.20f
        internal const val HOLD_MAX_HARD_FLUX = 0.75f
        internal const val SURFACE_HARD_FLUX = 0.55f
        internal const val FORCED_SURFACE_HOLD_MAX_SEC = 6f
        internal const val MAX_PHASE_TIME_SEC = 8f
        internal const val MIN_PHASE_TIME_SEC = 1.0f

        internal fun surfaceDangerThreshold(maxHull: Float): Float =
            maxOf(600f, maxHull * 0.10f)

        internal const val MIN_UNPHASE_TIME_SEC = 2.5f
        internal const val DIVE_FLUX_LEVEL = 0.90f
        internal const val DIVE_HARD_FLUX_MAX = 0.50f
        internal const val RECHARGE_DIVE_WEAPONS_FRAC = 0.35f
        internal const val RECHARGE_DIVE_HARD_FLUX_MAX = 0.35f
        internal const val WEAPONS_READY_SURFACE_FRAC = 0.6f
        internal const val RETREAT_DIVE_HARD_FLUX = 0.25f
        internal const val RETREAT_STAY_HARD_FLUX = 0.35f
        internal const val SOFT_FLUX_DIVE_MIN = 0.35f
        internal const val SOFT_FLUX_DIVE_MAX_FLUX_LEVEL = 0.75f
        internal const val SOFT_FLUX_DIVE_SAFE_NEAR_FRAC = 0.25f
        internal const val SOFT_FLUX_DIVE_SAFE_NEAR_FRAC_REAR = 0.5f
        internal const val REPAIR_DIVE_DISABLED_FRAC = 0.5f
        internal const val REPAIR_DIVE_HARD_FLUX_MAX = 0.5f
        internal const val REAR_SURFACE_MAX_COVERAGE = 1
        internal const val SURFACE_COVERAGE_MAX = 3
        internal const val FRIENDLY_CATCH_DAMAGE_MIN = 300f
        internal const val SCREENED_DIVE_THRESHOLD_MULT = 2f
        internal const val TRAVEL_PHASE_MIN_DIST = 2000f
        internal const val TRAVEL_DIVE_FLUX_MAX = 0.30f
        internal const val TRAVEL_DIVE_HARD_FLUX_MAX = 0.25f
        internal const val TRAVEL_HOLD_FLUX_MAX = 0.35f
        internal const val PRESS_APPROACH_MIN_DIST = 1500f
        internal const val PRESS_APPROACH_RANGE_MULT = 1.15f
        internal const val PRESS_SURFACE_RANGE_FRAC = 0.9f
        internal const val PRESS_APPROACH_WEAPONS_FRAC = 0.5f
        internal const val PRESS_APPROACH_HARD_FLUX_MAX = 0.25f
        internal const val FLANK_PHASE_CAP_MARGIN_SEC = 2f

        internal fun isSurfaceSafe(s: PhaseSituation): Boolean =
            !s.surfaceTooClose && !s.threatFrontAxisClose &&
                    s.incomingSurfaceDamage < surfaceDangerThreshold(s.maxHull)

        internal fun isForcedSurface(s: PhaseSituation): Boolean =
            s.phased && (s.hardFluxLevel >= SURFACE_HARD_FLUX || s.phaseActiveTime >= MAX_PHASE_TIME_SEC)

        internal fun isForcedSurfaceTrigger(s: PhaseSituation): Boolean {
            if (!s.phased) return false
            val maxPhaseTime =
                if (s.flankIntentActive || s.pressIntentActive) {
                    s.flankIntentWindowSec + FLANK_PHASE_CAP_MARGIN_SEC
                } else {
                    MAX_PHASE_TIME_SEC
                }
            return s.hardFluxLevel >= SURFACE_HARD_FLUX || s.phaseActiveTime >= maxPhaseTime
        }

        internal fun isHoldForLethal(s: PhaseSituation): Boolean =
            s.incomingSoonDamage + s.incomingFriendlySoonDamage >=
                    s.maxHull * LETHAL_SOON_HULL_FRACTION &&
                    s.hardFluxLevel < HOLD_MAX_HARD_FLUX

        internal fun nextForcedSurfaceHoldSec(s: PhaseSituation, currentSec: Float): Float =
            when {
                !isForcedSurfaceTrigger(s) || isSurfaceSafe(s) -> 0f
                isHoldForLethal(s) -> currentSec
                else -> currentSec + SCAN_INTERVAL_SEC
            }

        internal fun isSoftFluxDumpDive(s: PhaseSituation): Boolean {
            if (s.phased || !s.cloakReady || !s.engagedEnemyNear) return false
            if (s.softFluxLevel < SOFT_FLUX_DIVE_MIN || s.fluxLevel >= SOFT_FLUX_DIVE_MAX_FLUX_LEVEL) {
                return false
            }
            val safeFrac =
                if (s.inTargetRearArc && s.weaponCoverage <= SURFACE_COVERAGE_MAX) {
                    SOFT_FLUX_DIVE_SAFE_NEAR_FRAC_REAR
                } else {
                    SOFT_FLUX_DIVE_SAFE_NEAR_FRAC
                }
            return s.incomingNearDamage < diveNearThreshold(s.maxHull) * safeFrac
        }

        // travelDestDistance 无目标点时采样为 Float.MAX_VALUE，必须显式排除，否则会被 ≥ 闸误判为无限远目标点
        internal fun isTravelDive(s: PhaseSituation): Boolean =
            !s.phased && s.cloakReady && !s.engagedEnemyNear && !s.retreating &&
                    s.travelDestDistance >= TRAVEL_PHASE_MIN_DIST &&
                    s.travelDestDistance < Float.MAX_VALUE &&
                    s.fluxLevel <= TRAVEL_DIVE_FLUX_MAX &&
                    s.hardFluxLevel <= TRAVEL_DIVE_HARD_FLUX_MAX

        internal fun isTravelPhaseHold(s: PhaseSituation): Boolean =
            s.phased && !s.engagedEnemyNear && !s.retreating &&
                    s.travelDestDistance >= TRAVEL_PHASE_MIN_DIST &&
                    s.travelDestDistance < Float.MAX_VALUE &&
                    s.fluxLevel < TRAVEL_HOLD_FLUX_MAX

        internal fun isPressApproachDive(s: PhaseSituation): Boolean =
            !s.phased && s.cloakReady && !s.retreating &&
                    s.effectiveWeaponRange > 0f &&
                    s.targetDistance < Float.MAX_VALUE &&
                    s.targetDistance >= PRESS_APPROACH_MIN_DIST &&
                    s.targetDistance > s.effectiveWeaponRange * PRESS_APPROACH_RANGE_MULT &&
                    s.weaponsReadyFrac >= PRESS_APPROACH_WEAPONS_FRAC &&
                    s.hardFluxLevel < PRESS_APPROACH_HARD_FLUX_MAX &&
                    s.incomingNearDamage < diveNearThreshold(s.maxHull)
    }
}

internal object ForcedSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "ForcedSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        val holdForLethal = PhaseToggleTree.isHoldForLethal(s)
        if (PhaseToggleTree.isForcedSurfaceTrigger(s) && !holdForLethal) {
            if (PhaseToggleTree.isSurfaceSafe(s) ||
                s.forcedSurfaceHoldSec >= PhaseToggleTree.FORCED_SURFACE_HOLD_MAX_SEC
            ) {
                return PhaseOrder.SURFACE
            }
            return PhaseOrder.NONE
        }
        return null
    }
}

internal object MinPhaseTimeRule : PhaseRule<PhaseOrder> {
    override val id = "MinPhaseTime"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.phaseActiveTime < PhaseToggleTree.MIN_PHASE_TIME_SEC) return PhaseOrder.NONE
        return null
    }
}

internal object SurfaceSafetyGateRule : PhaseRule<PhaseOrder> {
    override val id = "SurfaceSafetyGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (!PhaseToggleTree.isSurfaceSafe(s)) return PhaseOrder.NONE
        return null
    }
}

internal object SoonHitGateRule : PhaseRule<PhaseOrder> {
    override val id = "SoonHitGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        val defensiveSoon = s.incomingSoonDamage + s.incomingFriendlySoonDamage
        if (defensiveSoon >= PhaseToggleTree.diveSoonThreshold(s.maxHull)) return PhaseOrder.NONE
        return null
    }
}

internal object FlankIntentRule : PhaseRule<PhaseOrder> {
    override val id = "FlankIntent"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.flankIntentActive) {
            if (s.inTargetRearArc && s.weaponCoverage <= PhaseToggleTree.SURFACE_COVERAGE_MAX &&
                s.rearSurfaceDistanceSafe
            ) {
                return PhaseOrder.SURFACE
            }
            return PhaseOrder.NONE
        }
        return null
    }
}

internal object PressIntentRule : PhaseRule<PhaseOrder> {
    override val id = "PressIntent"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased || !s.pressIntentActive) return null
        return PhaseOrder.NONE
    }
}

internal object NoThreatSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "NoThreatSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (!s.engagedEnemyNear &&
            s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull) * 0.5f
        ) {
            if (!(s.retreating && s.hardFluxLevel < PhaseToggleTree.RETREAT_STAY_HARD_FLUX) &&
                !PhaseToggleTree.isTravelPhaseHold(s)
            ) {
                return PhaseOrder.SURFACE
            }
        }
        return null
    }
}

internal object RearSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "RearSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.engagedEnemyNear && s.inTargetRearArc &&
            s.weaponCoverage <= PhaseToggleTree.REAR_SURFACE_MAX_COVERAGE && s.rearSurfaceDistanceSafe
        ) {
            return PhaseOrder.SURFACE
        }
        return null
    }
}

internal object CoverageGateRule : PhaseRule<PhaseOrder> {
    override val id = "CoverageGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.weaponCoverage > PhaseToggleTree.SURFACE_COVERAGE_MAX) return PhaseOrder.NONE
        return null
    }
}

internal object TacticalSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "TacticalSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.systemReady && s.engagedEnemyNear) return PhaseOrder.SURFACE
        return null
    }
}

internal object WeaponsReadySurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "WeaponsReadySurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.weaponsReadyFrac >= PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC &&
            s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull)
        ) {
            return PhaseOrder.SURFACE
        }
        return null
    }
}

internal object CloakReadyGateRule : PhaseRule<PhaseOrder> {
    override val id = "CloakReadyGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (!s.cloakReady) return PhaseOrder.NONE
        return null
    }
}

internal object EmergencyDiveRule : PhaseRule<PhaseOrder> {
    override val id = "EmergencyDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        val defensiveSoon = s.incomingSoonDamage + s.incomingFriendlySoonDamage
        val screenMult =
            if (s.friendlyScreenAhead) PhaseToggleTree.SCREENED_DIVE_THRESHOLD_MULT else 1f
        val selfCritical = defensiveSoon >= s.maxHull * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION
        val friendlyRisk = s.friendlyCatchDamage >= PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN
        if (defensiveSoon >= PhaseToggleTree.diveSoonThreshold(s.maxHull) * screenMult &&
            (!friendlyRisk || selfCritical)
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

internal object SystemActiveGateRule : PhaseRule<PhaseOrder> {
    override val id = "SystemActiveGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.systemActive) return PhaseOrder.NONE
        return null
    }
}

internal object FriendlyCatchGateRule : PhaseRule<PhaseOrder> {
    override val id = "FriendlyCatchGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.friendlyCatchDamage >= PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN) return PhaseOrder.NONE
        return null
    }
}

internal object SoftFluxDumpDiveRule : PhaseRule<PhaseOrder> {
    override val id = "SoftFluxDumpDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (PhaseToggleTree.isSoftFluxDumpDive(s)) return PhaseOrder.DIVE
        return null
    }
}

internal object StaggerGateRule : PhaseRule<PhaseOrder> {
    override val id = "StaggerGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.timeSinceUnphase < PhaseToggleTree.MIN_UNPHASE_TIME_SEC) return PhaseOrder.NONE
        return null
    }
}

internal object PressApproachDiveRule : PhaseRule<PhaseOrder> {
    override val id = "PressApproach"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (PhaseToggleTree.isPressApproachDive(s)) return PhaseOrder.DIVE
        return null
    }
}

internal object TravelDiveRule : PhaseRule<PhaseOrder> {
    override val id = "TravelDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (PhaseToggleTree.isTravelDive(s)) return PhaseOrder.DIVE
        return null
    }
}

internal object RetreatDiveRule : PhaseRule<PhaseOrder> {
    override val id = "RetreatDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.retreating && s.hardFluxLevel < PhaseToggleTree.RETREAT_DIVE_HARD_FLUX) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

internal object FluxPressureDiveRule : PhaseRule<PhaseOrder> {
    override val id = "FluxPressureDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.fluxLevel >= PhaseToggleTree.DIVE_FLUX_LEVEL &&
            s.hardFluxLevel < PhaseToggleTree.DIVE_HARD_FLUX_MAX && s.engagedEnemyNear
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

internal object FlankDiveRule : PhaseRule<PhaseOrder> {
    override val id = "FlankDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (FlankAttackTree.isFlankDive(s)) return PhaseOrder.DIVE
        return null
    }
}

internal object ThreatDiveRule : PhaseRule<PhaseOrder> {
    override val id = "ThreatDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        val screenMult =
            if (s.friendlyScreenAhead) PhaseToggleTree.SCREENED_DIVE_THRESHOLD_MULT else 1f
        if (s.incomingNearDamage >= PhaseToggleTree.diveNearThreshold(s.maxHull) * screenMult) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

internal object RechargeDiveRule : PhaseRule<PhaseOrder> {
    override val id = "RechargeDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.weaponsReadyFrac <= PhaseToggleTree.RECHARGE_DIVE_WEAPONS_FRAC && s.engagedEnemyNear &&
            s.hardFluxLevel < PhaseToggleTree.RECHARGE_DIVE_HARD_FLUX_MAX
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

internal object RepairDiveRule : PhaseRule<PhaseOrder> {
    override val id = "RepairDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.engagedEnemyNear && s.hardFluxLevel < PhaseToggleTree.REPAIR_DIVE_HARD_FLUX_MAX &&
            (s.weaponsDisabledFrac >= PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC ||
                    s.enginesDisabledFrac >= PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC)
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}
