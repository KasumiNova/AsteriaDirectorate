package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.api.combat.CuifengTorpedoStrike
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

class CuifengTorpedoOnHitEffect : OnHitEffectPlugin {

    private val strike: CuifengTorpedoStrike = CuifengTorpedoStrikeImpl

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return
        val hitPoint = point ?: projectile.location ?: return
        strike.strike(engine, projectile, target, hitPoint, shieldHit)
    }
}
