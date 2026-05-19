package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRibbonRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderManager
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMath
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxRibbonRendererTest {
    @Test
    fun `ribbon renderer samples projectile history by distance from head`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            historyNodes = curvedHistory(),
            visibleLength = 40f,
        )

        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, context, 4)

        assertEquals(5, points.size)
        assertEquals(200f, points.first().base.x, 0.0001f)
        assertEquals(120f, points.first().base.y, 0.0001f)
        assertEquals(174.19511f, points.last().base.x, 0.0001f)
        assertEquals(141.60977f, points.last().base.y, 0.0001f)
    }

    @Test
    fun `ribbon renderer alpha follows graph settings and beam alpha`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, testContext(), 6)

        val expected = ribbon.alphaScale *
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

        assertTrue(mesh.vertices.size >= 28)
        assertTrue(mesh.triangles.size >= 24)
        assertEquals("normal", mesh.blendMode)
        assertTrue(mesh.vertices.any { it.position.y != 0f })
    }

    @Test
    fun `ribbon mesh is emitted in projectile local space for shared render transform`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single().copy(waveSpeed = 0f)
        val trailWidth = preset.trailEntities.single().layers.single().startWidth
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            historyNodes = straightHistory(),
            visibleLength = 40f,
        )

        val mesh = ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, context, 4, trailWidth)
        val minLocalX = mesh.vertices.minOf { it.position.x }
        val maxLocalX = mesh.vertices.maxOf { it.position.x }
        val firstWorld = ASTDProjectileVfxBodyRenderManager.transformLocalPointForTests(
            mesh.vertices.first().position,
            context.location,
            context.renderFacing,
        )

        assertTrue(minLocalX < -38f, "ribbon tail should remain behind the projectile in local space")
        assertTrue(maxLocalX < 4f, "ribbon head should not be pre-translated into world space")
        assertTrue(firstWorld.x in 196f..204f, "shared render transform should place the ribbon near the projectile once")
    }

    @Test
    fun `ribbon renderer adds preview secondary edge stroke`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val trailWidth = preset.trailEntities.single().layers.single().startWidth

        val mesh = ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, testContext(), 6, trailWidth)

        assertTrue(mesh.vertices.size >= 28)
        assertTrue(mesh.triangles.size >= 24)
        val secondaryStroke = mesh.vertices.drop(14).take(14)
        assertTrue(secondaryStroke.all { it.color.alpha > 0f })
        assertTrue(secondaryStroke.maxOf { it.color.alpha } < mesh.vertices.take(14).maxOf { it.color.alpha })
    }

    @Test
    fun `ribbon secondary edge stroke uses preview constant alpha override`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val trailWidth = preset.trailEntities.single().layers.single().startWidth

        val mesh = ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, testContext(), 6, trailWidth)
        val secondaryStroke = mesh.vertices.drop(14).take(14)

        secondaryStroke.forEach { vertex ->
            assertEquals(ribbon.alphaScale * 0.18f, vertex.color.alpha, 0.0001f)
        }
    }

    @Test
    fun `ribbon renderer adds preview shadow blur envelope`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single()
        val trailWidth = preset.trailEntities.single().layers.single().startWidth

        val mesh = ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, testContext(), 6, trailWidth)

        assertTrue(mesh.vertices.size > 28)
        assertTrue(mesh.triangles.size > 24)
        assertTrue(mesh.vertices.drop(28).any { it.color.red == ribbon.color.red && it.color.alpha in 0.001f..0.07f })
    }

    @Test
    fun `ribbon mesh alpha uses preview alpha override instead of sampled color alpha`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single().copy(waveSpeed = 0f)
        val trailWidth = preset.trailEntities.single().layers.single().startWidth
        val context = testContext().copy(visibleLength = 40f, beamAlpha = 1f)

        val mesh = ASTDProjectileVfxRibbonRenderer.meshForTests(ribbon, context, 4, trailWidth)
        val primaryTail = mesh.vertices.take(10).takeLast(2)
        val expectedTailAlpha = ribbon.alphaScale * (1f - 1f * 0.22f) *
            ASTDProjectileVfxMath.lerp(0.6f, 1f, ASTDProjectileVfxRibbonRenderer.smokeEnvelopeForTest(1f))

        assertEquals(0.06f, ribbon.endColor.alpha, 0.0001f)
        primaryTail.forEach { vertex ->
            assertEquals(expectedTailAlpha, vertex.color.alpha, 0.0001f)
        }
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
            context.logicElapsed,
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
        val actualOffset = kotlin.math.sqrt(
            (middle.position.x - middle.base.x) * (middle.position.x - middle.base.x) +
                (middle.position.y - middle.base.y) * (middle.position.y - middle.base.y),
        )

        assertNotEquals(expectedWave, actualOffset)
        assertEquals(expectedOffset, actualOffset, 0.0001f)
    }

    @Test
    fun `ribbon renderer applies wave along sampled history normal`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val ribbon = preset.ribbonDecorations.single().copy(
            waveType = "sine",
            amplitude = 1f,
            waveSpeed = 0f,
            frequency = 0f,
        )
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            historyNodes = curvedHistory(),
            visibleLength = 40f,
        )

        val points = ASTDProjectileVfxRibbonRenderer.pointsForTests(ribbon, context, 4)
        val middle = points[2]

        assertTrue(middle.position.x != middle.base.x)
        assertTrue(middle.position.y != middle.base.y)
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

    @Test
    fun `ribbon by length sample count follows TypeScript rounding`() {
        val ribbon = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.ribbonDecorations.single()
        val context = testContext().copy(visibleLength = 100.1f)

        val sampleCount = ASTDProjectileVfxRibbonRenderer.sampleCountForTests(ribbon, context, trailNodeCount = 13)

        assertEquals(13, sampleCount)
    }

    private fun curvedHistory(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 130f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 150f), 0f, 0f),
    )

    private fun straightHistory(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(160f, 120f), 0f, 0f),
    )
}
