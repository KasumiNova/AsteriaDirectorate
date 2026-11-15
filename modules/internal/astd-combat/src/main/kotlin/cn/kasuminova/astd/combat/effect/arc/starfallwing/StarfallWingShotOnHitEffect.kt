package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.impl.combat.DcrDamageAttribution
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.combat.LinearExplosionFalloffImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

internal class StarfallWingHitListener : DamageDealtModifier {
    override fun modifyDamageDealt(
        param: Any?,
        target: CombatEntityAPI,
        damage: DamageAPI,
        point: Vector2f?,
        shieldHit: Boolean,
    ): String? {
        when (param) {
            is StarfallWingDamage -> damage.stats = param.sourceStats
            is DamagingProjectileAPI -> {
                val shot = param.customData[StarfallWingTuning.SHOT_STATE_KEY] as? StarfallWingShot
                    ?: return null
                shot.liveTargetHit = target is ShipAPI && target.owner != param.owner && target.owner != 100 &&
                    target.isAlive && !target.isHulk
            }
        }
        return null
    }
}

class StarfallWingShotOnHitEffect : OnHitEffectPlugin {
    private companion object {
        val HARD_FLUX_TEXT_COLOR = Color(200, 130, 255)
    }
    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI?,
        point: Vector2f?,
        shieldHit: Boolean,
        impact: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val shot = projectile.customData[StarfallWingTuning.SHOT_STATE_KEY] as? StarfallWingShot
        if (shot == null) {
            AstdLog.logger.error("星梭命中缺少发射状态: spec=${projectile.projectileSpecId}")
            return
        }
        if (target is MissileAPI) return
        if (!shot.claimHit()) return
        val source = projectile.source
        if (shot.type != StarfallWingTuning.ShuttleType.BLUE) {
            DcrDamageAttribution.clarify(
                source, target, shot.type.damageType, projectile.damageAmount, projectile.damage.computeFluxDealt(1f),
                projectile.weapon?.displayName,
            )
        }
        if (source != null && shot.liveTargetHit) {
            ImaginaryCharge.peek(source)?.add(DifficultyTuningImpl.valueFor(StarfallWingTuning.HIT_GAIN, source.owner == 0))
        }
        val location = point ?: projectile.location
        if (shot.type == StarfallWingTuning.ShuttleType.BLUE && target is ShipAPI) {
            StarfallWingEmp.strike(engine, projectile, shot, target, location, shieldHit)
        }
        val damageParam = source?.let { StarfallWingDamage(it.mutableStats) }
        if (source != null && target is ShipAPI && target.isAlive && !target.isHulk && !target.isPhased &&
            target.owner != projectile.owner && target.owner != 100
        ) {
            when (shot.type) {
                StarfallWingTuning.ShuttleType.RED -> {
                    val bonus = target.maxHitpoints *
                        DifficultyTuningImpl.valueFor(StarfallWingTuning.RED_HULL_DAMAGE, source.owner == 0) * shot.power
                    val damagePoint = if (shieldHit) ExplosionStrikeImpl.resolveDamagePoint(target, location) else location
                    engine.applyDamage(
                        damageParam, target, damagePoint, bonus, DamageType.HIGH_EXPLOSIVE, 0f,
                        !shieldHit, false, source, true,
                    )
                    DcrDamageAttribution.clarify(
                        source, target, DamageType.HIGH_EXPLOSIVE, bonus, 0f, projectile.weapon?.displayName,
                    )
                }
                StarfallWingTuning.ShuttleType.GOLD -> if (shieldHit) {
                    val tracker = target.fluxTracker
                    val extra = tracker.maxFlux *
                        DifficultyTuningImpl.valueFor(StarfallWingTuning.GOLD_HARD_FLUX, source.owner == 0) * shot.power
                    // 原生直击可能先触发过载，此时 increaseFlux 拒绝写入，必须直接增加硬辐能。
                    if (!tracker.increaseFlux(extra, true)) {
                        tracker.currFlux = minOf(tracker.maxFlux, tracker.currFlux + extra)
                        tracker.hardFlux = minOf(tracker.maxFlux, tracker.hardFlux + extra)
                    }
                    engine.addFloatingDamageText(Vector2f(location), extra, HARD_FLUX_TEXT_COLOR, target, source)
                }
                else -> Unit
            }
        }
        val radius = shot.type.radius
        val damage = projectile.damageAmount
        val falloff = LinearExplosionFalloffImpl(0f, 0f)
        for (victim in CombatUtils.getEntitiesWithinRange(location, radius)) {
            if (victim === target || victim.owner == projectile.owner || victim.owner == 100) continue
            if (victim !is ShipAPI && victim !is MissileAPI) continue
            if (victim is ShipAPI && (victim.isHulk || victim.isPhased)) continue
            if (victim is MissileAPI && victim.isExpired) continue
            val ship = victim as? ShipAPI
            val covered = ship != null && ExplosionStrikeImpl.shieldCovers(ship, location)
            val damagePoint = ship?.let { ExplosionStrikeImpl.resolveDamagePoint(it, location) }
                ?: Vector2f(location)
            val surfaceDistance = (MathUtils.getDistance(location, victim.location) - victim.collisionRadius)
                .coerceAtLeast(0f)
            val victimDamage = falloff.damageFor(damage, surfaceDistance, radius)
            if (victimDamage <= 0f) continue
            engine.applyDamage(
                damageParam, victim, damagePoint, victimDamage, shot.type.damageType, 0f,
                ship != null && !covered, false, source, true,
            )
            DcrDamageAttribution.clarify(
                source, victim, shot.type.damageType, victimDamage, 0f, projectile.weapon?.displayName,
            )
        }
        StarfallWingVfx.explosion(engine, location, shot.type)
    }
}
