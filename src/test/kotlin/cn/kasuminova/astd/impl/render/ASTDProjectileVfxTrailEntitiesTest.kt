package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
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

    @Test
    fun `exported trail entity specs use local head locked beam nodes`() {
        val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 16, distanceWindow = 120f)
        history.advance(org.lwjgl.util.vector.Vector2f(0f, 0f), 0f, 0f)
        history.advance(org.lwjgl.util.vector.Vector2f(10f, 0f), 0f, 0.1f)
        val layer = ASTDTrailLayerSpec(
            width = 80f,
            color = ASTDColor(0.278431f, 0.847059f, 0.921569f, 0.92f),
            length = 420f,
            startWidth = 80f,
            endWidth = 4f,
            texturePixels = 96f,
            textureSpeed = 0.9f,
            fillStartAlpha = 0.84f,
            fillEndAlpha = 0.03f,
            fillStartFactor = 0.02f,
            fillEndFactor = 0.12f,
            flowWhenPaused = true,
            flickWhenPaused = true,
            flickMixValue = 0f,
            flickerSyncCode = 17,
        )
        val exported = ASTDTrailEntitySpec(
            layerId = "astd_default_trail",
            nodes = emptyList(),
            layerSpec = layer,
            ribbonDecorations = listOf(
                ASTDTrailRibbonDecorationSpec(
                    id = "decor",
                    frequency = 1.1f,
                    amplitude = 1.35f,
                    thickness = 0.05f,
                    alphaScale = 0.28f,
                    waveType = "noise",
                    noiseScale = 2f,
                ),
            ),
            layers = listOf(layer),
        )

        val specs = ASTDProjectileVfxTrailEntities.buildSpecs(emptyList(), listOf(exported), history.nodes())

        assertEquals(2, specs.size)
        assertEquals("astd_default_trail", specs[0].layerId)
        assertEquals(2, specs[0].nodes.size)
        assertEquals(-420f, specs[0].nodes[0].x)
        assertEquals(0f, specs[0].nodes[1].x)
        assertEquals(80f, specs[0].layers.single().startWidth)
        assertEquals(4f, specs[0].layers.single().endWidth)
        assertEquals(96f, specs[0].layers.single().texturePixels)
        assertTrue(specs[0].layers.single().flowWhenPaused)
        assertEquals("astd_default_trail.decor", specs[1].layerId)
        assertEquals(4f, specs[1].layers.single().startWidth)
        assertEquals(0.2f, specs[1].layers.single().endWidth)
    }

    @Test
    fun `exported trail entity handles use local BoxUtil beam nodes`() {
        val layer = ASTDTrailLayerSpec(
            width = 80f,
            color = ASTDColor(0.278431f, 0.847059f, 0.921569f, 0.92f),
            length = 420f,
            startWidth = 80f,
            endWidth = 4f,
        )
        val exported = ASTDTrailEntitySpec(
            layerId = "astd_default_trail",
            nodes = emptyList(),
            layerSpec = layer,
            layers = listOf(layer),
        )

        val specs = ASTDProjectileVfxTrailEntities.buildSpecs(emptyList(), listOf(exported), listOf(
            ASTDProjectileHistoryNode(org.lwjgl.util.vector.Vector2f(0f, 0f), 0f, 0f),
            ASTDProjectileHistoryNode(org.lwjgl.util.vector.Vector2f(10f, 0f), 0f, 0.1f),
        ))

        assertEquals(1, specs.size)
        assertEquals(2, specs[0].nodes.size)
        assertEquals(-420f, specs[0].nodes[0].x)
        assertEquals(0f, specs[0].nodes[0].y)
        assertEquals(0f, specs[0].nodes[1].x)
        assertEquals(0f, specs[0].nodes[1].y)
    }

    @Test
    fun `exported trail entity specs expose head locked projectile velocity semantics`() {
        val layer = ASTDTrailLayerSpec(
            width = 80f,
            color = ASTDColor(0.278431f, 0.847059f, 0.921569f, 0.92f),
            length = 420f,
        )
        val exported = ASTDTrailEntitySpec(
            layerId = "astd_default_trail",
            nodes = emptyList(),
            layerSpec = layer,
            layers = listOf(layer),
        )

        val spec = ASTDProjectileVfxTrailEntities.buildSpecs(emptyList(), listOf(exported), listOf(
            ASTDProjectileHistoryNode(org.lwjgl.util.vector.Vector2f(0f, 0f), 0f, 0f),
            ASTDProjectileHistoryNode(org.lwjgl.util.vector.Vector2f(10f, 0f), 0f, 0.1f),
        )).single()

        assertEquals(ASTDProjectileVfxAnchorMode.HeadLocked, spec.anchorMode)
        assertEquals(ASTDProjectileVfxOrientationMode.ProjectileVelocity, spec.orientationMode)
    }
}
