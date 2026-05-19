package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxGlowLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxSideWispLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDColor
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max
import kotlin.math.min

object ASTDProjectileVfxLayout {
    private const val HEAD_AUTHORED_WIDTH_BASE = 6f
    private const val EDITOR_CAPTURE_WIDTH = 1280f

    data class FlightLayout(
        val dissolve: Float,
        val beamAlpha: Float,
        val visibleLength: Float,
    )

    data class HeadVertices(
        val rearTop: Vector2f,
        val shoulderTop: Vector2f,
        val curveTop: Vector2f,
        val tip: Vector2f,
        val curveBottom: Vector2f,
        val shoulderBottom: Vector2f,
        val rearBottom: Vector2f,
    ) {
        fun asList(): List<Vector2f> = listOf(rearTop, shoulderTop, curveTop, tip, curveBottom, shoulderBottom, rearBottom)
    }

    data class HeadColors(
        val start: ASTDColor,
        val mid: ASTDColor,
        val end: ASTDColor,
        val emissive: ASTDColor,
    )

    data class BodyGradientStop(
        val offset: Float,
        val color: ASTDColor,
        val alpha: Float,
        val css: String? = null,
    )

    data class HeadFillLayout(
        val headVisible: Float,
        val vertices: HeadVertices,
        val width: Float,
        val rearX: Float,
        val alpha: Float,
        val colors: HeadColors,
    )

    fun widthBase(layer: ASTDTrailLayerSpec): Float = max(layer.startWidth * 0.075f, 3.5f)

    fun flightLayout(baseLength: Float, elapsed: Float, durationSeconds: Float, dissolveStartRatio: Float): FlightLayout {
        val dissolve = ASTDProjectileVfxMath.dissolve(elapsed, durationSeconds, dissolveStartRatio)
        return FlightLayout(
            dissolve = dissolve,
            beamAlpha = ASTDProjectileVfxMath.beamAlpha(dissolve),
            visibleLength = ASTDProjectileVfxMath.visibleLength(baseLength, dissolve),
        )
    }

    fun distanceFlightLayout(
        maxVisibleLength: Float,
        traveledDistance: Float,
        elapsed: Float,
        durationSeconds: Float,
        dissolveStartRatio: Float,
    ): FlightLayout {
        val dissolve = ASTDProjectileVfxMath.dissolve(elapsed, durationSeconds, dissolveStartRatio)
        val liveLength = traveledDistance.coerceAtLeast(0f).coerceAtMost(maxVisibleLength.coerceAtLeast(0f))
        return FlightLayout(
            dissolve = dissolve,
            beamAlpha = ASTDProjectileVfxMath.beamAlpha(dissolve),
            visibleLength = liveLength * ASTDProjectileVfxMath.lerp(1f, 0.08f, dissolve),
        )
    }

    fun viewportTailCap(trailStartWidth: Float, viewportVisibleWidth: Float): Float {
        return max(viewportVisibleWidth.coerceAtLeast(0f) * 0.46f, trailStartWidth.coerceAtLeast(0f) * 4.8f)
    }

    fun previewFlightLayout(
        trailStartWidth: Float,
        elapsed: Float,
        durationSeconds: Float,
        flightEndRatio: Float,
        dissolveStartRatio: Float,
        preDissolveFraction: Float,
        captureWidth: Float = EDITOR_CAPTURE_WIDTH,
    ): FlightLayout {
        val duration = max(durationSeconds, 1.2f)
        val clampedElapsed = elapsed.coerceIn(0f, duration)
        val flightEndSeconds = duration * flightEndRatio
        val dissolveStartSeconds = duration * dissolveStartRatio
        val flightRange = preDissolveFraction.coerceIn(0f, 1f)
        val dissolveRange = 1f - flightRange
        val dissolveDuration = max(duration - dissolveStartSeconds, 0.0001f)
        val flightSpeed = flightRange / max(flightEndSeconds, 0.0001f)
        val dissolveStartSlope = (flightSpeed * dissolveDuration) / max(dissolveRange, 0.0001f)
        val dissolveEndSlope = (flightSpeed * 0.25f * dissolveDuration) / max(dissolveRange, 0.0001f)
        val flightProgress = if (clampedElapsed <= dissolveStartSeconds) {
            flightRange * (clampedElapsed / max(flightEndSeconds, 0.0001f)).coerceIn(0f, 1f)
        } else {
            flightRange + dissolveRange * ASTDProjectileVfxMath.hermite01(
                ((clampedElapsed - dissolveStartSeconds) / dissolveDuration).coerceIn(0f, 1f),
                dissolveStartSlope,
                dissolveEndSlope,
            )
        }
        val dissolveStart = min(dissolveStartSeconds, duration - 0.2f)
        val dissolve = ASTDProjectileVfxMath.smoothstep(dissolveStart, duration, clampedElapsed)
        val startX = captureWidth * 0.14f
        val endX = captureWidth * 0.88f
        val traveledLength = max(0f, ASTDProjectileVfxMath.lerp(startX, endX, flightProgress) - startX)
        val maxTailLength = viewportTailCap(trailStartWidth, captureWidth)
        val minTailLength = max(trailStartWidth * 0.22f, 6f)
        val grownLength = min(maxTailLength, max(minTailLength * ASTDProjectileVfxMath.smoothstep(0f, 0.08f, flightProgress), traveledLength))
        val visibleLength = grownLength * ASTDProjectileVfxMath.lerp(1f, 0.08f, dissolve)
        return FlightLayout(
            dissolve = dissolve,
            beamAlpha = ASTDProjectileVfxMath.beamAlpha(dissolve),
            visibleLength = visibleLength,
        )
    }

    fun trailLocalNodes(visibleLength: Float, yOffset: Float = 0f): List<Vector2f> {
        return listOf(Vector2f(-visibleLength.coerceAtLeast(0f), yOffset), Vector2f(0f, yOffset))
    }

    fun mutableTrailLocalNodes(visibleLength: Float, yOffset: Float = 0f): ArrayList<Vector2f> {
        return ArrayList(trailLocalNodes(visibleLength, yOffset).map { Vector2f(it) })
    }

    fun glowLocalNodes(visibleLength: Float, glow: ASTDProjectileVfxGlowLayerSpec): List<Vector2f> = trailLocalNodes(visibleLength, glow.yOffset)

    fun mutableGlowLocalNodes(visibleLength: Float, glow: ASTDProjectileVfxGlowLayerSpec): ArrayList<Vector2f> {
        return mutableTrailLocalNodes(visibleLength, glow.yOffset)
    }

    fun mutableNodeList(nodes: List<Vector2f>): ArrayList<Vector2f> {
        return ArrayList(nodes.map { Vector2f(it) })
    }

    fun scalePoint(point: Vector2f, worldUnitsPerPixel: Float): Vector2f {
        val scale = worldUnitsPerPixel.coerceAtLeast(0.0001f)
        return Vector2f(point.x * scale, point.y * scale)
    }

    fun scalePoints(points: List<Vector2f>, worldUnitsPerPixel: Float): List<Vector2f> {
        return points.map { scalePoint(it, worldUnitsPerPixel) }
    }

    fun mutableScaledNodeList(nodes: List<Vector2f>, worldUnitsPerPixel: Float): ArrayList<Vector2f> {
        return ArrayList(scalePoints(nodes, worldUnitsPerPixel))
    }

    fun glowLineWidth(widthBase: Float, glow: ASTDProjectileVfxGlowLayerSpec): Float = widthBase * glow.widthScale

    fun headTrailScale(widthBase: Float): Float = max(0.01f, widthBase / HEAD_AUTHORED_WIDTH_BASE)

    fun headVertices(layer: ASTDProjectileVfxHeadLayerSpec, visible: Float, headSizeScale: Float = 1f, widthBase: Float = HEAD_AUTHORED_WIDTH_BASE): HeadVertices {
        val scale = headSizeScale * headTrailScale(widthBase)
        val length = max(1f, layer.length) * visible * scale
        val width = max(1f, layer.width) * visible * scale
        val shoulderX = -length * layer.shoulderRatio
        val rearX = -length * layer.rearRatio
        return HeadVertices(
            rearTop = Vector2f(rearX, -width * 0.2f),
            shoulderTop = Vector2f(shoulderX, -width * 0.52f),
            curveTop = Vector2f(-length * 0.12f, -width * 0.3f),
            tip = Vector2f(0f, 0f),
            curveBottom = Vector2f(-length * 0.12f, width * 0.3f),
            shoulderBottom = Vector2f(shoulderX, width * 0.52f),
            rearBottom = Vector2f(rearX, width * 0.2f),
        )
    }

    fun headColors(baseLayer: ASTDTrailLayerSpec, layer: ASTDProjectileVfxHeadLayerSpec): HeadColors {
        val edge = mix(baseLayer.startColor, baseLayer.startEmissive, 0.48f)
        val hot = mix(baseLayer.startEmissive, ASTDColor(1f, 1f, 1f, 1f), 0.72f)
        return HeadColors(
            start = multiplyRgbAlpha(baseLayer.endColor, layer.shellColorStart),
            mid = multiplyRgbAlpha(edge, layer.shellColorMid),
            end = multiplyRgbAlpha(hot, layer.shellColorEnd),
            emissive = multiplyRgbAlpha(hot, layer.shellColorEnd),
        )
    }

    fun bodyPolygon(widthBase: Float, visibleLength: Float, pulse: Float): List<Vector2f> {
        val tailWidth = max(1.0f, widthBase * 0.72f)
        val headVisible = smoothstep(0.28f, 0.82f, pulse)
        val projectileWidth = max(4.8f, widthBase * 1.72f) * headVisible
        val headLength = max(30f, widthBase * 12.4f) * headVisible
        val coreLength = max(20f, widthBase * 8.8f) * headVisible
        val shoulderX = -headLength * 0.42f
        val tailReach = max(visibleLength, 6f)
        return listOf(
            Vector2f(-tailReach * 0.86f, -tailWidth * 0.12f),
            Vector2f(-tailReach * 0.36f, -tailWidth * 0.32f),
            Vector2f(-coreLength, -projectileWidth * 0.56f),
            Vector2f(shoulderX, -projectileWidth * 0.76f),
            Vector2f(0f, 0f),
            Vector2f(shoulderX, projectileWidth * 0.76f),
            Vector2f(-coreLength, projectileWidth * 0.56f),
            Vector2f(-tailReach * 0.36f, tailWidth * 0.32f),
            Vector2f(-tailReach * 0.86f, tailWidth * 0.12f),
        )
    }

    fun bodyGradientStops(baseLayer: ASTDTrailLayerSpec, pulse: Float): List<BodyGradientStop> {
        val bodyColor = mix(baseLayer.endColor, baseLayer.startColor, 0.42f)
        return listOf(
            BodyGradientStop(offset = 0f, color = darken(baseLayer.endColor, 0.16f), alpha = 0f),
            BodyGradientStop(offset = 0.24f, color = bodyColor, alpha = 0.08f * pulse),
            BodyGradientStop(offset = 0.62f, color = mix(baseLayer.startColor, baseLayer.startEmissive, 0.22f), alpha = 0.75f * pulse),
            BodyGradientStop(offset = 0.84f, color = ASTDColor(1f, 1f, 1f, 1f), alpha = 0.92f * pulse),
            BodyGradientStop(offset = 1f, color = ASTDColor(1f, 1f, 1f, 1f), alpha = 0f, css = "rgba(255,255,255,0)"),
        )
    }

    fun headFillLayout(
        baseLayer: ASTDTrailLayerSpec,
        layer: ASTDProjectileVfxHeadLayerSpec,
        headSizeScale: Float,
        widthBase: Float,
        pulse: Float,
    ): HeadFillLayout {
        val headVisible = smoothstep(0.2f, 0.72f, pulse)
        val vertices = headVertices(layer, headVisible, headSizeScale, widthBase)
        val width = max(1f, layer.width) * headVisible * headSizeScale * headTrailScale(widthBase)
        return HeadFillLayout(
            headVisible = headVisible,
            vertices = vertices,
            width = width,
            rearX = vertices.rearTop.x,
            alpha = pulse * headVisible * layer.alphaScale,
            colors = headColors(baseLayer, layer),
        )
    }

    fun sideWispLocalPaths(layer: ASTDProjectileVfxSideWispLayerSpec, visibleLength: Float, widthBase: Float): List<List<Vector2f>> {
        return layer.offsets.map { offsetScale ->
            val offset = widthBase * offsetScale
            listOf(
                Vector2f(-visibleLength * layer.lengthStartRatio, offset),
                Vector2f(-visibleLength * layer.lengthEndRatio, offset * 0.66f),
                Vector2f(-widthBase * 2.6f, offset * 0.18f),
            )
        }
    }

    private fun multiplyRgbAlpha(base: ASTDColor, tint: ASTDColor): ASTDColor = ASTDColor(
        red = base.red * tint.red,
        green = base.green * tint.green,
        blue = base.blue * tint.blue,
        alpha = base.alpha * tint.alpha,
    )

    private fun mix(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor {
        val ratio = t.coerceIn(0f, 1f)
        return ASTDColor(
            red = a.red + (b.red - a.red) * ratio,
            green = a.green + (b.green - a.green) * ratio,
            blue = a.blue + (b.blue - a.blue) * ratio,
            alpha = a.alpha + (b.alpha - a.alpha) * ratio,
        )
    }

    private fun darken(color: ASTDColor, factor: Float): ASTDColor = ASTDColor(
        red = color.red * factor,
        green = color.green * factor,
        blue = color.blue * factor,
        alpha = color.alpha,
    )

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / max(edge1 - edge0, 0.0001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
