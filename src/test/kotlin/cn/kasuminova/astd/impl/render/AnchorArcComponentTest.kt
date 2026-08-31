package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderHost
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AnchorArcComponent] 行为测试：桩引擎记录 spawnEmpArcVisual 调用——
 * 锚点在 attach 时捕获并固定、跟随端逐帧取当前 origin、按重铺间隔节流、淡出后停止重铺。
 */
class AnchorArcComponentTest {

    private data class ArcRecord(
        val from: Vector2f,
        val to: Vector2f,
        val thickness: Float,
        val fringe: Color,
        val core: Color,
    )

    private val spec = AnchorArcSpec(
        thickness = 10f,
        fringeColor = ASTDColor(0.47f, 0.75f, 1f, 0.75f),
        coreColor = ASTDColor(0.94f, 0.97f, 1f, 0.94f),
        respawnSeconds = 0.1f,
    )

    /** 记录型桩引擎：仅登记电弧调用（from/to/thickness/fringe/core）。 */
    private class StubEngine {
        val arcs = mutableListOf<ArcRecord>()
        val engine: CombatEngineAPI = mock(CombatEngineAPI::class.java).also { engine ->
            doAnswer { inv ->
                arcs += ArcRecord(
                    from = inv.getArgument(0),
                    to = inv.getArgument(2),
                    thickness = inv.getArgument(4),
                    fringe = inv.getArgument(5),
                    core = inv.getArgument(6),
                )
                null
            }.`when`(engine).spawnEmpArcVisual(
                nullable(Vector2f::class.java), nullable(CombatEntityAPI::class.java),
                nullable(Vector2f::class.java), nullable(CombatEntityAPI::class.java),
                anyFloat(), nullable(Color::class.java), nullable(Color::class.java),
            )
        }
    }

    private fun context(
        engine: CombatEngineAPI?,
        origin: Vector2f,
        phase: RenderPhase = RenderPhase.Active,
    ) = RenderContextImpl(
        engine = engine,
        host = object : RenderHost { override val hostId = "test" },
        frame = FrameStateImpl(
            elapsed = 1f,
            logicElapsed = 1f,
            amountThisFrame = 0.05f,
            origin = origin,
            facing = 0f,
            length = 0f,
            endpoint = null,
            worldUnitsPerPixel = 1f,
            active = phase == RenderPhase.Active,
            intensity = 1f,
            phase = phase,
            flightProgress = 0f,
            dissolve = 0f,
            fadeReason = null,
        ),
    )

    @Test
    fun `attach 捕获发射点为固定锚点，首次 advance 立即铺弧到当前跟随端`() {
        val stub = StubEngine()
        val component = AnchorArcComponent("arc", spec)

        assertTrue(component.onAttach(context(stub.engine, Vector2f(100f, 200f))))
        component.advance(context(stub.engine, Vector2f(400f, 260f)), 0.05f)

        assertEquals(1, stub.arcs.size)
        val arc = stub.arcs.single()
        // 锚点 = attach 时的 origin（发射点侧），不随后续帧移动
        assertEquals(100f, arc.from.x, 1e-4f)
        assertEquals(200f, arc.from.y, 1e-4f)
        // 跟随端 = 本帧 origin（弹体头部）
        assertEquals(400f, arc.to.x, 1e-4f)
        assertEquals(260f, arc.to.y, 1e-4f)
        assertEquals(10f, arc.thickness, 1e-4f)
        assertEquals(Color(119, 191, 255, 191), arc.fringe)
        assertEquals(Color(239, 247, 255, 239), arc.core)
    }

    @Test
    fun `重铺间隔节流：间隔内不重铺，跨间隔铺新弧且跟随端取最新位置`() {
        val stub = StubEngine()
        val component = AnchorArcComponent("arc", spec)
        component.onAttach(context(stub.engine, Vector2f(0f, 0f)))

        component.advance(context(stub.engine, Vector2f(50f, 0f)), 0.05f) // 首帧立即铺（计时器初始满）
        component.advance(context(stub.engine, Vector2f(100f, 0f)), 0.05f) // 间隔内不铺
        component.advance(context(stub.engine, Vector2f(150f, 0f)), 0.05f) // 跨间隔铺第二道
        assertEquals(2, stub.arcs.size)
        assertEquals(150f, stub.arcs[1].to.x, 1e-4f)
        assertEquals(0f, stub.arcs[1].from.x, 1e-4f, "锚点恒为发射点")
    }

    @Test
    fun `淡出后停止重铺，非 Active 相位同样不铺`() {
        val stub = StubEngine()
        val component = AnchorArcComponent("arc", spec)
        component.onAttach(context(stub.engine, Vector2f(0f, 0f)))
        component.advance(context(stub.engine, Vector2f(50f, 0f)), 0.05f)
        assertEquals(1, stub.arcs.size)

        component.beginFadeOut(FadeReason.Expire, 0.1f)
        component.advance(context(stub.engine, Vector2f(100f, 0f)), 0.5f)
        assertEquals(1, stub.arcs.size, "淡出后不得续铺")

        val stub2 = StubEngine()
        val component2 = AnchorArcComponent("arc", spec)
        component2.onAttach(context(stub2.engine, Vector2f(0f, 0f)))
        component2.advance(context(stub2.engine, Vector2f(50f, 0f), RenderPhase.FadingOut), 0.5f)
        assertEquals(0, stub2.arcs.size, "FadingOut 相位不得铺弧")
    }

    @Test
    fun `无引擎上下文 attach 失败`() {
        val component = AnchorArcComponent("arc", spec)
        assertFalse(component.onAttach(context(null, Vector2f(0f, 0f))))
    }
}
