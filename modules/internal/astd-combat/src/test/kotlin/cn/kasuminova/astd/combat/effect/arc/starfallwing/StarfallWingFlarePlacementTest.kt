package cn.kasuminova.astd.combat.effect.arc.starfallwing

import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarfallWingFlarePlacementTest {
    @Test
    fun `出生点位于所有舰体顶点和护盾圆的外侧且留足间距`() {
        val impact = Vector2f(20f, 15f)
        val center = Vector2f()
        val points = listOf(Vector2f(40f, 0f), Vector2f(15f, 60f), Vector2f(-20f, 10f))
        val shield = Vector2f(35f, -10f)
        val direction = Vector2f(0.8f, 0.6f)
        for (clearance in listOf(2f, 9f, 21f)) {
            val placement = StarfallWingFlarePlacement(impact, center, 180f)
            points.forEach(placement::include)
            placement.includeCircle(shield, 50f)
            val origin = placement.location(clearance)
            for (point in points) assertTrue(dotDifference(origin, point, direction) >= clearance - 1e-4f)
            assertTrue(dotDifference(origin, shield, direction) >= 50f + clearance - 1e-4f)
            assertEquals(0f, (origin.x - impact.x) * direction.y - (origin.y - impact.y) * direction.x, 1e-4f)
        }
    }

    @Test
    fun `相邻模块更突出时继续向外移动且与插入顺序无关`() {
        val impact = Vector2f(30f, 0f)
        val points = listOf(Vector2f(15f, -10f), Vector2f(70f, 25f), Vector2f(-50f, 10f))
        val first = StarfallWingFlarePlacement(impact, Vector2f(), 180f)
        first.include(points[0])
        val before = first.location(8f)
        points.drop(1).forEach(first::include)
        val after = first.location(8f)
        val reversed = StarfallWingFlarePlacement(impact, Vector2f(), 180f)
        points.reversed().forEach(reversed::include)
        assertTrue(after.x > before.x)
        assertEquals(after, reversed.location(8f))
        assertTrue(after.x - points.maxOf { it.x } >= 8f)
    }

    @Test
    fun `任意命中方向的散射始终朝外`() {
        for (degrees in 0 until 360 step 15) {
            val radians = Math.toRadians(degrees.toDouble())
            val direction = Vector2f(cos(radians).toFloat(), sin(radians).toFloat())
            val placement = StarfallWingFlarePlacement(Vector2f(direction.x * 50f, direction.y * 50f), Vector2f(), 0f)
            for (sample in 0..100) {
                val facing = Math.toRadians(placement.facing(sample / 100f).toDouble())
                val outwardSpeed = cos(facing) * direction.x + sin(facing) * direction.y
                assertTrue(outwardSpeed >= 0.49999, "direction=$degrees sample=$sample outward=$outwardSpeed")
            }
        }
    }

    @Test
    fun `命中点与船心重合时沿来弹反方向离开`() {
        for (facing in listOf(0f, 45f, 130f, 270f)) {
            val center = Vector2f(50f, -20f)
            val placement = StarfallWingFlarePlacement(center, center, facing)
            placement.includeCircle(center, 30f)
            val origin = placement.location(7f)
            val incoming = Math.toRadians(facing.toDouble())
            val alongIncoming = (origin.x - center.x) * cos(incoming) + (origin.y - center.y) * sin(incoming)
            assertEquals(-37.0, alongIncoming, 1e-4)
        }
    }

    @Test
    fun `整体平移不改变散射方向与相对出生位置`() {
        val offset = Vector2f(1000f, -2000f)
        val first = StarfallWingFlarePlacement(Vector2f(30f, 40f), Vector2f(), 0f)
        val second = StarfallWingFlarePlacement(Vector2f(1030f, -1960f), offset, 0f)
        first.includeCircle(Vector2f(15f, 20f), 80f)
        second.includeCircle(Vector2f(1015f, -1980f), 80f)
        val origin = first.location(9f)
        val translated = second.location(9f)
        assertEquals(origin.x, translated.x - offset.x, 1e-4f)
        assertEquals(origin.y, translated.y - offset.y, 1e-4f)
        assertEquals(first.facing(0.25f), second.facing(0.25f))
    }

    private fun dotDifference(a: Vector2f, b: Vector2f, direction: Vector2f): Float =
        (a.x - b.x) * direction.x + (a.y - b.y) * direction.y
}
