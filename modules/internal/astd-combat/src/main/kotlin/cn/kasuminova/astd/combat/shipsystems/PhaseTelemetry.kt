package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDiagnostics
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags

internal class PhaseTelemetry(owner: Any) {
    private val log = Global.getLogger(PhaseTelemetry::class.java)
    private val instanceId = System.identityHashCode(owner)
    private var lastAction: String? = null
    private var lastReason: String? = null
    private var lastLogTime = Float.NEGATIVE_INFINITY

    fun routeTrace(engine: CombatEngineAPI, ship: ShipAPI, operation: String, before: PhaseRouteState?, after: PhaseRouteState) {
        if (operation != "CREATE" && before != null &&
            before.waypointIndex == after.waypointIndex && before.route.version == after.route.version &&
            before.replans == after.replans && before.paused == after.paused && before.failure == after.failure
        ) return
        log.info(
            "[GravityPhaseRoute] ai=$instanceId ship=${ship.id} time=${engine.getTotalElapsedTime(false)} " +
                "operation=$operation before=${before?.waypoint?.stage}/${before?.waypointIndex} " +
                "after=${after.waypoint.stage}/${after.waypointIndex} version=${after.route.version} " +
                "replans=${before?.replans}->${after.replans} elapsed=${after.elapsed} " +
                "reason=${after.lastReplanReason} paused=${before?.paused}->${after.paused}",
        )
    }

    fun activation(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        action: String,
        recoverMode: String,
        reason: String,
        targetId: String?,
    ) {
        engine.customData["astd_phase_activation_${ship.id}"] = mapOf(
            "time" to engine.getTotalElapsedTime(false),
            "action" to action,
            "recoverMode" to recoverMode,
            "reason" to reason,
            "targetId" to targetId,
            "hardFlux" to ship.fluxTracker.hardFlux,
            "maxFlux" to ship.maxFlux,
            "timeMult" to ship.mutableStats.timeMult.modifiedValue,
            "weapons" to ship.allWeapons.map { weapon ->
                buildMap {
                    put("weapon", weapon)
                    put("remaining", weapon.cooldownRemaining)
                    put("refireDelay", weapon.refireDelay)
                    put("cooldown", weapon.cooldown)
                    put("inBurst", weapon.isInBurst)
                    put("naturalCooldownRate", GravityPhaseRecovery.naturalCooldownRate(ship, weapon))
                    put("usesAmmo", weapon.usesAmmo())
                    put("ammo", weapon.ammo)
                    put("requiredAmmo", GravityPhaseRecovery.requiredAmmo(weapon))
                    if (weapon.usesAmmo()) {
                        put("maxAmmo", weapon.ammoTracker.maxAmmo)
                        put("reloadProgress", weapon.ammoTracker.reloadProgress)
                        put("reloadSize", weapon.ammoTracker.reloadSize)
                        put("ammoPerSecond", weapon.ammoTracker.ammoPerSecond)
                        put("naturalAmmoRegenMult", GravityPhaseRecovery.naturalAmmoRegenMult(ship, weapon))
                    } else {
                        put("reloadProgress", 0f)
                        put("reloadSize", 0f)
                        put("ammoPerSecond", 0f)
                        put("naturalAmmoRegenMult", 0f)
                    }
                }
            },
        )
    }

    fun transition(engine: CombatEngineAPI, ship: ShipAPI, action: String, reason: String) {
        val key = GravityPhaseCloakAI.diagnosticsKey(ship.id)
        val current = engine.customData[key] as? Map<String, Any> ?: return
        engine.customData[key] = current + mapOf(
            "action" to action,
            "reason" to reason,
            "sampleTime" to engine.getTotalElapsedTime(false),
        )
    }

    private var lastRecoveryState = ""
    private var lastRecoveryLogTime = Float.NEGATIVE_INFINITY

    fun recovery(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        mode: cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoverMode,
        assessment: cn.kasuminova.astd.combat.shipsystems.base.PhaseRecovery,
        execution: cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryExecution,
        attack: RecoveredAttack,
    ) {
        val key = GravityPhaseCloakAI.diagnosticsKey(ship.id)
        val current = engine.customData[key] as? Map<String, Any> ?: emptyMap()
        val values = mapOf(
            "recoveredAttackState" to attack.state.name,
            "recoveredAttackReason" to attack.reason.name,
            "recoveredAttackPending" to attack.pendingWeapons.joinToString(",") { "${it.slot?.id}:${it.id}" },
            "recoveredAttackElapsedLocal" to attack.elapsedLocal,
            "recoveredAttackCost" to attack.attackCost,
            "recoveredAttackReady" to attack.canAttack,
            "recoveredAttackAffordable" to attack.budgetSufficient,
            "recoverMode" to mode.name,
            "recoveryCandidateMode" to assessment.mode.name,
            "recoveryState" to execution.state.name,
            "recoveryReason" to execution.reason.name,
            "recoveryCommittedMode" to execution.mode.name,
            "recoveryTargetId" to execution.targetId,
            "recoveryElapsedLocal" to execution.elapsedLocal,
            "recoveryCooldownRemaining" to execution.cooldownRemaining,
            "recoveryObjects" to execution.objects,
            "recoveryAwaitingSurface" to execution.awaitingSurface,
            "recoveryAwaitingAttack" to execution.awaitingAttack,
            "recoveryNaturalDuration" to assessment.naturalDuration,
            "recoveryVentDuration" to assessment.ventDuration,
            "recoveryPhaseDuration" to assessment.phaseCandidateDuration,
            "recoveryPhaseCandidateReason" to assessment.phaseCandidateReason,
            "autoFireSuppressed" to ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_AUTOFIRE_NON_ESSENTIAL_GROUPS),
            "cloakState" to ship.phaseCloak.state.name,
        )
        engine.customData[key] = current + values
        val state = "${execution.state}/${execution.reason}/$mode/${assessment.mode}"
        val time = engine.getTotalElapsedTime(false)
        if (state != lastRecoveryState || time >= lastRecoveryLogTime + 1f) {
            log.info("[GravityPhaseRecovery] ship=${ship.id} time=$time $values")
            lastRecoveryState = state
            lastRecoveryLogTime = time
        }
    }

    fun publish(engine: CombatEngineAPI, ship: ShipAPI, diagnostics: PhaseDiagnostics) {
        val values = mutableMapOf<String, Any>(
            "action" to diagnostics.action,
            "reason" to diagnostics.reason,
            "targetId" to diagnostics.targetId,
            "pathStage" to diagnostics.pathStage,
            "waypointX" to diagnostics.waypointX,
            "waypointY" to diagnostics.waypointY,
            "routeReplans" to diagnostics.routeReplans,
            "routeVersion" to diagnostics.routeVersion,
            "routeStage" to diagnostics.routeStage,
            "routeReplanReason" to diagnostics.routeReplanReason,
            "routeCompleted" to diagnostics.routeCompleted,
            "routeFailed" to diagnostics.routeFailed,
            "routeFailureReason" to diagnostics.routeFailureReason,
            "routeFailureCause" to diagnostics.routeFailureCause,
            "routeProgress" to diagnostics.routeProgress,
            "routeLateralTravel" to diagnostics.routeLateralTravel,
            "routeStageTravel" to diagnostics.routeStageTravel,
            "targetDistance" to diagnostics.targetDistance,
            "effectiveWeaponRange" to diagnostics.effectiveWeaponRange,
            "hardFlux" to diagnostics.hardFlux,
            "peakHardFlux" to diagnostics.peakHardFlux,
            "softFlux" to diagnostics.softFlux,
            "surfaceLoss" to diagnostics.surfaceLoss,
            "attackInRange" to diagnostics.attackInRange,
            "attackCanFire" to diagnostics.attackCanFire,
            "sampleTime" to diagnostics.sampleTime,
            "remainingBurstCost" to diagnostics.remainingBurstCost,
            "activeBurstCost" to diagnostics.activeBurstCost,
            "costUncertainties" to diagnostics.costUncertainties,
            "plannedPhaseCost" to diagnostics.plannedPhaseCost,
            "routeElapsed" to diagnostics.routeElapsed,
            "routeMaxElapsed" to diagnostics.routeMaxElapsed,
            "routeLength" to diagnostics.routeLength,
            "shipSpeed" to diagnostics.shipSpeed,
            "routeSideResults" to diagnostics.routeSideResults,
            "inTargetRearArc" to diagnostics.inTargetRearArc,
            "targetFrontCoverage" to diagnostics.targetFrontCoverage,
            "phaseActiveTime" to diagnostics.phaseActiveTime,
            "venting" to ship.fluxTracker.isVenting,
            "cloakState" to (ship.phaseCloak?.state?.name ?: "NONE"),
            "maxFlux" to ship.maxFlux,
            "doNotVent" to ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT),
            "autoFireSuppressed" to ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_AUTOFIRE_NON_ESSENTIAL_GROUPS),
        )
        engine.customData[GravityPhaseCloakAI.diagnosticsKey(ship.id)] = values
        if (diagnostics.routeFailureReason != "NONE") {
            log.info(
                "[GravityPhaseAI] routePlanning sideResults=${diagnostics.routeSideResults} " +
                    "inputs=${engine.customData["astd_phase_route_planning_${ship.id}"]}",
            )
        }
        if (diagnostics.routeFailureReason == "BOUNDARY") {
            val speed = ship.velocity.length()
            val stopTime = if (ship.mutableStats.deceleration.modifiedValue > 0f) {
                speed / ship.mutableStats.deceleration.modifiedValue
            } else 0.5f
            val coastScale = 0.5f + stopTime * 0.5f
            val dx = diagnostics.waypointX - ship.location.x
            val dy = diagnostics.waypointY - ship.location.y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            val steerScale = 0.5f * ship.mutableStats.acceleration.modifiedValue * 0.5f * 0.5f /
                if (length > 0.001f) length else 1f
            log.info(
                "[GravityPhaseAI] boundaryDetail radius=${ship.collisionRadius} " +
                    "failurePoint=${engine.customData["astd_phase_boundary_${ship.id}"]} " +
                    "position=${ship.location.x},${ship.location.y} " +
                    "coast=${ship.location.x + ship.velocity.x * coastScale}," +
                    "${ship.location.y + ship.velocity.y * coastScale} " +
                    "steer=${ship.location.x + ship.velocity.x * 0.5f + dx * steerScale}," +
                    "${ship.location.y + ship.velocity.y * 0.5f + dy * steerScale} " +
                    "bounds=${-engine.mapWidth / 2f},${engine.mapWidth / 2f}," +
                    "${-engine.mapHeight / 2f},${engine.mapHeight / 2f}",
            )
        }
        if (diagnostics.action != lastAction || diagnostics.reason != lastReason ||
            diagnostics.sampleTime >= lastLogTime + 1f
        ) {
            log.info(
                "[GravityPhaseAI] ${ship.name} action=${diagnostics.action} " +
                    "reason=${diagnostics.reason} target=${diagnostics.targetId} " +
                    "stage=${diagnostics.pathStage} replans=${diagnostics.routeReplans} " +
                    "routeVersion=${diagnostics.routeVersion} replanReason=${diagnostics.routeReplanReason} " +
                    "routeElapsed=${diagnostics.routeElapsed}/${diagnostics.routeMaxElapsed} " +
                    "routeLength=${diagnostics.routeLength} shipSpeed=${diagnostics.shipSpeed} " +
                    "routeProgress=${diagnostics.routeProgress} position=${ship.location.x},${ship.location.y} " +
                    "velocity=${ship.velocity.x},${ship.velocity.y} " +
                    "accel=${ship.mutableStats.acceleration.modifiedValue} " +
                    "decel=${ship.mutableStats.deceleration.modifiedValue} " +
                    "map=${engine.mapWidth},${engine.mapHeight} " +
                    "waypoint=${diagnostics.waypointX},${diagnostics.waypointY} " +
                    "routeFailure=${diagnostics.routeFailureReason} " +
                    "phaseActiveTime=${diagnostics.phaseActiveTime} " +
                    "burstCost=${diagnostics.remainingBurstCost} phaseCost=${diagnostics.plannedPhaseCost}",
            )
            lastAction = diagnostics.action
            lastReason = diagnostics.reason
            lastLogTime = diagnostics.sampleTime
        }
    }
}
