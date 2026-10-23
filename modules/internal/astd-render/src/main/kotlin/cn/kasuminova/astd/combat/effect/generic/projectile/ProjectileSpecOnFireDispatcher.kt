package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

class ProjectileSpecOnFireDispatcher : OnFireEffectPlugin {

    private companion object {
        private val log = Global.getLogger(ProjectileSpecOnFireDispatcher::class.java)
    }

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (ProjectileVfxDispatchState.isMarked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_MARK)) return
        if (ProjectileVfxDispatchState.isLocked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)) return

        ProjectileVfxDispatchState.lock(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)
        var tracked = false
        try {
            if (engine.customData[ProjectileVfxKeys.ENGINE_LOG_ONFIRE_ONCE] != true) {
                engine.customData[ProjectileVfxKeys.ENGINE_LOG_ONFIRE_ONCE] = true
                log.info("[ASTD] ProjectileSpecOnFireDispatcher.onFire invoked")
            }

            val projId = projectile.projectileSpecId
            if (!projId.isNullOrBlank()) {
                tracked = ProjectileVfxDriverPlugin.track(engine, projectile, projId)
                val trackLogKey = ProjectileVfxKeys.ENGINE_LOG_TRACK_ONCE_PREFIX + projId
                if (engine.customData[trackLogKey] != true) {
                    engine.customData[trackLogKey] = true
                    log.info(
                        "[ASTD] onFire track: spec=$projId tracked=$tracked " +
                                "specRegistered=${cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxRegistry.has(projId)}",
                    )
                }
            }
        } finally {
            ProjectileVfxDispatchState.unlock(projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)
            if (tracked) {
                ProjectileVfxDispatchState.mark(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_MARK)
            }
        }
    }
}
