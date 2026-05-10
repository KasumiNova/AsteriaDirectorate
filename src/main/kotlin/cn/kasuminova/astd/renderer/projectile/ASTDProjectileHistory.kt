package cn.kasuminova.astd.renderer.projectile

import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt

data class ASTDProjectileHistoryNode(val location: Vector2f, val facing: Float, val elapsed: Float)

class ASTDProjectileHistory(
    private val minDistancePerNode: Float,
    private val maxHistoryNodes: Int,
    private val distanceWindow: Float,
) {
    private val nodes = ArrayList<ASTDProjectileHistoryNode>()

    fun advance(location: Vector2f, facing: Float, elapsed: Float) {
        val last = nodes.lastOrNull()
        if (last != null && distance(last.location, location) < minDistancePerNode) return

        nodes += ASTDProjectileHistoryNode(Vector2f(location), facing, elapsed)
        trimByMaxNodeCount()
        trimByDistanceWindow(distanceWindow)
    }

    fun nodes(): List<ASTDProjectileHistoryNode> {
        return nodes.map { node ->
            ASTDProjectileHistoryNode(Vector2f(node.location), node.facing, node.elapsed)
        }
    }

    fun trimByDistanceWindow(maxDistance: Float) {
        if (nodes.size <= 1) return

        val latest = nodes.last().location
        while (nodes.size > 1 && distance(nodes.first().location, latest) > maxDistance) {
            nodes.removeAt(0)
        }
    }

    fun clear() {
        nodes.clear()
    }

    private fun trimByMaxNodeCount() {
        while (nodes.size > maxHistoryNodes.coerceAtLeast(1)) {
            nodes.removeAt(0)
        }
    }

    private fun distance(a: Vector2f, b: Vector2f): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
