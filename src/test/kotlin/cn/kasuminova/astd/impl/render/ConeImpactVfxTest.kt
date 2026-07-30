package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.buff.WarnCapture
import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
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
 * 共用锥面 VFX 的楔块网格、入参防线与驱动生命周期测试：
 * - 楔块网格（ConeWedgeMesh 纯函数）：角向分段映射与上下闸、三角网格结构（首圈收 origin、
 *   扇缘在抖动上界内）、UV 径向线性 + v 带边界、全局包络与径向 alpha profile；
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

    // ---- 楔块网格纯函数（ConeWedgeMesh）----

    /** 顶点游标工具：单元格 (i,j) 跨 2 三角形 × 3 顶点 × 8 浮点 = 48 浮点。 */
    private fun cellBase(i: Int, j: Int, angular: Int) = (i * angular + j) * 2 * 3 * TEX_TRAIL_VERTEX_FLOATS

    @Test
    fun `wedge angular segments maps full angle per 5 degrees with clamps`() {
        assertEquals(16, wedgeAngularSegments(40f), "贯星 40° 半角（80° 全角）→ 16 段")
        assertEquals(32, wedgeAngularSegments(80f), "破晓 80° 半角（160° 全角）→ 32 段")
        assertEquals(WEDGE_ANGULAR_SEGS_MIN, wedgeAngularSegments(1f), "极窄锥垫到下限保圆弧读感")
        assertEquals(WEDGE_ANGULAR_SEGS_MAX, wedgeAngularSegments(90f), "90° 半角顶到防爆上限")
    }

    @Test
    fun `cone wedge fan emits full triangle grid with first ring at origin and edges within jitter bound`() {
        val radial = 6
        val angular = 16
        val halfAngle = 40f
        val length = 600f
        val jitterDeg = 1.5f
        val jitter = FloatArray(angular + 1) { jitterDeg }

        val fan = coneWedgeFan(
            origin = origin, facingDeg = 90f, halfAngleDeg = halfAngle, length = length,
            radialSegs = radial, angularSegs = angular, vLo = -0.6f, vHi = 0.56f,
            tileLength = length, scroll = 0f, frontR = 300f, envelopeAlpha = 1f,
            red = 1f, green = 1f, blue = 1f, angularJitter = jitter,
        )

        assertEquals(2 * radial * angular * 3 * TEX_TRAIL_VERTEX_FLOATS, fan.size, "三角形网格浮点总数")

        // 首圈（r=0）顶点世界坐标 = origin：cell(0,j) 三角形 1 顶点 0 = 格点 (0,j)；末列 (0,A) 在 cell(0,A-1) 三角形 2 顶点 2。
        for (j in 0 until angular) {
            val off = cellBase(0, j, angular)
            assertEquals(origin.x, fan[off], 1e-3f, "首圈格点 (0,$j) x 应收在 origin")
            assertEquals(origin.y, fan[off + 1], 1e-3f, "首圈格点 (0,$j) y 应收在 origin")
        }
        val lastColFirstRing = cellBase(0, angular - 1, angular) + 5 * TEX_TRAIL_VERTEX_FLOATS
        assertEquals(origin.x, fan[lastColFirstRing], 1e-3f, "首圈末列格点 (0,A) x 应收在 origin")
        assertEquals(origin.y, fan[lastColFirstRing + 1], 1e-3f, "首圈末列格点 (0,A) y 应收在 origin")

        // 末圈角向两端：cell(R-1,0) 三角形 1 顶点 1 = 格点 (R,0)；cell(R-1,A-1) 三角形 1 顶点 2 = 格点 (R,A)。
        val leftOff = cellBase(radial - 1, 0, angular) + TEX_TRAIL_VERTEX_FLOATS
        val rightOff = cellBase(radial - 1, angular - 1, angular) + 2 * TEX_TRAIL_VERTEX_FLOATS
        val leftAngle = Math.toDegrees(kotlin.math.atan2((fan[leftOff + 1] - origin.y).toDouble(), (fan[leftOff] - origin.x).toDouble())).toFloat()
        val rightAngle = Math.toDegrees(kotlin.math.atan2((fan[rightOff + 1] - origin.y).toDouble(), (fan[rightOff] - origin.x).toDouble())).toFloat()
        assertEquals(90f - halfAngle + jitterDeg, leftAngle, 1e-2f, "左扇缘 = facing − halfAngle + 抖动")
        assertEquals(90f + halfAngle + jitterDeg, rightAngle, 1e-2f, "右扇缘 = facing + halfAngle + 抖动")
        assertTrue(kotlin.math.abs(leftAngle - 90f) <= halfAngle + 2f, "扇缘不得超出 ±(halfAngle+2°) 抖动上界")
        assertTrue(kotlin.math.abs(rightAngle - 90f) <= halfAngle + 2f, "扇缘不得超出 ±(halfAngle+2°) 抖动上界")
    }

    @Test
    fun `cone wedge fan uv is radial-linear with scroll shift and column-bounded v`() {
        val radial = 6
        val angular = 16
        val length = 600f
        val scroll = 0.25f
        val vLo = -0.76f
        val vHi = 0.90f

        val fan = coneWedgeFan(
            origin = origin, facingDeg = 90f, halfAngleDeg = 40f, length = length,
            radialSegs = radial, angularSegs = angular, vLo = vLo, vHi = vHi,
            tileLength = length, scroll = scroll, frontR = 300f, envelopeAlpha = 1f,
            red = 1f, green = 1f, blue = 1f, angularJitter = FloatArray(angular + 1),
        )

        // 同列相邻两圈：cell(2,3) 三角形 1 顶点 0 = 格点 (2,3)，顶点 1 = 格点 (3,3)。
        val off = cellBase(2, 3, angular)
        val u2 = fan[off + 2]
        val u3 = fan[off + TEX_TRAIL_VERTEX_FLOATS + 2]
        val r2 = length * 2 / radial
        assertEquals(r2 / length - scroll, u2, 1e-4f, "u = r/tileLength − scroll")
        assertEquals((length / radial) / length, u3 - u2, 1e-4f, "u 随 r 线性推进，步长 = 径向段长/tileLength")

        // v 角向线性：格点 (i,j) 的 v = vLo + (vHi−vLo)×j/A；全体顶点 v ∈ [vLo, vHi]。
        assertEquals(vLo + (vHi - vLo) * 3f / angular, fan[off + 3], 1e-4f, "v 按列线性映射进贴图 alpha 带")
        for (cursor in fan.indices step TEX_TRAIL_VERTEX_FLOATS) {
            val v = fan[cursor + 3]
            assertTrue(v in vLo - 1e-4f..vHi + 1e-4f, "v=$v 越出 [$vLo, $vHi]")
        }
    }

    @Test
    fun `wedge envelope ramps smoothstep holds then fades linearly`() {
        val expand = 0.14f
        val duration = 0.45f
        val holdEnd = duration - 0.22f

        assertEquals(0f, wedgeEnvelope(0f, expand, holdEnd, duration), "t=0 包络为 0")
        assertEquals(1f, wedgeEnvelope(expand, expand, holdEnd, duration), 1e-4f, "expand 末包络到 1")
        assertEquals(1f, wedgeEnvelope((expand + holdEnd) / 2f, expand, holdEnd, duration), "hold 段恒 1")
        assertEquals(1f, wedgeEnvelope(holdEnd, expand, holdEnd, duration), "hold 末仍 1")
        assertEquals(0.5f, wedgeEnvelope((holdEnd + duration) / 2f, expand, holdEnd, duration), 1e-4f, "fadeOut 段线性")
        assertEquals(0f, wedgeEnvelope(duration, expand, holdEnd, duration), 1e-4f, "duration 末归零")
    }

    @Test
    fun `wedge radial alpha peaks at front and keeps faint silhouette beyond`() {
        val length = 600f
        val band = WEDGE_FRONT_BAND_RATIO * length
        val frontR = 300f

        assertEquals(1f, wedgeRadialAlpha(frontR, frontR, band), 1e-4f, "波前处 BASE+PEAK 满峰")
        assertEquals(
            WEDGE_ALPHA_BASE + WEDGE_ALPHA_PEAK * 0.5f,
            wedgeRadialAlpha(frontR - band / 2f, frontR, band), 1e-4f,
            "波前后半带宽处三角峰减半",
        )
        assertEquals(WEDGE_ALPHA_BASE, wedgeRadialAlpha(0f, frontR, band), 1e-4f, "远离波前的已抵达区回落到 BASE")
        assertEquals(
            WEDGE_ALPHA_BASE * WEDGE_FAINT_MUL,
            wedgeRadialAlpha(frontR + 1f, frontR, band), 1e-4f,
            "未抵达区保留 BASE×0.3 淡影",
        )
        assertEquals(
            WEDGE_ALPHA_BASE * WEDGE_FAINT_MUL,
            wedgeRadialAlpha(length, frontR, band), 1e-4f,
            "锥缘未抵达区同为淡影",
        )
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

    // ---- 扩张弧调度 ----

    @Test
    fun `advance fires each arc exactly once across thresholds`() {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        val plugin = ConeImpactVfx.spawn(engine, vfxSpec())
        assertNotNull(plugin)

        // 逐帧推进跨过全部弧阈值（t=+0.03/0.08/0.13），三道弧各恰好生成一次。
        repeat(10) { plugin.advance(0.02f, null) }
        assertEquals(3, OglEllipseRingRenderer.ringCountForTests(engine), "累计 0.2s 后三道弧应各生成一次")

        // 继续推进不得重复生成（布尔标记位幂等）；树寿命 0.6s 内持续验证。
        repeat(5) { plugin.advance(0.02f, null) }
        assertEquals(3, OglEllipseRingRenderer.ringCountForTests(engine), "跨阈值后弧不得重复生成")
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
