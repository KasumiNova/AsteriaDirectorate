package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMistRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxMistRendererTest {
    @Test
    fun `mist renderer uses deterministic sample positions`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.mistLayers.single()
        val first = ASTDProjectileVfxMistRenderer.samplesForTests(layer, testContext(), 420f, 10f)
        val second = ASTDProjectileVfxMistRenderer.samplesForTests(layer, testContext(), 420f, 10f)

        assertEquals(layer.blobCount, first.size)
        assertEquals(first, second)
    }

    @Test
    fun `mist renderer alpha follows beam alpha`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.mistLayers.single()
        val sample = ASTDProjectileVfxMistRenderer.samplesForTests(layer, testContext(), 420f, 10f).first { it.alpha > 0f }

        assertEquals(true, sample.alpha > 0f)
        assertEquals(true, sample.alpha < 0.8f)
    }
}
