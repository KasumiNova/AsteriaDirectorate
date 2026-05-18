package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRibbonRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxRibbonRendererTest {
    @Test
    fun `ribbon renderer builds full local trail even when projectile history is pinned`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, testContext(), 6)

        assertEquals(7, points.size)
        assertEquals(0f, points.first().base.x, 0.0001f)
        assertEquals(-testContext().visibleLength, points.last().base.x, 0.0001f)
    }

    @Test
    fun `ribbon renderer alpha follows graph settings and beam alpha`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, testContext(), 6)

        val expected = ribbon.alphaScale * 0.8f *
            ASTDProjectileVfxMath.lerp(0.6f, 1f, ASTDProjectileVfxRibbonRenderer.smokeEnvelopeForTest(0f))
        assertEquals(expected, points.first().alpha, 0.0001f)
    }

    @Test
    fun `ribbon renderer width follows preview trail width and thickness`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val trailWidth = preset.trailEntities.single().layers.single().startWidth

        val widths = ASTDProjectileVfxRibbonRenderer.widthsForTests(ribbon, trailWidth)

        assertEquals(trailWidth * ribbon.thickness, widths.startWidth, 0.0001f)
        assertEquals(trailWidth * ribbon.thickness * 0.76f, widths.endWidth, 0.0001f)
    }

    @Test
    fun `ribbon renderer emits custom strip mesh instead of single line entity`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val trailWidth = preset.trailEntities.single().layers.single().startWidth

        val mesh = ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, testContext(), 6, trailWidth)

        assertEquals(28, mesh.vertices.size)
        assertEquals(24, mesh.triangles.size)
        assertEquals("additive", mesh.blendMode)
        assertTrue(mesh.vertices.any { it.position.y != 0f })
    }

    @Test
    fun `ribbon renderer adds preview secondary edge stroke`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val trailWidth = preset.trailEntities.single().layers.single().startWidth

        val mesh = ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, testContext(), 6, trailWidth)

        assertEquals(28, mesh.vertices.size)
        assertEquals(24, mesh.triangles.size)
        assertTrue(mesh.vertices.drop(14).all { it.color.alpha > 0f })
        assertTrue(mesh.vertices.drop(14).maxOf { it.color.alpha } < mesh.vertices.take(14).maxOf { it.color.alpha })
    }

    @Test
    fun `ribbon renderer scales wave by ribbon width and preview smoke envelope`() {
        val ribbon = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.ribbonDecorations.single().copy(
            waveType = "sine",
            amplitude = 1f,
            waveSpeed = 0f,
            frequency = 0f,
        )
        val context = testContext()
        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, context, 10)
        val middle = points[5]
        val expectedWave = ASTDProjectileVfxMath.ribbonWave(
            ribbon.waveType,
            middle.base.x,
            context.elapsed,
            ribbon.frequency,
            ribbon.waveSpeed,
            ribbon.amplitude,
            ribbon.noiseScale,
            17,
            0.48f,
        )
        val expectedEnvelope = ASTDProjectileVfxMath.lerp(
            0.72f,
            1f,
            ASTDProjectileVfxRibbonRenderer.smokeEnvelopeForTest(0.5f),
        )
        val expectedOffset = (ribbon.endOffset + expectedWave * 4f) * expectedEnvelope

        assertNotEquals(expectedWave, middle.position.y - middle.base.y)
        assertEquals(expectedOffset, middle.position.y - middle.base.y, 0.0001f)
    }

    @Test
    fun `ribbon wave samples quantized logic time instead of render frame elapsed`() {
        val ribbon = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.ribbonDecorations.single().copy(
            waveType = "noise",
            amplitude = 1.35f,
            waveSpeed = 1f,
        )
        val firstFrame = testContext(elapsed = 0.1001f).copy(logicElapsed = 0.1f)
        val sameFrame = testContext(elapsed = 0.109f).copy(logicElapsed = 0.1f)
        val nextFrame = testContext(elapsed = 0.117f).copy(logicElapsed = 7f / 60f)

        val first = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, firstFrame, 10)
        val same = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, sameFrame, 10)
        val next = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, nextFrame, 10)

        assertEquals(first[5].position.y, same[5].position.y, 0.0001f)
        assertNotEquals(first[5].position.y, next[5].position.y)
    }

    @Test
    fun `ribbon renderer samples runtime color gradient at start middle and end`() {
        val ribbon = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.ribbonDecorations.single().copy(
            colorGradient = ASTDTrailDecorationColorGradientSpec(
                enabled = true,
                stops = listOf(
                    ASTDTrailDecorationColorStopSpec(0f, ASTDColor(0f, 0f, 1f, 1f)),
                    ASTDTrailDecorationColorStopSpec(0.5f, ASTDColor(0f, 1f, 0f, 0.5f)),
                    ASTDTrailDecorationColorStopSpec(1f, ASTDColor(1f, 0f, 0f, 0f)),
                ),
            ),
        )

        assertEquals(0f, ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 0f).red, 0.0001f)
        assertEquals(1f, ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 0.5f).green, 0.0001f)
        assertEquals(1f, ASTDProjectileVfxRibbonRenderer.sampleColor(ribbon, 1f).red, 0.0001f)
    }
}
