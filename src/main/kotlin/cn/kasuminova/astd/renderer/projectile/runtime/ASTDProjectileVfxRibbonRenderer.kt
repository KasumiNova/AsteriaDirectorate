package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailRibbonDecorationSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max
import kotlin.math.sqrt

object ASTDProjectileVfxRibbonRenderer {
    data class RibbonPoint(val base: Vector2f, val position: Vector2f, val alpha: Float, val color: ASTDColor)
    data class RibbonWidths(val startWidth: Float, val endWidth: Float)

    fun widthsForTests(ribbon: ASTDTrailRibbonDecorationSpec, baseTrailStartWidth: Float): RibbonWidths {
        val widthBase = max(0.5f, baseTrailStartWidth * ribbon.thickness)
        return RibbonWidths(widthBase, max(0.5f, widthBase * 0.76f))
    }

    fun pointsForTests(
        ribbon: ASTDTrailRibbonDecorationSpec,
        context: ASTDProjectileVfxRenderContext,
        sampleCount: Int,
        baseTrailStartWidth: Float = 40f,
    ): List<RibbonPoint> {
        return (0..sampleCount).map { index ->
            val t = index.toFloat() / sampleCount.coerceAtLeast(1).toFloat()
            val dist = context.visibleLength * t * ribbon.lengthScale + ribbon.startOffset
            val base = Vector2f(-dist.coerceAtLeast(0f), 0f)
            val widthBase = max(0.65f, baseTrailStartWidth * ribbon.thickness)
            val smokeEnvelope = smokeEnvelope(t)
            val wave = ASTDProjectileVfxMath.ribbonWave(
                ribbon.waveType,
                context.location.x + base.x,
                context.elapsed,
                ribbon.frequency,
                ribbon.waveSpeed,
                ribbon.amplitude,
                ribbon.noiseScale,
                17,
                0.48f,
            )
            val perpOffset = (ribbon.endOffset + wave * widthBase) * ASTDProjectileVfxMath.lerp(0.72f, 1f, smokeEnvelope)
            val position = Vector2f(base.x, base.y + perpOffset)
            val alpha = ribbon.alphaScale * context.beamAlpha * (1f - t * 0.22f) * ASTDProjectileVfxMath.lerp(0.6f, 1f, smokeEnvelope)
            RibbonPoint(base, position, alpha, sampleColor(ribbon, t))
        }
    }

    fun smokeEnvelopeForTest(t: Float): Float = smokeEnvelope(t)

    fun meshForTests(
        ribbon: ASTDTrailRibbonDecorationSpec,
        context: ASTDProjectileVfxRenderContext,
        sampleCount: Int,
        baseTrailStartWidth: Float,
    ): ASTDProjectileVfxBodyRenderer.Mesh {
        val points = pointsForTests(ribbon, context, sampleCount, baseTrailStartWidth)
        val widths = widthsForTests(ribbon, baseTrailStartWidth)
        if (points.size < 2) {
            return ASTDProjectileVfxBodyRenderer.Mesh(
            polygon = emptyList(),
            gradientStops = emptyList(),
            vertices = emptyList(),
            triangles = emptyList(),
            blendMode = "additive",
            combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
            )
        }

        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(points.size * 2)
        val polygon = ArrayList<Vector2f>(points.size * 2)
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>((points.size - 1) * 4)
        appendRibbonStrip(
            points = points,
            startWidth = widths.startWidth,
            endWidth = widths.endWidth,
            alphaMultiplier = 2.4f,
            vertices = vertices,
            polygon = polygon,
            triangles = triangles,
        )
        appendRibbonStrip(
            points = points,
            startWidth = max(0.5f, widths.startWidth * 0.38f),
            endWidth = max(0.5f, widths.startWidth * 0.38f),
            alphaMultiplier = 0.38f,
            vertices = vertices,
            polygon = polygon,
            triangles = triangles,
        )

        return ASTDProjectileVfxBodyRenderer.Mesh(
            polygon = polygon,
            gradientStops = emptyList(),
            vertices = vertices,
            triangles = triangles,
            blendMode = "additive",
            combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
            xScale = 1.2f,
            yScale = 1f,
        )
    }

    fun sampleColor(ribbon: ASTDTrailRibbonDecorationSpec, t: Float): ASTDColor {
        val stops = ribbon.colorGradient.stops.takeIf { ribbon.colorGradient.enabled && it.isNotEmpty() }
            ?.sortedBy { it.offset }
            ?: return mix(ribbon.startColor, ribbon.endColor, t)
        if (t <= stops.first().offset) return stops.first().color
        for (index in 0 until stops.lastIndex) {
            val left = stops[index]
            val right = stops[index + 1]
            if (t >= left.offset && t <= right.offset) {
                val ratio = ((t - left.offset) / (right.offset - left.offset).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                return mix(left.color, right.color, ratio)
            }
        }
        return stops.last().color
    }

    private fun appendRibbonStrip(
        points: List<RibbonPoint>,
        startWidth: Float,
        endWidth: Float,
        alphaMultiplier: Float,
        vertices: MutableList<ASTDProjectileVfxBodyRenderer.Vertex>,
        polygon: MutableList<Vector2f>,
        triangles: MutableList<ASTDProjectileVfxBodyRenderer.Triangle>,
    ) {
        val stripStart = vertices.size
        points.forEachIndexed { index, point ->
            val t = index.toFloat() / (points.size - 1).coerceAtLeast(1).toFloat()
            val halfWidth = (startWidth + (endWidth - startWidth) * t) * 0.5f
            val previous = points.getOrNull(index - 1)?.position ?: point.position
            val next = points.getOrNull(index + 1)?.position ?: point.position
            val dx = next.x - previous.x
            val dy = next.y - previous.y
            val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
            val normalX = -dy / length
            val normalY = dx / length
            val left = Vector2f(point.position.x + normalX * halfWidth, point.position.y + normalY * halfWidth)
            val right = Vector2f(point.position.x - normalX * halfWidth, point.position.y - normalY * halfWidth)
            val color = point.color.copy(alpha = (point.color.alpha * point.alpha * alphaMultiplier).coerceIn(0f, 1f))
            vertices += ASTDProjectileVfxBodyRenderer.Vertex(left, color)
            vertices += ASTDProjectileVfxBodyRenderer.Vertex(right, color)
            polygon += left
            polygon += right
        }
        for (index in 0 until points.lastIndex) {
            val base = stripStart + index * 2
            val a = vertices[base]
            val b = vertices[base + 1]
            val c = vertices[base + 2]
            val d = vertices[base + 3]
            triangles += ASTDProjectileVfxBodyRenderer.Triangle(a, b, c)
            triangles += ASTDProjectileVfxBodyRenderer.Triangle(c, b, d)
        }
    }

    private fun mix(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor {
        val ratio = t.coerceIn(0f, 1f)
        return ASTDColor(
            a.red + (b.red - a.red) * ratio,
            a.green + (b.green - a.green) * ratio,
            a.blue + (b.blue - a.blue) * ratio,
            a.alpha + (b.alpha - a.alpha) * ratio,
        )
    }

    private fun estimateHistoryPixelsPerEntry(history: List<Vector2f>): Float {
        if (history.size < 2) return 4f
        var total = 0f
        val sampleN = minOf(history.size - 1, 8)
        for (index in 0 until sampleN) {
            val a = history[index]
            val b = history[index + 1]
            val dx = a.x - b.x
            val dy = a.y - b.y
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return max(0.5f, total / sampleN)
    }

    private fun smokeEnvelope(t: Float): Float {
        return ASTDProjectileVfxMath.smoothstep(0.08f, 0.28f, t) *
            (1f - ASTDProjectileVfxMath.smoothstep(0.7f, 0.96f, t))
    }
}

class ASTDProjectileVfxRibbonRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val ribbons: List<ASTDTrailRibbonDecorationSpec>,
) : ASTDProjectileVfxRenderLayer {
    private data class Handle(val ribbon: ASTDTrailRibbonDecorationSpec, val handle: ASTDProjectileVfxBodyRenderManager.Handle)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        val baseTrailStartWidth = baseTrailStartWidth()
        ribbons.filter { it.enabled }.forEach { ribbon ->
            val handle = ASTDProjectileVfxBodyRenderManager.createHandle(engine)
            val mesh = mesh(ribbon, context, baseTrailStartWidth)
            handle.update(context.location, context.renderFacing, mesh)
            handles += Handle(ribbon, handle)
        }
        return handles.size == ribbons.count { it.enabled }
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        val baseTrailStartWidth = baseTrailStartWidth()
        handles.forEach { handle ->
            val mesh = mesh(handle.ribbon, context.copy(beamAlpha = context.beamAlpha * fade.alpha()), baseTrailStartWidth)
            handle.handle.update(context.location, context.renderFacing, mesh)
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handles.forEach { it.handle.delete() }
        handles.clear()
    }

    private fun mesh(
        ribbon: ASTDTrailRibbonDecorationSpec,
        context: ASTDProjectileVfxRenderContext,
        baseTrailStartWidth: Float,
    ): ASTDProjectileVfxBodyRenderer.Mesh {
        val sampleCount = if (ribbon.renderMode == "byNodeCount") {
            max(8, (context.historyNodes.size * ribbon.nodeCountScale).toInt())
        } else {
            max(8, (context.visibleLength * ribbon.lengthScale / 8f).toInt())
        }
        return ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, context, sampleCount, baseTrailStartWidth)
    }

    private fun baseTrailStartWidth(): Float {
        val layer = trail.layers.firstOrNull() ?: trail.layerSpec
        return layer.startWidth
    }
}
