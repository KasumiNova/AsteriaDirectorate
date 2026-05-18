package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderManager
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxFadeReason
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxShaderRenderer
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDProjectileVfxBodyRendererTest {
    @Test
    fun `body renderer mesh consumes preview body polygon and gradient stops`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val baseLayer = trail.layers.single()
        val context = testContext(0.2f)
        val pulse = context.beamAlpha
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer) * ASTDProjectileVfxShaderRenderer.PREVIEW_BODY_WIDTH_SCALE

        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context)
        val expectedPolygon = ASTDProjectileVfxLayout.bodyPolygon(widthBase, context.visibleLength, pulse)
        val expectedStops = ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, pulse)

        assertEquals(expectedPolygon.size, mesh.polygon.size)
        assertEquals(expectedPolygon.first().x, mesh.polygon.first().x, 0.0001f)
        assertEquals(expectedPolygon[3].y, mesh.polygon[3].y, 0.0001f)
        assertEquals(expectedStops.size, mesh.gradientStops.size)
        assertEquals(expectedStops[2].offset, mesh.gradientStops[2].offset, 0.0001f)
        assertEquals(0f, mesh.vertices.first().color.alpha, 0.0001f)
        assertEquals(0f, mesh.vertexAt(expectedPolygon[4]).color.alpha, 0.0001f)
        assertTrue(mesh.triangles.size >= 8)
        assertEquals(mesh.vertices[0], mesh.triangles.first().a)
        assertEquals(mesh.vertices[1], mesh.triangles.first().b)
        assertEquals(mesh.vertices[2], mesh.triangles.first().c)
        assertEquals(mesh.vertices[2], mesh.triangles[1].a)
        assertEquals(mesh.vertices[1], mesh.triangles[1].b)
        assertEquals(mesh.vertices[3], mesh.triangles[1].c)
        assertTrue(mesh.vertices[3].color.alpha > mesh.vertices[1].color.alpha)
        assertEquals("additive", mesh.blendMode)
    }

    @Test
    fun `body renderer attenuates hot additive stops for in-game bloom parity`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            trail,
            testContext().copy(visibleLength = 420f, beamAlpha = 0.8f),
        )
        val polygon = mesh.polygon

        assertEquals(0.0026253266f, mesh.vertexAt(polygon[2]).color.alpha, 0.0001f)
        assertEquals(0.0026253266f, mesh.vertexAt(polygon[6]).color.alpha, 0.0001f)
    }

    @Test
    fun `body renderer keeps bright core thin and lets shadow envelope carry bloom`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            preset.trailEntities.single(),
            testContext().copy(visibleLength = 420f, beamAlpha = 0.8f),
        )

        assertEquals(1.55f, mesh.xScale, 0.0001f)
        assertEquals(ASTDProjectileVfxShaderRenderer.PREVIEW_VERTICAL_SCALE, mesh.yScale, 0.0001f)
    }

    @Test
    fun `body renderer adds preview shadow blur envelope around core polygon`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val context = testContext().copy(visibleLength = 160f, beamAlpha = 0.8f)
        val baseOnly = ASTDProjectileVfxLayout.bodyPolygon(
            ASTDProjectileVfxLayout.widthBase(trail.layers.single()),
            context.visibleLength,
            context.beamAlpha,
        )

        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context)

        assertTrue(mesh.vertices.size > baseOnly.size)
        assertTrue(mesh.triangles.size > baseOnly.size - 2)
        assertTrue(mesh.vertices.maxOf { it.position.y } > baseOnly.maxOf { it.y } + 7f)
        assertTrue(mesh.vertices.minOf { it.position.y } < baseOnly.minOf { it.y } - 7f)
        assertTrue(mesh.vertices.any { it.color.alpha in 0.01f..0.18f })
    }

    @Test
    fun `body renderer updates mesh from visible length and beam alpha`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()

        val shortDim = ASTDProjectileVfxBodyRenderer.meshForTests(trail, testContext().copy(visibleLength = 80f, beamAlpha = 0.25f))
        val longBright = ASTDProjectileVfxBodyRenderer.meshForTests(trail, testContext().copy(visibleLength = 160f, beamAlpha = 0.8f))

        assertTrue(longBright.polygon.first().x < shortDim.polygon.first().x)
        assertTrue(longBright.vertices.maxOf { it.color.alpha } > shortDim.vertices.maxOf { it.color.alpha })
    }

    @Test
    fun `body runtime layer tolerates test context without engine and keeps test mesh current`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = ASTDProjectileVfxBodyRenderLayer(preset.trailEntities.single())
        val firstContext = testContext().copy(visibleLength = 90f, beamAlpha = 0.4f)
        val secondContext = testContext().copy(visibleLength = 150f, beamAlpha = 0.75f)

        assertFalse(layer.create(null, firstContext))
        layer.advance(null, firstContext, 0.1f)
        val firstMesh = layer.meshForTests()
        layer.advance(null, secondContext, 0.1f)
        layer.beginFadeOut(ASTDProjectileVfxFadeReason.Removed, 0.2f)
        layer.advance(null, secondContext, 0.1f)
        val fadedMesh = layer.meshForTests()
        layer.delete()

        assertTrue(fadedMesh.polygon.first().x < firstMesh.polygon.first().x)
        assertTrue(fadedMesh.vertices.maxOf { it.color.alpha } < ASTDProjectileVfxBodyRenderer.meshForTests(preset.trailEntities.single(), secondContext).vertices.maxOf { it.color.alpha })
    }

    @Test
    fun `body runtime layer reports created when engine is available`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = ASTDProjectileVfxBodyRenderLayer(preset.trailEntities.single())
        val engine = engineStub()

        assertTrue(layer.create(engine.api, testContext()))
        assertEquals(1, ASTDProjectileVfxBodyRenderManager.activeHandleCountForTests(engine.api))
        assertEquals(1, engine.addedLayeredRenderingPlugins.size)
    }

    @Test
    fun `body runtime layer keeps manager handle current and deletes it`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val layer = ASTDProjectileVfxBodyRenderLayer(trail)
        val engine = engineStub()
        val firstContext = testContext().copy(location = Vector2f(10f, 20f), renderFacing = 0f, visibleLength = 90f)
        val secondContext = testContext().copy(location = Vector2f(30f, 40f), renderFacing = 90f, visibleLength = 150f)

        layer.create(engine.api, firstContext)
        layer.advance(engine.api, secondContext, 0.1f)
        val snapshot = ASTDProjectileVfxBodyRenderManager.activeSnapshotsForTests(engine.api).single()
        layer.delete()

        assertEquals(secondContext.location.x, snapshot.location.x, 0.0001f)
        assertEquals(secondContext.location.y, snapshot.location.y, 0.0001f)
        assertEquals(secondContext.renderFacing, snapshot.facing, 0.0001f)
        assertEquals(
            ASTDProjectileVfxBodyRenderer.meshForTests(trail, secondContext).polygon.first().x,
            snapshot.mesh.polygon.first().x,
            0.0001f,
        )
        assertEquals(0, ASTDProjectileVfxBodyRenderManager.activeHandleCountForTests(engine.api))
    }

    @Test
    fun `body runtime layer does not create manager handle without engine`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = ASTDProjectileVfxBodyRenderLayer(preset.trailEntities.single())
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

    private fun ASTDProjectileVfxBodyRenderer.Mesh.vertexAt(point: Vector2f): ASTDProjectileVfxBodyRenderer.Vertex {
        return vertices.first {
            kotlin.math.abs(it.position.x - point.x) < 0.0001f &&
                kotlin.math.abs(it.position.y - point.y) < 0.0001f
        }
    }
}
