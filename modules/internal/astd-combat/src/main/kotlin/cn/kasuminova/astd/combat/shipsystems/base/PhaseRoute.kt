package cn.kasuminova.astd.combat.shipsystems.base

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class PhasePosition(val x: Float, val y: Float) {
    operator fun plus(other: PhasePosition) = PhasePosition(x + other.x, y + other.y)
    operator fun minus(other: PhasePosition) = PhasePosition(x - other.x, y - other.y)
    operator fun times(scale: Float) = PhasePosition(x * scale, y * scale)
    fun dot(other: PhasePosition): Float = x * other.x + y * other.y
    fun length(): Float = sqrt(dot(this))
    fun distance(other: PhasePosition): Float = (this - other).length()
    fun unit(): PhasePosition = if (length() > 0.001f) this * (1f / length()) else ZERO
    fun left() = PhasePosition(-y, x)

    companion object {
        val ZERO = PhasePosition(0f, 0f)
        fun facing(degrees: Float): PhasePosition {
            val radians = Math.toRadians(degrees.toDouble())
            return PhasePosition(cos(radians).toFloat(), sin(radians).toFloat())
        }
        fun angleDelta(from: Float, to: Float): Float = ((to - from) % 360f + 540f) % 360f - 180f
        fun segmentFraction(point: PhasePosition, start: PhasePosition, end: PhasePosition): Float {
            val delta = end - start
            return if (delta.dot(delta) < 0.001f) 1f else (point - start).dot(delta) / delta.dot(delta)
        }
    }
}

internal data class PhaseBounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
    fun contains(point: PhasePosition, margin: Float): Boolean =
        point.x >= minX + margin && point.x <= maxX - margin &&
            point.y >= minY + margin && point.y <= maxY - margin
}

internal data class PhaseObstacle(
    val position: PhasePosition,
    val radius: Float,
    val velocity: PhasePosition = PhasePosition.ZERO,
)

internal data class PhaseRouteTarget(
    val id: String,
    val position: PhasePosition,
    val facing: Float,
    val radius: Float,
    val valid: Boolean = true,
    val velocity: PhasePosition = PhasePosition.ZERO,
    val angularVelocity: Float = 0f,
)

internal enum class PhaseRouteSide(val sign: Float) { LEFT(1f), RIGHT(-1f) }
internal enum class PhaseRouteStage { LATERAL, SIDE_ENTRY, ATTACK, RECOVERY }
internal enum class PhaseRouteReason {
    TARGET_LOST, TARGET_MOVED, TARGET_TURNED, DANGER, BOUNDARY, COLLISION,
    TIME_LIMIT, HARD_FLUX_LIMIT, NO_BENEFIT, REPLAN_LIMIT, NO_ROUTE,
}

internal data class PhaseWaypoint(val position: PhasePosition, val stage: PhaseRouteStage)

internal data class PhaseRoute(
    val target: PhaseRouteTarget,
    val side: PhaseRouteSide,
    val origin: PhasePosition,
    val waypoints: List<PhaseWaypoint>,
    val attackRange: Float,
    val version: Int,
    val score: Float,
    val sampledDamage: Float,
) {
    init { require(waypoints.isNotEmpty()) }
    val recovery: Boolean get() = waypoints.last().stage == PhaseRouteStage.RECOVERY
    fun segmentStart(index: Int): PhasePosition = if (index == 0) origin else waypoints[index - 1].position
    fun lengthBefore(index: Int): Float = (0 until index).sumOf {
        segmentStart(it).distance(waypoints[it].position).toDouble()
    }.toFloat()

    fun inAttackArea(position: PhasePosition, targetNow: PhaseRouteTarget): Boolean {
        if (recovery || !targetNow.valid || targetNow.id != target.id) return false
        val offset = position - targetNow.position
        val forward = PhasePosition.facing(targetNow.facing)
        val rearArcCosine = cos(Math.toRadians(110.0)).toFloat()
        val sideOffset = offset.dot(forward.left()) * side.sign
        val rearOffset = -offset.dot(forward)
        return offset.length() <= attackRange && offset.unit().dot(forward) <= rearArcCosine &&
            rearOffset > targetNow.radius * 0.25f && sideOffset > targetNow.radius * 0.25f
    }

    fun canCommitAttack(position: PhasePosition, targetNow: PhaseRouteTarget): Boolean {
        if (recovery || !targetNow.valid || targetNow.id != target.id) return false
        val offset = position - targetNow.position
        val forward = PhasePosition.facing(targetNow.facing)
        val outsideFront = offset.dot(forward) <= offset.length() * 0.5f
        val sideOffset = offset.dot(forward.left()) * side.sign
        val rearOffset = -offset.dot(forward)
        return offset.length() <= attackRange * 1.5f && outsideFront &&
            rearOffset > targetNow.radius * 0.25f && sideOffset > targetNow.radius * 0.25f
    }
}

internal data class PhaseRouteSpace(
    val bounds: PhaseBounds,
    val obstacles: List<PhaseObstacle> = emptyList(),
    val damagePerSecond: (PhasePosition, Float) -> Float = { _, _ -> 0f },
    val recoverySeconds: (PhasePosition) -> Float = { 0f },
    val battleTimePerLocalSecond: Float = 1f,
    val collides: Boolean = true,
) {
    init { require(battleTimePerLocalSecond > 0f && battleTimePerLocalSecond.isFinite()) }
}

internal data class PhaseRouteLimits(
    val maxElapsed: Float = 24f,
    val maxHardFluxCost: Float = Float.MAX_VALUE,
    val maxNoBenefitTime: Float = 5f,
    val maxReplans: Int = 2,
    val targetMoveDistance: Float = 180f,
    val targetTurnDegrees: Float = 25f,
    val minimumProgressSpeed: Float = 3f,
)

internal data class PhaseRouteRequest(
    val position: PhasePosition,
    val target: PhaseRouteTarget,
    val shipRadius: Float,
    val speed: Float,
    val planningInterval: Float,
    val attackRange: Float,
    val lateralDistance: Float = 800f,
    val frontHalfArc: Float = 30f,
    val clearance: Float = 40f,
    val sampleStep: Float = 80f,
    val maxDamage: Float = Float.MAX_VALUE,
    val damageWeight: Float = 0.01f,
    val maxElapsed: Float = Float.POSITIVE_INFINITY,
) {
    init {
        require(shipRadius >= 0f && speed > 0f && planningInterval > 0f && sampleStep > 0f)
        require(attackRange > 0f && clearance >= 0f && maxElapsed >= 0f)
    }
}

internal data class PhaseRouteEvaluation(val score: Float, val damage: Float, val failure: PhaseRouteReason?)
internal data class PhaseRoutePlan(val route: PhaseRoute?, val failure: PhaseRouteReason?, val sideResults: String = "")

internal object PhaseRoutePlanner {
    fun plan(request: PhaseRouteRequest, space: PhaseRouteSpace, recovery: Boolean = false): PhaseRoute? =
        planDetailed(request, space, recovery).route

    fun planDetailed(request: PhaseRouteRequest, space: PhaseRouteSpace, recovery: Boolean = false): PhaseRoutePlan {
        val candidates = PhaseRouteSide.entries.map {
            candidateDetailed(request, space, it, recovery, PhaseRouteStage.LATERAL, 1)
        }
        val sideResults = PhaseRouteSide.entries.zip(candidates).joinToString(",") { (side, result) ->
            "${side.name}=${result.failure?.name ?: "OK"}"
        }
        candidates.mapNotNull { it.route }.minByOrNull { it.score }?.let {
            return PhaseRoutePlan(it, null, sideResults)
        }
        return PhaseRoutePlan(null, candidates.firstNotNullOfOrNull { it.failure } ?: PhaseRouteReason.NO_ROUTE, sideResults)
    }

    fun candidate(
        request: PhaseRouteRequest,
        space: PhaseRouteSpace,
        side: PhaseRouteSide,
        recovery: Boolean,
        startStage: PhaseRouteStage,
        version: Int,
        retainedSideOffset: Float? = null,
    ): PhaseRoute? = candidateDetailed(request, space, side, recovery, startStage, version, retainedSideOffset).route

    private fun candidateDetailed(
        request: PhaseRouteRequest,
        space: PhaseRouteSpace,
        side: PhaseRouteSide,
        recovery: Boolean,
        startStage: PhaseRouteStage,
        version: Int,
        retainedSideOffset: Float? = null,
        stageTravel: Float = 0f,
        sideEntryCaptured: Boolean = false,
    ): PhaseRoutePlan {
        if (!request.target.valid) return PhaseRoutePlan(null, PhaseRouteReason.TARGET_LOST)
        val forward = PhasePosition.facing(request.target.facing)
        val lateral = forward.left() * side.sign
        val relative = request.position - request.target.position
        val separation = request.shipRadius + request.target.radius + request.clearance
        if (!recovery && request.attackRange <= separation) return PhaseRoutePlan(null, PhaseRouteReason.NO_ROUTE)
        val longShift = max(request.lateralDistance.coerceIn(600f, 1200f), separation * 2f)
        val front = max(0f, relative.dot(forward))
        val outsideCone = front * tan(Math.toRadians(request.frontHalfArc.coerceIn(0f, 80f).toDouble())).toFloat()
        val desiredOffset = retainedSideOffset ?: longShift
        val sideOffset = max(desiredOffset, max(outsideCone + separation, separation * 2f))
        val lateralFirst = request.position + lateral * (sideOffset - relative.dot(lateral))
        val desiredRange = request.attackRange * 1.2f
        val forwardRange = sqrt((desiredRange * desiredRange - sideOffset * sideOffset).coerceAtLeast(0f))
        val first = if (recovery) lateralFirst else {
            request.target.position + forward * relative.dot(forward).coerceIn(0f, forwardRange) + lateral * sideOffset
        }
        val points: List<PhaseWaypoint>
        val evaluationPoints: List<PhaseWaypoint>
        if (recovery) {
            val away = if (retainedSideOffset == null) relative.unit() * min(longShift * 0.35f, separation) else PhasePosition.ZERO
            points = listOf(PhaseWaypoint(first + away, PhaseRouteStage.RECOVERY))
            evaluationPoints = points
        } else {
            val attackDistance = max(separation + (request.attackRange - separation) * 0.2f, request.attackRange * 0.5f)
            val entrySideOffset = min(sideOffset, max(attackDistance, separation * 2f))
            val entryOffset = lateral * entrySideOffset - forward * max(separation, attackDistance * 0.8f)
            val attackOffset = (lateral - forward).unit() * attackDistance
            val staticEntry = request.target.position + entryOffset
            val staticAttack = request.target.position + attackOffset
            val lateralDuration = if (startStage == PhaseRouteStage.LATERAL) {
                request.position.distance(first) / request.speed
            } else 0f
            val entryDuration = when (startStage) {
                PhaseRouteStage.LATERAL -> first.distance(staticEntry) / request.speed
                PhaseRouteStage.SIDE_ENTRY -> request.position.distance(staticEntry) / request.speed
                else -> 0f
            }
            val entryArrival = lateralDuration + entryDuration
            val attackArrival = if (startStage == PhaseRouteStage.ATTACK) {
                request.position.distance(staticAttack) / request.speed
            } else {
                entryArrival + staticEntry.distance(staticAttack) / request.speed
            }
            val predictedEntry = request.target.position + request.target.velocity *
                (entryArrival * space.battleTimePerLocalSecond)
            val predictedAttack = request.target.position + request.target.velocity *
                (attackArrival * space.battleTimePerLocalSecond)
            points = listOf(
                PhaseWaypoint(first, PhaseRouteStage.LATERAL),
                PhaseWaypoint(staticEntry, PhaseRouteStage.SIDE_ENTRY),
                PhaseWaypoint(staticAttack, PhaseRouteStage.ATTACK),
            ).filter { it.stage.ordinal >= startStage.ordinal }
            evaluationPoints = listOf(
                PhaseWaypoint(first, PhaseRouteStage.LATERAL),
                PhaseWaypoint(predictedEntry + entryOffset, PhaseRouteStage.SIDE_ENTRY),
                PhaseWaypoint(predictedAttack + attackOffset, PhaseRouteStage.ATTACK),
            ).filter { it.stage.ordinal >= startStage.ordinal }
        }
        if (points.isEmpty()) return PhaseRoutePlan(null, PhaseRouteReason.NO_ROUTE)
        val route = PhaseRoute(request.target, side, request.position, points, request.attackRange, version, 0f, 0f)
        val evaluation = evaluate(
            route.copy(waypoints = evaluationPoints), request, space,
            sideEntryTravel = if (startStage == PhaseRouteStage.SIDE_ENTRY) stageTravel else 0f,
            sideEntryCaptured = startStage == PhaseRouteStage.SIDE_ENTRY && sideEntryCaptured,
        )
        return if (evaluation.failure == null) {
            PhaseRoutePlan(route.copy(score = evaluation.score, sampledDamage = evaluation.damage), null)
        } else {
            PhaseRoutePlan(null, evaluation.failure)
        }
    }

    fun evaluate(
        route: PhaseRoute,
        request: PhaseRouteRequest,
        space: PhaseRouteSpace,
        startIndex: Int = 0,
        position: PhasePosition = route.origin,
        lateralTravel: Float = 0f,
        lateralFacing: Float = route.target.facing,
        sideEntryTravel: Float = 0f,
        sideEntryCaptured: Boolean = false,
    ): PhaseRouteEvaluation {
        val step = min(request.sampleStep, request.speed * request.planningInterval)
        var previous = position
        var elapsed = 0f
        var damage = 0f
        fun failure(at: PhasePosition, time: Float): PhaseRouteReason? {
            if (time >= request.maxElapsed) return PhaseRouteReason.TIME_LIMIT
            val battleTime = time * space.battleTimePerLocalSecond
            if (!space.bounds.contains(at, request.shipRadius + request.clearance)) return PhaseRouteReason.BOUNDARY
            if (space.collides && space.obstacles.any {
                    at.distance(it.position + it.velocity * battleTime) <= request.shipRadius + it.radius + request.clearance
                }) return PhaseRouteReason.COLLISION
            if (space.collides && at.distance(request.target.position + request.target.velocity * battleTime) <=
                request.shipRadius + request.target.radius + request.clearance
            ) {
                return PhaseRouteReason.COLLISION
            }
            return null
        }
        failure(previous, 0f)?.let { return PhaseRouteEvaluation(Float.POSITIVE_INFINITY, damage, it) }
        for (index in startIndex until route.waypoints.size) {
            val waypoint = route.waypoints[index]
            var end = waypoint.position
            var distance = previous.distance(end)
            if (!space.collides && !route.recovery) when (waypoint.stage) {
                PhaseRouteStage.LATERAL -> {
                    val delta = end - previous
                    val side = PhasePosition.facing(lateralFacing).left() * route.side.sign
                    val available = abs(delta.dot(side))
                    val remaining = (400f - lateralTravel).coerceAtLeast(0f)
                    val fraction = if (available > 0.001f) (remaining / available).coerceIn(0f, 1f) else 0f
                    end = previous + delta * fraction + side * (remaining - available).coerceAtLeast(0f)
                    distance = previous.distance(end)
                }
                PhaseRouteStage.SIDE_ENTRY -> {
                    if (sideEntryCaptured) {
                        end = route.waypoints.last().position
                        distance = previous.distance(end)
                    } else {
                        val delta = end - previous
                        val direction = delta.unit()
                        val targetAtStart = request.target.position + request.target.velocity *
                            (elapsed * space.battleTimePerLocalSecond)
                        val targetOffset = targetAtStart - previous
                        val closing = targetOffset.unit().dot(direction)
                        val rangeDistance = if (closing > 0.001f) {
                            (targetOffset.length() - request.attackRange * 1.5f) / closing
                        } else {
                            distance
                        }
                        val entryRemaining = (50f - sideEntryTravel).coerceAtLeast(0f)
                        val captureDistance = min(distance, max(entryRemaining, rangeDistance).coerceAtLeast(0f))
                        end = previous + direction * captureDistance
                        distance = captureDistance
                    }
                }
                else -> Unit
            }
            val count = max(1, ceil(distance / step).toInt())
            val duration = distance / request.speed / count
            var last = previous
            for (sample in 1..count) {
                val point = previous + (end - previous) * (sample.toFloat() / count)
                val nextTime = elapsed + duration
                val unsafe = failure(point, nextTime) ?: sweptFailure(last, point, elapsed, duration, request, space)
                if (unsafe != null) return PhaseRouteEvaluation(Float.POSITIVE_INFINITY, damage, unsafe)
                val rate = space.damagePerSecond(point, nextTime * space.battleTimePerLocalSecond)
                if (!rate.isFinite() || rate < 0f) return PhaseRouteEvaluation(Float.POSITIVE_INFINITY, damage, PhaseRouteReason.DANGER)
                damage += rate * duration * space.battleTimePerLocalSecond
                if (damage > request.maxDamage) return PhaseRouteEvaluation(Float.POSITIVE_INFINITY, damage, PhaseRouteReason.DANGER)
                elapsed = nextTime
                last = point
            }
            previous = end
        }
        val recoveryTime = if (route.recovery) space.recoverySeconds(previous).coerceAtLeast(0f) else 0f
        return PhaseRouteEvaluation(elapsed + recoveryTime + damage * request.damageWeight, damage, null)
    }

    private fun sweptFailure(
        from: PhasePosition,
        to: PhasePosition,
        elapsed: Float,
        duration: Float,
        request: PhaseRouteRequest,
        space: PhaseRouteSpace,
    ): PhaseRouteReason? {
        if (!space.collides) return null
        val obstacles = space.obstacles + PhaseObstacle(request.target.position, request.target.radius, request.target.velocity)
        val timeScale = space.battleTimePerLocalSecond
        return if (obstacles.any {
                val start = from - (it.position + it.velocity * (elapsed * timeScale))
                val end = to - (it.position + it.velocity * ((elapsed + duration) * timeScale))
                val fraction = PhasePosition.segmentFraction(PhasePosition.ZERO, start, end).coerceIn(0f, 1f)
                (start + (end - start) * fraction).length() <= request.shipRadius + it.radius + request.clearance
            }) PhaseRouteReason.COLLISION else null
    }

    private fun evaluateState(
        state: PhaseRouteState,
        request: PhaseRouteRequest,
        space: PhaseRouteSpace,
    ): PhaseRouteEvaluation = evaluate(
        state.route, request, space, state.waypointIndex, request.position,
        lateralTravel = state.lateralTravelAt(request.position),
        lateralFacing = state.lateralFacing,
        sideEntryTravel = if (state.waypoint.stage == PhaseRouteStage.SIDE_ENTRY) state.stageTravel else 0f,
        sideEntryCaptured = state.waypoint.stage == PhaseRouteStage.SIDE_ENTRY && state.sideEntryCapture,
    )

    fun invalidation(state: PhaseRouteState, request: PhaseRouteRequest, space: PhaseRouteSpace): PhaseRouteReason? {
        val target = request.target
        if (!target.valid || state.route.target.id != target.id) return PhaseRouteReason.TARGET_LOST
        if (target.position.distance(state.targetAnchor.position) > state.limits.targetMoveDistance) return PhaseRouteReason.TARGET_MOVED
        if (abs(PhasePosition.angleDelta(state.targetAnchor.facing, target.facing)) > state.limits.targetTurnDegrees &&
            state.waypoint.stage != PhaseRouteStage.LATERAL
        ) {
            return PhaseRouteReason.TARGET_TURNED
        }
        val failure = evaluateState(state, request, space).failure
        if (failure != PhaseRouteReason.TIME_LIMIT) return failure
        val tolerance = request.copy(maxElapsed = request.maxElapsed + request.planningInterval)
        return if (evaluateState(state, tolerance, space).failure == null) {
            null
        } else {
            PhaseRouteReason.TIME_LIMIT
        }
    }

    fun replan(state: PhaseRouteState, request: PhaseRouteRequest, space: PhaseRouteSpace): PhaseRouteState {
        if (state.finished || state.paused) return state
        val tracked = state.trackTarget(request.target)
        val remainingRequest = request.copy(
            maxElapsed = min(request.maxElapsed, (state.limits.maxElapsed - state.elapsed).coerceAtLeast(0f)),
        )
        val initialReason = invalidation(tracked, remainingRequest, space) ?:
            return tracked.copy(recoveryRequired = false)
        if (initialReason == PhaseRouteReason.TARGET_LOST) return tracked.copy(failure = initialReason)
        val currentFailure = if (initialReason == PhaseRouteReason.TARGET_MOVED ||
            initialReason == PhaseRouteReason.TARGET_TURNED
        ) {
            evaluateState(tracked, remainingRequest, space).failure
        } else {
            null
        }
        val reason = if (currentFailure != null && currentFailure != PhaseRouteReason.TIME_LIMIT) {
            currentFailure
        } else {
            initialReason
        }
        if (tracked.waypoint.stage == PhaseRouteStage.LATERAL && initialReason == PhaseRouteReason.TARGET_MOVED &&
            currentFailure == null
        ) {
            return tracked.copy(lastReplanReason = initialReason, recoveryRequired = false)
        }
        if (tracked.waypoint.stage == PhaseRouteStage.SIDE_ENTRY && currentFailure == null &&
            initialReason == PhaseRouteReason.TARGET_TURNED
        ) {
            return tracked.copy(lastReplanReason = initialReason, recoveryRequired = false)
        }
        if (state.replans >= state.limits.maxReplans) {
            val targetChanged = initialReason == PhaseRouteReason.TARGET_MOVED ||
                initialReason == PhaseRouteReason.TARGET_TURNED
            return when {
                targetChanged && currentFailure == null -> tracked.copy(
                    lastReplanReason = initialReason, recoveryRequired = false,
                )
                currentFailure == PhaseRouteReason.TIME_LIMIT || initialReason == PhaseRouteReason.TIME_LIMIT ->
                    tracked.copy(lastReplanReason = initialReason, recoveryRequired = true)
                else -> tracked.copy(
                    failure = PhaseRouteReason.REPLAN_LIMIT,
                    failureCause = currentFailure ?: initialReason,
                    lastReplanReason = initialReason,
                )
            }
        }
        val stage = tracked.waypoint.stage
        val oldLateral = PhasePosition.facing(tracked.route.target.facing).left() * tracked.route.side.sign
        val retainedOffset = if (stage == PhaseRouteStage.LATERAL) null else {
            tracked.route.waypoints.maxOf { (it.position - tracked.route.target.position).dot(oldLateral) }
        }
        val sameSide = candidateDetailed(
            remainingRequest, space, tracked.route.side, tracked.route.recovery, stage, tracked.route.version + 1,
            retainedOffset,
            stageTravel = if (stage == PhaseRouteStage.SIDE_ENTRY) tracked.stageTravel else 0f,
            sideEntryCaptured = stage == PhaseRouteStage.SIDE_ENTRY && tracked.sideEntryCapture,
        )
        val otherSide = candidateDetailed(
            remainingRequest, space, PhaseRouteSide.entries.first { it != tracked.route.side },
            tracked.route.recovery, stage, tracked.route.version + 1,
        )
        val replacement = when {
            sameSide.route == null -> otherSide.route
            otherSide.route == null -> sameSide.route
            otherSide.route.score * 1.2f < sameSide.route.score -> otherSide.route
            else -> sameSide.route
        }
        val sideResults = buildString {
            append("${tracked.route.side}=${sameSide.failure?.name ?: "OK"}")
            val side = PhaseRouteSide.entries.first { it != tracked.route.side }
            append(",$side=${otherSide.failure?.name ?: "OK"}")
        }
        if (replacement == null && (reason == PhaseRouteReason.TIME_LIMIT ||
                (reason == PhaseRouteReason.TARGET_MOVED || reason == PhaseRouteReason.TARGET_TURNED) &&
                sameSide.failure == PhaseRouteReason.TIME_LIMIT && otherSide.failure == PhaseRouteReason.TIME_LIMIT)
        ) {
            return tracked.copy(
                recoveryRequired = true,
                lastReplanReason = initialReason,
                lastSideResults = sideResults,
            )
        }
        if (replacement == null && (reason == PhaseRouteReason.TARGET_MOVED || reason == PhaseRouteReason.TARGET_TURNED)) {
            val oldRouteSafe = evaluateState(tracked, remainingRequest, space).failure == null
            if (oldRouteSafe) return tracked.copy(recoveryRequired = false)
        }
        return if (replacement == null) tracked.copy(
            failure = sameSide.failure ?: otherSide.failure ?: PhaseRouteReason.NO_ROUTE,
            lastReplanReason = initialReason, replans = state.replans + 1, lastSideResults = sideResults,
        )
        else {
            val continueStage = replacement.side == tracked.route.side &&
                replacement.recovery == tracked.route.recovery && replacement.waypoints.first().stage == stage
            tracked.copy(
                route = replacement, waypointIndex = 0, replans = state.replans + 1,
                progressBeforeRoute = state.progress, routeProgress = 0f,
                stageTravel = if (continueStage) tracked.stageTravel else 0f,
                sideEntryCapture = continueStage && tracked.sideEntryCapture,
                lastPosition = request.position, lastReplanReason = reason, lastSideResults = sideResults,
                targetAnchor = request.target,
                lateralBeforeRoute = tracked.lateralTravelAt(request.position),
                lateralOrigin = request.position,
                lateralFacing = request.target.facing,
                recoveryRequired = false,
            )
        }
    }
}

internal data class PhaseRouteState(
    val route: PhaseRoute,
    val startedAt: Float,
    val limits: PhaseRouteLimits = PhaseRouteLimits(),
    val waypointIndex: Int = 0,
    val elapsed: Float = 0f,
    val hardFluxCost: Float = 0f,
    val noBenefitTime: Float = 0f,
    val replans: Int = 0,
    val lastPosition: PhasePosition = route.origin,
    val progressBeforeRoute: Float = 0f,
    val routeProgress: Float = 0f,
    val stageTravel: Float = 0f,
    val lastReplanReason: PhaseRouteReason? = null,
    val lastSideResults: String = "",
    val paused: Boolean = false,
    val completed: Boolean = false,
    val failure: PhaseRouteReason? = null,
    val targetAnchor: PhaseRouteTarget = route.target,
    val lateralBeforeRoute: Float = 0f,
    val lateralOrigin: PhasePosition = route.origin,
    val lateralFacing: Float = route.target.facing,
    val sideEntryCapture: Boolean = false,
    val recoveryRequired: Boolean = false,
    val failureCause: PhaseRouteReason? = null,
) {
    val progress: Float get() = progressBeforeRoute + routeProgress
    val lateralTravel: Float get() = lateralTravelAt(lastPosition)
    val waypoint: PhaseWaypoint get() = route.waypoints[waypointIndex]
    val finished: Boolean get() = completed || failure != null

    fun lateralTravelAt(position: PhasePosition): Float {
        if (paused) return lateralBeforeRoute
        val lateral = PhasePosition.facing(lateralFacing).left()
        return lateralBeforeRoute + abs((position - lateralOrigin).dot(lateral))
    }

    fun completeAttack(position: PhasePosition, targetNow: PhaseRouteTarget, canAttack: Boolean): PhaseRouteState {
        if (completed) {
            return if (route.recovery || route.inAttackArea(position, targetNow)) {
                this
            } else {
                copy(completed = false, stageTravel = 0f)
            }
        }
        val attackStage = waypoint.stage == PhaseRouteStage.ATTACK ||
            waypoint.stage == PhaseRouteStage.SIDE_ENTRY && stageTravel >= 50f
        if (failure != null || route.recovery || !attackStage || !canAttack || !route.inAttackArea(position, targetNow)) {
            return this
        }
        return copy(waypointIndex = route.waypoints.lastIndex, completed = true)
    }

    fun trackTarget(target: PhaseRouteTarget): PhaseRouteState {
        if (failure != null || route.recovery || route.target == target || !target.valid || route.target.id != target.id) return this
        val angle = PhasePosition.angleDelta(route.target.facing, target.facing)
        val radians = Math.toRadians(angle.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val waypoints = route.waypoints.map { waypoint ->
            val offset = waypoint.position - route.target.position
            when {
                waypoint.stage.ordinal < PhaseRouteStage.ATTACK.ordinal ->
                    waypoint.copy(position = target.position + offset)
                else -> waypoint.copy(
                    position = target.position + PhasePosition(
                        offset.x * cosine - offset.y * sine,
                        offset.x * sine + offset.y * cosine,
                    ),
                )
            }
        }
        return copy(route = route.copy(target = target, waypoints = waypoints))
    }

    fun advance(
        amount: Float,
        position: PhasePosition,
        hardFluxSpent: Float,
        terminalAlignment: Boolean = false,
        destination: PhasePosition? = null,
    ): PhaseRouteState {
        require(amount >= 0f && amount.isFinite() && hardFluxSpent >= 0f && hardFluxSpent.isFinite())
        if (finished) return this
        if (paused) return copy(lastPosition = position)
        val start = route.segmentStart(waypointIndex)
        val end = waypoint.position
        val projection = PhasePosition.segmentFraction(position, start, end).coerceIn(0f, 1f)
        val projected = route.lengthBefore(waypointIndex) + start.distance(end) * projection
        val minimumBenefit = max(0.001f, limits.minimumProgressSpeed * amount)
        val projectedGain = projected - routeProgress
        val benefitTarget = destination ?: end
        val waypointGain = lastPosition.distance(benefitTarget) - position.distance(benefitTarget)
        val actualTravel = lastPosition.distance(position)
        val stageGain = min(actualTravel, waypointGain.coerceAtLeast(0f))
        val progressed = projectedGain > minimumBenefit || waypointGain > minimumBenefit
        val nextProgress = if (projectedGain > minimumBenefit) projected else routeProgress
        val noBenefit = noBenefitTime + if (!paused && !progressed && !terminalAlignment) amount else 0f
        val time = elapsed + amount
        val cost = hardFluxCost + hardFluxSpent
        val reason = when {
            time >= limits.maxElapsed -> PhaseRouteReason.TIME_LIMIT
            cost >= limits.maxHardFluxCost -> PhaseRouteReason.HARD_FLUX_LIMIT
            noBenefit >= limits.maxNoBenefitTime -> PhaseRouteReason.NO_BENEFIT
            else -> null
        }
        return copy(elapsed = time, hardFluxCost = cost, noBenefitTime = noBenefit,
            routeProgress = nextProgress, stageTravel = stageTravel + stageGain, lastPosition = position, failure = reason)
    }

    fun pause(): PhaseRouteState = if (finished) this else copy(
        paused = true,
        lateralBeforeRoute = lateralTravel,
        lateralOrigin = lastPosition,
    )

    fun resume(request: PhaseRouteRequest, space: PhaseRouteSpace): PhaseRouteState =
        if (finished) this else PhaseRoutePlanner.replan(
            copy(
                paused = false,
                lastPosition = request.position,
                lateralOrigin = request.position,
            ).trackTarget(request.target), request, space,
        )
}
