package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeManager
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectileSpecOnFireDispatcherRuntimeTest {
    @BeforeTest
    fun clearRuntimeManager() {
        ASTDProjectileVfxRuntimeManager.clear()
    }

    @Test
    fun `dispatcher keeps missile AI injection path`() {
        val engine = engineStub()
        val missile = missileStub("astd_rct6_torp")
        val weapon = weaponStub()

        ProjectileSpecOnFireDispatcher().onFire(missile.api, weapon, engine.api)

        assertNotNull(missile.missileAI, "missile AI was not installed")
    }

    @Test
    fun `configured projectile tracks through runtime manager`() {
        val engine = engineStub()
        val projectile = projectileStub("astd_aod7_shot")
        val weapon = weaponStub()

        ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine.api)

        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    @Test
    fun `unconfigured projectile does not create runtime`() {
        val engine = engineStub()
        val projectile = projectileStub("astd_unconfigured_projectile")
        val weapon = weaponStub()

        ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine.api)

        assertEquals(0, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    @Test
    fun `duplicate onFire for same projectile tracks once`() {
        val engine = engineStub()
        val projectile = projectileStub("astd_aod7_shot")
        val weapon = weaponStub()
        val dispatcher = ProjectileSpecOnFireDispatcher()

        dispatcher.onFire(projectile, weapon, engine.api)
        dispatcher.onFire(projectile, weapon, engine.api)

        assertEquals(1, ASTDProjectileVfxRuntimeManager.trackedCountForTests())
    }

    @Test
    fun `dispatcher source does not reference old projectile renderers`() {
        val text = Files.readString(dispatcherSourcePath())

        assertFalse(text.contains("CodeProjectileRenderer"), "dispatcher still references CodeProjectileRenderer")
        assertFalse(text.contains("ProjectileVfxPresets"), "dispatcher still references ProjectileVfxPresets")
    }

    private class EngineStub {
        val customData: MutableMap<String, Any?> = HashMap()
        lateinit var api: CombatEngineAPI
    }

    private class MissileStub {
        val customData: MutableMap<String, Any?> = HashMap()
        var missileAI: MissileAIPlugin? = null
        lateinit var api: DamagingProjectileAPI
    }

    private fun engineStub(): EngineStub {
        val state = EngineStub()
        state.api = Proxy.newProxyInstance(
            CombatEngineAPI::class.java.classLoader,
            arrayOf(CombatEngineAPI::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getCustomData" -> state.customData
                    "isEntityInPlay" -> true
                    "isPaused" -> false
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as CombatEngineAPI
        return state
    }

    private fun projectileStub(projectileSpecId: String): DamagingProjectileAPI {
        val customData: MutableMap<String, Any?> = HashMap()
        return Proxy.newProxyInstance(
            DamagingProjectileAPI::class.java.classLoader,
            arrayOf(DamagingProjectileAPI::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getProjectileSpecId" -> projectileSpecId
                    "getCustomData" -> customData
                    "setCustomData" -> {
                        customData[args?.get(0) as String] = args[1]
                        null
                    }
                    "getLocation" -> Vector2f(10f, 20f)
                    "getFacing" -> 30f
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as DamagingProjectileAPI
    }

    private fun missileStub(projectileSpecId: String): MissileStub {
        val state = MissileStub()
        state.api = Proxy.newProxyInstance(
            MissileAPI::class.java.classLoader,
            arrayOf(MissileAPI::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getProjectileSpecId" -> projectileSpecId
                    "getCustomData" -> state.customData
                    "setCustomData" -> {
                        state.customData[args?.get(0) as String] = args[1]
                        null
                    }
                    "getLocation" -> Vector2f(10f, 20f)
                    "getFacing" -> 30f
                    "setMissileAI" -> {
                        state.missileAI = args?.get(0) as? MissileAIPlugin
                        null
                    }
                    "getMissileAI" -> state.missileAI
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as DamagingProjectileAPI
        return state
    }

    private fun weaponStub(): WeaponAPI {
        return Proxy.newProxyInstance(
            WeaponAPI::class.java.classLoader,
            arrayOf(WeaponAPI::class.java),
            InvocationHandler { _, method, _ -> defaultReturn(method.returnType) },
        ) as WeaponAPI
    }

    private fun dispatcherSourcePath(): Path =
        Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileSpecOnFireDispatcher.kt")

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
