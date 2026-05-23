package cn.kasuminova.astd.renderer.projectile.reload

import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxFadePolicy
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPreset
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetCatalog
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxSamplingPolicy
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ASTDProjectileVfxHotReloadManagerTest {
    @AfterTest
    fun resetCatalog() {
        ASTDProjectileVfxPresetCatalog.resetForTests()
    }

    @Test
    fun `reload replaces preset for future catalog lookups`() {
        val original = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val replacement = preset("aod7_shot", width = 99f)

        val count = ASTDProjectileVfxPresetCatalog.reloadForDev(source(2, replacement))

        assertEquals(1, count)
        assertSame(replacement, ASTDProjectileVfxPresetCatalog.preset("aod7_shot"))
        assertFalse(original === ASTDProjectileVfxPresetCatalog.preset("aod7_shot"))
        assertEquals(2, ASTDProjectileVfxPresetCatalog.version())
    }

    @Test
    fun `reload validates duplicate source ids`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ASTDProjectileVfxHotReloadManager.reload(
                source = object : ASTDProjectileVfxHotReloadSource {
                    override fun version(): Long = 3
                    override fun presets(): List<ASTDProjectileVfxPreset> = listOf(preset("dup", 8f), preset("dup", 9f))
                },
            )
        }

        assertEquals("Projectile VFX hot reload source contains duplicate preset ids: dup", error.message)
    }

    @Test
    fun `reload validates missing component trail anchors`() {
        val invalid = preset("invalid", 8f).copy(
            components = listOf(ASTDProjectileVfxComponentSpec.Body("body", trailId = "missing")),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ASTDProjectileVfxPresetCatalog.reloadForDev(source(4, invalid))
        }

        assertEquals("Projectile VFX component references missing trailId=missing", error.message)
    }

    @Test
    fun `catalog source no longer has json fallback`() {
        val text = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt"),
        )

        assertFalse(text.contains("loadJSON"))
        assertFalse(text.contains("ASTDProjectileVfxPresetJson"))
        assertFalse(text.contains("gameExportPresetOrFallback"))
    }

    private fun source(version: Long, vararg presets: ASTDProjectileVfxPreset) = object : ASTDProjectileVfxHotReloadSource {
        override fun version(): Long = version
        override fun presets(): List<ASTDProjectileVfxPreset> = presets.toList()
    }

    private fun preset(id: String, width: Float): ASTDProjectileVfxPreset = ASTDProjectileVfxPreset(
        id = id,
        components = listOf(
            ASTDProjectileVfxComponentSpec.Trail(
                id = "trail",
                layer = ASTDTrailLayerSpec(width = width, color = ASTDColor(1f, 1f, 1f, 1f), length = 120f),
            ),
            ASTDProjectileVfxComponentSpec.Body("body", trailId = "trail"),
        ),
        samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 1, 120f),
        fadePolicy = ASTDProjectileVfxFadePolicy(0f, 0.2f, 0.1f, 0.2f),
    )
}
