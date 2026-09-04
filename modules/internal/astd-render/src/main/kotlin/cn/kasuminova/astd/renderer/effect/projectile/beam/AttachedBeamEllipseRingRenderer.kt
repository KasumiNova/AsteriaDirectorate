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

/**
 * “跟随光束的永久环”渲染器：
 * - 不再按时间生成短寿命 ring 实体，而是在 render() 时基于当前光束线段实时计算环位置并绘制；
 * - 环按固定间距（spacing）分布，并通过 scrollOffset 实现沿束方向缓慢前进；
 * - 调用方需要每帧 upsert(...) 更新 line（从/to/朝向等）。
 */
internal object AttachedBeamEllipseRingRenderer {

    private const val ENGINE_KEY = "astd_attached_beam_ellipse_ring_renderer"

    data class Spec(
        val spacing: Float,
        val travelSpeed: Float,
        val aSideHalf: Float,
        val bAlongHalf: Float,
        /** 沿束方向的距离偏移（su）。用于让子光圈相对主光圈做固定 offset，而不是另起一套间距节奏。 */
        val distanceOffset: Float = 0f,
        val color: Color,
        /** 目标“粗细”（像素）。渲染时会自动拆成多条较细的 loop 叠加，避免大线宽导致的缺口/伪影。 */
        val lineWidthPx: Float,
        val segments: Int,
        /** 束根部 scale。 */
        val headScale: Float = 1.00f,
        /** 束尾 scale（例如 2.0 表示尾部 200%）。 */
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
            // 重新开始更新：取消渐隐
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

                // 沿束前进
                val spd = e.spec.travelSpeed
                if (spd > 0.01f) e.scroll += spd * amount

                // 若调用方停止更新（beam 结束/武器失活），不要立刻移除；先渐隐，避免“到点直接消失”。
                val idle = now - e.lastUpdated
                if (idle > 0.05f) {
                    if (e.fadeStartedAt == null) e.fadeStartedAt = now
                    val fadeT = ((now - (e.fadeStartedAt ?: now)) / 0.65f).coerceIn(0f, 1f)
                    // 用三次方让初期淡化更快（约 +50% 初始斜率），避免“看不出变化直到消失”。
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
            // 层次效果：
            // - 后半圈：在较低层渲染（让主束覆盖它）
            // - 前半圈：在更高层渲染（压在主束之上）
            val drawFront = when (layer) {
                CombatEngineLayers.ABOVE_PARTICLES -> true
                CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER -> false
                else -> return
            }
            if (map.isEmpty()) return

            val eng = engine ?: Global.getCombatEngine() ?: return
            val nowGlobal = safeTime(eng)

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
                    // 关键修复：不要把 dist % len 再 wrap 回束根部，否则会出现“间距乱/多一个环跳回起点”的错觉。
                    // 对于 len < spacing 的短束场景：startDist 用 offset % len，保证至少能绘制一个环。
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

                    // 重要：不要直接用超大 glLineWidth（某些驱动会出现矩形缺口/断裂）。
                    // 用多条较细的 loop 叠加 + 轻微半轴偏移来模拟厚度。
                    val target = att.spec.lineWidthPx.coerceAtLeast(0.25f)
                    val baseWidth = kotlin.math.min(1.85f, target)
                    val layers = kotlin.math.ceil((target / baseWidth).toDouble()).toInt().coerceIn(1, 10)
                    val centerIdx = (layers - 1) * 0.5f
                    val delta = kotlin.math.max(0.10f, target * 0.20f) // 厚度分层的半轴偏移幅度

                    for (i in 0 until count) {
                        val dist = startDist + i * spacing + att.spec.distanceOffset
                        if (dist < 0f) continue
                        if (dist > len) break

                        // 新需求：越接近束尾光圈越大（而不是随时间变大）
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

            // 用“半圈”在不同 layer 渲染，模拟圈绕着光束有前后遮挡。
            // 这里用 ca 的正负划分前/后半圈（相当于 ellipse 在 perp 轴上的左右半圈），足够产生层次。
            val step = (2f * PI).toFloat() / segments.toFloat().coerceAtLeast(1f)
            var drawing = false
            // i <= segments：让曲线在边界处闭合得更自然（但我们是 strip，需手动分段）
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
