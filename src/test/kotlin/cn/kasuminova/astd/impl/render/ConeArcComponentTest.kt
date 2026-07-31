package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 锥面扩张弧组件（§10.9 v4.3 真柔边迁移）测试：
 * - 错峰激活：四道弧按 t=+0.03/0.07/0.11/0.15 逐道激活、跨阈值后幂等；
 * - 几何域：轴位/弧心/两侧半轴/外扩速度/存续/亮度档逐值平移 v2.2（40ce3a5 现状代码锚）；
 * - 节点几何：20 节点局部单位弧 θ∈[25°,155°]、绝对外扩纯函数（a/b 同额，非比例 ramp）；
 * - 宽度换算：12/11/10/10px ÷ viewMult≈1.5637 ≈ 7.67/7.03/6.40/6.40su 锚定值；
 * - 包络对齐：fill 羽化映射（fillStartFactor=0.35 / fillEndFactor=0.65）与 v2.2 逐顶点
 *   alpha 包络（峰值 0.65 smoothstep 双边）逐点恒等的网格断言；
 * - headless：贴图缺席记 WARN、弧激活置位、实体缺席无兜底。
 */
class ConeArcComponentTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    private val origin = Vector2f(1000f, 2000f)
    private val fringe = Color(60, 120, 255)

    private fun component(halfAngleDeg: Float = 40f, length: Float = 600f) =
        ConeArcComponent("arc_test", origin, 90f, halfAngleDeg, length, fringe)

    private fun ctx(engine: CombatEngineAPI, amount: Float) = RenderContextImpl(
        engine = engine,
        host = PointHost("t", origin, 90f),
        frame = FrameStateImpl(
            elapsed = 0f,
            logicElapsed = 0f,
            amountThisFrame = amount,
            origin = Vector2f(origin),
            facing = 90f,
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

    private fun mockEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        return engine
    }

    // ---- 错峰激活 ----

    @Test
    fun `arcs activate one by one across stagger delays and stay idempotent`() {
        val engine = mockEngine()
        val c = component()

        // 累计 0.02s：首道阈值 0.03 未跨，全未激活。
        c.advance(ctx(engine, 0.02f), 0.02f)
        assertTrue(c.arcs.none { it.activated }, "首道阈值未跨不得激活")

        // 逐道跨阈值：0.03 / 0.07 / 0.11 / 0.15。
        c.advance(ctx(engine, 0.02f), 0.02f) // 累计 0.04 → 第 1 道
        assertEquals(1, c.arcs.count { it.activated }, "跨 0.03 恰好激活第 1 道")
        assertTrue(c.arcs[0].activated)

        c.advance(ctx(engine, 0.04f), 0.04f) // 累计 0.08 → 第 2 道
        assertEquals(2, c.arcs.count { it.activated }, "跨 0.07 恰好激活第 2 道")
        c.advance(ctx(engine, 0.04f), 0.04f) // 累计 0.12 → 第 3 道
        assertEquals(3, c.arcs.count { it.activated }, "跨 0.11 恰好激活第 3 道")
        c.advance(ctx(engine, 0.04f), 0.04f) // 累计 0.16 → 第 4 道
        assertEquals(4, c.arcs.count { it.activated }, "跨 0.15 四道全部激活")

        // 幂等：继续推进不得重复激活（激活为布尔置位，无重复实体）。
        c.advance(ctx(engine, 0.10f), 0.10f)
        assertEquals(4, c.arcs.count { it.activated }, "跨阈值后不得重复触发")
    }

    // ---- 几何域（v2.2 逐值平移）----

    @Test
    fun `arc geometry ports v2 2 values verbatim`() {
        val c = component(halfAngleDeg = 40f, length = 600f)
        assertEquals(4, c.arcs.size)

        val tanHalf = tan(Math.toRadians(40.0)).toFloat()
        for (i in 0 until ConeArcComponent.ARC_COUNT) {
            val arc = c.arcs[i]
            val dist = 600f * ConeArcComponent.AXIS_FRACS[i]
            // 轴位 0.3/0.5/0.7/0.9L 沿 facing=90°（+y 方向）。
            val expectCenter = MathUtils.getPointOnCircumference(origin, dist, 90f)
            assertEquals(expectCenter.x, arc.center.x, 1e-3f, "弧 $i 弧心 x")
            assertEquals(expectCenter.y, arc.center.y, 1e-3f, "弧 $i 弧心 y")
            // aSide = dist × tan(halfAngle) × 0.85；bAlong = aSide × 0.5。
            val expectASide = dist * tanHalf * 0.85f
            assertEquals(expectASide, arc.aSide0, 1e-3f, "弧 $i 侧向半轴")
            assertEquals(expectASide * 0.5f, arc.bAlong0, 1e-3f, "弧 $i 沿向半轴")
            // 错峰/存续/外扩/亮度档逐值平移。
            assertEquals(ConeArcComponent.DELAYS[i], arc.delay, 1e-6f, "弧 $i 错峰")
            assertEquals(ConeArcComponent.DURATIONS[i], arc.duration, 1e-6f, "弧 $i 存续")
            assertEquals(ConeArcComponent.EXPAND_SPEEDS[i], arc.expandSpeed, 1e-6f, "弧 $i 外扩速度")
            assertEquals(ConeArcComponent.ALPHAS[i] / 255f, arc.alphaNorm, 1e-6f, "弧 $i 亮度档")
        }
    }

    // ---- 节点几何与绝对外扩 ----

    @Test
    fun `unit arc nodes span theta 25 to 155 degrees on unit circle`() {
        val nodes = ConeArcComponent.UNIT_NODES
        assertEquals(ConeArcComponent.NODE_COUNT, nodes.size, "节点数 20")
        // 首节点 θ=25°、末节点 θ=155°（x=sinθ 沿向、y=cosθ 侧向）。
        val firstRad = Math.toRadians(25.0)
        assertEquals(sin(firstRad).toFloat(), nodes.first().x, 1e-4f, "首节点 θ=25° x")
        assertEquals(cos(firstRad).toFloat(), nodes.first().y, 1e-4f, "首节点 θ=25° y")
        val lastRad = Math.toRadians(155.0)
        assertEquals(sin(lastRad).toFloat(), nodes.last().x, 1e-4f, "末节点 θ=155° x")
        assertEquals(cos(lastRad).toFloat(), nodes.last().y, 1e-4f, "末节点 θ=155° y")
        // 全部落在单位圆上（半轴缩放由逐帧重写承担）。
        for (n in nodes) {
            assertEquals(1f, n.x * n.x + n.y * n.y, 1e-4f, "节点必须在单位圆上: $n")
        }
        // 朝前最前点 θ=90° 附近 x≈1（弧中段），两端 x 对称。
        assertTrue(nodes.maxOf { it.x } > 0.99f, "弧中段必须含朝前最前点")
        assertEquals(nodes.first().x, nodes.last().x, 1e-4f, "两端 x 对称（sin25°=sin155°）")
    }

    @Test
    fun `expansion is absolute per v2 2 not proportional ramp`() {
        // v2.2：a/b 同额绝对外扩 expandSpeed×t（如第 1 道满存续外扩 260×0.22=57.2su）。
        assertEquals(57.2f, ConeArcComponent.expandedHalfAxis(0f, 260f, 0.22f), 1e-3f, "绝对外扩量")
        assertEquals(44.6f, ConeArcComponent.expandedHalfAxis(44.6f, 260f, 0f), 1e-3f, "t=0 保持初值")
        // 与比例 ramp 的根本区别：增长量与初值无关。
        assertEquals(
            ConeArcComponent.expandedHalfAxis(10f, 260f, 0.1f) - 10f,
            ConeArcComponent.expandedHalfAxis(90f, 260f, 0.1f) - 90f,
            1e-4f,
            "外扩增量必须与半轴初值无关（同额绝对外扩）",
        )
    }

    // ---- 宽度换算锚定 ----

    @Test
    fun `width conversion anchors view mult telemetry`() {
        val expect = floatArrayOf(7.674f, 7.035f, 6.395f, 6.395f)
        for (i in 0 until ConeArcComponent.ARC_COUNT) {
            assertEquals(
                ConeArcComponent.LINE_WIDTHS_PX[i] / ConeArcComponent.VIEW_MULT_PX_PER_SU,
                ConeArcComponent.WIDTHS_SU[i],
                1e-4f,
                "宽度 $i = px ÷ viewMult",
            )
            assertEquals(expect[i], ConeArcComponent.WIDTHS_SU[i], 0.01f, "宽度 $i 锚定值 ≈${expect[i]}su")
        }
        // 弧实例宽度与换算表一致（内外道差档温和：最细 ≥ 最粗的 83%，拒绝 v3 的 0.55 激进分档）。
        val c = component()
        for (i in 0 until ConeArcComponent.ARC_COUNT) {
            assertEquals(ConeArcComponent.WIDTHS_SU[i], c.arcs[i].widthSu, 1e-6f, "弧 $i 恒宽")
        }
        assertTrue(
            ConeArcComponent.WIDTHS_SU.min() >= ConeArcComponent.WIDTHS_SU.max() * 0.8f,
            "宽度分档必须温和（v2.2 的 12/11/10/10），不得引入 v3 激进分档",
        )
    }

    // ---- 包络对齐（fill 映射与 v2.2 逐顶点包络逐点恒等）----

    /** GLSL smoothstep（着色器 fill 模型的基元）。 */
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val u = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /**
     * v2.2 逐顶点 alpha 包络（40ce3a5 的 arcAlphaEnvelope 逐字锚本，随渲染器弧段分支退役后
     * 仅存于本测试作等价性基准）：[0,peak] smoothstep 渐升、[peak,1] smoothstep 渐落，两端归零。
     */
    private fun v22Envelope(t: Float, peak: Float): Float {
        val v = if (t <= peak) t / peak else 1f - (t - peak) / (1f - peak)
        val s = v.coerceIn(0f, 1f)
        return s * s * (3f - 2f * s)
    }

    /** BoxUtil TrailEntity 片元着色器 fill 模型（BUtil_TrailShader.frag 逐字锚本，fillStart/EndAlpha=0）。 */
    private fun shaderFillFactor(t: Float): Float {
        val fillMixX = 1f - smoothstep(ConeArcComponent.FILL_START_FACTOR, 1f, 1f - t)
        val fillMixY = 1f - smoothstep(ConeArcComponent.FILL_END_FACTOR, 1f, t)
        return (fillMixX * fillMixY).coerceIn(0f, 1f)
    }

    @Test
    fun `fill feather mapping is pointwise identical to v2 2 per vertex envelope`() {
        // fill 常量映射：fillStartFactor = 1−峰值、fillEndFactor = 峰值。
        assertEquals(1f - ConeArcComponent.ALPHA_PEAK_POS, ConeArcComponent.FILL_START_FACTOR, 1e-6f)
        assertEquals(ConeArcComponent.ALPHA_PEAK_POS, ConeArcComponent.FILL_END_FACTOR, 1e-6f)

        // 网格逐点等价：t ∈ [0,1] 步进 0.005（含峰值点 0.65）。
        var t = 0f
        while (t <= 1.0001f) {
            val clamped = t.coerceIn(0f, 1f)
            assertEquals(
                v22Envelope(clamped, ConeArcComponent.ALPHA_PEAK_POS),
                shaderFillFactor(clamped),
                1e-4f,
                "fill 映射与 v2.2 包络必须在 t=$clamped 逐点恒等",
            )
            t += 0.005f
        }
    }

    // ---- headless 失败语义 ----

    @Test
    fun `headless activation warns once and leaves entities absent`() {
        val capture = WarnCapture(ConeArcComponent::class.java).also { captures += it }
        val engine = mockEngine()
        val c = component()

        // 跨过全部阈值：四道激活置位，实体因贴图缺席全部缺席（无兜底）。
        repeat(10) { c.advance(ctx(engine, 0.02f), 0.02f) }
        assertEquals(4, c.arcs.count { it.activated }, "headless 下激活照常置位")
        assertTrue(c.arcs.all { it.entity == null }, "headless 下实体必须缺席")
        c.arcs.forEach { assertNull(it.entity) }
        assertTrue(
            capture.messages().any { it.contains("锥面弧") },
            "贴图缺席必须记 WARN: ${capture.messages()}",
        )
    }
}
