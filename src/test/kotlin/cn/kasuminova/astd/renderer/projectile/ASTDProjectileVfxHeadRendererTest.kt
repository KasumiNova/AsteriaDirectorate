package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxHeadRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxHeadRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderManager
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxShaderRenderer
import com.fs.starfarer.api.combat.CombatEngineAPI
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ASTDProjectileVfxHeadRendererTest {
    @Test
    fun `head renderer creates stable pointed shell vertices`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.headLayers.single()
        val vertices = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f)

        assertEquals(7, vertices.size)
        assertEquals(-layer.length * layer.rearRatio * 0.8f, vertices[0].x, 0.0001f)
        assertEquals(0f, vertices[3].x, 0.0001f)
        assertEquals(-layer.length * layer.shoulderRatio * 0.8f, vertices[1].x, 0.0001f)
    }

    @Test
    fun `head renderer vertices scale with trail width base`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.headLayers.single()
        val base = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f, widthBase = 6f)
        val wider = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, 0.8f, widthBase = 12f)

        assertEquals(base[0].x * 2f, wider[0].x, 0.0001f)
        assertEquals(base[1].y * 2f, wider[1].y, 0.0001f)
    }

    @Test
    fun `head renderer colors follow trail colors`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = preset.headLayers.single()
        val baseLayer = preset.trailEntities.single().layers.single()
        val redLayer = baseLayer.copy(
            startColor = ASTDColor(1f, 0f, 0f, 1f),
            startEmissive = ASTDColor(1f, 0f, 0f, 1f),
            endColor = ASTDColor(0.4f, 0f, 0f, 0.5f),
            endEmissive = ASTDColor(0.6f, 0f, 0f, 0.5f),
        )
        val defaultColors = ASTDProjectileVfxHeadRenderer.colorsForTests(baseLayer, layer)
        val redColors = ASTDProjectileVfxHeadRenderer.colorsForTests(redLayer, layer)

        assert(defaultColors.mid.blue > defaultColors.mid.red)
        assert(redColors.mid.red > 0f)
        assertEquals(0f, redColors.mid.green, 0.0001f)
        assertEquals(0f, redColors.mid.blue, 0.0001f)
        assert(redColors.end.red > redColors.end.green)
    }

    @Test
    fun `head renderer alpha follows shared beam alpha`() {
        val layer = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.headLayers.single()
        assertEquals(0.8f, ASTDProjectileVfxHeadRenderer.alphaForTests(layer, testContext()), 0.0001f)
    }

    @Test
    fun `head renderer fill layout consumes preview head dimensions and vertices`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val baseLayer = trail.layers.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer) * ASTDProjectileVfxShaderRenderer.PREVIEW_BODY_WIDTH_SCALE
        val expected = ASTDProjectileVfxLayout.headFillLayout(baseLayer, layer, preset.lifecycle.projectileHeadSizeScale, widthBase, context.beamAlpha)

        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(baseLayer, layer, context, headSizeScale = preset.lifecycle.projectileHeadSizeScale)
        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trail, listOf(layer), context, headSizeScale = preset.lifecycle.projectileHeadSizeScale).single()

        assertEquals(expected.headVisible, layout.headVisible, 0.0001f)
        assertEquals(expected.width, layout.width, 0.0001f)
        assertEquals(expected.rearX, layout.rearX, 0.0001f)
        assertEquals(expected.vertices.asList().size, mesh.polygon.size)
        assertEquals(expected.vertices.rearTop.x, mesh.polygon.first().x, 0.0001f)
        assertEquals(expected.vertices.tip.x, mesh.polygon[3].x, 0.0001f)
        assertTrue(mesh.triangles.size >= 5)
        assertEquals(expected.vertices.rearTop.x, mesh.vertices[0].position.x, 0.0001f)
        assertEquals(expected.vertices.rearBottom.x, mesh.vertices[1].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderTop.x, mesh.vertices[2].position.x, 0.0001f)
        assertEquals(expected.vertices.shoulderBottom.x, mesh.vertices[3].position.x, 0.0001f)
        assertSame(mesh.vertices[0], mesh.triangles.first().a)
        assertSame(mesh.vertices[1], mesh.triangles.first().b)
        assertSame(mesh.vertices[2], mesh.triangles.first().c)
        assertSame(mesh.vertices[3], mesh.triangles[1].c)
        assertEquals("additive", mesh.blendMode)
    }

    @Test
    fun `head renderer adds preview blur and shadow envelope around filled shell`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)
        val baseLayer = trail.layers.single()
        val layout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(
            baseLayer,
            layer,
            context,
            headSizeScale = preset.lifecycle.projectileHeadSizeScale,
        )

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = preset.lifecycle.projectileHeadSizeScale,
        ).single()

        assertTrue(mesh.vertices.size > layout.vertices.asList().size)
        assertTrue(mesh.vertices.maxOf { it.position.y } > layout.vertices.asList().maxOf { it.y } + 7f)
        assertTrue(mesh.vertices.minOf { it.position.y } < layout.vertices.asList().minOf { it.y } - 7f)
        assertTrue(mesh.vertices.any { it.color.alpha in 0.01f..0.16f })
    }

    @Test
    fun `head renderer attenuates filled shell alpha for in-game bloom parity`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single().copy(alphaScale = 0.5f)
        val context = testContext().copy(beamAlpha = 0.8f)
        val baseLayer = trail.layers.single()
        val expectedLayout = ASTDProjectileVfxHeadRenderer.fillLayoutForTests(baseLayer, layer, context, headSizeScale = 1f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trail, listOf(layer), context).single()

        assertTrue(mesh.vertices.take(mesh.polygon.size).all { it.color.alpha < expectedLayout.alpha })
        assertEquals(expectedLayout.alpha * 0.16f, mesh.vertices[0].color.alpha, 0.0001f)
        assertEquals(expectedLayout.alpha * 0.024f, mesh.vertices[2].color.alpha, 0.0001f)
        assertEquals(expectedLayout.alpha * 0.008f, mesh.vertices[6].color.alpha, 0.0001f)
    }

    @Test
    fun `head renderer keeps shell geometry thin and reserves vertical spread for shadow envelope`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(
            trail,
            listOf(layer),
            context,
            headSizeScale = preset.lifecycle.projectileHeadSizeScale,
        ).single()

        assertEquals(1.2f, mesh.xScale, 0.0001f)
        assertEquals(ASTDProjectileVfxShaderRenderer.PREVIEW_VERTICAL_SCALE, mesh.yScale, 0.0001f)
    }

    @Test
    fun `head renderer samples continuous preview shell gradient`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val layer = preset.headLayers.single()
        val context = testContext().copy(beamAlpha = 0.8f)

        val mesh = ASTDProjectileVfxHeadRenderer.meshForTests(trail, listOf(layer), context).single()
        val rear = mesh.vertices[0].color
        val shoulder = mesh.vertices[2].color
        val tip = mesh.vertices[6].color

        assertTrue(shoulder.blue > rear.blue)
        assertTrue(tip.red > shoulder.red)
        assertTrue(tip.green > shoulder.green)
        assertTrue(tip.blue > shoulder.blue)
    }

    @Test
    fun `head runtime creates filled mesh handle when engine is available`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = ASTDProjectileVfxHeadRenderLayer(
            preset.trailEntities.single(),
            preset.headLayers,
            preset.lifecycle.projectileHeadSizeScale,
        )
        val engine = engineStub()

        assertTrue(layer.create(engine.api, testContext()))

        val snapshot = ASTDProjectileVfxBodyRenderManager.activeSnapshotsForTests(engine.api).single()
        val expected = ASTDProjectileVfxLayout.headVertices(
            preset.headLayers.single(),
            ASTDProjectileVfxLayout.headFillLayout(
                preset.trailEntities.single().layers.single(),
                preset.headLayers.single(),
                preset.lifecycle.projectileHeadSizeScale,
                ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single()) *
                    ASTDProjectileVfxShaderRenderer.PREVIEW_BODY_WIDTH_SCALE,
                testContext().beamAlpha,
            ).headVisible,
            preset.lifecycle.projectileHeadSizeScale,
            ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single()) *
                ASTDProjectileVfxShaderRenderer.PREVIEW_BODY_WIDTH_SCALE,
        )
        assertEquals(1, engine.addedLayeredRenderingPlugins.size)
        assertEquals(7, snapshot.mesh.polygon.size)
        assertEquals(expected.rearTop.x, snapshot.mesh.polygon.first().x, 0.0001f)
        assertTrue(snapshot.mesh.triangles.size >= 5)
        assertEquals("additive", snapshot.mesh.blendMode)
    }

    @Test
    fun `head runtime layer tolerates test context without engine`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = ASTDProjectileVfxHeadRenderLayer(
            preset.trailEntities.single(),
            preset.headLayers,
            preset.lifecycle.projectileHeadSizeScale,
        )
        val engine = engineStub()

        assertFalse(layer.create(null, testContext()))
        layer.advance(null, testContext(), 0.1f)

        assertEquals(0, ASTDProjectileVfxBodyRenderManager.activeHandleCountForTests(engine.api))
        assertEquals(0, engine.addedLayeredRenderingPlugins.size)
    }

    private class EngineStub {
        val customData: MutableMap<String, Any?> = HashMap()
        val addedLayeredRenderingPlugins = ArrayList<Any?>()
        lateinit var api: CombatEngineAPI
    }

    private fun engineStub(): EngineStub {
        val state = EngineStub()
        state.api = Proxy.newProxyInstance(
            CombatEngineAPI::class.java.classLoader,
            arrayOf(CombatEngineAPI::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getCustomData" -> state.customData
                    "addLayeredRenderingPlugin" -> {
                        state.addedLayeredRenderingPlugins += args?.get(0)
                        null
                    }
                    "isPaused" -> false
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as CombatEngineAPI
        return state
    }

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
