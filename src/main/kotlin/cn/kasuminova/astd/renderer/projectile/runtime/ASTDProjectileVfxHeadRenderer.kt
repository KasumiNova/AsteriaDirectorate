package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f

object ASTDProjectileVfxHeadRenderer {
    fun verticesForTests(layer: ASTDProjectileVfxHeadLayerSpec, visible: Float, widthBase: Float = 6f, headSizeScale: Float = 1f): List<Vector2f> {
        return ASTDProjectileVfxLayout.headVertices(layer, visible, headSizeScale, widthBase).asList()
    }

    fun alphaForTests(layer: ASTDProjectileVfxHeadLayerSpec, context: ASTDProjectileVfxRenderContext): Float {
        return layer.alphaScale * context.beamAlpha
    }

    fun colorsForTests(baseLayer: ASTDTrailLayerSpec, layer: ASTDProjectileVfxHeadLayerSpec): ASTDProjectileVfxLayout.HeadColors {
        return ASTDProjectileVfxLayout.headColors(baseLayer, layer)
    }

    fun fillLayoutForTests(
        baseLayer: ASTDTrailLayerSpec,
        layer: ASTDProjectileVfxHeadLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        headSizeScale: Float = 1f,
    ): ASTDProjectileVfxLayout.HeadFillLayout {
        return ASTDProjectileVfxLayout.headFillLayout(
            baseLayer = baseLayer,
            layer = layer,
            headSizeScale = headSizeScale,
            widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer),
            pulse = context.beamAlpha.coerceIn(0f, 1f),
        )
    }

    fun meshForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxHeadLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
        headSizeScale: Float = 1f,
        alphaScale: Float = 1f,
    ): List<ASTDProjectileVfxBodyRenderer.Mesh> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        return layers.filter { it.enabled }.map { layer ->
            val layout = fillLayoutForTests(baseLayer, layer, context, headSizeScale)
            val polygon = layout.vertices.asList().map { Vector2f(it) }
            val vertices = headStripVertices(layout, alphaScale)
            val envelope = headShadowEnvelope(polygon, layout, alphaScale)
            val tipBloom = headTipBloom(layout, alphaScale)
            val allVertices = vertices + envelope.vertices + tipBloom.vertices
            ASTDProjectileVfxBodyRenderer.Mesh(
                polygon = polygon,
                gradientStops = emptyList(),
                vertices = allVertices,
                triangles = ASTDProjectileVfxBodyRenderer.triangulateStrip(vertices) + envelope.triangles + tipBloom.triangles,
                blendMode = layer.blendMode,
                combatLayer = baseLayer.combatLayer,
                xScale = 1.2f,
                yScale = 0.54f,
                shaderQuad = ASTDProjectileVfxShaderRenderer.headQuadForTests(trail, layer, context, headSizeScale, alphaScale),
            )
        }
    }

    private fun triangulate(vertices: List<ASTDProjectileVfxBodyRenderer.Vertex>): List<ASTDProjectileVfxBodyRenderer.Triangle> {
        if (vertices.size < 3) return emptyList()
        return (1 until vertices.lastIndex).map { index ->
            ASTDProjectileVfxBodyRenderer.Triangle(vertices[0], vertices[index], vertices[index + 1])
        }
    }

    private fun headStripVertices(
        layout: ASTDProjectileVfxLayout.HeadFillLayout,
        alphaScale: Float,
    ): List<ASTDProjectileVfxBodyRenderer.Vertex> {
        val pairs = listOf(
            layout.vertices.rearTop to layout.vertices.rearBottom,
            layout.vertices.shoulderTop to layout.vertices.shoulderBottom,
            layout.vertices.curveTop to layout.vertices.curveBottom,
            layout.vertices.tip to layout.vertices.tip,
        )
        return pairs.flatMap { (top, bottom) ->
            listOf(
                ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(top), sampleHeadColor(top, layout, alphaScale)),
                ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(bottom), sampleHeadColor(bottom, layout, alphaScale)),
            )
        }
    }

    private fun sampleHeadColor(point: Vector2f, layout: ASTDProjectileVfxLayout.HeadFillLayout, alphaScale: Float): ASTDColor {
        val progress = ((point.x - layout.rearX) / (0f - layout.rearX).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val color = samplePreviewShellGradient(layout, progress)
        val hotAttenuation = when {
            progress >= 0.74f -> 0.008f
            progress >= 0.36f -> 0.024f
            else -> 0.16f
        }
        return color.copy(alpha = (layout.alpha * alphaScale * hotAttenuation).coerceIn(0f, 1f))
    }

    private fun samplePreviewShellGradient(layout: ASTDProjectileVfxLayout.HeadFillLayout, progress: Float): ASTDColor {
        val t = progress.coerceIn(0f, 1f)
        return when {
            t <= 0.36f -> mix(layout.colors.start, layout.colors.mid, t / 0.36f)
            t <= 0.74f -> mix(layout.colors.mid, layout.colors.end, (t - 0.36f) / (0.74f - 0.36f))
            else -> mix(layout.colors.end, ASTDColor(1f, 1f, 1f, 1f), (t - 0.74f) / (1f - 0.74f))
        }
    }

    private data class MeshPart(
        val vertices: List<ASTDProjectileVfxBodyRenderer.Vertex>,
        val triangles: List<ASTDProjectileVfxBodyRenderer.Triangle>,
    )

    private fun headShadowEnvelope(
        polygon: List<Vector2f>,
        layout: ASTDProjectileVfxLayout.HeadFillLayout,
        alphaScale: Float,
    ): MeshPart {
        if (polygon.size < 4 || layout.headVisible <= 0.01f) return MeshPart(emptyList(), emptyList())
        val blur = kotlin.math.max(8f, layout.width * 0.7f) * layout.headVisible
        val color = layout.colors.mid
        val samples = listOf(0f, 0.28f, 0.58f, 0.82f, 1f)
        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(samples.size * 4)
        for (band in 0..1) {
            val expand = blur * (0.42f + band * 0.36f)
            val alpha = 0.84f * layout.alpha * alphaScale * (0.006f / (band + 1f))
            for (t in samples) {
                val x = ASTDProjectileVfxMath.lerp(layout.rearX, 0f, t)
                val baseHalf = ASTDProjectileVfxBodyRenderer.halfHeightAt(polygon, x)
                val profile = ASTDProjectileVfxMath.smoothstep(0.02f, 0.26f, t) *
                    (1f - ASTDProjectileVfxMath.smoothstep(0.92f, 1f, t))
                val half = baseHalf + expand * (0.35f + 0.65f * profile)
                val vertexColor = color.copy(alpha = (alpha * profile).coerceIn(0f, 1f))
                vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x, -half), vertexColor)
                vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x, half), vertexColor)
            }
        }
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>()
        val stripSize = samples.size * 2
        for (band in 0..1) {
            triangles += ASTDProjectileVfxBodyRenderer.triangulateStrip(vertices.subList(band * stripSize, band * stripSize + stripSize))
        }
        return MeshPart(vertices, triangles)
    }

    private fun headTipBloom(
        layout: ASTDProjectileVfxLayout.HeadFillLayout,
        alphaScale: Float,
    ): MeshPart {
        if (layout.headVisible <= 0.01f) return MeshPart(emptyList(), emptyList())
        val length = (-layout.rearX).coerceAtLeast(1f)
        val color = layout.colors.mid
        val samples = listOf(0f, 0.34f, 0.68f, 1f)
        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(samples.size * 6)
        for (band in 0..2) {
            val alpha = layout.alpha * alphaScale * (0.006f / (band + 1f))
            for (t in samples) {
                val x = ASTDProjectileVfxMath.lerp(-length * 0.58f, length * 0.08f, t)
                val profile = ASTDProjectileVfxMath.smoothstep(0f, 0.45f, t) *
                    (1f - ASTDProjectileVfxMath.smoothstep(0.9f, 1f, t))
                val half = layout.width * (0.45f + band * 0.62f) * profile
                vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x, -half), color.copy(alpha = alpha * profile))
                vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x, half), color.copy(alpha = alpha * profile))
            }
        }
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>()
        val stripSize = samples.size * 2
        for (band in 0..2) {
            triangles += ASTDProjectileVfxBodyRenderer.triangulateStrip(vertices.subList(band * stripSize, band * stripSize + stripSize))
        }
        return MeshPart(vertices, triangles)
    }

    private fun mix(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor {
        val ratio = t.coerceIn(0f, 1f)
        return ASTDColor(
            red = a.red + (b.red - a.red) * ratio,
            green = a.green + (b.green - a.green) * ratio,
            blue = a.blue + (b.blue - a.blue) * ratio,
            alpha = a.alpha + (b.alpha - a.alpha) * ratio,
        )
    }
}

class ASTDProjectileVfxHeadRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxHeadLayerSpec>,
    private val headSizeScale: Float = 1f,
) : ASTDProjectileVfxRenderLayer {
    private val fade = ASTDProjectileVfxLayerFadeState()
    private var meshes: List<ASTDProjectileVfxBodyRenderer.Mesh> = emptyList()
    private val handles = ArrayList<ASTDProjectileVfxBodyRenderManager.Handle>()

    fun meshesForTests(): List<ASTDProjectileVfxBodyRenderer.Mesh> = meshes

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        meshes = ASTDProjectileVfxHeadRenderer.meshForTests(trail, layers, context, headSizeScale, fade.alpha())
        if (engine == null) return false
        ensureHandles(engine, meshes.size)
        handles.zip(meshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
        return true
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        meshes = ASTDProjectileVfxHeadRenderer.meshForTests(trail, layers, context, headSizeScale, fade.alpha())
        if (engine != null) {
            ensureHandles(engine, meshes.size)
            handles.zip(meshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handles.forEach { it.delete() }
        handles.clear()
        meshes = emptyList()
    }

    private fun ensureHandles(engine: CombatEngineAPI, required: Int) {
        while (handles.size < required) {
            handles += ASTDProjectileVfxBodyRenderManager.createHandle(engine)
        }
        while (handles.size > required) {
            handles.removeAt(handles.lastIndex).delete()
        }
    }
}
