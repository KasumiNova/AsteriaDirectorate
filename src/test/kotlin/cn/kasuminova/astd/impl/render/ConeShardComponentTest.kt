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
 * 锥面三角碎片组件测试（§10.9 v4.2，SpriteEntity 实例化接原生泛光）：
 * - 批次错峰：批内有实例才激活、每批恰好激活一次；
 * - 实例参数域（逐值平移 v2.2）：自旋 ±180~540°/s、非均匀 scale 两边比 0.7~1.3（半尺寸校准）、
 *   定时器 full 0.38~0.55（总寿命 ≈ v2.2 的 0.45~0.65s）、alpha 140~200、1/4 概率 coreColor 提亮；
 * - emissive 降权：emissiveAlpha == color.alpha × 0.4 精确派生（域 56~80，降权非删除）；
 * - 单测环境贴图不可用：激活记 WARN 缺席视觉（无兜底）。
 */
class ConeShardComponentTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    private val core = Color(120, 180, 255)
    private val fringe = Color(60, 120, 255)

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
    fun `batches activate only after instances arrive and exactly once`() {
        val engine = stubEngine()
        val host = PointHost("h", Vector2f(0f, 0f), 90f)
        val comp = ConeShardComponent("t", 600f, core, fringe)

        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        // 空批不激活。
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)
        assertTrue(comp.batches.none { it.activated }, "空批不得激活")

        // 灌批 0 → 同帧 advance 激活；批 1/2 未灌不激活。
        repeat(6) { comp.addShard(0, Vector2f(0f, 0f), Vector2f(10f, 0f)) }
        comp.advance(frameCtx(engine, host, 0.04f, 0.02f), 0.02f)
        assertTrue(comp.batches[0].activated, "批 0 灌实例后必须激活")
        assertTrue(!comp.batches[1].activated && !comp.batches[2].activated, "未灌批次不得激活")

        // 灌批 2 → 激活；重复 advance 不重复激活（activated 幂等）。
        repeat(4) { comp.addShard(2, Vector2f(0f, 0f), Vector2f(10f, 0f)) }
        comp.advance(frameCtx(engine, host, 0.06f, 0.02f), 0.02f)
        comp.advance(frameCtx(engine, host, 0.08f, 0.02f), 0.02f)
        assertTrue(comp.batches[2].activated, "批 2 灌实例后必须激活")
        assertEquals(6, comp.batches[0].instances.size)
        assertEquals(4, comp.batches[2].instances.size)
    }

    @Test
    fun `instance params stay in v2 2 domains`() {
        val comp = ConeShardComponent("t", 200f, core, fringe)
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
            // 定时器 full 0.38~0.55（总寿命 0.02+full+0.10 ≈ v2.2 的 0.45~0.65s）。
            assertTrue(inst.timerFull in 0.38f - 1e-4f..0.55f + 1e-4f, "满亮相域: ${inst.timerFull}")
            // alpha 140~200。
            assertTrue(inst.color.alpha in 140..200, "alpha 域: ${inst.color.alpha}")
            // emissive 降权：emissiveAlpha == color.alpha × 0.4（域 56~80；降权非删除，必须 > 0）。
            assertEquals(
                (inst.color.alpha * ConeShardComponent.EMISSIVE_ALPHA_MUL).toInt(),
                inst.emissiveAlpha,
                "emissive alpha 必须按 color.alpha × 0.4 派生",
            )
            assertTrue(inst.emissiveAlpha in 56..80, "emissive alpha 域: ${inst.emissiveAlpha}")
            if (inst.color.red == core.red && inst.color.green == core.green && inst.color.blue == core.blue) coreBrightened++
        }
        assertTrue(coreBrightened < instances.size, "不得全部提亮（1/4 概率 coreColor）")
    }

    @Test
    fun `headless boxutil absence logs warn and skips batch visuals`() {
        val capture = WarnCapture(ConeShardComponent::class.java).also { captures += it }
        val engine = stubEngine()
        val host = PointHost("h", Vector2f(0f, 0f), 90f)
        val comp = ConeShardComponent("t", 600f, core, fringe)

        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        repeat(6) { comp.addShard(0, Vector2f(0f, 0f), Vector2f(10f, 0f)) }
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)

        assertTrue(
            capture.messages().any { it.contains("锥面碎片批") },
            "单测环境贴图不可用必须记 WARN: ${capture.messages()}",
        )
        assertTrue(comp.batches[0].entity == null, "实体缺席但批参数必须在册")
        assertTrue(comp.batches[0].activated, "激活流程照常推进")
    }
}
