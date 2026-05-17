package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPreset
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectileVfxRegistryRuntimeTest {
    @Test
    fun `presetFor returns runtime preset`() {
        val preset: ASTDProjectileVfxPreset? = ProjectileVfxRegistry.presetFor("astd_aod7_shot")

        assertNotNull(preset)
        assertTrue(preset.trailEntities.isNotEmpty(), "AOD-7 must use editor-exported TrailEntity preset")
        assertTrue(preset.trailEntities.all { it.layers.isNotEmpty() })
        assertTrue(preset.headLayers.all { it.enabled })
        assertTrue(preset.glowLayers.all { it.enabled })
        assertTrue(preset.mistLayers.all { it.enabled })
        assertTrue(preset.sideWispLayers.all { it.enabled })
        assertTrue(preset.ribbonDecorations.all { it.enabled })
    }

    @Test
    fun `every configured projectile resolves to runtime preset`() {
        val entries = JSONObject(configPath().readText()).getJSONArray("entries")

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val projectileSpecId = entry.getString("projectileSpecId")
            assertNotNull(
                ProjectileVfxRegistry.presetFor(projectileSpecId),
                "missing runtime preset for projectileSpecId: $projectileSpecId",
            )
        }
    }

    @Test
    fun `unconfigured projectile returns null`() {
        assertNull(ProjectileVfxRegistry.presetFor("astd_unconfigured_projectile"))
    }

    @Test
    fun `active registry source no longer references old preset handlers`() {
        val text = Files.readString(registrySourcePath())

        assertFalse(text.contains("ProjectileVfxPresets."), "registry still references ProjectileVfxPresets")
        assertFalse(text.contains("ProjectileSpawnHandler"), "registry still exposes ProjectileSpawnHandler")
    }

    private fun configPath(): Path = Path.of("contents/data/config/astd_projectile_vfx.json")

    private fun registrySourcePath(): Path =
        Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileVfxRegistry.kt")
}