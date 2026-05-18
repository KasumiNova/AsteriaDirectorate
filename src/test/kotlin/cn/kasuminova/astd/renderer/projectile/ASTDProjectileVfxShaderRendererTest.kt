package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxGlowRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxHeadRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxShaderRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxShaderRendererTest {
    @Test
    fun `body shader quad carries preview analytic body and glow semantics`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val context = testContext().copy(visibleLength = 420f, beamAlpha = 0.8f)

        val quad = ASTDProjectileVfxShaderRenderer.bodyQuadForTests(trail, context)

        assertEquals(ASTDProjectileVfxShaderRenderer.Kind.Body, quad.kind)
        assertEquals(-context.visibleLength * 0.98f, quad.bounds.minX, 0.0001f)
        assertEquals(0f, quad.bounds.maxX, 0.0001f)
        assertEquals(context.visibleLength, quad.params.visibleLength, 0.0001f)
        assertEquals(0.8f, quad.params.pulse, 0.0001f)
        assertEquals(3.5f * ASTDProjectileVfxShaderRenderer.PREVIEW_BODY_WIDTH_SCALE, quad.params.widthBase, 0.0001f)
        assertEquals(ASTDProjectileVfxShaderRenderer.PREVIEW_VERTICAL_SCALE, quad.params.bodyYScale, 0.0001f)
        assertEquals(1.55f, quad.params.bodyXScale, 0.0001f)
        assertEquals(9.408001f, quad.params.bodyShadowBlur, 0.0001f)
        assertEquals(trail.layers.single().endEmissive.red, quad.params.tailEmissive.red, 0.0001f)
        assertEquals(trail.layers.single().startEmissive.blue, quad.params.headEmissive.blue, 0.0001f)
    }

    @Test
    fun `head shader quad carries continuous shell and blur parameters`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)
        val baseLayer = trail.layers.single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer) * ASTDProjectileVfxShaderRenderer.PREVIEW_BODY_WIDTH_SCALE
        val layout = ASTDProjectileVfxLayout.headFillLayout(baseLayer, layer, preset.lifecycle.projectileHeadSizeScale, widthBase, context.beamAlpha)

        val quad = ASTDProjectileVfxShaderRenderer.headQuadForTests(
            trail,
            layer,
            context,
            headSizeScale = preset.lifecycle.projectileHeadSizeScale,
        )

        assertEquals(ASTDProjectileVfxShaderRenderer.Kind.Head, quad.kind)
        assertEquals(layout.rearX, quad.bounds.minX, 0.0001f)
        assertEquals(0f, quad.bounds.maxX, 0.0001f)
        assertEquals(layout.width, quad.params.headWidth, 0.0001f)
        assertEquals(layout.alpha, quad.params.headAlpha, 0.0001f)
        assertEquals(ASTDProjectileVfxShaderRenderer.PREVIEW_VERTICAL_SCALE, quad.params.bodyYScale, 0.0001f)
        assertEquals(layer.blur, quad.params.headFilterBlur, 0.0001f)
        assertEquals(10.976f, quad.params.headShadowBlur, 0.0001f)
        assertEquals(layout.colors.mid.green, quad.params.headMid.green, 0.0001f)
    }

    @Test
    fun `glow shader quads preserve every editor glow layer instead of baking mesh bands`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 420f, beamAlpha = 0.8f)
        val expected = ASTDProjectileVfxGlowRenderer.parametersForTests(preset.trailEntities.single(), preset.glowLayers, context)

        val quads = ASTDProjectileVfxShaderRenderer.glowQuadsForTests(preset.trailEntities.single(), preset.glowLayers, context)

        assertEquals(expected.size, quads.size)
        assertTrue(quads.all { it.kind == ASTDProjectileVfxShaderRenderer.Kind.Glow })
        assertEquals(expected[0].lineWidth, quads[0].params.glowLineWidth, 0.0001f)
        assertEquals(expected[0].alpha, quads[0].params.glowAlpha, 0.0001f)
        assertEquals(expected[0].blur * context.beamAlpha, quads[0].params.glowBlur, 0.0001f)
        assertEquals(expected[0].yOffset, quads[0].params.glowYOffset, 0.0001f)
        assertEquals(expected[3].lineWidth, quads[3].params.glowLineWidth, 0.0001f)
        assertEquals(-context.visibleLength * 0.8f, quads[0].bounds.minX, 0.0001f)
        assertTrue(quads.all { it.params.bodyYScale == ASTDProjectileVfxShaderRenderer.PREVIEW_VERTICAL_SCALE })
    }
}
