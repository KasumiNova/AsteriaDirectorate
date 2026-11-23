package cn.kasuminova.astd.renderer.effect.system

import com.fs.starfarer.api.combat.BoundsAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarfallShardWakeTest {
    private class Edge(private val a: Vector2f, private val b: Vector2f) : BoundsAPI.SegmentAPI {
        override fun getP1() = a
        override fun getP2() = b
        override fun set(x1: Float, y1: Float, x2: Float, y2: Float) {
            a.set(x1, y1)
            b.set(x2, y2)
        }
    }

    private data class Sample(val x: Float, val y: Float, val spread: Float)

    private fun samples(points: List<Vector2f>, direction: Vector2f, center: Vector2f = Vector2f()): List<Sample> {
        val edges = points.mapIndexed { i, a -> Edge(a, points[(i + 1) % points.size]) }
        return buildList {
            sampleStarfallWake(edges, center, direction, 0.37f) { x, y, spread -> add(Sample(x, y, spread)) }
        }
    }

    @Test
    fun `各向移动只在后半平面中向外的轮廓上发射`() {
        val center = Vector2f(2900f, -1700f)
        val points = listOf(Vector2f(-800f, -250f), Vector2f(800f, -250f), Vector2f(800f, 250f), Vector2f(-800f, 250f))
            .map { Vector2f.add(it, center, null) }
        for (i in -1..1) for (j in -1..1) {
            if (i == 0 && j == 0) continue
            val forward = Vector2f(i.toFloat(), j.toFloat()).apply { normalise() }
            val hits = samples(points, forward, center)
            assertTrue(hits.isNotEmpty())
            for (hit in hits) {
                val x = hit.x - center.x
                val y = hit.y - center.y
                assertTrue(x * forward.x + y * forward.y < 0f)
                val leavesSide = abs(abs(x) - 800f) < 0.001f && x * forward.x < 0f
                val leavesEnd = abs(abs(y) - 250f) < 0.001f && y * forward.y < 0f
                assertTrue(leavesSide || leavesEnd)
            }
        }
    }

    @Test
    fun `尾翼内折边在两种绕序下均不发射`() {
        val hull = listOf(
            Vector2f(-360f, -260f), Vector2f(300f, -260f), Vector2f(300f, 260f), Vector2f(-100f, 260f),
            Vector2f(-100f, 140f), Vector2f(-300f, 140f), Vector2f(-300f, 230f), Vector2f(-360f, 230f),
        )
        for (points in listOf(hull, hull.reversed())) {
            val faces = samples(points, Vector2f(1f, 0f)).map { it.x }.toSet()
            assertEquals(setOf(-100f, -360f), faces)
        }
    }

    @Test
    fun `后侧边长决定间距而整圈周长决定数量`() {
        val hull = listOf(Vector2f(-900f, -160f), Vector2f(900f, -160f), Vector2f(900f, 160f), Vector2f(-900f, 160f))
        val narrow = samples(hull, Vector2f(1f, 0f))
        val broad = samples(hull, Vector2f(0f, 1f))
        assertTrue(narrow.size > 1)
        assertEquals(narrow.size, broad.size)
        for ((a, b) in narrow.zipWithNext()) assertEquals(320f / narrow.size, a.y - b.y, 0.001f)
        for ((a, b) in broad.zipWithNext()) assertEquals(1800f / broad.size, b.x - a.x, 0.001f)
        val split = hull + listOf(Vector2f(-900f, 60f), Vector2f(-900f, -110f))
        val splitHits = samples(split, Vector2f(1f, 0f))
        assertEquals(narrow.size, splitHits.size)
        for ((a, b) in narrow.zip(splitHits)) assertEquals(a.y, b.y, 0.001f)
    }

    @Test
    fun `近切线边段的最大侧偏仍朝舰体外`() {
        val hull = listOf(
            Vector2f(-1600f, -100f), Vector2f(1500f, -100f), Vector2f(1500f, 100f),
            Vector2f(-300f, 100f), Vector2f(-1600f, 80f),
        )
        val slope = samples(hull, Vector2f(1f, 0f)).filter { it.x < -300f && it.y > 80f }
        assertTrue(slope.isNotEmpty())
        for (hit in slope) {
            for (lateral in listOf(-hit.spread, hit.spread)) assertTrue(20f + 1300f * lateral > 0f)
        }
    }
}
