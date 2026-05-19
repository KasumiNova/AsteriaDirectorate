package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxGlowRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDProjectileVfxGlowRendererTest {
    @Test
    fun `aod7 glow layer parameters mirror preview graph`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val params = ASTDProjectileVfxGlowRenderer.parametersForTests(preset.trailEntities.single(), preset.glowLayers, testContext())

        assertEquals(4, params.size)
        assertEquals(5.4f, params[0].widthScale)
        assertEquals(0.18f * 0.8f, params[0].alpha)
        assertEquals(34f, params[0].blur)
        assertEquals(-0.36f, params[0].yOffset)
        assertEquals(-120f * 0.72f, params[0].nodes[0].x, 0.0001f)
        assertEquals(0.62f, params[3].widthScale)
        assertEquals(0.82f * 0.8f, params[3].alpha)
    }

    @Test
    fun `glow renderer samples preview color mix for tail and head`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val glow = preset.glowLayers.first()
        val (tail, head) = ASTDProjectileVfxGlowRenderer.colors(baseLayer, glow)

        assertEquals(0.315244f, tail.red, 0.0001f)
        assertEquals(0.467125f, tail.green, 0.0001f)
        assertEquals(0.662281f, tail.blue, 0.0001f)
        assertEquals(0.550464f, tail.alpha, 0.0001f)
        assertEquals(0.570039f, head.red, 0.0001f)
        assertEquals(0.739765f, head.green, 0.0001f)
        assertEquals(0.956079f, head.blue, 0.0001f)
    }

    @Test
    fun `glow renderer prefers explicit gradient stops when present`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val glow = preset.glowLayers.first().copy(
            gradientStops = listOf(
                ASTDColorStopSpec(0f, ASTDColor(0.1f, 0.2f, 0.3f, 0.4f)),
                ASTDColorStopSpec(1f, ASTDColor(0.9f, 0.8f, 0.7f, 0.6f)),
            ),
        )

        val (tail, head) = ASTDProjectileVfxGlowRenderer.colors(baseLayer, glow)

        assertEquals(0.1f, tail.red, 0.0001f)
        assertEquals(0.9f, head.red, 0.0001f)
    }

    @Test
    fun `BoxUtil glow layer keeps emissive head fill instead of inheriting body taper fade`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val glow = preset.glowLayers.first()

        val layer = ASTDProjectileVfxGlowRenderer.layerSpec(baseLayer, glow)

        assertEquals(0.22f, layer.fillStartAlpha, 0.0001f)
        assertEquals(1f, layer.fillEndAlpha, 0.0001f)
        assertEquals(0f, layer.fillStartFactor, 0.0001f)
        assertEquals(0f, layer.fillEndFactor, 0.0001f)
        assertTrue(layer.endEmissive.alpha > baseLayer.endEmissive.alpha)
    }

    @Test
    fun `BoxUtil glow layer does not reinterpret preview blur as trail jitter`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val glow = preset.glowLayers.first().copy(blur = 34f)

        val layer = ASTDProjectileVfxGlowRenderer.layerSpec(baseLayer, glow)

        assertEquals(baseLayer.jitterPower, layer.jitterPower, 0.0001f)
    }

    @Test
    fun `BoxUtil glow power does not reinterpret preview blur radius as intensity`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!

        assertEquals(1f, ASTDProjectileVfxGlowRenderer.glowPower(preset.glowLayers.first()), 0.0001f)
        assertEquals(1f, ASTDProjectileVfxGlowRenderer.glowPower(preset.glowLayers.last()), 0.0001f)
    }

    @Test
    fun `BoxUtil glow core is split into preview gradient stop segments`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val glow = preset.glowLayers.first()
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)

        val segments = ASTDProjectileVfxGlowRenderer.boxUtilSegmentsForTests(baseLayer, widthBase, glow, testContext())

        assertEquals(4, segments.size)
        assertEquals(0f, segments[0].startT, 0.0001f)
        assertEquals(0.22f, segments[0].endT, 0.0001f)
        assertEquals(0.62f, segments[2].startT, 0.0001f)
        assertEquals(0.88f, segments[2].endT, 0.0001f)
        assertEquals(lineWidth, segments[0].width, 0.0001f)
        assertEquals(0f, segments[0].startColor.alpha, 0.0001f)
        assertTrue(segments[2].endColor.alpha > segments[1].endColor.alpha)
        assertTrue(segments.all { it.glowPower > 0f })
    }

    @Test
    fun `glow renderer builds preview blur stroke mesh with soft outer bands`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()

        assertTrue(mesh.vertices.size >= 16)
        assertTrue(mesh.triangles.size >= 12)
        assertTrue(mesh.vertices.minOf { it.position.x } <= -context.visibleLength * 0.72f + 0.001f)
        assertTrue(mesh.vertices.maxOf { it.position.x } < 0f)
        assertTrue(mesh.vertices.maxOf { it.position.y } - mesh.vertices.minOf { it.position.y } > 34f)
        assertTrue(mesh.vertices.any { it.color.alpha in 0.005f..0.08f })
    }

    @Test
    fun `glow renderer samples preview stroke gradient continuously`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()
        val sample = mesh.vertices[4].color

        assertEquals(0.455381f, sample.red, 0.0001f)
        assertEquals(0.617077f, sample.green, 0.0001f)
        assertEquals(0.823870f, sample.blue, 0.0001f)
        assertEquals(0.0936f, sample.alpha, 0.0001f)
    }

    @Test
    fun `glow renderer keeps outer bloom visible to parity mask without creating bright core`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()

        val outerBand = mesh.vertices.takeLast(10)
        assertTrue(outerBand.any { it.color.blue > 0.2f && it.color.green > 0.16f })
        assertTrue(outerBand.all { it.color.alpha <= 0.009f })
    }

    @Test
    fun `runtime glow shadow mesh excludes direct stroke core`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxGlowRenderer.shadowMeshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()

        assertTrue(mesh.vertices.isNotEmpty())
        assertTrue(mesh.triangles.isNotEmpty())
        assertTrue(mesh.vertices.all { it.color.alpha <= 0.03f })
        assertTrue(mesh.vertices.any { it.color.alpha <= 0.004f })
    }

    @Test
    fun `glow shadow mesh uses multi pass bloom falloff for soft visible halo`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val glow = preset.glowLayers.first()
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)

        val mesh = ASTDProjectileVfxGlowRenderer.shadowMeshesForTests(
            preset.trailEntities.single(),
            listOf(glow),
            context,
        ).single()
        val baseHalf = lineWidth * 0.5f + kotlin.math.abs(glow.yOffset)
        val nearHalo = mesh.vertices.filter { kotlin.math.abs(it.position.y) <= baseHalf + glow.blur * 0.75f }
        val farHalo = mesh.vertices.filter { kotlin.math.abs(it.position.y) >= baseHalf + glow.blur * 0.75f }

        assertTrue(farHalo.isNotEmpty(), "glow bloom should extend beyond the Canvas shadow kernel support")
        assertTrue(farHalo.any { it.color.alpha in 0.001f..0.009f }, "outer halo should remain visible instead of being quantized away")
        assertTrue(nearHalo.maxOf { it.color.alpha } > farHalo.maxOf { it.color.alpha }, "falloff should soften outward")
    }

    @Test
    fun `glow shadow mesh treats Canvas blur as visible falloff radius not full geometry support`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val glow = preset.glowLayers.first()
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)

        val mesh = ASTDProjectileVfxGlowRenderer.shadowMeshesForTests(
            preset.trailEntities.single(),
            listOf(glow),
            context,
        ).single()
        val allowedHalf = lineWidth * 0.5f + glow.blur * 1.6f + kotlin.math.abs(glow.yOffset)

        assertTrue(mesh.vertices.maxOf { kotlin.math.abs(it.position.y) } <= allowedHalf)
    }

    @Test
    fun `glow blur envelope fades continuously to transparent outer edge`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, preset.glowLayers.first())
        val headGap = kotlin.math.max(14f, lineWidth * 0.55f)
        val edgeColumn = mesh.vertices.filter { kotlin.math.abs(it.position.x + headGap) < 0.0001f }
        val distinctAlphas = edgeColumn.map { (it.color.alpha * 100000f).toInt() }.distinct()

        assertTrue(edgeColumn.any { it.color.alpha <= 0.0001f })
        assertTrue(edgeColumn.any { it.color.alpha > 0.04f })
        assertTrue(edgeColumn.filter { it.color.alpha > 0.0001f }.all { it.color.alpha <= 0.07f })
        assertTrue(distinctAlphas.size >= 6)
    }

    @Test
    fun `glow renderer keeps direct TypeScript layout scale`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, preset.glowLayers.first())
        val headGap = kotlin.math.max(14f, lineWidth * 0.55f)

        assertEquals(-context.visibleLength * 0.72f, mesh.vertices.first().position.x, 0.0001f)
        assertEquals(-headGap, mesh.vertices[8].position.x, 0.0001f)
    }

    @Test
    fun `glow stroke samples TypeScript gradient space instead of path t`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, preset.glowLayers.first())
        val headGap = kotlin.math.max(14f, lineWidth * 0.55f)
        val gradientOffsetAtPathStart = ((-context.visibleLength * 0.72f) - (-context.visibleLength * 0.8f)) /
            ((-headGap) - (-context.visibleLength * 0.8f))
        val expectedAlpha = preset.glowLayers.first().alphaScale * context.beamAlpha * 0.22f *
            gradientOffsetAtPathStart / 0.22f

        assertTrue(gradientOffsetAtPathStart > 0f)
        assertEquals(expectedAlpha, mesh.vertices.first().color.alpha, 0.0001f)
    }

    @Test
    fun `glow renderer converts TypeScript pixel stroke into world units at render boundary`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 180f, beamAlpha = 0.8f, worldUnitsPerPixel = 0.5f)
        val mesh = ASTDProjectileVfxGlowRenderer.meshesForTests(
            preset.trailEntities.single(),
            listOf(preset.glowLayers.first()),
            context,
        ).single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, preset.glowLayers.first())
        val headGap = kotlin.math.max(14f, lineWidth * 0.55f)

        assertEquals(-context.visibleLength * 0.72f * 0.5f, mesh.vertices.first().position.x, 0.0001f)
        assertEquals((-headGap) * 0.5f, mesh.vertices[8].position.x, 0.0001f)
        assertEquals((-lineWidth * 0.5f + preset.glowLayers.first().yOffset) * 0.5f, mesh.vertices.first().position.y, 0.0001f)
    }

    @Test
    fun `runtime BoxUtil glow uses the same stroke endpoints as TypeScript preview`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val baseLayer = preset.trailEntities.single().layers.single()
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val glow = preset.glowLayers.first()
        val nodes = ASTDProjectileVfxGlowRenderer.mutableRuntimeNodes(180f, widthBase, glow)
        val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, glow)
        val headGap = kotlin.math.max(14f, lineWidth * 0.55f)

        assertEquals(2, nodes.size)
        assertEquals(-180f * 0.72f, nodes[0].x, 0.0001f)
        assertEquals(glow.yOffset, nodes[0].y, 0.0001f)
        assertEquals(-headGap, nodes[1].x, 0.0001f)
        assertEquals(glow.yOffset * 0.18f, nodes[1].y, 0.0001f)
    }

    @Test
    fun `runtime glow layer uses one TS gradient mesh path without BoxUtil segment fallback`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxGlowRenderer.kt"))
        val renderLayerBody = source.substringAfter("class ASTDProjectileVfxGlowRenderLayer").substringBefore("override fun beginFadeOut")

        assertFalse(source.contains("org.boxutil.units.standard.entity.TrailEntity"), "runtime glow should not keep the lossy segmented BoxUtil gradient path")
        assertFalse(renderLayerBody.contains("createEntity("), "runtime glow should not create segmented BoxUtil trail entities")
        assertFalse(renderLayerBody.contains("boxUtilSegmentsForTests"), "runtime glow should not split the TS gradient into material-tinted BoxUtil segments")
        assertTrue(renderLayerBody.contains("ASTDProjectileVfxBodyRenderManager"), "runtime glow should render through the shared mesh manager")
        assertTrue(renderLayerBody.contains("meshesForTests"), "runtime glow should use the full TS stroke plus shadow mesh covered by preview parity tests")
    }
}
