package cn.kasuminova.astd.impl.render

/**
 * bloom 弹头阴影网格的共享片段与能量常量（[ASTDProjectileVfxHeadRenderer] 的 headBloomMesh 在用）。
 * 常量沿用 Canvas shadowBlur 近似公式：kernel alpha × visible radius 展开外晕。
 */
internal object ASTDProjectileVfxSoftMesh {
    const val CANVAS_SHADOW_KERNEL_ALPHA: Float = 0.22f
    const val CANVAS_SHADOW_VISIBLE_RADIUS: Float = 0.72f

    /** 一段软网格：顶点 + 三角形（局部系，调用方负责世界变换）。 */
    data class MeshPart(
        val vertices: List<ASTDProjectileVfxBodyRenderer.Vertex>,
        val triangles: List<ASTDProjectileVfxBodyRenderer.Triangle>,
    )
}
