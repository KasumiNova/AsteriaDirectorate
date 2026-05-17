package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxFadeReason
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMath
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderContext
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
        val runtime = ASTDProjectileVfxRuntime.forTests(ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!)

        assertEquals(6, runtime.renderLayerCountForTests())
    }

    @Test
    fun `runtime context lifecycle fields use shared math`() {
        val preset = testPreset().copy(
            trailEntities = listOf(
                ASTDTrailEntitySpec(
                    layerId = "test_runtime_trail",
                    nodes = emptyList(),
                    layerSpec = ASTDTrailLayerSpec(
                        width = 8f,
                        color = ASTDColor(1f, 1f, 1f, 1f),
                        length = 420f,
                    ),
                ),
            ),
            lifecycle = ASTDProjectileVfxLifecycleSpec(durationSeconds = 1.25f, dissolveStartRatio = 0.6f),
        )
        val layer = RecordingRuntimeLayer()
        val runtime = ASTDProjectileVfxRuntime.forTests(preset, listOf(layer))

        runtime.advanceForTests(0f, 0f, 0f, 1.0f, projectileAlive = true)

        val context = layer.contexts.last()
        val expectedDissolve = ASTDProjectileVfxMath.dissolve(1.0f, 1.25f, 0.6f)
        assertEquals(expectedDissolve, context.dissolve, 0.0001f)
        assertEquals(ASTDProjectileVfxMath.beamAlpha(expectedDissolve), context.beamAlpha, 0.0001f)
        assertEquals(ASTDProjectileVfxMath.visibleLength(420f, expectedDissolve), context.visibleLength, 0.0001f)
    }

    private fun testPreset() = ASTDProjectileVfxPreset(
        id = "test_runtime",
        layers = listOf(ASTDProjectileVfxLayer.Trail("trail", 8f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 1f))),
        samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 0, 160f),
        fadePolicy = ASTDProjectileVfxFadePolicy(0f, 0.2f, 0.1f, 0.2f),
    )

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
