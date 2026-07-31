package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectileSpecOnFireDispatcherRuntimeTest {
    @Test
    fun `configured projectile tracks through driver pipeline`() {
        val engine = engineStub()
        val projectile = projectileStub("astd_aod7_shot")
        val weapon = weaponStub()

        ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine.api)

        assertEquals(1, ProjectileVfxDriverPlugin.trackedCountForTests(engine.api))
    }

    @Test
    fun `unconfigured projectile does not create runtime`() {
        val engine = engineStub()
        val projectile = projectileStub("astd_unconfigured_projectile")
        val weapon = weaponStub()

        ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine.api)

        assertEquals(0, ProjectileVfxDriverPlugin.trackedCountForTests(engine.api))
    }

    @Test
    fun `duplicate onFire for same projectile tracks once`() {
        val engine = engineStub()
        // 同一弹体重复派发：新管线按弹体身份去重（driversByProjectile 以弹体为键），只登记一份。
        val projectile = projectileStub("astd_aod7_shot")
        val weapon = weaponStub()
        val dispatcher = ProjectileSpecOnFireDispatcher()

        dispatcher.onFire(projectile, weapon, engine.api)
        dispatcher.onFire(projectile, weapon, engine.api)

        assertEquals(1, ProjectileVfxDriverPlugin.trackedCountForTests(engine.api))
    }

    private class EngineStub {
        val customData: MutableMap<String, Any?> = HashMap()
        lateinit var api: CombatEngineAPI
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

    private fun weaponStub(): WeaponAPI {
        return Proxy.newProxyInstance(
            WeaponAPI::class.java.classLoader,
            arrayOf(WeaponAPI::class.java),
            InvocationHandler { _, method, _ -> defaultReturn(method.returnType) },
        ) as WeaponAPI
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
