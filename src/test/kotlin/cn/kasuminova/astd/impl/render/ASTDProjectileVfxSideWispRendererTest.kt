package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import kotlin.math.abs
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxSideWispRendererTest {
    @Test
    fun `side wisp renderer follows curved projectile history with sampled path`() {
        val preset = Aod7Fixture
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            beamAlpha = 1f,
            historyNodes = curvedHistory(),
        )

        val mesh = ASTDProjectileVfxSideWispRenderer.meshesForTests(
            preset.trailEntities.single(),
            preset.sideWispLayers,
            context,
        ).first()

        assertTrue(mesh.vertices.size > 6, "curved side wisp should use sampled centerline path instead of three-node chord")
        assertTrue(mesh.vertices.maxOf { it.position.y } - mesh.vertices.minOf { it.position.y } > 2f, "side wisp should bend along projectile history")
        assertTrue(mesh.vertices.all { it.position.x in -45f..8f }, "side wisp mesh should remain local")
    }

    @Test
    fun `side wisp renderer creates one local three-node path per offset`() {
        val preset = Aod7Fixture
        val paths = ASTDProjectileVfxSideWispRenderer.localPathsForTests(preset.sideWispLayers.single(), 420f, 10f)

        assertEquals(4, paths.size)
        assertTrue(paths.all { it.size == 3 })
        assertEquals(-420f * 0.64f, paths[0][0].x)
        assertEquals(-21f, paths[0][0].y)
    }

    @Test
    fun `side wisp renderer rotates offsets with projectile transform`() {
        val preset = Aod7Fixture
        val world = ASTDProjectileVfxSideWispRenderer.worldPathForTests(preset.sideWispLayers.single(), testContext().copy(renderFacing = 90f), 420f, 10f).first()

        assertTrue(abs(world[0].x - 31f) < 0.01f)
        assertTrue(abs(world[0].y - (20f - 268.8f)) < 0.01f)
    }

    @Test
    fun `side wisp mesh fades out before the projectile head`() {
        val preset = Aod7Fixture
        val mesh = ASTDProjectileVfxSideWispRenderer.meshesForTests(
            preset.trailEntities.single(),
            preset.sideWispLayers,
            testContext().copy(visibleLength = 420f, beamAlpha = 1f),
        ).first()
        val headEnd = mesh.vertices.maxBy { it.position.x }

        assertEquals(ASTDProjectileVfxBodyRenderer.RENDER_ORDER_SIDE_WISP, mesh.renderOrder)
        assertTrue(headEnd.position.x < 0f)
        assertTrue(headEnd.color.alpha <= 0.0001f)
        assertTrue(mesh.vertices.any { it.color.alpha > 0.1f })
    }





    private fun curvedHistory(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 130f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 150f), 0f, 0f),
    )
}
