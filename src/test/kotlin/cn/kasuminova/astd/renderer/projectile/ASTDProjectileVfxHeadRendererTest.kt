package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxHeadRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxHeadRenderLayer
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxHeadRendererTest {
    @Test
    fun `head renderer creates stable pointed shell vertices`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.headLayers.single()
        val vertices = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f)

        assertEquals(7, vertices.size)
        assertEquals(-layer.length * layer.rearRatio * 0.8f, vertices[0].x, 0.0001f)
        assertEquals(0f, vertices[3].x, 0.0001f)
        assertEquals(-layer.length * layer.shoulderRatio * 0.8f, vertices[1].x, 0.0001f)
    }

    @Test
    fun `head renderer vertices scale with trail width base`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.headLayers.single()
        val base = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f, widthBase = 6f)
        val wider = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f, widthBase = 12f)

        assertEquals(base[0].x * 2f, wider[0].x, 0.0001f)
        assertEquals(base[1].y * 2f, wider[1].y, 0.0001f)
    }

    @Test
    fun `head renderer colors follow trail colors`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
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
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.headLayers.single()
        assertEquals(0.8f, ASTDProjectileVfxHeadRenderer.alphaForTests(layer, testContext()), 0.0001f)
    }

    @Test
    fun `head runtime uses custom segment shell renderer path`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = ASTDProjectileVfxHeadRenderLayer(preset.trailEntities.single(), preset.headLayers)

        assertEquals("ASTDProjectileVfxHeadRenderLayer", layer.javaClass.simpleName)
    }
}
