package cn.kasuminova.astd.renderer.effect.projectile.beam

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVisual
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileTracerManager

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.DamagingProjectileAPI
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
import kotlin.math.sqrt

/**
 * OpenGL（固定管线）绘制的“椭圆环”渲染器。
 *
 * 目标：替换粒子拼接的线圈环，让环看起来更像“连续线”。
 *
 * 注意：
 * - 这里绘制的是纯线框（line loop），使用 additive blending。
 * - 只做视觉，不影响碰撞/伤害。
 */
internal object OglEllipseRingRenderer {

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
        /** 沿半径方向外扩速度（世界单位/秒） */
        val expandSpeed: Float = 0f,
        /** 切向速度（可选）：给一点“活性”，默认 0 */
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
        // 这里不要手动调用 plugin.init(...)：该方法的参数类型在 API 中是 CombatEntityAPI，
        // 由引擎在 addLayeredRenderingPlugin 时负责正确调用。
        // 我们仅提前保存 CombatEngine 引用，避免 init 回调顺序差异导致的空指针。
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

            // 重要：不要因为 rings 暂时为空就把 plugin 标记为 expired。
            // AOD-7 等武器是“按距离采样生成环”，中间可能会有空档；如果此时过期，
            // 后续 spawn() 会拿到 customData 里的旧实例并被忽略，从而表现为“只有前几发有环”。
            if (rings.isEmpty()) return

            for (i in rings.size - 1 downTo 0) {
                val r = rings[i]
                r.age += amount
                if (r.age >= r.duration) {
                    rings.removeAt(i)
                    continue
                }

                // 外扩：保持椭圆比例
                if (r.expandSpeed > 0.01f) {
                    val da = r.expandSpeed * amount
                    val db = r.expandSpeed * amount
                    r.a += da
                    r.b += db
                }

                // 轻微切向：用相位漂移模拟（避免每点单独速度）
                if (abs(r.tangentialSpeed) > 0.01f) {
                    r.phase += r.tangentialSpeed * amount * 0.04f
                }
            }

            // rings 为空时保持常驻，等待下一次 spawn。
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired) return
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (rings.isEmpty()) return

            // 保存/设置 OpenGL 状态
            GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT or
                    GL11.GL_COLOR_BUFFER_BIT or
                    GL11.GL_LINE_BIT or
                    GL11.GL_TEXTURE_BIT
            )

            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE) // additive
                GL11.glEnable(GL11.GL_LINE_SMOOTH)
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)

                for (r in rings) {
                    // alpha 随寿命衰减（smoothstep-ish）
                    val t = (r.age / r.duration).coerceIn(0f, 1f)
                    val fade = 1f - t
                    val a = r.a
                    val b = r.b

                    // 预计算朝向轴：ux 沿弹道，vx 垂直弹道（环长轴）
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

                    // 双层线圈：外层更宽更淡，内层更细更亮（模拟轻微虚化）
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

                // 椭圆：侧向用 cos，沿向用 sin；长轴在侧向
                val ox = vx * (a * ca) + ux * (b * sa)
                val oy = vy * (a * ca) + uy * (b * sa)

                // 注意：LayeredRenderingPlugin 的 render() 里 OpenGL 变换已处于“世界坐标系”。
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

/**
 * “霓虹椭圆环（OpenGL 线圈版）”：沿弹体路径按【距离】均匀采样生成椭圆环，并用 OGL 连续线绘制。
 */
internal class PathEllipseOglShockRingEmitterProjectileVisual(
    private val engine: CombatEngineAPI,
    spacingDistance: Float,
    private val offsetsBehind: FloatArray = floatArrayOf(0f),
    private val startDistance: Float = 0f,
    private val aSideHalf: Float,
    private val bAlongHalf: Float,
    private val duration: Float = 0.42f,
    private val color: Color = Color(255, 180, 100, 115),
    private val lineWidthPx: Float = 1.25f,
    private val segments: Int = 72,
    private val expandSpeed: Float = 45f,
    private val tangentialSpeed: Float = 0f,
) : ProjectileVisual {

    private val step: Float = spacingDistance.coerceAtLeast(1f)

    private var fadeStarted = false
    private var fadeOutSeconds = 0.12f
    private var fadeTimer = 0f

    private var traveled = 0f
    private var distAcc = 0f

    private fun computeFacing(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity
        return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
            org.lazywizard.lazylib.VectorUtils.getFacing(v)
        } else {
            projectile.facing
        }
    }

    private fun speed(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity ?: return 0f
        val s2 = v.x * v.x + v.y * v.y
        if (s2 <= 0.0001f) return 0f
        return sqrt(s2)
    }

    private fun spawnRing(center: Vector2f, facing: Float) {
        OglEllipseRingRenderer.spawn(
            engine,
            OglEllipseRingRenderer.RingSpec(
                center = center,
                facing = facing,
                aSideHalf = aSideHalf,
                bAlongHalf = bAlongHalf,
                duration = duration,
                color = color,
                lineWidthPx = lineWidthPx,
                segments = segments,
                expandSpeed = expandSpeed,
                tangentialSpeed = tangentialSpeed,
            )
        )
    }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        val s = speed(projectile)
        if (s <= 0.01f) return

        val prevTraveled = traveled
        traveled += s * amount
        if (traveled < startDistance) return

        // 首次跨越 startDistance：只累计实际越过 startDistance 的那部分距离，
        // 避免 distAcc 虚高导致第一帧生成过多的环。
        val effectiveDelta = if (prevTraveled < startDistance) {
            traveled - startDistance
        } else {
            s * amount
        }
        distAcc += effectiveDelta
        if (distAcc < step) return

        val facing = computeFacing(projectile)
        while (distAcc >= step) {
            distAcc -= step
            // 用剩余 distAcc（= backDist）反算各环的实际位置，保证多环同帧时仍均匀间隔。
            val backDist = distAcc
            for (d in offsetsBehind) {
                val totalBack = backDist + (if (d > 0.01f) d else 0f)
                val center = if (totalBack <= 0.1f) {
                    Vector2f(projectile.location)
                } else {
                    MathUtils.getPointOnCircumference(projectile.location, totalBack, facing + 180f)
                }
                spawnRing(center, facing)
            }
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // ring 实例由 renderer 托管并自行过期
    }
}
