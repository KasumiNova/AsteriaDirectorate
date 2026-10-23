package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemTrackAI
import com.fs.starfarer.api.combat.CollisionGridAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeminiDemTrackAITest {

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isPaused).thenReturn(false)
        val shipGrid = stubGrid(emptyList())
        val asteroidGrid = stubGrid(emptyList())
        `when`(engine.aiGridShips).thenReturn(shipGrid)
        `when`(engine.aiGridAsteroids).thenReturn(asteroidGrid)
        return engine
    }

    private fun stubGrid(entries: List<Any>): CollisionGridAPI {
        val grid = mock(CollisionGridAPI::class.java)
        `when`(grid.getCheckIterator(any(Vector2f::class.java), anyFloat(), anyFloat()))
            .thenAnswer { entries.iterator() }
        return grid
    }

    private fun stubMissile(x: Float, y: Float, facing: Float, owner: Int = 0): MissileAPI {
        val missile = mock(MissileAPI::class.java)
        `when`(missile.location).thenReturn(Vector2f(x, y))
        `when`(missile.facing).thenReturn(facing)
        `when`(missile.owner).thenReturn(owner)
        `when`(missile.isFading).thenReturn(false)
        `when`(missile.isExpired).thenReturn(false)
        return missile
    }

    private fun stubShip(id: String, x: Float, y: Float, alive: Boolean = true): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.location).thenReturn(Vector2f(x, y))
        `when`(ship.isAlive).thenReturn(alive)
        `when`(ship.isHulk).thenReturn(false)
        return ship
    }

    @Test
    fun `用例11a 有目标且偏角大于1度：正确转向指令加ACCELERATE，getTarget 供出目标`() {
        val engine = stubEngine()
        val missile = stubMissile(0f, 0f, 0f)
        val target = stubShip("T1", 0f, 100f)
        `when`(engine.isEntityInPlay(target)).thenReturn(true)

        var retargetCalls = 0
        val ai = GeminiDemTrackAI(
            missile,
            target,
            engineProvider = { engine },
            retarget = { _, _ -> retargetCalls++; null },
        )
        ai.advance(0.016f)

        val order = inOrder(missile)
        order.verify(missile).giveCommand(ShipCommand.TURN_LEFT)
        order.verify(missile).giveCommand(ShipCommand.ACCELERATE)
        assertSame(target, ai.target, "GuidedMissileAI.getTarget 供 DEMScript WAIT 段读取")
        assertTrue(retargetCalls == 0, "目标有效不重搜索")

        val missile2 = stubMissile(0f, 0f, 0f)
        val target2 = stubShip("T2", 0f, -100f)
        `when`(engine.isEntityInPlay(target2)).thenReturn(true)
        val ai2 = GeminiDemTrackAI(missile2, target2, engineProvider = { engine }, retarget = { _, _ -> null })
        ai2.advance(0.016f)
        val order2 = inOrder(missile2)
        order2.verify(missile2).giveCommand(ShipCommand.TURN_RIGHT)
        order2.verify(missile2).giveCommand(ShipCommand.ACCELERATE)
    }

    @Test
    fun `用例11b 目标失效下一帧重搜索被调用；仍无目标仅 ACCELERATE`() {
        val engine = stubEngine()
        val missile = stubMissile(0f, 0f, 0f)
        val deadTarget = stubShip("T1", 100f, 0f, alive = false)

        var retargetCalls = 0
        val ai = GeminiDemTrackAI(
            missile,
            deadTarget,
            engineProvider = { engine },
            retarget = { _, _ -> retargetCalls++; null },
        )
        ai.advance(0.016f)

        assertTrue(retargetCalls == 1, "目标失效触发重搜索")
        assertNull(ai.target)
        verify(missile, never()).giveCommand(ShipCommand.TURN_LEFT)
        verify(missile, never()).giveCommand(ShipCommand.TURN_RIGHT)
        verify(missile).giveCommand(ShipCommand.ACCELERATE)
    }

    @Test
    fun `用例11c 弹体淡出或过期后不再发指令`() {
        val engine = stubEngine()
        val missile = stubMissile(0f, 0f, 0f)
        `when`(missile.isFading).thenReturn(true)
        val target = stubShip("T1", 0f, 100f)
        val ai = GeminiDemTrackAI(missile, target, engineProvider = { engine }, retarget = { _, _ -> null })
        ai.advance(0.016f)
        verify(missile, never()).giveCommand(ShipCommand.ACCELERATE)
    }

    @Test
    fun `用例11d 摆动飞行：纯函数波形锚定 + 远距离追踪段转向指令左右交替`() {
        assertEquals(0f, GeminiDemTrackAI.weaveOffset(0f, 0f, 1f, 120f), 1e-4f)
        assertEquals(120f, GeminiDemTrackAI.weaveOffset(0.25f, 0f, 1f, 120f), 1e-4f)
        assertEquals(-120f, GeminiDemTrackAI.weaveOffset(0.75f, 0f, 1f, 120f), 1e-4f)

        val engine = stubEngine()
        val missile = stubMissile(0f, 0f, 0f)
        val target = stubShip("T1", 2000f, 0f)
        `when`(engine.isEntityInPlay(target)).thenReturn(true)
        val commands = mutableListOf<ShipCommand>()
        org.mockito.Mockito.doAnswer { inv -> commands += inv.getArgument<ShipCommand>(0); null }
            .`when`(missile).giveCommand(org.mockito.ArgumentMatchers.any())

        val ai = GeminiDemTrackAI(
            missile, target,
            engineProvider = { engine },
            retarget = { _, _ -> null },
            random = kotlin.random.Random(42),
        )
        repeat(40) { ai.advance(0.1f) }

        assertTrue(ShipCommand.TURN_LEFT in commands, "摆动段应出现过左转指令")
        assertTrue(ShipCommand.TURN_RIGHT in commands, "摆动段应出现过右转指令")
    }
}
