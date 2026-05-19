package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxFadeReason
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderContext
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderGraph
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderLayer
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ASTDProjectileVfxRenderGraphTest {
    @Test
    fun `create follows preset render graph layer order`() {
        val layer = RecordingLayer()
        val graph = ASTDProjectileVfxRenderGraph(listOf(layer))
        val context = testContext(elapsed = 0f)

        graph.create(null, context)

        assertEquals(listOf("create:0.0"), layer.events)
        assertSame(context, layer.contexts.single())
    }

    @Test
    fun `advance passes the same context to every layer`() {
        val first = RecordingLayer()
        val second = RecordingLayer()
        val graph = ASTDProjectileVfxRenderGraph(listOf(first, second))
        val context = testContext(elapsed = 0.25f)

        graph.advance(null, context, 0.1f)

        assertTrue(first.contexts.all { it === context })
        assertTrue(second.contexts.all { it === context })
        assertEquals(listOf("create:0.25", "advance:0.25:0.1"), first.events)
        assertEquals(listOf("create:0.25", "advance:0.25:0.1"), second.events)
    }

    @Test
    fun `fade and delete broadcast to all layers`() {
        val first = RecordingLayer()
        val second = RecordingLayer()
        val graph = ASTDProjectileVfxRenderGraph(listOf(first, second))

        graph.beginFadeOut(ASTDProjectileVfxFadeReason.Hit, 0.15f)
        graph.delete()

        assertEquals(listOf("fade:Hit:0.15", "delete"), first.events)
        assertEquals(listOf("fade:Hit:0.15", "delete"), second.events)
    }

    @Test
    fun `aod7 preset builds runtime render layers from exported graph`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layers = ASTDProjectileVfxRenderGraph.layersFor(preset)

        assertEquals(
            listOf(
                "ASTDProjectileVfxMistRenderLayer",
                "ASTDProjectileVfxGlowRenderLayer",
                "ASTDProjectileVfxBodyRenderLayer",
                "ASTDProjectileVfxSideWispRenderLayer",
                "ASTDProjectileVfxHeadRenderLayer",
                "ASTDProjectileVfxRibbonRenderLayer",
            ),
            layers.map { it.javaClass.simpleName },
        )
    }

    @Test
    fun `runtime render layers tolerate test context without engine`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layers = ASTDProjectileVfxRenderGraph.layersFor(preset)
        val context = testContext()

        layers.forEach { layer ->
            assertEquals(false, layer.create(null, context))
            layer.advance(null, context, 0.1f)
            layer.beginFadeOut(ASTDProjectileVfxFadeReason.Removed, 0.15f)
            layer.delete()
        }
    }

    private class RecordingLayer : ASTDProjectileVfxRenderLayer {
        val events = ArrayList<String>()
        val contexts = ArrayList<ASTDProjectileVfxRenderContext>()

        override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
            contexts += context
            events += "create:${context.elapsed}"
            return true
        }

        override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
            contexts += context
            events += "advance:${context.elapsed}:$amount"
        }

        override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
            events += "fade:$reason:$seconds"
        }

        override fun delete() {
            events += "delete"
        }
    }
}

internal fun testContext(elapsed: Float = 0f): ASTDProjectileVfxRenderContext = ASTDProjectileVfxRenderContext(
    location = Vector2f(10f, 20f),
    velocityFacing = 5f,
    projectileFacing = 3f,
    renderFacing = 5f,
    elapsed = elapsed,
    logicElapsed = elapsed,
    flightProgress = 0.5f,
    dissolve = 0.1f,
    visibleLength = 120f,
    beamAlpha = 0.8f,
    historyNodes = listOf(
        ASTDProjectileHistoryNode(Vector2f(0f, 0f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(10f, 0f), 0f, 0.1f),
    ),
    presetId = "test_preset",
    projectileSpecId = "test_projectile",
)
