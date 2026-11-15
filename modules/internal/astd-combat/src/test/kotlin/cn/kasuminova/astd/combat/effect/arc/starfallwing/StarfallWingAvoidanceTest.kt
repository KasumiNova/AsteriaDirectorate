package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarfallWingAvoidanceTest {

    private val speed = 1100f
    private val origin = Vector2f(0f, 0f)

    private fun obstacle(x: Float, y: Float, vx: Float = 0f, vy: Float = 0f, radius: Float = 60f) =
        StarfallWingObstacle(Vector2f(x, y), Vector2f(vx, vy), radius)

    private fun steer(vararg obstacles: StarfallWingBody, distance: Float = 900f, latched: Float = 0f) =
        StarfallWingAvoidance.steer(origin, 0f, speed, distance, obstacles.toList(), latched)

    private fun ship(parent: ShipAPI? = null): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.parentStation).thenReturn(parent)
        return ship
    }

    @Test
    fun `前方障碍把航向压离障碍一侧且左右对称`() {
        val left = steer(obstacle(400f, 60f))
        val right = steer(obstacle(400f, -60f))

        assertTrue(left.deflection < 0f, "障碍在航向左侧应向右偏，实际 ${left.deflection}")
        assertTrue(right.deflection > 0f, "障碍在航向右侧应向左偏，实际 ${right.deflection}")
        assertEquals(-1f, left.side)
        assertEquals(1f, right.side)
        assertEquals(abs(left.deflection), abs(right.deflection), 1e-4f)
    }

    @Test
    fun `正后方障碍不触发偏转而已经贴上的障碍仍被推开`() {
        assertEquals(0f, steer(obstacle(-400f, 60f)).deflection, 1e-4f)
        assertEquals(0f, steer(obstacle(-40f, 0f)).deflection, 1e-4f)

        val overlapped = steer(obstacle(0f, 0f))
        assertTrue(overlapped.deflection != 0f, "起点已处于净空圆内时仍需偏转")
        assertEquals(StarfallWingAvoidance.MAX_DEFLECTION, abs(overlapped.deflection), 1e-4f)
    }

    @Test
    fun `间距达到净空时擦边通过不偏转`() {
        val clear = 60f + StarfallWingAvoidance.CLEARANCE
        assertEquals(0f, steer(obstacle(400f, clear)).deflection, 1e-4f)
        assertEquals(0f, steer(obstacle(400f, -clear)).deflection, 1e-4f)
        assertTrue(steer(obstacle(400f, clear - 1f)).deflection < 0f, "刚好侵入净空即应偏转")
    }

    @Test
    fun `最近接近点超出剩余射程的障碍不计入`() {
        assertEquals(0f, steer(obstacle(2000f, 60f), distance = 900f).deflection, 1e-4f)
        assertTrue(steer(obstacle(880f, 60f), distance = 900f).deflection < 0f, "射程内的障碍仍需规避")
    }

    @Test
    fun `横向接近的障碍按相对速度提前规避`() {
        assertEquals(0f, steer(obstacle(400f, 130f)).deflection, 1e-4f)
        val crossing = steer(obstacle(400f, 130f, vy = -400f))
        assertTrue(crossing.deflection != 0f, "垂直逼近航线的障碍应触发规避")
        assertTrue(abs(crossing.deflection) > 10f, "相对速度预测应给出可见偏转，实际 ${crossing.deflection}")
    }

    @Test
    fun `正前方共线时沿用已选侧避免逐帧换向`() {
        val defaultSide = steer(obstacle(400f, 0f))
        assertEquals(1f, defaultSide.side)
        assertTrue(defaultSide.deflection > 0f)

        val latchLeft = steer(obstacle(400f, 10f), latched = 1f)
        val latchRight = steer(obstacle(400f, -10f), latched = -1f)
        assertEquals(1f, latchLeft.side, "共线带内应沿用已锁定侧")
        assertEquals(-1f, latchRight.side)
        assertEquals(abs(latchLeft.deflection), abs(latchRight.deflection), 1e-4f)
    }

    @Test
    fun `偏转幅度随距离收紧并受上限约束`() {
        val far = steer(obstacle(600f, 60f)).deflection
        val near = steer(obstacle(300f, 60f)).deflection
        assertTrue(abs(near) > abs(far), "越近的障碍需要更大偏转")

        val capped = steer(obstacle(10f, 60f, radius = 100f)).deflection
        assertEquals(-StarfallWingAvoidance.MAX_DEFLECTION, capped, 1e-4f)
    }

    @Test
    fun `多个障碍取最紧迫的一个`() {
        val mixed = steer(obstacle(600f, 60f), obstacle(250f, 60f))
        assertTrue(abs(mixed.deflection) > abs(steer(obstacle(600f, 60f)).deflection), "更近的障碍应主导偏转")
        assertEquals(abs(steer(obstacle(250f, 60f)).deflection), abs(mixed.deflection), 1e-4f)
    }

    @Test
    fun `扫描窗口受剩余射程与预测航程约束`() {
        assertEquals(900f, StarfallWingAvoidance.scanRange(speed, 900f), 1e-4f)
        val short = StarfallWingAvoidance.scanRange(600f, 5000f)
        assertTrue(short < 5000f, "高速下窗口不应无限制扩张，实际 $short")
        assertTrue(short > 600f * StarfallWingAvoidance.LOOKAHEAD_SECONDS, "窗口须覆盖预测航程")
    }

    @Test
    fun `锁定主体时主体与全部模块不参与避障`() {
        val root = ship()
        val module = ship(root)

        assertTrue(starfallWingIgnoresObstacle(root, source = null, target = root), "主体自身不参与避障")
        assertTrue(starfallWingIgnoresObstacle(module, source = null, target = root), "主体的模块不参与避障")
        assertFalse(starfallWingIgnoresObstacle(ship(), source = null, target = root), "独立舰仍需规避")
    }

    @Test
    fun `锁定模块时主体与兄弟模块不参与避障`() {
        val root = ship()
        val module = ship(root)
        val sibling = ship(root)

        assertTrue(starfallWingIgnoresObstacle(module, source = null, target = module), "被锁定的模块自身不参与避障")
        assertTrue(starfallWingIgnoresObstacle(sibling, source = null, target = module), "兄弟模块不参与避障")
        assertTrue(starfallWingIgnoresObstacle(root, source = null, target = module), "主体不参与避障")
    }

    @Test
    fun `另一主体的模块与独立舰仍参与避障`() {
        val target = ship(ship())
        val otherRoot = ship()
        val otherModule = ship(otherRoot)

        assertFalse(starfallWingIgnoresObstacle(otherModule, source = null, target = target), "另一主体的模块不同舰")
        assertFalse(starfallWingIgnoresObstacle(otherRoot, source = null, target = target), "另一主体不同舰")
    }

    @Test
    fun `目标为空时仅排除弹药来源舰自身`() {
        val source = ship()
        val sourceModule = ship(source)
        val other = ship()

        assertTrue(starfallWingIgnoresObstacle(source, source = source, target = null), "来源舰自身不参与避障")
        assertFalse(
            starfallWingIgnoresObstacle(sourceModule, source = source, target = null),
            "目标为空时不得按来源舰的模块归属忽略",
        )
        assertFalse(starfallWingIgnoresObstacle(other, source = source, target = null), "目标为空时其他舰仍需规避")
    }

    @Test
    fun `步进飞行让开正面圆障碍后仍命中后方目标`() {
        for (speed in listOf(770f, 1100f, 1400f, 1925f, 2585f)) {
            for (dt in listOf(1f / 30f, 1f / 60f, 1f / 144f)) {
                val result = fly(speed, dt)
                assertTrue(result.hit, "speed=$speed dt=$dt 未能命中目标，最近距离 ${result.missDistance}")
                assertTrue(!result.blocked, "speed=$speed dt=$dt 穿过了障碍")
            }
        }
    }

    private class FlightResult(val hit: Boolean, val missDistance: Float, val blocked: Boolean)

    private fun fly(speed: Float, dt: Float): FlightResult {
        val obstacleRadius = 60f
        val targetRadius = 30f
        val targetDistance = 1000f
        var x = 0f
        var y = 0f
        var facing = 0f
        var travelled = 0f
        var latched = 0f
        var closest = Float.MAX_VALUE
        var blocked = false
        val obstacle = obstacle(400f, 0f, radius = obstacleRadius)
        while (travelled < targetDistance + speed * 0.5f) {
            val dx = targetDistance - x
            val dy = -y
            val heading = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val steer = StarfallWingAvoidance.steer(
                Vector2f(x, y), heading, speed, (targetDistance - travelled).coerceAtLeast(0f),
                listOf(obstacle), latched,
            )
            if (steer.side != 0f) latched = steer.side
            if (travelled < targetDistance) {
                facing = StarfallWingTuning.turnToward(
                    facing, heading + steer.deflection, StarfallWingTuning.turnRate(speed) * dt,
                )
            }
            val radians = Math.toRadians(facing.toDouble())
            val step = speed * dt
            val sx = (cos(radians) * step).toFloat()
            val sy = (sin(radians) * step).toFloat()
            fun segmentDistance(tx: Float, ty: Float): Float {
                val t = (((tx - x) * sx + (ty - y) * sy) / (step * step)).coerceIn(0f, 1f)
                return hypot(tx - x - sx * t, ty - y - sy * t)
            }
            if (segmentDistance(400f, 0f) < obstacleRadius) blocked = true
            val miss = segmentDistance(targetDistance, 0f)
            x += sx
            y += sy
            travelled += step
            closest = minOf(closest, miss)
            if (miss <= targetRadius) return FlightResult(true, closest, blocked)
        }
        return FlightResult(false, closest, blocked)
    }
}
