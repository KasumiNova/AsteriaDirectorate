package cn.kasuminova.astd.combat.effect.generic

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher as Impl

class ProjectileSpecOnFireDispatcher : OnFireEffectPlugin {
    private val impl = Impl()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        impl.onFire(projectile, weapon, engine)
    }
}
