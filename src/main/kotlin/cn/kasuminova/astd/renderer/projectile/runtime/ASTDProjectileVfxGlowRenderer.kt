package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxGlowLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDColor
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

object ASTDProjectileVfxGlowRenderer {
    data class Parameters(
        val id: String,
        val widthScale: Float,
        val lineWidth: Float,
        val alpha: Float,
        val blur: Float,
        val yOffset: Float,
        val nodes: List<Vector2f>,
        val startColor: ASTDColor,
        val endColor: ASTDColor,
    )

    fun parametersForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxGlowLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
    ): List<Parameters> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer) * ASTDProjectileVfxShaderRenderer.PREVIEW_BODY_WIDTH_SCALE
        return layers.filter { it.enabled }.map { layer ->
            val colors = colors(baseLayer, layer)
            Parameters(
                id = layer.id,
                widthScale = layer.widthScale,
                lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, layer),
                alpha = layer.alphaScale * context.beamAlpha,
                blur = layer.blur,
                yOffset = layer.yOffset,
                nodes = ASTDProjectileVfxLayout.glowLocalNodes(context.visibleLength, layer),
                startColor = colors.first,
                endColor = colors.second,
            )
        }
    }

    fun meshesForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxGlowLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): List<ASTDProjectileVfxBodyRenderer.Mesh> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        return layers.filter { it.enabled }.map { layer ->
            glowStrokeMesh(baseLayer, layer, context, widthBase, alphaScale)
        }
    }

    fun layerSpec(baseLayer: ASTDTrailLayerSpec, glow: ASTDProjectileVfxGlowLayerSpec, context: ASTDProjectileVfxRenderContext): ASTDTrailLayerSpec {
        val (tail, head) = colors(baseLayer, glow)
        val width = ASTDProjectileVfxLayout.glowLineWidth(ASTDProjectileVfxLayout.widthBase(baseLayer), glow)
        return baseLayer.copy(
            width = width,
            startWidth = width,
            endWidth = width,
            startColor = head,
            endColor = tail,
            startEmissive = head,
            endEmissive = tail,
            jitterPower = baseLayer.jitterPower + glow.blur * 0.02f,
        )
    }

    fun colors(baseLayer: ASTDTrailLayerSpec, glow: ASTDProjectileVfxGlowLayerSpec): Pair<ASTDColor, ASTDColor> {
        if (glow.gradientStops.isNotEmpty()) {
            return sampleColorStops(glow.gradientStops, 0f, baseLayer.endColor) to sampleColorStops(glow.gradientStops, 1f, baseLayer.startColor)
        }
        val darkTail = mix(baseLayer.endColor, baseLayer.endEmissive, 0.52f)
        val hotCore = mix(baseLayer.startColor, baseLayer.startEmissive, 0.44f)
        val tail = mix(darkTail, hotCore, glow.colorMixTail)
        val head = if (glow.colorMixHead >= 1f) ASTDColor(1f, 1f, 1f, 1f) else mix(baseLayer.startColor, baseLayer.startEmissive, glow.colorMixHead)
        return tail to head
    }

    private fun sampleColorStops(stops: List<cn.kasuminova.astd.renderer.projectile.ASTDColorStopSpec>, offset: Float, fallback: ASTDColor): ASTDColor {
        if (stops.isEmpty()) return fallback
        val sorted = stops.sortedBy { it.offset }
        if (offset <= sorted.first().offset) return sorted.first().color
        for (index in 0 until sorted.lastIndex) {
            val left = sorted[index]
            val right = sorted[index + 1]
            if (offset >= left.offset && offset <= right.offset) {
                val ratio = ((offset - left.offset) / (right.offset - left.offset).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                return mix(left.color, right.color, ratio)
            }
        }
        return sorted.last().color
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

    private fun glowStrokeMesh(
        baseLayer: ASTDTrailLayerSpec,
        glow: ASTDProjectileVfxGlowLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        widthBase: Float,
        fadeAlpha: Float,
    ): ASTDProjectileVfxBodyRenderer.Mesh {
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)
        val headGap = max(14f, lineWidth * 0.55f)
        val startX = -context.visibleLength * 0.72f
        val endX = -headGap
        val (tail, head) = colors(baseLayer, glow)
        val samples = listOf(0f, 0.22f, 0.62f, 0.88f, 1f)
        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(samples.size * 8)
        for (band in 0..3) {
            val bandScale = 0.26f + band * 0.36f
            val bandAlphaScale = listOf(0.055f, 0.011f, 0.0035f, 0.0015f)[band]
            val halfWidth = (lineWidth + glow.blur * bandScale) * 0.5f
            for (t in samples) {
                val x = ASTDProjectileVfxMath.lerp(startX, endX, t)
                val y = ASTDProjectileVfxMath.lerp(glow.yOffset, glow.yOffset * 0.18f, t)
                val color = glowColorAt(t, tail, head)
                val alpha = glowAlphaAt(t, glow.alphaScale * context.beamAlpha * fadeAlpha) * bandAlphaScale
                val vertexColor = color.copy(alpha = alpha.coerceIn(0f, 1f))
                vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x, y - halfWidth), vertexColor)
                vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x, y + halfWidth), vertexColor)
            }
        }
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>()
        val stripSize = samples.size * 2
        for (band in 0..3) {
            triangles += ASTDProjectileVfxBodyRenderer.triangulateStrip(vertices.subList(band * stripSize, band * stripSize + stripSize))
        }
        return ASTDProjectileVfxBodyRenderer.Mesh(
            polygon = vertices.map { Vector2f(it.position) },
            gradientStops = emptyList(),
            vertices = vertices,
            triangles = triangles,
            blendMode = "additive",
            combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
            xScale = 1.2f,
            yScale = ASTDProjectileVfxShaderRenderer.PREVIEW_VERTICAL_SCALE,
            shaderQuad = ASTDProjectileVfxShaderRenderer.glowQuadsForTests(
                ASTDTrailEntitySpec(
                    layerId = "astd_runtime_glow_shader",
                    nodes = emptyList(),
                    id = "astd_runtime_glow_shader",
                    layerSpec = baseLayer,
                    layers = listOf(baseLayer),
                ),
                listOf(glow),
                context,
                fadeAlpha,
            ).single(),
        )
    }

    private fun glowColorAt(t: Float, tail: ASTDColor, head: ASTDColor): ASTDColor = when {
        t <= 0.22f -> darken(tail, 0.36f)
        t <= 0.62f -> tail
        t <= 0.88f -> mix(tail, head, 0.55f)
        t < 1f -> head
        else -> ASTDColor(1f, 0.9f, 0.98f, 1f)
    }

    private fun glowAlphaAt(t: Float, alpha: Float): Float = when {
        t <= 0f -> 0f
        t <= 0.22f -> alpha * 0.22f
        t <= 0.62f -> alpha * 0.65f
        t <= 0.88f -> alpha
        else -> alpha * 0.46f
    }

    private fun darken(color: ASTDColor, factor: Float): ASTDColor = ASTDColor(
        red = color.red * factor,
        green = color.green * factor,
        blue = color.blue * factor,
        alpha = color.alpha,
    )
}

class ASTDProjectileVfxGlowRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxGlowLayerSpec>,
) : ASTDProjectileVfxRenderLayer {
    private data class Handle(val spec: ASTDProjectileVfxGlowLayerSpec, val handle: ASTDProjectileVfxBodyRenderManager.Handle)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        layers.filter { it.enabled }.forEach { glow ->
            val handle = ASTDProjectileVfxBodyRenderManager.createHandle(engine)
            val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(trail, listOf(glow), context, fade.alpha()).single()
            handle.update(context.location, context.renderFacing, mesh)
            handles += Handle(glow, handle)
        }
        return handles.size == layers.count { it.enabled }
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        handles.forEach { handle ->
            val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(trail, listOf(handle.spec), context, fade.alpha()).single()
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
}
