package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentSpec
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxFadeReason
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMath
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderContext
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderGraph
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderLayer
import com.fs.starfarer.api.combat.CombatEngineAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ASTDProjectileVfxRuntimeTest {
    @Test
    fun `runtime state machine transitions from active to fading to removed`() {
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())

        assertEquals(ASTDProjectileVfxRuntimeState.Active, runtime.state)

        runtime.advanceForTests(locationX = 0f, locationY = 0f, facing = 0f, amount = 0.1f, projectileAlive = true)
        assertEquals(ASTDProjectileVfxRuntimeState.Active, runtime.state)
        assertTrue(runtime.historyNodesForTests().isNotEmpty())

        runtime.advanceForTests(locationX = 2f, locationY = 0f, facing = 0f, amount = 0.1f, projectileAlive = false)
        assertEquals(ASTDProjectileVfxRuntimeState.Fading, runtime.state)

        runtime.advanceForTests(locationX = 4f, locationY = 0f, facing = 0f, amount = 1f, projectileAlive = false)
        assertEquals(ASTDProjectileVfxRuntimeState.Removed, runtime.state)
    }

    @Test
    fun `removed runtime no longer samples history`() {
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())
        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.markProjectileGone()
        runtime.advanceForTests(1f, 0f, 0f, 1f, projectileAlive = false)
        val count = runtime.historyNodesForTests().size

        runtime.advanceForTests(20f, 0f, 0f, 1f, projectileAlive = true)

        assertEquals(count, runtime.historyNodesForTests().size)
    }

    @Test
    fun `runtime preserves non linear projectile history`() {
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())
        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.advanceForTests(10f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.advanceForTests(10f, 10f, 90f, 0.1f, projectileAlive = true)

        val nodes = runtime.historyNodesForTests()
        assertEquals(3, nodes.size)
        assertEquals(10f, nodes[1].location.x)
        assertEquals(0f, nodes[1].location.y)
        assertEquals(10f, nodes[2].location.x)
        assertEquals(10f, nodes[2].location.y)
    }

    @Test
    fun `runtime render graph receives shared context and projectile gone fade`() {
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset(), listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.advanceForTests(10f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.advanceForTests(12f, 0f, 0f, 0.05f, projectileAlive = false)

        assertTrue(layer.created)
        assertTrue(layer.contexts.isNotEmpty())
        assertEquals(ASTDProjectileVfxFadeReason.Removed, layer.fadeReasons.single())
        assertSame(layer.contexts.last(), layer.contexts.last())
    }

    @Test
    fun `mark projectile gone forwards explicit fade reasons and matching seconds`() {
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset(), listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.markProjectileGone(ASTDProjectileVfxFadeReason.Hit)

        assertEquals(ASTDProjectileVfxFadeReason.Hit, layer.fadeReasons.single())
        assertEquals(0.1f, layer.fadeSeconds.single(), 0.0001f)
    }

    @Test
    fun `runtime creates render graph layers from preset by default`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val runtime = ASTDProjectileVfxRuntime.forTests(preset)

        assertEquals(ASTDProjectileVfxRenderGraph.layersFor(preset).size, runtime.renderLayerCountForTests())
    }

    @Test
    fun `runtime context visible length follows traveled distance until authored trail cap`() {
        val preset = testPreset().withTrailLayer(
            ASTDTrailLayerSpec(
                width = 8f,
                color = ASTDColor(1f, 1f, 1f, 1f),
                length = 420f,
            ),
        ).copy(lifecycle = ASTDProjectileVfxLifecycleSpec(durationSeconds = 1.25f, dissolveStartRatio = 0.6f))
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.advanceForTests(180f, 0f, 0f, 0.1f, projectileAlive = true)
        val growing = layer.contexts.last()
        runtime.advanceForTests(720f, 0f, 0f, 0.1f, projectileAlive = true)
        val capped = layer.contexts.last()

        assertEquals(180f, growing.visibleLength, 0.0001f)
        assertEquals(1280f * 0.46f, capped.visibleLength, 0.0001f)
        assertEquals(1f, growing.beamAlpha, 0.0001f)
        assertEquals(1f, capped.beamAlpha, 0.0001f)
    }

    @Test
    fun `runtime tail cap uses authored reference width instead of active viewport zoom`() {
        val preset = testPreset().withTrailLayer(
            ASTDTrailLayerSpec(
                width = 40f,
                color = ASTDColor(1f, 1f, 1f, 1f),
                length = 420f,
                startWidth = 40f,
            ),
        ).copy(lifecycle = ASTDProjectileVfxLifecycleSpec(durationSeconds = 1.25f, dissolveStartRatio = 0.6f))
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true, viewportVisibleWidth = 1280f)
        runtime.advanceForTests(720f, 0f, 0f, 0.1f, projectileAlive = true, viewportVisibleWidth = 1280f)

        assertEquals(588.8f, layer.contexts.last().visibleLength, 0.0001f)
    }

    @Test
    fun `runtime authored tail cap is not overridden by trail length`() {
        val preset = testPreset().withTrailLayer(
            ASTDTrailLayerSpec(
                width = 40f,
                color = ASTDColor(1f, 1f, 1f, 1f),
                length = 900f,
                startWidth = 40f,
            ),
        ).copy(lifecycle = ASTDProjectileVfxLifecycleSpec(durationSeconds = 1.25f, dissolveStartRatio = 0.6f))
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true, viewportVisibleWidth = 1280f)
        runtime.advanceForTests(720f, 0f, 0f, 0.1f, projectileAlive = true, viewportVisibleWidth = 1280f)

        assertEquals(588.8f, layer.contexts.last().visibleLength, 0.0001f)
    }

    @Test
    fun `runtime keeps authored preview geometry in world space while distance grows in world units`() {
        val preset = testPreset().withTrailLayer(
            ASTDTrailLayerSpec(
                width = 40f,
                color = ASTDColor(1f, 1f, 1f, 1f),
                length = 900f,
                startWidth = 40f,
            ),
        ).copy(
            lifecycle = ASTDProjectileVfxLifecycleSpec(
                durationSeconds = 1.25f,
                dissolveStartRatio = 0.6f,
                layoutReferenceWidth = 1846f,
            ),
        )
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))

        runtime.advanceForTests(
            locationX = 0f,
            locationY = 0f,
            facing = 0f,
            amount = 0.1f,
            projectileAlive = true,
            viewportVisibleWidth = 1067.0833f,
            viewportPixelWidth = 2560f,
            viewportViewMult = 0.6255f,
        )
        runtime.advanceForTests(
            locationX = 720f,
            locationY = 0f,
            facing = 0f,
            amount = 0.1f,
            projectileAlive = true,
            viewportVisibleWidth = 1067.0833f,
            viewportPixelWidth = 2560f,
            viewportViewMult = 0.6255f,
        )

        val context = layer.contexts.last()
        val referenceScale = 600f / 1440f
        assertEquals(referenceScale, context.worldUnitsPerPixel, 0.0001f)
        assertEquals(1846f * 0.46f, context.visibleLength, 0.0001f)
        assertEquals(353.81668f, context.visibleLength * context.worldUnitsPerPixel, 0.0001f)
    }

    @Test
    fun `runtime keeps projectile geometry in world space across active viewport zoom`() {
        val preset = testPreset().withTrailLayer(
            ASTDTrailLayerSpec(
                width = 40f,
                color = ASTDColor(1f, 1f, 1f, 1f),
                length = 900f,
                startWidth = 40f,
            ),
        ).copy(
            lifecycle = ASTDProjectileVfxLifecycleSpec(
                durationSeconds = 1.25f,
                dissolveStartRatio = 0.6f,
                layoutReferenceWidth = 1846f,
            ),
        )
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 0.1f, true, viewportVisibleWidth = 1846f, viewportPixelWidth = 2560f, viewportViewMult = 4f)
        runtime.advanceForTests(120f, 0f, 0f, 0.1f, true, viewportVisibleWidth = 1067.0833f, viewportPixelWidth = 2560f, viewportViewMult = 0.6255f)
        val zoomedIn = layer.contexts.last()

        runtime.advanceForTests(240f, 0f, 0f, 0.1f, true, viewportVisibleWidth = 4200f, viewportPixelWidth = 2560f, viewportViewMult = 4f)
        val zoomedOut = layer.contexts.last()

        val referenceScale = 600f / 1440f
        assertEquals(referenceScale, zoomedIn.worldUnitsPerPixel, 0.0001f)
        assertEquals(referenceScale, zoomedOut.worldUnitsPerPixel, 0.0001f)
        assertEquals(120f / referenceScale, zoomedIn.visibleLength, 0.0001f)
        assertEquals(240f / referenceScale, zoomedOut.visibleLength, 0.0001f)
    }

    @Test
    fun `runtime retains projectile history for the full world-space visible tail`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))
        val amount = 1f / 60f
        var x = 0f

        repeat(45) {
            runtime.advanceForTests(
                locationX = x,
                locationY = 0f,
                facing = 0f,
                amount = amount,
                projectileAlive = true,
                viewportVisibleWidth = 1067.0833f,
                viewportPixelWidth = 2560f,
            )
            x += 20f
        }

        val context = layer.contexts.last()
        val history = context.historyNodes
        val retainedWorldDistance = history.last().location.x - history.first().location.x
        val requiredWorldDistance = context.visibleLength * context.worldUnitsPerPixel

        assertTrue(context.visibleLength > preset.samplingPolicy.distanceWindow)
        assertTrue(
            retainedWorldDistance >= requiredWorldDistance,
            "history retained $retainedWorldDistance world units, required $requiredWorldDistance for visibleLength ${context.visibleLength}",
        )
    }

    @Test
    fun `runtime context exposes quantized logic elapsed from sampling fps`() {
        val preset = testPreset().copy(
            samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 0, 160f),
        )
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 0.109f, projectileAlive = true)

        val context = layer.contexts.last()
        assertEquals(0.109f, context.elapsed, 0.0001f)
        assertEquals(6f / 60f, context.logicElapsed, 0.0001f)
    }

    @Test
    fun `runtime telemetry records last render context for automation evidence`() {
        ASTDProjectileVfxRuntimeTelemetry.clear()
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset(), listOf(RecordingRuntimeLayer()))

        runtime.advanceForTests(0f, 0f, 0f, 0.42f, projectileAlive = true)
        runtime.advanceForTests(10f, 0f, 0f, 0.01f, projectileAlive = true)

        val snapshot = ASTDProjectileVfxRuntimeTelemetry.snapshot()
        assertEquals(0.43f, snapshot.lastElapsed, 0.0001f)
        assertTrue(snapshot.lastVisibleLength > 0f)
        assertTrue(snapshot.lastBeamAlpha > 0f)
        assertEquals(1f, snapshot.lastWorldUnitsPerPixel, 0.0001f)
    }

    private fun testPreset() = ASTDProjectileVfxPreset(
        id = "test_runtime",
        components = listOf(testTrail(), ASTDProjectileVfxComponentSpec.Body("body", trailId = "trail")),
        samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 0, 160f),
        fadePolicy = ASTDProjectileVfxFadePolicy(0f, 0.2f, 0.1f, 0.2f),
    )

    private fun testTrail(layer: ASTDTrailLayerSpec = ASTDTrailLayerSpec(width = 8f, color = ASTDColor(1f, 1f, 1f, 1f), length = 120f)) =
        ASTDProjectileVfxComponentSpec.Trail(id = "trail", layer = layer)

    private fun ASTDProjectileVfxPreset.withTrailLayer(layer: ASTDTrailLayerSpec): ASTDProjectileVfxPreset =
        copy(components = components.map { component ->
            if (component is ASTDProjectileVfxComponentSpec.Trail) component.copy(layer = layer) else component
        })

    private class RecordingRuntimeLayer : ASTDProjectileVfxRenderLayer {
        var created = false
        val contexts = ArrayList<ASTDProjectileVfxRenderContext>()
        val fadeReasons = ArrayList<ASTDProjectileVfxFadeReason>()
        val fadeSeconds = ArrayList<Float>()

        override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
            created = true
            contexts += context
            return true
        }

        override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
            contexts += context
        }

        override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
            fadeReasons += reason
            fadeSeconds += seconds
        }

        override fun delete() = Unit
    }
}
