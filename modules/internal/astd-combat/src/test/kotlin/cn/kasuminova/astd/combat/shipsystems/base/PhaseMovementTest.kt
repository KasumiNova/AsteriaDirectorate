package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseMovementTest {
    private val target = PhaseRouteTarget("enemy", PhasePosition.ZERO, 0f, 100f)
    private val space = PhaseRouteSpace(PhaseBounds(-6000f, 6000f, -6000f, 6000f))
    private val request = PhaseRouteRequest(PhasePosition(1000f, 0f), target, 30f, 130f, 0.2f, 600f)
    private val route = assertNotNull(PhaseRoutePlanner.plan(request, space))
    private val motion = PhaseMotion(
        position = request.position,
        velocity = PhasePosition.ZERO,
        facing = 0f,
        angularVelocity = 0f,
        radius = 30f,
        maxSpeed = 130f,
        acceleration = 100f,
        deceleration = 100f,
        strafeAcceleration = 100f,
        turnAcceleration = 80f,
        maxTurnRate = 50f,
    )
    private val tuning = PhaseMovementTuning()

    @Test
    fun `中间点允许近距离通过而不是要求精确坐标`() {
        val start = PhasePosition.ZERO
        val end = PhasePosition(100f, 0f)
        assertTrue(PhaseMovement.reached(PhasePosition(90f, 0f), start, end, tuning))
        assertTrue(PhaseMovement.reached(PhasePosition(200f, 70f), start, end, tuning))
        assertFalse(PhaseMovement.reached(PhasePosition(200f, 200f), start, end, tuning))
        assertFalse(PhaseMovement.reached(PhasePosition(20f, 0f), start, end, tuning))
    }

    @Test
    fun `长侧移按当前实际位移推进而不依赖预置进度或上一帧读数`() {
        val waiting = PhaseMovement.step(PhaseRouteState(route, 0f), motion, target, space, 0.01f)
        assertEquals(0, waiting.state.waypointIndex)
        assertTrue(waiting.controlsMovement)
        assertTrue(waiting.allowed.isNotEmpty())

        val atWaypoint = motion.copy(position = route.waypoints[0].position)
        val ready = PhaseMovement.step(PhaseRouteState(route, 0f), atWaypoint, target, space, 0.01f)
        assertEquals(1, ready.state.waypointIndex)
        assertEquals(0.01f, ready.state.elapsed)
        assertTrue(ready.controlsMovement)
        assertTrue(ready.allowed.isNotEmpty())
    }

    @Test
    fun `长侧移接近终点后进入侧方进入段而不反复上浮`() {
        val waypoint = route.waypoints[0].position
        val direction = (waypoint - request.position).unit()
        val near = motion.copy(position = waypoint - direction * 170f)
        val result = PhaseMovement.step(PhaseRouteState(route, 0f, lateralBeforeRoute = 400f), near, target, space, 0.01f)

        assertEquals(PhaseRouteStage.SIDE_ENTRY, result.state.waypoint.stage)
        assertTrue(result.controlsMovement)
    }

    @Test
    fun `已经进入侧后攻击区也必须完成侧移后按顺序进入后续阶段`() {
        val atAttack = motion.copy(position = route.waypoints.last().position, canAttackTarget = true)
        val waiting = PhaseMovement.step(PhaseRouteState(route, 0f), atAttack, target, space, 0.01f)
        assertEquals(0, waiting.state.waypointIndex)
        assertFalse(waiting.state.completed)

        val entering = PhaseMovement.step(
            PhaseRouteState(route, 0f, lateralBeforeRoute = 400f),
            atAttack, target, space, 0.01f,
        )
        assertEquals(1, entering.state.waypointIndex)
        assertFalse(entering.state.completed)
        assertTrue(entering.controlsMovement)

        val ready = entering.state.copy(routeProgress = route.lengthBefore(1) + 50f, stageTravel = 50f)
        val result = PhaseMovement.step(ready, atAttack, target, space, 0.01f)
        assertEquals(route.waypoints.lastIndex, result.state.waypointIndex)
        assertTrue(result.state.completed)
        assertFalse(result.controlsMovement)
    }

    @Test
    fun `同帧完成或阶段推进优先于停滞时限和硬辐能失败`() {
        val atAttack = motion.copy(position = route.waypoints.last().position, canAttackTarget = true)
        val states = listOf(
            PhaseRouteState(route, 0f, limits = PhaseRouteLimits(maxNoBenefitTime = 2f), noBenefitTime = 1.99f),
            PhaseRouteState(route, 0f, limits = PhaseRouteLimits(maxElapsed = 0.01f)),
            PhaseRouteState(route, 0f, limits = PhaseRouteLimits(maxHardFluxCost = 1f)),
        ).map { it.copy(waypointIndex = 1, routeProgress = route.lengthBefore(1) + 50f, stageTravel = 50f) }
        for (state in states) {
            val result = PhaseMovement.step(state, atAttack, target, space, 0.02f, hardFluxSpent = 2f)
            assertTrue(result.state.completed)
            assertNull(result.state.failure)
            assertFalse(result.controlsMovement)
        }

        val sideEntry = PhaseRouteState(route, 0f, limits = PhaseRouteLimits(maxNoBenefitTime = 2f),
            waypointIndex = 1, noBenefitTime = 1.99f, routeProgress = route.lengthBefore(1) + 50f, stageTravel = 50f)
        val promoted = PhaseMovement.step(sideEntry, atAttack.copy(canAttackTarget = false), target, space, 0.02f)
        assertEquals(route.waypoints.lastIndex, promoted.state.waypointIndex)
        assertNull(promoted.state.failure)
        assertTrue(promoted.controlsMovement)
    }

    @Test
    fun `侧方进入段只有形成真实后弧才晋升攻击阶段`() {
        val state = PhaseRouteState(route, 0f, waypointIndex = 1, routeProgress = route.lengthBefore(1) + 50f, stageTravel = 50f)
        val side = PhaseMovement.step(state, motion.copy(position = PhasePosition(300f, 600f)), target, space, 0.01f)
        assertEquals(1, side.state.waypointIndex)
        assertTrue(side.controlsMovement)
        assertFalse(side.state.completed)

        val front = PhaseMovement.step(state, motion.copy(position = PhasePosition(700f, 0f)), target, space, 0.01f)
        assertEquals(1, front.state.waypointIndex)
        val close = PhaseMovement.step(state, motion.copy(position = PhasePosition(500f, 0f)), target, space, 0.01f)
        assertEquals(1, close.state.waypointIndex)
        assertFalse(close.state.completed)
        val rear = PhaseMovement.step(
            state,
            motion.copy(position = route.waypoints.last().position),
            target,
            space,
            0.01f,
        )
        assertEquals(route.waypoints.lastIndex, rear.state.waypointIndex)
    }

    @Test
    fun `紧凑穿后捕获写回路线但不会替代真实后弧验收`() {
        val state = PhaseRouteState(
            route, 0f, waypointIndex = 1, routeProgress = route.lengthBefore(1) + 50f, stageTravel = 50f,
        )
        val body = motion.copy(position = PhasePosition(300f, 600f))
        val phasedSpace = space.copy(collides = false)

        val captured = PhaseMovement.step(state, body, target, phasedSpace, 0.01f)
        val following = PhaseMovement.step(captured.state, body, target, phasedSpace, 0.01f)

        assertTrue(captured.state.sideEntryCapture)
        assertTrue(following.state.sideEntryCapture)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, following.state.waypoint.stage)
        assertFalse(following.state.completed)
        assertTrue(following.controlsMovement)
    }

    @Test
    fun `长侧移形成侧方进入条件后不再追逐移动中的旧终点`() {
        val side = motion.copy(position = PhasePosition(-300f, 600f * route.side.sign))
        val state = PhaseRouteState(route, 0f, lastPosition = side.position)
        val result = PhaseMovement.step(state, side, target, space, 0.01f)

        assertEquals(
            PhaseRouteStage.SIDE_ENTRY,
            result.state.waypoint.stage,
            "side=${route.side}, lateral=${state.lateralTravelAt(side.position)}, " +
                "canCommit=${route.canCommitAttack(side.position, target)}, failure=${result.state.failure}, result=$result",
        )
        assertFalse(result.state.completed)
        assertTrue(result.controlsMovement)

        val front = PhaseMovement.step(state, motion.copy(position = PhasePosition(700f, 0f)), target, space, 0.01f)
        assertEquals(PhaseRouteStage.LATERAL, front.state.waypoint.stage)
    }

    @Test
    fun `攻击区重叠时继续跟随路线直到可以安全上浮`() {
        val atAttack = motion.copy(position = route.waypoints.last().position, canAttackTarget = true, surfaceUnsafe = true)
        val state = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex)
        val unsafe = PhaseMovement.step(state, atAttack, target, space, 0.01f)

        assertFalse(unsafe.state.completed)
        assertTrue(unsafe.controlsMovement)

        val safe = PhaseMovement.step(unsafe.state, atAttack.copy(surfaceUnsafe = false), target, space, 0.01f)
        assertTrue(safe.state.completed)
        assertFalse(safe.controlsMovement)
    }

    @Test
    fun `攻击段实际重叠时先向舰体外侧脱离再完成路线`() {
        val state = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex)
        val overlap = motion.copy(position = PhasePosition(-40f, 0f), canAttackTarget = true, surfaceUnsafe = true)
        val unsafe = PhaseMovement.step(state, overlap, target, space.copy(collides = false), 0.01f)

        assertFalse(unsafe.state.completed)
        assertTrue(unsafe.controlsMovement)
        assertTrue(ShipCommand.ACCELERATE_BACKWARDS in unsafe.allowed, unsafe.allowed.toString())

        val safe = PhaseMovement.step(
            unsafe.state,
            overlap.copy(position = route.waypoints.last().position, surfaceUnsafe = false),
            target,
            space.copy(collides = false),
            0.01f,
        )
        assertTrue(safe.state.completed)
    }

    @Test
    fun `主目标重叠时脱离制导不使用旋转前馈`() {
        val state = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex)
        val overlap = motion.copy(position = PhasePosition(-40f, 0f), canAttackTarget = true, surfaceUnsafe = true)
        val phasedSpace = space.copy(collides = false)
        val stationary = PhaseMovement.step(state, overlap, target, phasedSpace, 0.01f)
        val rotating = PhaseMovement.step(
            state,
            overlap,
            target.copy(angularVelocity = 20f),
            phasedSpace,
            0.01f,
        )

        assertFalse(rotating.state.completed)
        assertTrue(rotating.controlsMovement)
        assertEquals(stationary.allowed, rotating.allowed)
        assertTrue(
            PhaseMovement.attackDestination(rotating.state, overlap, target, phasedSpace).x < overlap.position.x,
        )

        val entering = PhaseMovement.step(
            state.copy(waypointIndex = 1, sideEntryCapture = true),
            overlap,
            target.copy(angularVelocity = 20f),
            phasedSpace,
            0.01f,
        )
        assertTrue(entering.controlsMovement)
        assertFalse(entering.state.completed)
        assertEquals(
            PhasePosition.ZERO,
            PhaseMovement.attackDestinationVelocity(entering.state, overlap, target, phasedSpace),
        )
    }

    @Test
    fun `重规划从侧方进入开始时按当前段累计五十单位进度`() {
        val replanned = assertNotNull(PhaseRoutePlanner.candidate(
            request, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.SIDE_ENTRY, 3,
        ))
        val state = PhaseRouteState(replanned, 0f, routeProgress = 50f, stageTravel = 50f)
        val result = PhaseMovement.step(
            state,
            motion.copy(position = replanned.waypoints.last().position),
            target,
            space,
            0.01f,
        )

        assertEquals(PhaseRouteStage.ATTACK, result.state.waypoint.stage)
        assertFalse(result.state.completed)
        assertTrue(result.controlsMovement)
    }

    @Test
    fun `侧方进入到点不能跳过五十单位本舰实际位移`() {
        val entry = route.waypoints[1].position
        val state = PhaseRouteState(
            route, 0f, waypointIndex = 1, lastPosition = entry, routeProgress = route.lengthBefore(1),
        )
        val waiting = PhaseMovement.step(state, motion.copy(position = entry, canAttackTarget = true), target, space, 0.01f)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, waiting.state.waypoint.stage)
        assertEquals(0f, waiting.state.stageTravel)
        assertFalse(waiting.state.completed)
        assertTrue(waiting.controlsMovement)

        val direction = (route.waypoints.last().position - entry).unit()
        val body = motion.copy(position = entry + direction * 60f, canAttackTarget = true)
        val travelled = PhaseMovement.step(waiting.state, body, target, space, 0.01f)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, travelled.state.waypoint.stage)
        assertTrue(travelled.state.stageTravel >= 50f)
        assertFalse(travelled.state.completed)

        val completed = PhaseMovement.step(travelled.state, body, target, space, 0.01f)
        assertEquals(PhaseRouteStage.ATTACK, completed.state.waypoint.stage)
        assertTrue(completed.state.completed)
    }

    @Test
    fun `终点等待武器对准不累计无收益但仍受总时限约束`() {
        val end = route.waypoints.last().position
        val state = PhaseRouteState(
            route, 0f, limits = PhaseRouteLimits(maxNoBenefitTime = 2f), waypointIndex = route.waypoints.lastIndex,
            noBenefitTime = 1.99f, lastPosition = end,
        )
        val waiting = PhaseMovement.step(state, motion.copy(position = end), target, space, 0.02f)
        assertNull(waiting.state.failure)
        assertEquals(state.noBenefitTime, waiting.state.noBenefitTime)
        assertFalse(waiting.state.completed)

        val expired = PhaseMovement.step(
            state.copy(limits = PhaseRouteLimits(maxElapsed = 0.01f)),
            motion.copy(position = end), target, space, 0.02f,
        )
        assertEquals(PhaseRouteReason.TIME_LIMIT, expired.state.failure)
    }

    @Test
    fun `长侧移路径点继承目标平移速度避免稳定追尾`() {
        val movingTarget = target.copy(velocity = PhasePosition(100f, 0f))
        val movingRoute = assertNotNull(PhaseRoutePlanner.plan(request.copy(target = movingTarget), space))
        val waypoint = movingRoute.waypoints[0].position
        val body = motion.copy(
            position = waypoint - PhasePosition(500f, 0f),
            velocity = movingTarget.velocity,
            deceleration = 10f,
            strafeAcceleration = 10f,
        )

        val result = PhaseMovement.step(PhaseRouteState(movingRoute, 0f), body, movingTarget, space, 0.02f)

        assertTrue(result.allowed.any {
            it == ShipCommand.ACCELERATE || it == ShipCommand.ACCELERATE_BACKWARDS ||
                it == ShipCommand.STRAFE_LEFT || it == ShipCommand.STRAFE_RIGHT
        })
    }

    @Test
    fun `补足侧移主导目的地时目标反向平移不会抵消剩余侧移`() {
        val leftRoute = assertNotNull(PhaseRoutePlanner.candidate(
            request, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.LATERAL, 1,
        )).copy(
            waypoints = listOf(
                PhaseWaypoint(PhasePosition(0f, 300f), PhaseRouteStage.LATERAL),
                route.waypoints[1], route.waypoints[2],
            ),
        )
        val movingTarget = target.copy(velocity = PhasePosition(0f, -40f))
        val state = PhaseRouteState(
            leftRoute, 0f, lateralOrigin = PhasePosition.ZERO, lateralFacing = 0f,
            lastPosition = PhasePosition(0f, 390f),
        )
        val body = motion.copy(
            position = PhasePosition(0f, 390f), facing = 0f,
            deceleration = 10f, strafeAcceleration = 10f,
        )

        val result = PhaseMovement.step(state, body, movingTarget, space, 0.02f)

        assertTrue(ShipCommand.STRAFE_LEFT in result.allowed, result.allowed.toString())
        assertFalse(ShipCommand.STRAFE_RIGHT in result.allowed, result.allowed.toString())
    }

    @Test
    fun `暂停恢复后的补足目的地可以同时满足实际侧移和到点推进`() {
        val leftRoute = assertNotNull(PhaseRoutePlanner.candidate(
            request, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.LATERAL, 1,
        ))
        val position = PhasePosition(0f, 1000f)
        val state = PhaseRouteState(
            leftRoute, 0f, lateralBeforeRoute = 100f,
            lateralOrigin = PhasePosition(0f, 700f), lateralFacing = 0f, lastPosition = position,
        )

        val result = PhaseMovement.step(state, motion.copy(position = position), target, space, 0.02f)

        assertNull(result.state.failure)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, result.state.waypoint.stage)
        assertTrue(result.state.lateralTravel >= 400f)
    }

    @Test
    fun `达到实际侧移门槛后不追逐仍远离的规划第一点`() {
        val leftRoute = assertNotNull(PhaseRoutePlanner.candidate(
            request, space, PhaseRouteSide.LEFT, false, PhaseRouteStage.LATERAL, 1,
        )).copy(
            waypoints = listOf(
                PhaseWaypoint(PhasePosition(0f, 1200f), PhaseRouteStage.LATERAL),
                route.waypoints[1], route.waypoints[2],
            ),
        )
        val position = PhasePosition(0f, 401f)
        val state = PhaseRouteState(
            leftRoute, 0f, lateralOrigin = PhasePosition.ZERO, lateralFacing = 0f, lastPosition = position,
        )

        val result = PhaseMovement.step(state, motion.copy(position = position), target, space, 0.02f)

        assertNull(result.state.failure)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, result.state.waypoint.stage)
    }

    @Test
    fun `长侧移到点同帧先推进阶段而不是判无收益失败`() {
        val waypoint = route.waypoints[0].position
        val direction = (waypoint - request.position).unit()
        val state = PhaseRouteState(route, 0f, noBenefitTime = 1.99f, lateralBeforeRoute = 400f)
        val body = motion.copy(position = waypoint - direction * 179.99f)

        val result = PhaseMovement.step(state, body, target, space, 0.02f)

        assertNull(result.state.failure)
        assertEquals(PhaseRouteStage.SIDE_ENTRY, result.state.waypoint.stage)
        assertEquals(0f, result.state.noBenefitTime)
    }

    @Test
    fun `制动距离和纵横推力互斥`() {
        val speed = 200f
        val deceleration = 100f
        assertEquals(speed * speed / (2f * deceleration), PhaseMovement.stoppingDistance(speed, deceleration))
        val moving = motion.copy(position = PhasePosition.ZERO, velocity = PhasePosition(speed, 0f), deceleration = deceleration)
        val allowed = PhaseMovement.allowed(moving, PhasePosition(100f, 0f))
        assertEquals(setOf(ShipCommand.DECELERATE), allowed)
        assertFalse(ShipCommand.ACCELERATE in allowed)
        assertFalse(ShipCommand.ACCELERATE_BACKWARDS in allowed)
        assertTrue(PhaseMovement.stoppingDistance(speed, 0f).isInfinite())
    }

    @Test
    fun `近目标线速度和角速度死区屏蔽整轴`() {
        val body = motion.copy(position = PhasePosition.ZERO, velocity = PhasePosition(1f, 1f), angularVelocity = 0.1f)
        assertTrue(PhaseMovement.allowed(body, PhasePosition(35f, 0f)).isEmpty())
        val turning = PhaseMovement.allowed(body.copy(angularVelocity = 20f), PhasePosition(35f, 0f))
        assertEquals(setOf(ShipCommand.TURN_RIGHT), turning)
    }

    @Test
    fun `移动攻击点按目标速度跟随而不是到位后绝对停车`() {
        val body = motion.copy(position = PhasePosition.ZERO, velocity = PhasePosition(80f, 0f))
        val allowed = PhaseMovement.allowed(
            body,
            PhasePosition(20f, 0f),
            destinationVelocity = PhasePosition(80f, 0f),
        )
        assertTrue(allowed.isEmpty())
        assertFalse(ShipCommand.DECELERATE in allowed)
    }

    @Test
    fun `转向使用最短方向并提前反向制动`() {
        val body = motion.copy(position = PhasePosition.ZERO, facing = 359f)
        val straight = PhaseMovement.allowed(body, PhasePosition(1000f, 0f))
        assertFalse(ShipCommand.TURN_LEFT in straight || ShipCommand.TURN_RIGHT in straight)
        val fast = PhaseMovement.allowed(body.copy(facing = 0f, angularVelocity = 80f),
            PhasePosition.facing(10f) * 1000f)
        assertTrue(ShipCommand.TURN_RIGHT in fast)
        assertFalse(ShipCommand.TURN_LEFT in fast)
    }

    @Test
    fun `受控侧移可以规避惯性相撞但无法规避时仍取消特殊移动`() {
        val moving = motion.copy(velocity = PhasePosition(200f, 0f))
        val avoidable = space.copy(obstacles = listOf(PhaseObstacle(PhasePosition(1300f, 0f), 40f)))
        val steering = PhaseMovement.step(PhaseRouteState(route, 0f), moving, target, avoidable, 0.02f)
        assertNull(steering.state.failure)
        assertTrue(steering.controlsMovement)

        val unavoidable = space.copy(obstacles = listOf(PhaseObstacle(PhasePosition(1100f, 0f), 40f)))
        val result = PhaseMovement.step(PhaseRouteState(route, 0f), moving, target, unavoidable, 0.02f)
        assertEquals(PhaseRouteReason.COLLISION, result.state.failure)
        assertFalse(result.controlsMovement)
        assertTrue(result.allowed.isEmpty())
        val phased = unavoidable.copy(collides = false)
        val phasedResult = PhaseMovement.step(PhaseRouteState(route, 0f), moving, target, phased, 0.02f)
        assertNull(phasedResult.state.failure)
        assertTrue(phasedResult.controlsMovement)

        val edge = space.copy(bounds = PhaseBounds(-6000f, 1250f, -6000f, 6000f))
        assertEquals(PhaseRouteReason.BOUNDARY,
            PhaseMovement.step(PhaseRouteState(route, 0f), moving, target, edge, 0.02f).state.failure)
        assertEquals(PhaseRouteReason.BOUNDARY,
            PhaseMovement.step(PhaseRouteState(route, 0f), moving, target, edge.copy(collides = false), 0.02f).state.failure)
    }

    @Test
    fun `地图内部的充入高速侧移不会被误判为越界`() {
        val body = motion.copy(
            position = PhasePosition(927.3085f, 267.7339f),
            velocity = PhasePosition(22.759672f, 119.26209f),
            radius = 97f,
            acceleration = 40.75036f,
            deceleration = 40.75036f,
        )
        val realSpace = PhaseRouteSpace(PhaseBounds(-9000f, 9000f, -6000f, 6000f))
        val realTarget = PhaseRouteTarget(
            "enemy", PhasePosition(1493.3922f, -40.540005f), 179.50015f, 152.6251f,
            velocity = PhasePosition(-229.99553f, 1.4343114f),
        )

        assertNull(PhaseMovement.localRisk(body, PhasePosition(927.90356f, 856.76135f), realTarget, realSpace))
        assertNull(
            PhaseMovement.localRisk(
                body.copy(deceleration = 0.05f),
                PhasePosition(927.90356f, 856.76135f),
                realTarget,
                realSpace,
            ),
        )
    }

    @Test
    fun `静止本舰也检查驶入路径的船体`() {
        val incoming = PhaseObstacle(PhasePosition(1200f, 0f), 30f, PhasePosition(-400f, 0f))
        assertEquals(PhaseRouteReason.COLLISION,
            PhaseMovement.localRisk(motion, route.waypoints[0].position, target, space.copy(obstacles = listOf(incoming))))
        val movingTarget = target.copy(position = incoming.position, velocity = incoming.velocity)
        assertEquals(PhaseRouteReason.COLLISION,
            PhaseMovement.localRisk(motion, route.waypoints[0].position, movingTarget, space))
    }

    @Test
    fun `终点要求统一目标实际攻击许可不能仅因到达坐标结束`() {
        val state = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex)
        val atEnd = motion.copy(position = route.waypoints.last().position)
        val notReady = PhaseMovement.step(state, atEnd, target, space, 0.01f)
        assertFalse(notReady.state.completed)
        val ready = PhaseMovement.step(state, atEnd.copy(canAttackTarget = true), target, space, 0.01f)
        assertTrue(ready.state.completed)
        assertFalse(ready.controlsMovement)
        val outOfRange = atEnd.copy(position = PhasePosition(-800f, 800f), canAttackTarget = true)
        assertFalse(PhaseMovement.step(state, outOfRange, target, space, 0.01f).state.completed)
        assertEquals(PhaseRouteReason.TARGET_LOST,
            PhaseMovement.step(state, atEnd, target.copy(id = "other"), space, 0.01f).state.failure)
    }

    @Test
    fun `进入新路径段后重新计算停滞时间`() {
        val state = PhaseRouteState(
            route,
            0f,
            limits = PhaseRouteLimits(maxNoBenefitTime = 2f),
            noBenefitTime = 1.7f,
            lastPosition = route.waypoints.first().position,
        )

        val result = PhaseMovement.step(
            state,
            motion.copy(position = route.waypoints.first().position),
            target,
            space,
            0.2f,
        )

        assertNull(result.state.failure)
        assertEquals(1, result.state.waypointIndex)
        assertEquals(0f, result.state.noBenefitTime)
    }

    @Test
    fun `终点对准期间不错判无收益且完成优先于无收益超时`() {
        val state = PhaseRouteState(
            route,
            0f,
            waypointIndex = route.waypoints.lastIndex,
            limits = PhaseRouteLimits(maxNoBenefitTime = 2f),
            noBenefitTime = 1.99f,
        )
        val atEnd = motion.copy(position = route.waypoints.last().position)
        val aligning = PhaseMovement.step(state, atEnd, target, space, 0.2f)
        assertNull(aligning.state.failure)
        assertEquals(1.99f, aligning.state.noBenefitTime)

        val ready = PhaseMovement.step(aligning.state, atEnd.copy(canAttackTarget = true), target, space, 0.02f)
        assertTrue(ready.state.completed)
        assertNull(ready.state.failure)
    }

    @Test
    fun `旧终点离开侧后攻击区会重新定位而不是永久停车`() {
        val state = PhaseRouteState(
            route,
            0f,
            waypointIndex = route.waypoints.lastIndex,
            limits = PhaseRouteLimits(maxNoBenefitTime = 2f),
        )
        val oldEnd = route.waypoints.last().position
        val movedTarget = target.copy(position = oldEnd + PhasePosition(-150f, 0f), radius = 0f)
        val waiting = motion.copy(position = oldEnd, angularVelocity = 30f, canAttackTarget = false)

        val result = PhaseMovement.step(state, waiting, movedTarget, space, 2.1f)

        assertNull(result.state.failure)
        assertFalse(result.state.completed)
        assertTrue(result.controlsMovement)
        assertTrue(result.allowed.isNotEmpty())
    }

    @Test
    fun `终点修正离开旧坐标后不折返`() {
        val state = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex)
        val oldEnd = route.waypoints.last().position
        val movedTarget = target.copy(position = oldEnd + PhasePosition(-150f, 0f), radius = 0f)
        val body = motion.copy(position = oldEnd + PhasePosition(-60f, 0f), facing = 180f)

        val result = PhaseMovement.step(state, body, movedTarget, space, 0.02f)

        assertTrue(ShipCommand.ACCELERATE in result.allowed)
        assertFalse(ShipCommand.ACCELERATE_BACKWARDS in result.allowed)
    }

    @Test
    fun `恢复终点要减速且暂停不控制移动`() {
        val recovery = assertNotNull(PhaseRoutePlanner.plan(request, space, recovery = true))
        val state = PhaseRouteState(recovery, 0f)
        val body = motion.copy(position = recovery.waypoints.last().position)
        assertFalse(PhaseMovement.step(state, body.copy(velocity = PhasePosition(50f, 0f)), target, space, 0.01f).state.completed)
        assertTrue(PhaseMovement.step(state, body, target, space, 0.01f).state.completed)
        assertFalse(PhaseMovement.step(state.pause(), motion, target, space, 0.01f).controlsMovement)
    }

    @Test
    fun `最终命令集只屏蔽非允许移动并仅补发允许移动`() {
        val ship = mock(ShipAPI::class.java)
        val allowed = setOf(ShipCommand.ACCELERATE, ShipCommand.STRAFE_LEFT, ShipCommand.TURN_RIGHT)
        val result = PhaseMovementResult(PhaseRouteState(route, 0f), allowed, true)
        PhaseMovement.execute(ship, result)
        for (command in PhaseMovement.commands) {
            if (command in allowed) {
                verify(ship).giveCommand(command, null, 0)
                verify(ship, never()).blockCommandForOneFrame(command)
            } else {
                verify(ship).blockCommandForOneFrame(command)
                verify(ship, never()).giveCommand(command, null, 0)
            }
        }
        verifyNoMoreInteractions(ship)
        val idle = mock(ShipAPI::class.java)
        PhaseMovement.execute(idle, result.copy(controlsMovement = false))
        verifyNoInteractions(idle)
        assertFailsWith<IllegalArgumentException> {
            PhaseMovement.blocked(setOf(ShipCommand.TURN_LEFT, ShipCommand.TURN_RIGHT))
        }
        assertFailsWith<IllegalArgumentException> {
            PhaseMovement.blocked(setOf(ShipCommand.ACCELERATE, ShipCommand.DECELERATE))
        }
    }

    @Test
    fun `几乎正后方但横向间距不足时不提前完成侧后路线`() {
        val state = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex)
        val directlyBehind = motion.copy(position = PhasePosition(-150f, 1f), canAttackTarget = true)

        val waiting = PhaseMovement.step(state, directlyBehind, target, space, 0.01f)

        assertFalse(waiting.state.completed)
        assertTrue(waiting.controlsMovement)
        val side = PhasePosition.facing(target.facing).left() * route.side.sign
        val sideRear = directlyBehind.copy(position = PhasePosition(-200f, 0f) + side * 100f)
        assertTrue(PhaseMovement.step(waiting.state, sideRear, target, space, 0.01f).state.completed)
    }

    @Test
    fun `后向间距不足时也不能完成侧后路线`() {
        val state = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex)
        for (position in listOf(PhasePosition(-200f, 25f), PhasePosition(-24f, 65f))) {
            val result = PhaseMovement.step(
                state,
                motion.copy(position = position, canAttackTarget = true),
                target,
                space,
                0.01f,
            )
            assertFalse(result.state.completed, position.toString())
            assertTrue(result.controlsMovement, position.toString())
        }
    }

    @Test
    fun `攻击保持连续运动不会把横向间距缩回完成阈值内`() {
        for (routeSide in PhaseRouteSide.entries) {
            val currentRoute = assertNotNull(PhaseRoutePlanner.candidate(
                request, space, routeSide, false, PhaseRouteStage.LATERAL, 1,
            ))
            val side = PhasePosition.facing(target.facing).left() * routeSide.sign
            var state = PhaseRouteState(currentRoute, 0f, waypointIndex = currentRoute.waypoints.lastIndex)
            var body = motion.copy(position = PhasePosition(-500f, 0f) + side * 26f)
            repeat(120) {
                val result = PhaseMovement.step(state, body, target, space, 0.02f)
                state = result.state
                assertNull(state.failure)
                val commands = result.allowed
                val turn = when {
                    ShipCommand.TURN_LEFT in commands -> 1f
                    ShipCommand.TURN_RIGHT in commands -> -1f
                    else -> 0f
                }
                val angular = (body.angularVelocity + turn * body.turnAcceleration * 0.02f)
                    .coerceIn(-body.maxTurnRate, body.maxTurnRate)
                val facing = body.facing + angular * 0.02f
                val forward = PhasePosition.facing(facing)
                var velocity = body.velocity
                if (ShipCommand.DECELERATE in commands) {
                    velocity = velocity.unit() * max(0f, velocity.length() - body.deceleration * 0.02f)
                } else {
                    if (ShipCommand.ACCELERATE in commands) velocity += forward * (body.acceleration * 0.02f)
                    if (ShipCommand.ACCELERATE_BACKWARDS in commands) velocity -= forward * (body.deceleration * 0.02f)
                    if (ShipCommand.STRAFE_LEFT in commands) velocity += forward.left() * (body.strafeAcceleration * 0.02f)
                    if (ShipCommand.STRAFE_RIGHT in commands) velocity -= forward.left() * (body.strafeAcceleration * 0.02f)
                }
                if (velocity.length() > body.maxSpeed) velocity = velocity.unit() * body.maxSpeed
                body = body.copy(
                    position = body.position + velocity * 0.02f,
                    velocity = velocity,
                    facing = facing,
                    angularVelocity = angular,
                )
                assertTrue(currentRoute.inAttackArea(body.position, target),
                    "side=$routeSide position=${body.position} destinationState=${state.waypoint.stage}")
            }
        }
    }

    @Test
    fun `纯运动积分能跟随三段路线并进入实际攻击区`() {
        val request = request.copy(
            position = PhasePosition(700f, 0f), frontHalfArc = 30f, clearance = 30f, maxElapsed = 24f,
        )
        val result = simulate(request, target)

        assertNull(result.state.failure, "stage=${result.state.waypoint.stage}, position=${result.body.position}, target=${result.target}, canAttack=${result.body.canAttackTarget}, inArea=${result.state.route.inAttackArea(result.body.position, result.target)}, elapsed=${result.state.elapsed}")
        assertTrue(result.state.completed)
        assertEquals(setOf(PhaseRouteStage.LATERAL, PhaseRouteStage.SIDE_ENTRY, PhaseRouteStage.ATTACK), result.seen)
        assertTrue(result.state.route.inAttackArea(result.body.position, result.target))
    }

    @Test
    fun `持续转向目标通过提前拦截进入真实侧后攻击区`() {
        val request = request.copy(
            position = PhasePosition(1000f, 0f), frontHalfArc = 30f, clearance = 30f, maxElapsed = 36f,
        )
        val result = simulate(request, target.copy(facing = 180f), targetTurnRate = 20f, phased = true)

        assertNull(result.state.failure, "stage=${result.state.waypoint.stage}, position=${result.body.position}, target=${result.target}, canAttack=${result.body.canAttackTarget}, inArea=${result.state.route.inAttackArea(result.body.position, result.target)}, elapsed=${result.state.elapsed}")
        assertTrue(result.state.completed)
        assertEquals(setOf(PhaseRouteStage.LATERAL, PhaseRouteStage.SIDE_ENTRY, PhaseRouteStage.ATTACK), result.seen)
        assertTrue(result.state.route.inAttackArea(result.body.position, result.target))
    }

    @Test
    fun `正前方起步时持续平移转向的目标也能被穿后捕获`() {
        val request = request.copy(
            position = PhasePosition(1000f, 0f), frontHalfArc = 30f, clearance = 30f, maxElapsed = 36f,
        )
        val result = simulate(
            request,
            target,
            targetTurnRate = 20f,
            targetVelocity = PhasePosition(35f, -20f),
            phased = true,
        )

        assertNull(result.state.failure, "stage=${result.state.waypoint.stage}, position=${result.body.position}, target=${result.target}, canAttack=${result.body.canAttackTarget}, inArea=${result.state.route.inAttackArea(result.body.position, result.target)}, elapsed=${result.state.elapsed}")
        assertTrue(result.state.completed)
        assertEquals(setOf(PhaseRouteStage.LATERAL, PhaseRouteStage.SIDE_ENTRY, PhaseRouteStage.ATTACK), result.seen)
        assertTrue(result.state.route.inAttackArea(result.body.position, result.target))
    }

    @Test
    fun `目标持续平移转向时首段沿路线侧移轴在预算内完成且不持续拉远`() {
        val initialTarget = target.copy(facing = 218f)
        val request = request.copy(
            position = PhasePosition(-1040f, 200f),
            target = initialTarget,
            speed = 78f,
            frontHalfArc = 30f,
            clearance = 30f,
            maxElapsed = 36f,
        )
        val result = simulate(
            request,
            initialTarget,
            targetTurnRate = 20f,
            targetVelocity = PhasePosition(8f, -29f),
            phased = true,
            bodyFacing = 350f,
            bodyVelocity = PhasePosition(104.6f, -17.5f),
        )

        val completedAt = assertNotNull(
            result.lateralCompletedAt,
            "stage=${result.state.waypoint.stage}, lateral=${result.state.lateralTravel}, " +
                "elapsed=${result.state.elapsed}, failure=${result.state.failure}, position=${result.body.position}, " +
                "waypoint=${result.state.waypoint.position}, noBenefit=${result.state.noBenefitTime}",
        )
        val completionDistance = assertNotNull(result.lateralCompletionDistance)
        assertTrue(completedAt <= 12f, "completedAt=$completedAt, distance=$completionDistance")
        assertTrue(
            result.maximumDistance <= result.initialDistance + 400f,
            "initial=${result.initialDistance}, maximum=${result.maximumDistance}, completedAt=$completedAt",
        )
        assertNull(
            result.state.failure,
            "stage=${result.state.waypoint.stage}, elapsed=${result.state.elapsed}, position=${result.body.position}, " +
                "target=${result.target}, sideEntryCapture=${result.state.sideEntryCapture}, " +
                "waypoint=${result.state.waypoint.position}, noBenefit=${result.state.noBenefitTime}",
        )
        assertTrue(
            result.state.completed,
            "stage=${result.state.waypoint.stage}, elapsed=${result.state.elapsed}, position=${result.body.position}, " +
                "target=${result.target}, sideEntryCapture=${result.state.sideEntryCapture}",
        )
        assertEquals(setOf(PhaseRouteStage.LATERAL, PhaseRouteStage.SIDE_ENTRY, PhaseRouteStage.ATTACK), result.seen)
    }

    @Test
    fun `低基础航速时仍能按完整到达时间拦截持续远离目标`() {
        val request = request.copy(
            position = PhasePosition(1000f, 0f),
            speed = 43f,
            frontHalfArc = 30f,
            clearance = 30f,
            maxElapsed = 90f,
        )
        val result = simulate(
            request,
            target,
            targetTurnRate = 20f,
            targetVelocity = PhasePosition(50f, 0f),
            phased = true,
        )

        assertNull(result.state.failure, "stage=${result.state.waypoint.stage}, position=${result.body.position}, target=${result.target}, canAttack=${result.body.canAttackTarget}, inArea=${result.state.route.inAttackArea(result.body.position, result.target)}, elapsed=${result.state.elapsed}")
        assertTrue(result.state.completed, "stage=${result.state.waypoint.stage}, position=${result.body.position}, velocity=${result.body.velocity}, facing=${result.body.facing}, target=${result.target}, canAttack=${result.body.canAttackTarget}, inArea=${result.state.route.inAttackArea(result.body.position, result.target)}, elapsed=${result.state.elapsed}, stageTravel=${result.state.stageTravel}, lateral=${result.state.lateralTravel}, noBenefit=${result.state.noBenefitTime}")
        assertEquals(setOf(PhaseRouteStage.LATERAL, PhaseRouteStage.SIDE_ENTRY, PhaseRouteStage.ATTACK), result.seen)
        assertTrue(result.state.route.inAttackArea(result.body.position, result.target))
    }

    @Test
    fun `完成侧后路线后的交接目的地保留路线侧和几何余量`() {
        val currentTarget = target.copy(position = PhasePosition(500f, 200f), facing = 20f)
        val forward = PhasePosition.facing(currentTarget.facing)
        val side = forward.left() * route.side.sign
        val completed = PhaseRouteState(route, 0f, waypointIndex = route.waypoints.lastIndex, completed = true)
        val position = currentTarget.position - forward * 200f + side * (currentTarget.radius * 0.25f + 1f)

        val destination = PhaseMovement.attackDestination(
            completed,
            motion.copy(position = position, canAttackTarget = true),
            currentTarget,
            space,
        )

        assertTrue(route.inAttackArea(destination, currentTarget))
        assertTrue((destination - currentTarget.position).dot(side) > currentTarget.radius * 0.25f)
        val movingTarget = currentTarget.copy(velocity = PhasePosition(35f, -20f))
        assertEquals(
            movingTarget.velocity,
            PhaseMovement.attackDestinationVelocity(
                completed,
                motion.copy(position = position, canAttackTarget = true),
                movingTarget,
                space,
            ),
        )
    }

    @Test
    fun `攻击保持跟随当前后弧而不把目的地旋转到目标正面`() {
        val turning = target.copy(angularVelocity = 20f)
        val forward = PhasePosition.facing(turning.facing)
        val side = forward.left() * route.side.sign
        val rear = forward * -1f
        val radius = route.waypoints.last().position.distance(turning.position)
        val boundary = rear * kotlin.math.cos(Math.toRadians(70.0)).toFloat() +
            side * kotlin.math.sin(Math.toRadians(70.0)).toFloat()
        val attacking = PhaseRouteState(
            route, 0f, waypointIndex = route.waypoints.lastIndex, sideEntryCapture = true,
        )
        val body = motion.copy(
            position = turning.position + boundary * radius, maxSpeed = 43f, canAttackTarget = true,
        )

        val destination = PhaseMovement.attackDestination(attacking, body, turning, space)

        assertTrue(route.inAttackArea(destination, turning))
        assertTrue(PhaseMovement.attackDestinationVelocity(attacking, body, turning, space).length() > 0f)
    }

    @Test
    fun `目标局部侧向与径向重合时规避仍保持正交侧移和射程恢复`() {
        val position = PhasePosition(0f, 1300f)
        val away = (position - target.position).unit()

        val destination = PhaseMovement.evadeDestination(position, target, 230f, 1000f, 1f)
        val displacement = destination - position

        assertEquals(-400f, displacement.dot(away), 0.001f)
        assertEquals(400f, abs(displacement.dot(away.left())), 0.001f)
        assertTrue(destination.y < position.y)
        val left = PhaseMovement.evadeDestination(PhasePosition(-2f, 1300f), target, 230f, 1000f, 1f)
        val right = PhaseMovement.evadeDestination(PhasePosition(2f, 1300f), target, 230f, 1000f, 1f)
        assertTrue(left.x < -2f)
        assertTrue(right.x < 2f)
    }

    @Test
    fun `暂停恢复重规划保留已进入的穿后捕获和实际段位移`() {
        val phased = space.copy(collides = false)
        val initialRequest = request.copy(position = PhasePosition(1000f, 800f), maxElapsed = 36f)
        val initial = assertNotNull(PhaseRoutePlanner.candidate(
            initialRequest, phased, PhaseRouteSide.LEFT, false, PhaseRouteStage.SIDE_ENTRY, 1,
        ))
        val paused = PhaseRouteState(
            initial, 0f, limits = PhaseRouteLimits(maxElapsed = 36f),
            elapsed = 19.8f, stageTravel = 942.1f, sideEntryCapture = true,
            lastPosition = initialRequest.position, paused = true,
        )
        val moved = target.copy(position = PhasePosition(200f, 0f), facing = 30f)

        val resumed = paused.resume(initialRequest.copy(target = moved), phased)
        val result = PhaseMovement.step(
            resumed,
            motion.copy(position = initialRequest.position),
            moved,
            phased,
            0.02f,
        )

        assertEquals(1, resumed.replans)
        assertEquals(2, resumed.route.version)
        assertEquals(942.1f, resumed.stageTravel)
        assertTrue(resumed.sideEntryCapture)
        assertTrue(result.state.sideEntryCapture)
        assertTrue(result.state.stageTravel >= 942.1f)
        assertFalse(result.state.completed)
    }

    private data class Simulation(
        val state: PhaseRouteState,
        val body: PhaseMotion,
        val target: PhaseRouteTarget,
        val seen: Set<PhaseRouteStage>,
        val initialDistance: Float,
        val lateralCompletedAt: Float?,
        val lateralCompletionDistance: Float?,
        val maximumDistance: Float,
    )

    private fun simulate(
        request: PhaseRouteRequest,
        initialTarget: PhaseRouteTarget,
        targetTurnRate: Float = 0f,
        targetVelocity: PhasePosition = PhasePosition.ZERO,
        phased: Boolean = false,
        bodyFacing: Float = 180f,
        bodyVelocity: PhasePosition = PhasePosition.ZERO,
    ): Simulation {
        val movementSpace = space.copy(
            collides = !phased,
            battleTimePerLocalSecond = if (phased) 1f / 3.6f else 1f,
        )
        val route = assertNotNull(PhaseRoutePlanner.plan(request, movementSpace))
        var state = PhaseRouteState(
            route, 0f,
            limits = PhaseRouteLimits(maxElapsed = request.maxElapsed, maxNoBenefitTime = 2f),
        )
        var body = motion.copy(
            position = request.position,
            velocity = bodyVelocity,
            facing = bodyFacing,
            maxSpeed = request.speed,
            strafeAcceleration = 75f,
        )
        var currentTarget = initialTarget.copy(velocity = targetVelocity)
        val initialDistance = body.position.distance(currentTarget.position)
        var maximumDistance = initialDistance
        var lateralCompletedAt: Float? = null
        var lateralCompletionDistance: Float? = null
        val seen = mutableSetOf<PhaseRouteStage>()
        val amount = 0.02f
        val steps = if (request.maxElapsed.isFinite()) (request.maxElapsed / amount).toInt() else 3000
        repeat(steps) {
            if (state.finished) return@repeat
            seen += state.waypoint.stage
            val targetVector = currentTarget.position - body.position
            val targetAngle = Math.toDegrees(atan2(targetVector.y.toDouble(), targetVector.x.toDouble())).toFloat()
            body = body.copy(canAttackTarget = targetVector.length() <= request.attackRange &&
                abs(PhasePosition.angleDelta(body.facing, targetAngle)) <= 10f)
            val result = PhaseMovement.step(state, body, currentTarget, movementSpace, amount)
            state = result.state
            seen += state.waypoint.stage
            if (lateralCompletedAt == null && state.lateralTravelAt(body.position) >= 400f) {
                lateralCompletedAt = state.elapsed
                lateralCompletionDistance = body.position.distance(currentTarget.position)
            }
            maximumDistance = max(maximumDistance, body.position.distance(currentTarget.position))
            val commands = result.allowed
            val turn = when {
                ShipCommand.TURN_LEFT in commands -> 1f
                ShipCommand.TURN_RIGHT in commands -> -1f
                else -> 0f
            }
            val angular = (body.angularVelocity + turn * body.turnAcceleration * amount).coerceIn(-body.maxTurnRate, body.maxTurnRate)
            val facing = body.facing + angular * amount
            val forward = PhasePosition.facing(facing)
            var velocity = body.velocity
            if (ShipCommand.DECELERATE in commands) {
                velocity = velocity.unit() * max(0f, velocity.length() - body.deceleration * amount)
            } else {
                if (ShipCommand.ACCELERATE in commands) velocity += forward * (body.acceleration * amount)
                if (ShipCommand.ACCELERATE_BACKWARDS in commands) velocity -= forward * (body.deceleration * amount)
                if (ShipCommand.STRAFE_LEFT in commands) velocity += forward.left() * (body.strafeAcceleration * amount)
                if (ShipCommand.STRAFE_RIGHT in commands) velocity -= forward.left() * (body.strafeAcceleration * amount)
            }
            if (velocity.length() > body.maxSpeed) velocity = velocity.unit() * body.maxSpeed
            body = body.copy(position = body.position + velocity * amount, velocity = velocity, facing = facing, angularVelocity = angular)
            val targetAmount = amount * movementSpace.battleTimePerLocalSecond
            currentTarget = currentTarget.copy(position = currentTarget.position + currentTarget.velocity * targetAmount)
            if (targetTurnRate > 0f) {
                val offset = body.position - currentTarget.position
                val desired = Math.toDegrees(atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()
                val delta = PhasePosition.angleDelta(currentTarget.facing, desired)
                    .coerceIn(-targetTurnRate * targetAmount, targetTurnRate * targetAmount)
                currentTarget = currentTarget.copy(
                    facing = currentTarget.facing + delta,
                    angularVelocity = delta / targetAmount,
                )
            }
        }
        return Simulation(
            state, body, currentTarget, seen, initialDistance,
            lateralCompletedAt, lateralCompletionDistance, maximumDistance,
        )
    }
}
