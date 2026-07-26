package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxCenterlineTest {
    @Test
    fun `centerline normalizes history to head first local points`() {
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            historyNodes = curvedHistoryOldToNew(),
        )

        val centerline = ASTDProjectileVfxCenterline.build(context, sampleCount = 4)

        assertEquals(5, centerline.size)
        assertEquals(0f, centerline.first().position.x, 0.0001f)
        assertEquals(0f, centerline.first().position.y, 0.0001f)
        assertTrue(centerline.last().position.x < -25f)
        assertTrue(centerline.last().position.y > 20f, "curved history should survive local-space conversion")
    }

    @Test
    fun `straight centerline remains on local negative x axis`() {
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            historyNodes = straightHistoryHeadFirst(),
        )

        val centerline = ASTDProjectileVfxCenterline.build(context, sampleCount = 4)

        assertTrue(centerline.drop(1).all { kotlin.math.abs(it.position.y) <= 0.0001f })
        assertTrue(centerline.last().position.x < -39f)
    }

    @Test
    fun `body polygon can follow curved centerline without world pretranslation`() {
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            historyNodes = curvedHistoryHeadFirst(),
        )

        val polygon = ASTDProjectileVfxCenterline.bodyPolygon(context, widthBase = 10f, pulse = 1f)

        assertTrue(polygon.any { it.y > 18f }, "body polygon should bend with the sampled trail")
        assertTrue(polygon.all { it.x in -45f..8f }, "mesh must stay projectile-local for the shared render transform")
    }

    @Test
    fun `centerline keeps preview pixel shape stable across world unit scale`() {
        val zoomedIn = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 80f,
            worldUnitsPerPixel = 0.5f,
            historyNodes = curvedHistoryHeadFirst(),
        )
        val zoomedOut = zoomedIn.copy(
            visibleLength = 40f,
            worldUnitsPerPixel = 1f,
        )

        val inPixels = ASTDProjectileVfxCenterline.build(zoomedIn, sampleCount = 4)
        val outPixels = ASTDProjectileVfxCenterline.build(zoomedOut, sampleCount = 4)

        assertEquals(outPixels.last().position.x, inPixels.last().position.x * zoomedIn.worldUnitsPerPixel, 0.0001f)
        assertEquals(outPixels.last().position.y, inPixels.last().position.y * zoomedIn.worldUnitsPerPixel, 0.0001f)
    }

    private fun curvedHistoryHeadFirst(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 130f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 150f), 0f, 0f),
    )

    private fun curvedHistoryOldToNew(): List<ASTDProjectileHistoryNode> = curvedHistoryHeadFirst().asReversed()

    private fun straightHistoryHeadFirst(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(160f, 120f), 0f, 0f),
    )
}
