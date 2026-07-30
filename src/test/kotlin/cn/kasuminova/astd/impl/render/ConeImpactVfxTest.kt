package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.buff.WarnCapture
import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 共用锥面 VFX 的入参防线、错峰调度与驱动生命周期测试：
 * - spawn 入参防线：非法 length/duration 记 WARN 且不注册插件；合法注册一次性驱动；
 * - 扩张弧调度：advance 跨过阈值后每道恰好生成一次、不重复；
 * - OneShotVfxPlugin：暂停不推进、到期 detach + removePlugin 恰好一次。
 */
class ConeImpactVfxTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    private val origin = Vector2f(1000f, 2000f)
    private val core = Color(120, 180, 255)
    private val fringe = Color(60, 120, 255)

    private fun vfxSpec(
        halfAngleDeg: Float = 40f,
        length: Float = 600f,
        duration: Float = 0.45f,
        expandSeconds: Float = 0.14f,
        fadeOutSeconds: Float = 0.22f,
    ) = ConeImpactVfxSpec(
        origin = origin,
        facingDeg = 90f,
        halfAngleDeg = halfAngleDeg,
        length = length,
        coreColor = core,
        fringeColor = fringe,
        duration = duration,
        expandSeconds = expandSeconds,
        fadeOutSeconds = fadeOutSeconds,
    )

    // ---- spawn 入参防线与驱动注册 ----

    @Test
    fun `spawn registers a one shot driver for valid spec`() {
        val engine = mock(CombatEngineAPI::class.java)

        val plugin = ConeImpactVfx.spawn(engine, vfxSpec())

        assertNotNull(plugin)
        verify(engine, times(1)).addPlugin(plugin)
    }

    @Test
    fun `spawn rejects non positive length with warn and no plugin`() {
        val capture = WarnCapture(ConeImpactVfx::class.java).also { captures += it }
        val engine = mock(CombatEngineAPI::class.java)

        assertNull(ConeImpactVfx.spawn(engine, vfxSpec(length = 0f)))
        assertNull(ConeImpactVfx.spawn(engine, vfxSpec(length = -10f)))
        verify(engine, never()).addPlugin(any())
        assertEquals(2, capture.messages().count { it.contains("length 非正") }, "必须各记一次 WARN: ${capture.messages()}")
    }

    @Test
    fun `spawn rejects non positive duration with warn and no plugin`() {
        val capture = WarnCapture(ConeImpactVfx::class.java).also { captures += it }
        val engine = mock(CombatEngineAPI::class.java)

        assertNull(ConeImpactVfx.spawn(engine, vfxSpec(duration = 0f)))
        verify(engine, never()).addPlugin(any())
        assertTrue(capture.messages().any { it.contains("duration 非正") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `spawn clamps out of domain half angle and fade out with warns but still registers`() {
        val capture = WarnCapture(ConeImpactVfx::class.java).also { captures += it }
        val engine = mock(CombatEngineAPI::class.java)

        val plugin = ConeImpactVfx.spawn(engine, vfxSpec(halfAngleDeg = 170f, fadeOutSeconds = 9f))

        assertNotNull(plugin, "可修正的越界不阻断特效生成")
        verify(engine, times(1)).addPlugin(plugin)
        assertTrue(capture.messages().any { it.contains("halfAngleDeg 越界") }, "必须记 WARN: ${capture.messages()}")
        assertTrue(capture.messages().any { it.contains("fadeOutSeconds 越界") }, "必须记 WARN: ${capture.messages()}")
    }

    // ---- 扩张弧调度 ----

    @Test
    fun `advance fires each arc exactly once across thresholds`() {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        val plugin = ConeImpactVfx.spawn(engine, vfxSpec())
        assertNotNull(plugin)

        // 逐帧推进跨过全部弧阈值（t=+0.03/0.07/0.11/0.15），四道弧各恰好生成一次。
        repeat(10) { plugin.advance(0.02f, null) }
        assertEquals(4, OglEllipseRingRenderer.ringCountForTests(engine), "累计 0.2s 后四道弧应各生成一次")

        // 继续推进不得重复生成（布尔标记位幂等）；树寿命 0.6s 内持续验证。
        repeat(5) { plugin.advance(0.02f, null) }
        assertEquals(4, OglEllipseRingRenderer.ringCountForTests(engine), "跨阈值后弧不得重复生成")
    }

    // ---- t=0 层（闪光 / 刺束簇）与三角碎片 ----

    /** 构造一帧渲染上下文（驱动错峰阈值用：elapsed 为树已存活秒数）。 */
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
    fun `attach fires strike spray and flash with observable engine effects`() {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        val plugin = ConeImpactVfx.spawn(engine, vfxSpec())
        assertNotNull(plugin)

        // 首帧触发 attach：闪光 2 颗 + 刺束簇（9~13 条；单测环境 BoxUtil/贴图不可用，
        // ImpactStrikeFx 内部按既有兜底链落 vanilla 粒子，每条一次 addSmoothParticle）。
        plugin.advance(0.02f, null)

        // 刺束触发路径已执行：addSmoothParticle 至少 2（闪光）+ 9（刺束条数下限）次。
        verify(engine, atLeast(11)).addSmoothParticle(any(), any(), anyFloat(), anyFloat(), anyFloat(), any())
    }

    @Test
    fun `shard batches accumulate eighteen shards across thresholds exactly once`() {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        val host = PointHost("t", origin, 90f)
        val root = ConeImpactVfxComponent(
            id = "t",
            origin = origin,
            facingDeg = 90f,
            halfAngleDeg = 40f,
            length = 600f,
            coreColor = core,
            fringeColor = fringe,
            flashColor = core,
        )

        // 顶点批（attach）：6 颗。
        root.onAttach(frameCtx(engine, host, 0.02f, 0.02f))
        assertEquals(6, root.shardComponent.shards.size, "attach 顶点批必须恰好 6 颗")

        // 锥内批（t=+0.05）：8 颗，累计 14。
        root.advance(frameCtx(engine, host, 0.06f, 0.04f), 0.04f)
        assertEquals(14, root.shardComponent.shards.size, "锥内批触发后必须累计 14 颗")

        // 锥缘批（t=+0.10）：4 颗，累计 18。
        root.advance(frameCtx(engine, host, 0.11f, 0.05f), 0.05f)
        assertEquals(18, root.shardComponent.shards.size, "锥缘批触发后必须累计 18 颗")

        // 幂等：跨过全部阈值后继续推进不得重复加批（各批年龄 < 寿命下限 0.45s，无摘除干扰）。
        root.advance(frameCtx(engine, host, 0.20f, 0.09f), 0.09f)
        assertEquals(18, root.shardComponent.shards.size, "批次跨阈值后不得重复触发")
    }

    @Test
    fun `shard component integrates motion and evicts expired shards`() {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        val host = PointHost("t", origin, 90f)
        val comp = ConeShardComponent("shards", 600f, core, fringe)

        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        comp.addShard(Vector2f(100f, 200f), Vector2f(30f, -40f))
        val shard = comp.shards.single()
        val angle0 = shard.angleDeg

        comp.advance(frameCtx(engine, host, 0.1f, 0.1f), 0.1f)
        assertEquals(103f, shard.x, 1e-3f, "位置 x 必须按 vx 积分")
        assertEquals(196f, shard.y, 1e-3f, "位置 y 必须按 vy 积分")
        assertEquals(angle0 + shard.spinDegPerSec * 0.1f, shard.angleDeg, 1e-3f, "自旋角必须按角速度积分")

        // 寿命上限 0.65s：再推进 0.7s（累计 0.8s）后必须摘除。
        comp.advance(frameCtx(engine, host, 0.8f, 0.7f), 0.7f)
        assertTrue(comp.shards.isEmpty(), "过寿命碎片必须摘除")
    }

    // ---- OneShotVfxPlugin 生命周期 ----

    /** 记录调用的 RenderEntity 桩（Kotlin 接口不走 mockito：非空形参的 matcher 传 null 会触发空指针）。 */
    private class RecordingTree : RenderEntity {
        override val id: String = "recording_tree"
        override val layer = com.fs.starfarer.api.combat.CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER
        override val children: List<RenderEntity> = emptyList()

        var attachCalls = 0
            private set
        var advanceCalls = 0
            private set
        var detachCalls = 0
            private set
        var lastCtx: RenderContext? = null
            private set

        override fun addChild(child: RenderEntity) {}
        override fun removeChild(id: String) {}

        override fun onAttach(ctx: RenderContext): Boolean {
            attachCalls++
            lastCtx = ctx
            return true
        }

        override fun advance(ctx: RenderContext, amount: Float) {
            advanceCalls++
            lastCtx = ctx
        }

        override fun render(ctx: RenderContext) {}
        override fun beginFadeOut(reason: cn.kasuminova.astd.api.render.FadeReason, seconds: Float) {}

        override fun onDetach() {
            detachCalls++
        }
    }

    @Test
    fun `driver advances tree each frame and detaches exactly once on expiry`() {
        val engine = mock(CombatEngineAPI::class.java)
        val tree = RecordingTree()
        val host = PointHost("test", origin, 90f)
        val plugin = OneShotVfxPlugin(engine, host, tree, durationSeconds = 0.3f)

        plugin.advance(0.1f, null)
        plugin.advance(0.1f, null)
        plugin.advance(0.1f, null)

        assertTrue(plugin.isFinishedForTests(), "累计 0.3s 到期应完成收尾")
        assertEquals(1, tree.detachCalls, "到期恰好 detach 一次")
        assertEquals(2, tree.advanceCalls, "到期帧只收尾不再 advance")
        verify(engine, times(1)).removePlugin(plugin)

        // 完成后再 advance 为空操作（防引擎摘插件前多跑一帧）。
        plugin.advance(0.1f, null)
        assertEquals(1, tree.detachCalls)
        verify(engine, times(1)).removePlugin(plugin)
    }

    @Test
    fun `driver skips paused frames without advancing elapsed`() {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isPaused).thenReturn(true)
        val tree = RecordingTree()
        val plugin = OneShotVfxPlugin(engine, PointHost("test", origin, 90f), tree, durationSeconds = 0.3f)

        plugin.advance(0.5f, null)

        assertEquals(0f, plugin.elapsedForTests(), "暂停帧不得推进逻辑时钟")
        assertEquals(0, tree.advanceCalls)
        verify(engine, never()).removePlugin(plugin)
    }

    @Test
    fun `driver feeds frame with host anchor and elapsed`() {
        val engine = mock(CombatEngineAPI::class.java)
        val tree = RecordingTree()
        val plugin = OneShotVfxPlugin(engine, PointHost("test", origin, 45f), tree, durationSeconds = 1f)

        plugin.advance(0.1f, null)

        val ctx = tree.lastCtx
        assertNotNull(ctx)
        assertEquals(origin.x, ctx.frame.origin.x, 1e-4f)
        assertEquals(origin.y, ctx.frame.origin.y, 1e-4f)
        assertEquals(45f, ctx.frame.facing, 1e-4f)
        assertEquals(0.1f, ctx.frame.elapsed, 1e-4f)
        assertTrue(ctx.frame.active)
    }
}
