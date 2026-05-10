package cn.kasuminova.astd.renderer.projectile

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lwjgl.util.vector.Vector2f
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDProjectileVfxRuntimePluginTest {
    @BeforeTest
    fun clearRuntimeManager() {
        ASTDProjectileVfxRuntimeManager.clear()
    }

    @Test
    fun `plugin advance delegates to runtime manager advance`() {
        val engine = engineStub()
        val plugin = ASTDProjectileVfxRuntimePlugin()
        plugin.init(engine.api)

        ASTDProjectileVfxRuntimeManager.track(engine.api, projectileStub(), testPreset())
        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())

        engine.playing = false
        plugin.advance(0.5f, mutableListOf())

        assertEquals(0, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    @Test
    fun `plugin init clears stale runtime state for combat cleanup`() {
        val engine = engineStub()
        ASTDProjectileVfxRuntimeManager.track(engine.api, projectileStub(), testPreset())
        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())

        ASTDProjectileVfxRuntimePlugin().init(engine.api)

        assertEquals(0, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    @Test
    fun `plugin source does not scan battlefield projectile lists`() {
        val text = Files.readString(pluginSourcePath())

        assertFalse(text.contains(".projectiles"), "runtime plugin scans engine.projectiles")
        assertFalse(text.contains(".missiles"), "runtime plugin scans engine.missiles")
        assertFalse(text.contains("getProjectiles"), "runtime plugin scans getProjectiles")
        assertFalse(text.contains("getMissiles"), "runtime plugin scans getMissiles")
    }

    @Test
    fun `combat bootstrap installs runtime plugin through current combat init path`() {
        val text = Files.readString(combatBootstrapSourcePath())

        assertTrue(
            text.contains("ASTDProjectileVfxRuntimePlugin.ensureInstalled(engine)"),
            "combat bootstrap does not install ASTDProjectileVfxRuntimePlugin",
        )
    }

    private fun testPreset() = ASTDProjectileVfxPreset(
        id = "test_plugin",
        layers = listOf(ASTDProjectileVfxLayer.Trail("trail", 8f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 1f))),
        samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 0, 160f),
        fadePolicy = ASTDProjectileVfxFadePolicy(0f, 0.2f, 0.1f, 0.2f),
    )

    private class EngineStub(var playing: Boolean = true) {
        val customData: MutableMap<String, Any?> = HashMap()
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
                    "isEntityInPlay" -> state.playing
                    "isPaused" -> false
                    "addPlugin" -> {
                        state.customData["addedPlugin"] = args?.get(0)
                        null
                    }
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as CombatEngineAPI
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

    private fun pluginSourcePath(): Path =
        Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimePlugin.kt")

    private fun combatBootstrapSourcePath(): Path =
        Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/CombatVfxBootstrap.kt")

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