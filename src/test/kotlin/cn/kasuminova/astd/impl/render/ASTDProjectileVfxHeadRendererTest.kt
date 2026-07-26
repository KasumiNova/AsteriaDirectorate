package cn.kasuminova.astd.impl.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ASTDProjectileVfxHeadRendererTest {
    @Test
    fun `head renderer creates stable pointed shell vertices`() {
        val layer = Aod7Fixture.headLayers.single()
        val vertices = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f)

        assertEquals(7, vertices.size)
        assertEquals(-layer.length * layer.rearRatio * 0.8f, vertices[0].x, 0.0001f)
        assertEquals(0f, vertices[3].x, 0.0001f)
        assertEquals(-layer.length * layer.shoulderRatio * 0.8f, vertices[1].x, 0.0001f)
    }

    @Test
    fun `head renderer vertices scale with trail width base`() {
        val layer = Aod7Fixture.headLayers.single()
        val base = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f, widthBase = 6f)
        val wider = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f, widthBase = 12f)

        assertEquals(base[0].x * 2f, wider[0].x, 0.0001f)
        assertEquals(base[1].y * 2f, wider[1].y, 0.0001f)
    }

    @Test
    fun `head renderer colors follow trail colors`() {
        val preset = Aod7Fixture
        val layer = preset.headLayers.single()
        val baseLayer = preset.trailEntities.single().layers.single()
        val redLayer = baseLayer.copy(
            startColor = ASTDColor(1f, 0f, 0f, 1f),
            startEmissive = ASTDColor(1f, 0f, 0f, 1f),
            endColor = ASTDColor(0.4f, 0f, 0f, 0.5f),
            endEmissive = ASTDColor(0.6f, 0f, 0f, 0.5f),
        )
        val defaultColors = ASTDProjectileVfxHeadRenderer.colorsForTests(baseLayer, layer)
        val redColors = ASTDProjectileVfxHeadRenderer.colorsForTests(redLayer, layer)

        assert(defaultColors.mid.blue > defaultColors.mid.red)
        assert(redColors.mid.red > 0f)
        assertEquals(0f, redColors.mid.green, 0.0001f)
        assertEquals(0f, redColors.mid.blue, 0.0001f)
        assert(redColors.end.red > redColors.end.green)
    }

    @Test
    fun `head renderer alpha follows shared beam alpha`() {
        val layer = Aod7Fixture.headLayers.single()
        assertEquals(0.8f, ASTDProjectileVfxHeadRenderer.alphaForTests(layer, testContext()), 0.0001f)
    }

    @Test
    fun `head renderer fill layout consumes preview head dimensions and vertices`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val baseLayer = trail.layers.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val expected = ASTDProjectileVfxLayout.headFillLayout(baseLayer, layer, Aod7Fixture.lifecycle.projectileHeadSizeScale, widthBase, context.beamAlpha)

        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(baseLayer, layer, context, headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale)
        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trail, listOf(layer), context, headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale).single()

        assertEquals(expected.headVisible, layout.headVisible, 0.0001f)
        assertEquals(expected.width, layout.width, 0.0001f)
        assertEquals(expected.rearX, layout.rearX, 0.0001f)
        assertEquals(expected.vertices.asList().size, mesh.polygon.size)
        assertEquals(expected.vertices.rearTop.x, mesh.polygon.first().x, 0.0001f)
        assertEquals(expected.vertices.tip.x, mesh.polygon[3].x, 0.0001f)
        assertTrue(mesh.triangles.size >= 5)
        assertEquals(expected.vertices.rearTop.x, mesh.vertices[0].position.x, 0.0001f)
        assertEquals(expected.vertices.rearBottom.x, mesh.vertices[1].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderTop.x, mesh.vertices[2].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderBottom.x, mesh.vertices[3].position.x, 0.0001f)
        assertSame(mesh.vertices[0], mesh.triangles.first().a)
        assertSame(mesh.vertices[1], mesh.triangles.first().b)
        assertSame(mesh.vertices[2], mesh.triangles.first().c)
        assertSame(mesh.vertices[3], mesh.triangles[1].c)
        assertEquals("additive", mesh.blendMode)
    }

    @Test
    fun `head renderer does not add non TypeScript geometry around filled shell`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)
        val baseLayer = trail.layers.single()
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(
            baseLayer,
            layer,
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        )

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()

        assertEquals(layout.vertices.asList().maxOf { it.y }, mesh.vertices.maxOf { it.position.y }, 0.0001f)
        assertEquals(layout.vertices.asList().minOf { it.y }, mesh.vertices.minOf { it.position.y }, 0.0001f)
    }

    @Test
    fun `head renderer samples TypeScript quadratic curves for softened corners`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()

        assertTrue(mesh.vertices.size > 8)
        assertTrue(mesh.triangles.size > 6)
        val upperCurve = mesh.vertices.drop(8).filter { it.position.y < 0f }
        assertTrue(upperCurve.size >= 4)
        assertTrue(upperCurve.map { it.position.x }.distinct().size >= 4)
        assertTrue(upperCurve.any { it.position.x > mesh.polygon[2].x && it.position.x < mesh.polygon[3].x })
    }

    @Test
    fun `head renderer keeps blur implicit in the direct fill path`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)
        val baseLayer = trail.layers.single()
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(
            baseLayer,
            layer,
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        )
        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()
        val baseHalf = layout.vertices.asList().maxOf { kotlin.math.abs(it.y) }

        assertFalse(mesh.vertices.any { kotlin.math.abs(it.position.y) > baseHalf + 0.0001f })
    }

    @Test
    fun `head renderer emits separate soft shadow mesh for TypeScript shadow blur`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)
        val baseLayer = trail.layers.single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(
            baseLayer,
            layer,
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        )
        val baseHalf = layout.vertices.asList().maxOf { kotlin.math.abs(it.y) }
        val shadowBlur = kotlin.math.max(8f, widthBase * 2.8f) * layout.headVisible

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()

        assertTrue(shadow.vertices.isNotEmpty())
        assertTrue(shadow.triangles.isNotEmpty())
        assertTrue(shadow.vertices.maxOf { kotlin.math.abs(it.position.y) } > baseHalf + shadowBlur * 0.5f)
        assertTrue(shadow.vertices.any { it.color.blue > it.color.red && it.color.alpha > 0.04f })
        assertTrue(shadow.vertices.any { it.color.alpha <= 0.0025f })
        assertTrue(shadow.vertices.maxOf { it.color.alpha } <= 0.36f)
        assertEquals("additive", shadow.blendMode)
    }

    @Test
    fun `head shadow tapers beyond tip instead of ending in a rectangular seam`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()
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
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()
        val maxX = shadow.vertices.maxOf { it.position.x }
        val capVertexCount = shadow.vertices.count { kotlin.math.abs(it.position.x - maxX) <= 0.0001f }

        assertTrue(capVertexCount < 12, "head bloom should taper through polygon rings, not a repeated column cap; cap vertices=$capVertexCount")
    }

    @Test
    fun `head shadow bloom is strong enough to cover the trail under the head`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 1f)

        val shadow = ASTDProjectileVfxHeadRenderer.shadowMeshesForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()

        assertTrue(shadow.vertices.maxOf { it.color.alpha } >= 0.22f)
    }

    @Test
    fun `head renderer does not widen runtime silhouette for bloom approximation`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 1f)
        val baseLayer = trail.layers.single()
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(
            baseLayer,
            layer,
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        )

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()
        val baseHalf = layout.vertices.asList().maxOf { kotlin.math.abs(it.y) }

        assertFalse(mesh.vertices.any { kotlin.math.abs(it.position.y) > baseHalf + 0.0001f })
    }

    @Test
    fun `head renderer samples preview shell alpha without hidden attenuation`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single().copy(alphaScale = 0.5f)
        val context = testContext().copy(beamAlpha = 0.8f)
        val baseLayer = trail.layers.single()
        val expectedLayout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(baseLayer, layer, context, headSizeScale = 1f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trail, listOf(layer), context).single()

        assertEquals(expectedLayout.colors.start.alpha * expectedLayout.alpha, mesh.vertices[0].color.alpha, 0.0001f)
        assertEquals(previewShellAlphaAt(expectedLayout, mesh.vertices[2].position.x), mesh.vertices[2].color.alpha, 0.0001f)
        assertEquals(0.98f * expectedLayout.alpha, mesh.vertices[6].color.alpha, 0.0001f)
        assertTrue(mesh.vertices[2].color.alpha > mesh.vertices[0].color.alpha)
    }

    @Test
    fun `head renderer keeps direct TypeScript layout scale`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()
        val expected = ASTDProjectileVfxLayout.headFillLayout(
            trail.layers.single(),
            layer,
            Aod7Fixture.lifecycle.projectileHeadSizeScale,
            ASTDProjectileVfxLayout.widthBase(trail.layers.single()),
            context.beamAlpha,
        )

        assertEquals(expected.vertices.rearTop.x, mesh.polygon.first().x, 0.0001f)
        assertEquals(expected.vertices.shoulderTop.y, mesh.polygon[1].y, 0.0001f)
    }

    @Test
    fun `head renderer converts TypeScript pixel geometry into world units at render boundary`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f, worldUnitsPerPixel = 0.5f)
        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
        ).single()
        val expected = ASTDProjectileVfxLayout.headFillLayout(
            trail.layers.single(),
            layer,
            Aod7Fixture.lifecycle.projectileHeadSizeScale,
            ASTDProjectileVfxLayout.widthBase(trail.layers.single()),
            context.beamAlpha,
        )

        assertEquals(expected.vertices.rearTop.x * 0.5f, mesh.polygon.first().x, 0.0001f)
        assertEquals(expected.vertices.shoulderTop.y * 0.5f, mesh.polygon[1].y, 0.0001f)
        assertEquals(expected.vertices.tip.x * 0.5f, mesh.vertices[6].position.x, 0.0001f)
        assertEquals(expected.vertices.tip.y * 0.5f, mesh.vertices[6].position.y, 0.0001f)
    }

    @Test
    fun `head renderer samples continuous preview shell gradient`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trail, listOf(layer), context).single()
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
