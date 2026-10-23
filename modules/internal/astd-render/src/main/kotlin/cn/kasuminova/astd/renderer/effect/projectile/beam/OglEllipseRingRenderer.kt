package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.ViewportAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object OglEllipseRingRenderer {

    private const val ENGINE_KEY = "astd_ogl_ellipse_ring_renderer"

    data class RingSpec(
        val center: Vector2f,
        val facing: Float,
        val aSideHalf: Float,
        val bAlongHalf: Float,
        val duration: Float,
        val color: Color,
        val lineWidthPx: Float = 1.25f,
        val segments: Int = 64,
        val expandSpeed: Float = 0f,
        val tangentialSpeed: Float = 0f,
    )

    private data class RingInstance(
        val center: Vector2f,
        val facing: Float,
        var a: Float,
        var b: Float,
        val duration: Float,
        val color: Color,
        val lineWidthPx: Float,
        val segments: Int,
        val expandSpeed: Float,
        val tangentialSpeed: Float,
        var age: Float = 0f,
        var phase: Float = 0f,
    )

    fun spawn(engine: CombatEngineAPI, spec: RingSpec) {
        val r = getOrCreate(engine)
        r.spawn(spec)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val r = Renderer()
        // 禁止手动调用 plugin.init(...)：API 参数类型为 CombatEntityAPI，由引擎在 addLayeredRenderingPlugin 时调用；
        // 仅提前保存 CombatEngine 引用，避免 init 回调顺序差异导致空指针
        r.bindEngine(engine)
        engine.addLayeredRenderingPlugin(r)
        engine.customData[ENGINE_KEY] = r
        return r
    }

    private class Renderer : CombatLayeredRenderingPlugin {

        private var engine: CombatEngineAPI? = null

        private val rings = ArrayList<RingInstance>(256)
        private var expired = false

        fun bindEngine(engine: CombatEngineAPI) {
            this.engine = engine
        }

        fun spawn(spec: RingSpec) {
            if (expired) return
            val a = spec.aSideHalf.coerceAtLeast(1f)
            val b = spec.bAlongHalf.coerceAtLeast(1f)
            val d = spec.duration.coerceAtLeast(0.01f)

            rings.add(
                RingInstance(
                    center = Vector2f(spec.center),
                    facing = spec.facing,
                    a = a,
                    b = b,
                    duration = d,
                    color = spec.color,
                    lineWidthPx = spec.lineWidthPx.coerceAtLeast(0.25f),
                    segments = spec.segments.coerceIn(12, 256),
                    expandSpeed = spec.expandSpeed,
                    tangentialSpeed = spec.tangentialSpeed,
                    age = 0f,
                    phase = MathUtils.getRandomNumberInRange(0f, (2f * PI).toFloat()),
                )
            )
        }

        override fun init(entity: CombatEntityAPI) {
            // 在 Starsector API 中 layered plugin 的 init 参数类型为 CombatEntityAPI。
            // 实战里传入的通常是 CombatEngine（它也实现了 CombatEntityAPI）。
            if (entity is CombatEngineAPI) {
                this.engine = entity
            }
        }

        override fun cleanup() {
            rings.clear()
            expired = true
            engine = null
        }

        override fun advance(amount: Float) {
            if (expired) return
            val eng = engine ?: Global.getCombatEngine()
            if (eng == null) return
            if (eng.isPaused) return
            if (amount <= 0f) return

            // rings 为空不能标记 expired：spawn() 会拿到 customData 里的旧实例并被忽略，表现为只有前几发有环
            if (rings.isEmpty()) return

            for (i in rings.size - 1 downTo 0) {
                val r = rings[i]
                r.age += amount
                if (r.age >= r.duration) {
                    rings.removeAt(i)
                    continue
                }

                if (r.expandSpeed > 0.01f) {
                    val da = r.expandSpeed * amount
                    val db = r.expandSpeed * amount
                    r.a += da
                    r.b += db
                }

                if (abs(r.tangentialSpeed) > 0.01f) {
                    r.phase += r.tangentialSpeed * amount * 0.04f
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired) return
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (rings.isEmpty()) return

            GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT or
                        GL11.GL_COLOR_BUFFER_BIT or
                        GL11.GL_LINE_BIT or
                        GL11.GL_TEXTURE_BIT
            )

            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                GL11.glEnable(GL11.GL_LINE_SMOOTH)
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)

                for (r in rings) {
                    val t = (r.age / r.duration).coerceIn(0f, 1f)
                    val fade = 1f - t
                    val a = r.a
                    val b = r.b

                    val rad = Math.toRadians(r.facing.toDouble())
                    val ux = cos(rad).toFloat()
                    val uy = sin(rad).toFloat()
                    val vx = -uy
                    val vy = ux

                    val baseA = (r.color.alpha / 255f).coerceIn(0f, 1f)
                    val alpha = (baseA * fade).coerceIn(0f, 1f)
                    if (alpha <= 0.001f) continue

                    val rr = (r.color.red / 255f).coerceIn(0f, 1f)
                    val gg = (r.color.green / 255f).coerceIn(0f, 1f)
                    val bb = (r.color.blue / 255f).coerceIn(0f, 1f)

                    drawEllipseLoop(
                        center = r.center,
                        ux = ux,
                        uy = uy,
                        vx = vx,
                        vy = vy,
                        a = a,
                        b = b,
                        phase = r.phase,
                        segments = r.segments,
                        lineWidthPx = r.lineWidthPx * 2.0f,
                        r = rr,
                        g = gg,
                        bCol = bb,
                        aCol = alpha * 0.35f,
                    )
                    drawEllipseLoop(
                        center = r.center,
                        ux = ux,
                        uy = uy,
                        vx = vx,
                        vy = vy,
                        a = a,
                        b = b,
                        phase = r.phase,
                        segments = r.segments,
                        lineWidthPx = r.lineWidthPx,
                        r = rr,
                        g = gg,
                        bCol = bb,
                        aCol = alpha,
                    )
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun drawEllipseLoop(
            center: Vector2f,
            ux: Float,
            uy: Float,
            vx: Float,
            vy: Float,
            a: Float,
            b: Float,
            phase: Float,
            segments: Int,
            lineWidthPx: Float,
            r: Float,
            g: Float,
            bCol: Float,
            aCol: Float,
        ) {
            GL11.glLineWidth(lineWidthPx.coerceAtLeast(0.25f))
            GL11.glColor4f(r, g, bCol, aCol.coerceIn(0f, 1f))

            GL11.glBegin(GL11.GL_LINE_LOOP)
            val step = (2f * PI).toFloat() / segments.toFloat()
            for (i in 0 until segments) {
                val ang = phase + step * i
                val ca = cos(ang.toDouble()).toFloat()
                val sa = sin(ang.toDouble()).toFloat()

                val ox = vx * (a * ca) + ux * (b * sa)
                val oy = vy * (a * ca) + uy * (b * sa)

                // LayeredRenderingPlugin 的 render() 里 OpenGL 变换已处于世界坐标系
                GL11.glVertex2f(center.x + ox, center.y + oy)
            }
            GL11.glEnd()
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> {
            return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        }

        override fun getRenderRadius(): Float {
            return 999999f
        }

        override fun isExpired(): Boolean = expired
    }
}

