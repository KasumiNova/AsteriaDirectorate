package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.combat.entities.Ship
import com.fs.starfarer.combat.systems.Weapon
import com.fs.starfarer.loading.specs.ProjectileFactory
import com.fs.starfarer.loading.specs.ProjectileSpec
import org.lwjgl.util.vector.Vector2f
import kotlin.math.PI
import kotlin.random.Random

class StarfallWingOnFireEffect : OnFireEffectPlugin {
    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        val source = weapon.ship ?: projectile.source
        if (source == null) {
            AstdLog.logger.error("坠星残翼发射源缺失: weapon=${weapon.id}")
            engine.removeEntity(projectile)
            return
        }
        if (!source.hasListenerOfClass(StarfallWingHitListener::class.java)) {
            source.addListener(StarfallWingHitListener())
        }
        val baseSpeed = projectile.moveSpeed
        val baseDamage = projectile.baseDamageAmount
        val spent = ImaginaryCharge.of(source).consumeFraction(StarfallWingTuning.CHARGE_FRACTION)
        val isPlayer = source.owner == 0
        val power = StarfallWingTuning.power(
            Random.nextFloat(), spent,
            DifficultyTuningImpl.valueFor(StarfallWingTuning.POWER_MIN, isPlayer),
            DifficultyTuningImpl.valueFor(StarfallWingTuning.POWER_MAX, isPlayer),
            DifficultyTuningImpl.valueFor(StarfallWingTuning.CHARGE_CONVERSION, isPlayer),
        )
        val type = StarfallWingTuning.ShuttleType.entries.random()
        val damage = baseDamage * type.damageFactor * power *
            DifficultyTuningImpl.valueFor(StarfallWingTuning.DAMAGE_FACTOR, isPlayer)
        val sizeFactor = StarfallWingTuning.sizeFactor(power)
        val speedFactor = StarfallWingTuning.speedFactor(power)
        val carrier = Global.getSettings().getWeaponSpec(StarfallWingTuning.SHUTTLE_LAUNCHER_ID)
            .projectileSpec as ProjectileSpec
        val shotSpec = carrier.clone()
        shotSpec.setMoveSpeed(projectile.projectileSpec.getMoveSpeed(null, null) * speedFactor)
        val spawned = ProjectileFactory.spawnBallisticProjectile(
            source as Ship, weapon as Weapon, shotSpec,
            Vector2f(projectile.location), projectile.facing, Vector2f(),
        )
        engine.removeEntity(projectile)
        spawned.damage.type = type.damageType
        spawned.setDamageAmount(damage)
        spawned.damage.fluxComponent = if (type == StarfallWingTuning.ShuttleType.BLUE) damage * 2f else 0f
        spawned.collisionRadius *= sizeFactor
        // 原版纵向碰撞段已随弹速变化，横向宽度单独按威力缩放。
        spawned.exactBounds?.let { bounds ->
            val segments = bounds.origSegments.map { Vector2f(it.p1) to Vector2f(it.p2) }
            bounds.clear()
            for ((a, b) in segments) bounds.addSegment(a.x, a.y * sizeFactor, b.x, b.y * sizeFactor)
            bounds.update(spawned.location, spawned.facing)
        }
        val state = StarfallWingShot(
            type, power, baseSpeed * speedFactor, weapon.range, Random.nextFloat() * 2f * PI.toFloat(),
        )
        spawned.setCustomData(StarfallWingTuning.SHOT_STATE_KEY, state)
        spawned.setCustomData(StarfallWingTuning.VISUAL_SCALE_KEY, sizeFactor)
        spawned.setCustomData(StarfallWingTuning.BASE_SPEED_KEY, baseSpeed)
        StarfallWingGuidance.track(engine, spawned, state)
        ProjectileVfxDriverPlugin.track(engine, spawned, type.vfxId)
    }
}
