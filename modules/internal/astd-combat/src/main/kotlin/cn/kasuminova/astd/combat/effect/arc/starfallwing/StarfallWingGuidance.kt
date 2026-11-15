package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class StarfallWingShot(
    val type: StarfallWingTuning.ShuttleType,
    val power: Float,
    val speed: Float,
    val range: Float,
    val phase: Float,
) {
    var liveTargetHit = false
    var travelled = 0f
        internal set

    var target: ShipAPI? = null
        internal set

    var availability: Int = AVAILABILITY_NONE
        internal set

    var aimScore: Float = 0f
        internal set

    var avoidanceDeflection: Float = 0f
        internal set

    private var hit = false

    fun claimHit(): Boolean {
        if (hit) return false
        hit = true
        return true
    }

    companion object {
        const val AVAILABILITY_LOCK = 0

        const val AVAILABILITY_SHIP = 1

        const val AVAILABILITY_FIGHTER = 2

        const val AVAILABILITY_SHIP_OUT_OF_RANGE = 3

        const val AVAILABILITY_NONE = 5
    }
}

internal class StarfallWingGuidance private constructor(private val engine: CombatEngineAPI) : BaseEveryFrameCombatPlugin() {
    private class Flight(val projectile: DamagingProjectileAPI, val shot: StarfallWingShot) {
        val previous = Vector2f(projectile.location)
        val launchFacing = projectile.facing
        var arcDistance = shot.range
        var scanRemaining = 0f
        var obstacles: List<StarfallWingBody> = emptyList()
        var avoidRemaining = 0f
        var avoidSide = 0f
    }

    private val flights = ArrayList<Flight>()
    private val claims = StarfallWingTargetPool<ShipAPI>()

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (engine.isPaused || amount <= 0f) return
        releaseInactive()
        for (flight in flights) step(flight, amount)
        if (flights.isEmpty()) {
            engine.customData.remove(KEY)
            engine.removePlugin(this)
        }
    }

    private fun releaseInactive() {
        val iterator = flights.iterator()
        while (iterator.hasNext()) {
            val flight = iterator.next()
            val projectile = flight.projectile
            if (!engine.isEntityInPlay(projectile) || projectile.didDamage() || projectile.isFading) {
                bind(flight, null)
                iterator.remove()
            }
        }
    }

    private fun step(flight: Flight, amount: Float) {
        val projectile = flight.projectile
        val shot = flight.shot
        val dx = projectile.location.x - flight.previous.x
        val dy = projectile.location.y - flight.previous.y
        shot.travelled += sqrt(dx * dx + dy * dy)
        flight.previous.set(projectile.location)
        val remaining = (shot.range - shot.travelled).coerceAtLeast(0f)
        if (shot.target != null && !valid(shot.target, projectile.owner)) {
            bind(flight, null)
            flight.scanRemaining = 0f
        }
        flight.scanRemaining -= amount
        if (flight.scanRemaining <= 0f) {
            flight.scanRemaining = StarfallWingTuning.TARGET_SCAN_INTERVAL
            bind(flight, choose(flight, remaining))
        }
        val target = shot.target
        var targetDistance = remaining
        val targetFacing = if (target != null) {
            val tx = target.location.x - projectile.location.x
            val ty = target.location.y - projectile.location.y
            targetDistance = sqrt(tx * tx + ty * ty)
            val lead = minOf(targetDistance / shot.speed, 0.35f)
            Math.toDegrees(atan2(
                (ty + target.velocity.y * lead).toDouble(),
                (tx + target.velocity.x * lead).toDouble(),
            )).toFloat()
        } else flight.launchFacing
        val progress = shot.travelled / flight.arcDistance
        val offset = if (target == null) {
            StarfallWingTuning.arcOffset(progress, shot.phase)
        } else {
            StarfallWingTuning.approachOffset(
                progress, shot.phase, (targetDistance - target.collisionRadius).coerceAtLeast(0f),
                remaining + minOf(shot.range * 0.08f, shot.speed * 0.2f), shot.speed,
            )
        }
        val base = targetFacing + offset
        val steer = avoidance(flight, base, remaining, amount)
        val facing = StarfallWingTuning.turnToward(
            projectile.facing, base + steer,
            StarfallWingTuning.turnRate(shot.speed) * amount,
        )
        projectile.facing = facing
        velocity(projectile, shot.speed)
    }

    private fun avoidance(flight: Flight, heading: Float, remaining: Float, amount: Float): Float {
        val projectile = flight.projectile
        flight.avoidRemaining -= amount
        if (flight.avoidRemaining <= 0f) {
            val range = StarfallWingAvoidance.scanRange(flight.shot.speed, remaining)
            flight.obstacles = starfallWingScanObstacles(
                engine, projectile.location, range, projectile.collisionRadius,
                flight.shot.target?.isFighter == true, projectile.source, flight.shot.target,
            )
            flight.avoidRemaining = StarfallWingAvoidance.SCAN_INTERVAL
        }
        if (flight.obstacles.isEmpty()) {
            flight.shot.avoidanceDeflection = 0f
            return 0f
        }
        val steer = StarfallWingAvoidance.steer(
            projectile.location, heading, flight.shot.speed, remaining, flight.obstacles, flight.avoidSide,
        )
        if (steer.side != 0f) flight.avoidSide = steer.side
        flight.shot.avoidanceDeflection = steer.deflection
        return steer.deflection
    }

    private fun valid(ship: ShipAPI?, owner: Int): Boolean =
        ship != null && ship.owner != owner && ship.owner != 100 && ship.isAlive && !ship.isHulk &&
            !ship.isPhased && ship.isTargetable && engine.isEntityInPlay(ship)

    private fun claimOpen(candidate: ShipAPI, shot: StarfallWingShot): Boolean =
        !candidate.isFighter || claims.isOpen(candidate, shot)

    private fun bind(flight: Flight, next: ShipAPI?) {
        val shot = flight.shot
        val current = shot.target
        if (current === next) {
            refresh(flight, next)
            return
        }
        claims.release(current, shot)
        shot.target = next
        if (next != null && next.isFighter) claims.claim(next, shot)
        flight.avoidRemaining = 0f
        refresh(flight, next)
    }

    private fun refresh(flight: Flight, target: ShipAPI?) {
        val shot = flight.shot
        if (target == null) {
            shot.availability = StarfallWingShot.AVAILABILITY_NONE
            shot.aimScore = 0f
            return
        }
        val remaining = (shot.range - shot.travelled).coerceAtLeast(0f)
        val projectile = flight.projectile
        shot.availability = StarfallWingTargeting.classOf(
            target.isFighter, target === locked(projectile), inReach(projectile, target, remaining),
        )
        shot.aimScore = rawScore(flight, target, remaining)
    }

    private fun locked(projectile: DamagingProjectileAPI): ShipAPI? = projectile.source?.shipTarget

    private fun choose(flight: Flight, remaining: Float): ShipAPI? {
        val projectile = flight.projectile
        val shot = flight.shot
        val current = shot.target
        val lock = locked(projectile)
        var best: ShipAPI? = null
        var bestClass = Int.MAX_VALUE
        var bestScore = -Float.MAX_VALUE
        if (lock != null && !lock.isFighter && valid(lock, projectile.owner) && claimOpen(lock, shot) &&
            inReach(projectile, lock, remaining)
        ) {
            best = lock
            bestClass = StarfallWingShot.AVAILABILITY_LOCK
            bestScore = score(flight, lock, remaining)
        }
        if (best == null) {
            for (candidate in engine.ships) {
                if (candidate === current) continue
                if (!valid(candidate, projectile.owner) || !claimOpen(candidate, shot)) continue
                val candidateClass = StarfallWingTargeting.classOf(
                    candidate.isFighter, false, inReach(projectile, candidate, remaining),
                )
                if (candidateClass > bestClass) continue
                val candidateScore = score(flight, candidate, remaining)
                if (candidateClass < bestClass || candidateScore > bestScore) {
                    best = candidate
                    bestClass = candidateClass
                    bestScore = candidateScore
                }
            }
        }
        if (current != null && valid(current, projectile.owner)) {
            val currentClass = StarfallWingTargeting.classOf(
                current.isFighter, current === lock, inReach(projectile, current, remaining),
            )
            if (best == null) return current
            if (currentClass < bestClass) return current
            if (currentClass == bestClass && !StarfallWingTargeting.outbids(bestScore, score(flight, current, remaining))) {
                return current
            }
        }
        return best
    }

    private fun rawScore(flight: Flight, candidate: ShipAPI, remaining: Float): Float {
        val projectile = flight.projectile
        return StarfallWingTargeting.aimScore(
            candidate.location.x - projectile.location.x,
            candidate.location.y - projectile.location.y,
            candidate.velocity.x, candidate.velocity.y, candidate.collisionRadius,
            projectile.facing, flight.shot.speed,
            StarfallWingTuning.turnRate(flight.shot.speed), remaining,
        )
    }

    private fun score(flight: Flight, candidate: ShipAPI, remaining: Float): Float =
        StarfallWingTargeting.withLockBonus(rawScore(flight, candidate, remaining), candidate === locked(flight.projectile))

    private fun inReach(projectile: DamagingProjectileAPI, target: ShipAPI, remaining: Float): Boolean {
        val dx = target.location.x - projectile.location.x
        val dy = target.location.y - projectile.location.y
        val reach = remaining + target.collisionRadius
        return dx * dx + dy * dy <= reach * reach
    }

    companion object {
        private const val KEY = "astd_starfall_wing_guidance"

        fun track(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, shot: StarfallWingShot) {
            val plugin = engine.customData[KEY] as? StarfallWingGuidance
                ?: StarfallWingGuidance(engine).also {
                    engine.customData[KEY] = it
                    engine.addPlugin(it)
                }
            plugin.releaseInactive()
            val flight = Flight(projectile, shot)
            plugin.flights += flight
            plugin.bind(flight, plugin.choose(flight, shot.range))
            flight.scanRemaining = StarfallWingTuning.TARGET_SCAN_INTERVAL
            shot.target?.let {
                val dx = it.location.x - projectile.location.x
                val dy = it.location.y - projectile.location.y
                flight.arcDistance = minOf(shot.range, sqrt(dx * dx + dy * dy)).coerceAtLeast(1f)
            }
            velocity(projectile, shot.speed)
        }

        private fun velocity(projectile: DamagingProjectileAPI, speed: Float) {
            val angle = Math.toRadians(projectile.facing.toDouble())
            projectile.velocity.set((cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat())
        }
    }
}
