package cn.kasuminova.astd.combat.effect.arc.piercinglance

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

class PiercingLanceOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        val hitPoint = point ?: projectile.location ?: return

        val spec = PiercingLanceConeStrike.buildConeSpec(projectile, target, hitPoint) ?: return
        PiercingLanceConeStrike.resolve(engine, spec, target)
    }
}
