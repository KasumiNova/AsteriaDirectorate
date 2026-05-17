package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxGlowRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

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

        assertEquals(0.328703f, tail.red, 0.0001f)
        assertEquals(0.557765f, tail.green, 0.0001f)
        assertEquals(0.657705f, tail.blue, 0.0001f)
        assertEquals(0.550464f, tail.alpha, 0.0001f)
        assertEquals(0.595921f, head.red, 0.0001f)
        assertEquals(0.892353f, head.green, 0.0001f)
        assertEquals(0.947279f, head.blue, 0.0001f)
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
}
