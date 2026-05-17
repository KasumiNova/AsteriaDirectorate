package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxGlowLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxSideWispLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDColor
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

object ASTDProjectileVfxLayout {
    private const val HEAD_AUTHORED_WIDTH_BASE = 6f

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

    fun widthBase(layer: ASTDTrailLayerSpec): Float = max(layer.startWidth * 0.075f, 3.5f)

    fun flightLayout(baseLength: Float, elapsed: Float, durationSeconds: Float, dissolveStartRatio: Float): FlightLayout {
        val dissolve = ASTDProjectileVfxMath.dissolve(elapsed, durationSeconds, dissolveStartRatio)
        return FlightLayout(
            dissolve = dissolve,
            beamAlpha = ASTDProjectileVfxMath.beamAlpha(dissolve),
            visibleLength = ASTDProjectileVfxMath.visibleLength(baseLength, dissolve),
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
}
