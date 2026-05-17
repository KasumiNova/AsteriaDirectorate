package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import cn.kasuminova.astd.renderer.projectile.runtime.rotateLocal
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxCoordinateContractTest {
    @Test
    fun `AOD7 local contract keeps head at origin and tail on negative X`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val nodes = ASTDProjectileVfxLayout.trailLocalNodes(preset.trailEntities.single().layers.single().length)

        assertEquals(2, nodes.size)
        assertTrue(nodes[0].x < 0f)
        assertEquals(-420f, nodes[0].x, 0.0001f)
        assertEquals(0f, nodes[1].x, 0.0001f)
        assertEquals(0f, nodes[1].y, 0.0001f)
    }

    @Test
    fun `facing zero puts tail on world left side`() {
        val location = Vector2f(100f, 50f)
        val tail = rotateLocal(Vector2f(-420f, 0f), 0f, location)
        val head = rotateLocal(Vector2f(0f, 0f), 0f, location)

        assertEquals(100f, head.x, 0.0001f)
        assertEquals(-320f, tail.x, 0.0001f)
        assertTrue(tail.x < head.x)
    }

    @Test
    fun `legacy export handle uses same negative X contract`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val specs = ASTDProjectileVfxTrailEntities.buildSpecs(emptyList(), preset.trailEntities, listOf(historyNode(0f), historyNode(1f)))
        val export = specs.first { it.layerId == "astd_default_trail" }

        assertEquals(-420f, export.nodes[0].x, 0.0001f)
        assertEquals(0f, export.nodes[1].x, 0.0001f)
    }

    @Test
    fun `runtime mutable node lists support BoxUtil delete cleanup`() {
        val nodes = ASTDProjectileVfxLayout.mutableTrailLocalNodes(420f)

        nodes.clear()

        assertEquals(0, nodes.size)
    }

    private fun historyNode(x: Float): ASTDProjectileHistoryNode = ASTDProjectileHistoryNode(Vector2f(x, 0f), 0f, x)
}
