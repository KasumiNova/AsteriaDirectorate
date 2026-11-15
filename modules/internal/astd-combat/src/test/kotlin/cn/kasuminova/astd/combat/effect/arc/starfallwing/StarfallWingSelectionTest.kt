package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarfallWingSelectionTest {
    @Test
    fun `无目标时四种颜色等权`() {
        assertIntervals(false, false, false, listOf(1, 1, 1, 1))
    }

    @Test
    fun `无盾或关盾舰船提高蓝红权重`() {
        assertIntervals(true, false, false, listOf(2, 2, 1, 1))
    }

    @Test
    fun `开盾舰船提高金色权重`() {
        assertIntervals(true, false, true, listOf(1, 1, 3, 1))
    }

    @Test
    fun `战机仅提高紫色权重且不受护盾状态影响`() {
        for (shieldOn in listOf(false, true)) {
            assertIntervals(true, true, shieldOn, listOf(1, 1, 1, 3))
        }
    }

    @Test
    fun `护盾当前展开弧决定是否覆盖入射点`() {
        val target = Vector2f()
        val origin = Vector2f(-500f, 0f)
        assertTrue(StarfallWingSelection.shieldCoversApproach(origin, target, target, 100f, 180f, 90f))
        assertFalse(StarfallWingSelection.shieldCoversApproach(origin, target, target, 100f, 0f, 180f))
        assertFalse(StarfallWingSelection.shieldCoversApproach(origin, target, target, 100f, 150f, 40f))
        assertTrue(StarfallWingSelection.shieldCoversApproach(origin, target, target, 100f, 150f, 80f))
        assertFalse(StarfallWingSelection.shieldCoversApproach(origin, target, target, 100f, 180f, 0f))
        assertTrue(StarfallWingSelection.shieldCoversApproach(origin, target, target, 100f, 0f, 360f))
    }

    @Test
    fun `偏心护盾按线段交点判定且忽略后方与未相交护盾`() {
        val origin = Vector2f(-500f, 0f)
        val target = Vector2f()
        assertTrue(StarfallWingSelection.shieldCoversApproach(origin, target, Vector2f(-200f, 60f), 100f, 217f, 20f))
        assertFalse(StarfallWingSelection.shieldCoversApproach(origin, target, Vector2f(200f, 0f), 100f, 180f, 360f))
        assertFalse(StarfallWingSelection.shieldCoversApproach(origin, target, Vector2f(-200f, 200f), 100f, 180f, 360f))
        assertFalse(StarfallWingSelection.shieldCoversApproach(Vector2f(-50f, 0f), target, target, 100f, 180f, 360f))
        assertFalse(StarfallWingSelection.shieldCoversApproach(target, target, target, 100f, 180f, 360f))
    }

    @Test
    fun `主体和兄弟模块的护盾均能保护目标模块`() {
        val root = ship()
        val target = ship(root)
        val sibling = ship(root)
        val origin = Vector2f(-500f, 0f)
        val rootShield = shield(Vector2f(), 100f)
        val moduleShield = shield(Vector2f(-200f, 0f), 80f)
        `when`(root.shield).thenReturn(rootShield)
        assertTrue(StarfallWingSelection.shieldedApproach(origin, target, listOf(root, target, sibling)))
        `when`(root.shield).thenReturn(null)
        `when`(sibling.shield).thenReturn(moduleShield)
        assertTrue(StarfallWingSelection.shieldedApproach(origin, target, listOf(root, target, sibling)))
        assertTrue(StarfallWingSelection.shieldedApproach(origin, root, listOf(root, target, sibling)))
    }

    @Test
    fun `模块盾关闭未覆盖或属于其他舰船时按无盾处理`() {
        val root = ship()
        val target = ship(root)
        val sibling = ship(root)
        val other = ship(ship())
        val shield = shield(Vector2f(-200f, 0f), 80f)
        val origin = Vector2f(-500f, 0f)
        `when`(other.shield).thenReturn(shield)
        val ships = listOf(root, target, sibling, other)
        assertFalse(StarfallWingSelection.shieldedApproach(origin, target, ships))
        `when`(sibling.shield).thenReturn(shield)
        `when`(shield.isOn).thenReturn(false)
        assertFalse(StarfallWingSelection.shieldedApproach(origin, target, ships))
        `when`(shield.isOn).thenReturn(true)
        `when`(shield.facing).thenReturn(0f)
        assertFalse(StarfallWingSelection.shieldedApproach(origin, target, ships))
        `when`(shield.facing).thenReturn(180f)
        `when`(sibling.isHulk).thenReturn(true)
        assertFalse(StarfallWingSelection.shieldedApproach(origin, target, ships))
    }

    private fun ship(parent: ShipAPI? = null): ShipAPI = mock(ShipAPI::class.java).also {
        `when`(it.parentStation).thenReturn(parent)
        `when`(it.location).thenReturn(Vector2f())
        `when`(it.isAlive).thenReturn(true)
    }

    private fun shield(center: Vector2f, radius: Float): ShieldAPI = mock(ShieldAPI::class.java).also {
        `when`(it.location).thenReturn(center)
        `when`(it.radius).thenReturn(radius)
        `when`(it.facing).thenReturn(180f)
        `when`(it.activeArc).thenReturn(90f)
        `when`(it.isOn).thenReturn(true)
    }

    private fun assertIntervals(hasTarget: Boolean, fighter: Boolean, shieldOn: Boolean, weights: List<Int>) {
        val types = StarfallWingTuning.ShuttleType.entries
        val total = weights.sum().toFloat()
        var cumulative = 0
        for ((index, weight) in weights.withIndex()) {
            val lower = cumulative / total
            val upper = (cumulative + weight) / total
            assertEquals(types[index], StarfallWingSelection.choose(lower, hasTarget, fighter, shieldOn))
            assertEquals(types[index], StarfallWingSelection.choose((lower + upper) / 2f, hasTarget, fighter, shieldOn))
            assertEquals(types[index], StarfallWingSelection.choose(Math.nextDown(upper), hasTarget, fighter, shieldOn))
            cumulative += weight
        }
    }
}
