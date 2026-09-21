package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 正电子冲击波 `.proj` 侧发射回调（规格 06 §2.1）：先委托 [ProjectileSpecOnFireDispatcher]
 * 完成弹体 VFX 追踪登记，再一次性结算难度三锚点并为每发弹体注册 [PositronShockwaveFuseScript] 引信脚本。
 *
 * 动机：难度取值调用点唯一——同一发弹体的锥角/锥长/伤害在其生命周期内恒定，
 * 不受战斗中调整 LunaLib 设置影响；下一发重新取值（与全局口径一致）。
 *
 * 挂载分工：`.proj` 的 onFireEffect 只挂本类（组合 VFX 分发 + 引信注册）。
 * 注意原版 WeaponSpecLoader 只在 projectile/missile specClass 下读取 onFireEffect 键，
 * `.wpn`（weapon specClass）上的同名键会被静默忽略——弹体 VFX 追踪不能放 `.wpn`。
 */
class PositronShockwaveOnFireEffect : OnFireEffectPlugin {

    private val vfxDispatcher = ProjectileSpecOnFireDispatcher()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        vfxDispatcher.onFire(projectile, weapon, engine)
        if (engine.isPaused) return
        val source = weapon.ship
        val spec = PositronShockwaveDifficulty.resolve(source)
        // 注册心跳（每场战斗一次）：证实 .proj onFireEffect 回调真实到达
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
                // 满射程引爆基准读武器面板（射程修正实时生效，与面板射程圈一致）
                maxRange = weapon.range,
                spawnLoc = Vector2f(projectile.location),
            ),
        )
    }

    private companion object {
        private val log = Global.getLogger(PositronShockwaveOnFireEffect::class.java)
    }
}
