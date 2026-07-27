package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f

/**
 * 网格几何的共享数据结构与三角化工具。
 *
 * bloom 弹头网格（[ASTDProjectileVfxHeadRenderer] 的 `*ForTests` 数学）产出 [Mesh]，由 bloom 管线
 * （BloomMeshComponent → [texTrailMeshTriangles] → TexTrailRenderer）烘成世界系顶点流绘制；
 * 弹头阴影（[RENDER_ORDER_HEAD_SHADOW]）垫在弹头本体（[RENDER_ORDER_HEAD]）之下。
 */
object ASTDProjectileVfxBodyRenderer {
    const val RENDER_ORDER_HEAD_SHADOW = 280
    const val RENDER_ORDER_HEAD = 300

    data class Vertex(
        val position: Vector2f,
        val color: ASTDColor,
    )

    data class Triangle(
        val a: Vertex,
        val b: Vertex,
        val c: Vertex,
    )

    data class Mesh(
        val vertices: List<Vertex>,
        val triangles: List<Triangle>,
        val renderOrder: Int = 0,
    )

    /** 上下顶点交替排列的三角条带三角化（每对上下顶点为一列，相邻列拼两个三角形）。 */
    internal fun triangulateStrip(vertices: List<Vertex>): List<Triangle> {
        if (vertices.size < 4) return emptyList()
        val triangles = ArrayList<Triangle>((vertices.size / 2 - 1) * 2)
        var index = 0
        while (index + 3 < vertices.size) {
            val a = vertices[index]
            val b = vertices[index + 1]
            val c = vertices[index + 2]
            val d = vertices[index + 3]
            triangles += Triangle(a, b, c)
            triangles += Triangle(c, b, d)
            index += 2
        }
        return triangles
    }
}
