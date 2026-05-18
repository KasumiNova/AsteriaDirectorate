package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderManager
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxShaderRenderer
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ASTDProjectileVfxBodyRenderManagerTest {
    @Test
    fun `ensure installs one renderer in engine custom data`() {
        val engine = engineStub()

        val first = ASTDProjectileVfxBodyRenderManager.ensure(engine.api)
        val second = ASTDProjectileVfxBodyRenderManager.ensure(engine.api)

        assertSame(first, second)
        assertEquals(1, engine.addedLayeredRenderingPlugins.size)
    }

    @Test
    fun `handle update stores copied location facing and mesh snapshot`() {
        val engine = engineStub()
        val handle = ASTDProjectileVfxBodyRenderManager.createHandle(engine.api)
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.trailEntities.single(),
            testContext().copy(visibleLength = 120f),
        )
        val location = Vector2f(12f, 34f)

        handle.update(location, 45f, mesh)
        location.set(99f, 88f)
        val snapshot = ASTDProjectileVfxBodyRenderManager.activeSnapshotsForTests(engine.api).single()

        assertEquals(12f, snapshot.location.x, 0.0001f)
        assertEquals(34f, snapshot.location.y, 0.0001f)
        assertEquals(45f, snapshot.facing, 0.0001f)
        assertSame(mesh, snapshot.mesh)
    }

    @Test
    fun `handle delete removes active body snapshot`() {
        val engine = engineStub()
        val handle = ASTDProjectileVfxBodyRenderManager.createHandle(engine.api)
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.trailEntities.single(),
            testContext(),
        )

        handle.update(Vector2f(1f, 2f), 0f, mesh)
        handle.delete()

        assertEquals(0, ASTDProjectileVfxBodyRenderManager.activeHandleCountForTests(engine.api))
    }

    @Test
    fun `renderer active layers follow active mesh combat layers`() {
        val engine = engineStub()
        val renderer = ASTDProjectileVfxBodyRenderManager.ensure(engine.api)
        val handle = ASTDProjectileVfxBodyRenderManager.createHandle(engine.api)
        val mesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.trailEntities.single(),
            testContext(),
        ).copy(combatLayer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

        handle.update(Vector2f(1f, 2f), 0f, mesh)

        assertEquals(setOf(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER), renderer.activeLayers)
    }

    @Test
    fun `renderer snapshots for layer uses a copied filtered snapshot list`() {
        val engine = engineStub()
        val renderer = ASTDProjectileVfxBodyRenderManager.ensure(engine.api)
        val first = ASTDProjectileVfxBodyRenderManager.createHandle(engine.api)
        val second = ASTDProjectileVfxBodyRenderManager.createHandle(engine.api)
        val baseMesh = ASTDProjectileVfxBodyRenderer.meshForTests(
            ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.trailEntities.single(),
            testContext(),
        )
        val foregroundMesh = baseMesh.copy(combatLayer = CombatEngineLayers.ABOVE_PARTICLES)
        val shipMesh = baseMesh.copy(combatLayer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

        first.update(Vector2f(1f, 2f), 0f, foregroundMesh)
        second.update(Vector2f(3f, 4f), 0f, shipMesh)
        val filtered = renderer.snapshotsForLayerForTests(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        second.delete()

        assertEquals(1, filtered.size)
        assertEquals(shipMesh, filtered.single().mesh)
        assertEquals(0, renderer.snapshotsForLayerForTests(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER).size)
    }

    @Test
    fun `transform local point rotates around origin and adds world location`() {
        val origin = Vector2f(10f, 20f)

        val facing0 = ASTDProjectileVfxBodyRenderManager.transformLocalPointForTests(Vector2f(5f, -2f), origin, 0f)
        val facing90 = ASTDProjectileVfxBodyRenderManager.transformLocalPointForTests(Vector2f(5f, -2f), origin, 90f)

        assertEquals(15f, facing0.x, 0.0001f)
        assertEquals(18f, facing0.y, 0.0001f)
        assertEquals(12f, facing90.x, 0.0001f)
        assertEquals(25f, facing90.y, 0.0001f)
    }

    @Test
    fun `shader snapshots use canvas lighter compatible source alpha additive blend`() {
        assertEquals(
            ASTDProjectileVfxBodyRenderManager.BlendState(
                sourceFactor = ASTDProjectileVfxBodyRenderManager.BlendFactor.SrcAlpha,
                destinationFactor = ASTDProjectileVfxBodyRenderManager.BlendFactor.One,
            ),
            ASTDProjectileVfxBodyRenderManager.blendStateForTests(
                ASTDProjectileVfxBodyRenderer.Mesh(
                    polygon = emptyList(),
                    gradientStops = emptyList(),
                    vertices = emptyList(),
                    triangles = emptyList(),
                    blendMode = "additive",
                    combatLayer = CombatEngineLayers.ABOVE_PARTICLES,
                    shaderQuad = ASTDProjectileVfxShaderRenderer.bodyQuadForTests(
                        ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!.trailEntities.single(),
                        testContext(),
                    ),
                ),
            ),
        )
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
