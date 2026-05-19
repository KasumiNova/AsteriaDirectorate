package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.max

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
            val scaledVertices = vertices.map { vertex ->
                vertex.copy(position = ASTDProjectileVfxLayout.scalePoint(vertex.position, context.worldUnitsPerPixel))
            }
            ASTDProjectileVfxBodyRenderer.Mesh(
                polygon = ASTDProjectileVfxLayout.scalePoints(polygon, context.worldUnitsPerPixel),
                gradientStops = emptyList(),
                vertices = scaledVertices,
                triangles = ASTDProjectileVfxBodyRenderer.triangulateStrip(scaledVertices),
                blendMode = layer.blendMode,
                combatLayer = baseLayer.combatLayer,
            )
        }
    }

    fun shadowMeshesForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxHeadLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
        headSizeScale: Float = 1f,
        alphaScale: Float = 1f,
    ): List<ASTDProjectileVfxBodyRenderer.Mesh> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        return layers.filter { it.enabled }.map { layer ->
            val layout = fillLayoutForTests(baseLayer, layer, context, headSizeScale)
            val columns = headShadowColumns(layer, layout, widthBase, context, alphaScale)
            val shadow = ASTDProjectileVfxSoftMesh.symmetricOuterFalloff(columns, 8)
            ASTDProjectileVfxBodyRenderer.Mesh(
                polygon = shadow.vertices.map { Vector2f(it.position) },
                gradientStops = emptyList(),
                vertices = shadow.vertices,
                triangles = shadow.triangles,
                blendMode = layer.blendMode,
                combatLayer = baseLayer.combatLayer,
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

    private fun headShadowColumns(
        layer: ASTDProjectileVfxHeadLayerSpec,
        layout: ASTDProjectileVfxLayout.HeadFillLayout,
        widthBase: Float,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float,
    ): List<ASTDProjectileVfxSoftMesh.Column> {
        if (layout.headVisible <= 0.01f) return emptyList()
        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val shadowBlur = max(8f, widthBase * 2.8f) * layout.headVisible + layer.blur
        val shadowColor = layout.colors.mid
        val pairs = listOf(
            layout.vertices.rearTop to layout.vertices.rearBottom,
            layout.vertices.shoulderTop to layout.vertices.shoulderBottom,
            layout.vertices.curveTop to layout.vertices.curveBottom,
            layout.vertices.tip to layout.vertices.tip,
        )
        return pairs.map { (top, bottom) ->
            val x = (top.x + bottom.x) * 0.5f
            val centerY = (top.y + bottom.y) * 0.5f
            val half = abs(bottom.y - top.y) * 0.5f
            val progress = ((x - layout.rearX) / (0f - layout.rearX).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
            val sourceAlpha = samplePreviewShellAlpha(layout, progress)
            ASTDProjectileVfxSoftMesh.Column(
                x = x * scale,
                centerY = centerY * scale,
                innerHalf = half * scale,
                outerHalf = (half + shadowBlur * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_VISIBLE_RADIUS) * scale,
                color = shadowColor,
                alpha = (sourceAlpha * 0.84f * layout.alpha * alphaScale * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_KERNEL_ALPHA).coerceIn(0f, 1f),
            )
        }
    }

    private fun sampleHeadColor(point: Vector2f, layout: ASTDProjectileVfxLayout.HeadFillLayout, alphaScale: Float): ASTDColor {
        val progress = ((point.x - layout.rearX) / (0f - layout.rearX).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val color = samplePreviewShellGradient(layout, progress)
        val stopAlpha = samplePreviewShellAlpha(layout, progress)
        return color.copy(alpha = (stopAlpha * layout.alpha * alphaScale).coerceIn(0f, 1f))
    }

    private fun samplePreviewShellGradient(layout: ASTDProjectileVfxLayout.HeadFillLayout, progress: Float): ASTDColor {
        val t = progress.coerceIn(0f, 1f)
        return when {
            t <= 0.36f -> mix(layout.colors.start, layout.colors.mid, t / 0.36f)
            t <= 0.74f -> mix(layout.colors.mid, layout.colors.end, (t - 0.36f) / (0.74f - 0.36f))
            else -> mix(layout.colors.end, ASTDColor(1f, 1f, 1f, 1f), (t - 0.74f) / (1f - 0.74f))
        }
    }

    private fun samplePreviewShellAlpha(layout: ASTDProjectileVfxLayout.HeadFillLayout, progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return when {
            t <= 0.36f -> {
                val local = t / 0.36f
                ASTDProjectileVfxMath.lerp(layout.colors.start.alpha, layout.colors.mid.alpha, local)
            }
            t <= 0.74f -> {
                val local = (t - 0.36f) / (0.74f - 0.36f)
                ASTDProjectileVfxMath.lerp(layout.colors.mid.alpha, 0.9f, local)
            }
            else -> {
                val local = (t - 0.74f) / (1f - 0.74f)
                ASTDProjectileVfxMath.lerp(0.9f, 0.98f, local)
            }
        }
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
    private var shadowMeshes: List<ASTDProjectileVfxBodyRenderer.Mesh> = emptyList()
    private val handles = ArrayList<ASTDProjectileVfxBodyRenderManager.Handle>()
    private val shadowHandles = ArrayList<ASTDProjectileVfxBodyRenderManager.Handle>()

    fun meshesForTests(): List<ASTDProjectileVfxBodyRenderer.Mesh> = meshes
    fun shadowMeshesForTests(): List<ASTDProjectileVfxBodyRenderer.Mesh> = shadowMeshes

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        meshes = ASTDProjectileVfxHeadRenderer.meshForTests(trail, layers, context, headSizeScale, fade.alpha())
        shadowMeshes = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(trail, layers, context, headSizeScale, fade.alpha())
        if (engine == null) return false
        ensureHandles(engine, meshes.size)
        ensureShadowHandles(engine, shadowMeshes.size)
        handles.zip(meshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
        shadowHandles.zip(shadowMeshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
        return true
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        meshes = ASTDProjectileVfxHeadRenderer.meshForTests(trail, layers, context, headSizeScale, fade.alpha())
        shadowMeshes = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(trail, layers, context, headSizeScale, fade.alpha())
        if (engine != null) {
            ensureHandles(engine, meshes.size)
            ensureShadowHandles(engine, shadowMeshes.size)
            handles.zip(meshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
            shadowHandles.zip(shadowMeshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handles.forEach { it.delete() }
        handles.clear()
        shadowHandles.forEach { it.delete() }
        shadowHandles.clear()
        meshes = emptyList()
        shadowMeshes = emptyList()
    }

    private fun ensureHandles(engine: CombatEngineAPI, required: Int) {
        while (handles.size < required) {
            handles += ASTDProjectileVfxBodyRenderManager.createHandle(engine)
        }
        while (handles.size > required) {
            handles.removeAt(handles.lastIndex).delete()
        }
    }

    private fun ensureShadowHandles(engine: CombatEngineAPI, required: Int) {
        while (shadowHandles.size < required) {
            shadowHandles += ASTDProjectileVfxBodyRenderManager.createHandle(engine)
        }
        while (shadowHandles.size > required) {
            shadowHandles.removeAt(shadowHandles.lastIndex).delete()
        }
    }
}
