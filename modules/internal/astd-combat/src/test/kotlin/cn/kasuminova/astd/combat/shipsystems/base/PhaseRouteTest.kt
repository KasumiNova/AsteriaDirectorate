package cn.kasuminova.astd.combat.shipsystems.base

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseRouteTest {
    private val target = PhaseRouteTarget("enemy", PhasePosition.ZERO, 0f, 100f)
    private val request = PhaseRouteRequest(PhasePosition(1000f, 0f), target, 30f, 300f, 0.2f, 600f)
    private val space = PhaseRouteSpace(PhaseBounds(-6000f, 6000f, -6000f, 6000f))

    private fun route(side: PhaseRouteSide = PhaseRouteSide.LEFT): PhaseRoute = assertNotNull(
        PhaseRoutePlanner.candidate(request, space, side, false, PhaseRouteStage.LATERAL, 1),
    )

    @Test
    fun `左右路线先长侧移然后侧后进入攻击区`() {
        for (side in PhaseRouteSide.entries) {
            val route = route(side)
            assertEquals(listOf(PhaseRouteStage.LATERAL, PhaseRouteStage.SIDE_ENTRY, PhaseRouteStage.ATTACK), route.waypoints.map { it.stage })
            val lateral = route.waypoints[0].position
            assertTrue(lateral.x <= request.position.x && lateral.x >= target.position.x)
            assertTrue(lateral.distance(target.position) <= kotlin.math.max(request.attackRange * 1.2f, abs(lateral.y - target.position.y)))
            assertTrue(abs(lateral.y - request.position.y) >= 600f)
            assertTrue(lateral.y * side.sign > 0f)
            val entry = route.waypoints[1].position
            assertTrue(entry.x < target.position.x)
            assertTrue(entry.y * side.sign < lateral.y * side.sign)
            assertTrue(entry.y * side.sign > route.waypoints.last().position.y * side.sign)
            assertTrue(route.inAttackArea(route.waypoints.last().position, target))
            val attackDistance = route.waypoints.last().position.distance(target.position)
            assertTrue(attackDistance >= request.shipRadius + target.radius + request.clearance)
            assertTrue(attackDistance <= request.attackRange * 0.6f)
            assertFalse(route.inAttackArea(PhasePosition(-100f, 300f * side.sign), target))
            assertTrue(route.inAttackArea(PhasePosition(-300f, 100f * side.sign), target))
            assertFalse(route.inAttackArea(PhasePosition(-1000f, 100f * side.sign), target))
            assertFalse(route.inAttackArea(route.waypoints.last().position, target.copy(id = "other")))
        }
    }

    @Test
    fun `已有侧向偏移时重规划使用目标总偏移而非继续追加`() {
        val shifted = assertNotNull(
            PhaseRoutePlanner.plan(request.copy(position = PhasePosition(1000f, 300f), frontHalfArc = 0f), space),
        )

        assertEquals(800f, shifted.waypoints[0].position.y, 0.01f)
        assertEquals(500f, shifted.waypoints[0].position.y - 300f, 0.01f)
    }

    @Test
    fun `路径损失评分选择更安全一侧但安全路线不因评分波动重规划`() {
        val dangerousLeft = space.copy(damagePerSecond = { p, _ -> if (p.y > 0f) 100f else 0f })
        val selected = assertNotNull(PhaseRoutePlanner.plan(request, dangerousLeft))
        assertEquals(PhaseRouteSide.RIGHT, selected.side)
        val state = PhaseRouteState(route(), 10f)
        assertEquals(state, PhaseRoutePlanner.replan(state, request, dangerousLeft))
    }

    @Test
    fun `先排除左侧超时路线再选择评分较高但可完成的右侧`() {
        val asymmetric = request.copy(position = PhasePosition(1000f, -500f), speed = 100f, maxDamage = 1250f, damageWeight = 0.02f, frontHalfArc = 45f)
        val dangerousRight = space.copy(damagePerSecond = { p, _ -> if (p.y < -500f) 40f else 0f })
        val unlimited = assertNotNull(PhaseRoutePlanner.planDetailed(asymmetric, dangerousRight).route)
        assertEquals(PhaseRouteSide.LEFT, unlimited.side)
        assertTrue(unlimited.lengthBefore(unlimited.waypoints.size) / asymmetric.speed > 24f)

        val bounded = asymmetric.copy(maxElapsed = 24f)
        assertEquals(PhaseRouteReason.TIME_LIMIT, PhaseRoutePlanner.evaluate(unlimited, bounded, dangerousRight).failure)
        val result = PhaseRoutePlanner.planDetailed(bounded, dangerousRight)
        val selected = assertNotNull(result.route)
        assertNull(result.failure)
        assertEquals(PhaseRouteSide.RIGHT, selected.side)
        assertTrue(selected.score > unlimited.score)
        assertTrue(selected.sampledDamage > 0f && selected.sampledDamage <= bounded.maxDamage)
        assertTrue(selected.lengthBefore(selected.waypoints.size) / bounded.speed < bounded.maxElapsed)
    }

    @Test
    fun `采样达到时间预算即失败且零预算不能开始执行`() {
        val short = route().copy(waypoints = listOf(
            PhaseWaypoint(request.position + PhasePosition(0f, 300f), PhaseRouteStage.LATERAL),
        ))
        val singleSample = request.copy(sampleStep = 300f, planningInterval = 1f)
        for (budget in listOf(0f, 0.5f, 1f)) {
            assertEquals(PhaseRouteReason.TIME_LIMIT,
                PhaseRoutePlanner.evaluate(short, singleSample.copy(maxElapsed = budget), space).failure)
        }
        assertNull(PhaseRoutePlanner.evaluate(short, singleSample.copy(maxElapsed = 1.01f), space).failure)
        val exhausted = PhaseRoutePlanner.planDetailed(request.copy(maxElapsed = 0f), space)
        assertNull(exhausted.route)
        assertEquals(PhaseRouteReason.TIME_LIMIT, exhausted.failure)
    }

    @Test
    fun `预测超时保留路线等待恢复且实际到期仍失败`() {
        val state = PhaseRouteState(route(), 7f, elapsed = 23f, hardFluxCost = 80f)
        val moved = request.copy(target = target.copy(position = PhasePosition(200f, 0f)), maxElapsed = 24f)
        assertNotNull(PhaseRoutePlanner.plan(moved, space))
        val exhausted = PhaseRoutePlanner.replan(state, moved, space)
        assertNull(exhausted.failure)
        assertEquals(PhaseRouteReason.TARGET_MOVED, exhausted.lastReplanReason)
        assertEquals(state.elapsed, exhausted.elapsed)
        assertEquals(state.hardFluxCost, exhausted.hardFluxCost)
        assertEquals(state.startedAt, exhausted.startedAt)
        assertEquals(0, exhausted.replans)
        assertEquals(PhaseRouteReason.TIME_LIMIT,
            exhausted.advance(1f, exhausted.lastPosition, 0f).failure)

        val fresh = state.copy(elapsed = 1f)
        assertNull(PhaseRoutePlanner.replan(fresh, moved, space).failure)
        val shortState = fresh.copy(limits = PhaseRouteLimits(maxElapsed = 1f))
        val requestLimited = PhaseRoutePlanner.replan(shortState, moved.copy(maxElapsed = 1f), space)
        assertNull(requestLimited.failure)
        assertEquals(PhaseRouteReason.TARGET_MOVED, requestLimited.lastReplanReason)
        assertEquals(PhaseRouteReason.TIME_LIMIT,
            requestLimited.advance(0.1f, requestLimited.lastPosition, 0f).failure)
    }

    @Test
    fun `目标未变时剩余预算也会触发重规划并切换到可完成侧`() {
        val asymmetric = request.copy(position = PhasePosition(1000f, -500f), speed = 140f)
        val original = assertNotNull(PhaseRoutePlanner.candidate(
            asymmetric, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.LATERAL, 1,
        ))
        val totalBudget = original.lengthBefore(original.waypoints.size) / asymmetric.speed + 0.5f
        val state = PhaseRouteState(original, 0f, elapsed = 1f, limits = PhaseRouteLimits(maxElapsed = totalBudget))
        val replanned = PhaseRoutePlanner.replan(state, asymmetric.copy(maxElapsed = totalBudget), space)
        assertNull(replanned.failure)
        assertEquals(PhaseRouteReason.TIME_LIMIT, replanned.lastReplanReason)
        assertEquals(PhaseRouteSide.RIGHT, replanned.route.side)
        assertEquals(state.elapsed, replanned.elapsed)
        assertEquals(1, replanned.replans)
        assertTrue(replanned.route.lengthBefore(replanned.route.waypoints.size) / asymmetric.speed < totalBudget - state.elapsed)
    }

    @Test
    fun `时限误差不足一个规划周期时不重复重规划`() {
        val initial = route()
        val duration = initial.lengthBefore(initial.waypoints.size) / request.speed
        val state = PhaseRouteState(initial, 0f, elapsed = 24f - duration + 0.1f)
        val bounded = request.copy(maxElapsed = 24f - state.elapsed)

        assertEquals(PhaseRouteReason.TIME_LIMIT,
            PhaseRoutePlanner.evaluate(initial, bounded, space).failure)
        assertEquals(state, PhaseRoutePlanner.replan(state, bounded, space))
    }

    @Test
    fun `重规划两侧均失败时区分触发原因和候选拒绝原因`() {
        val state = PhaseRouteState(route(), 0f)
        val moved = request.copy(target = target.copy(position = PhasePosition(200f, 0f)), maxDamage = 1f)
        val failures = listOf(
            space.copy(bounds = PhaseBounds(-6000f, 6000f, -400f, 400f)) to PhaseRouteReason.BOUNDARY,
            space.copy(damagePerSecond = { _, _ -> 1000f }) to PhaseRouteReason.DANGER,
        )
        for ((unsafe, expected) in failures) {
            val replanned = PhaseRoutePlanner.replan(state, moved, unsafe)
            assertEquals(expected, replanned.failure)
            assertEquals(PhaseRouteReason.TARGET_MOVED, replanned.lastReplanReason)
            assertEquals(1, replanned.replans)
            assertEquals(state.route.side, replanned.route.side)
            assertEquals(state.route.version, replanned.route.version)
            assertEquals(moved.target, replanned.route.target)
        }
    }

    @Test
    fun `采样步长不大于一个规划周期位移并计算沿途损失`() {
        val samples = mutableListOf<PhasePosition>()
        val route = route()
        val result = PhaseRoutePlanner.evaluate(route, request, space.copy(damagePerSecond = { p, _ -> samples += p; 10f }))
        var previous = request.position
        samples.forEach {
            assertTrue(previous.distance(it) <= request.speed * request.planningInterval + 0.01f)
            previous = it
        }
        assertEquals(route.lengthBefore(route.waypoints.size) / request.speed * 10f, result.damage, 0.02f)
        assertEquals(PhaseRouteReason.DANGER, PhaseRoutePlanner.evaluate(route, request.copy(maxDamage = result.damage * 0.5f),
            space.copy(damagePerSecond = { _, _ -> 10f })).failure)
    }

    @Test
    fun `外部运动和暴露伤害按战场时间而不是相位本地时间累计`() {
        val sampleTimes = mutableListOf<Float>()
        val route = route()
        val scaled = space.copy(
            damagePerSecond = { _, time -> sampleTimes += time; 10f },
            battleTimePerLocalSecond = 0.5f,
        )
        val result = PhaseRoutePlanner.evaluate(route, request, scaled)
        val localSeconds = route.lengthBefore(route.waypoints.size) / request.speed
        assertEquals(localSeconds * 0.5f, sampleTimes.max(), 0.001f)
        assertEquals(localSeconds * 0.5f * 10f, result.damage, 0.02f)
    }

    @Test
    fun `两侧路线均失败时发布实际拒绝原因`() {
        val bounded = space.copy(bounds = PhaseBounds(-6000f, 6000f, -400f, 400f))
        val result = PhaseRoutePlanner.planDetailed(request, bounded)
        assertNull(result.route)
        assertEquals(PhaseRouteReason.BOUNDARY, result.failure)
        assertEquals("LEFT=BOUNDARY,RIGHT=BOUNDARY", result.sideResults)
    }

    @Test
    fun `边界和采样间障碍被拒绝`() {
        assertNull(PhaseRoutePlanner.plan(request, space.copy(bounds = PhaseBounds(-6000f, 6000f, -400f, 400f))))
        val thinRequest = request.copy(shipRadius = 0f, clearance = 0f, sampleStep = 200f, planningInterval = 1f)
        val current = route()
        val pathPoint = request.position + (current.waypoints[0].position - request.position) * 0.37f
        val obstacle = PhaseObstacle(pathPoint, 1f)
        val evaluation = PhaseRoutePlanner.evaluate(current, thinRequest, space.copy(obstacles = listOf(obstacle)))
        assertEquals(PhaseRouteReason.COLLISION, evaluation.failure)
        val phased = space.copy(obstacles = listOf(obstacle), collides = false)
        assertNull(PhaseRoutePlanner.evaluate(current, thinRequest, phased).failure)
        assertNotNull(PhaseRoutePlanner.plan(thinRequest, phased))
        assertEquals(PhaseRouteReason.BOUNDARY,
            PhaseRoutePlanner.evaluate(current, thinRequest, phased.copy(
                bounds = PhaseBounds(-6000f, 6000f, -300f, 300f),
            )).failure)
    }

    @Test
    fun `运动障碍按采样时间预测`() {
        val current = route()
        val pathPoint = request.position + (current.waypoints[0].position - request.position) * 0.5f
        val arrival = request.position.distance(current.waypoints[0].position) * 0.5f / request.speed
        val velocity = PhasePosition(200f, 0f)
        val crossing = PhaseObstacle(pathPoint - velocity * arrival, 30f, velocity)
        assertEquals(PhaseRouteReason.COLLISION,
            PhaseRoutePlanner.evaluate(current, request, space.copy(obstacles = listOf(crossing))).failure)
    }

    @Test
    fun `目标移动和跨零转向有独立失效阈值`() {
        val state = PhaseRouteState(route(), 0f)
        assertEquals(PhaseRouteReason.TARGET_MOVED, PhaseRoutePlanner.invalidation(state,
            request.copy(target = target.copy(position = PhasePosition(200f, 0f))), space))
        assertEquals(PhaseRouteReason.TARGET_TURNED, PhaseRoutePlanner.invalidation(
            state.copy(waypointIndex = 1), request.copy(target = target.copy(facing = 30f)), space))
        assertNull(PhaseRoutePlanner.invalidation(state, request.copy(target = target.copy(facing = 359f)), space))
        assertEquals(PhaseRouteReason.TARGET_LOST, PhaseRoutePlanner.invalidation(state,
            request.copy(target = target.copy(id = "replacement")), space))
    }

    @Test
    fun `目标转向后保留仍安全的已承诺侧移路线`() {
        val oldRoute = route()
        val state = PhaseRouteState(oldRoute, 0f)
        val turned = target.copy(facing = 60f)
        val updated = request.copy(target = turned)
        val replacement = assertNotNull(PhaseRoutePlanner.candidate(
            updated, space, oldRoute.side, false, PhaseRouteStage.LATERAL, 2,
        ))
        val otherReplacement = assertNotNull(PhaseRoutePlanner.candidate(
            updated, space, PhaseRouteSide.RIGHT, false, PhaseRouteStage.LATERAL, 2,
        ))
        val blocked = space.copy(
            obstacles = listOf(
                PhaseObstacle(replacement.waypoints[0].position, 40f),
                PhaseObstacle(otherReplacement.waypoints[1].position, 40f),
            ),
        )

        val result = PhaseRoutePlanner.replan(state, updated, blocked)

        assertNull(result.failure)
        assertEquals(0, result.replans)
        assertEquals(oldRoute.version, result.route.version)
        assertEquals(turned, result.route.target)
        assertEquals(oldRoute.waypoints[0], result.route.waypoints[0])
    }

    @Test
    fun `可穿后范围内目标转向保留原路线和进入段进度`() {
        val closeRequest = request.copy(position = PhasePosition(-300f, 300f))
        val initial = assertNotNull(PhaseRoutePlanner.candidate(
            closeRequest, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.SIDE_ENTRY, 1,
        ))
        val state = PhaseRouteState(initial, 0f, replans = 1, stageTravel = 37f)
        val turned = target.copy(facing = 30f)

        val result = PhaseRoutePlanner.replan(
            state,
            closeRequest.copy(target = turned),
            space.copy(collides = false),
        )

        assertNull(result.failure)
        assertEquals(1, result.replans)
        assertEquals(initial.version, result.route.version)
        assertEquals(37f, result.stageTravel)
        assertEquals(PhaseRouteReason.TARGET_TURNED, result.lastReplanReason)
        assertTrue(result.route.canCommitAttack(closeRequest.position, turned))
    }

    @Test
    fun `目标明显转向后安全进入路线保留预算和段进度`() {
        val sideEntryRequest = request.copy(position = PhasePosition(1000f, 800f))
        val initial = assertNotNull(PhaseRoutePlanner.candidate(
            sideEntryRequest, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.SIDE_ENTRY, 1,
        ))
        val state = PhaseRouteState(initial, 0f, replans = 1, stageTravel = 37f)
        val moved = target.copy(position = PhasePosition(100f, 50f), facing = 60f)
        val updated = sideEntryRequest.copy(target = moved)

        val result = PhaseRoutePlanner.replan(state, updated, space)

        assertNull(result.failure)
        assertEquals(1, result.replans)
        assertEquals(initial.version, result.route.version)
        assertEquals(37f, result.stageTravel)
        assertEquals(PhaseRouteReason.TARGET_TURNED, result.lastReplanReason)
        assertEquals(moved, result.route.target)
        assertEquals(state.targetAnchor, result.targetAnchor)
        assertEquals(initial.waypoints[0].position + PhasePosition(100f, 50f), result.waypoint.position)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, result.waypoint.stage)
        assertEquals(result, PhaseRoutePlanner.replan(result, updated, space))
    }

    @Test
    fun `重规划次数用尽后安全路径继续跟随转向而不直接失败`() {
        val sideEntryRequest = request.copy(position = PhasePosition(1000f, 800f))
        val initial = assertNotNull(PhaseRoutePlanner.candidate(
            sideEntryRequest, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.SIDE_ENTRY, 1,
        ))
        val state = PhaseRouteState(
            initial, 0f, replans = 1, limits = PhaseRouteLimits(maxReplans = 1), recoveryRequired = true,
        )
        val turned = target.copy(facing = 60f)

        val result = PhaseRoutePlanner.replan(state, sideEntryRequest.copy(target = turned), space)

        assertNull(result.failure)
        assertFalse(result.recoveryRequired)
        assertEquals(1, result.replans)
        assertEquals(initial.version, result.route.version)
        assertEquals(turned, result.route.target)
        assertEquals(state.targetAnchor, result.targetAnchor)
        assertEquals(PhaseRouteReason.TARGET_TURNED, result.lastReplanReason)
    }

    @Test
    fun `重规划次数用尽且转向后旧路线仅超时进入恢复等待`() {
        val initial = PhaseRouteState(
            route(), 0f, waypointIndex = 1, replans = 1, limits = PhaseRouteLimits(maxReplans = 1),
        )
        val turned = target.copy(facing = 60f)

        val result = PhaseRoutePlanner.replan(initial, request.copy(speed = 1f, target = turned), space)

        assertNull(result.failure)
        assertTrue(result.recoveryRequired)
        assertEquals(1, result.replans)
        assertEquals(PhaseRouteReason.TARGET_TURNED, result.lastReplanReason)
    }

    @Test
    fun `重规划次数用尽且纯预测超时也进入恢复等待`() {
        val initial = PhaseRouteState(
            route(), 0f, waypointIndex = 1, replans = 1, limits = PhaseRouteLimits(maxReplans = 1),
        )

        val result = PhaseRoutePlanner.replan(initial, request.copy(speed = 1f), space)

        assertNull(result.failure)
        assertTrue(result.recoveryRequired)
        assertEquals(1, result.replans)
        assertEquals(PhaseRouteReason.TIME_LIMIT, result.lastReplanReason)
    }

    @Test
    fun `第一阶段目标转向只跟随目标并保留有限重规划次数`() {
        val initial = PhaseRouteState(route(), 0f)
        val turned = request.copy(
            target = target.copy(position = PhasePosition(100f, 50f), facing = 60f, velocity = PhasePosition(12f, 9f)),
        )

        val tracked = PhaseRoutePlanner.replan(initial, turned, space)

        assertNull(tracked.failure)
        assertEquals(PhaseRouteStage.LATERAL, tracked.waypoint.stage)
        assertEquals(turned.target, tracked.route.target)
        assertEquals(0, tracked.replans)
        assertEquals(initial.route.version, tracked.route.version)
        assertEquals(initial.targetAnchor, tracked.targetAnchor)
        assertEquals(tracked, PhaseRoutePlanner.replan(tracked, turned, space))
        assertTrue(tracked.route.lengthBefore(tracked.route.waypoints.size) / turned.speed < 24f)
    }

    @Test
    fun `侧后目标移动重规划保留段进度和累计成本`() {
        val oldRoute = route()
        val position = oldRoute.waypoints[0].position
        val state = PhaseRouteState(oldRoute, 7f, waypointIndex = 1, elapsed = 4f, hardFluxCost = 80f,
            noBenefitTime = 1f, routeProgress = oldRoute.lengthBefore(1), lastPosition = position,
            stageTravel = 942.1f, sideEntryCapture = true)
        val moved = target.copy(position = PhasePosition(200f, 0f))
        val updated = request.copy(position = position, target = moved)
        val tracked = PhaseRoutePlanner.replan(state, updated, space)
        assertNull(tracked.failure)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, tracked.waypoint.stage)
        assertEquals(state.route.side, tracked.route.side)
        assertEquals(oldRoute.waypoints[1].position.y, tracked.waypoint.position.y, 0.01f)
        assertEquals(moved, tracked.route.target)
        assertEquals(moved, tracked.targetAnchor)
        assertEquals(state.startedAt, tracked.startedAt)
        assertEquals(state.elapsed, tracked.elapsed)
        assertEquals(state.hardFluxCost, tracked.hardFluxCost)
        assertEquals(state.noBenefitTime, tracked.noBenefitTime)
        assertEquals(state.progress, tracked.progress)
        assertEquals(state.lateralTravel, tracked.lateralTravel)
        assertEquals(942.1f, tracked.stageTravel)
        assertTrue(tracked.sideEntryCapture)
        assertEquals(1, tracked.replans)
        assertEquals(2, tracked.route.version)
    }

    @Test
    fun `侧方进入换侧后必须重新累计位移和捕获`() {
        val initial = assertNotNull(PhaseRoutePlanner.candidate(
            request, space.copy(collides = false), PhaseRouteSide.LEFT, false, PhaseRouteStage.SIDE_ENTRY, 1,
        ))
        val state = PhaseRouteState(initial, 0f, stageTravel = 50f, sideEntryCapture = true)
        val dangerousLeft = space.copy(
            collides = false,
            damagePerSecond = { point, _ -> if (point.y > 0f) 1000f else 0f },
        )

        val switched = PhaseRoutePlanner.replan(state, request.copy(maxDamage = 1f), dangerousLeft)

        assertNull(switched.failure)
        assertEquals(PhaseRouteSide.RIGHT, switched.route.side)
        assertEquals(0f, switched.stageTravel)
        assertFalse(switched.sideEntryCapture)
        assertEquals(1, switched.replans)
    }

    @Test
    fun `已捕获续接按直接穿后路径评估而不是重新进入`() {
        val phased = space.copy(collides = false)
        val entering = assertNotNull(PhaseRoutePlanner.candidate(
            request, phased, PhaseRouteSide.LEFT, false, PhaseRouteStage.SIDE_ENTRY, 1,
        ))

        val restart = PhaseRoutePlanner.evaluate(entering, request, phased, sideEntryTravel = 50f)
        val captured = PhaseRoutePlanner.evaluate(
            entering, request, phased, sideEntryTravel = 50f, sideEntryCaptured = true,
        )

        assertNull(restart.failure)
        assertNull(captured.failure)
        assertTrue(captured.score < restart.score)
    }

    @Test
    fun `失败侧可切换但重规划预算不能重置`() {
        val state = PhaseRouteState(route(), 0f, limits = PhaseRouteLimits(maxReplans = 1))
        val blockedLeft = space.copy(bounds = PhaseBounds(-6000f, 6000f, -6000f, 500f))
        val switched = PhaseRoutePlanner.replan(state, request, blockedLeft)
        assertNull(switched.failure)
        assertEquals(PhaseRouteSide.RIGHT, switched.route.side)
        val blockedRight = space.copy(bounds = PhaseBounds(-6000f, 6000f, -500f, 6000f))
        val failed = PhaseRoutePlanner.replan(switched, request, blockedRight)
        assertEquals(PhaseRouteReason.REPLAN_LIMIT, failed.failure)
        assertEquals(failed, PhaseRoutePlanner.replan(failed, request, space))
    }

    @Test
    fun `EVADE暂停不累计执行时间成本和无收益`() {
        val state = PhaseRouteState(route(), 0f, limits = PhaseRouteLimits(maxElapsed = 5f, maxHardFluxCost = 100f),
            waypointIndex = 1, elapsed = 1f, noBenefitTime = 1f, hardFluxCost = 10f, routeProgress = 100f)
        val paused = state.pause().advance(2f, state.route.waypoints[0].position, 20f)
        assertTrue(paused.paused)
        assertEquals(1f, paused.elapsed)
        assertEquals(10f, paused.hardFluxCost)
        assertEquals(1f, paused.noBenefitTime)
        assertEquals(state.progress, paused.progress)
        val resumed = paused.resume(request.copy(position = paused.lastPosition,
            target = target.copy(facing = 30f), speed = 1000f), space)
        assertNull(resumed.failure)
        assertFalse(resumed.paused)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, resumed.waypoint.stage)
        assertEquals(30f, resumed.route.target.facing)
        assertEquals(0, resumed.replans)
        assertEquals(PhaseRouteReason.TIME_LIMIT, resumed.advance(4f, resumed.lastPosition, 0f).failure)
        assertEquals(PhaseRouteReason.HARD_FLUX_LIMIT, resumed.advance(0.1f, resumed.lastPosition, 90f).failure)
    }

    @Test
    fun `恢复路线以当前位置重新累计阶段位移`() {
        val state = PhaseRouteState(route(), 0f, stageTravel = 20f, lateralBeforeRoute = 100f)
        val paused = state.pause().advance(2f, PhasePosition(2000f, 2000f), 0f)
        val current = PhasePosition(900f, 700f)

        val resumed = paused.resume(request.copy(position = current), space)
        val direction = (resumed.waypoint.position - current).unit()
        val advanced = resumed.advance(0.2f, current + direction * 10f, 0f)

        assertEquals(100f, paused.lateralTravel)
        assertEquals(current, resumed.lastPosition)
        assertEquals(100f, resumed.lateralTravel)
        assertTrue(advanced.stageTravel < 50f)
        assertEquals(30f, advanced.stageTravel, 0.01f)
    }

    @Test
    fun `无收益累计不被短进步或重规划清空`() {
        var state = PhaseRouteState(route(), 0f, limits = PhaseRouteLimits(maxNoBenefitTime = 2f))
        val direction = (state.route.waypoints[0].position - request.position).unit()
        state = state.advance(1f, request.position, 0f)
        state = state.advance(0.1f, request.position + direction * 100f, 0f)
        assertEquals(1f, state.noBenefitTime)
        state = PhaseRoutePlanner.replan(state, request.copy(position = state.lastPosition,
            target = target.copy(position = PhasePosition(200f, 0f))), space)
        assertEquals(PhaseRouteReason.NO_BENEFIT, state.advance(1f, state.lastPosition, 0f).failure)
    }

    @Test
    fun `高频子步的小幅持续进度不算停滞且静止不能刷进度`() {
        var state = PhaseRouteState(route(), 0f)
        val direction = (state.route.waypoints[0].position - request.position).unit()
        repeat(100) { index -> state = state.advance(0.01f, request.position + direction * (index + 1f), 0f) }
        assertEquals(0f, state.noBenefitTime)
        assertEquals(100f, state.progress, 0.01f)
        state = state.advance(1f, request.position, 0f)
        state = state.advance(1f, state.lastPosition, 0f)
        assertEquals(100f, state.progress, 0.01f)
        assertEquals(2f, state.noBenefitTime)
    }

    @Test
    fun `追踪更新后的终点属于有效收益`() {
        val initial = PhaseRouteState(
            route(), 0f,
            waypointIndex = 2,
            limits = PhaseRouteLimits(maxNoBenefitTime = 2f),
            noBenefitTime = 1.5f,
            lastPosition = PhasePosition(0f, 800f),
        )

        val following = initial.advance(
            0.6f,
            PhasePosition(0f, 820f),
            0f,
            destination = PhasePosition(0f, 1000f),
        )

        assertNull(following.failure)
        assertEquals(1.5f, following.noBenefitTime)
    }

    @Test
    fun `路线起点后方向路径点接近也属于有效收益`() {
        val initial = PhaseRouteState(
            route(), 0f,
            limits = PhaseRouteLimits(maxNoBenefitTime = 2f),
            noBenefitTime = 1.5f,
            lastPosition = request.position + PhasePosition(-200f, -300f),
        )
        val closing = initial.advance(
            0.5f,
            initial.lastPosition + PhasePosition(25f, 40f),
            0f,
        )

        assertNull(closing.failure)
        assertEquals(1.5f, closing.noBenefitTime)
        val retreating = closing.advance(0.5f, closing.lastPosition, 0f)
        assertEquals(2f, retreating.noBenefitTime)
        assertEquals(PhaseRouteReason.NO_BENEFIT, retreating.failure)
    }

    @Test
    fun `目标预测后远程与近距离路线均可生成且重规划可行`() {
        val actual = PhaseRouteRequest(
            position = PhasePosition(1215f, 2192f),
            target = PhaseRouteTarget("enemy", PhasePosition(1416f, 1117f), 100f, 180f,
                velocity = PhasePosition(12f, 9f)),
            shipRadius = 97f,
            speed = 130f,
            planningInterval = 0.2f,
            attackRange = 612f,
            lateralDistance = 776f,
            frontHalfArc = 35f,
            maxElapsed = 36f,
        )
        val phased = space.copy(collides = false)
        val far = assertNotNull(PhaseRoutePlanner.planDetailed(actual, phased).route)
        assertTrue(far.score < actual.maxElapsed)
        val near = actual.copy(position = PhasePosition(1287f, 1805f))
        val planning = PhaseRoutePlanner.planDetailed(near, phased)
        val planned = assertNotNull(planning.route, planning.sideResults)
        val state = PhaseRouteState(planned, 0f).advance(0.36f, near.position, 0f)
        val replanned = PhaseRoutePlanner.replan(state, near.copy(
            target = actual.target.copy(position = PhasePosition(1420f, 1120f), facing = 99.5f),
        ), phased)

        assertNull(replanned.failure)
        assertTrue(state.elapsed < 1f)
    }

    @Test
    fun `目标交汇按战场时钟而不是相位本地时钟计算`() {
        val movingTarget = target.copy(position = PhasePosition(0f, -1000f), velocity = PhasePosition(0f, 1000f))
        val crossingRequest = request.copy(
            position = PhasePosition(1000f, 0f),
            target = movingTarget,
            speed = 1000f,
        )
        val crossing = PhaseRoute(
            movingTarget,
            PhaseRouteSide.LEFT,
            crossingRequest.position,
            listOf(PhaseWaypoint(PhasePosition(-1000f, 0f), PhaseRouteStage.LATERAL)),
            crossingRequest.attackRange,
            1,
            0f,
            0f,
        )

        assertEquals(PhaseRouteReason.COLLISION,
            PhaseRoutePlanner.evaluate(crossing, crossingRequest, space).failure)
        assertNull(PhaseRoutePlanner.evaluate(
            crossing,
            crossingRequest,
            space.copy(battleTimePerLocalSecond = 0.1f),
        ).failure)
    }

    @Test
    fun `六倍半径长侧移在激活费纳入恢复后的航速下保留时间余量`() {
        val actual = PhaseRouteRequest(
            position = PhasePosition(1468.4846f, 134.59538f),
            target = PhaseRouteTarget("enemy", PhasePosition(2146.542f, -221.7793f), 138.38249f, 152.6251f,
                velocity = PhasePosition(19.493755f, -22.798992f)),
            shipRadius = 97f,
            speed = 103.87f,
            planningInterval = 0.2f,
            attackRange = 612f,
            lateralDistance = 600f,
            frontHalfArc = 35f,
            maxElapsed = 24f,
        )

        val planning = PhaseRoutePlanner.planDetailed(actual, space.copy(battleTimePerLocalSecond = 1f / 3f))

        val route = assertNotNull(planning.route, planning.sideResults)
        assertTrue(route.lengthBefore(route.waypoints.size) < 2400f,
            "side=${route.side}, length=${route.lengthBefore(route.waypoints.size)}, score=${route.score}, waypoints=${route.waypoints}")
        assertTrue(route.score < 23f)
    }

    @Test
    fun `目标移动只用于到达时间评分且执行跟踪不会重复叠加提前量`() {
        val moving = request.copy(target = target.copy(velocity = PhasePosition(20f, 0f)))
        val planned = assertNotNull(PhaseRoutePlanner.plan(moving, space))
        val stationary = assertNotNull(PhaseRoutePlanner.plan(request, space))
        assertEquals(stationary.waypoints, planned.waypoints)

        val currentTarget = moving.target.copy(position = PhasePosition(200f, 0f))
        val tracked = PhaseRouteState(planned, 0f).trackTarget(currentTarget)

        assertEquals(planned.waypoints[0].position + PhasePosition(200f, 0f), tracked.route.waypoints[0].position)
        for (index in 1..planned.waypoints.lastIndex) {
            val expected = planned.waypoints[index].position + PhasePosition(200f, 0f)
            assertEquals(expected.x, tracked.route.waypoints[index].position.x, 0.01f)
            assertEquals(expected.y, tracked.route.waypoints[index].position.y, 0.01f)
        }
    }

    @Test
    fun `侧方进入需实际位移且完成后离开侧后区会重新打开路线`() {
        val attackPosition = route().waypoints.last().position
        val lateral = PhaseRouteState(route(), 0f)
        val sideEntry = lateral.copy(waypointIndex = 1)
        val attack = lateral.copy(waypointIndex = 2)

        assertEquals(lateral, lateral.completeAttack(attackPosition, target, true))
        assertEquals(sideEntry, sideEntry.completeAttack(attackPosition, target, true))
        assertEquals(attack, attack.completeAttack(attackPosition, target, false))
        assertEquals(attack, attack.completeAttack(request.position, target, true))

        val entered = sideEntry.copy(stageTravel = 50f).completeAttack(attackPosition, target, true)
        assertTrue(entered.completed)
        assertEquals(entered.route.waypoints.lastIndex, entered.waypointIndex)
        val completed = attack.completeAttack(attackPosition, target, true)
        assertTrue(completed.completed)
        assertEquals(attack.route.waypoints.lastIndex, completed.waypointIndex)

        val reopened = completed.completeAttack(request.position, target, true)
        assertFalse(reopened.completed)
        assertNull(reopened.failure)
        assertEquals(0f, reopened.stageTravel)
    }

    @Test
    fun `第一阶段同时平移转向仍保留安全侧移路线`() {
        val initial = PhaseRouteState(route(), 0f)
        val movedTarget = target.copy(position = PhasePosition(250f, 100f), facing = 60f)
        val result = PhaseRoutePlanner.replan(initial, request.copy(target = movedTarget), space)

        assertNull(result.failure)
        assertEquals(0, result.replans)
        assertEquals(PhaseRouteReason.TARGET_MOVED, result.lastReplanReason)
        assertEquals(initial.route.version, result.route.version)
        assertEquals(initial.route.waypoints[0].position + PhasePosition(250f, 100f), result.route.waypoints[0].position)
    }

    @Test
    fun `替代路线超时不能保留已经判定危险的旧路线`() {
        val dangerousRoute = PhaseRoute(
            target, PhaseRouteSide.LEFT, request.position,
            listOf(PhaseWaypoint(request.position + PhasePosition(0f, 300f), PhaseRouteStage.LATERAL)),
            request.attackRange, 1, 0f, 0f,
        )
        val state = PhaseRouteState(dangerousRoute, 0f)
        val dangerous = space.copy(damagePerSecond = { point, _ -> if (point.y > 0f) 100f else 0f })
        val result = PhaseRoutePlanner.replan(state, request.copy(maxDamage = 1f, maxElapsed = 0.25f), dangerous)

        assertEquals(PhaseRouteReason.DANGER, result.failure)
    }

    @Test
    fun `当前航速不可完成时保留路线等待恢复并在可行后解除`() {
        val initial = PhaseRouteState(route(), 0f, waypointIndex = 1, stageTravel = 50f)
        val waiting = PhaseRoutePlanner.replan(initial, request.copy(speed = 1f), space)

        assertTrue(waiting.recoveryRequired)
        assertNull(waiting.failure)
        assertEquals(initial.waypointIndex, waiting.waypointIndex)
        assertEquals(initial.stageTravel, waiting.stageTravel)
        assertEquals(initial.replans, waiting.replans)

        val resumed = PhaseRoutePlanner.replan(waiting, request.copy(speed = 1000f), space)
        assertFalse(resumed.recoveryRequired)
        assertNull(resumed.failure)
        assertEquals(waiting.route, resumed.route)
    }

    @Test
    fun `长侧向恢复选择最快恢复的位置而非最远位置`() {
        val recoverySpace = space.copy(recoverySeconds = { p -> if (p.y > 0f) 100f else 1f })
        val route = assertNotNull(PhaseRoutePlanner.plan(request, recoverySpace, recovery = true))
        assertTrue(route.recovery)
        assertEquals(PhaseRouteSide.RIGHT, route.side)
        val displacement = route.waypoints.single().position - request.position
        assertTrue(abs(displacement.y) >= 600f)
        assertTrue(abs(displacement.y) > abs(displacement.x))
        assertTrue(displacement.x > 0f)
    }

    @Test
    fun `相位路线按实际侧移和紧凑进入路径评估执行预算`() {
        val phased = space.copy(collides = false)
        val ordinary = PhaseRoutePlanner.evaluate(route(), request, space)
        val executable = PhaseRoutePlanner.evaluate(route(), request, phased)

        assertTrue(executable.score < ordinary.score)
        val budget = (ordinary.score + executable.score) * 0.5f
        assertEquals(
            PhaseRouteReason.TIME_LIMIT,
            PhaseRoutePlanner.evaluate(route(), request.copy(maxElapsed = budget), space).failure,
        )
        assertNull(PhaseRoutePlanner.evaluate(route(), request.copy(maxElapsed = budget), phased).failure)
    }

    @Test
    fun `旧相位路线重验证同样使用紧凑执行路径而不是静态全长`() {
        val initial = PhaseRouteState(route(), 0f, waypointIndex = 1, stageTravel = 50f)
        val slow = request.copy(speed = 60f, maxElapsed = 23f)

        val ordinary = PhaseRoutePlanner.replan(initial, slow, space)
        val phased = PhaseRoutePlanner.replan(initial, slow, space.copy(collides = false))

        assertTrue(ordinary.recoveryRequired)
        assertFalse(phased.recoveryRequired)
        assertNull(phased.failure)
    }
}
