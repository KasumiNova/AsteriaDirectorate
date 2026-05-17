package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRibbonRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxRibbonRendererTest {
    @Test
    fun `ribbon renderer samples projectile history with preview math`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, testContext(), 6)

        assertEquals(7, points.size)
        assertEquals(testContext().historyNodes.first().location.x, points.first().base.x, 0.0001f)
        assertTrue(points.last().base.x >= testContext().historyNodes.last().location.x)
    }

    @Test
    fun `ribbon renderer alpha follows graph settings and beam alpha`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, testContext(), 6)

        assertEquals(ribbon.alphaScale * 0.8f, points.first().alpha, 0.0001f)
    }

    @Test
    fun `ribbon renderer samples runtime color gradient at start middle and end`() {
        val ribbon = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.ribbonDecorations.single().copy(
            colorGradient = ASTDTrailDecorationColorGradientSpec(
                enabled = true,
                stops = listOf(
                    ASTDTrailDecorationColorStopSpec(0f, ASTDColor(0f, 0f, 1f, 1f)),
                    ASTDTrailDecorationColorStopSpec(0.5f, ASTDColor(0f, 1f, 0f, 0.5f)),
                    ASTDTrailDecorationColorStopSpec(1f, ASTDColor(1f, 0f, 0f, 0f)),
                ),
            ),
        )

        assertEquals(0f, ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 0f).red, 0.0001f)
        assertEquals(1f, ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 0.5f).green, 0.0001f)
        assertEquals(1f, ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 1f).red, 0.0001f)
    }
}
