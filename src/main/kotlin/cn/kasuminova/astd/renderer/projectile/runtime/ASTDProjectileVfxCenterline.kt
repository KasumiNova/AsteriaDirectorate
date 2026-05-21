package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object ASTDProjectileVfxCenterline {
    data class Point(
        val position: Vector2f,
        val distance: Float,
        val t: Float,
    )

    fun build(context: ASTDProjectileVfxRenderContext, sampleCount: Int = 28): List<Point> {
        val count = sampleCount.coerceAtLeast(1)
        val history = currentFirstHistory(context)
        val worldUnitsPerPixel = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        val worldUnitsPerEntry = estimateHistoryWorldUnitsPerEntry(history)
        return (0..count).map { index ->
            val t = index.toFloat() / count.toFloat()
            val previewDistance = context.visibleLength * t
            Point(
                position = scaleLocalToPreviewPixels(
                    worldToLocal(
                        sampleWorld(context, history, previewDistance * worldUnitsPerPixel, worldUnitsPerEntry),
                        context,
                    ),
                    worldUnitsPerPixel,
                ),
                distance = previewDistance,
                t = t,
            )
        }
    }

    fun sampleByRatio(centerline: List<Point>, ratio: Float): Point {
        if (centerline.isEmpty()) return Point(Vector2f(0f, 0f), 0f, 0f)
        val target = ratio.coerceIn(0f, 1f)
        return centerline.minBy { abs(it.t - target) }
    }

    fun offsetPoint(centerline: List<Point>, distance: Float, offset: Float): Vector2f {
        if (centerline.isEmpty()) return Vector2f(0f, 0f)
        val length = centerline.last().distance.coerceAtLeast(0.0001f)
        val ratio = (distance / length).coerceIn(0f, 1f)
        val point = sampleByRatio(centerline, ratio)
        val normal = normalAt(centerline, ratio)
        return Vector2f(point.position.x + normal.x * offset, point.position.y + normal.y * offset)
    }

    fun tangentAt(centerline: List<Point>, ratio: Float, fallback: Vector2f = Vector2f(1f, 0f)): Vector2f {
        if (centerline.size < 2) return normalized(fallback)
        val index = kotlin.math.round(ratio.coerceIn(0f, 1f) * (centerline.size - 1)).toInt()
        val previous = centerline[(index - 1).coerceAtLeast(0)].position
        val next = centerline[(index + 1).coerceAtMost(centerline.lastIndex)].position
        return normalized(Vector2f(previous.x - next.x, previous.y - next.y), fallback)
    }

    fun normalAt(centerline: List<Point>, ratio: Float): Vector2f {
        val tangent = tangentAt(centerline, ratio)
        return Vector2f(-tangent.y, tangent.x)
    }

    fun bodyPolygon(context: ASTDProjectileVfxRenderContext, widthBase: Float, pulse: Float): List<Vector2f> {
        val centerline = build(context)
        if (centerline.size < 2) return ASTDProjectileVfxLayout.bodyPolygon(widthBase, context.visibleLength, pulse)
        val top = ArrayList<Vector2f>(centerline.size)
        val bottom = ArrayList<Vector2f>(centerline.size)
        for (point in centerline) {
            val normal = normalAt(centerline, point.t)
            val halfWidth = bodyHalfWidthAt(point.t, widthBase, pulse)
            top += Vector2f(point.position.x + normal.x * halfWidth, point.position.y + normal.y * halfWidth)
            bottom += Vector2f(point.position.x - normal.x * halfWidth, point.position.y - normal.y * halfWidth)
        }
        return top.asReversed() + bottom
    }

    fun isEffectivelyStraight(context: ASTDProjectileVfxRenderContext, tolerance: Float = 0.05f): Boolean {
        val centerline = build(context, sampleCount = 8)
        if (centerline.size < 3) return true
        return centerline.all { abs(it.position.y) <= tolerance }
    }

    fun bodyHalfWidthAt(t: Float, widthBase: Float, pulse: Float): Float {
        val tailWidth = max(1.0f, widthBase * 0.72f)
        val headVisible = ASTDProjectileVfxMath.smoothstep(0.28f, 0.82f, pulse)
        val projectileWidth = max(4.8f, widthBase * 1.72f) * headVisible
        val shaped = ASTDProjectileVfxMath.smoothstep(0.05f, 0.42f, 1f - t) *
            (1f - ASTDProjectileVfxMath.smoothstep(0.92f, 1f, t) * 0.72f)
        return ASTDProjectileVfxMath.lerp(projectileWidth * 0.56f, tailWidth * 0.12f, t) * (0.52f + shaped * 0.48f)
    }

    private fun sampleWorld(
        context: ASTDProjectileVfxRenderContext,
        history: List<ASTDProjectileHistoryNode>,
        targetDistance: Float,
        pixelsPerEntry: Float,
    ): Vector2f {
        if (history.isNotEmpty()) {
            return ASTDProjectileVfxMath.sampleHistoryAtNodes(history, targetDistance.coerceAtLeast(0f), pixelsPerEntry)
        }
        val radians = Math.toRadians(context.renderFacing.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return Vector2f(
            context.location.x - targetDistance * c,
            context.location.y - targetDistance * s,
        )
    }

    private fun currentFirstHistory(context: ASTDProjectileVfxRenderContext): List<ASTDProjectileHistoryNode> {
        if (context.historyNodes.isEmpty()) return emptyList()
        val first = context.historyNodes.first().location
        if (close(first, context.location)) return context.historyNodes
        val last = context.historyNodes.last().location
        if (close(last, context.location)) return context.historyNodes.asReversed()
        return emptyList()
    }

    private fun estimateHistoryWorldUnitsPerEntry(history: List<ASTDProjectileHistoryNode>): Float {
        if (history.size < 2) return 4f
        var total = 0f
        val sampleN = minOf(history.size - 1, 8)
        for (index in 0 until sampleN) {
            total += distance(history[index].location, history[index + 1].location)
        }
        return max(0.5f, total / sampleN)
    }

    private fun worldToLocal(world: Vector2f, context: ASTDProjectileVfxRenderContext): Vector2f {
        val dx = world.x - context.location.x
        val dy = world.y - context.location.y
        val radians = Math.toRadians(context.renderFacing.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return Vector2f(dx * c + dy * s, -dx * s + dy * c)
    }

    private fun scaleLocalToPreviewPixels(localWorld: Vector2f, worldUnitsPerPixel: Float): Vector2f {
        val scale = worldUnitsPerPixel.coerceAtLeast(0.0001f)
        return Vector2f(localWorld.x / scale, localWorld.y / scale)
    }

    private fun close(a: Vector2f, b: Vector2f): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy <= 0.01f
    }

    private fun distance(a: Vector2f, b: Vector2f): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun normalized(vector: Vector2f, fallback: Vector2f = Vector2f(1f, 0f)): Vector2f {
        val length = sqrt(vector.x * vector.x + vector.y * vector.y)
        if (length <= 0.0001f) return normalized(fallback, Vector2f(1f, 0f))
        return Vector2f(vector.x / length, vector.y / length)
    }
}
