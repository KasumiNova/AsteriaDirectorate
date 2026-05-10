package cn.kasuminova.astd.renderer.projectile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxTrailEntitiesTest {
    @Test
    fun `trail entity specs are derived for supported layer types`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 16, distanceWindow = 120f)
        history.advance(org.lwjgl.util.vector.Vector2f(0f, 0f), 0f, 0f)
        history.advance(org.lwjgl.util.vector.Vector2f(10f, 0f), 0f, 0.1f)
        val layers = listOf(
            ASTDProjectileVfxLayer.Trail("trail", 8f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 1f)),
            ASTDProjectileVfxLayer.Glow("glow", 18f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(0.4f, 0.8f, 1f, 0.5f)),
            ASTDProjectileVfxLayer.Ribbon("ribbon", 4f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 0.7f), frequency = 8f, amplitude = 6f),
            ASTDProjectileVfxLayer.HeadTrail("head", 10f, ASTDProjectileVfxLengthPolicy.LifetimeWindow(0.08f), ASTDColor(0.8f, 1f, 1f, 1f)),
        )

        val specs = ASTDProjectileVfxTrailEntities.buildSpecs(layers, history.nodes())

        assertEquals(4, specs.size)
        assertTrue(specs.all { it.nodes.size >= 2 })
        assertEquals("trail", specs[0].layerId)
        assertEquals("glow", specs[1].layerId)
        assertEquals("ribbon", specs[2].layerId)
        assertEquals("head", specs[3].layerId)
    }
}
