package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderManager
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxFadeReason
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
    fun `body renderer follows curved projectile history when present`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            beamAlpha = 1f,
            historyNodes = curvedHistory(),
        )

        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(preset.trailEntities.single(), context)

        assertTrue(mesh.polygon.any { it.y > 18f }, "body should bend along projectile history")
        assertTrue(mesh.polygon.all { it.x in -45f..8f }, "body mesh should remain local, not world-translated")
    }

    @Test
    fun `curved body samples TypeScript tail to head gradient direction`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            beamAlpha = 1f,
            historyNodes = curvedHistory(),
        )

        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(preset.trailEntities.single(), context)
        val stripVertices = mesh.vertices.take(58)
        val headPair = stripVertices.take(2)
        val tailPair = stripVertices.takeLast(2)
        val maxAlpha = stripVertices.maxOf { it.color.alpha }
        val maxAlphaIndex = stripVertices.indexOfFirst { kotlin.math.abs(it.color.alpha - maxAlpha) < 0.0001f }

        assertTrue(headPair.all { it.position.x > -1f }, "first curved strip pair should be the projectile head")
        assertTrue(tailPair.all { it.position.x < headPair.minOf { vertex -> vertex.position.x } - 20f }, "last curved strip pair should be behind the projectile head")
        assertTrue(headPair.all { it.color.alpha <= 0.001f }, "TypeScript body gradient fades to transparent at the exact head tip")
        assertTrue(tailPair.all { it.color.alpha <= 0.001f }, "TypeScript body gradient starts transparent at the trail tail")
        assertTrue(maxAlphaIndex in 2 until (stripVertices.size * 0.35f).toInt(), "bright body core should sit near the head side, not the tail side")
    }

    @Test
    fun `body renderer mesh consumes preview body polygon and gradient stops`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val baseLayer = trail.layers.single()
        val context = testContext(0.2f)
        val pulse = context.beamAlpha
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)

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
    fun `body renderer samples preview body fill alpha without hidden attenuation`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val baseLayer = trail.layers.single()
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            trail,
            testContext().copy(visibleLength = 420f, beamAlpha = 0.8f),
        )
        val polygon = mesh.polygon
        val stops = ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, 0.8f)

        assertEquals(previewBodyAlphaAt(stops, polygon[2].x, 420f), mesh.vertexAt(polygon[2]).color.alpha, 0.0001f)
        assertEquals(previewBodyAlphaAt(stops, polygon[6].x, 420f), mesh.vertexAt(polygon[6]).color.alpha, 0.0001f)
        assertTrue(mesh.vertexAt(polygon[2]).color.alpha > 0.35f)
    }

    @Test
    fun `body renderer keeps direct TypeScript layout scale`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            preset.trailEntities.single(),
            testContext().copy(visibleLength = 420f, beamAlpha = 0.8f),
        )
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val expectedPolygon = ASTDProjectileVfxLayout.bodyPolygon(widthBase, 420f, 0.8f)

        assertEquals(expectedPolygon[3].x, mesh.polygon[3].x, 0.0001f)
        assertEquals(expectedPolygon[3].y, mesh.polygon[3].y, 0.0001f)
    }

    @Test
    fun `body renderer converts TypeScript pixel geometry into world units at render boundary`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(visibleLength = 420f, beamAlpha = 0.8f, worldUnitsPerPixel = 0.5f)
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(preset.trailEntities.single(), context)
        val widthBase = ASTDProjectileVfxLayout.widthBase(preset.trailEntities.single().layers.single())
        val expectedPolygon = ASTDProjectileVfxLayout.bodyPolygon(widthBase, 420f, 0.8f)

        assertEquals(expectedPolygon[0].x * 0.5f, mesh.polygon[0].x, 0.0001f)
        assertEquals(expectedPolygon[3].y * 0.5f, mesh.polygon[3].y, 0.0001f)
        assertEquals(expectedPolygon[4].x * 0.5f, mesh.vertices[8].position.x, 0.0001f)
        assertEquals(expectedPolygon[4].y * 0.5f, mesh.vertices[8].position.y, 0.0001f)
    }

    @Test
    fun `body renderer does not add non TypeScript geometry around core polygon`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val context = testContext().copy(visibleLength = 160f, beamAlpha = 0.8f)
        val baseOnly = ASTDProjectileVfxLayout.bodyPolygon(
            ASTDProjectileVfxLayout.widthBase(trail.layers.single()),
            context.visibleLength,
            context.beamAlpha,
        )

        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context)

        assertEquals(baseOnly.maxOf { it.y }, mesh.vertices.maxOf { it.position.y }, 0.0001f)
        assertEquals(baseOnly.minOf { it.y }, mesh.vertices.minOf { it.position.y }, 0.0001f)
    }

    @Test
    fun `body renderer applies stable shader-like noise across the fill mesh`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val context = testContext().copy(visibleLength = 420f, beamAlpha = 0.8f, logicElapsed = 0.4f)
        val sameLogicFrame = context.copy(elapsed = 0.409f)
        val nextLogicFrame = context.copy(elapsed = 0.42f, logicElapsed = 0.4166667f)

        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context)
        val same = ASTDProjectileVfxBodyRenderer.meshForTests(trail, sameLogicFrame)
        val next = ASTDProjectileVfxBodyRenderer.meshForTests(trail, nextLogicFrame)
        val noiseVertices = mesh.vertices.drop(10).filter { it.color.alpha > 0.02f }
        val distinctBlue = noiseVertices.map { (it.color.blue * 10000f).toInt() }.distinct()

        assertTrue(mesh.vertices.size > 10)
        assertTrue(distinctBlue.size >= 4)
        assertEquals(mesh.vertices.drop(10).map { it.color }, same.vertices.drop(10).map { it.color })
        assertTrue(mesh.vertices.drop(10).zip(next.vertices.drop(10)).any { (a, b) -> kotlin.math.abs(a.color.blue - b.color.blue) > 0.001f })
    }

    @Test
    fun `body renderer keeps shadow blur implicit in the direct fill path`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val context = testContext().copy(visibleLength = 160f, beamAlpha = 0.8f)
        val baseOnly = ASTDProjectileVfxLayout.bodyPolygon(
            ASTDProjectileVfxLayout.widthBase(trail.layers.single()),
            context.visibleLength,
            context.beamAlpha,
        )
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context)
        val baseHalf = baseOnly.maxOf { kotlin.math.abs(it.y) }

        assertFalse(mesh.vertices.any { kotlin.math.abs(it.position.y) > baseHalf + 0.0001f })
    }

    @Test
    fun `body renderer emits separate soft shadow mesh for TypeScript shadow blur`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val context = testContext().copy(visibleLength = 160f, beamAlpha = 0.8f)
        val widthBase = ASTDProjectileVfxLayout.widthBase(trail.layers.single())
        val baseOnly = ASTDProjectileVfxLayout.bodyPolygon(widthBase, context.visibleLength, context.beamAlpha)
        val baseHalf = baseOnly.maxOf { kotlin.math.abs(it.y) }
        val shadowBlur = kotlin.math.max(8f, widthBase * 2.4f)

        val shadow = ASTDProjectileVfxBodyRenderer.shadowMeshForTests(trail, context)

        assertTrue(shadow.vertices.isNotEmpty())
        assertTrue(shadow.triangles.isNotEmpty())
        assertTrue(shadow.vertices.maxOf { kotlin.math.abs(it.position.y) } > baseHalf + shadowBlur * 0.5f)
        assertTrue(shadow.vertices.any { it.color.blue > it.color.red && it.color.alpha > 0.05f })
        assertTrue(shadow.vertices.any { it.color.alpha <= 0.0025f })
        assertTrue(shadow.vertices.maxOf { it.color.alpha } <= 0.16f)
        assertEquals("additive", shadow.blendMode)
    }

    @Test
    fun `body renderer does not widen runtime silhouette for bloom approximation`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val trail = preset.trailEntities.single()
        val context = testContext().copy(visibleLength = 420f, beamAlpha = 1f)
        val baseOnly = ASTDProjectileVfxLayout.bodyPolygon(
            ASTDProjectileVfxLayout.widthBase(trail.layers.single()),
            context.visibleLength,
            context.beamAlpha,
        )

        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(trail, context)
        val baseHalf = baseOnly.maxOf { kotlin.math.abs(it.y) }

        assertFalse(mesh.vertices.any { kotlin.math.abs(it.position.y) > baseHalf + 0.0001f })
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
        assertEquals(2, ASTDProjectileVfxBodyRenderManager.activeHandleCountForTests(engine.api))
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
        val snapshots = ASTDProjectileVfxBodyRenderManager.activeSnapshotsForTests(engine.api)
        val snapshot = snapshots.single { it.mesh.polygon.size == 9 }
        layer.delete()

        assertTrue(snapshots.all { kotlin.math.abs(it.location.x - secondContext.location.x) < 0.0001f })
        assertTrue(snapshots.all { kotlin.math.abs(it.location.y - secondContext.location.y) < 0.0001f })
        assertTrue(snapshots.all { kotlin.math.abs(it.facing - secondContext.renderFacing) < 0.0001f })
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

    private fun previewBodyAlphaAt(
        stops: List<ASTDProjectileVfxLayout.BodyGradientStop>,
        x: Float,
        visibleLength: Float,
    ): Float {
        val offset = ((x + visibleLength * 0.6f) / (visibleLength * 0.6f).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val left = stops.lastOrNull { it.offset <= offset } ?: stops.first()
        val right = stops.firstOrNull { it.offset >= offset } ?: stops.last()
        val t = ((offset - left.offset) / (right.offset - left.offset).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        return left.alpha + (right.alpha - left.alpha) * t
    }

    private fun curvedHistory(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 130f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 150f), 0f, 0f),
    )
}
