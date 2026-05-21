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

    data class BoxUtilGlowSegment(
        val startT: Float,
        val endT: Float,
        val width: Float,
        val startColor: ASTDColor,
        val endColor: ASTDColor,
        val glowPower: Float,
    )

    fun parametersForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxGlowLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
    ): List<Parameters> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        return layers.filter { it.enabled }.map { layer ->
            val colors = colors(baseLayer, layer)
            Parameters(
                id = layer.id,
                widthScale = layer.widthScale,
                lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, layer),
                alpha = layer.alphaScale * context.beamAlpha,
                blur = layer.blur,
                yOffset = layer.yOffset,
                nodes = runtimeNodes(context.visibleLength, widthBase, layer),
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

    fun shadowMeshesForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxGlowLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): List<ASTDProjectileVfxBodyRenderer.Mesh> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        return layers.filter { it.enabled }.map { layer ->
            glowShadowMesh(baseLayer, layer, context, widthBase, alphaScale)
        }
    }

    fun layerSpec(baseLayer: ASTDTrailLayerSpec, glow: ASTDProjectileVfxGlowLayerSpec): ASTDTrailLayerSpec {
        val (tail, head) = colors(baseLayer, glow)
        val width = ASTDProjectileVfxLayout.glowLineWidth(ASTDProjectileVfxLayout.widthBase(baseLayer), glow)
        return baseLayer.copy(
            width = width,
            color = head,
            startWidth = width,
            endWidth = width,
            startColor = head,
            endColor = tail,
            startEmissive = head,
            endEmissive = tail,
            fillStartAlpha = 0.22f,
            fillEndAlpha = 1f,
            fillStartFactor = 0f,
            fillEndFactor = 0f,
            jitterPower = baseLayer.jitterPower,
        )
    }

    fun glowPower(glow: ASTDProjectileVfxGlowLayerSpec): Float {
        return 1f
    }

    fun boxUtilSegmentsForTests(
        baseLayer: ASTDTrailLayerSpec,
        widthBase: Float,
        glow: ASTDProjectileVfxGlowLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): List<BoxUtilGlowSegment> {
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)
        val (tail, head) = colors(baseLayer, glow)
        val alpha = glow.alphaScale * context.beamAlpha * alphaScale
        val samples = listOf(0f, 0.22f, 0.62f, 0.88f, 1f)
        return samples.zipWithNext().map { (startT, endT) ->
            val start = glowSampleAt(startT, tail, head, alpha)
            val end = glowSampleAt(endT, tail, head, alpha)
            BoxUtilGlowSegment(
                startT = startT,
                endT = endT,
                width = lineWidth,
                startColor = start.first.copy(alpha = start.second.coerceIn(0f, 1f)),
                endColor = end.first.copy(alpha = end.second.coerceIn(0f, 1f)),
                glowPower = glowPower(glow),
            )
        }
    }

    fun segmentLayerSpec(baseLayer: ASTDTrailLayerSpec, segment: BoxUtilGlowSegment): ASTDTrailLayerSpec {
        return baseLayer.copy(
            width = segment.width,
            color = segment.endColor,
            startWidth = segment.width,
            endWidth = segment.width,
            startColor = segment.endColor,
            endColor = segment.startColor,
            startEmissive = segment.endColor,
            endEmissive = segment.startColor,
            fillStartAlpha = 1f,
            fillEndAlpha = 1f,
            fillStartFactor = 0f,
            fillEndFactor = 0f,
            jitterPower = baseLayer.jitterPower,
        )
    }

    fun mutableRuntimeNodes(
        visibleLength: Float,
        widthBase: Float,
        glow: ASTDProjectileVfxGlowLayerSpec,
        worldUnitsPerPixel: Float = 1f,
        startT: Float = 0f,
        endT: Float = 1f,
    ): ArrayList<Vector2f> {
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)
        val headGap = max(14f, lineWidth * 0.55f)
        val startX = -visibleLength.coerceAtLeast(0f) * 0.72f
        val endX = -headGap
        fun point(t: Float): Vector2f {
            val ratio = t.coerceIn(0f, 1f)
            return Vector2f(
                ASTDProjectileVfxMath.lerp(startX, endX, ratio),
                ASTDProjectileVfxMath.lerp(glow.yOffset, glow.yOffset * 0.18f, ratio),
            )
        }
        return ASTDProjectileVfxLayout.mutableScaledNodeList(
            listOf(point(startT), point(endT)),
            worldUnitsPerPixel,
        )
    }

    fun runtimeNodes(visibleLength: Float, widthBase: Float, glow: ASTDProjectileVfxGlowLayerSpec): List<Vector2f> {
        return mutableRuntimeNodes(visibleLength, widthBase, glow).map { Vector2f(it) }
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
        val parts = glowMeshParts(baseLayer, glow, context, widthBase, fadeAlpha)
        val allVertices = parts.strokeVertices + parts.shadow.vertices
        return ASTDProjectileVfxBodyRenderer.Mesh(
            polygon = allVertices.map { Vector2f(it.position) },
            gradientStops = emptyList(),
            vertices = allVertices,
            triangles = ASTDProjectileVfxBodyRenderer.triangulateStrip(parts.strokeVertices) + parts.shadow.triangles,
            blendMode = "additive",
            combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
            renderOrder = ASTDProjectileVfxBodyRenderer.RENDER_ORDER_GLOW,
        )
    }

    private fun glowShadowMesh(
        baseLayer: ASTDTrailLayerSpec,
        glow: ASTDProjectileVfxGlowLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        widthBase: Float,
        fadeAlpha: Float,
    ): ASTDProjectileVfxBodyRenderer.Mesh {
        val shadow = glowMeshParts(baseLayer, glow, context, widthBase, fadeAlpha).shadow
        return ASTDProjectileVfxBodyRenderer.Mesh(
            polygon = shadow.vertices.map { Vector2f(it.position) },
            gradientStops = emptyList(),
            vertices = shadow.vertices,
            triangles = shadow.triangles,
            blendMode = "additive",
            combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
            renderOrder = ASTDProjectileVfxBodyRenderer.RENDER_ORDER_GLOW,
        )
    }

    private data class GlowMeshParts(
        val strokeVertices: List<ASTDProjectileVfxBodyRenderer.Vertex>,
        val shadow: ASTDProjectileVfxSoftMesh.MeshPart,
    )

    private fun glowMeshParts(
        baseLayer: ASTDTrailLayerSpec,
        glow: ASTDProjectileVfxGlowLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        widthBase: Float,
        fadeAlpha: Float,
    ): GlowMeshParts {
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)
        val headGap = max(14f, lineWidth * 0.55f)
        val startX = -context.visibleLength * 0.72f
        val endX = -headGap
        val (tail, head) = colors(baseLayer, glow)
        val samples = glowStrokeSamples(context.visibleLength, headGap)
        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(samples.size * 2)
        val softColumns = ArrayList<ASTDProjectileVfxSoftMesh.Column>(samples.size)
        val coreHalf = lineWidth * 0.5f
        val outerHalf = coreHalf + glow.blur * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_VISIBLE_RADIUS
        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val centerline = if (context.historyNodes.size >= 3 && !ASTDProjectileVfxCenterline.isEffectivelyStraight(context)) {
            ASTDProjectileVfxCenterline.build(context)
        } else {
            emptyList()
        }
        for (samplePoint in samples) {
            val distance = ASTDProjectileVfxMath.lerp(context.visibleLength * 0.72f, headGap, samplePoint.pathT)
            val yOffset = ASTDProjectileVfxMath.lerp(glow.yOffset, glow.yOffset * 0.18f, samplePoint.pathT)
            val center = if (centerline.isNotEmpty()) {
                ASTDProjectileVfxCenterline.offsetPoint(centerline, distance, yOffset)
            } else {
                Vector2f(
                    ASTDProjectileVfxMath.lerp(startX, endX, samplePoint.pathT),
                    yOffset,
                )
            }
            val normal = if (centerline.isNotEmpty()) {
                ASTDProjectileVfxCenterline.normalAt(
                    centerline,
                    (distance / context.visibleLength.coerceAtLeast(0.0001f)).coerceIn(0f, 1f),
                )
            } else {
                Vector2f(0f, 1f)
            }
            val sample = glowSampleAt(samplePoint.gradientT, tail, head, glow.alphaScale * context.beamAlpha * fadeAlpha)
            val color = sample.first
            val alpha = sample.second
            val vertexColor = color.copy(alpha = alpha.coerceIn(0f, 1f))
            vertices += ASTDProjectileVfxBodyRenderer.Vertex(
                Vector2f((center.x - normal.x * coreHalf) * scale, (center.y - normal.y * coreHalf) * scale),
                vertexColor,
            )
            vertices += ASTDProjectileVfxBodyRenderer.Vertex(
                Vector2f((center.x + normal.x * coreHalf) * scale, (center.y + normal.y * coreHalf) * scale),
                vertexColor,
            )
            val shadowColor = mix(color, head, 0.65f)
            softColumns += ASTDProjectileVfxSoftMesh.Column(
                x = center.x * scale,
                centerY = center.y * scale,
                innerHalf = coreHalf * scale,
                outerHalf = outerHalf * scale,
                color = shadowColor,
                alpha = (alpha * 0.42f * ASTDProjectileVfxSoftMesh.CANVAS_SHADOW_KERNEL_ALPHA).coerceAtLeast(0f),
            )
        }
        val soft = ASTDProjectileVfxSoftMesh.symmetricBloomFalloff(softColumns, 8)
        return GlowMeshParts(vertices, soft)
    }

    private data class GlowStrokeSample(val pathT: Float, val gradientT: Float)

    private fun glowStrokeSamples(visibleLength: Float, headGap: Float): List<GlowStrokeSample> {
        val length = visibleLength.coerceAtLeast(0f)
        val pathStart = -length * 0.72f
        val pathEnd = -headGap
        val gradientStart = -length * 0.8f
        val gradientEnd = pathEnd
        val pathSpan = (pathEnd - pathStart).coerceAtLeast(0.0001f)
        val gradientSpan = (gradientEnd - gradientStart).coerceAtLeast(0.0001f)
        val gradientStops = listOf(0f, 0.22f, 0.62f, 0.88f, 1f)
        val samples = ArrayList<GlowStrokeSample>(gradientStops.size + 1)
        fun gradientOffsetAt(x: Float): Float = ((x - gradientStart) / gradientSpan).coerceIn(0f, 1f)
        samples += GlowStrokeSample(pathT = 0f, gradientT = gradientOffsetAt(pathStart))
        gradientStops.forEach { gradientT ->
            val x = ASTDProjectileVfxMath.lerp(gradientStart, gradientEnd, gradientT)
            if (x > pathStart && x <= pathEnd) {
                samples += GlowStrokeSample(
                    pathT = ((x - pathStart) / pathSpan).coerceIn(0f, 1f),
                    gradientT = gradientT,
                )
            }
        }
        if (samples.lastOrNull()?.pathT != 1f) {
            samples += GlowStrokeSample(pathT = 1f, gradientT = 1f)
        }
        return samples.distinctBy { (it.pathT * 10000f).toInt() }
    }

    private fun glowSampleAt(t: Float, tail: ASTDColor, head: ASTDColor, alpha: Float): Pair<ASTDColor, Float> {
        val stops = listOf(
            GradientStop(0f, darken(tail, 0.36f), 0f),
            GradientStop(0.22f, tail, alpha * 0.22f),
            GradientStop(0.62f, mix(tail, head, 0.55f), alpha * 0.65f),
            GradientStop(0.88f, head, alpha),
            GradientStop(1f, ASTDColor(1f, 0.9f, 0.98f, 1f), alpha * 0.46f),
        )
        val offset = t.coerceIn(0f, 1f)
        val left = stops.lastOrNull { it.offset <= offset } ?: stops.first()
        val right = stops.firstOrNull { it.offset >= offset } ?: stops.last()
        val ratio = ((offset - left.offset) / (right.offset - left.offset).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        return mix(left.color, right.color, ratio) to ASTDProjectileVfxMath.lerp(left.alpha, right.alpha, ratio)
    }

    private data class GradientStop(val offset: Float, val color: ASTDColor, val alpha: Float)

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
    private val handles = ArrayList<ASTDProjectileVfxBodyRenderManager.Handle>()
    private var meshes: List<ASTDProjectileVfxBodyRenderer.Mesh> = emptyList()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        meshes = ASTDProjectileVfxGlowRenderer.meshesForTests(trail, layers, context, fade.alpha())
        ensureHandles(engine, meshes.size)
        handles.zip(meshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
        return handles.size == meshes.size
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        meshes = ASTDProjectileVfxGlowRenderer.meshesForTests(trail, layers, context, fade.alpha())
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
