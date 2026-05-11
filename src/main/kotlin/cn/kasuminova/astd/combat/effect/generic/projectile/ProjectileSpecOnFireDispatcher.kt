package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.combat.effect.arc.omega.DrvOmegaSlugInstantOnSpawn
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeManager
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 一个“通用 onFireEffect”：
 *
 * - 推荐：把 `.proj` 的 onFireEffect 指向这个类（以 projectileSpecId 为中心驱动效果）。
 * - 兼容：如果 `.wpn` 也配置了同样的 onFireEffect，本类会用 projectile.customData 去重，避免重复触发。
 * - 真实要执行的 VFX 由 [ProjectileVfxRegistry] 按 projectileSpecId（也就是 .proj 的 id）分发。
 */
class ProjectileSpecOnFireDispatcher : OnFireEffectPlugin {

    private companion object {
        private val log = Global.getLogger(ProjectileSpecOnFireDispatcher::class.java)
    }

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        ProjectileMissileAiInjector.ensureInstalled(engine, projectile)

        if (ProjectileVfxDispatchState.isMarked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_MARK)) return
        if (ProjectileVfxDispatchState.isLocked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)) return
        if (ProjectileVfxDispatchState.isLocked(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_SCAN_LOCK)) return

        ProjectileVfxDispatchState.lock(engine, projectile, ProjectileVfxKeys.PROJECTILE_VFX_ONFIRE_LOCK)
        var tracked = false
        try {
            if (engine.customData[ProjectileVfxKeys.ENGINE_LOG_ONFIRE_ONCE] != true) {
                engine.customData[ProjectileVfxKeys.ENGINE_LOG_ONFIRE_ONCE] = true
                log.info("[ASTD] ProjectileSpecOnFireDispatcher.onFire invoked")
            }

            ProjectileVfxRegistry.ensureLoaded()

            val projId = projectile.projectileSpecId
            if (!projId.isNullOrBlank()) {
                if (projId == "astd_drv_omega_slug") {
                    DrvOmegaSlugInstantOnSpawn.onSpawn(engine, projectile, weapon)
                }

                val preset = ProjectileVfxRegistry.presetFor(projId)
                if (preset != null) {
                    tracked = ASTDProjectileVfxRuntimeManager.track(engine, projectile, preset)
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
