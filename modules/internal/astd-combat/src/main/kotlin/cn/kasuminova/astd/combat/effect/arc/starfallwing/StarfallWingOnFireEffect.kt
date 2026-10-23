package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.util.IdentityHashMap

class StarfallWingOnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        projectileStates(engine)[projectile] = ProjectileState(
            ownerWeapon = weapon, lastPierceLocation = Vector2f(projectile.location),
        )
        ProjectileVfxDriverPlugin.track(engine, projectile, StarfallWingTuning.SHOT_SPEC_ID)
    }

    class ProjectileState(
        val ownerWeapon: WeaponAPI,
        var pierceTimer: Float = 0f,
        var moteTimer: Float = 0f,
        var lastPierceLocation: Vector2f? = null,
        val passContacts: PiercePassTracker = PiercePassTracker(),
    )

    companion object {
        private const val KEY_PROJECTILE_STATES = "astd_starfall_wing_projectile_states"

        @Suppress("UNCHECKED_CAST")
        fun projectileStates(engine: CombatEngineAPI): IdentityHashMap<DamagingProjectileAPI, ProjectileState> {
            return engine.customData.getOrPut(KEY_PROJECTILE_STATES) {
                IdentityHashMap<DamagingProjectileAPI, ProjectileState>()
            } as IdentityHashMap<DamagingProjectileAPI, ProjectileState>
        }
    }
}
