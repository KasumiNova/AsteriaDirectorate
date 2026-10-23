package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin

class PositronShockwaveOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        if (target !is ShipAPI || target.isFighter || target.isDrone || target.isHulk) return

        val loc = point ?: projectile.location ?: return

        val vel = projectile.velocity
        val dir = if (vel.lengthSquared() > 1e-3f) {
            Vector2f(vel).also { it.normalise() }
        } else {
            if (!warnedZeroVelocity) {
                warnedZeroVelocity = true
                log.warn("正电子冲击波撞舰引爆时弹体速度近零（|v|²=${vel.lengthSquared()}），锥轴退化为弹体朝向（一次性告警）")
            }
            val rad = Math.toRadians(projectile.facing.toDouble())
            Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
        }

        val source = projectile.source
        val spec = PositronShockwaveDifficulty.resolve(source)
        PositronShockwaveFuseScript.detonate(
            engine, projectile, loc, dir, source, spec,
            fuseOwner = source?.owner ?: projectile.owner,
            detonateTelemetryKey = PositronShockwaveFuseScript.TELEMETRY_DETONATE_IMPACT,
        )
    }

    companion object {
        private val log = Global.getLogger(PositronShockwaveOnHitEffect::class.java)

        @Volatile
        private var warnedZeroVelocity = false
    }
}
