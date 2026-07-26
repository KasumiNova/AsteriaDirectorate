package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileHistoryTest {
    @Test
    fun `history samples by minimum distance`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 5f, maxHistoryNodes = 10, distanceWindow = 100f)
        history.advance(Vector2f(0f, 0f), facing = 0f, elapsed = 0f)
        history.advance(Vector2f(2f, 0f), facing = 0f, elapsed = 0.1f)
        history.advance(Vector2f(5f, 0f), facing = 0f, elapsed = 0.2f)

        assertEquals(2, history.nodes().size)
    }

    @Test
    fun `history preserves non linear path nodes`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 10, distanceWindow = 100f)
        history.advance(Vector2f(0f, 0f), facing = 0f, elapsed = 0f)
        history.advance(Vector2f(10f, 0f), facing = 0f, elapsed = 0.1f)
        history.advance(Vector2f(10f, 10f), facing = 90f, elapsed = 0.2f)

        val nodes = history.nodes()
        assertEquals(3, nodes.size)
        assertEquals(10f, nodes[1].location.x)
        assertEquals(0f, nodes[1].location.y)
        assertEquals(10f, nodes[2].location.x)
        assertEquals(10f, nodes[2].location.y)
    }

    @Test
    fun `history trims old nodes by maximum node count`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 3, distanceWindow = 100f)
        for (i in 0..5) history.advance(Vector2f(i.toFloat(), 0f), facing = 0f, elapsed = i * 0.1f)

        assertEquals(3, history.nodes().size)
        assertEquals(3f, history.nodes().first().location.x)
    }

    @Test
    fun `history trims old nodes by distance window`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 10, distanceWindow = 12f)
        history.advance(Vector2f(0f, 0f), facing = 0f, elapsed = 0f)
        history.advance(Vector2f(10f, 0f), facing = 0f, elapsed = 0.1f)
        history.advance(Vector2f(20f, 0f), facing = 0f, elapsed = 0.2f)

        val nodes = history.nodes()
        assertTrue(nodes.first().location.x >= 8f)
        assertEquals(20f, nodes.last().location.x)
    }

    @Test
    fun `history can retain a larger runtime distance window without changing preset policy`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 10, distanceWindow = 12f)
        history.advance(Vector2f(0f, 0f), facing = 0f, elapsed = 0f, retainDistance = 25f)
        history.advance(Vector2f(10f, 0f), facing = 0f, elapsed = 0.1f, retainDistance = 25f)
        history.advance(Vector2f(20f, 0f), facing = 0f, elapsed = 0.2f, retainDistance = 25f)

        val nodes = history.nodes()
        assertEquals(0f, nodes.first().location.x)
        assertEquals(20f, nodes.last().location.x)
    }

    @Test
    fun `history retention by distance also allows enough samples for the window`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 3, distanceWindow = 100f)
        for (i in 0..5) {
            history.advance(Vector2f(i.toFloat() * 10f, 0f), facing = 0f, elapsed = i * 0.1f, retainNodeCount = 6)
        }

        assertEquals(6, history.nodes().size)
        assertEquals(0f, history.nodes().first().location.x)
    }

    @Test
    fun `history ignores repeated identical location`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 10, distanceWindow = 100f)
        history.advance(Vector2f(3f, 4f), facing = 0f, elapsed = 0f)
        history.advance(Vector2f(3f, 4f), facing = 0f, elapsed = 0.1f)

        assertEquals(1, history.nodes().size)
    }
}
