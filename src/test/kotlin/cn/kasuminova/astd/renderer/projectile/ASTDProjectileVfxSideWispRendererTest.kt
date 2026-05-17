package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxSideWispRenderer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxSideWispRendererTest {
    @Test
    fun `side wisp renderer creates one local three-node path per offset`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val paths = ASTDProjectileVfxSideWispRenderer.localPathsForTests(preset.sideWispLayers.single(), 420f, 10f)

        assertEquals(4, paths.size)
        assertTrue(paths.all { it.size == 3 })
        assertEquals(-420f * 0.64f, paths[0][0].x)
        assertEquals(-21f, paths[0][0].y)
    }

    @Test
    fun `side wisp renderer rotates offsets with projectile transform`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val world = ASTDProjectileVfxSideWispRenderer.worldPathForTests(preset.sideWispLayers.single(), testContext().copy(renderFacing = 90f), 420f, 10f).first()

        assertTrue(abs(world[0].x - 31f) < 0.01f)
        assertTrue(abs(world[0].y - (20f - 268.8f)) < 0.01f)
    }
}
