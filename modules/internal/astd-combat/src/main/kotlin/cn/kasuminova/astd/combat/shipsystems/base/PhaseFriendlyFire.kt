package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

internal object PhaseFriendlyFire {
    fun transferredLoss(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        forecast: PhaseThreat.Forecast,
    ): Float {
        val hitsByFriendly = java.util.IdentityHashMap<ShipAPI, MutableList<PhaseThreat.Hit>>()
        for (impact in forecast.impacts) {
            val hit = impact.hit
            val projectile = hit.key as? DamagingProjectileAPI ?: continue
            if (hit.stage != PhaseThreat.Stage.DIRECT || hit.friendly || projectile.owner == ship.owner ||
                hit.time !in 0f..1.2f || projectile.isExpired || projectile.explosionSpecIfExplosion != null
            ) continue
            val end = minOf(1.5f, PhaseThreat.remainingLife(projectile, 1.5f))
            if (end <= hit.time) continue
            val start = PhaseThreat.Point(projectile.location.x, projectile.location.y)
            val velocity = PhaseThreat.Point(projectile.velocity.x, projectile.velocity.y)
            var bestFriendly: ShipAPI? = null
            var bestHit: PhaseThreat.Hit? = null
            var bestTime = Float.POSITIVE_INFINITY
            for (friendly in engine.ships) {
                if (friendly === ship || friendly.owner != ship.owner || !friendly.isAlive ||
                    friendly.isHulk || friendly.isFighter || friendly.isPhased
                ) {
                    continue
                }
                val motion = PhaseThreat.Motion(friendly)
                val hitTime = PhaseThreat.intercept(
                    start, velocity, motion, friendly.collisionRadius + projectile.collisionRadius, hit.time, end,
                ) ?: continue
                if (hitTime >= bestTime) continue
                val impactPoint = motion.currentFrame(start + velocity * hitTime, hitTime)
                val point = Vector2f(impactPoint.x, impactPoint.y)
                bestFriendly = friendly
                bestTime = hitTime
                bestHit = hit.copy(
                    key = projectile,
                    time = hitTime,
                    friendly = false,
                    shieldCovered = friendly.shield?.isOn == true && friendly.shield.isWithinArc(point),
                    cellX = friendly.armorGrid.getCellAtLocation(point)?.get(0) ?: -1,
                    cellY = friendly.armorGrid.getCellAtLocation(point)?.get(1) ?: -1,
                )
            }
            val friendly = bestFriendly ?: continue
            val friendlyHit = bestHit ?: continue
            hitsByFriendly.getOrPut(friendly) { ArrayList() } += friendlyHit
        }
        var total = 0f
        for ((friendly, hits) in hitsByFriendly) {
            val loss = PhaseThreat.estimate(
                defense(friendly),
                hits,
                PhaseThreat.Window(0f, 1.5f),
            ).hostile
            total += loss.hull + loss.shieldFlux * 0.25f
        }
        return total
    }

    private fun defense(ship: ShipAPI): PhaseThreat.Defense {
        val stats = ship.mutableStats
        val effective = stats.effectiveArmorBonus
        val flat = effective.computeEffective(0f)
        return PhaseThreat.Defense(
            hull = ship.hitpoints,
            armor = ship.armorGrid.grid.map { it.toList() },
            armorRating = ship.armorGrid.armorRating,
            minimumArmorFraction = stats.minArmorFraction.modifiedValue,
            maximumArmorReduction = stats.maxArmorDamageReduction.modifiedValue,
            effectiveArmorMult = effective.computeEffective(1f) - flat,
            effectiveArmorFlat = flat,
            armorDamageMult = stats.armorDamageTakenMult.modifiedValue,
            kineticArmorMult = stats.kineticArmorDamageTakenMult.modifiedValue,
            hullDamageMult = stats.hullDamageTakenMult.modifiedValue,
            shieldOn = ship.shield?.isOn == true && !ship.fluxTracker.isOverloadedOrVenting,
            fluxCapacity = (ship.maxFlux - ship.currFlux).coerceAtLeast(0f),
            shieldFluxPerDamage = (ship.shield?.fluxPerPointOfDamage ?: 1f) *
                stats.shieldDamageTakenMult.modifiedValue,
            beamDamageMult = stats.beamDamageTakenMult.modifiedValue,
            projectileDamageMult = stats.projectileDamageTakenMult.modifiedValue,
        )
    }
}
