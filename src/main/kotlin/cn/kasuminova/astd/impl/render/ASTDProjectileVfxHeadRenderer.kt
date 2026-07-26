package cn.kasuminova.astd.impl.render

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
                triangles = headTriangles(scaledVertices),
                blendMode = layer.blendMode,
                combatLayer = baseLayer.combatLayer,
                renderOrder = ASTDProjectileVfxBodyRenderer.RENDER_ORDER_HEAD,
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
            val shadow = headBloomMesh(layer, layout, widthBase, context, alphaScale)
            ASTDProjectileVfxBodyRenderer.Mesh(
                polygon = shadow.vertices.map { Vector2f(it.position) },
                gradientStops = emptyList(),
                vertices = shadow.vertices,
                triangles = shadow.triangles,
                blendMode = layer.blendMode,
                combatLayer = baseLayer.combatLayer,
                renderOrder = ASTDProjectileVfxBodyRenderer.RENDER_ORDER_HEAD_SHADOW,
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
        val base = pairs.flatMap { (top, bottom) ->
            listOf(
                ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(top), sampleHeadColor(top, layout, alphaScale)),
                ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(bottom), sampleHeadColor(bottom, layout, alphaScale)),
            )
        }
        val curveSamples = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(10)
        for (index in 1..5) {
            val t = index.toFloat() / 5f
            val top = quadratic(layout.vertices.shoulderTop, layout.vertices.curveTop, layout.vertices.tip, t)
            val bottom = quadratic(layout.vertices.shoulderBottom, layout.vertices.curveBottom, layout.vertices.tip, t)
            curveSamples += ASTDProjectileVfxBodyRenderer.Vertex(top, sampleHeadColor(top, layout, alphaScale))
            curveSamples += ASTDProjectileVfxBodyRenderer.Vertex(bottom, sampleHeadColor(bottom, layout, alphaScale))
        }
        return base + curveSamples
    }

    private fun headTriangles(vertices: List<ASTDProjectileVfxBodyRenderer.Vertex>): List<ASTDProjectileVfxBodyRenderer.Triangle> {
        if (vertices.size <= 8) return ASTDProjectileVfxBodyRenderer.triangulateStrip(vertices)
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>()
        triangles += ASTDProjectileVfxBodyRenderer.Triangle(vertices[0], vertices[1], vertices[2])
        triangles += ASTDProjectileVfxBodyRenderer.Triangle(vertices[2], vertices[1], vertices[3])
        val curveStrip = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(2 + vertices.size - 8)
        curveStrip += vertices[2]
        curveStrip += vertices[3]
        curveStrip += vertices.drop(8)
        triangles += ASTDProjectileVfxBodyRenderer.triangulateStrip(curveStrip)
        return triangles
    }

    private fun headBloomMesh(
        layer: ASTDProjectileVfxHeadLayerSpec,
        layout: ASTDProjectileVfxLayout.HeadFillLayout,
        widthBase: Float,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float,
    ): ASTDProjectileVfxSoftMesh.MeshPart {
        if (layout.headVisible <= 0.01f) return ASTDProjectileVfxSoftMesh.MeshPart(emptyList(), emptyList())
        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val shadowBlur = max(8f, widthBase * 3.6f) * layout.headVisible + layer.blur * 1.35f
        val shadowColor = layout.colors.mid
        val outline = headBloomOutline(layout)
        if (outline.size < 3) return ASTDProjectileVfxSoftMesh.MeshPart(emptyList(), emptyList())

        val baseAlpha = 1.65f * layout.alpha * alphaScale * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_KERNEL_ALPHA
        val radius = shadowBlur * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_VISIBLE_RADIUS * scale
        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(outline.size * 8 * 4)
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>(outline.size * 8 * 6)
        val passes = listOf(1f to 0.72f, 1.55f to 0.48f, 2.15f to 0.34f)

        passes.forEach { (radiusScale, alphaPassScale) ->
            for (step in 0 until 8) {
                val innerRatio = step.toFloat() / 8f
                val outerRatio = (step + 1).toFloat() / 8f
                val inner = outline.map { bloomVertex(it, layout, shadowColor, radius, radiusScale, innerRatio, baseAlpha, alphaPassScale, scale) }
                val outer = outline.map { bloomVertex(it, layout, shadowColor, radius, radiusScale, outerRatio, baseAlpha, alphaPassScale, scale) }
                val offset = vertices.size
                vertices += inner
                vertices += outer
                for (index in 0 until outline.lastIndex) {
                    val a = vertices[offset + index]
                    val b = vertices[offset + index + 1]
                    val c = vertices[offset + outline.size + index]
                    val d = vertices[offset + outline.size + index + 1]
                    triangles += ASTDProjectileVfxBodyRenderer.Triangle(a, b, c)
                    triangles += ASTDProjectileVfxBodyRenderer.Triangle(c, b, d)
                }
            }
        }

        return ASTDProjectileVfxSoftMesh.MeshPart(vertices, triangles)
    }

    private data class BloomOutlinePoint(val position: Vector2f, val normal: Vector2f)

    private fun headBloomOutline(layout: ASTDProjectileVfxLayout.HeadFillLayout): List<BloomOutlinePoint> {
        val topCurve = (1..5).map { index ->
            val t = index.toFloat() / 5f
            quadratic(layout.vertices.shoulderTop, layout.vertices.curveTop, layout.vertices.tip, t)
        }
        val bottomCurve = (5 downTo 1).map { index ->
            val t = index.toFloat() / 5f
            quadratic(layout.vertices.shoulderBottom, layout.vertices.curveBottom, layout.vertices.tip, t)
        }
        val outline = listOf(layout.vertices.rearTop, layout.vertices.shoulderTop) +
            topCurve +
            bottomCurve +
            listOf(layout.vertices.shoulderBottom, layout.vertices.rearBottom)
        return outline.map { point -> BloomOutlinePoint(Vector2f(point), bloomNormal(point, layout)) }
    }

    private fun bloomNormal(point: Vector2f, layout: ASTDProjectileVfxLayout.HeadFillLayout): Vector2f {
        val tipEpsilon = max(1f, abs(layout.rearX) * 0.01f)
        val raw = when {
            abs(point.x) <= tipEpsilon -> Vector2f(1f, 0f)
            point.x <= layout.rearX + tipEpsilon && point.y < 0f -> Vector2f(-0.35f, -1f)
            point.x <= layout.rearX + tipEpsilon && point.y > 0f -> Vector2f(-0.35f, 1f)
            point.y < 0f -> Vector2f(0f, -1f)
            point.y > 0f -> Vector2f(0f, 1f)
            else -> Vector2f(1f, 0f)
        }
        val length = kotlin.math.sqrt(raw.x * raw.x + raw.y * raw.y).coerceAtLeast(0.0001f)
        return Vector2f(raw.x / length, raw.y / length)
    }

    private fun bloomVertex(
        point: BloomOutlinePoint,
        layout: ASTDProjectileVfxLayout.HeadFillLayout,
        color: ASTDColor,
        radius: Float,
        radiusScale: Float,
        ratio: Float,
        baseAlpha: Float,
        alphaPassScale: Float,
        scale: Float,
    ): ASTDProjectileVfxBodyRenderer.Vertex {
        val t = ratio.coerceIn(0f, 1f)
        val x = point.position.x * scale + point.normal.x * radius * radiusScale * t
        val y = point.position.y * scale + point.normal.y * radius * radiusScale * t
        val progress = ((point.position.x - layout.rearX) / (0f - layout.rearX).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val sourceAlpha = samplePreviewShellAlpha(layout, progress)
        val alpha = sourceAlpha * baseAlpha * alphaPassScale * bloomFalloff(t)
        return ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x, y), color.copy(alpha = alpha.coerceIn(0f, 1f)))
    }

    private fun bloomFalloff(t: Float): Float {
        val inverse = 1f - ASTDProjectileVfxMath.smoothstep(0f, 1f, t)
        return inverse * inverse
    }

    private fun quadratic(start: Vector2f, control: Vector2f, end: Vector2f, t: Float): Vector2f {
        val ratio = t.coerceIn(0f, 1f)
        val inverse = 1f - ratio
        return Vector2f(
            inverse * inverse * start.x + 2f * inverse * ratio * control.x + ratio * ratio * end.x,
            inverse * inverse * start.y + 2f * inverse * ratio * control.y + ratio * ratio * end.y,
        )
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
