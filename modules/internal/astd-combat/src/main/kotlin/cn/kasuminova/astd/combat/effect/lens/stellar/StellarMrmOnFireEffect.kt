package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

class StellarMrmOnFireEffect : OnFireEffectPlugin {

    private val log = Global.getLogger(StellarMrmOnFireEffect::class.java)

    private val vfxDispatcher = ProjectileSpecOnFireDispatcher()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        vfxDispatcher.onFire(projectile, weapon, engine)

        val missile = projectile as? MissileAPI
        if (missile == null) {
            log.warn("辉星 onFire 拿到非导弹实体（spec=${projectile.projectileSpecId}），属配置错误，AI 未安装")
            return
        }
        missile.missileAI = StellarMrmMissileAI(missile, StellarMrmTargetingImpl)
    }
}
