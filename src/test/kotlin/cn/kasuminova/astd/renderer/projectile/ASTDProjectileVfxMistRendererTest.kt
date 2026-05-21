package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMistRenderer
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxMistRendererTest {
    @Test
    fun `mist renderer follows curved projectile history when present`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.mistLayers.single().copy(widthScale = 0f)
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            beamAlpha = 1f,
            historyNodes = curvedHistory(),
        )

        val samples = ASTDProjectileVfxMistRenderer.samplesForTests(layer, context, 40f, 10f)

        assertTrue(samples.any { it.position.y > 8f }, "mist blob bases should bend along projectile history")
        assertTrue(samples.all { it.position.x in -45f..8f }, "mist samples should remain local")
    }

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

    private fun curvedHistory(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 130f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 150f), 0f, 0f),
    )
}
