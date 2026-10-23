package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemObstacle
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemTrackAI
import cn.kasuminova.astd.combat.effect.arc.geminidem.geminiDemScanObstacles
import com.fs.starfarer.api.combat.CollisionGridAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiDemAvoidanceTest {

    private fun stubGrid(entries: List<Any>): CollisionGridAPI {
        val grid = mock(CollisionGridAPI::class.java)
        `when`(grid.getCheckIterator(any(Vector2f::class.java), anyFloat(), anyFloat()))
            .thenAnswer { entries.iterator() }
        return grid
    }

    private fun stubShip(id: String, x: Float, y: Float, fighter: Boolean = false, radius: Float = 30f): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.location).thenReturn(Vector2f(x, y))
        `when`(ship.isFighter).thenReturn(fighter)
        `when`(ship.collisionRadius).thenReturn(radius)
        return ship
    }

    @Test
    fun `避障纯函数：斥力指向远离障碍方向，距离越近模长越大，超程为零，多障碍累加`() {
        val margin = GeminiDemTrackAI.AVOID_MARGIN
        val obstacleRadius = 30f
        val range = obstacleRadius + margin
        val near = GeminiDemObstacle(Vector2f(60f, 0f), obstacleRadius)
        val far = GeminiDemObstacle(Vector2f(150f, 0f), obstacleRadius)

        val vNear = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(near), margin)
        val expectedNear = (1f - 60f / range) * GeminiDemTrackAI.AVOID_WEIGHT
        assertEquals(-expectedNear, vNear.x, 1e-4f)
        assertEquals(0f, vNear.y, 1e-4f)

        val vFar = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(far), margin)
        assertTrue(sqrt(vFar.x * vFar.x + vFar.y * vFar.y) < expectedNear, "距离更远斥力更小")

        val out = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(GeminiDemObstacle(Vector2f(range + 90f, 0f), obstacleRadius)), margin)
        assertEquals(0f, out.length(), 1e-4f)

        val cancel = GeminiDemTrackAI.avoidanceVector(
            Vector2f(0f, 0f),
            listOf(GeminiDemObstacle(Vector2f(60f, 0f), obstacleRadius), GeminiDemObstacle(Vector2f(-60f, 0f), obstacleRadius)),
            margin,
        )
        assertEquals(0f, cancel.length(), 1e-4f)
        val doubled = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(near, near), margin)
        assertEquals(expectedNear * 2f, -doubled.x, 1e-4f)
    }

    @Test
    fun `合成方向纯函数：零斥力保持原角，正前方障碍把期望朝向压向侧方`() {
        assertEquals(42f, GeminiDemTrackAI.desiredHeadingAngle(42f, Vector2f(0f, 0f)), 1e-4f)

        val headOn = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-1f, 0f))
        assertEquals(0f, headOn, 1e-4f)

        val deflect = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-0.8f, -0.5f))
        val rotation = MathUtils.getShortestRotation(0f, deflect)
        assertTrue(rotation < -20f, "期望朝向应明显偏离基准方向，实际偏转 $rotation")
        assertTrue(rotation > -GeminiDemTrackAI.MAX_AVOID_DEFLECT_DEG, "偏转不应触上限，实际偏转 $rotation")
    }

    @Test
    fun `合成方向偏转钳制：近共线强斥力只偏转到上限不掉头`() {
        val cap = GeminiDemTrackAI.MAX_AVOID_DEFLECT_DEG

        val collinear = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-GeminiDemTrackAI.AVOID_WEIGHT, 0f))
        assertEquals(cap, collinear, 1e-4f, "完全反向斥力应钳到 +上限（左转侧确定性）")

        val stacked = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-GeminiDemTrackAI.AVOID_WEIGHT * 2f, 0f))
        assertEquals(cap, stacked, 1e-4f)

        val rightSide = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-GeminiDemTrackAI.AVOID_WEIGHT, -0.5f))
        assertEquals(360f - cap, rightSide, 1e-4f, "右转侧钳到 -上限（归一化为 360-cap）")

        val rotated = GeminiDemTrackAI.desiredHeadingAngle(90f, Vector2f(0f, -GeminiDemTrackAI.AVOID_WEIGHT))
        assertEquals(90f + cap, rotated, 1e-4f)

        val within = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-0.8f, -0.5f))
        val withinRotation = MathUtils.getShortestRotation(0f, within)
        assertTrue(withinRotation > -cap, "未触上限的偏转不应被钳，实际 $withinRotation")
    }

    @Test
    fun `扫描窗口自适应：大障碍半径扩展窗口，无障碍保持基础窗口`() {
        assertEquals(GeminiDemTrackAI.SCAN_RANGE, GeminiDemTrackAI.resolveScanRange(0f), 1e-4f)
        assertEquals(GeminiDemTrackAI.SCAN_RANGE, GeminiDemTrackAI.resolveScanRange(200f), 1e-4f, "主力舰级半径不触发扩展")
        val expanded = GeminiDemTrackAI.resolveScanRange(400f)
        assertTrue(expanded > 400f + GeminiDemTrackAI.AVOID_MARGIN, "扩展窗口须覆盖大障碍避障生效距离，实际 $expanded")
    }

    @Test
    fun `障碍扫描口径：追踪目标豁免、战机剔除、残骸与陨石保留`() {
        val target = stubShip("T", 100f, 0f)
        val hulk = stubShip("H", -100f, 0f, radius = 60f)
        val fighter = stubShip("F", 50f, 50f, fighter = true)
        val asteroid = mock(com.fs.starfarer.api.combat.CombatEntityAPI::class.java)
        `when`(asteroid.location).thenReturn(Vector2f(0f, 100f))
        `when`(asteroid.collisionRadius).thenReturn(40f)

        val engine = mock(CombatEngineAPI::class.java)
        val shipGrid = stubGrid(listOf(target, hulk, fighter))
        val asteroidGrid = stubGrid(listOf(asteroid))
        `when`(engine.aiGridShips).thenReturn(shipGrid)
        `when`(engine.aiGridAsteroids).thenReturn(asteroidGrid)

        val obstacles = geminiDemScanObstacles(engine, Vector2f(0f, 0f), exempt = target)

        assertEquals(2, obstacles.size, "应只剩残骸与陨石（目标豁免、战机剔除）")
        assertTrue(obstacles.any { it.location.x == -100f && it.collisionRadius == 60f }, "残骸保留")
        assertTrue(obstacles.any { it.location.y == 100f && it.collisionRadius == 40f }, "陨石保留")
    }

    @Test
    fun `advance 集成：航线上侧方残骸把期望朝向压离瞄准方向，发右转指令`() {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isPaused).thenReturn(false)
        val hulk = stubShip("H", 120f, 40f)
        val shipGrid = stubGrid(listOf(hulk))
        val asteroidGrid = stubGrid(emptyList())
        `when`(engine.aiGridShips).thenReturn(shipGrid)
        `when`(engine.aiGridAsteroids).thenReturn(asteroidGrid)

        val missile = mock(MissileAPI::class.java)
        `when`(missile.location).thenReturn(Vector2f(0f, 0f))
        `when`(missile.facing).thenReturn(0f)
        `when`(missile.isFading).thenReturn(false)
        `when`(missile.isExpired).thenReturn(false)
        val commands = mutableListOf<ShipCommand>()
        doAnswer { inv -> commands += inv.getArgument<ShipCommand>(0); null }
            .`when`(missile).giveCommand(any())

        val target = stubShip("T", 2000f, 0f)
        `when`(target.isAlive).thenReturn(true)
        `when`(target.isHulk).thenReturn(false)
        `when`(engine.isEntityInPlay(target)).thenReturn(true)

        val ai = GeminiDemTrackAI(
            missile, target,
            engineProvider = { engine },
            retarget = { _, _ -> null },
            random = Random(42),
        )
        ai.advance(0.1f)

        assertTrue(ShipCommand.TURN_RIGHT in commands, "残骸在航线上方应压出右转规避，实际指令 $commands")
        assertTrue(ShipCommand.ACCELERATE in commands, "规避不丢失加速指令")
    }
}
