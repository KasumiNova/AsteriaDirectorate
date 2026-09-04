package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher as Impl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 兼容旧类路径：将 onFireEffect 代理到新实现。
 *
 * 当前实现位于：
 * cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
 */
class ProjectileSpecOnFireDispatcher : OnFireEffectPlugin {
    private val impl = Impl()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        impl.onFire(projectile, weapon, engine)
    }
}
