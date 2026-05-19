package cn.kasuminova.astd.renderer.projectile

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ASTDProjectileVfxPresetCatalogTest {
    @Test
    fun `catalog resolves representative preset`() {
        assertNotNull(ASTDProjectileVfxPresetCatalog.preset("aod7_shot"))
    }

    @Test
    fun `aod7 preset matches editor exported trail parameters`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")
        assertNotNull(preset)
        val trail = preset.trailEntities.single()
        val layer = trail.layers.single()

        assertEquals("astd_default_trail", trail.id)
        assertEquals(420f, layer.length)
        assertEquals(ASTDProjectileVfxAnchorMode.HeadLocked, trail.anchorMode)
        assertEquals(ASTDProjectileVfxOrientationMode.ProjectileVelocity, trail.orientationMode)
        assertEquals(40f, layer.startWidth)
        assertEquals(ASTDColor(0.278431f, 0.556863f, 0.921569f, 0.92f), layer.startColor)
        assertEquals(ASTDColor(0.039216f, 0.141176f, 0.219608f, 0.06f), layer.endColor)
        assertEquals(ASTDColor(0.941176f, 0.972549f, 1f, 1f), layer.startEmissive)
        assertEquals(ASTDColor(0.039216f, 0.2f, 0.458824f, 0.16f), layer.endEmissive)
        assertEquals(4f, layer.endWidth)
        assertEquals(96f, layer.texturePixels)
        assertEquals(0.9f, layer.textureSpeed)
        assertEquals(0.84f, layer.fillStartAlpha)
        assertEquals(0.03f, layer.fillEndAlpha)
        assertEquals(0.02f, layer.fillStartFactor)
        assertEquals(0.12f, layer.fillEndFactor)
        assertTrue(layer.flowWhenPaused)
        assertTrue(layer.flickWhenPaused)
        assertEquals(0f, layer.flickMixValue)
        assertEquals(17, layer.flickerSyncCode)
        assertEquals(3, preset.samplingPolicy.smoothingPasses)
        assertEquals(420f, preset.samplingPolicy.distanceWindow)
        assertEquals(0.15f, preset.fadePolicy.fadeOutSeconds)
        assertTrue(preset.headLayers.isNotEmpty())
        assertEquals(ASTDColor(0.22f, 0.04f, 0.18f, 0.08f), preset.headLayers.single().shellColorStart)
        assertEquals(ASTDColor(0.72f, 0.94f, 1f, 0.46f), preset.headLayers.single().shellColorMid)
        assertEquals(4, preset.glowLayers.size)
        assertTrue(preset.mistLayers.isNotEmpty())
        assertEquals(ASTDColor(0.22f, 0.04f, 0.18f, 0.06f), preset.mistLayers.single().colorStart)
        assertTrue(preset.sideWispLayers.isNotEmpty())
        assertTrue(preset.ribbonDecorations.isNotEmpty())
        assertEquals(0.1f, preset.ribbonDecorations.single().thickness)
        assertEquals(4f, preset.ribbonDecorations.single().noiseScale)
        assertEquals(ASTDColor(1f, 1f, 1f, 0.92f), preset.ribbonDecorations.single().color)
        assertEquals(1.25f, preset.lifecycle.durationSeconds)
        assertEquals(1846f, preset.lifecycle.layoutReferenceWidth)
    }

    @Test
    fun `aod7 preset is loaded from frontend game export json`() {
        val exportedPath = Path.of("contents/data/config/astd_projectile_vfx_presets/aod7_shot.json")
        assertTrue(Files.exists(exportedPath), "missing frontend game export preset: $exportedPath")

        val exportedPreset = ASTDProjectileVfxPresetCatalog.loadGameExportPresetForTest(
            JSONObject(Files.readString(exportedPath)),
        )
        assertEquals(exportedPreset, ASTDProjectileVfxPresetCatalog.preset("aod7_shot"))
    }

    @Test
    fun `every configured preset id resolves`() {
        val entries = JSONObject(configPath().readText()).getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val presetId = entries.getJSONObject(i).getString("preset")
            assertNotNull(ASTDProjectileVfxPresetCatalog.preset(presetId), "missing runtime preset: $presetId")
        }
    }

    @Test
    fun `every catalog preset contains supported trail entity layer`() {
        for (id in ASTDProjectileVfxPresetCatalog.presetIds()) {
            val preset = ASTDProjectileVfxPresetCatalog.preset(id)
            assertNotNull(preset)
            assertTrue(preset.layers.isNotEmpty() || preset.trailEntities.isNotEmpty(), "preset has no runtime layers: $id")
            assertTrue(
                preset.layers.all {
                    it is ASTDProjectileVfxLayer.Trail ||
                        it is ASTDProjectileVfxLayer.Glow ||
                        it is ASTDProjectileVfxLayer.Ribbon ||
                        it is ASTDProjectileVfxLayer.HeadTrail
                },
                "preset contains unsupported runtime layer: $id",
            )
        }
    }

    @Test
    fun `catalog implementation avoids preview only fields`() {
        val text = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt"))
        listOf("timeline", "simulation", "previewCamera", "projectileVelocity", "curve", "loop").forEach { forbidden ->
            assertFalse(text.contains(forbidden), "preview-only field leaked into runtime catalog: $forbidden")
        }
    }

    private fun configPath(): Path = Path.of("contents/data/config/astd_projectile_vfx.json")
}
