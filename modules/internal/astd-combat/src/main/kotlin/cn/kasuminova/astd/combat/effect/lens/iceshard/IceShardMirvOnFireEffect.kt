package cn.kasuminova.astd.combat.effect.lens.iceshard

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

class IceShardMirvOnFireEffect : OnFireEffectPlugin {

    private val log = Global.getLogger(IceShardMirvOnFireEffect::class.java)

    private val vfxDispatcher = ProjectileSpecOnFireDispatcher()

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        vfxDispatcher.onFire(projectile, weapon, engine)

        val missile = projectile as? MissileAPI
        if (missile == null) {
            log.warn("源生冰晶 onFire 拿到非导弹实体（spec=${projectile.projectileSpecId}），属配置错误，分裂引信未注册")
            return
        }
        engine.addPlugin(IceShardMirvSplitScript(missile, weapon.ship))
        engine.customData[TELEMETRY_FUSES_REGISTERED] =
            (engine.customData[TELEMETRY_FUSES_REGISTERED] as? Int ?: 0) + 1
    }

    companion object {
        const val TELEMETRY_FUSES_REGISTERED = "astd_ice_shard_mirv_fuses_registered"
    }
}
