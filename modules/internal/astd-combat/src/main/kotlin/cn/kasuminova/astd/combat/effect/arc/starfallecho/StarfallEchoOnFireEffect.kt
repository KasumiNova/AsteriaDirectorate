package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

class StarfallEchoOnFireEffect : OnFireEffectPlugin {
    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        val source = weapon.ship
        val finalShot = StarfallEchoTuning.isFinalShot(weapon.isInBurst, weapon.burstFireTimeRemaining)
        if (finalShot) {
            projectile.setCustomData(FINAL_SHOT_MARK_KEY, true)
            projectile.setDamageAmount(projectile.damage.baseDamage * StarfallEchoTuning.FINAL_DAMAGE_MULT)
            projectile.collisionRadius *= StarfallEchoTuning.FINAL_SIZE_MULT
            source.fluxTracker.increaseFlux(StarfallEchoTuning.FINAL_FLUX_EXTRA, false)
        }
        val sound = if (finalShot) "astd_starfall_echo_final_fire" else "astd_starfall_echo_fire"
        Global.getSoundPlayer().playSound(sound, 1f, 1f, projectile.location, source.velocity)
        ProjectileVfxDriverPlugin.track(engine, projectile, if (finalShot) SPEC_ID_FINAL else SPEC_ID_NORMAL)
    }

    companion object {
        const val WEAPON_ID = "astd_starfall_echo"
        const val SPEC_ID_NORMAL = "astd_starfall_echo_shot"
        const val SPEC_ID_FINAL = "astd_starfall_echo_shot_final"
        const val FINAL_SHOT_MARK_KEY = "astd_starfall_echo_final_shot"
    }
}
