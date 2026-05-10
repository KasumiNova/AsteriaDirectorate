package cn.kasuminova.astd.renderer.projectile

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ASTDProjectileVfxPresetCatalogTest {
    @Test
    fun `catalog resolves representative preset`() {
        assertNotNull(ASTDProjectileVfxPresetCatalog.preset("aod7_shot"))
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
            assertTrue(preset.layers.isNotEmpty(), "preset has no runtime layers: $id")
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
