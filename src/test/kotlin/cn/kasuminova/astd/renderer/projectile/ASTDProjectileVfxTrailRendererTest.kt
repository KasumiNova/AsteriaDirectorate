package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxTrailRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxTrailRendererTest {
    @Test
    fun `trail renderer builds local two node head locked beam`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val spec = preset.trailEntities.single()
        val nodes = ASTDProjectileVfxTrailRenderer.localNodes(spec)

        assertEquals(2, nodes.size)
    assertEquals(-420f, nodes[0].x)
        assertEquals(0f, nodes[0].y)
        assertEquals(0f, nodes[1].x)
        assertEquals(0f, nodes[1].y)
    }

    @Test
    fun `trail renderer exposes width and orientation mapping`() {
        val spec = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.trailEntities.single()
        val params = ASTDProjectileVfxTrailRenderer.parametersForTests(spec, testContext())

        assertEquals(6f, params.startWidth)
        assertEquals(0.3f, params.endWidth)
        assertEquals(ASTDProjectileVfxAnchorMode.HeadLocked, params.anchorMode)
        assertEquals(ASTDProjectileVfxOrientationMode.ProjectileVelocity, params.orientationMode)
        assertEquals(5f, params.boxUtilFacing)
    }
}
