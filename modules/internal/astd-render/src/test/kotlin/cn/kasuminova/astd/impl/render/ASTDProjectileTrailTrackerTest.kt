package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileTrailTrackerTest {
    @Test
    fun `锚点沿朝向提前 forwardOffset`() {
        val east = trailAnchor(Vector2f(100f, 200f), 0.0, forwardOffset = 10f)
        assertEquals(110f, east.x, 1e-4f)
        assertEquals(200f, east.y, 1e-4f)
        val north = trailAnchor(Vector2f(100f, 200f), PI / 2, forwardOffset = 10f)
        assertEquals(100f, north.x, 1e-4f)
        assertEquals(210f, north.y, 1e-4f)
    }
}
