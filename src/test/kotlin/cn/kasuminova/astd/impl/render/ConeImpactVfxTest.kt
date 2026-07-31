package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
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
 * 共用锥面 VFX 的入参防线、错峰调度与驱动生命周期测试（§10.9 v4.1：刺束吞并为子节点）：
 * - spawn 入参防线：非法 length/duration 记 WARN 且不注册插件；合法注册一次性驱动；
 * - 扩张弧调度：advance 跨过阈值后每道恰好生成一次、不重复；
 * - t=0 层：闪光 2 颗 vanilla 粒子、刺束子节点 v2.2 档针数域与零延迟段激活
 *   （兜底链退役后 headless 实体缺席不再落 vanilla 粒子）；
 * - 碎片三批（v4.2 SpriteEntity 实例化）：跨阈值累计 6+8+4=18 实例且幂等、灌批当帧激活；
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

    // ---- 扩张弧调度（v4.3 起由子节点 ConeArcComponent 承载）----

    @Test
    fun `advance fires each arc exactly once across thresholds`() {
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

        root.onAttach(frameCtx(engine, host, 0f, 0.02f))
        assertEquals(4, root.arcComponent.arcs.size, "弧子节点必须持有四道弧")

        // 逐帧推进跨过全部弧阈值（子节点内部错峰 t=+0.03/0.07/0.11/0.15），四道弧各恰好激活一次
        // （headless 实体缺席记 WARN，activated 置位）。
        root.advance(frameCtx(engine, host, 0.06f, 0.06f), 0.06f)
        assertEquals(1, root.arcComponent.arcs.count { it.activated }, "跨 0.03 恰好激活第 1 道")
        root.advance(frameCtx(engine, host, 0.12f, 0.06f), 0.06f)
        assertEquals(3, root.arcComponent.arcs.count { it.activated }, "跨 0.11 恰好激活前 3 道")
        root.advance(frameCtx(engine, host, 0.20f, 0.08f), 0.08f)
        assertEquals(4, root.arcComponent.arcs.count { it.activated }, "跨 0.15 四道全部激活")

        // 继续推进不得重复激活（布尔标记位幂等）；树寿命内持续验证。
        root.advance(frameCtx(engine, host, 0.30f, 0.10f), 0.10f)
        assertEquals(4, root.arcComponent.arcs.count { it.activated }, "跨阈值后弧不得重复触发")
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
    fun `attach builds spray needles by v2 2 tier and fires flash only in vanilla particles`() {
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

        root.onAttach(frameCtx(engine, host, 0f, 0.02f))

        // 刺束子节点（v4.1 吞并）：v2.2 档针数 9~13；零延迟段 attach 即激活
        // （headless 实体缺席记 WARN，activated 置位、参数照常；兜底链退役后不再落 vanilla 粒子）。
        assertTrue(
            root.sprayComponent.needles.size in
                ConeImpactVfxComponent.SPRAY_RAYS_MIN..ConeImpactVfxComponent.SPRAY_RAYS_MIN + ConeImpactVfxComponent.SPRAY_RAYS_EXTRA,
            "v2.2 针数域 9~13: ${root.sprayComponent.needles.size}",
        )
        assertTrue(root.sprayComponent.needles.count { it.activated } >= 1, "attach 必须激活零延迟针")

        // 闪光恰好 2 颗 vanilla 粒子（刺束实体缺席不再有加法：无兜底链、针尖补光随实体缺席）。
        verify(engine, times(2)).addSmoothParticle(any(), any(), anyFloat(), anyFloat(), anyFloat(), any())
    }

    @Test
    fun `shard batches accumulate eighteen instances across thresholds exactly once`() {
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
        assertEquals(6, root.shardComponent.batches[0].instances.size, "attach 顶点批必须恰好 6 颗")

        // 锥内批（t=+0.05）：8 颗，累计 14。
        root.advance(frameCtx(engine, host, 0.06f, 0.04f), 0.04f)
        assertEquals(8, root.shardComponent.batches[1].instances.size, "锥内批必须恰好 8 颗")

        // 锥缘批（t=+0.10）：4 颗，累计 18。
        root.advance(frameCtx(engine, host, 0.11f, 0.05f), 0.05f)
        assertEquals(4, root.shardComponent.batches[2].instances.size, "锥缘批必须恰好 4 颗")
        val total = root.shardComponent.batches.sumOf { it.instances.size }
        assertEquals(18, total, "三批必须累计 18 颗")

        // 幂等：跨过全部阈值后继续推进不得重复灌批。
        root.advance(frameCtx(engine, host, 0.20f, 0.09f), 0.09f)
        assertEquals(18, root.shardComponent.batches.sumOf { it.instances.size }, "批次跨阈值后不得重复触发")

        // 各批在灌批当帧（子节点同帧 advance）即激活（headless 实体缺席记 WARN，activated 置位）。
        assertTrue(root.shardComponent.batches.all { it.activated }, "三批必须全部激活")
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
