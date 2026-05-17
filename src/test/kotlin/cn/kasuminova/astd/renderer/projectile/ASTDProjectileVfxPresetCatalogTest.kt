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
        assertEquals(80f, layer.startWidth)
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
        assertEquals(0.15f, preset.fadePolicy.fadeOutSeconds)
        assertTrue(preset.headLayers.isNotEmpty())
        assertEquals(4, preset.glowLayers.size)
        assertTrue(preset.mistLayers.isNotEmpty())
        assertTrue(preset.sideWispLayers.isNotEmpty())
        assertTrue(preset.ribbonDecorations.isNotEmpty())
        assertEquals(1.25f, preset.lifecycle.durationSeconds)
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
