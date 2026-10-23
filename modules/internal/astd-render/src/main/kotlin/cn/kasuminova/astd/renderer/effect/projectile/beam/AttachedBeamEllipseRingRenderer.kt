package cn.kasuminova.astd.renderer.effect.projectile.beam

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object AttachedBeamEllipseRingRenderer {

    private const val ENGINE_KEY = "astd_attached_beam_ellipse_ring_renderer"

    data class Spec(
        val spacing: Float,
        val travelSpeed: Float,
        val aSideHalf: Float,
        val bAlongHalf: Float,
        val distanceOffset: Float = 0f,
        val color: Color,
        val lineWidthPx: Float,
        val segments: Int,
        val headScale: Float = 1.00f,
        val tailScale: Float = 1.20f,
    )

    private data class Attachment(
        var line: BeamLineUtil.BeamLine,
        val spec: Spec,
        var scroll: Float,
        var lastUpdated: Float,
        var fadeStartedAt: Float?,
        var fadeMul: Float,
    )

    fun upsert(engine: CombatEngineAPI, key: String, line: BeamLineUtil.BeamLine, spec: Spec) {
        val r = getOrCreate(engine)
        val now = safeTime(engine)
        val existing = r.map[key]
        if (existing == null) {
            r.map[key] = Attachment(
                line = line,
                spec = spec,
                scroll = 0f,
                lastUpdated = now,
                fadeStartedAt = null,
                fadeMul = 1f,
            )
        } else {
            existing.line = line
            existing.lastUpdated = now
            existing.fadeStartedAt = null
            existing.fadeMul = 1f
        }
    }

    fun remove(engine: CombatEngineAPI, key: String) {
        val r = engine.customData[ENGINE_KEY] as? Renderer ?: return
        r.map.remove(key)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val r = Renderer()
        r.bindEngine(engine)
        engine.addLayeredRenderingPlugin(r)
        engine.customData[ENGINE_KEY] = r
        return r
    }

    private fun safeTime(engine: CombatEngineAPI): Float {
        return try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }
    }

    private class Renderer : CombatLayeredRenderingPlugin {

        private var expired = false

        private var engine: CombatEngineAPI? = null
        val map: MutableMap<String, Attachment> = LinkedHashMap()

        fun bindEngine(engine: CombatEngineAPI) {
            this.engine = engine
        }

        override fun init(entity: CombatEntityAPI) {
            if (entity is CombatEngineAPI) this.engine = entity
        }

        override fun cleanup() {
            map.clear()
            expired = true
            engine = null
        }

        override fun advance(amount: Float) {
            if (expired) return
            val eng = engine ?: Global.getCombatEngine() ?: return
            if (eng.isPaused) return
            if (amount <= 0f) return

            if (map.isEmpty()) return

            val now = safeTime(eng)
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value

                val spd = e.spec.travelSpeed
                if (spd > 0.01f) e.scroll += spd * amount

                val idle = now - e.lastUpdated
                if (idle > 0.05f) {
                    if (e.fadeStartedAt == null) e.fadeStartedAt = now
                    val fadeT = ((now - (e.fadeStartedAt ?: now)) / 0.65f).coerceIn(0f, 1f)
                    val f = (1f - fadeT).coerceIn(0f, 1f)
                    e.fadeMul = (f * f * f).coerceIn(0f, 1f)
                    if (e.fadeMul <= 0.001f) {
                        it.remove()
                    }
                } else {
                    e.fadeMul = 1f
                    e.fadeStartedAt = null
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired) return
            val drawFront = when (layer) {
                CombatEngineLayers.ABOVE_PARTICLES -> true
                CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER -> false
                else -> return
            }
            if (map.isEmpty()) return

            val eng = engine ?: Global.getCombatEngine() ?: return
            safeTime(eng)

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
                // 关键：若外部开启 alpha test，会导致 alpha 低于阈值时“突然消失”，看不到淡出。
                GL11.glDisable(GL11.GL_ALPHA_TEST)
                GL11.glEnable(GL11.GL_LINE_SMOOTH)
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)

                for (att in map.values) {
                    val line = att.line
                    val len = line.length
                    if (len <= 16f) continue

                    val spacing = att.spec.spacing.coerceAtLeast(1f)
                    val offset = if (spacing > 0.01f) (att.scroll % spacing) else 0f
                    // dist 不能再 wrap 回束根部（否则间距错乱、环跳回起点）；len < spacing 时 startDist 用 offset % len 保证至少绘制一环
                    val startDist = if (len > 0.01f) (offset % len) else 0f
                    val count = (((len - startDist) / spacing).toInt().coerceAtLeast(0) + 2).coerceAtLeast(1)

                    val aBase = att.spec.aSideHalf.coerceAtLeast(1f)
                    val bBase = att.spec.bAlongHalf.coerceAtLeast(1f)
                    val c = att.spec.color

                    val rr = (c.red / 255f).coerceIn(0f, 1f)
                    val gg = (c.green / 255f).coerceIn(0f, 1f)
                    val bb = (c.blue / 255f).coerceIn(0f, 1f)
                    val aa = ((c.alpha / 255f) * att.fadeMul).coerceIn(0f, 1f)
                    if (aa <= 0.001f) continue

                    // 禁止直接用超大 glLineWidth（部分驱动出现矩形缺口/断裂），用多条细 loop 叠加加半轴偏移模拟厚度
                    val target = att.spec.lineWidthPx.coerceAtLeast(0.25f)
                    val baseWidth = kotlin.math.min(1.85f, target)
                    val layers = kotlin.math.ceil((target / baseWidth).toDouble()).toInt().coerceIn(1, 10)
                    val centerIdx = (layers - 1) * 0.5f
                    val delta = kotlin.math.max(0.10f, target * 0.20f)

                    for (i in 0 until count) {
                        val dist = startDist + i * spacing + att.spec.distanceOffset
                        if (dist < 0f) continue
                        if (dist > len) break

                        val t = if (len > 0.01f) (dist / len).coerceIn(0f, 1f) else 0f
                        val scale = (att.spec.headScale + (att.spec.tailScale - att.spec.headScale) * t).coerceAtLeast(0.01f)
                        val a = (aBase * scale).coerceAtLeast(1f)
                        val b = (bBase * scale).coerceAtLeast(1f)

                        val pos = Vector2f(
                            line.from.x + line.dirUnit.x * dist,
                            line.from.y + line.dirUnit.y * dist,
                        )

                        val phaseShift = if (att.spec.segments >= 24) (PI.toFloat() / att.spec.segments.toFloat()) else 0f

                        for (li in 0 until layers) {
                            val k = (li.toFloat() - centerIdx)
                            val aaMul = (1f - (kotlin.math.abs(k) / (centerIdx + 1f)) * 0.65f).coerceIn(0.20f, 1f)
                            val a2 = (a + k * delta).coerceAtLeast(1f)
                            val b2 = (b + k * delta).coerceAtLeast(1f)
                            drawEllipseHalf(
                                center = pos,
                                ux = line.dirUnit.x,
                                uy = line.dirUnit.y,
                                vx = line.perpUnit.x,
                                vy = line.perpUnit.y,
                                a = a2,
                                b = b2,
                                phase = 0f,
                                segments = att.spec.segments,
                                lineWidthPx = baseWidth,
                                r = rr,
                                g = gg,
                                bCol = bb,
                                aCol = aa * aaMul,
                                frontHalf = drawFront,
                            )
                            if (phaseShift != 0f) {
                                drawEllipseHalf(
                                    center = pos,
                                    ux = line.dirUnit.x,
                                    uy = line.dirUnit.y,
                                    vx = line.perpUnit.x,
                                    vy = line.perpUnit.y,
                                    a = a2,
                                    b = b2,
                                    phase = phaseShift,
                                    segments = att.spec.segments,
                                    lineWidthPx = baseWidth,
                                    r = rr,
                                    g = gg,
                                    bCol = bb,
                                    aCol = aa * aaMul,
                                    frontHalf = drawFront,
                                )
                            }
                        }
                    }
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        private fun drawEllipseHalf(
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
            frontHalf: Boolean,
        ) {
            GL11.glLineWidth(lineWidthPx.coerceAtLeast(0.25f))
            GL11.glColor4f(r, g, bCol, aCol.coerceIn(0f, 1f))

            val step = (2f * PI).toFloat() / segments.toFloat().coerceAtLeast(1f)
            var drawing = false
            for (i in 0..segments) {
                val ang = phase + step * (i % segments)
                val ca = cos(ang.toDouble()).toFloat()
                val sa = sin(ang.toDouble()).toFloat()

                val isFront = ca >= 0f
                val shouldDraw = if (frontHalf) isFront else !isFront

                if (shouldDraw) {
                    if (!drawing) {
                        GL11.glBegin(GL11.GL_LINE_STRIP)
                        drawing = true
                    }
                    val ox = vx * (a * ca) + ux * (b * sa)
                    val oy = vy * (a * ca) + uy * (b * sa)
                    GL11.glVertex2f(center.x + ox, center.y + oy)
                } else {
                    if (drawing) {
                        GL11.glEnd()
                        drawing = false
                    }
                }
            }
            if (drawing) GL11.glEnd()
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> {
            return EnumSet.of(
                CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                CombatEngineLayers.ABOVE_PARTICLES
            )
        }

        override fun getRenderRadius(): Float = 999999f

        override fun isExpired(): Boolean = expired
    }
}
