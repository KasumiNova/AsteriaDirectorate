package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import org.lwjgl.util.vector.Vector2f

internal object ASTDProjectileVfxSoftMesh {
    const val CANVAS_SHADOW_KERNEL_ALPHA: Float = 0.22f
    const val CANVAS_SHADOW_VISIBLE_RADIUS: Float = 0.72f

    data class Column(
        val x: Float,
        val centerY: Float,
        val innerHalf: Float,
        val outerHalf: Float,
        val color: ASTDColor,
        val alpha: Float,
    )

    data class MeshPart(
        val vertices: List<ASTDProjectileVfxBodyRenderer.Vertex>,
        val triangles: List<ASTDProjectileVfxBodyRenderer.Triangle>,
    )

    fun symmetricOuterFalloff(columns: List<Column>, steps: Int): MeshPart {
        if (columns.size < 2 || steps <= 0) return MeshPart(emptyList(), emptyList())

        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(columns.size * steps * 8)
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>((columns.size - 1) * steps * 8)
        appendSymmetricBands(columns, steps, 1f, 1f, vertices, triangles)
        return MeshPart(vertices, triangles)
    }

    fun symmetricBloomFalloff(columns: List<Column>, steps: Int): MeshPart {
        if (columns.size < 2 || steps <= 0) return MeshPart(emptyList(), emptyList())

        val vertices = ArrayList<ASTDProjectileVfxBodyRenderer.Vertex>(columns.size * steps * 8 * BLOOM_PASSES.size)
        val triangles = ArrayList<ASTDProjectileVfxBodyRenderer.Triangle>((columns.size - 1) * steps * 8 * BLOOM_PASSES.size)
        BLOOM_PASSES.forEach { pass ->
            appendSymmetricBands(columns, steps, pass.radiusScale, pass.alphaScale, vertices, triangles)
        }
        return MeshPart(vertices, triangles)
    }

    private data class BloomPass(val radiusScale: Float, val alphaScale: Float)

    private val BLOOM_PASSES = listOf(
        BloomPass(radiusScale = 1.0f, alphaScale = 0.72f),
        BloomPass(radiusScale = 1.55f, alphaScale = 0.48f),
        BloomPass(radiusScale = 2.15f, alphaScale = 0.34f),
    )

    private fun appendSymmetricBands(
        columns: List<Column>,
        steps: Int,
        radiusScale: Float,
        alphaScale: Float,
        vertices: MutableList<ASTDProjectileVfxBodyRenderer.Vertex>,
        triangles: MutableList<ASTDProjectileVfxBodyRenderer.Triangle>,
    ) {
        for (step in 0 until steps) {
            val innerRatio = step.toFloat() / steps.toFloat()
            val outerRatio = (step + 1).toFloat() / steps.toFloat()
            for (index in 0 until columns.lastIndex) {
                appendSideQuad(columns[index], columns[index + 1], innerRatio, outerRatio, -1f, radiusScale, alphaScale, vertices, triangles)
                appendSideQuad(columns[index], columns[index + 1], innerRatio, outerRatio, 1f, radiusScale, alphaScale, vertices, triangles)
            }
        }
    }

    private fun appendSideQuad(
        left: Column,
        right: Column,
        innerRatio: Float,
        outerRatio: Float,
        side: Float,
        radiusScale: Float,
        alphaScale: Float,
        vertices: MutableList<ASTDProjectileVfxBodyRenderer.Vertex>,
        triangles: MutableList<ASTDProjectileVfxBodyRenderer.Triangle>,
    ) {
        val offset = vertices.size
        vertices += vertex(left, innerRatio, side, radiusScale, alphaScale)
        vertices += vertex(right, innerRatio, side, radiusScale, alphaScale)
        vertices += vertex(left, outerRatio, side, radiusScale, alphaScale)
        vertices += vertex(right, outerRatio, side, radiusScale, alphaScale)

        val a = vertices[offset]
        val b = vertices[offset + 1]
        val c = vertices[offset + 2]
        val d = vertices[offset + 3]
        triangles += ASTDProjectileVfxBodyRenderer.Triangle(a, b, c)
        triangles += ASTDProjectileVfxBodyRenderer.Triangle(c, b, d)
    }

    private fun vertex(
        column: Column,
        ratio: Float,
        side: Float,
        radiusScale: Float,
        alphaScale: Float,
    ): ASTDProjectileVfxBodyRenderer.Vertex {
        val t = ratio.coerceIn(0f, 1f)
        val outerHalf = column.innerHalf + (column.outerHalf - column.innerHalf) * radiusScale
        val half = ASTDProjectileVfxMath.lerp(column.innerHalf, outerHalf, t)
        val alpha = column.alpha * alphaScale * falloff(t)
        return ASTDProjectileVfxBodyRenderer.Vertex(
            Vector2f(column.x, column.centerY + side * half),
            column.color.copy(alpha = alpha.coerceIn(0f, 1f)),
        )
    }

    private fun falloff(t: Float): Float {
        val inverse = 1f - ASTDProjectileVfxMath.smoothstep(0f, 1f, t)
        return inverse * inverse
    }
}
