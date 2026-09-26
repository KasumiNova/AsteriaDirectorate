package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemTrackAI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lwjgl.util.vector.Vector2f
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

/**
 * 规格 10 §4.1 用例 11：TrackAI 追踪段——偏角 >1° 发正确转向指令 + ACCELERATE；
 * 目标失效下一帧重搜索被调用；仍无目标仅 ACCELERATE（直飞定义行为）。
 * 注入引擎/重搜索桩驱动真实 advance 状态机。
 */
class GeminiDemTrackAITest {

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isPaused).thenReturn(false)
        return engine
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
        // 弹体朝 0°（+x），目标在正上方（90°）→ 应 TURN_LEFT
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

        // 目标在正右方（-90°/270°）→ 应 TURN_RIGHT
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
        // 纯函数锚定：weaveOffset = sin(elapsed×2π×freq + phase) × amp
        assertEquals(0f, GeminiDemTrackAI.weaveOffset(0f, 0f, 1f, 120f), 1e-4f)
        assertEquals(120f, GeminiDemTrackAI.weaveOffset(0.25f, 0f, 1f, 120f), 1e-4f)
        assertEquals(-120f, GeminiDemTrackAI.weaveOffset(0.75f, 0f, 1f, 120f), 1e-4f)

        // 定值种子驱动真实 advance：目标正前方 2000su（proximity 全额），4 秒内正弦偏移多次过零，
        // 瞄准点左右交替 → 转向指令必须同时出现过 LEFT 与 RIGHT（直线追尾只会单边或死区静默）
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
