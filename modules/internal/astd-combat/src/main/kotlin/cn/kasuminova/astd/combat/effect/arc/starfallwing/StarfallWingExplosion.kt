package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.combat.DcrDamageAttribution
import cn.kasuminova.astd.impl.combat.ExplosionStrikeImpl
import cn.kasuminova.astd.impl.combat.LinearExplosionFalloffImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.loading.MissileSpecAPI
import org.lazywizard.lazylib.CollisionUtils
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.random.Random

internal object StarfallWingExplosion {
    fun explode(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        shot: StarfallWingShot,
        location: Vector2f,
        damage: Float,
        directTarget: CombatEntityAPI? = null,
    ) {
        val source = projectile.source
        val damageParam = source?.let { StarfallWingDamage(it.mutableStats) }
        val radius = shot.type.radius
        val falloff = LinearExplosionFalloffImpl(0f, 0f)
        for (victim in CombatUtils.getEntitiesWithinRange(location, radius)) {
            if (victim === directTarget || victim.owner == projectile.owner || victim.owner == 100) continue
            if (victim !is ShipAPI && victim !is MissileAPI) continue
            if (victim is ShipAPI && (victim.isHulk || victim.isPhased)) continue
            if (victim is MissileAPI && victim.isExpired) continue
            val ship = victim as? ShipAPI
            val covered = ship != null && ExplosionStrikeImpl.shieldCovers(ship, location)
            val damagePoint = when {
                ship == null -> Vector2f(location)
                covered -> ExplosionStrikeImpl.resolveDamagePoint(ship, location)
                else -> {
                    val surface = CollisionUtils.getNearestPointOnBounds(location, ship)
                    Vector2f(
                        surface.x + (ship.location.x - surface.x) * 0.05f,
                        surface.y + (ship.location.y - surface.y) * 0.05f,
                    )
                }
            }
            val surfaceDistance = (MathUtils.getDistance(location, victim.location) - victim.collisionRadius)
                .coerceAtLeast(0f)
            val victimDamage = falloff.damageFor(damage, surfaceDistance, radius)
            if (victimDamage <= 0f) continue
            engine.applyDamage(
                damageParam, victim, damagePoint, victimDamage, shot.type.damageType, 0f,
                ship != null && !covered, false, source, false,
            )
            DcrDamageAttribution.clarify(
                source, victim, shot.type.damageType, victimDamage, 0f, projectile.weapon?.displayName,
            )
        }
        Global.getSoundPlayer().playSound("astd_starfall_echo_final_explosion", 1f, 0.2f, location, directTarget?.velocity ?: Vector2f())
        scatterFlares(engine, projectile, shot.type, location, directTarget)
        StarfallWingVfx.explosion(engine, location, shot.type)
    }

    private fun scatterFlares(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        type: StarfallWingTuning.ShuttleType,
        location: Vector2f,
        directTarget: CombatEntityAPI?,
    ) {
        val color = StarfallWingVfx.color(type)
        val placement = directTarget?.let { flarePlacement(engine, projectile, it, location) }
        val flareSpec = Global.getSettings().getWeaponSpec("flarelauncher1").projectileSpec as MissileSpecAPI
        val origin = placement?.location(flareSpec.hullSpec.collisionRadius + 2f) ?: location
        val extraVelocity = directTarget?.velocity ?: Vector2f()
        repeat(Random.nextInt(3, 7)) {
            val facing = placement?.facing(Random.nextFloat()) ?: (Random.nextFloat() * 360f)
            val flare = engine.spawnProjectile(
                projectile.source, null, "flarelauncher1", Vector2f(origin), facing, Vector2f(extraVelocity),
            ) as? MissileAPI
            if (flare == null) {
                AstdLog.logger.error("星梭爆炸未生成热诱弹: spec=${projectile.projectileSpecId}, type=$type")
                return@repeat
            }
            flare.owner = projectile.owner
            flare.engineController.fadeToOtherColor(flare, color, null, 1f, 1f)
            // 原版染色每帧自动淡出，延长实例颜色的淡出时间以覆盖热诱弹整个寿命。
            flare.engineController.flameColorShifter.shift(flare, color, 0f, Float.MAX_VALUE, 1f)
            flare.destroyedExplosionColorOverride = color
        }
    }

    private fun flarePlacement(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        location: Vector2f,
    ): StarfallWingFlarePlacement {
        val placement = StarfallWingFlarePlacement(location, target.location, projectile.facing)
        includeObstacle(placement, target)
        if (target is ShipAPI) {
            val root = target.parentStation ?: target
            for (ship in engine.ships) {
                if (ship === target || ship.isPhased || ship.collisionClass == CollisionClass.NONE) continue
                if ((ship.parentStation ?: ship) === root) includeObstacle(placement, ship)
            }
        }
        return placement
    }

    private fun includeObstacle(placement: StarfallWingFlarePlacement, entity: CombatEntityAPI) {
        val bounds = entity.exactBounds
        if (bounds == null) {
            placement.includeCircle(entity.location, entity.collisionRadius)
        } else {
            bounds.update(entity.location, entity.facing)
            for (segment in bounds.segments) {
                placement.include(segment.p1)
                placement.include(segment.p2)
            }
        }
        val shield = entity.shield
        if (shield != null && shield.isOn) placement.includeCircle(shield.location, shield.radius)
    }
}
