package cn.kasuminova.astd.combat.shipsystems.base

internal enum class PhaseAction {
    RETREAT,
    EVADE,
    RECOVER,
    FLANK,
    APPROACH,
    ATTACK,
    TRAVEL,
}

internal enum class PhaseRecoverMode {
    NONE,
    NATURAL,
    PHASE_COOLDOWN,
    PHASE_FLUX,
    VENT,
}

internal data class PhaseAttack(
    val hasWeapons: Boolean,
    val inRange: Boolean,
    val canFire: Boolean,
    val readySoon: Boolean,
    val remainingBurstCost: Float,
    val burstWithinCapacity: Boolean,
    val effectiveOutput: Boolean,
    val killingBlow: Boolean,
    val cooldownRemaining: Float,
)

internal data class PhaseSupport(
    val available: Boolean = false,
    val effectiveRange: Float = 0f,
    val targetInRange: Boolean = false,
    val engaged: Boolean = false,
)

internal data class PhaseDanger(
    val lethalSoon: Boolean,
    val significantSoon: Boolean,
    val significantNear: Boolean,
    val surfaceLoss: Float,
    val friendlyCatchLoss: Float,
    val threatKeys: Set<Any> = emptySet(),
    val activeThreatKeys: Set<Any> = emptySet(),
    val significantThreatKeys: Set<Any> = threatKeys,
)

internal enum class PhaseRecoveryState { NONE, RUNNING, PAUSED, EXITING, COMPLETED, CANCELLED }

internal enum class PhaseRecoveryReason {
    NONE, COMMITTED, COOLDOWN_PENDING, FLUX_PENDING, OBJECTIVES_MET, EVADE,
    TIME_LIMIT, HARD_FLUX_LIMIT, UNSAFE_SIMULATION, TARGET_INVALID, WEAPON_INVALID,
    SYSTEM_INVALID, RETREAT, PHASE_ENDED, ACTIVATION_UNCONFIRMED, ROUTE_FAILED, ATTACK_WINDOW,
}

internal data class PhaseRecoveryExecution(
    val state: PhaseRecoveryState = PhaseRecoveryState.NONE,
    val reason: PhaseRecoveryReason = PhaseRecoveryReason.NONE,
    val mode: PhaseRecoverMode = PhaseRecoverMode.NONE,
    val targetId: String = "",
    val elapsedLocal: Float = 0f,
    val cooldownRemaining: Float = 0f,
    val objects: String = "",
    val awaitingSurface: Boolean = false,
    val awaitingAttack: Boolean = false,
) {
    val continuing: Boolean get() = state == PhaseRecoveryState.RUNNING || state == PhaseRecoveryState.PAUSED
}

internal data class PhaseRecovery(
    val required: Boolean,
    val mode: PhaseRecoverMode = PhaseRecoverMode.NONE,
    val phaseFluxDuration: Float = 0f,
    val phaseCooldownDuration: Float = 0f,
    val ventDuration: Float = 0f,
    val ventSafe: Boolean = false,
    val naturalDuration: Float = Float.POSITIVE_INFINITY,
    val plannedPhaseCost: Float = 0f,
    val phaseCandidateDuration: Float = Float.POSITIVE_INFINITY,
    val phaseCandidateReason: String = "NOT_REQUIRED",
    val execution: PhaseRecoveryExecution = PhaseRecoveryExecution(),
)

internal data class PhaseSituation(
    val targetId: String?,
    val phased: Boolean,
    val phaseActiveTime: Float,
    val timeSinceUnphase: Float,
    val hardFlux: Float,
    val softFlux: Float,
    val maxFlux: Float,
    val hull: Float,
    val maxHull: Float,
    val cloakReady: Boolean,
    val retreating: Boolean,
    val unphaseUnsafe: Boolean,
    val targetDistance: Float,
    val effectiveWeaponRange: Float,
    val inTargetRearArc: Boolean,
    val targetFrontCoverage: Int,
    val enemyNearby: Boolean,
    val enemyWindowRemaining: Float,
    val travelDestDistance: Float,
    val attack: PhaseAttack,
    val danger: PhaseDanger,
    val recovery: PhaseRecovery,
    val routeActive: Boolean,
    val routeComplete: Boolean,
    val routeFailed: Boolean,
    val routeRecoveryRequired: Boolean = false,
    val routeStage: PhaseRouteStage? = null,
    val phaseActivationCost: Float = 0f,
    val protectingBurst: Boolean = false,
    val support: PhaseSupport = PhaseSupport(),
    val opportunity: PhaseOpportunity = PhaseOpportunity(),
    val recoveredAttackReady: Boolean = false,
    val hardFluxRecoveryActive: Boolean = false,
    val routeProgress: Float = 0f,
)

internal data class PhaseDecision(
    val action: PhaseAction,
    val dive: Boolean = false,
    val surface: Boolean = false,
    val vent: Boolean = false,
    val suppressVent: Boolean = false,
    val customMovement: Boolean = false,
    val recoverMode: PhaseRecoverMode = PhaseRecoverMode.NONE,
    val goodSpot: Boolean = false,
    val reason: String,
)

internal data class PhaseDiagnostics(
    val action: String,
    val reason: String,
    val targetId: String,
    val pathStage: Int,
    val waypointX: Float,
    val waypointY: Float,
    val routeReplans: Int,
    val routeVersion: Int,
    val routeStage: String,
    val routeReplanReason: String,
    val routeCompleted: Boolean,
    val routeFailed: Boolean,
    val routeFailureReason: String,
    val routeProgress: Float,
    val routeLateralTravel: Float = 0f,
    val routeStageTravel: Float = 0f,
    val targetDistance: Float,
    val effectiveWeaponRange: Float,
    val hardFlux: Float,
    val peakHardFlux: Float,
    val softFlux: Float,
    val surfaceLoss: Float,
    val attackInRange: Boolean,
    val attackCanFire: Boolean,
    val sampleTime: Float,
    val remainingBurstCost: Float,
    val activeBurstCost: Float,
    val costUncertainties: String,
    val plannedPhaseCost: Float,
    val routeElapsed: Float = 0f,
    val routeMaxElapsed: Float = 0f,
    val routeLength: Float = 0f,
    val shipSpeed: Float = 0f,
    val routeSideResults: String = "",
    val inTargetRearArc: Boolean = false,
    val targetFrontCoverage: Int = 0,
    val phaseActiveTime: Float = 0f,
    val routeFailureCause: String = "NONE",
)
