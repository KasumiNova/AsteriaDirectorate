package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class PhaseMotion(
    val position: PhasePosition,
    val velocity: PhasePosition,
    val facing: Float,
    val angularVelocity: Float,
    val radius: Float,
    val maxSpeed: Float,
    val acceleration: Float,
    val deceleration: Float,
    val strafeAcceleration: Float,
    val turnAcceleration: Float,
    val maxTurnRate: Float,
    val canAttackTarget: Boolean = false,
    val surfaceUnsafe: Boolean = false,
)

internal data class PhaseMovementTuning(
    val arrivalRadius: Float = 50f,
    val passCorridor: Float = 100f,
    val velocityDeadZone: Float = 5f,
    val headingDeadZone: Float = 3f,
    val angularVelocityDeadZone: Float = 0.5f,
    val safetyHorizon: Float = 0.5f,
    val clearance: Float = 20f,
)

internal data class PhaseMovementResult(
    val state: PhaseRouteState,
    val allowed: Set<ShipCommand>,
    val controlsMovement: Boolean,
)

internal object PhaseMovement {
    val commands: Set<ShipCommand> = setOf(
        ShipCommand.TURN_LEFT, ShipCommand.TURN_RIGHT,
        ShipCommand.STRAFE_LEFT, ShipCommand.STRAFE_RIGHT,
        ShipCommand.ACCELERATE, ShipCommand.ACCELERATE_BACKWARDS, ShipCommand.DECELERATE,
    )

    fun step(
        state: PhaseRouteState,
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
        amount: Float,
        hardFluxSpent: Float = 0f,
        tuning: PhaseMovementTuning = PhaseMovementTuning(),
        onBoundary: (PhasePosition) -> Unit = {},
    ): PhaseMovementResult {
        val tracked = state.trackTarget(target)
        val currentLateralTravel = tracked.lateralTravelAt(motion.position)
        val reachedWaypoint = tracked.waypointIndex < tracked.route.waypoints.lastIndex && reached(
            motion.position,
            tracked.route.segmentStart(tracked.waypointIndex),
            tracked.waypoint.position,
            tuning,
            arrivalRadius = if (tracked.waypoint.stage == PhaseRouteStage.LATERAL) 180f else tuning.arrivalRadius,
        )
        val terminalWaypoint = tracked.waypointIndex == tracked.route.waypoints.lastIndex && reached(
            motion.position,
            tracked.route.segmentStart(tracked.waypointIndex),
            tracked.waypoint.position,
            tuning,
        )
        val initialMovementState = attackCaptureState(tracked, motion, target, reachedWaypoint, space)
        val initialDestination = destination(initialMovementState, motion, target, space, tuning)
        val attackAreaAtEntry = tracked.route.inAttackArea(motion.position, target)
        val promoteToAttack = !tracked.route.recovery && tracked.waypoint.stage == PhaseRouteStage.SIDE_ENTRY &&
            tracked.stageTravel >= 50f && attackAreaAtEntry
        val advanceToSideEntry = !tracked.route.recovery && tracked.waypoint.stage == PhaseRouteStage.LATERAL &&
            currentLateralTravel >= 400f
        val completionIndex = when {
            promoteToAttack -> tracked.route.waypoints.lastIndex
            advanceToSideEntry -> tracked.waypointIndex + 1
            else -> tracked.waypointIndex
        }
        val lastAtEntry = completionIndex == tracked.route.waypoints.lastIndex
        val completedNow = lastAtEntry && if (tracked.route.recovery) {
            motion.position.distance(tracked.route.waypoints[completionIndex].position) <= tuning.arrivalRadius &&
                motion.velocity.length() <= tuning.velocityDeadZone
        } else {
            attackAreaAtEntry && motion.canAttackTarget && !motion.surfaceUnsafe
        }
        val terminalAlignment = reachedWaypoint || terminalWaypoint || promoteToAttack || advanceToSideEntry || completedNow
        var next = tracked.advance(amount, motion.position, hardFluxSpent, terminalAlignment, initialDestination)
            .copy(sideEntryCapture = initialMovementState.sideEntryCapture)
        if (completedNow) {
            next = next.copy(
                waypointIndex = completionIndex,
                routeProgress = max(next.routeProgress, next.route.lengthBefore(completionIndex)),
                noBenefitTime = 0f,
                completed = true,
                failure = null,
            )
            return PhaseMovementResult(next, emptySet(), false)
        }
        if (next.finished || next.paused) return PhaseMovementResult(next, emptySet(), false)
        if (!target.valid || target.id != next.route.target.id) {
            return cancelled(next, PhaseRouteReason.TARGET_LOST)
        }
        val previousWaypoint = next.waypointIndex
        if (promoteToAttack || advanceToSideEntry) {
            next = next.copy(
                waypointIndex = completionIndex,
                routeProgress = max(next.routeProgress, next.route.lengthBefore(completionIndex)),
            )
        } else if (reachedWaypoint && when (tracked.waypoint.stage) {
            PhaseRouteStage.LATERAL -> advanceToSideEntry
            PhaseRouteStage.SIDE_ENTRY -> tracked.stageTravel >= 50f
            else -> true
        }) {
            val completedLength = next.route.lengthBefore(next.waypointIndex + 1)
            next = next.copy(waypointIndex = next.waypointIndex + 1, routeProgress = max(next.routeProgress, completedLength))
        }
        if (next.waypointIndex != previousWaypoint) next = next.copy(noBenefitTime = 0f, stageTravel = 0f)
        val last = next.waypointIndex == next.route.waypoints.lastIndex
        val inAttackArea = next.route.inAttackArea(motion.position, target)
        val movementState = attackCaptureState(
            next, motion, target, reachedWaypoint && next.waypointIndex == previousWaypoint, space,
        )
        val destination = destination(movementState, motion, target, space, tuning)
        localRisk(motion, destination, target, space, tuning, onBoundary)?.let { return cancelled(next, it) }
        val arrived = if (next.route.recovery) {
            motion.position.distance(next.waypoint.position) <= tuning.arrivalRadius &&
                motion.velocity.length() <= tuning.velocityDeadZone
        } else inAttackArea && motion.canAttackTarget && !motion.surfaceUnsafe
        if (last && arrived) return PhaseMovementResult(next.copy(completed = true), emptySet(), false)
        val facePoint = target.position
        val terminal = last && (next.route.recovery || inAttackArea)
        return PhaseMovementResult(
            next.copy(sideEntryCapture = movementState.sideEntryCapture),
            allowed(
                motion, destination, facePoint, terminal, tuning,
                destinationVelocity(movementState, motion, target, space, tuning),
            ),
            true,
        )
    }

    private fun attackCaptureState(
        state: PhaseRouteState,
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        reachedWaypoint: Boolean,
        space: PhaseRouteSpace,
    ): PhaseRouteState {
        if (state.route.recovery || state.waypoint.stage != PhaseRouteStage.SIDE_ENTRY) return state
        if (state.sideEntryCapture) return state
        val distance = motion.position.distance(target.position)
        val attackRadius = state.route.waypoints.last().position.distance(target.position)
        val close = distance <= attackRadius * 1.2f
        val directPhaseCapture = !space.collides && state.stageTravel >= 50f && distance <= state.route.attackRange * 1.5f
        if (directPhaseCapture && !motion.surfaceUnsafe) return state.copy(sideEntryCapture = true)
        if (!reachedWaypoint && !motion.surfaceUnsafe && !close) return state
        if (!reachedWaypoint && state.stageTravel < 50f && !motion.surfaceUnsafe) return state
        return state.copy(waypointIndex = state.route.waypoints.lastIndex, sideEntryCapture = true)
    }

    private fun destinationVelocity(
        state: PhaseRouteState,
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
        tuning: PhaseMovementTuning = PhaseMovementTuning(),
    ): PhasePosition {
        if (state.route.recovery ||
            state.waypoint.stage != PhaseRouteStage.LATERAL && escapeDestination(motion, target, space) != null
        ) {
            return PhasePosition.ZERO
        }
        val timeScale = space.battleTimePerLocalSecond
        val targetVelocity = target.velocity * timeScale
        if (state.waypoint.stage == PhaseRouteStage.LATERAL) {
            val side = PhasePosition.facing(state.lateralFacing).left() * state.route.side.sign
            val base = destinationBase(state, motion, target, space)
            val remaining = (400f + tuning.arrivalRadius - state.lateralTravelAt(motion.position)).coerceAtLeast(0f)
            val baseLateral = (base - motion.position).dot(side)
            return if (remaining - baseLateral > 0f) {
                targetVelocity - side * targetVelocity.dot(side)
            } else {
                targetVelocity
            }
        }
        val angular = if (state.waypoint.stage != PhaseRouteStage.ATTACK) 0f else
            Math.toRadians((target.angularVelocity * timeScale).toDouble()).toFloat()
        val offset = destinationBase(state, motion, target, space) - target.position
        return targetVelocity + offset.left() * angular
    }

    private fun destination(
        state: PhaseRouteState,
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
        tuning: PhaseMovementTuning = PhaseMovementTuning(),
    ): PhasePosition {
        if (state.waypoint.stage == PhaseRouteStage.LATERAL) {
            val side = PhasePosition.facing(state.lateralFacing).left() * state.route.side.sign
            val base = destinationBase(state, motion, target, space)
            val remaining = (400f + tuning.arrivalRadius - state.lateralTravelAt(motion.position)).coerceAtLeast(0f)
            val baseLateral = (base - motion.position).dot(side)
            return base + side * (remaining - baseLateral).coerceAtLeast(0f)
        }
        val base = destinationBase(state, motion, target, space)
        if (state.route.recovery || escapeDestination(motion, target, space) != null) {
            return base
        }
        val eta = (motion.position.distance(base) / motion.maxSpeed.coerceAtLeast(1f) *
            space.battleTimePerLocalSecond).coerceAtLeast(0f)
        if (eta <= 0f) return base
        val offset = base - target.position
        return target.position + target.velocity * eta + offset
    }

    private fun destinationBase(
        state: PhaseRouteState,
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
    ): PhasePosition {
        val saved = state.waypoint.position
        val capturedSideEntry = state.waypoint.stage == PhaseRouteStage.SIDE_ENTRY && state.sideEntryCapture
        if (state.waypoint.stage != PhaseRouteStage.ATTACK && !capturedSideEntry) return saved
        escapeDestination(motion, target, space)?.let { return it }
        val forward = PhasePosition.facing(target.facing)
        val rear = forward * -1f
        val side = forward.left() * state.route.side.sign
        val attackPoint = state.route.waypoints.last().position
        val radius = attackPoint.distance(target.position).coerceAtLeast(motion.radius + target.radius + 50f)
        if (!state.route.inAttackArea(motion.position, target)) {
            return target.position + (rear + side).unit() * radius
        }
        val offset = motion.position - target.position
        val bearing = Math.toDegrees(atan2(offset.dot(side).toDouble(), offset.dot(rear).toDouble())).toFloat()
        val clearanceRatio = ((target.radius * 0.25f + 5f) / radius).coerceIn(0f, 0.95f)
        val minimumBearing = Math.toDegrees(kotlin.math.asin(clearanceRatio).toDouble()).toFloat()
        val maximumBearing = Math.toDegrees(kotlin.math.acos(clearanceRatio).toDouble()).toFloat().coerceAtMost(70f)
        val angle = Math.toRadians(bearing.coerceIn(minimumBearing, maximumBearing).toDouble())
        val direction = rear * kotlin.math.cos(angle).toFloat() + side * kotlin.math.sin(angle).toFloat()
        return target.position + direction * radius
    }

    fun attackDestination(
        state: PhaseRouteState,
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
    ): PhasePosition = destination(state.trackTarget(target), motion, target, space)

    fun attackDestinationVelocity(
        state: PhaseRouteState,
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
    ): PhasePosition = destinationVelocity(state.trackTarget(target), motion, target, space)

    fun evadeDestination(
        position: PhasePosition,
        target: PhaseRouteTarget,
        minimum: Float,
        range: Float,
        side: Float,
    ): PhasePosition {
        val away = (position - target.position).unit().takeIf { it.length() > 0f } ?:
            PhasePosition.facing(target.facing) * -1f
        val distance = position.distance(target.position)
        val far = (range * 0.9f).coerceAtLeast(minimum + 50f)
        val outside = range > 0f && distance > far
        val longitudinal = when {
            distance < minimum -> away * 300f
            outside -> away * -(distance - far).coerceIn(0f, 600f)
            else -> PhasePosition.ZERO
        }
        val tangent = away.left() * side
        val lateral = if (outside) 400f else 1000f
        return position + tangent * lateral + longitudinal
    }

    private fun escapeDestination(
        motion: PhaseMotion,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
    ): PhasePosition? {
        if (!motion.surfaceUnsafe) return null
        var nearest = Float.MAX_VALUE
        var escape: PhasePosition? = null
        val obstacles = space.obstacles + PhaseObstacle(target.position, target.radius, target.velocity)
        for (obstacle in obstacles) {
            val away = motion.position - obstacle.position
            val distance = away.length()
            val limit = (motion.radius + obstacle.radius) * 0.5f
            if (distance >= limit || distance >= nearest) continue
            nearest = distance
            escape = away.unit().takeIf { it.length() > 0f } ?: PhasePosition.facing(target.facing) * -1f
        }
        return escape?.let { motion.position + it * 600f }
    }

    fun reached(
        position: PhasePosition,
        start: PhasePosition,
        end: PhasePosition,
        tuning: PhaseMovementTuning,
        arrivalRadius: Float = tuning.arrivalRadius,
    ): Boolean {
        if (position.distance(end) <= arrivalRadius) return true
        val fraction = PhasePosition.segmentFraction(position, start, end)
        if (fraction < 1f) return false
        val projection = start + (end - start) * fraction
        return position.distance(projection) <= tuning.passCorridor
    }

    fun stoppingDistance(speed: Float, deceleration: Float): Float =
        if (deceleration > 0f) speed * speed / (2f * deceleration) else Float.POSITIVE_INFINITY

    fun allowed(
        motion: PhaseMotion,
        destination: PhasePosition,
        facePoint: PhasePosition = destination,
        terminal: Boolean = true,
        tuning: PhaseMovementTuning = PhaseMovementTuning(),
        destinationVelocity: PhasePosition = PhasePosition.ZERO,
    ): Set<ShipCommand> {
        val result = mutableSetOf<ShipCommand>()
        val delta = destination - motion.position
        val distance = delta.length()
        val forward = PhasePosition.facing(motion.facing)
        val left = forward.left()
        val braking = min(motion.deceleration, motion.strafeAcceleration).coerceAtLeast(0f)
        val remaining = if (terminal) max(0f, distance - tuning.arrivalRadius * 0.5f) else distance
        val desiredSpeed = if (terminal && distance <= tuning.arrivalRadius) 0f
            else min(motion.maxSpeed, sqrt(2f * braking * remaining))
        val desired = (delta.unit() * desiredSpeed + destinationVelocity).let {
            if (it.length() > motion.maxSpeed) it.unit() * motion.maxSpeed else it
        }
        val velocityError = desired - motion.velocity
        val forwardError = velocityError.dot(forward)
        val lateralError = velocityError.dot(left)
        val closingSpeed = (motion.velocity - destinationVelocity).dot(delta.unit())
        val stop = terminal && destinationVelocity.length() <= tuning.velocityDeadZone &&
            (distance <= tuning.arrivalRadius ||
                closingSpeed > tuning.velocityDeadZone && stoppingDistance(closingSpeed, braking) >= remaining)
        if (stop && motion.velocity.length() > tuning.velocityDeadZone) {
            result += ShipCommand.DECELERATE
        } else {
            when {
                forwardError > tuning.velocityDeadZone && motion.acceleration > 0f -> result += ShipCommand.ACCELERATE
                forwardError < -tuning.velocityDeadZone && motion.deceleration > 0f -> result += ShipCommand.ACCELERATE_BACKWARDS
            }
            when {
                lateralError > tuning.velocityDeadZone && motion.strafeAcceleration > 0f -> result += ShipCommand.STRAFE_LEFT
                lateralError < -tuning.velocityDeadZone && motion.strafeAcceleration > 0f -> result += ShipCommand.STRAFE_RIGHT
            }
        }
        val facingDelta = facePoint - motion.position
        if (facingDelta.length() > 0.001f) {
            val desiredFacing = Math.toDegrees(atan2(facingDelta.y.toDouble(), facingDelta.x.toDouble())).toFloat()
            val angle = PhasePosition.angleDelta(motion.facing, desiredFacing)
            val desiredAngular = if (abs(angle) <= tuning.headingDeadZone) 0f else {
                val magnitude = min(motion.maxTurnRate, sqrt(2f * motion.turnAcceleration.coerceAtLeast(0f) * abs(angle)))
                if (angle > 0f) magnitude else -magnitude
            }
            val error = desiredAngular - motion.angularVelocity
            if (motion.turnAcceleration > 0f) when {
                error > tuning.angularVelocityDeadZone -> result += ShipCommand.TURN_LEFT
                error < -tuning.angularVelocityDeadZone -> result += ShipCommand.TURN_RIGHT
            }
        }
        return result
    }

    fun localRisk(
        motion: PhaseMotion,
        destination: PhasePosition,
        target: PhaseRouteTarget,
        space: PhaseRouteSpace,
        tuning: PhaseMovementTuning = PhaseMovementTuning(),
        onBoundary: (PhasePosition) -> Unit = {},
    ): PhaseRouteReason? {
        val speed = motion.velocity.length()
        val stopTime = if (motion.deceleration > 0f) speed / motion.deceleration else tuning.safetyHorizon
        val horizon = (tuning.safetyHorizon + stopTime).coerceAtMost(2f)
        val steerDirection = (destination - motion.position).unit()
        val forward = PhasePosition.facing(motion.facing)
        val left = forward.left()
        val forwardInput = steerDirection.dot(forward)
        val forwardAcceleration = if (forwardInput >= 0f) motion.acceleration else motion.deceleration
        val steeringAcceleration = forward * (forwardInput * forwardAcceleration) +
            left * (steerDirection.dot(left) * motion.strafeAcceleration)
        val acceleratedVelocity = motion.velocity + steeringAcceleration * horizon
        val steerVelocity = if (acceleratedVelocity.length() > motion.maxSpeed) {
            acceleratedVelocity.unit() * motion.maxSpeed
        } else {
            acceleratedVelocity
        }
        val steer = motion.position + (motion.velocity + steerVelocity) * (horizon * 0.5f)
        val margin = motion.radius + tuning.clearance
        val outside = listOf(motion.position, steer).firstOrNull { !space.bounds.contains(it, margin) }
        if (outside != null) {
            onBoundary(outside)
            return PhaseRouteReason.BOUNDARY
        }
        if (!space.collides) return null
        val obstacles = space.obstacles + PhaseObstacle(target.position, target.radius, target.velocity)
        for (obstacle in obstacles) {
            val relativeStart = motion.position - obstacle.position
            val relativeEnd = steer - (obstacle.position + obstacle.velocity * (horizon * space.battleTimePerLocalSecond))
            val fraction = PhasePosition.segmentFraction(PhasePosition.ZERO, relativeStart, relativeEnd).coerceIn(0f, 1f)
            val startDistance = relativeStart.length()
            val closestDistance = (relativeStart + (relativeEnd - relativeStart) * fraction).length()
            if (closestDistance <= margin + obstacle.radius &&
                (startDistance > margin + obstacle.radius || closestDistance < startDistance - 0.001f)
            ) {
                return PhaseRouteReason.COLLISION
            }
        }
        return null
    }

    fun blocked(allowed: Set<ShipCommand>): Set<ShipCommand> {
        require(commands.containsAll(allowed))
        require(listOf(ShipCommand.TURN_LEFT, ShipCommand.TURN_RIGHT).count { it in allowed } <= 1)
        require(listOf(ShipCommand.STRAFE_LEFT, ShipCommand.STRAFE_RIGHT).count { it in allowed } <= 1)
        require(listOf(ShipCommand.ACCELERATE, ShipCommand.ACCELERATE_BACKWARDS, ShipCommand.DECELERATE).count { it in allowed } <= 1)
        return commands - allowed
    }

    fun execute(ship: ShipAPI, result: PhaseMovementResult) {
        if (result.controlsMovement) execute(ship, result.allowed)
    }

    fun execute(ship: ShipAPI, allowed: Set<ShipCommand>) {
        blocked(allowed).forEach(ship::blockCommandForOneFrame)
        allowed.forEach { ship.giveCommand(it, null, 0) }
    }

    private fun cancelled(state: PhaseRouteState, reason: PhaseRouteReason) =
        PhaseMovementResult(state.copy(failure = reason), emptySet(), false)
}
