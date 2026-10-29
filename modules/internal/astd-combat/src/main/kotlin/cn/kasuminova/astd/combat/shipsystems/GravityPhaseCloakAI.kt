package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseAction
import cn.kasuminova.astd.combat.shipsystems.base.PhaseDanger
import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecision
import cn.kasuminova.astd.combat.shipsystems.base.PhaseDiagnostics
import cn.kasuminova.astd.combat.shipsystems.base.PhaseFriendlyFire
import cn.kasuminova.astd.combat.shipsystems.base.PhaseMovement
import cn.kasuminova.astd.combat.shipsystems.base.PhasePlanner
import cn.kasuminova.astd.combat.shipsystems.base.PhasePosition
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoverMode
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteLimits
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRoutePlanner
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteReason
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteState
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteTarget
import cn.kasuminova.astd.combat.shipsystems.base.PhaseTargeting
import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat
import cn.kasuminova.astd.combat.shipsystems.base.WeaponTracker
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import org.lwjgl.util.vector.Vector2f

class GravityPhaseCloakAI : ShipSystemAIScript {
    companion object {
        private const val PLAN_INTERVAL = 0.2f
        private const val TOGGLE_GUARD = 0.5f

        /** 已承诺绕后路线等待跨周期恢复航速的上界（战斗秒），超时后强制验证并有界退出。 */
        private const val RECOVERY_ROUTE_WAIT_LIMIT = 12f

        fun diagnosticsKey(shipId: String): String = "astd_phase_diagnostics_$shipId"
    }

    private var ship: ShipAPI? = null
    private var engine: CombatEngineAPI? = null
    private val snapshot = GravityPhaseSnapshot()
    private val planner = PhasePlanner()
    private val threat = PhaseThreat()
    private val recovery = GravityPhaseRecovery()
    private val telemetry = PhaseTelemetry(this)
    private var fireWeaponTracker = WeaponTracker()
    private var fireTrackedShip: ShipAPI? = null
    private val previousCooldowns = java.util.IdentityHashMap<com.fs.starfarer.api.combat.WeaponAPI, Float>()
    private var route: PhaseRouteState? = null
    private var finishedRouteForPublish: PhaseRouteState? = null
    private var routePlanningFailure: PhaseRouteReason? = null
    private var recoveryRouteExhausted = false
    private var lastDecision = PhaseDecision(PhaseAction.TRAVEL, reason = "初始化")
    private var planElapsed = PLAN_INTERVAL
    private var lastPlanBattleTime = Float.NaN
    private var phaseActiveTime = 0f
    private var timeSinceUnphase = 2f
    private var toggleGuard = TOGGLE_GUARD
    private var lastHardFlux = 0f
    private var peakHardFlux = 0f
    private var controlledMovement = false
    private var suppressingAutoFire = false
    private var surfacePending = false
    private var surfacePendingElapsed = 0f
    private var recoveryFireSafe = false
    private var recoveryFireTargetId: String? = null
    private var reservedOutputCost = 0f
    private var recoveryRouteWaitElapsed = 0f

    override fun init(
        ship: ShipAPI,
        system: ShipSystemAPI,
        flags: ShipwideAIFlags,
        engine: CombatEngineAPI,
    ) {
        this.ship = ship
        this.engine = engine
        lastHardFlux = ship.fluxTracker.hardFlux
        peakHardFlux = lastHardFlux
    }

    @Synchronized
    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = ship ?: return
        val engine = engine ?: return
        if (engine.isPaused || ship.isHulk || !ship.isAlive) return
        val cloak = ship.phaseCloak ?: return
        val phased = cloak.state == ShipSystemAPI.SystemState.IN ||
            cloak.state == ShipSystemAPI.SystemState.ACTIVE
        val chargingDown = cloak.state == ShipSystemAPI.SystemState.OUT
        if (!phased) {
            surfacePending = false
            surfacePendingElapsed = 0f
        } else if (surfacePending) {
            surfacePendingElapsed += amount
            if (surfacePendingElapsed >= 1f) {
                surfacePending = false
                surfacePendingElapsed = 0f
                lastDecision = lastDecision.copy(
                    surface = false,
                    customMovement = false,
                    reason = "上浮命令未确认，重新评估",
                )
                telemetry.transition(engine, ship, lastDecision.action.name, lastDecision.reason)
            }
        }
        updateClocks(ship, phased, amount)
        val waitingRouteRecovery = !phased &&
            route?.let { !it.finished && it.paused && it.recoveryRequired } == true
        recoveryRouteWaitElapsed = when {
            !waitingRouteRecovery -> 0f
            ship.fluxTracker.isVenting -> recoveryRouteWaitElapsed
            else -> recoveryRouteWaitElapsed + amount
        }
        recovery.advance(ship, cloak, amount)
        val hardFlux = ship.fluxTracker.hardFlux
        val hardFluxSpent = (hardFlux - lastHardFlux).coerceAtLeast(0f)
        lastHardFlux = hardFlux
        peakHardFlux = maxOf(peakHardFlux, hardFlux)
        val unifiedTarget = recovery.committedTarget(ship) ?: PhaseTargeting.selectTarget(engine, ship, target)
        val forecast = threat.sample(
            engine,
            ship,
            PhaseThreat.Window(0f, 1.0f),
        )
        if (forecast == null) {
            recoveryFireSafe = false
            applyFireGate(ship, cloak, unifiedTarget, amount)
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, 0.3f)
            ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX)
            advanceMovement(ship, engine, unifiedTarget, amount, hardFluxSpent, false)
            return
        }
        val danger = danger(engine, ship, forecast)
        if (danger.lethalSoon || danger.significantSoon || danger.significantNear ||
            unifiedTarget?.id != recoveryFireTargetId
        ) {
            recoveryFireSafe = false
        }
        planElapsed += amount
        var cooldownStarted = false
        for (weapon in ship.allWeapons) {
            val remaining = weapon.cooldownRemaining
            val previous = previousCooldowns.put(weapon, remaining)
            if (previous != null && previous <= 0f && remaining > 0f && !weapon.isInBurst &&
                unifiedTarget != null && PhaseTargeting.usableAttackWeapon(weapon) &&
                PhaseTargeting.inRangeAndArc(weapon, unifiedTarget)
            ) {
                cooldownStarted = true
            }
        }
        var immediatePlan = cooldownStarted && recovery.canCommit && !ship.isRetreating && !chargingDown &&
            !surfacePending && !lastDecision.surface && lastDecision.action != PhaseAction.EVADE

        val protectOutput = unifiedTarget?.let { recovery.effectiveOutput(it) } == true
        if (phased || chargingDown || planElapsed < PLAN_INTERVAL &&
            (lastDecision.suppressVent || protectOutput && !lastDecision.vent)
        ) {
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, 0.3f)
            ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX)
        }
        if (ship.isRetreating) route = null
        val friendlyRisk = danger.friendlyCatchLoss >=
            (danger.surfaceLoss * 1.25f).coerceAtLeast(ship.maxHitpoints * 0.03f)
        if (danger.lethalSoon && (!friendlyRisk || ship.hitpoints < ship.maxHitpoints * 0.25f)) {
            route = route?.pause()
            if (!phased && !chargingDown && cloak.state == ShipSystemAPI.SystemState.IDLE &&
                cloak.canBeActivated() && toggleGuard >= TOGGLE_GUARD
            ) {
                telemetry.activation(engine, ship, "EVADE", "NONE", "紧急规避", unifiedTarget?.id)
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
                toggleGuard = 0f
                lastDecision = PhaseDecision(PhaseAction.EVADE, suppressVent = true, reason = "紧急规避")
                immediatePlan = true
            }
        }

        if (planElapsed >= PLAN_INTERVAL || immediatePlan) {
            val elapsed = planElapsed
            planElapsed = 0f
            val assessment = recovery.evaluate(engine, ship, cloak, unifiedTarget, danger)
            if (chargingDown || (phased && surfacePending)) {
                route = route?.pause()
                lastDecision = lastDecision.copy(
                    dive = false,
                    surface = false,
                    customMovement = false,
                    suppressVent = true,
                    reason = if (chargingDown) "充出过渡，等待普通战斗状态" else "已发出上浮命令，等待实际充出",
                )
                telemetry.transition(engine, ship, lastDecision.action.name, lastDecision.reason)
            } else {
                if (route != null && (unifiedTarget == null || route?.route?.target?.id != unifiedTarget.id)) {
                    route = null
                }
                val surfaceUnsafe = snapshot.unphaseUnsafe(engine, ship)
                if (unifiedTarget != null && !surfaceUnsafe) {
                    val canAttack = ship.allWeapons.any {
                        PhaseTargeting.usableAttackWeapon(it) && PhaseTargeting.weaponCanAttack(it, unifiedTarget)
                    }
                    route = route?.completeAttack(
                        GravityPhaseMovement.point(ship.location), GravityPhaseMovement.target(unifiedTarget), canAttack,
                    )
                }
                val currentRoute = route
                if (currentRoute != null && !currentRoute.finished && !currentRoute.paused && unifiedTarget != null &&
                    !surfaceUnsafe
                ) {
                    val replanned = PhaseRoutePlanner.replan(
                        currentRoute,
                        GravityPhaseMovement.request(
                            engine, ship, unifiedTarget, currentRoute.route.attackRange,
                            maxElapsed = currentRoute.limits.maxElapsed,
                            continuation = !currentRoute.route.recovery,
                        ),
                        GravityPhaseMovement.space(engine, ship, unifiedTarget, phasedRoute = route?.route?.recovery == false),
                    )
                    telemetry.routeTrace(engine, ship, "REPLAN", currentRoute, replanned)
                    route = replanned
                } else if (currentRoute != null && !currentRoute.finished && currentRoute.paused &&
                    currentRoute.recoveryRequired && !phased && unifiedTarget != null &&
                    (hardFlux + cloak.fluxPerUse < ship.maxFlux * 0.30f ||
                        recoveryRouteWaitElapsed >= RECOVERY_ROUTE_WAIT_LIMIT)
                ) {
                    val resumed = currentRoute.resume(
                        GravityPhaseMovement.request(
                            engine, ship, unifiedTarget, currentRoute.route.attackRange,
                            maxElapsed = currentRoute.limits.maxElapsed,
                            continuation = !currentRoute.route.recovery,
                        ),
                        GravityPhaseMovement.space(engine, ship, unifiedTarget, phasedRoute = route?.route?.recovery == false),
                    )
                    telemetry.routeTrace(engine, ship, "REPLAN", currentRoute, resumed)
                    route = if (resumed.recoveryRequired) {
                        resumed.copy(failure = PhaseRouteReason.TIME_LIMIT)
                    } else {
                        resumed
                    }
                }
                val routeStatus = PhaseRouteStatus(
                    active = route?.finished == false,
                    complete = route?.completed == true,
                    failed = route?.failure != null || routePlanningFailure != null,
                    recoveryRequired = route?.recoveryRequired == true,
                    stage = route?.takeUnless { it.finished }?.waypoint?.stage,
                    progress = route?.progress ?: 0f,
                )
                val situation = snapshot.sample(
                    engine, ship, cloak, unifiedTarget, phased, phaseActiveTime, timeSinceUnphase,
                    routeStatus, danger, assessment.recovery, assessment.remainingBurstCost,
                    unifiedTarget?.let { recovery.effectiveOutput(it) } == true,
                    recovery.hardFluxRecoveryActive,
                ).copy(
                    protectingBurst = recovery.protectingBurst(unifiedTarget),
                    recoveredAttackReady = assessment.recoveredAttack.canAttack &&
                        assessment.recoveredAttack.budgetSufficient && assessment.recoveredAttack.fullAmmo,
                )
                recoveryFireSafe = situation.attack.hasWeapons && situation.attack.inRange &&
                    !situation.routeRecoveryRequired &&
                    !situation.danger.lethalSoon && !situation.danger.significantSoon &&
                    !situation.danger.significantNear
                recoveryFireTargetId = unifiedTarget?.id
                val battleNow = engine.getTotalElapsedTime(false)
                val battleElapsed = if (lastPlanBattleTime.isNaN()) elapsed else {
                    (battleNow - lastPlanBattleTime).coerceAtLeast(0f)
                }
                lastPlanBattleTime = battleNow
                var decision = planner.decide(situation, elapsed, battleElapsed)
                routePlanningFailure = null
                decision = prepareRoute(ship, engine, unifiedTarget, situation, decision)
                applyDecision(ship, situation, decision, unifiedTarget)
                lastDecision = decision
                publish(engine, ship, situation, decision, assessment.activeBurstCost,
                    assessment.costUncertainties.joinToString(",") { it.name })
            }
            applyFireGate(ship, cloak, unifiedTarget, amount)
            telemetry.recovery(engine, ship, lastDecision.recoverMode, assessment.recovery, recovery.execution, recovery.recoveredAttack)
        } else {
            applyFireGate(ship, cloak, unifiedTarget, amount)
        }
        advanceMovement(ship, engine, unifiedTarget, amount, hardFluxSpent, protectOutput)
    }

    private fun updateClocks(ship: ShipAPI, phased: Boolean, amount: Float) {
        if (phased) {
            phaseActiveTime += amount
            timeSinceUnphase = 0f
        } else {
            timeSinceUnphase += amount
            val state = ship.phaseCloak?.state
            if (state == ShipSystemAPI.SystemState.IDLE || state == ShipSystemAPI.SystemState.COOLDOWN) {
                phaseActiveTime = 0f
            }
        }
        toggleGuard += amount
    }

    private fun advanceMovement(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        target: ShipAPI?,
        amount: Float,
        hardFluxSpent: Float,
        protectOutput: Boolean,
    ) {
        if (target == null) {
            route = null
            val flagged = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MOVEMENT_DEST) as? Vector2f
            val position = GravityPhaseMovement.point(ship.location)
            val destination = flagged?.let(GravityPhaseMovement::point) ?: position
            executeDirect(ship, engine, null, clamp(destination, engine, ship))
            return
        }
        val overlapEvade = lastDecision.action == PhaseAction.EVADE &&
            lastDecision.reason == "上浮位置重叠，先脱离阻塞"
        val current = route
        val routeClockMatches = current?.route?.recovery == true ||
            ship.phaseCloak.state == ShipSystemAPI.SystemState.IN || ship.phaseCloak.state == ShipSystemAPI.SystemState.ACTIVE
        val evadeFollowsRoute = lastDecision.action == PhaseAction.EVADE && !overlapEvade &&
            current != null && !current.finished && !current.route.recovery &&
            routeClockMatches && lastDecision.customMovement
        if (lastDecision.action == PhaseAction.EVADE && !overlapEvade && !evadeFollowsRoute) {
            route = route?.pause()
            executeDirect(ship, engine, target, evadeDestination(ship, engine, target))
            return
        }
        val followRoute = current != null && !current.finished && routeClockMatches &&
            lastDecision.customMovement && !lastDecision.surface &&
            (lastDecision.action == PhaseAction.FLANK || lastDecision.action == PhaseAction.RECOVER || overlapEvade ||
                evadeFollowsRoute ||
                lastDecision.action == PhaseAction.ATTACK && !current.route.recovery)
        if (followRoute) {
            val resumed = if (current.paused) {
                if (snapshot.unphaseUnsafe(engine, ship)) {
                    val position = GravityPhaseMovement.point(ship.location)
                    current.copy(paused = false, lastPosition = position, lateralOrigin = position)
                } else {
                    current.resume(
                        GravityPhaseMovement.request(
                            engine, ship, target, current.route.attackRange,
                            maxElapsed = current.limits.maxElapsed,
                            continuation = !current.route.recovery,
                        ),
                        GravityPhaseMovement.space(engine, ship, target, phasedRoute = route?.route?.recovery == false),
                    )
                }
            } else current
            if (resumed.recoveryRequired) {
                route = resumed.pause()
            } else {
                route = resumed
                val canAttack = ship.allWeapons.any {
                    PhaseTargeting.usableAttackWeapon(it) && PhaseTargeting.weaponCanAttack(it, target)
                }
                engine.customData.remove("astd_phase_boundary_${ship.id}")
                val result = PhaseMovement.step(
                    resumed,
                    GravityPhaseMovement.motion(ship, canAttack, snapshot.unphaseUnsafe(engine, ship)),
                    GravityPhaseMovement.target(target),
                    GravityPhaseMovement.space(engine, ship, target, phasedRoute = route?.route?.recovery == false),
                    amount,
                    hardFluxSpent,
                    onBoundary = { point -> engine.customData["astd_phase_boundary_${ship.id}"] = point },
                )
                route = result.state
                if (result.controlsMovement) {
                    PhaseMovement.execute(ship, result)
                    return
                }
            }
        }
        val completedAttackHandoff = route?.completed == true && route?.route?.recovery == false && !protectOutput &&
            (ship.isPhased || ship.phaseCloak.state == ShipSystemAPI.SystemState.OUT || timeSinceUnphase <= 1f)
        if (completedAttackHandoff) {
            val current = route!!
            val motion = GravityPhaseMovement.motion(ship, true, snapshot.unphaseUnsafe(engine, ship))
            val targetPoint = GravityPhaseMovement.target(target)
            val space = GravityPhaseMovement.space(engine, ship, target, phasedRoute = false, useCurrentClock = true)
            executeDirect(
                ship,
                engine,
                target,
                PhaseMovement.attackDestination(current, motion, targetPoint, space),
                PhaseMovement.attackDestinationVelocity(current, motion, targetPoint, space),
            )
            return
        }
        val destination = when {
            lastDecision.action == PhaseAction.RETREAT -> retreatDestination(ship, target)
            recovery.execution.awaitingAttack -> attackDestination(ship, engine, target)
            recovery.hardFluxRecoveryActive || lastDecision.action == PhaseAction.RECOVER ||
                lastDecision.action == PhaseAction.FLANK && !protectOutput ->
                recoveryDestination(
                    ship,
                    engine,
                    target,
                    recovery.hardFluxRecoveryActive || lastDecision.action == PhaseAction.FLANK &&
                        !protectOutput && route?.recoveryRequired != true,
                )
            else -> attackDestination(ship, engine, target)
        }
        executeDirect(ship, engine, target, destination)
    }

    private fun attackDestination(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI): PhasePosition {
        val position = GravityPhaseMovement.point(ship.location)
        val targetPoint = GravityPhaseMovement.target(target)
        val away = (position - targetPoint.position).unit().takeIf { it.length() > 0f } ?:
            PhasePosition.facing(target.facing) * -1f
        val side = movementSide(ship, target)
        val range = engagementRange(ship, target)
        if (range <= 0f) return clamp(position + away.left() * (side * 400f), engine, ship)
        val minimum = ship.collisionRadius + target.collisionRadius + 50f
        val near = (range * 0.65f).coerceAtLeast(minimum)
        val far = (range * 0.9f).coerceAtLeast(near + 50f)
        val distance = position.distance(targetPoint.position)
        val destination = when {
            distance < near -> position + away * 800f + away.left() * (side * 400f)
            distance > far -> targetPoint.position + targetPoint.velocity * 0.2f + away * far
            else -> sideRearDestination(position, targetPoint, range, minimum, side)
        }
        return clamp(destination, engine, ship)
    }

    private fun recoveryDestination(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        target: ShipAPI,
        withdraw: Boolean,
    ): PhasePosition {
        val position = GravityPhaseMovement.point(ship.location)
        val targetPoint = GravityPhaseMovement.target(target)
        val away = (position - targetPoint.position).unit().takeIf { it.length() > 0f } ?:
            PhasePosition.facing(target.facing) * -1f
        val range = engagementRange(ship, target).coerceAtLeast(600f)
        val side = movementSide(ship, target)
        val minimum = ship.collisionRadius + target.collisionRadius + 100f
        val near = (range * 0.65f).coerceAtLeast(minimum)
        val far = (range * 0.9f).coerceAtLeast(near + 50f)
        val distance = position.distance(targetPoint.position)
        val destination = when {
            distance < minimum -> position + away * 800f + away.left() * (side * 400f)
            withdraw && distance < range * 1.5f -> position + away * 800f + away.left() * (side * 600f)
            withdraw -> position + away.left() * (side * 800f)
            distance < near -> position + away * 600f + away.left() * (side * 400f)
            distance > far -> targetPoint.position + targetPoint.velocity * 0.2f + away * far
            else -> sideRearDestination(position, targetPoint, range, minimum, side)
        }
        return clamp(destination, engine, ship)
    }

    private fun engagementRange(ship: ShipAPI, target: ShipAPI): Float {
        val direct = PhaseTargeting.effectiveRange(ship)
        val support = PhaseTargeting.sampleSupport(ship, target)
        return when {
            support.available && support.targetInRange && support.effectiveRange > 0f -> support.effectiveRange
            direct > 0f -> direct
            support.effectiveRange > 0f -> support.effectiveRange
            else -> 0f
        }
    }

    private fun sideRearDestination(
        position: PhasePosition,
        target: PhaseRouteTarget,
        range: Float,
        minimum: Float,
        side: Float,
    ): PhasePosition {
        val offset = position - target.position
        val forward = PhasePosition.facing(target.facing)
        if (offset.length() > 0.001f && offset.unit().dot(forward) <= -0.34202014f) return position
        val anchorRange = (range * 0.8f).coerceAtLeast(minimum)
        return target.position - forward * (anchorRange * 0.45f) + forward.left() * (side * anchorRange * 0.75f)
    }

    private fun evadeDestination(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI): PhasePosition {
        val destination = PhaseMovement.evadeDestination(
            GravityPhaseMovement.point(ship.location),
            GravityPhaseMovement.target(target),
            ship.collisionRadius + target.collisionRadius + 100f,
            engagementRange(ship, target),
            movementSide(ship, target),
        )
        return clamp(destination, engine, ship)
    }

    private fun retreatDestination(ship: ShipAPI, target: ShipAPI): PhasePosition {
        val position = GravityPhaseMovement.point(ship.location)
        val targetPoint = GravityPhaseMovement.target(target)
        val away = (position - targetPoint.position).unit().takeIf { it.length() > 0f } ?:
            PhasePosition.facing(target.facing) * -1f
        val side = movementSide(ship, target)
        return position + away * 1600f + away.left() * (side * 400f)
    }

    private var movementTargetId: String? = null
    private var movementSide = 1f

    private fun movementSide(ship: ShipAPI, target: ShipAPI): Float {
        route?.takeUnless { it.finished }?.route?.side?.sign?.let { return it }
        if (movementTargetId != target.id) {
            movementTargetId = target.id
            val offset = GravityPhaseMovement.point(ship.location) - GravityPhaseMovement.point(target.location)
            movementSide = if (offset.dot(PhasePosition.facing(target.facing).left()) >= 0f) 1f else -1f
        }
        return movementSide
    }

    private fun clamp(point: PhasePosition, engine: CombatEngineAPI, ship: ShipAPI): PhasePosition {
        val margin = ship.collisionRadius + 50f
        val halfWidth = engine.mapWidth / 2f
        val halfHeight = engine.mapHeight / 2f
        return point.copy(
            x = if (halfWidth > margin) point.x.coerceIn(-halfWidth + margin, halfWidth - margin) else point.x,
            y = if (halfHeight > margin) point.y.coerceIn(-halfHeight + margin, halfHeight - margin) else point.y,
        )
    }

    private fun executeDirect(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        target: ShipAPI?,
        destination: PhasePosition,
        destinationVelocity: PhasePosition = PhasePosition.ZERO,
    ) {
        val motion = GravityPhaseMovement.motion(ship, false)
        val facePoint = target?.let { GravityPhaseMovement.point(it.location) } ?: destination
        if (target == null) {
            PhaseMovement.execute(ship, PhaseMovement.allowed(motion, destination, facePoint, terminal = false,
                destinationVelocity = destinationVelocity))
            return
        }
        if (lastDecision.action == PhaseAction.RETREAT) {
            PhaseMovement.execute(ship, PhaseMovement.allowed(motion, destination, facePoint, terminal = false,
                destinationVelocity = destinationVelocity))
            engine.customData["astd_phase_movement_${ship.id}"] = mapOf(
                "action" to lastDecision.action.name, "x" to destination.x, "y" to destination.y,
            )
            return
        }
        val targetPoint = GravityPhaseMovement.target(target)
        val space = GravityPhaseMovement.space(engine, ship, target, phasedRoute = ship.isPhased)
        val direction = (destination - motion.position).unit()
        val side = movementSide(ship, target)
        val away = (motion.position - targetPoint.position).unit().takeIf { it.length() > 0f } ?:
            (PhasePosition.ZERO - motion.position).unit()
        val inward = clamp(motion.position + (PhasePosition.ZERO - motion.position).unit() * 1200f, engine, ship)
        val escape = clamp(motion.position + away * 1200f, engine, ship)
        val candidates = listOf(
            destination,
            clamp(motion.position + direction.left() * (side * 800f), engine, ship),
            clamp(motion.position - direction.left() * (side * 800f), engine, ship),
            escape,
            inward,
        )
        for ((index, candidate) in candidates.withIndex()) {
            if (PhaseMovement.localRisk(motion, candidate, targetPoint, space) != null) continue
            PhaseMovement.execute(ship, PhaseMovement.allowed(motion, candidate, facePoint, terminal = false,
                destinationVelocity = if (index == 0) destinationVelocity else PhasePosition.ZERO))
            engine.customData["astd_phase_movement_${ship.id}"] = mapOf(
                "action" to lastDecision.action.name, "x" to candidate.x, "y" to candidate.y,
            )
            return
        }
        val outside = !space.bounds.contains(motion.position, motion.radius + 20f)
        val fallback = if (outside) inward else escape
        PhaseMovement.execute(ship, PhaseMovement.allowed(motion, fallback, facePoint, terminal = false))
        engine.customData["astd_phase_movement_${ship.id}"] = mapOf(
            "action" to lastDecision.action.name, "x" to fallback.x, "y" to fallback.y,
            "avoidance" to if (outside) "BOUNDARY_RETURN" else "ESCAPE",
        )
    }

    private fun prepareRoute(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        target: ShipAPI?,
        situation: cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation,
        decision: PhaseDecision,
    ): PhaseDecision {
        route?.takeIf { it.finished }?.let { finishedRouteForPublish = it }
        if (decision.action == PhaseAction.EVADE) {
            if (decision.reason != "上浮位置重叠，先脱离阻塞") route = route?.pause()
            return decision
        }
        if (decision.surface) {
            route = route?.pause()
            return decision.copy(customMovement = false)
        }
        if (situation.recovery.execution.awaitingAttack || decision.vent && situation.opportunity.vent) {
            route = route?.pause()
            return decision.copy(customMovement = false)
        }
        val phaseRecovery = decision.action == PhaseAction.RECOVER &&
            (decision.recoverMode == PhaseRecoverMode.PHASE_FLUX || decision.recoverMode == PhaseRecoverMode.PHASE_COOLDOWN)
        if (phaseRecovery && route != null) {
            if (route!!.route.recovery) {
                route = route?.pause()
                return decision.copy(customMovement = false)
            }
            return decision.copy(customMovement = !route!!.finished)
        }
        val needsFlank = decision.action == PhaseAction.FLANK
        val needsRecovery = decision.action == PhaseAction.RECOVER &&
            (decision.recoverMode == PhaseRecoverMode.NATURAL || decision.recoverMode == PhaseRecoverMode.VENT)
        if (decision.action == PhaseAction.ATTACK && route?.finished == false) {
            if (route!!.route.recovery || route!!.recoveryRequired || !situation.phased) {
                if (route!!.route.recovery && !situation.recovery.required) route = null
                route = route?.pause()
                return decision.copy(customMovement = false)
            }
            return decision.copy(customMovement = true)
        }
        if (decision.action != PhaseAction.RECOVER) recoveryRouteExhausted = false
        if (decision.action == PhaseAction.APPROACH && decision.customMovement) {
            return decision
        }
        if (!needsFlank && !needsRecovery) {
            if (decision.action != PhaseAction.ATTACK || route?.completed != true) route = null
            return decision
        }
        if (target == null) return decision.copy(customMovement = false)
        val active = route?.takeUnless { it.finished }
        if (active != null) {
            if (active.route.recovery == needsRecovery) {
                if (needsFlank) {
                    if (decision.dive && active.paused) {
                        val resumed = active.resume(
                            GravityPhaseMovement.request(
                                engine, ship, target, active.route.attackRange,
                                maxElapsed = active.limits.maxElapsed,
                                continuation = !active.route.recovery,
                            ),
                            GravityPhaseMovement.space(engine, ship, target, phasedRoute = !active.route.recovery),
                        )
                        telemetry.routeTrace(engine, ship, "REPLAN", active, resumed)
                        route = resumed
                        if (resumed.failure != null) {
                            return decision.copy(
                                action = PhaseAction.APPROACH,
                                dive = false,
                                customMovement = false,
                                reason = "暂停绕后路线重验证失败（${resumed.failure}），取消本次下潜",
                            )
                        }
                        if (resumed.recoveryRequired) {
                            route = resumed.pause()
                            return decision.copy(
                                dive = false,
                                customMovement = false,
                                reason = "暂停绕后路线仍需恢复，取消本次下潜",
                            )
                        }
                    }
                    if (!decision.customMovement) route = route?.pause()
                    return decision
                }
                return decision.copy(customMovement = true)
            }
            route = null
        }
        if (needsRecovery && recoveryRouteExhausted) {
            return decision.copy(
                vent = false,
                customMovement = false,
                recoverMode = PhaseRecoverMode.NATURAL,
                reason = "恢复路线预算已用尽，等待非相位恢复",
            )
        }
        val finishedRecovery = route?.takeIf { it.route.recovery && it.finished }
        if (finishedRecovery != null) {
            if (finishedRecovery.failure != null) {
                recoveryRouteExhausted = true
                routePlanningFailure = finishedRecovery.failure
                return decision.copy(
                    vent = false,
                    customMovement = false,
                    recoverMode = PhaseRecoverMode.NATURAL,
                    reason = "恢复路线失败，停止反复重建",
                )
            }
            return decision.copy(customMovement = false)
        }
        val request = GravityPhaseMovement.request(
            engine,
            ship,
            target,
            situation.effectiveWeaponRange * 0.85f,
            maxElapsed = if (needsRecovery) 12f else 36f,
        )
        val space = GravityPhaseMovement.space(engine, ship, target, phasedRoute = !needsRecovery)
        val planning = PhaseRoutePlanner.planDetailed(request, space, recovery = needsRecovery)
        val planned = planning.route
        if (planned == null) {
            routePlanningFailure = planning.failure ?: PhaseRouteReason.NO_ROUTE
            route = null
            return if (needsFlank) {
                decision.copy(
                    action = PhaseAction.APPROACH,
                    dive = false,
                    customMovement = false,
                    reason = "没有安全绕后路线（${planning.sideResults}），改为直接接近",
                )
            } else {
                recoveryRouteExhausted = true
                decision.copy(
                    vent = false,
                    customMovement = false,
                    recoverMode = PhaseRecoverMode.NATURAL,
                    reason = "没有安全侧向恢复位置，停止反复选路",
                )
            }
        }
        val previousRoute = route
        route = PhaseRouteState(
            route = planned,
            startedAt = engine.getTotalElapsedTime(false),
            limits = PhaseRouteLimits(
                maxElapsed = if (needsRecovery) 12f else 36f,
                maxHardFluxCost = if (needsRecovery) situation.maxFlux * 0.75f else Float.MAX_VALUE,
                maxNoBenefitTime = 2f,
                maxReplans = 2,
            ),
        )
        telemetry.routeTrace(engine, ship, "CREATE", previousRoute, route!!)
        val result = decision.copy(
            customMovement = decision.customMovement && (!needsFlank || situation.phased || decision.dive),
        )
        if (needsFlank && !result.customMovement) route = route?.pause()
        return result
    }

    private fun applyDecision(
        ship: ShipAPI,
        situation: cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation,
        decision: PhaseDecision,
        target: ShipAPI?,
    ) {
        val flags = ship.aiFlags
        val chargingDown = ship.phaseCloak?.state == ShipSystemAPI.SystemState.OUT
        if (decision.suppressVent || situation.phased || chargingDown) {
            flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, 0.3f)
            ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX)
        } else if (decision.vent) {
            flags.unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT)
            ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
        }
        if (!controlledMovement) {
            ship.shipAI?.cancelCurrentManeuver()
            ship.shipAI?.forceCircumstanceEvaluation()
            controlledMovement = true
        }
        if (decision.surface && recovery.execution.continuing) {
            recovery.exit(when {
                situation.routeComplete && decision.action == PhaseAction.ATTACK && !situation.recovery.required ->
                    cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.OBJECTIVES_MET
                situation.opportunity.attack || situation.opportunity.vent ->
                    cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.ATTACK_WINDOW
                situation.routeFailed -> cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.ROUTE_FAILED
                situation.phaseActiveTime >= 12f -> cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.TIME_LIMIT
                else -> cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.HARD_FLUX_LIMIT
            })
        }
        val currentPhaseRecovery = situation.phased && !decision.surface && !recovery.execution.awaitingSurface &&
            decision.action == PhaseAction.RECOVER && decision.recoverMode == situation.recovery.mode && when (decision.recoverMode) {
                PhaseRecoverMode.PHASE_FLUX -> situation.recovery.phaseFluxDuration > 0f
                PhaseRecoverMode.PHASE_COOLDOWN -> situation.recovery.phaseCooldownDuration > 0f
                else -> false
            }
        if (currentPhaseRecovery && recovery.canCommit) {
            recovery.commit(ship, ship.phaseCloak, target, decision.recoverMode)
        }
        if (toggleGuard < TOGGLE_GUARD) return
        val canToggle = when {
            decision.dive -> ship.phaseCloak?.canBeActivated() == true &&
                ship.phaseCloak.state == ShipSystemAPI.SystemState.IDLE
            decision.surface -> ship.phaseCloak?.state == ShipSystemAPI.SystemState.IN ||
                ship.phaseCloak?.state == ShipSystemAPI.SystemState.ACTIVE
            else -> false
        }
        if (canToggle) {
            if (decision.dive) engine?.let {
                telemetry.activation(it, ship, decision.action.name, decision.recoverMode.name, decision.reason, situation.targetId)
            }
            ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
            if (decision.dive && decision.action == PhaseAction.RECOVER && recovery.canCommit) {
                recovery.commit(ship, ship.phaseCloak, target, decision.recoverMode)
            }
            surfacePending = decision.surface
            surfacePendingElapsed = 0f
            toggleGuard = 0f
        }
    }

    private var handedOffTarget: ShipAPI? = null

    private fun applyFireGate(ship: ShipAPI, cloak: ShipSystemAPI, target: ShipAPI?, amount: Float) {
        if (fireTrackedShip !== ship) {
            fireWeaponTracker = WeaponTracker()
            fireTrackedShip = ship
        }
        val fireStates = fireWeaponTracker.sample(ship.allWeapons, amount)
        reservedOutputCost = fireStates.filter { state ->
            state.active && !(state.snapshot.isBeam && !state.snapshot.isBurstBeam)
        }.sumOf { state ->
            cn.kasuminova.astd.combat.shipsystems.base.WeaponCost.continueCost(state, 2f).amount.toDouble()
        }.toFloat()
        val handoff = target?.takeIf { recovery.execution.awaitingAttack && !ship.isRetreating }
        if (handoff != null && (handedOffTarget !== handoff || ship.shipTarget !== handoff)) {
            ship.shipTarget = handoff
            ship.shipAI?.setTargetOverride(handoff)
            ship.shipAI?.forceCircumstanceEvaluation()
        }
        handedOffTarget = handoff
        val waitingForVolley = handoff != null && lastDecision.action != PhaseAction.EVADE &&
            !ship.fluxTracker.isOverloadedOrVenting && recovery.recoveredAttack.elapsedLocal < 1f &&
            ship.allWeapons.any { weapon ->
                PhaseTargeting.usableAttackWeapon(weapon) &&
                    recovery.recoveredAttack.weapons.none { it === weapon } &&
                    !PhaseTargeting.weaponCanAttack(weapon, handoff)
            }
        val flux = ship.fluxTracker
        val safeLimit = (ship.maxFlux - cloak.fluxPerUse - cloak.fluxPerSecond * 1.5f - ship.maxFlux * 0.05f)
            .coerceAtLeast(0f)
        val recoveryTransition = recovery.execution.continuing || recovery.execution.awaitingSurface
        val recoveryWait = !lastDecision.customMovement &&
            (lastDecision.action == PhaseAction.RECOVER || lastDecision.action == PhaseAction.FLANK)
        val recoveryHold = !recovery.execution.awaitingAttack &&
            (recoveryWait && !recoveryFireSafe || route?.recoveryRequired == true)
        val suppress = !ship.isRetreating && (waitingForVolley || surfacePending || recoveryTransition ||
            toggleGuard == 0f && cloak.state == ShipSystemAPI.SystemState.IDLE ||
            cloak.state == ShipSystemAPI.SystemState.IN || cloak.state == ShipSystemAPI.SystemState.ACTIVE ||
            cloak.state == ShipSystemAPI.SystemState.OUT || lastDecision.vent || recoveryHold)
        val allowed = ship.allWeapons.filter { weapon ->
            val group = ship.getWeaponGroupFor(weapon)
            val autofire = group?.takeIf { it.isAutofiring }?.getAutofirePlugin(weapon)
            val pointDefense = weapon.hasAIHint(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD_ONLY) ||
                autofire?.shouldFire() == true && (autofire.targetMissile != null || autofire.targetShip?.isFighter == true)
            val charging = !weapon.isBeam && weapon.cooldownRemaining <= 0f &&
                (weapon.isPulse || weapon.chargeLevel < 1f) && weapon.chargeLevel > 0f
            pointDefense && (!weapon.isBeam || weapon.isBurstBeam) ||
                (!weapon.isBeam || weapon.isBurstBeam) && weapon.isInBurst || weapon.isFiring &&
                (weapon.isBurstBeam && weapon.chargeLevel > 0f || charging)
        }
        val blockedWeapons = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<com.fs.starfarer.api.combat.WeaponAPI, Boolean>(),
        )
        val blockReasons = java.util.IdentityHashMap<com.fs.starfarer.api.combat.WeaponAPI, String>()
        fun block(weapon: com.fs.starfarer.api.combat.WeaponAPI, reason: String) {
            if (weapon in allowed ||
                !(PhaseTargeting.attackWeapon(weapon) || weapon.isBeam && !weapon.isBurstBeam)
            ) return
            blockedWeapons.add(weapon)
            blockReasons[weapon] = reason
            weapon.setForceNoFireOneFrame(true)
        }
        fun volleyCost(weapon: com.fs.starfarer.api.combat.WeaponAPI): Float {
            val snapshot = cn.kasuminova.astd.combat.shipsystems.base.WeaponSnapshot.from(weapon)
            val cost = if (snapshot.isBeam && !snapshot.isBurstBeam && weapon.isFiring) {
                cn.kasuminova.astd.combat.shipsystems.base.WeaponCost.continuousBeamCost(
                    snapshot, snapshot.chargeupTime * (1f - snapshot.chargeLevel.coerceIn(0f, 1f)), 2f,
                )
            } else {
                cn.kasuminova.astd.combat.shipsystems.base.WeaponCost.nextVolleyCost(snapshot, 2f)
            }
            return cost.amount
        }
        fun newAttackReady(weapon: com.fs.starfarer.api.combat.WeaponAPI): Boolean {
            val continuousBeam = weapon.isBeam && !weapon.isBurstBeam && weapon.isFiring
            return continuousBeam || PhaseTargeting.usableAttackWeapon(weapon) && weapon.cooldownRemaining <= 0.05f
        }
        val flags = ship.aiFlags
        if (suppress && allowed.isEmpty()) {
            flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_AUTOFIRE_NON_ESSENTIAL_GROUPS, 0.5f)
            suppressingAutoFire = true
        } else if (suppressingAutoFire) {
            flags.unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_AUTOFIRE_NON_ESSENTIAL_GROUPS)
            suppressingAutoFire = false
        }
        for (weapon in ship.allWeapons) if (suppress) block(weapon, "SUPPRESSED")
        val manualFireReady = !flux.isOverloadedOrVenting &&
            (cloak.state == ShipSystemAPI.SystemState.IDLE || cloak.state == ShipSystemAPI.SystemState.COOLDOWN)
        val manualTarget = target?.takeIf { PhaseTargeting.isValidEnemy(ship, it) }
        var remainingBudget = safeLimit - flux.currFlux - reservedOutputCost
        val groupedWeapons = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<com.fs.starfarer.api.combat.WeaponAPI, Boolean>(),
        )
        if (!suppress) {
            ship.weaponGroupsCopy.forEachIndexed { index, group ->
                groupedWeapons.addAll(group.weaponsCopy)
                val ready = when {
                    group.isAutofiring -> group.weaponsCopy.filter { weapon ->
                        val autofire = group.getAutofirePlugin(weapon)
                        newAttackReady(weapon) && (autofire?.shouldFire() == true ||
                            weapon.isBeam && !weapon.isBurstBeam && weapon.isFiring)
                    }
                    manualFireReady && manualTarget != null ->
                        group.weaponsCopy.filter { PhaseTargeting.weaponCanAttack(it, manualTarget) }
                    else -> emptyList()
                }
                if (!group.isAutofiring) {
                    group.weaponsCopy.filter { it !in ready }.forEach { block(it, "NOT_READY") }
                }
                val newAttacks = ready.filter { it !in allowed }
                val newCost = newAttacks.fold(0f) { total, weapon -> total + volleyCost(weapon) }
                if (!newCost.isFinite() || !remainingBudget.isFinite() || newCost > remainingBudget) {
                    newAttacks.forEach { block(it, "BUDGET") }
                    return@forEachIndexed
                }
                remainingBudget -= newCost
                if (group.isAutofiring || ready.isEmpty() || manualTarget == null) return@forEachIndexed
                if (ship.shipTarget !== manualTarget) ship.shipTarget = manualTarget
                ship.giveCommand(ShipCommand.FIRE, manualTarget.location, index)
            }
            ship.allWeapons.filter { it !in groupedWeapons }.forEach { weapon ->
                if (!newAttackReady(weapon) || weapon in allowed) return@forEach
                val cost = volleyCost(weapon)
                if (!cost.isFinite() || !remainingBudget.isFinite() || cost > remainingBudget) {
                    block(weapon, "BUDGET")
                } else {
                    remainingBudget -= cost
                }
            }
        }
        if (planElapsed != 0f) return
        val gates = ship.allWeapons.map { weapon ->
            val autofire = ship.getWeaponGroupFor(weapon)?.getAutofirePlugin(weapon)
            mapOf(
                "weapon" to weapon.id, "slot" to weapon.slot?.id.orEmpty(),
                "blocked" to (weapon in blockedWeapons), "preservingOutput" to (weapon in allowed),
                "affordable" to (blockReasons[weapon] != "BUDGET"),
                "fireBlockReason" to blockReasons[weapon].orEmpty(),
                "cooldown" to weapon.cooldownRemaining, "charge" to weapon.chargeLevel,
                "inBurst" to weapon.isInBurst, "isFiring" to weapon.isFiring,
                "ammo" to weapon.ammo, "autofireTarget" to autofire?.targetShip?.id.orEmpty(),
                "autofireRequested" to (autofire?.shouldFire() == true),
                "inTargetArc" to (target != null && PhaseTargeting.inRangeAndArc(weapon, target)),
            )
        }
        engine?.let { active ->
            val key = diagnosticsKey(ship.id)
            val current = active.customData[key] as? Map<*, *> ?: emptyMap<Any, Any>()
            active.customData[key] = current + mapOf(
                "weaponGates" to gates, "shipTarget" to ship.shipTarget?.id.orEmpty(),
                "handoffTarget" to handoff?.id.orEmpty(), "fireBudgetRemaining" to remainingBudget,
                "reservedOutputCost" to reservedOutputCost,
            )
        }
    }

    private fun danger(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        forecast: PhaseThreat.Forecast,
    ): PhaseDanger {
        val totalLoss = forecast.total.durability
        val significantThreshold = (ship.maxHitpoints * 0.035f).coerceAtLeast(200f)
        val significantThreatKeys = forecast.significantThreatKeys(significantThreshold)
        return PhaseDanger(
            lethalSoon = forecast.lethal,
            significantSoon = totalLoss >= significantThreshold,
            significantNear = totalLoss >= (ship.maxHitpoints * 0.08f).coerceAtLeast(400f),
            surfaceLoss = totalLoss,
            friendlyCatchLoss = PhaseFriendlyFire.transferredLoss(engine, ship, forecast),
            threatKeys = forecast.threatKeys + forecast.carrierThreatKeys,
            activeThreatKeys = forecast.activeThreatKeys,
            significantThreatKeys = significantThreatKeys,
        )
    }

    private fun publish(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        situation: cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation,
        decision: PhaseDecision,
        activeBurstCost: Float,
        costUncertainties: String,
    ) {
        val finished = finishedRouteForPublish
        val current = finished ?: route
        val planningFailure = if (finished == null) routePlanningFailure else null
        val waypoint = current?.waypoint?.position
        telemetry.publish(
            engine,
            ship,
            PhaseDiagnostics(
                action = decision.action.name,
                reason = decision.reason,
                targetId = situation.targetId.orEmpty(),
                pathStage = current?.waypointIndex ?: -1,
                waypointX = waypoint?.x ?: 0f,
                waypointY = waypoint?.y ?: 0f,
                routeReplans = current?.replans ?: 0,
                routeVersion = current?.route?.version ?: 0,
                routeStage = current?.waypoint?.stage?.name ?: "NONE",
                routeReplanReason = current?.lastReplanReason?.name ?: "NONE",
                routeCompleted = current?.completed == true,
                routeFailed = current?.failure != null || planningFailure != null,
                routeFailureReason = current?.failure?.name ?: planningFailure?.name ?: "NONE",
                routeFailureCause = current?.failureCause?.name ?: "NONE",
                routeProgress = current?.progress ?: 0f,
                routeLateralTravel = current?.lateralTravel ?: 0f,
                routeStageTravel = current?.stageTravel ?: 0f,
                targetDistance = situation.targetDistance.takeIf { it.isFinite() } ?: -1f,
                effectiveWeaponRange = situation.effectiveWeaponRange,
                hardFlux = situation.hardFlux,
                peakHardFlux = peakHardFlux,
                softFlux = situation.softFlux,
                surfaceLoss = situation.danger.surfaceLoss,
                attackInRange = situation.attack.inRange,
                attackCanFire = situation.attack.canFire,
                sampleTime = engine.getTotalElapsedTime(false),
                remainingBurstCost = situation.attack.remainingBurstCost,
                activeBurstCost = activeBurstCost,
                costUncertainties = costUncertainties,
                plannedPhaseCost = situation.recovery.plannedPhaseCost,
                routeElapsed = current?.elapsed ?: 0f,
                routeMaxElapsed = current?.limits?.maxElapsed ?: 0f,
                routeLength = current?.route?.lengthBefore(current.route.waypoints.size) ?: 0f,
                shipSpeed = ship.mutableStats.maxSpeed.modifiedValue,
                routeSideResults = current?.lastSideResults.orEmpty(),
                inTargetRearArc = situation.inTargetRearArc,
                targetFrontCoverage = situation.targetFrontCoverage,
                phaseActiveTime = situation.phaseActiveTime,
            ),
        )
        val key = diagnosticsKey(ship.id)
        val values = engine.customData[key] as Map<*, *>
        engine.customData[key] = values + mapOf(
            "currentThreatCount" to situation.danger.threatKeys.size,
            "activeThreatCount" to situation.danger.activeThreatKeys.size,
            "committedThreatCount" to planner.committedThreatCount,
            "recoveryFireSafe" to recoveryFireSafe,
            "recoveryRouteWait" to recoveryRouteWaitElapsed,
            "supportAvailable" to situation.support.available,
            "supportRange" to situation.support.effectiveRange,
            "supportInRange" to situation.support.targetInRange,
            "supportEngaged" to situation.support.engaged,
            "opportunityAttack" to situation.opportunity.attack,
            "opportunityVent" to situation.opportunity.vent,
            "opportunityAttackImpact" to situation.opportunity.attackImpact,
            "opportunityVentImpact" to situation.opportunity.ventImpact,
            "opportunityWindow" to situation.opportunity.window,
        )
        finishedRouteForPublish = null
    }
}
