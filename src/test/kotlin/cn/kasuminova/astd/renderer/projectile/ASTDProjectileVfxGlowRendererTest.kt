package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxGlowRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxShaderRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxGlowRendererTest {
    @Test
    fun `aod7 glow layer parameters mirror preview graph`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val params = ASTDProjectileVfxGlowRenderer.parametersForTests(preset.trailEntities.single(), preset.glowLayers, testContext())

        assertEquals(4, params.size)
        assertEquals(5.4f, params[0].widthScale)
        assertEquals(0.18f * 0.8f, params[0].alpha)
        assertEquals(34f, params[0].blur)
        assertEquals(-0.36f, params[0].yOffset)
        assertEquals(0.62f, params[3].widthScale)
        assertEquals(0.82f * 0.8f, params[3].alpha)
    }

    @Test
    fun `glow renderer samples preview color mix for tail and head`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val glow = preset.glowLayers.first()
        val (tail, head) = ASTDProjectileVfxGlowRenderer.colors(baseLayer, glow)

        assertEquals(0.315244f, tail.red, 0.0001f)
        assertEquals(0.467125f, tail.green, 0.0001f)
        assertEquals(0.662281f, tail.blue, 0.0001f)
        assertEquals(0.550464f, tail.alpha, 0.0001f)
        assertEquals(0.570039f, head.red, 0.0001f)
        assertEquals(0.739765f, head.green, 0.0001f)
        assertEquals(0.956079f, head.blue, 0.0001f)
    }

    @Test
    fun `glow renderer prefers explicit gradient stops when present`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val glow = preset.glowLayers.first().copy(
            gradientStops = listOf(
                ASTDColorStopSpec(0f, ASTDColor(0.1f, 0.2f, 0.3f, 0.4f)),
                ASTDColorStopSpec(1f, ASTDColor(0.9f, 0.8f, 0.7f, 0.6f)),
            ),
        )

        val (tail, head) = ASTDProjectileVfxGlowRenderer.colors(baseLayer, glow)

        assertEquals(0.1f, tail.red, 0.0001f)
        assertEquals(0.9f, head.red, 0.0001f)
    }

    @Test
    fun `glow renderer builds preview blur stroke mesh with soft outer bands`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()

        assertTrue(mesh.vertices.size >= 16)
        assertTrue(mesh.triangles.size >= 12)
        assertTrue(mesh.vertices.minOf { it.position.x } <= -context.visibleLength * 0.72f + 0.001f)
        assertTrue(mesh.vertices.maxOf { it.position.x } < 0f)
        assertTrue(mesh.vertices.maxOf { it.position.y } - mesh.vertices.minOf { it.position.y } > 34f)
        assertTrue(mesh.vertices.any { it.color.alpha in 0.005f..0.08f })
    }

    @Test
    fun `glow renderer keeps outer bloom visible to parity mask without creating bright core`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()

        val outerBand = mesh.vertices.takeLast(10)
        assertTrue(outerBand.any { it.color.blue > 0.2f && it.color.green > 0.16f })
        assertTrue(outerBand.all { it.color.alpha <= 0.0015f })
    }

    @Test
    fun `glow renderer keeps mesh scale close to preview stroke footprint`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()

        assertEquals(1.2f, mesh.xScale, 0.0001f)
        assertEquals(ASTDProjectileVfxShaderRenderer.PREVIEW_VERTICAL_SCALE, mesh.yScale, 0.0001f)
    }
}
