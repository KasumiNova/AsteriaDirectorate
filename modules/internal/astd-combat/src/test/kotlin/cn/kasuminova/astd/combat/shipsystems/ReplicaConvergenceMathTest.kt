package cn.kasuminova.astd.combat.shipsystems

import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplicaConvergenceMathTest {

    @Test
    fun `射程终点为出生点加归一方向乘射程`() {
        val terminal = ReplicaConvergenceMath.terminalPoint(Vector2f(100f, 50f), Vector2f(0.6f, 0.8f), 250f)
        assertEquals(250f, terminal.x, 1e-3f)
        assertEquals(250f, terminal.y, 1e-3f)

        val scaled = ReplicaConvergenceMath.terminalPoint(Vector2f(0f, 0f), Vector2f(30f, 40f), 100f)
        assertEquals(60f, scaled.x, 1e-3f)
        assertEquals(80f, scaled.y, 1e-3f)
    }

    @Test
    fun `不同出生点的复制弹都收敛到同一终点且互不平行`() {
        val terminal = ReplicaConvergenceMath.terminalPoint(Vector2f(0f, 0f), Vector2f(3f, 1f), 400f)
        val speed = 800f

        val spawns = listOf(
            Vector2f(150f, 0f), Vector2f(-60f, 140f), Vector2f(-140f, -40f),
            Vector2f(30f, -145f), Vector2f(-90f, -110f), Vector2f(-150f, 60f),
        )
        val velocities = spawns.map { spawn ->
            ReplicaConvergenceMath.convergingVelocity(terminal, spawn, speed).also {
                assertNotNull(it, "环带出生点不得方向退化")
            }.let { spawn to it!! }
        }

        for ((spawn, velocity) in velocities) {
            assertEquals(speed, velocity.length(), 1e-2f)
            val toTerminal = Vector2f.sub(terminal, spawn, null)
            toTerminal.normalise(toTerminal)
            val alignment = (velocity.x * toTerminal.x + velocity.y * toTerminal.y) / speed
            assertEquals(1f, alignment, 1e-4f, "复制弹速度必须指向射程终点")
        }

        for (i in velocities.indices) {
            for (j in i + 1 until velocities.size) {
                val a = velocities[i].second
                val b = velocities[j].second
                val dot = (a.x * b.x + a.y * b.y) / (speed * speed)
                assertTrue(abs(dot) < 0.9998f, "出生点 $i 与 $j 的复制弹射向不得平行（dot=$dot）")
            }
        }
    }

    @Test
    fun `出生点与终点重合时方向退化返回 null`() {
        val terminal = Vector2f(300f, -120f)
        assertNull(ReplicaConvergenceMath.convergingVelocity(terminal, Vector2f(terminal), 800f))
    }
}
