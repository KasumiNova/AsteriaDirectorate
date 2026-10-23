package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MachRingComponentTest {

    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    private val spec = MachRingSpec(interval = 0.1f, halfSize = 35f, color = ASTDColor(0.5f, 0.7f, 1f, 1f))

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        return engine
    }

    private fun ctx(engine: CombatEngineAPI, host: PointHost, phase: RenderPhase, amount: Float) = RenderContextImpl(
        engine = engine,
        host = host,
        frame = FrameStateImpl(
            elapsed = 0f,
            logicElapsed = 0f,
            amountThisFrame = amount,
            origin = Vector2f(host.origin),
            facing = host.facingDeg,
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
    fun `cadence emits only on full tick and failure warns once`() {
        val capture = WarnCapture(MachRingComponent::class.java).also { captures += it }
        val engine = stubEngine()
        val host = PointHost("h", Vector2f(0f, 0f), 90f)
        val comp = MachRingComponent("r", spec)

        comp.advance(ctx(engine, host, RenderPhase.Active, 0.04f), 0.04f)
        comp.advance(ctx(engine, host, RenderPhase.Active, 0.04f), 0.04f)
        assertEquals(0.08f, comp.accumulator, 1e-4f)
        assertEquals(0, capture.messages().size, "未满节拍不得发射: ${capture.messages()}")

        comp.advance(ctx(engine, host, RenderPhase.Active, 0.04f), 0.04f)
        assertEquals(0f, comp.accumulator, 1e-4f, "留环后累积器清零（reset-to-0）")
        assertEquals(1, capture.messages().size, "发射失败记一次 WARN: ${capture.messages()}")

        repeat(6) { comp.advance(ctx(engine, host, RenderPhase.Active, 0.04f), 0.04f) }
        assertEquals(1, capture.messages().size, "同类失败不得刷屏: ${capture.messages()}")
    }

    @Test
    fun `only active phase accumulates and emits`() {
        val capture = WarnCapture(MachRingComponent::class.java).also { captures += it }
        val engine = stubEngine()
        val host = PointHost("h", Vector2f(0f, 0f), 0f)
        val comp = MachRingComponent("r", spec)

        repeat(10) { comp.advance(ctx(engine, host, RenderPhase.FadingOut, 0.04f), 0.04f) }
        assertEquals(0f, comp.accumulator, 1e-4f, "淡出中不累积节拍")
        assertEquals(0, capture.messages().size, "淡出中不得发射: ${capture.messages()}")

        repeat(10) { comp.advance(ctx(engine, host, RenderPhase.Removed, 0.04f), 0.04f) }
        assertEquals(0f, comp.accumulator, 1e-4f)
        assertEquals(0, capture.messages().size, "移除后不得发射: ${capture.messages()}")
    }
}
