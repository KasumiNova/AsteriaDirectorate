package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

/** 弹体轨迹历史采样器：按最小间距采样位置/朝向，供拖尾与网格节点回溯几何。 */
class ASTDProjectileHistory(
    private val minDistancePerNode: Float,
    private val maxHistoryNodes: Int,
    private val distanceWindow: Float,
) {
    private val nodes = ArrayList<ASTDProjectileHistoryNode>()

    fun advance(
        location: Vector2f,
        facing: Float,
        elapsed: Float,
        retainDistance: Float? = null,
        retainNodeCount: Int? = null,
    ) {
        val last = nodes.lastOrNull()
        if (last != null && distance(last.location, location) < minDistancePerNode) return

        nodes += ASTDProjectileHistoryNode(Vector2f(location), facing, elapsed)
        trimByMaxNodeCount(retainNodeCount?.let { maxOf(maxHistoryNodes, it) } ?: maxHistoryNodes)
        trimByDistanceWindow(retainDistance?.let { maxOf(distanceWindow, it) } ?: distanceWindow)
    }

    fun nodes(): List<ASTDProjectileHistoryNode> {
        return nodes.map { node ->
            ASTDProjectileHistoryNode(Vector2f(node.location), node.facing, node.elapsed)
        }
    }

    fun trimByDistanceWindow(maxDistance: Float) {
        if (nodes.size <= 1) return

        val window = maxDistance.coerceAtLeast(0f)
        var retained = 0f
        for (index in nodes.lastIndex downTo 1) {
            val newer = nodes[index]
            val older = nodes[index - 1]
            val segment = distance(older.location, newer.location)
            if (retained + segment > window) {
                val fromNewer = (window - retained).coerceIn(0f, segment)
                val ratioFromOlder = if (segment <= 0.0001f) 1f else 1f - fromNewer / segment
                val boundary = interpolate(older, newer, ratioFromOlder)
                repeat(index) { nodes.removeAt(0) }
                nodes.add(0, boundary)
                return
            }
            retained += segment
        }
    }

    fun clear() {
        nodes.clear()
    }

    private fun trimByMaxNodeCount(limit: Int) {
        while (nodes.size > limit.coerceAtLeast(1)) {
            nodes.removeAt(0)
        }
    }

    private fun interpolate(a: ASTDProjectileHistoryNode, b: ASTDProjectileHistoryNode, t: Float): ASTDProjectileHistoryNode {
        val ratio = t.coerceIn(0f, 1f)
        return ASTDProjectileHistoryNode(
            location = Vector2f(
                a.location.x + (b.location.x - a.location.x) * ratio,
                a.location.y + (b.location.y - a.location.y) * ratio,
            ),
            facing = if (ratio < 0.5f) a.facing else b.facing,
            elapsed = a.elapsed + (b.elapsed - a.elapsed) * ratio,
        )
    }

    private fun distance(a: Vector2f, b: Vector2f): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
