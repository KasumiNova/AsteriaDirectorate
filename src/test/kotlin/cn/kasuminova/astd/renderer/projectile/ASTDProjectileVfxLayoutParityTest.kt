package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxLayoutParityTest {
    @Test
    fun `matches TypeScript layout vectors for AOD7`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single().layers.single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(trail)
        val flight = ASTDProjectileVfxLayout.flightLayout(trail.length, 1.0f, preset.lifecycle.durationSeconds, preset.lifecycle.dissolveStartRatio)
        val glowNodes = ASTDProjectileVfxLayout.glowLocalNodes(flight.visibleLength, preset.glowLayers[0])
        val head = ASTDProjectileVfxLayout.headVertices(preset.headLayers[0], 0.8f, preset.lifecycle.projectileHeadSizeScale, widthBase)
        val sideWisps = ASTDProjectileVfxLayout.sideWispLocalPaths(preset.sideWispLayers[0], flight.visibleLength, widthBase)

        assertEquals(0.5f, flight.dissolve, 0.0001f)
        assertEquals(0.19f, flight.beamAlpha, 0.0001f)
        assertEquals(226.8f, flight.visibleLength, 0.0001f)
        assertEquals(-226.8f, glowNodes[0].x, 0.0001f)
        assertEquals(-0.36f, glowNodes[0].y, 0.0001f)
        assertEquals(0f, glowNodes[1].x, 0.0001f)
        assertEquals(-0.36f, glowNodes[1].y, 0.0001f)
        assertEquals(-157.32f, head.rearTop.x, 0.0001f)
        assertEquals(0f, head.tip.x, 0.0001f)
        assertEquals(-145.152f, sideWisps[0][0].x, 0.0001f)
        assertEquals(-12.6f, sideWisps[0][0].y, 0.0001f)
    }

    @Test
    fun `projectile head scales from trail width base`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single().layers.single()
        val defaultWidthBase = ASTDProjectileVfxLayout.widthBase(trail)
        val widerWidthBase = ASTDProjectileVfxLayout.widthBase(trail.copy(startWidth = trail.startWidth * 2f))
        val defaultHead = ASTDProjectileVfxLayout.headVertices(preset.headLayers[0], 0.8f, preset.lifecycle.projectileHeadSizeScale, defaultWidthBase)
        val widerHead = ASTDProjectileVfxLayout.headVertices(preset.headLayers[0], 0.8f, preset.lifecycle.projectileHeadSizeScale, widerWidthBase)

        assertEquals(1f, ASTDProjectileVfxLayout.headTrailScale(defaultWidthBase), 0.0001f)
        assertEquals(defaultHead.rearTop.x * 2f, widerHead.rearTop.x, 0.0001f)
        assertEquals(defaultHead.shoulderTop.y * 2f, widerHead.shoulderTop.y, 0.0001f)
    }
}
