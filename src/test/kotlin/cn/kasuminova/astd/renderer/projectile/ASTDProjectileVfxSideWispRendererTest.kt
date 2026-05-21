package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderManager
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxSideWispRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxSideWispRenderer
import com.fs.starfarer.api.combat.CombatEngineAPI
import kotlin.math.abs
import org.lwjgl.util.vector.Vector2f
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxSideWispRendererTest {
    @Test
    fun `side wisp renderer follows curved projectile history with sampled path`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val context = testContext().copy(
            location = Vector2f(200f, 120f),
            renderFacing = 0f,
            visibleLength = 40f,
            beamAlpha = 1f,
            historyNodes = curvedHistory(),
        )

        val mesh = ASTDProjectileVfxSideWispRenderer.meshesForTests(
            preset.trailEntities.single(),
            preset.sideWispLayers,
            context,
        ).first()

        assertTrue(mesh.vertices.size > 6, "curved side wisp should use sampled centerline path instead of three-node chord")
        assertTrue(mesh.vertices.maxOf { it.position.y } - mesh.vertices.minOf { it.position.y } > 2f, "side wisp should bend along projectile history")
        assertTrue(mesh.vertices.all { it.position.x in -45f..8f }, "side wisp mesh should remain local")
    }

    @Test
    fun `side wisp renderer creates one local three-node path per offset`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val paths = ASTDProjectileVfxSideWispRenderer.localPathsForTests(preset.sideWispLayers.single(), 420f, 10f)

        assertEquals(4, paths.size)
        assertTrue(paths.all { it.size == 3 })
        assertEquals(-420f * 0.64f, paths[0][0].x)
        assertEquals(-21f, paths[0][0].y)
    }

    @Test
    fun `side wisp renderer rotates offsets with projectile transform`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val world = ASTDProjectileVfxSideWispRenderer.worldPathForTests(preset.sideWispLayers.single(), testContext().copy(renderFacing = 90f), 420f, 10f).first()

        assertTrue(abs(world[0].x - 31f) < 0.01f)
        assertTrue(abs(world[0].y - (20f - 268.8f)) < 0.01f)
    }

    @Test
    fun `side wisp mesh fades out before the projectile head`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val mesh = ASTDProjectileVfxSideWispRenderer.meshesForTests(
            preset.trailEntities.single(),
            preset.sideWispLayers,
            testContext().copy(visibleLength = 420f, beamAlpha = 1f),
        ).first()
        val headEnd = mesh.vertices.maxBy { it.position.x }

        assertEquals(ASTDProjectileVfxBodyRenderer.RENDER_ORDER_SIDE_WISP, mesh.renderOrder)
        assertTrue(headEnd.position.x < 0f)
        assertTrue(headEnd.color.alpha <= 0.0001f)
        assertTrue(mesh.vertices.any { it.color.alpha > 0.1f })
    }

    @Test
    fun `side wisp runtime uses body render manager so it can sort behind head`() {
        val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
        val layer = ASTDProjectileVfxSideWispRenderLayer(preset.trailEntities.single(), preset.sideWispLayers)
        val engine = engineStub()

        assertTrue(layer.create(engine.api, testContext()))
        val snapshots = ASTDProjectileVfxBodyRenderManager.activeSnapshotsForTests(engine.api)
        layer.delete()

        assertEquals(4, snapshots.size)
        assertTrue(snapshots.all { it.mesh.renderOrder == ASTDProjectileVfxBodyRenderer.RENDER_ORDER_SIDE_WISP })
        assertEquals(0, ASTDProjectileVfxBodyRenderManager.activeHandleCountForTests(engine.api))
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

    private fun curvedHistory(): List<ASTDProjectileHistoryNode> = listOf(
        ASTDProjectileHistoryNode(Vector2f(200f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(190f, 120f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(180f, 130f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(170f, 150f), 0f, 0f),
    )
}
