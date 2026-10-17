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

/**
 * TrackAI 追踪段障碍规避（2026-10-17 实机裁定）：
 * - [GeminiDemTrackAI.avoidanceVector] / [GeminiDemTrackAI.desiredHeadingAngle] 纯函数完整逻辑验证；
 * - [geminiDemScanObstacles] 网格扫描口径（目标豁免、战机剔除、残骸/陨石保留）；
 * - advance 集成：航线侧方残骸把期望朝向压离瞄准方向，转向指令跟随合成方向。
 */
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
        // 障碍在弹体正右方（+x）半径 30：range = 30 + 180 = 210
        val near = GeminiDemObstacle(Vector2f(60f, 0f), 30f)
        val far = GeminiDemObstacle(Vector2f(150f, 0f), 30f)

        val vNear = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(near), margin)
        // 远离方向 = -x；模长 = (1 - 60/210) × 3
        val expectedNear = (1f - 60f / 210f) * GeminiDemTrackAI.AVOID_WEIGHT
        assertEquals(-expectedNear, vNear.x, 1e-4f)
        assertEquals(0f, vNear.y, 1e-4f)

        val vFar = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(far), margin)
        assertTrue(sqrt(vFar.x * vFar.x + vFar.y * vFar.y) < expectedNear, "距离更远斥力更小")

        // 超出 range（300 > 210）不生效
        val out = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(GeminiDemObstacle(Vector2f(300f, 0f), 30f)), margin)
        assertEquals(0f, out.length(), 1e-4f)

        // 双障碍（左右各一）方向抵消为零；同侧叠加模长翻倍口径
        val cancel = GeminiDemTrackAI.avoidanceVector(
            Vector2f(0f, 0f),
            listOf(GeminiDemObstacle(Vector2f(60f, 0f), 30f), GeminiDemObstacle(Vector2f(-60f, 0f), 30f)),
            margin,
        )
        assertEquals(0f, cancel.length(), 1e-4f)
        val doubled = GeminiDemTrackAI.avoidanceVector(Vector2f(0f, 0f), listOf(near, near), margin)
        assertEquals(expectedNear * 2f, -doubled.x, 1e-4f)
    }

    @Test
    fun `合成方向纯函数：零斥力保持原角，正前方障碍把期望朝向压向侧方`() {
        // 零斥力：原样返回基准角
        assertEquals(42f, GeminiDemTrackAI.desiredHeadingAngle(42f, Vector2f(0f, 0f)), 1e-4f)

        // 基准朝 0°（+x），障碍在正前方 → 斥力 -x 与基准正对抵消后保持基准角（罕见极端定义行为）
        val headOn = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-1f, 0f))
        assertEquals(0f, headOn, 1e-4f)

        // 障碍在航线上方 → 斥力向下（-y 分量），期望朝向应从 0° 压向负角（右转侧）
        // （返回值归一化到 0~360，按 getShortestRotation 语义判读——与 advance 的消费方式一致）
        val deflect = GeminiDemTrackAI.desiredHeadingAngle(0f, Vector2f(-0.8f, -0.5f))
        val rotation = MathUtils.getShortestRotation(0f, deflect)
        assertTrue(rotation < -20f, "期望朝向应明显偏离基准方向，实际偏转 $rotation")
        // 斥力未强到完全反向时，偏转不应超过 90°（不掉头）
        assertTrue(rotation > -90f, "偏转不应掉头，实际偏转 $rotation")
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
        // 弹体 (0,0) 朝 0°，目标正前方 2000su；残骸在航线上方 (120, 40) 半径 30
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

        // 斥力 (-1.13, -0.38) 压过单位瞄准向量（摆动扰动 ±1.5° 不足以翻盘）→ 期望朝向约 -110° → TURN_RIGHT
        assertTrue(ShipCommand.TURN_RIGHT in commands, "残骸在航线上方应压出右转规避，实际指令 $commands")
        assertTrue(ShipCommand.ACCELERATE in commands, "规避不丢失加速指令")
    }
}
