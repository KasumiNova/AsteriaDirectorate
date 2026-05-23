package cn.kasuminova.astd.renderer.projectile

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lwjgl.util.vector.Vector2f
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDProjectileVfxRuntimeManagerTest {
    @Test
    fun `track creates one runtime and dedupes same projectile`() {
        val engine = engineStub()
        val projectile = projectileStub()
        val preset = testPreset()

        ASTDProjectileVfxRuntimeManager.clear()

        assertTrue(ASTDProjectileVfxRuntimeManager.track(engine.api, projectile, preset))
        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
        assertFalse(ASTDProjectileVfxRuntimeManager.track(engine.api, projectile, preset))
        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    @Test
    fun `advance progresses runtime and removes after projectile leaves engine`() {
        val engine = engineStub()
        val projectile = projectileStub()
        val preset = testPreset()

        ASTDProjectileVfxRuntimeManager.clear()
        ASTDProjectileVfxRuntimeManager.track(engine.api, projectile, preset)

        ASTDProjectileVfxRuntimeManager.advance(engine.api, 0.1f)
        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())

        engine.playing = false
        ASTDProjectileVfxRuntimeManager.advance(engine.api, 0.1f)
        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())

        ASTDProjectileVfxRuntimeManager.advance(engine.api, 0.25f)
        assertEquals(0, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    @Test
    fun `clear disposes all tracked runtimes`() {
        val engine = engineStub()
        ASTDProjectileVfxRuntimeManager.clear()

        ASTDProjectileVfxRuntimeManager.track(engine.api, projectileStub(), testPreset())
        ASTDProjectileVfxRuntimeManager.track(engine.api, projectileStub(), testPreset())
        assertEquals(2, ASTDProjectileVfxRuntimeManager.trackedCountForTests())

        ASTDProjectileVfxRuntimeManager.clear()

        assertEquals(0, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    private fun testPreset() = ASTDProjectileVfxPreset(
        id = "test_manager",
        components = emptyList(),
        samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 0, 160f),
        fadePolicy = ASTDProjectileVfxFadePolicy(0f, 0.2f, 0.1f, 0.2f),
    )

    private class EngineStub(var playing: Boolean = true) {
        lateinit var api: CombatEngineAPI
    }

    private fun engineStub(): EngineStub {
        val state = EngineStub()
        val proxy = Proxy.newProxyInstance(
            CombatEngineAPI::class.java.classLoader,
            arrayOf(CombatEngineAPI::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "isEntityInPlay" -> state.playing
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as CombatEngineAPI
        state.api = proxy
        return state
    }

    private fun projectileStub(): DamagingProjectileAPI {
        return Proxy.newProxyInstance(
            DamagingProjectileAPI::class.java.classLoader,
            arrayOf(DamagingProjectileAPI::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getLocation" -> Vector2f(10f, 20f)
                    "getFacing" -> 30f
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as DamagingProjectileAPI
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
