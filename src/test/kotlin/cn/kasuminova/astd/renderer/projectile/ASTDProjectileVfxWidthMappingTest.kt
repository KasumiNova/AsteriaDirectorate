package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxGlowRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxWidthMappingTest {
    @Test
    fun `AOD7 runtime maps raw preview width to visual width base`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)

        assertEquals(3.5f, widthBase, 0.0001f)
        assertEquals(18.9f, ASTDProjectileVfxLayout.glowLineWidth(widthBase, preset.glowLayers[0]), 0.0001f)
        assertEquals(0.7f, widthBase * preset.sideWispLayers[0].widthScale, 0.0001f)
    }

    @Test
    fun `trail and glow renderer expose visual widths without raw inflation`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext()
        val trailParams = cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxTrailRenderer.parametersForTests(preset.trailEntities.single(), context)
        val glowParams = ASTDProjectileVfxGlowRenderer.parametersForTests(preset.trailEntities.single(), preset.glowLayers, context)
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val sideWispWidth = widthBase * preset.sideWispLayers.single().widthScale

        assertEquals(3.5f, trailParams.startWidth, 0.0001f)
        assertEquals(0.3f, trailParams.endWidth, 0.0001f)
        assertEquals(18.9f, glowParams[0].widthScale * widthBase, 0.0001f)
        assertEquals(0.7f, sideWispWidth, 0.0001f)
        assertTrue(glowParams[0].widthScale * widthBase < 80f)
    }
}
