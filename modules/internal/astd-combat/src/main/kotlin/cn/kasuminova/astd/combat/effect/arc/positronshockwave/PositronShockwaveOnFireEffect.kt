package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f

// 原版只在 projectile/missile specClass 读取 onFireEffect 键，.wpn 上的同名键会被静默忽略。
class PositronShockwaveOnFireEffect : OnFireEffectPlugin {

    private val vfxDispatcher = ProjectileSpecOnFireDispatcher()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        vfxDispatcher.onFire(projectile, weapon, engine)
        if (engine.isPaused) return
        val source = weapon.ship
        val spec = PositronShockwaveDifficulty.resolve(source)
        if (engine.customData[PositronShockwaveFuseScript.TELEMETRY_ONFIRE_LOGGED] != true) {
            engine.customData[PositronShockwaveFuseScript.TELEMETRY_ONFIRE_LOGGED] = true
            log.info(
                "正电子冲击波 onFire 注册引信：projSpec=${projectile.projectileSpecId}, " +
                        "weaponRange=${weapon.range}, coneRange=${spec.range}, damage=${spec.damage}",
            )
        }
        engine.addPlugin(
            PositronShockwaveFuseScript(
                projectile = projectile,
                source = source,
                spec = spec,
                maxRange = weapon.range,
                spawnLoc = Vector2f(projectile.location),
            ),
        )
    }

    private companion object {
        private val log = Global.getLogger(PositronShockwaveOnFireEffect::class.java)
    }
}
