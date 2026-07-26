package cn.kasuminova.astd.impl.render

import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxLayoutParityTest {
    @Test
    fun `matches TypeScript layout vectors for AOD7`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(trail)
        val flight = ASTDProjectileVfxLayout.flightLayout(trail.length, 1.0f, Aod7Fixture.lifecycle.durationSeconds, Aod7Fixture.lifecycle.dissolveStartRatio)
        val glowNodes = ASTDProjectileVfxLayout.glowLocalNodes(flight.visibleLength, preset.glowLayers[0])
        val head = ASTDProjectileVfxLayout.headVertices(preset.headLayers[0], 0.8f, Aod7Fixture.lifecycle.projectileHeadSizeScale, widthBase)
        val sideWisps = ASTDProjectileVfxLayout.sideWispLocalPaths(preset.sideWispLayers[0], flight.visibleLength, widthBase)

        assertEquals(0.5f, flight.dissolve, 0.0001f)
        assertEquals(0.19f, flight.beamAlpha, 0.0001f)
        assertEquals(226.8f, flight.visibleLength, 0.0001f)
        assertEquals(-226.8f, glowNodes[0].x, 0.0001f)
        assertEquals(-0.36f, glowNodes[0].y, 0.0001f)
        assertEquals(0f, glowNodes[1].x, 0.0001f)
        assertEquals(-0.36f, glowNodes[1].y, 0.0001f)
        assertEquals(-91.77f, head.rearTop.x, 0.0001f)
        assertEquals(0f, head.tip.x, 0.0001f)
        assertEquals(-145.152f, sideWisps[0][0].x, 0.0001f)
        assertEquals(-7.35f, sideWisps[0][0].y, 0.0001f)
    }

    @Test
    fun `matches TypeScript preview flight track vectors for AOD7 capture`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()

        val captureFrame = ASTDProjectileVfxLayout.previewFlightLayout(
            trailStartWidth = trail.startWidth,
            elapsed = 0.42f,
            durationSeconds = Aod7Fixture.lifecycle.durationSeconds,
            flightEndRatio = Aod7Fixture.lifecycle.flightEndRatio,
            dissolveStartRatio = Aod7Fixture.lifecycle.dissolveStartRatio,
            preDissolveFraction = Aod7Fixture.lifecycle.preDissolveFraction,
        )
        val dissolveFrame = ASTDProjectileVfxLayout.previewFlightLayout(
            trailStartWidth = trail.startWidth,
            elapsed = 1.0f,
            durationSeconds = Aod7Fixture.lifecycle.durationSeconds,
            flightEndRatio = Aod7Fixture.lifecycle.flightEndRatio,
            dissolveStartRatio = Aod7Fixture.lifecycle.dissolveStartRatio,
            preDissolveFraction = Aod7Fixture.lifecycle.preDissolveFraction,
        )

        assertEquals(0f, captureFrame.dissolve, 0.0001f)
        assertEquals(1f, captureFrame.beamAlpha, 0.0001f)
        assertEquals(434.95424f, captureFrame.visibleLength, 0.0001f)
        assertEquals(0.5f, dissolveFrame.dissolve, 0.0001f)
        assertEquals(0.19f, dissolveFrame.beamAlpha, 0.0001f)
        assertEquals(317.952f, dissolveFrame.visibleLength, 0.0001f)
    }

    @Test
    fun `preview flight track mirrors TypeScript curved automation trajectory`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()

        val track = ASTDProjectileVfxLayout.previewFlightTrack(
            trailStartWidth = trail.startWidth,
            elapsed = 0.3004f,
            durationSeconds = Aod7Fixture.lifecycle.durationSeconds,
            flightEndRatio = Aod7Fixture.lifecycle.flightEndRatio,
            dissolveStartRatio = Aod7Fixture.lifecycle.dissolveStartRatio,
            preDissolveFraction = Aod7Fixture.lifecycle.preDissolveFraction,
            captureWidth = Aod7Fixture.lifecycle.layoutReferenceWidth,
            captureHeight = 600f,
            curveAmount = 96f,
            curveFrequency = 0.8f,
            curved = true,
        )

        assertEquals(448.65854f, track.headOffset.x, 0.0001f)
        assertEquals(86.93850f, track.headOffset.y, 0.0001f)
        assertEquals(0f, track.tailOffset.x, 0.0001f)
        assertEquals(269.19513f, track.centerOffset.x, 0.0001f)
        assertEquals(448.65854f, track.visibleLength, 0.0001f)
        assertEquals(0.32843733f, track.flightProgress, 0.0001f)
    }

    @Test
    fun `AOD7 reference capture uses exported layout reference width`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()

        val captureFrame = ASTDProjectileVfxLayout.previewFlightLayout(
            trailStartWidth = trail.startWidth,
            elapsed = 0.42f,
            durationSeconds = Aod7Fixture.lifecycle.durationSeconds,
            flightEndRatio = Aod7Fixture.lifecycle.flightEndRatio,
            dissolveStartRatio = Aod7Fixture.lifecycle.dissolveStartRatio,
            preDissolveFraction = Aod7Fixture.lifecycle.preDissolveFraction,
            captureWidth = Aod7Fixture.lifecycle.layoutReferenceWidth,
        )

        assertEquals(0f, captureFrame.dissolve, 0.0001f)
        assertEquals(1f, captureFrame.beamAlpha, 0.0001f)
        assertEquals(627.2856f, captureFrame.visibleLength, 0.0001f)
    }

    @Test
    fun `distance flight layout grows trail one to one until configured cap`() {
        val viewportCap = ASTDProjectileVfxLayout.viewportTailCap(
            trailStartWidth = 40f,
            viewportVisibleWidth = 1280f,
        )
        val growing = ASTDProjectileVfxLayout.distanceFlightLayout(
            maxVisibleLength = viewportCap,
            traveledDistance = 180f,
            elapsed = 0.3f,
            durationSeconds = 1.25f,
            dissolveStartRatio = 0.6f,
        )
        val capped = ASTDProjectileVfxLayout.distanceFlightLayout(
            maxVisibleLength = viewportCap,
            traveledDistance = 720f,
            elapsed = 0.3f,
            durationSeconds = 1.25f,
            dissolveStartRatio = 0.6f,
        )

        assertEquals(0f, growing.dissolve, 0.0001f)
        assertEquals(1f, growing.beamAlpha, 0.0001f)
        assertEquals(180f, growing.visibleLength, 0.0001f)
        assertEquals(588.8f, capped.visibleLength, 0.0001f)
    }

    @Test
    fun `viewport tail cap mirrors TypeScript computeFlightTrack max tail length`() {
        val aod7Default = ASTDProjectileVfxLayout.viewportTailCap(
            trailStartWidth = 40f,
            viewportVisibleWidth = 1280f,
        )
        val automationViewport = ASTDProjectileVfxLayout.viewportTailCap(
            trailStartWidth = 40f,
            viewportVisibleWidth = 600f * 16f / 9f,
        )
        val wideTrail = ASTDProjectileVfxLayout.viewportTailCap(
            trailStartWidth = 220f,
            viewportVisibleWidth = 640f,
        )

        assertEquals(588.8f, aod7Default, 0.0001f)
        assertEquals(490.6667f, automationViewport, 0.0001f)
        assertEquals(1056f, wideTrail, 0.0001f)
    }

    @Test
    fun `projectile head scales from trail width base`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()
        val defaultWidthBase = ASTDProjectileVfxLayout.widthBase(trail)
        val widerWidthBase = ASTDProjectileVfxLayout.widthBase(trail.copy(startWidth = trail.startWidth * 2f))
        val defaultHead = ASTDProjectileVfxLayout.headVertices(preset.headLayers[0], 0.8f, Aod7Fixture.lifecycle.projectileHeadSizeScale, defaultWidthBase)
        val widerHead = ASTDProjectileVfxLayout.headVertices(preset.headLayers[0], 0.8f, Aod7Fixture.lifecycle.projectileHeadSizeScale, widerWidthBase)

        assertEquals(0.5833333f, ASTDProjectileVfxLayout.headTrailScale(defaultWidthBase), 0.0001f)
        val widthBaseRatio = widerWidthBase / defaultWidthBase
        assertEquals(defaultHead.rearTop.x * widthBaseRatio, widerHead.rearTop.x, 0.0001f)
        assertEquals(defaultHead.shoulderTop.y * widthBaseRatio, widerHead.shoulderTop.y, 0.0001f)
    }

    @Test
    fun `body polygon matches TypeScript preview contract`() {
        val polygon = ASTDProjectileVfxLayout.bodyPolygon(widthBase = 6f, visibleLength = 420f, pulse = 1f)

        assertEquals(-361.2f, polygon[0].x, 0.0001f)
        assertEquals(-0.5184f, polygon[0].y, 0.0001f)
        assertEquals(-151.2f, polygon[1].x, 0.0001f)
        assertEquals(-1.3824f, polygon[1].y, 0.0001f)
        assertEquals(-52.8f, polygon[2].x, 0.0001f)
        assertEquals(-5.7792f, polygon[2].y, 0.0001f)
        assertEquals(-31.248f, polygon[3].x, 0.0001f)
        assertEquals(-7.8432f, polygon[3].y, 0.0001f)
        assertEquals(0f, polygon[4].x, 0.0001f)
        assertEquals(0f, polygon[4].y, 0.0001f)
        assertEquals(-31.248f, polygon[5].x, 0.0001f)
        assertEquals(7.8432f, polygon[5].y, 0.0001f)
        assertEquals(-52.8f, polygon[6].x, 0.0001f)
        assertEquals(5.7792f, polygon[6].y, 0.0001f)
        assertEquals(-151.2f, polygon[7].x, 0.0001f)
        assertEquals(1.3824f, polygon[7].y, 0.0001f)
        assertEquals(-361.2f, polygon[8].x, 0.0001f)
        assertEquals(0.5184f, polygon[8].y, 0.0001f)
    }

    @Test
    fun `head fill layout matches TypeScript preview dimensions`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()
        val layout = ASTDProjectileVfxLayout.headFillLayout(
            baseLayer = trail,
            layer = preset.headLayers.single(),
            headSizeScale = Aod7Fixture.lifecycle.projectileHeadSizeScale,
            widthBase = ASTDProjectileVfxLayout.widthBase(trail),
            pulse = 1f,
        )

        assertEquals(1f, layout.headVisible, 0.0001f)
        assertEquals(21f, layout.width, 0.0001f)
        assertEquals(-114.7125f, layout.rearX, 0.0001f)
        assertEquals(1f, layout.alpha, 0.0001f)
        assertEquals(-114.7125f, layout.vertices.rearTop.x, 0.0001f)
        assertEquals(-4.2f, layout.vertices.rearTop.y, 0.0001f)
        assertEquals(0.00862752f, layout.colors.start.red, 0.0001f)
        assertEquals(0.7110087f, layout.colors.mid.green, 0.0001f)
        assertEquals(0.98f, layout.colors.end.alpha, 0.0001f)
    }

    @Test
    fun `body gradient stops match TypeScript preview contract`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()
        val stops = ASTDProjectileVfxLayout.bodyGradientStops(trail, pulse = 0.5f)

        assertEquals(0f, stops[0].offset, 0.0001f)
        assertEquals(0f, stops[0].alpha, 0.0001f)
        assertEquals(0.00627456f, stops[0].color.red, 0.0001f)
        assertEquals(0.24f, stops[1].offset, 0.0001f)
        assertEquals(0.04f, stops[1].alpha, 0.0001f)
        assertEquals(0.1396863f, stops[1].color.red, 0.0001f)
        assertEquals(0.62f, stops[2].offset, 0.0001f)
        assertEquals(0.375f, stops[2].alpha, 0.0001f)
        assertEquals(0.84f, stops[3].offset, 0.0001f)
        assertEquals(0.46f, stops[3].alpha, 0.0001f)
        assertEquals(1f, stops[4].offset, 0.0001f)
        assertEquals(0f, stops[4].alpha, 0.0001f)
        assertEquals("rgba(255,255,255,0)", stops[4].css)
    }

    @Test
    fun `body and head shrink with TypeScript smoothstep thresholds`() {
        val preset = Aod7Fixture
        val trail = preset.trailEntities.single().layers.single()
        val headLayer = preset.headLayers.single()
        val hiddenBody = ASTDProjectileVfxLayout.bodyPolygon(widthBase = 6f, visibleLength = 420f, pulse = 0.28f)
        val visibleBody = ASTDProjectileVfxLayout.bodyPolygon(widthBase = 6f, visibleLength = 420f, pulse = 0.82f)
        val hiddenHead = ASTDProjectileVfxLayout.headFillLayout(trail, headLayer, 1.5f, 6f, pulse = 0.2f)
        val visibleHead = ASTDProjectileVfxLayout.headFillLayout(trail, headLayer, 1.5f, 6f, pulse = 0.72f)

        assertEquals(0f, hiddenBody[2].x, 0.0001f)
        assertEquals(0f, hiddenBody[3].y, 0.0001f)
        assertEquals(-52.8f, visibleBody[2].x, 0.0001f)
        assertEquals(-7.8432f, visibleBody[3].y, 0.0001f)
        assertEquals(0f, hiddenHead.headVisible, 0.0001f)
        assertEquals(0f, hiddenHead.width, 0.0001f)
        assertEquals(1f, visibleHead.headVisible, 0.0001f)
        assertEquals(36f, visibleHead.width, 0.0001f)
        assertEquals(0.72f, visibleHead.alpha, 0.0001f)
    }
}
