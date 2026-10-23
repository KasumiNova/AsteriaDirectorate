package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.awt.Color
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriShardComponentTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    private val core = Color(120, 180, 255)
    private val fringe = Color(60, 120, 255)
    private val spec = TriShardSpec()

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        return engine
    }

    private fun frameCtx(engine: CombatEngineAPI, host: PointHost, elapsed: Float, amount: Float) = RenderContextImpl(
        engine = engine,
        host = host,
        frame = FrameStateImpl(
            elapsed = elapsed,
            logicElapsed = elapsed,
            amountThisFrame = amount,
            origin = Vector2f(host.origin),
            facing = host.facingDeg,
            length = 0f,
            endpoint = null,
            worldUnitsPerPixel = 1f,
            active = true,
            intensity = 1f,
            phase = RenderPhase.Active,
            flightProgress = 0f,
            dissolve = 0f,
            fadeReason = null,
        ),
    )

    @Test
    fun `batches activate only after instances arrive and activation consumes them`() {
        val capture = WarnCapture(TriShardComponent::class.java).also { captures += it }
        val engine = stubEngine()
        val host = PointHost("h", Vector2f(0f, 0f), 90f)
        val comp = TriShardComponent("t", 600f, core, fringe)

        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)
        assertTrue(capture.messages().isEmpty(), "空批不得激活: ${capture.messages()}")

        repeat(6) { comp.addShard(0, Vector2f(0f, 0f), Vector2f(10f, 0f)) }
        comp.advance(frameCtx(engine, host, 0.04f, 0.02f), 0.02f)
        assertTrue(comp.batches[0].instances.isEmpty(), "批 0 激活后实例必须被消费清空")
        assertEquals(1, capture.messages().size, "仅批 0 走激活流程: ${capture.messages()}")

        repeat(3) { comp.addShard(0, Vector2f(0f, 0f), Vector2f(10f, 0f)) }
        comp.advance(frameCtx(engine, host, 0.06f, 0.02f), 0.02f)
        assertTrue(comp.batches[0].instances.isEmpty(), "批 0 二次灌批后必须再次消费清空")
        assertEquals(2, capture.messages().size, "批 0 必须可再次激活: ${capture.messages()}")
    }

    @Test
    fun `instance params stay in spec default domains`() {
        val comp = TriShardComponent("t", 200f, core, fringe)
        val pos = Vector2f(100f, 200f)
        val vel = Vector2f(30f, -40f)
        repeat(8) { comp.addShard(1, pos, vel) }

        val instances = comp.batches[1].instances
        assertEquals(8, instances.size)
        var coreBrightened = 0
        for (inst in instances) {
            assertEquals(100f, inst.pos.x, 1e-4f)
            assertEquals(30f, inst.vel.x, 1e-4f)
            assertEquals(-40f, inst.vel.y, 1e-4f)
            assertTrue(abs(inst.turnRateDegPerSec) in 180f..540f, "自旋角速度域: ${inst.turnRateDegPerSec}")
            assertTrue(inst.facingDeg in 0f..360f, "初始自旋角域: ${inst.facingDeg}")
            assertTrue(inst.scaleX in 2.1f - 1e-3f..3.9f + 1e-3f, "半尺寸域: ${inst.scaleX}")
            assertTrue(inst.scaleY / inst.scaleX in 0.7f - 1e-3f..1.3f + 1e-3f, "两边比域: ${inst.scaleY / inst.scaleX}")
            assertTrue(inst.timerFull in 0.18f - 1e-4f..0.32f + 1e-4f, "满亮相域: ${inst.timerFull}")
            assertEquals(0.32f, spec.timerFadeOut, 1e-4f, "淡出相锚定值")
            assertTrue(spec.timerFadeOut >= spec.timerFullHi - 1e-4f, "淡出相不得短于满亮相上限")
            assertTrue(inst.color.alpha in 140..200, "alpha 域: ${inst.color.alpha}")
            assertEquals(
                (inst.color.alpha * spec.emissiveAlphaMul).toInt(),
                inst.emissiveAlpha,
                "emissive alpha 必须按 color.alpha × 0.4 派生",
            )
            assertTrue(inst.emissiveAlpha in 56..80, "emissive alpha 域: ${inst.emissiveAlpha}")
            if (inst.color.red == core.red && inst.color.green == core.green && inst.color.blue == core.blue) coreBrightened++
        }
        assertTrue(coreBrightened < instances.size, "不得全部提亮（1/4 概率 coreColor）")
    }

    @Test
    fun `sizeScale multiplies shard size on top of spec domain`() {
        val comp = TriShardComponent("t", 200f, core, fringe)
        repeat(8) { comp.addShard(0, Vector2f(0f, 0f), Vector2f(0f, 0f), sizeScale = 2f) }
        for (inst in comp.batches[0].instances) {
            assertTrue(inst.scaleX in 4.2f - 1e-3f..7.8f + 1e-3f, "sizeScale 后的半尺寸域: ${inst.scaleX}")
        }
    }

    @Test
    fun `custom spec overrides batch count and param domains`() {
        val custom = TriShardSpec(
            batchCount = 1,
            sizeMul = 0.5f,
            sizeMin = 10f,
            sizeMax = 20f,
            sizeJitterLo = 1f,
            sizeJitterHi = 1f,
            skewLo = 1f,
            skewHi = 1f,
            alphaLo = 100,
            alphaHi = 100,
            timerFullLo = 0.5f,
            timerFullHi = 0.5f,
        )
        val comp = TriShardComponent("t", 30f, core, fringe, custom)
        assertEquals(1, comp.batches.size, "批次数必须随 spec")

        comp.addShard(0, Vector2f(0f, 0f), Vector2f(0f, 0f))
        val inst = comp.batches[0].instances.single()
        assertEquals(7.5f, inst.scaleX, 1e-4f)
        assertEquals(7.5f, inst.scaleY, 1e-4f)
        assertEquals(100, inst.color.alpha)
        assertEquals(0.5f, inst.timerFull, 1e-4f)
    }

    @Test
    fun `headless boxutil absence logs warn and skips batch visuals`() {
        val capture = WarnCapture(TriShardComponent::class.java).also { captures += it }
        val engine = stubEngine()
        val host = PointHost("h", Vector2f(0f, 0f), 90f)
        val comp = TriShardComponent("t", 600f, core, fringe)

        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        repeat(6) { comp.addShard(0, Vector2f(0f, 0f), Vector2f(10f, 0f)) }
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)

        assertTrue(
            capture.messages().any { it.contains("三角碎片批") },
            "单测环境贴图不可用必须记 WARN: ${capture.messages()}",
        )
        assertTrue(comp.batches[0].instances.isEmpty(), "激活失败后批实例同样被消费（激活即消费）")
    }
}
