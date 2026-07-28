package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
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
 * 共用锥面 VFX 的几何布局与驱动生命周期测试：
 * - 射线布局：奇数条、含中轴与 ±halfAngle 两缘、对称、条数 clamp 上下限；
 * - 射线基宽：随锥端弧长缩放并 clamp；
 * - spawn 入参防线：非法 length/duration 记 WARN 且不注册插件；合法注册一次性驱动；
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
        raySpacingDeg: Float = 12f,
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
        raySpacingDeg = raySpacingDeg,
    )

    // ---- 射线布局 ----

    @Test
    fun `ray layout is odd symmetric and contains axis and both edges`() {
        val offsets = ConeImpactVfx.layoutRayOffsets(40f, 12f)

        // ceil(80/12)=7 → 8 条（偶）→ 保奇数 9 条。
        assertEquals(9, offsets.size)
        assertEquals(0f, offsets[4], 1e-4f, "正中一条为中轴 0")
        assertEquals(-40f, offsets.first(), 1e-4f, "首条为 -halfAngle 缘")
        assertEquals(40f, offsets.last(), 1e-4f, "末条为 +halfAngle 缘")
        for (i in 0 until offsets.size / 2) {
            assertEquals(offsets[i], -offsets[offsets.size - 1 - i], 1e-4f, "布局必须左右对称")
        }
    }

    @Test
    fun `ray layout clamps to min and max ray count`() {
        val narrow = ConeImpactVfx.layoutRayOffsets(1f, 30f)
        assertEquals(ConeImpactVfx.MIN_RAYS, narrow.size, "极窄锥至少三条成形")

        val wide = ConeImpactVfx.layoutRayOffsets(90f, 0.5f)
        // 上限为 MAX_RAYS；若 MAX_RAYS 为偶则 +1 保奇数。
        val expectMax = if (ConeImpactVfx.MAX_RAYS % 2 == 0) ConeImpactVfx.MAX_RAYS + 1 else ConeImpactVfx.MAX_RAYS
        assertEquals(expectMax, wide.size, "密集间隔不得爆量")
    }

    @Test
    fun `ray base width scales with cone chord and clamps`() {
        // 半角 40°、长 600：弦宽 = 2*600*sin40° ≈ 771；7 条射线 ×0.8 ≈ 88 → 顶到上限。
        val wide = ConeImpactVfx.rayBaseWidth(600f, 40f, 7)
        assertEquals(ConeImpactVfx.RAY_WIDTH_MAX, wide, 1e-3f)

        // 极窄锥：弦宽 = 2*100*sin1° ≈ 3.49，3 条 ×0.8 ≈ 0.93 → 垫到下限，保证可见。
        val narrow = ConeImpactVfx.rayBaseWidth(100f, 1f, 3)
        assertEquals(ConeImpactVfx.RAY_WIDTH_MIN, narrow, 1e-3f)

        // 中段：弦宽 = 2*300*sin15° ≈ 155.3；5 条 ×0.8 ≈ 24.8。
        val mid = ConeImpactVfx.rayBaseWidth(300f, 15f, 5)
        assertEquals(2f * 300f * sin15 * 0.8f / 5f, mid, 0.1f)
    }

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

    private companion object {
        val sin15 = Math.sin(Math.toRadians(15.0)).toFloat()
    }
}
