package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.loading.ProjectileSpecAPI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class PhaseOpportunity(
    val attack: Boolean = false,
    val vent: Boolean = false,
    val attackImpact: Float = Float.POSITIVE_INFINITY,
    val ventImpact: Float = Float.POSITIVE_INFINITY,
    val window: Float = 0f,
)

internal object PhaseOpportunities {
    internal data class Timing(
        val cooldown: Float,
        val aim: Float,
        val charge: Float,
        val burst: Float,
        val flight: Float,
        val reloadFreezes: Boolean = true,
        val flightAt: ((Float) -> Float)? = null,
    ) {
        fun impact(available: Float, reloadPause: Float = 0f): Float {
            if (listOf(available, cooldown, aim, charge, burst, flight, reloadPause).any { !duration(it) }) {
                return Float.POSITIVE_INFINITY
            }
            val pause = if (reloadFreezes && cooldown > 0f) reloadPause else 0f
            val firstRelease = max(available, max(cooldown + pause, aim)) + charge
            val release = firstRelease + burst
            val firstTravel = flightAt?.invoke(firstRelease) ?: flight
            val travel = flightAt?.invoke(release) ?: flight
            return if (duration(firstTravel) && duration(travel)) max(firstRelease + firstTravel, release + travel)
                else Float.POSITIVE_INFINITY
        }
    }

    fun compare(
        timings: List<Timing>,
        window: Float,
        surface: Float,
        affordable: Boolean,
        ventDuration: Float,
        reloadPause: Float,
        ventAffordable: Boolean,
        ventSafe: Boolean,
    ): PhaseOpportunity {
        if (!window.isFinite() || window <= 0f || !duration(surface)) return PhaseOpportunity()
        val attackImpact = timings.maxOfOrNull { it.impact(surface) } ?: Float.POSITIVE_INFINITY
        val ventImpact = timings.maxOfOrNull {
            it.impact(ventDuration, if (it.cooldown > surface) reloadPause else 0f)
        } ?: Float.POSITIVE_INFINITY
        val attack = affordable && attackImpact.isFinite() && attackImpact < window
        return PhaseOpportunity(attack, !attack && ventAffordable && ventSafe &&
            ventImpact.isFinite() && ventImpact < window, attackImpact, ventImpact, window)
    }

    fun sample(
        ship: ShipAPI,
        cloak: ShipSystemAPI,
        target: ShipAPI?,
        recovery: PhaseRecovery,
        attackCost: Float,
        beamOutputLocalSec: Float = 2f,
    ): PhaseOpportunity {
        if (target == null || target.isPhased || !PhaseTargeting.isValidEnemy(ship, target)) return PhaseOpportunity()
        val window = PhaseTargeting.enemyWindow(target)
        val flux = ship.fluxTracker
        if (window <= 0f || flux.isOverloadedOrVenting || !duration(attackCost) ||
            !duration(beamOutputLocalSec) || !duration(flux.currFlux) ||
            !flux.maxFlux.isFinite() || flux.maxFlux <= 0f || !ship.angularVelocity.isFinite()
        ) return PhaseOpportunity(window = window)
        val transitioning = cloak.state == ShipSystemAPI.SystemState.IN ||
            cloak.state == ShipSystemAPI.SystemState.ACTIVE || cloak.state == ShipSystemAPI.SystemState.OUT
        val level = if (transitioning) cloak.effectLevel else 0f
        val phaseMult = if (transitioning) PhaseCloakStats.getMaxTimeMult(ship.mutableStats) else 1f
        val baseMult = ship.mutableStats.timeMult.modifiedValue / (1f + (phaseMult - 1f) * level)
        if (!baseMult.isFinite() || baseMult <= 0f || !phaseMult.isFinite() || phaseMult < 1f ||
            !level.isFinite() || level !in 0f..1f || !duration(cloak.chargeDownDur)
        ) return PhaseOpportunity(window = window)
        val motion = PhaseThreat.Motion(ship, surface = true)
        val surface = if (transitioning) motion.chargeOutTime ?: return PhaseOpportunity(window = window) else 0f
        if (!duration(surface)) return PhaseOpportunity(window = window)
        val weapons = ship.allWeapons.filter {
            PhaseTargeting.attackWeapon(it) && !it.isForceDisabled && PhaseTargeting.inRangeAndArc(it, target)
        }
        val states = WeaponTracker().sample(weapons)
        val timings = ArrayList<Timing>()
        var continuing = false
        for (state in states) {
            val w = state.snapshot
            val ammo = if (w.isInBurst && !w.isBeam && !w.isPulse) {
                w.uncertainties.none { it == WeaponCostUncertainty.BARREL_MODE } &&
                    (!w.usesAmmo || w.ammo >= state.remainingShots * w.barrels)
            } else w.isBurstBeam && w.isFiring && w.cooldownRemaining == 0f || WeaponCost.hasAmmoForFullVolley(w)
            continuing = continuing || state.active || w.isBeam && w.isFiring
            if (!ammo) {
                if (state.active) return PhaseOpportunity(window = window)
                continue
            }
            val timing = timing(state, ship, target, motion, surface, baseMult, beamOutputLocalSec)
                ?: return PhaseOpportunity(window = window)
            timings += timing
        }
        val ventLocal = OwnVentCalc.newVentLocalSec(
            flux = flux,
            endFlux = flux.currFlux,
            currentDissipation = ship.mutableStats.fluxDissipation.modifiedValue,
            naturalDissipation = ship.mutableStats.fluxDissipation.createCopy().apply {
                unmodifyMult("phase_anchor_modifier")
            }.modifiedValue,
            ventRateMult = ship.mutableStats.ventRateMult.modifiedValue,
        )
        val ventDuration = max(recovery.ventDuration, surface + ventLocal / baseMult)
        val freeze = Global.getSettings().getFloat("minVentTimeForWeaponReload")
        val pause = if (duration(freeze)) min(ventLocal, freeze) / baseMult else Float.POSITIVE_INFINITY
        val reserve = cloak.fluxPerUse + cloak.fluxPerSecond * 1.5f + flux.maxFlux * 0.05f
        val safeLimit = (flux.maxFlux - reserve).coerceAtLeast(0f)
        return compare(timings, window, surface, flux.currFlux + attackCost <= safeLimit,
            if (continuing) Float.POSITIVE_INFINITY else ventDuration, pause,
            !continuing && attackCost <= safeLimit, recovery.ventSafe)
    }

    private fun timing(
        state: WeaponState, ship: ShipAPI, target: ShipAPI, motion: PhaseThreat.Motion,
        surface: Float, baseMult: Float, beamOutput: Float,
    ): Timing? {
        val w = state.snapshot
        val weapon = w.weapon
        if (w.uncertainties.contains(WeaponCostUncertainty.UNSUPPORTED_WEAPON) ||
            !duration(w.cooldownRemaining) || !duration(w.chargeLevel) ||
            !duration(w.chargeTime) || !duration(w.chargeupTime) || !duration(w.burstDuration) ||
            !duration(w.burstDelay) || !duration(w.cooldown) || surface > 0f && state.active
        ) return null
        val stat = when (weapon.type) {
            WeaponAPI.WeaponType.ENERGY -> ship.mutableStats.energyRoFMult
            WeaponAPI.WeaponType.BALLISTIC -> ship.mutableStats.ballisticRoFMult
            WeaponAPI.WeaponType.MISSILE -> ship.mutableStats.missileRoFMult
            else -> return null
        }
        val rof = stat.createCopy().apply { unmodifyMult("phase_anchor_modifier") }.modifiedValue
        if (!rof.isFinite() || rof <= 0f) return null
        val minRefire = Global.getSettings().getFloat("minRefireDelay")
        if (!minRefire.isFinite() || minRefire <= 0f) return null
        fun projectileRate(cycle: Float): Float = if (rof > 1f) max(1f, min(rof, cycle / minRefire)) else rof
        var cooldown = w.cooldownRemaining
        var charge: Float
        var burst: Float
        var cooldownRate = 1f
        when {
            w.isBurstBeam -> {
                if (!duration(w.chargedownTime) || w.burstDuration <= 0f) return null
                cooldownRate = rof
                if (w.isFiring && cooldown == 0f) {
                    if (!duration(w.burstFireTimeRemaining) || w.burstFireTimeRemaining <= 0f ||
                        w.burstFireTimeRemaining > w.chargeupTime + w.burstDuration) return null
                    charge = (w.burstFireTimeRemaining - w.burstDuration).coerceAtLeast(0f)
                    burst = min(w.burstFireTimeRemaining, w.burstDuration) + w.chargedownTime / rof
                } else {
                    charge = w.chargeupTime
                    burst = w.burstDuration + w.chargedownTime / rof
                }
            }
            w.isBeam -> {
                if (beamOutput <= 0f || w.isFiring && w.chargeLevel != 1f) return null
                charge = if (w.isFiring) 0f else w.chargeupTime
                burst = beamOutput
            }
            w.isPulse -> {
                if (w.isFiring && (w.chargeLevel != 1f || w.chargeTime <= 0.1f)) return null
                charge = if (w.isFiring) 0f else max(w.chargeTime, 0.1f)
                burst = 0f
            }
            w.isInBurst -> {
                val remaining = w.burstFireTimeRemaining / w.burstDelay
                if (w.burstDelay <= 0f || w.cooldown <= 0f || !remaining.isFinite() ||
                    remaining < 1f || remaining > w.burstSize || abs(remaining - state.remainingShots) > 0.001f ||
                    w.cooldownRemaining > w.cooldown) return null
                val firstGap = if (w.cooldownRemaining > 0f) w.cooldownRemaining / w.cooldown * w.burstDelay else w.burstDelay
                cooldown = 0f
                charge = 0f
                burst = (firstGap + (state.remainingShots - 1) * w.burstDelay) / projectileRate(w.burstDelay)
            }
            else -> {
                if (w.burstSize > 1 && w.burstDelay <= 0f) return null
                cooldownRate = projectileRate(w.chargeTime + w.cooldown)
                charge = w.chargeTime * if (state.outputState == WeaponOutputState.CHARGING) 1f - w.chargeLevel else 1f
                charge /= cooldownRate
                burst = (w.burstSize - 1) * w.burstDelay / projectileRate(w.burstDelay)
            }
        }
        if (!duration(charge) || !duration(burst)) return null
        val trajectory = Trajectory(ship, target, weapon, motion)
        val aim = trajectory.aimLocal() ?: return null
        return Timing(motion.battleTime(cooldown / cooldownRate), motion.battleTime(aim),
            charge / baseMult, burst / baseMult, 0f, reloadFreezes = !w.isPulse,
            flightAt = { release -> trajectory.flight(release) })
    }

    private class Trajectory(
        private val ship: ShipAPI, private val target: ShipAPI,
        private val weapon: WeaponAPI, private val motion: PhaseThreat.Motion,
    ) {
        private val targetMult = target.mutableStats.timeMult.modifiedValue
        private val speed = weapon.projectileSpeed
        private fun relative(at: Float): Pair<Double, Double> {
            val origin = motion.mountedPoint(PhaseThreat.Point(weapon.location.x, weapon.location.y), at)
            return (target.location.x + target.velocity.x * targetMult * at - origin.x).toDouble() to
                (target.location.y + target.velocity.y * targetMult * at - origin.y).toDouble()
        }

        private fun intercept(x: Double, y: Double): Float {
            if (weapon.isBeam) return 0f
            val spec = weapon.spec.projectileSpec as? ProjectileSpecAPI ?: return Float.POSITIVE_INFINITY
            if (spec.behaviorSpec != null || !speed.isFinite() || speed <= 0f) return Float.POSITIVE_INFINITY
            val vx = (target.velocity.x * targetMult - ship.velocity.x).toDouble()
            val vy = (target.velocity.y * targetMult - ship.velocity.y).toDouble()
            val radius = target.collisionRadius.toDouble()
            val c = x * x + y * y - radius * radius
            if (c <= 0.0) return 0f
            val a = vx * vx + vy * vy - speed.toDouble() * speed
            val b = 2.0 * (x * vx + y * vy - speed * radius)
            if (a == 0.0) return if (b < 0.0) (-c / b).toFloat() else Float.POSITIVE_INFINITY
            val discriminant = b * b - 4.0 * a * c
            if (!discriminant.isFinite() || discriminant < 0.0) return Float.POSITIVE_INFINITY
            return listOf((-b - sqrt(discriminant)) / (2.0 * a), (-b + sqrt(discriminant)) / (2.0 * a))
                .filter { it >= 0.0 && it.isFinite() }.minOrNull()?.toFloat() ?: Float.POSITIVE_INFINITY
        }

        private fun aimAngle(x: Double, y: Double, flight: Float, rotation: Float = 0f): Float {
            val vx = if (weapon.isBeam) 0.0 else (target.velocity.x * targetMult - ship.velocity.x).toDouble()
            val vy = if (weapon.isBeam) 0.0 else (target.velocity.y * targetMult - ship.velocity.y).toDouble()
            val bearing = Math.toDegrees(kotlin.math.atan2(y + vy * flight, x + vx * flight)).toFloat()
            val distance = sqrt(x * x + y * y)
            val radius = when {
                distance <= target.collisionRadius -> 180f
                !weapon.isBeam -> 0f
                else -> Math.toDegrees(asin(target.collisionRadius / distance)).toFloat()
            }
            return (PhaseTargeting.angleDiff(weapon.currAngle + rotation, bearing) - radius).coerceAtLeast(0f)
        }

        fun aimLocal(): Float? {
            if (!targetMult.isFinite() || targetMult <= 0f || !duration(target.collisionRadius) ||
                !weapon.range.isFinite() || weapon.range <= 0f || !weapon.currAngle.isFinite() ||
                !duration(weapon.turnRate) || listOf(ship.velocity.x, ship.velocity.y, target.velocity.x,
                    target.velocity.y, weapon.location.x, weapon.location.y, target.location.x, target.location.y)
                    .any { !it.isFinite() }) return null
            val (x, y) = relative(0f)
            val flight = intercept(x, y)
            if (!duration(flight)) return null
            val angle = aimAngle(x, y, flight)
            val rate = weapon.turnRate + abs(ship.angularVelocity)
            if (!duration(angle) || angle > 0f && rate <= 0f) return null
            return if (angle == 0f) 0f else angle / rate
        }

        fun flight(release: Float): Float {
            val (x, y) = relative(release)
            if (!x.isFinite() || !y.isFinite() || sqrt(x * x + y * y) > weapon.range + target.collisionRadius) {
                return Float.POSITIVE_INFINITY
            }
            val targetAtRelease = PhaseThreat.Point(target.location.x + target.velocity.x * targetMult * release,
                target.location.y + target.velocity.y * targetMult * release)
            val frame = motion.currentFrame(targetAtRelease, release)
            val future = org.lwjgl.util.vector.Vector2f(frame.x, frame.y)
            if (weapon.distanceFromArc(future) > 0.01f) return Float.POSITIVE_INFINITY
            val flight = intercept(x, y)
            if (!duration(flight) || !weapon.isBeam && speed * flight > weapon.range ||
                aimAngle(x, y, flight, motion.rotation(release)) > weapon.turnRate * motion.localTime(release)) {
                return Float.POSITIVE_INFINITY
            }
            return flight
        }
    }

    private fun duration(value: Float): Boolean = value.isFinite() && value >= 0f
}
