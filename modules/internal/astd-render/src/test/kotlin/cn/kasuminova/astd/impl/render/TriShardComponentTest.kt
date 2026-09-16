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

/**
 * 三角碎片组件测试（原 ConeShardComponentTest；§10.9 v4.2 SpriteEntity 实例化接原生泛光，
 * 通用化改名 TriShardComponent 后：激活即消费、批可反复灌、参数域由 [TriShardSpec] 承载）：
 * - 批次语义：批内有实例才激活、激活即消费清空（可反复灌批）；
 * - 实例参数域（spec 默认值逐值平移 v2.2）：自旋 ±180~540°/s、非均匀 scale 两边比 0.7~1.3（半尺寸校准）、
 *   定时器 full 0.18~0.32 + fadeOut 0.32 定值（总寿命 0.52~0.67s）、alpha 140~200、1/4 概率 coreColor 提亮；
 * - sizeScale：在 spec 尺寸域基础上乘算（持续发射器按出力动态调尺寸）；
 * - 自定义 spec：批次数/尺寸域/alpha 域生效；
 * - emissive 降权：emissiveAlpha == color.alpha × 0.4 精确派生（域 56~80，降权非删除）；
 * - 单测环境贴图不可用：激活记 WARN 缺席视觉（无兜底）。
 */
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
        // 空批不激活（无 WARN 即未走激活流程）。
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)
        assertTrue(capture.messages().isEmpty(), "空批不得激活: ${capture.messages()}")

        // 灌批 0 → 同帧 advance 激活并消费；批 1/2 未灌不动。
        repeat(6) { comp.addShard(0, Vector2f(0f, 0f), Vector2f(10f, 0f)) }
        comp.advance(frameCtx(engine, host, 0.04f, 0.02f), 0.02f)
        assertTrue(comp.batches[0].instances.isEmpty(), "批 0 激活后实例必须被消费清空")
        assertEquals(1, capture.messages().size, "仅批 0 走激活流程: ${capture.messages()}")

        // 批可反复灌：再次灌批 0 → 再次激活消费。
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
            // 位置/速度由调用方散布模型给定，逐字持有。
            assertEquals(100f, inst.pos.x, 1e-4f)
            assertEquals(30f, inst.vel.x, 1e-4f)
            assertEquals(-40f, inst.vel.y, 1e-4f)
            // 自旋角速度 ±180~540°/s。
            assertTrue(abs(inst.turnRateDegPerSec) in 180f..540f, "自旋角速度域: ${inst.turnRateDegPerSec}")
            // 初始角 0~360°。
            assertTrue(inst.facingDeg in 0f..360f, "初始自旋角域: ${inst.facingDeg}")
            // 半尺寸 scale：边长 clamp(200×0.03,6,16)=6 ×0.7~1.3 → 半尺寸 2.1~3.9；两边比 0.7~1.3。
            assertTrue(inst.scaleX in 2.1f - 1e-3f..3.9f + 1e-3f, "半尺寸域: ${inst.scaleX}")
            assertTrue(inst.scaleY / inst.scaleX in 0.7f - 1e-3f..1.3f + 1e-3f, "两边比域: ${inst.scaleY / inst.scaleX}")
            // 定时器 full 0.18~0.32（fadeOut 0.32 定值，总寿命 0.02+full+0.32 = 0.52~0.67s）。
            assertTrue(inst.timerFull in 0.18f - 1e-4f..0.32f + 1e-4f, "满亮相域: ${inst.timerFull}")
            // 淡出相不得短于满亮相上限（渐隐观感保证）。
            assertEquals(0.32f, spec.timerFadeOut, 1e-4f, "淡出相锚定值")
            assertTrue(spec.timerFadeOut >= spec.timerFullHi - 1e-4f, "淡出相不得短于满亮相上限")
            // alpha 140~200。
            assertTrue(inst.color.alpha in 140..200, "alpha 域: ${inst.color.alpha}")
            // emissive 降权：emissiveAlpha == color.alpha × 0.4（域 56~80；降权非删除，必须 > 0）。
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
        // length 200 → 基准边长 clamp(200×0.03,6,16)=6，sizeScale=2 → 边长 12×0.7~1.3 → 半尺寸 4.2~7.8。
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
        // 边长 clamp(30×0.5,10,20)=15，jitter 固定 1 → 半尺寸 7.5，两边比 1。
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
