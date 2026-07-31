package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * bloom 弹头网格数学（[ASTDProjectileVfxHeadRenderer]）自检。
 *
 * 夹具数值内联自旧 aod7_shot preset（新管线 aod7 DSL 按同一组值 1:1 书写）：
 * 弹头产出 [ASTDProjectileVfxBodyRenderer.Mesh]（顶点 + 三角形 + renderOrder），
 * 由 bloom 管线烘成世界系顶点流绘制（BloomMeshComponent → TexTrailRenderer）。
 */
class ASTDProjectileVfxHeadRendererTest {

    private val trailLayer = ASTDTrailLayerSpec(
        startWidth = 40f,
        length = 420f,
        startColor = ASTDColor(0.278431f, 0.556863f, 0.921569f, 0.92f),
        startEmissive = ASTDColor(0.941176f, 0.972549f, 1f, 1f),
        endColor = ASTDColor(0.039216f, 0.141176f, 0.219608f, 0.06f),
    )

    private val headLayer = ASTDProjectileVfxHeadLayerSpec(
        length = 138f,
        width = 24f,
        shoulderRatio = 0.5f,
        rearRatio = 0.95f,
        shellColorStart = ASTDColor(0.22f, 0.04f, 0.18f, 0.08f),
        shellColorMid = ASTDColor(0.72f, 0.94f, 1f, 0.46f),
        shellColorEnd = ASTDColor(1f, 1f, 1f, 0.98f),
        blur = 0.35f,
        alphaScale = 1f,
    )

    /** 旧 aod7 preset 的 projectileHeadSizeScale。 */
    private val headSizeScale = 1.5f

    private fun testContext(): ASTDProjectileVfxRenderContext = ASTDProjectileVfxRenderContext(
        location = Vector2f(10f, 20f),
        renderFacing = 5f,
        beamAlpha = 0.8f,
    )

    @Test
    fun `head renderer creates stable pointed shell vertices`() {
        val vertices = ASTDProjectileVfxHeadRenderer.verticesForTests(headLayer, 0.8f)

        assertEquals(7, vertices.size)
        assertEquals(-headLayer.length * headLayer.rearRatio * 0.8f, vertices[0].x, 0.0001f)
        assertEquals(0f, vertices[3].x, 0.0001f)
        assertEquals(-headLayer.length * headLayer.shoulderRatio * 0.8f, vertices[1].x, 0.0001f)
    }

    @Test
    fun `head renderer vertices scale with trail width base`() {
        val base = ASTDProjectileVfxHeadRenderer.verticesForTests(headLayer, 0.8f, widthBase = 6f)
        val wider = ASTDProjectileVfxHeadRenderer.verticesForTests(headLayer, 0.8f, widthBase = 12f)

        assertEquals(base[0].x * 2f, wider[0].x, 0.0001f)
        assertEquals(base[1].y * 2f, wider[1].y, 0.0001f)
    }

    @Test
    fun `head renderer colors follow trail colors`() {
        val redLayer = trailLayer.copy(
            startColor = ASTDColor(1f, 0f, 0f, 1f),
            startEmissive = ASTDColor(1f, 0f, 0f, 1f),
            endColor = ASTDColor(0.4f, 0f, 0f, 0.5f),
        )
        val defaultColors = ASTDProjectileVfxHeadRenderer.colorsForTests(trailLayer, headLayer)
        val redColors = ASTDProjectileVfxHeadRenderer.colorsForTests(redLayer, headLayer)

        assert(defaultColors.mid.blue > defaultColors.mid.red)
        assert(redColors.mid.red > 0f)
        assertEquals(0f, redColors.mid.green, 0.0001f)
        assertEquals(0f, redColors.mid.blue, 0.0001f)
        assert(redColors.end.red > redColors.end.green)
    }

    @Test
    fun `head renderer alpha follows shared beam alpha`() {
        assertEquals(0.8f, ASTDProjectileVfxHeadRenderer.alphaForTests(headLayer, testContext()), 0.0001f)
    }

    @Test
    fun `head renderer fill layout consumes preview head dimensions and vertices`() {
        val context = testContext().copy(beamAlpha = 0.8f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(trailLayer)
        val expected = ASTDProjectileVfxLayout.headFillLayout(trailLayer, headLayer, headSizeScale, widthBase, context.beamAlpha)

        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(trailLayer, headLayer, context, headSizeScale = headSizeScale)
        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()

        assertEquals(expected.headVisible, layout.headVisible, 0.0001f)
        assertEquals(expected.width, layout.width, 0.0001f)
        assertEquals(expected.rearX, layout.rearX, 0.0001f)
        assertEquals(7, expected.vertices.asList().size)
        // 顶点条带前 8 个 = 7 轮廓点的上下对（rearTop/rearBottom/shoulderTop/shoulderBottom/curveTop/curveBottom/tip/tip）
        assertEquals(expected.vertices.rearTop.x, mesh.vertices[0].position.x, 0.0001f)
        assertEquals(expected.vertices.rearBottom.x, mesh.vertices[1].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderTop.x, mesh.vertices[2].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderBottom.x, mesh.vertices[3].position.x, 0.0001f)
        assertEquals(expected.vertices.tip.x, mesh.vertices[6].position.x, 0.0001f)
        assertTrue(mesh.triangles.size >= 5)
        assertSame(mesh.vertices[0], mesh.triangles.first().a)
        assertSame(mesh.vertices[1], mesh.triangles.first().b)
        assertSame(mesh.vertices[2], mesh.triangles.first().c)
        assertSame(mesh.vertices[3], mesh.triangles[1].c)
        assertEquals(ASTDProjectileVfxBodyRenderer.RENDER_ORDER_HEAD, mesh.renderOrder)
    }

    @Test
    fun `head renderer does not add non TypeScript geometry around filled shell`() {
        val context = testContext().copy(beamAlpha = 0.8f)
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(trailLayer, headLayer, context, headSizeScale = headSizeScale)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()

        assertEquals(layout.vertices.asList().maxOf { it.y }, mesh.vertices.maxOf { it.position.y }, 0.0001f)
        assertEquals(layout.vertices.asList().minOf { it.y }, mesh.vertices.minOf { it.position.y }, 0.0001f)
    }

    @Test
    fun `head renderer samples TypeScript quadratic curves for softened corners`() {
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()

        assertTrue(mesh.vertices.size > 8)
        assertTrue(mesh.triangles.size > 6)
        val upperCurve = mesh.vertices.drop(8).filter { it.position.y < 0f }
        assertTrue(upperCurve.size >= 4)
        assertTrue(upperCurve.map { it.position.x }.distinct().size >= 4)
        // 曲线采样点落在 curveTop 与 tip 之间
        assertTrue(upperCurve.any { it.position.x > mesh.vertices[4].position.x && it.position.x < mesh.vertices[6].position.x })
    }

    @Test
    fun `head renderer keeps blur implicit in the direct fill path`() {
        val context = testContext().copy(beamAlpha = 0.8f)
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(trailLayer, headLayer, context, headSizeScale = headSizeScale)
        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()
        val baseHalf = layout.vertices.asList().maxOf { kotlin.math.abs(it.y) }

        assertFalse(mesh.vertices.any { kotlin.math.abs(it.position.y) > baseHalf + 0.0001f })
    }

    @Test
    fun `head renderer emits separate soft shadow mesh for TypeScript shadow blur`() {
        val context = testContext().copy(beamAlpha = 0.8f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(trailLayer)
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(trailLayer, headLayer, context, headSizeScale = headSizeScale)
        val baseHalf = layout.vertices.asList().maxOf { kotlin.math.abs(it.y) }
        val shadowBlur = kotlin.math.max(8f, widthBase * 2.8f) * layout.headVisible

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()

        assertTrue(shadow.vertices.isNotEmpty())
        assertTrue(shadow.triangles.isNotEmpty())
        assertTrue(shadow.vertices.maxOf { kotlin.math.abs(it.position.y) } > baseHalf + shadowBlur * 0.5f)
        assertTrue(shadow.vertices.any { it.color.blue > it.color.red && it.color.alpha > 0.04f })
        assertTrue(shadow.vertices.any { it.color.alpha <= 0.0025f })
        assertTrue(shadow.vertices.maxOf { it.color.alpha } <= 0.36f)
        assertEquals(ASTDProjectileVfxBodyRenderer.RENDER_ORDER_HEAD_SHADOW, shadow.renderOrder)
    }

    @Test
    fun `head shadow tapers beyond tip instead of ending in a rectangular seam`() {
        val context = testContext().copy(beamAlpha = 0.8f)

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()
        val maxX = shadow.vertices.maxOf { it.position.x }
        val capVertices = shadow.vertices.filter { it.position.x > 0f }
        val farCap = shadow.vertices.filter { kotlin.math.abs(it.position.x - maxX) <= 0.0001f }

        assertTrue(maxX > 0f, "head bloom should extend past the tip as a soft cap")
        assertTrue(capVertices.isNotEmpty())
        assertTrue(farCap.all { kotlin.math.abs(it.position.y) <= 0.0001f })
        assertTrue(farCap.all { it.color.alpha <= 0.0025f })
    }

    @Test
    fun `head shadow does not emit a repeated vertical cap column`() {
        val context = testContext().copy(beamAlpha = 0.8f)

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()
        val maxX = shadow.vertices.maxOf { it.position.x }
        val capVertexCount = shadow.vertices.count { kotlin.math.abs(it.position.x - maxX) <= 0.0001f }

        assertTrue(capVertexCount < 12, "head bloom should taper through polygon rings, not a repeated column cap; cap vertices=$capVertexCount")
    }

    @Test
    fun `head shadow bloom is strong enough to cover the trail under the head`() {
        val context = testContext().copy(beamAlpha = 1f)

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()

        assertTrue(shadow.vertices.maxOf { it.color.alpha } >= 0.22f)
    }

    @Test
    fun `head renderer does not widen runtime silhouette for bloom approximation`() {
        val context = testContext().copy(beamAlpha = 1f)
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(trailLayer, headLayer, context, headSizeScale = headSizeScale)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()
        val baseHalf = layout.vertices.asList().maxOf { kotlin.math.abs(it.y) }

        assertFalse(mesh.vertices.any { kotlin.math.abs(it.position.y) > baseHalf + 0.0001f })
    }

    @Test
    fun `head renderer samples preview shell alpha without hidden attenuation`() {
        val layer = headLayer.copy(alphaScale = 0.5f)
        val context = testContext().copy(beamAlpha = 0.8f)
        val expectedLayout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(trailLayer, layer, context, headSizeScale = 1f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(layer), context).single()

        assertEquals(expectedLayout.colors.start.alpha * expectedLayout.alpha, mesh.vertices[0].color.alpha, 0.0001f)
        assertEquals(previewShellAlphaAt(expectedLayout, mesh.vertices[2].position.x), mesh.vertices[2].color.alpha, 0.0001f)
        assertEquals(0.98f * expectedLayout.alpha, mesh.vertices[6].color.alpha, 0.0001f)
        assertTrue(mesh.vertices[2].color.alpha > mesh.vertices[0].color.alpha)
    }

    @Test
    fun `head renderer keeps direct TypeScript layout scale`() {
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()
        val expected = ASTDProjectileVfxLayout.headFillLayout(
            trailLayer,
            headLayer,
            headSizeScale,
            ASTDProjectileVfxLayout.widthBase(trailLayer),
            context.beamAlpha,
        )

        assertEquals(expected.vertices.rearTop.x, mesh.vertices[0].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderTop.y, mesh.vertices[2].position.y, 0.0001f)
    }

    @Test
    fun `head renderer converts TypeScript pixel geometry into world units at render boundary`() {
        val context = testContext().copy(beamAlpha = 0.8f, worldUnitsPerPixel = 0.5f)
        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context, headSizeScale = headSizeScale).single()
        val expected = ASTDProjectileVfxLayout.headFillLayout(
            trailLayer,
            headLayer,
            headSizeScale,
            ASTDProjectileVfxLayout.widthBase(trailLayer),
            context.beamAlpha,
        )

        assertEquals(expected.vertices.rearTop.x * 0.5f, mesh.vertices[0].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderTop.y * 0.5f, mesh.vertices[2].position.y, 0.0001f)
        assertEquals(expected.vertices.tip.x * 0.5f, mesh.vertices[6].position.x, 0.0001f)
        assertEquals(expected.vertices.tip.y * 0.5f, mesh.vertices[6].position.y, 0.0001f)
    }

    @Test
    fun `head renderer samples continuous preview shell gradient`() {
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trailLayer, listOf(headLayer), context).single()
        val rear = mesh.vertices[0].color
        val shoulder = mesh.vertices[2].color
        val tip = mesh.vertices[6].color

        assertTrue(shoulder.blue > rear.blue)
        assertTrue(tip.red > shoulder.red)
        assertTrue(tip.green > shoulder.green)
        assertTrue(tip.blue > shoulder.blue)
    }

    private fun previewShellAlphaAt(layout: ASTDProjectileVfxLayout.HeadFillLayout, x: Float): Float {
        val progress = ((x - layout.rearX) / (0f - layout.rearX).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val stopAlpha = when {
            progress <= 0.36f -> {
                val t = progress / 0.36f
                layout.colors.start.alpha + (layout.colors.mid.alpha - layout.colors.start.alpha) * t
            }
            progress <= 0.74f -> {
                val t = (progress - 0.36f) / (0.74f - 0.36f)
                layout.colors.mid.alpha + (0.9f - layout.colors.mid.alpha) * t
            }
            else -> {
                val t = (progress - 0.74f) / (1f - 0.74f)
                0.9f + (0.98f - 0.9f) * t
            }
        }
        return stopAlpha * layout.alpha
    }
}
