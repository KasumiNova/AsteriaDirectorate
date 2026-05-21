package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxSideWispLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDColor
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

object ASTDProjectileVfxSideWispRenderer {
    fun localPathsForTests(layer: ASTDProjectileVfxSideWispLayerSpec, length: Float, widthBase: Float): List<List<Vector2f>> {
        return ASTDProjectileVfxLayout.sideWispLocalPaths(layer, length, widthBase)
    }

    fun worldPathForTests(layer: ASTDProjectileVfxSideWispLayerSpec, context: ASTDProjectileVfxRenderContext, length: Float, widthBase: Float): List<List<Vector2f>> {
        return localPathsForTests(layer, length, widthBase).map { path ->
            path.map { rotateLocal(it, context.renderFacing, context.location) }
        }
    }

    fun alpha(layer: ASTDProjectileVfxSideWispLayerSpec, context: ASTDProjectileVfxRenderContext): Float {
        return layer.alphaScale * context.beamAlpha
    }

    fun lineWidthForTests(trail: ASTDTrailEntitySpec, layer: ASTDProjectileVfxSideWispLayerSpec): Float {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        return max(0.65f, ASTDProjectileVfxLayout.widthBase(baseLayer) * layer.widthScale)
    }

    fun meshesForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxSideWispLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float = 1f,
    ): List<ASTDProjectileVfxBodyRenderer.Mesh> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val centerline = if (context.historyNodes.size >= 3 && !ASTDProjectileVfxCenterline.isEffectivelyStraight(context)) {
            ASTDProjectileVfxCenterline.build(context)
        } else {
            emptyList()
        }
        return layers.filter { it.enabled }.flatMap { layer ->
            val lineWidth = lineWidthForTests(trail, layer)
            localPathsForTests(layer, context.visibleLength, widthBase).map { path ->
                val sampledPath = if (centerline.isNotEmpty()) centerlinePath(centerline, path, widthBase) else path
                pathMesh(sampledPath, lineWidth, layer, context, alphaScale)
            }.filter { it.vertices.isNotEmpty() }
        }
    }

    private fun centerlinePath(centerline: List<ASTDProjectileVfxCenterline.Point>, localPath: List<Vector2f>, widthBase: Float): List<Vector2f> {
        if (centerline.size < 2 || localPath.size < 2) return emptyList()
        val startDistance = localPath.maxOf { kotlin.math.abs(it.x) }
        val endDistance = localPath.minOf { kotlin.math.abs(it.x) }
        val headGap = max(widthBase * 6.5f, 18f)
        val clampedEnd = max(endDistance, headGap)
        val distanceSpan = max(0f, startDistance - clampedEnd)
        if (distanceSpan <= 0.5f) return emptyList()
        val sampleCount = max(6, kotlin.math.ceil(distanceSpan / max(12f, widthBase * 2.4f)).toInt())
        val offsets = localPath
            .map { PathOffset(kotlin.math.abs(it.x), it.y) }
            .sortedByDescending { it.distance }
        return (0..sampleCount).map { index ->
            val t = index.toFloat() / sampleCount.toFloat()
            val distance = ASTDProjectileVfxMath.lerp(startDistance, clampedEnd, t)
            val offset = sampleSideWispOffset(offsets, distance)
            ASTDProjectileVfxCenterline.offsetPoint(centerline, distance, offset)
        }
    }

    private data class PathOffset(val distance: Float, val offset: Float)

    private fun sampleSideWispOffset(samples: List<PathOffset>, distance: Float): Float {
        if (samples.isEmpty()) return 0f
        if (distance >= samples.first().distance) return samples.first().offset
        for (index in 0 until samples.lastIndex) {
            val left = samples[index]
            val right = samples[index + 1]
            if (distance <= left.distance && distance >= right.distance) {
                val ratio = ((left.distance - distance) / (left.distance - right.distance).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                return ASTDProjectileVfxMath.lerp(left.offset, right.offset, ratio)
            }
        }
        return samples.last().offset
    }

    private fun pathMesh(
        path: List<Vector2f>,
        lineWidth: Float,
        layer: ASTDProjectileVfxSideWispLayerSpec,
        context: ASTDProjectileVfxRenderContext,
        alphaScale: Float,
    ): ASTDProjectileVfxBodyRenderer.Mesh {
        if (path.size < 2) {
            return ASTDProjectileVfxBodyRenderer.Mesh(
                polygon = emptyList(),
                gradientStops = emptyList(),
                vertices = emptyList(),
                triangles = emptyList(),
                blendMode = "additive",
                combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
                renderOrder = ASTDProjectileVfxBodyRenderer.RENDER_ORDER_SIDE_WISP,
            )
        }

        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val samples = pathSampleDistances(path)
        val totalDistance = samples.last().distance.coerceAtLeast(0.0001f)
        val half = lineWidth * 0.5f
        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(path.size * 2)
        for (sample in samples) {
            val t = (sample.distance / totalDistance).coerceIn(0f, 1f)
            val normal = normalForSample(samples, sample.index)
            val color = sideWispColor(layer.color, t, context.beamAlpha * alphaScale)
            val x = sample.point.x * scale
            val y = sample.point.y * scale
            vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x - normal.x * half * scale, y - normal.y * half * scale), color)
            vertices += ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(x + normal.x * half * scale, y + normal.y * half * scale), color)
        }

        return ASTDProjectileVfxBodyRenderer.Mesh(
            polygon = vertices.map { Vector2f(it.position) },
            gradientStops = emptyList(),
            vertices = vertices,
            triangles = ASTDProjectileVfxBodyRenderer.triangulateStrip(vertices),
            blendMode = "additive",
            combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
            renderOrder = ASTDProjectileVfxBodyRenderer.RENDER_ORDER_SIDE_WISP,
        )
    }

    private data class PathSample(val index: Int, val point: Vector2f, val distance: Float)

    private fun pathSampleDistances(path: List<Vector2f>): List<PathSample> {
        var distance = 0f
        val samples = ArrayList<PathSample>(path.size)
        for (index in path.indices) {
            if (index > 0) {
                distance += distance(path[index - 1], path[index])
            }
            samples += PathSample(index, path[index], distance)
        }
        return samples
    }

    private fun normalForSample(samples: List<PathSample>, index: Int): Vector2f {
        val previous = samples.getOrNull((index - 1).coerceAtLeast(0))?.point ?: samples[index].point
        val next = samples.getOrNull((index + 1).coerceAtMost(samples.lastIndex))?.point ?: samples[index].point
        val dx = next.x - previous.x
        val dy = next.y - previous.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        return Vector2f(-dy / length, dx / length)
    }

    private fun sideWispColor(color: ASTDColor, t: Float, alphaScale: Float): ASTDColor {
        val alpha = when {
            t <= 0.28f -> ASTDProjectileVfxMath.lerp(0f, 0.1f * alphaScale, t / 0.28f)
            t <= 0.7f -> ASTDProjectileVfxMath.lerp(0.1f * alphaScale, color.alpha * alphaScale, (t - 0.28f) / (0.7f - 0.28f))
            else -> ASTDProjectileVfxMath.lerp(color.alpha * alphaScale, 0f, (t - 0.7f) / 0.3f)
        }
        val rgb = if (t <= 0.7f) {
            val dark = ASTDColor(color.red * 0.5f, color.green * 0.5f, color.blue * 0.5f, color.alpha)
            mix(dark, color, (t / 0.7f).coerceIn(0f, 1f))
        } else {
            color
        }
        return rgb.copy(alpha = alpha.coerceIn(0f, 1f))
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

    private fun distance(a: Vector2f, b: Vector2f): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

class ASTDProjectileVfxSideWispRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxSideWispLayerSpec>,
) : ASTDProjectileVfxRenderLayer {
    private val handles = ArrayList<ASTDProjectileVfxBodyRenderManager.Handle>()
    private var meshes: List<ASTDProjectileVfxBodyRenderer.Mesh> = emptyList()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        meshes = ASTDProjectileVfxSideWispRenderer.meshesForTests(trail, layers, context, fade.alpha())
        ensureHandles(engine, meshes.size)
        handles.zip(meshes).forEach { (handle, mesh) -> handle.update(context.location, context.renderFacing, mesh) }
        return handles.size == meshes.size
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        meshes = ASTDProjectileVfxSideWispRenderer.meshesForTests(trail, layers, context, fade.alpha())
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
