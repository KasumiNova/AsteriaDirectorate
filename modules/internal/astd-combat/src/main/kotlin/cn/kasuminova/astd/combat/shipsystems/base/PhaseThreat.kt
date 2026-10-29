package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class PhaseThreat {
    data class Point(val x: Float, val y: Float) {
        operator fun plus(other: Point) = Point(x + other.x, y + other.y)
        operator fun minus(other: Point) = Point(x - other.x, y - other.y)
        operator fun times(scale: Float) = Point(x * scale, y * scale)
        fun dot(other: Point) = x * other.x + y * other.y
        fun length() = sqrt(dot(this))
    }

    data class Window(val chargeOut: Float, val reaction: Float, val cooldown: Float = 0f) {
        init {
            require(chargeOut.isFinite() && reaction.isFinite() && cooldown.isFinite() &&
                chargeOut >= 0f && reaction >= 0f && cooldown >= 0f)
        }
        val total: Float get() = chargeOut + cooldown + reaction
    }

    data class Approach(val closestDistance: Float, val closestTime: Float, val hitTime: Float?)
    data class Guidance(val tracking: Boolean, val facing: Float, val turnRate: Float)
    enum class Stage { DIRECT, CARRIER, EXPLOSION, BEAM }

    data class Hit(
        val key: Any,
        val time: Float,
        val damage: Float,
        val type: DamageType,
        val friendly: Boolean = false,
        val stage: Stage = Stage.DIRECT,
        val shieldCovered: Boolean = false,
        val cellX: Int = -1,
        val cellY: Int = -1,
        val hitStrength: Float = damage,
        val softFlux: Boolean = false,
    )

    data class Defense(
        val hull: Float,
        val armor: List<List<Float>> = emptyList(),
        val armorRating: Float = 0f,
        val minimumArmorFraction: Float = 0f,
        val maximumArmorReduction: Float = 0.85f,
        val effectiveArmorMult: Float = 1f,
        val effectiveArmorFlat: Float = 0f,
        val armorDamageMult: Float = 1f,
        val kineticArmorMult: Float = 1f,
        val hullDamageMult: Float = 1f,
        val shieldOn: Boolean = false,
        val fluxCapacity: Float = 0f,
        val shieldFluxPerDamage: Float = 1f,
        val beamDamageMult: Float = 1f,
        val projectileDamageMult: Float = 1f,
    )

    data class Loss(val raw: Float = 0f, val armor: Float = 0f, val hull: Float = 0f,
                    val shieldFlux: Float = 0f, val hardFlux: Float = 0f) {
        val durability: Float get() = armor + hull
        operator fun plus(other: Loss) = Loss(raw + other.raw, armor + other.armor, hull + other.hull,
            shieldFlux + other.shieldFlux, hardFlux + other.hardFlux)
    }

    data class Impact(val hit: Hit, val loss: Loss)
    data class Forecast(
        val impacts: List<Impact>,
        val hostile: Loss,
        val friendly: Loss,
        val duringChargeOut: Loss,
        val duringReaction: Loss,
        val remainingHull: Float,
        val overloadAt: Float?,
        val lethalAt: Float?,
        val activeThreatKeys: Set<Any> = emptySet(),
        val carrierThreatKeys: Set<Any> = emptySet(),
        val lineageThreatKeys: Set<Any> = emptySet(),
        val impactAncestorKeys: Map<Any, Set<Any>> = emptyMap(),
    ) {
        val lethal: Boolean get() = lethalAt != null
        val total: Loss get() = hostile + friendly
        val threatKeys: Set<Any> get() = impacts.mapTo(linkedSetOf<Any>()) { it.hit.key }.also { it.addAll(lineageThreatKeys) }
        fun exceedsHullBudget(budget: Float): Boolean = total.hull > budget
        fun significantThreatKeys(threshold: Float): Set<Any> {
            val relevant = (if (lethal) impacts.filter { lethalAt == null || it.hit.time <= lethalAt } else impacts)
                .filter { it.loss.durability > 0f }
            val selected = linkedSetOf<Any>()
            relevant.groupBy { it.hit.key }.forEach { (key, group) ->
                if (group.sumOf { it.loss.durability.toDouble() } >= threshold) selected += key
            }
            if (lethal) {
                relevant.mapTo(selected) { it.hit.key }
            } else if (selected.isEmpty() && total.durability >= threshold) {
                var cumulative = 0f
                for (impact in relevant) {
                    if (cumulative >= threshold) break
                    selected += impact.hit.key
                    cumulative += impact.loss.durability
                }
            }
            return selected.flatMapTo(linkedSetOf()) { key ->
                sequenceOf(key) + impactAncestorKeys[key].orEmpty().asSequence()
            }
        }
    }

    internal class Motion(ship: ShipAPI, surface: Boolean = false) {
        val origin = point(ship.location)
        private val velocity = point(ship.velocity)
        private val facing = ship.facing
        private val angularVelocity = ship.angularVelocity
        private val mult = ship.mutableStats.timeMult.modifiedValue
        private val cloak = ship.phaseCloak
        private val state = if (surface && (cloak?.state == ShipSystemAPI.SystemState.ACTIVE ||
            cloak?.state == ShipSystemAPI.SystemState.IN)) ShipSystemAPI.SystemState.OUT else cloak?.state
        private val level = cloak?.effectLevel?.coerceIn(0f, 1f) ?: 0f
        private val phaseBonus = if (state == ShipSystemAPI.SystemState.IN || state == ShipSystemAPI.SystemState.OUT) {
            (PhaseCloakStats.getMaxTimeMult(ship.mutableStats) - 1f).coerceAtLeast(0f)
        } else 0f
        private val baseMult = mult / (1f + phaseBonus * level)
        private val duration = when (state) {
            ShipSystemAPI.SystemState.IN -> cloak?.chargeUpDur ?: 0f
            ShipSystemAPI.SystemState.OUT -> cloak?.chargeDownDur ?: 0f
            else -> 0f
        }
        private val remaining = duration * if (state == ShipSystemAPI.SystemState.IN) 1f - level else level
        private val slope = if (duration > 0f) {
            baseMult * phaseBonus / duration * if (state == ShipSystemAPI.SystemState.IN) 1f else -1f
        } else 0f
        private val endMult = when (state) {
            ShipSystemAPI.SystemState.IN -> baseMult * (1f + phaseBonus)
            ShipSystemAPI.SystemState.OUT -> baseMult
            else -> mult
        }

        val chargeOutTime: Float? get() = if (state == ShipSystemAPI.SystemState.OUT) battleTime(remaining) else null

        var rejectionReason: String? = null
            private set

        fun exposure(window: Window): Window? {
            rejectionReason = null
            if (!mult.isFinite() || mult <= 0f) return rejectExposure("non-positive or non-finite time multiplier")
            if (!baseMult.isFinite() || baseMult <= 0f || !endMult.isFinite() || endMult <= 0f) {
                return rejectExposure("non-positive or non-finite derived time multiplier")
            }
            if (!duration.isFinite() || duration < 0f || !remaining.isFinite() || remaining < 0f ||
                !slope.isFinite()) return rejectExposure("invalid phase transition clock")
            if (!window.total.isFinite()) return rejectExposure("non-finite requested window total")
            val chargeOut = if (cloak == null) window.chargeOut else chargeOutTime ?: 0f
            if (!chargeOut.isFinite() || chargeOut < 0f) return rejectExposure("non-finite charge-out time")
            val cooldownLocal = when (state) {
                ShipSystemAPI.SystemState.OUT -> cloak?.cooldown ?: 0f
                ShipSystemAPI.SystemState.COOLDOWN -> cloak?.cooldownRemaining ?: 0f
                else -> 0f
            }
            if (!cooldownLocal.isFinite() || cooldownLocal < 0f) return rejectExposure("invalid local cooldown")
            val cooldownBattle = if (cloak == null) window.cooldown else cooldownLocal / endMult
            if (!cooldownBattle.isFinite() || cooldownBattle < 0f) return rejectExposure("non-finite battle cooldown")
            val total = chargeOut + cooldownBattle + window.reaction
            if (!total.isFinite()) return rejectExposure("non-finite exposure window total")
            if (!localTime(total).isFinite()) return rejectExposure("non-finite integrated local time")
            return Window(chargeOut, window.reaction, cooldownBattle)
        }

        private fun rejectExposure(reason: String): Window? {
            rejectionReason = reason
            return null
        }

        fun localTime(battleTime: Float): Float {
            if (mult <= 0f || battleTime <= 0f) return 0f
            if (remaining <= 0f) return endMult * battleTime
            if (abs(slope) < EPSILON) return mult * battleTime
            val transitionTime = (kotlin.math.ln(endMult.toDouble() / mult) / slope).toFloat()
            val transition = min(battleTime, transitionTime)
            val local = mult * kotlin.math.expm1(slope.toDouble() * transition) / slope
            return local.toFloat().coerceIn(0f, remaining) + max(0f, battleTime - transitionTime) * endMult
        }

        fun battleTime(localTime: Float): Float {
            if (localTime <= 0f) return 0f
            if (mult <= 0f) return Float.POSITIVE_INFINITY
            if (remaining <= 0f) return localTime / endMult
            if (abs(slope) < EPSILON) return localTime / mult
            val transition = min(localTime, remaining)
            return (kotlin.math.ln1p(slope.toDouble() * transition / mult) / slope).toFloat() +
                max(0f, localTime - remaining) / endMult
        }

        fun position(time: Float): Point = origin + velocity * localTime(time)
        fun heading(time: Float): Float = facing + angularVelocity * localTime(time)
        fun rotation(time: Float): Float = angularVelocity * localTime(time)
        fun mountedPoint(point: Point, time: Float): Point = position(time) + rotate(point - origin, rotation(time))
        fun currentFrame(point: Point, time: Float): Point = origin + rotate(point - position(time), -rotation(time))
    }

    private data class BeamHistory(
        val time: Float,
        val charge: Float,
        val emittingLocalTime: Float?,
        val priorCharge: Float?,
        val motion: Motion,
    )
    private val beamHistory = IdentityHashMap<WeaponAPI, BeamHistory>()
    private val beamTargets = IdentityHashMap<com.fs.starfarer.api.combat.BeamAPI, com.fs.starfarer.api.combat.CombatEntityAPI>()
    private val demBehaviors = IdentityHashMap<WeaponAPI, org.json.JSONObject>()

    private class SamplingFailure(reason: String) : RuntimeException(reason)

    companion object {
        private const val EPSILON = 0.00001f
        private const val BEAM_STEP = 0.05f
        private const val MOTION_STEP = 0.01f
        private const val DEM_DRONE_MATCH_DISTANCE = 5f
        private const val DEM_LOCK_SWEEP_TOLERANCE = 100f

        private fun checkSamplingStep(end: Float, step: Float) {
            if (!end.isFinite() || end < 0f || !step.isFinite() || step <= 0f || end + step <= end) {
                throw SamplingFailure("sampling clock cannot advance over the complete window")
            }
        }

        private fun nextSample(time: Float, end: Float, step: Float): Float {
            val next = min(end, time + step)
            if (!time.isFinite() || !next.isFinite() || next <= time) {
                throw SamplingFailure("sampling clock failed to advance")
            }
            return next
        }

        fun intercept(origin: Point, velocity: Point, motion: Motion, radius: Float, start: Float, end: Float): Float? {
            if (end < start) return null
            checkSamplingStep(end, MOTION_STEP)
            var time = start
            var relative = origin + velocity * time - motion.position(time)
            if (relative.length() <= radius) return time
            while (time < end) {
                val next = nextSample(time, end, MOTION_STEP)
                val nextRelative = origin + velocity * next - motion.position(next)
                val dt = next - time
                val result = approach(relative, (nextRelative - relative) * (1f / dt), radius, dt, dt)
                if (result.hitTime != null) {
                    fun distance(at: Float) = (origin + velocity * at - motion.position(at)).length()
                    var closest = time + result.closestTime
                    if (distance(closest) > radius) {
                        var left = time
                        var right = next
                        repeat(20) {
                            val a = left + (right - left) / 3f
                            val b = right - (right - left) / 3f
                            if (distance(a) < distance(b)) right = b else left = a
                        }
                        closest = (left + right) * 0.5f
                    }
                    if (distance(closest) <= radius) {
                        var left = time
                        var right = closest
                        repeat(20) {
                            val middle = (left + right) * 0.5f
                            if (distance(middle) <= radius) right = middle else left = middle
                        }
                        return right
                    }
                }
                time = next
                relative = nextRelative
            }
            return null
        }

        fun remainingLife(projectile: DamagingProjectileAPI, fallback: Float): Float {
            if (projectile.isExpired) return 0f
            val explosion = projectile.explosionSpecIfExplosion
            if (explosion != null) return (explosion.duration - projectile.elapsed).coerceAtLeast(0f)
            if (projectile is MissileAPI) {
                val fading = if (projectile.isArmedWhileFizzling) {
                    projectile.spec.noEngineGlowTime + projectile.spec.flameoutTime + projectile.spec.fadeTime
                } else 0f
                return if (projectile.isFizzling) (fading - projectile.timeSinceFizzling).coerceAtLeast(0f)
                else (projectile.maxFlightTime - projectile.flightTime + fading).coerceAtLeast(0f)
            }
            val spec = projectile.projectileSpec ?: return fallback
            val speed = projectile.moveSpeed
            if (speed <= EPSILON) return fallback
            val range = projectile.weapon?.range ?: spec.maxRange
            return (range / speed + spec.fadeTime - projectile.elapsed).coerceAtLeast(0f)
        }

        private fun rotate(point: Point, degrees: Float): Point {
            val heading = direction(degrees)
            return Point(point.x * heading.x - point.y * heading.y, point.x * heading.y + point.y * heading.x)
        }

        fun approach(relativePosition: Point, relativeVelocity: Point, radius: Float,
                     remainingLife: Float, window: Float): Approach {
            require(radius >= 0f && remainingLife >= 0f && window >= 0f)
            val end = min(remainingLife, window)
            val speedSq = relativeVelocity.dot(relativeVelocity)
            val dot = relativePosition.dot(relativeVelocity)
            val closest = if (speedSq > EPSILON) (-dot / speedSq).coerceIn(0f, end) else 0f
            val distance = (relativePosition + relativeVelocity * closest).length()
            val c = relativePosition.dot(relativePosition) - radius * radius
            val discriminant = dot * dot - speedSq * c
            val hit = when {
                c <= 0f -> 0f
                speedSq <= EPSILON || dot >= 0f || discriminant < 0f -> null
                else -> ((-dot - sqrt(discriminant)) / speedSq).takeIf { it in 0f..end }
            }
            return Approach(distance, closest, hit)
        }

        fun guidedApproach(relativePosition: Point, relativeVelocity: Point, radius: Float,
                           remainingLife: Float, window: Float, guidance: Guidance): Approach {
            val direct = approach(relativePosition, relativeVelocity, radius, remainingLife, window)
            if (direct.hitTime != null) return direct
            return guidedTrajectory(relativePosition, relativeVelocity, radius, min(remainingLife, window), guidance) {
                Point(0f, 0f)
            }?.approach ?: direct
        }

        private data class GuidedImpact(val approach: Approach, val relativePosition: Point)

        private fun guidedTrajectory(origin: Point, velocity: Point, radius: Float, end: Float,
                                     guidance: Guidance, targetPosition: (Float) -> Point): GuidedImpact? {
            if (!guidance.tracking || !guidance.turnRate.isFinite() || guidance.turnRate <= 0f ||
                velocity.length() <= EPSILON) return null
            val step = min(MOTION_STEP, 5f / guidance.turnRate)
            checkSamplingStep(end, step)
            var time = 0f
            var position = origin
            var heading = guidance.facing
            var speed = velocity
            var relative = position - targetPosition(time)
            while (time < end) {
                val next = nextSample(time, end, step)
                val dt = next - time
                val bearing = angle(relative * -1f)
                val turn = (((bearing - heading) % 360f + 540f) % 360f - 180f)
                    .coerceIn(-guidance.turnRate * dt, guidance.turnRate * dt)
                val halfTurn = Math.toRadians(turn.toDouble() * 0.5)
                val arcScale = if (abs(halfTurn) <= EPSILON) 1f else (sin(halfTurn) / halfTurn).toFloat()
                val nextPosition = position + rotate(speed, turn * 0.5f) * (dt * arcScale)
                val nextRelative = nextPosition - targetPosition(next)
                val relativeVelocity = (nextRelative - relative) * (1f / dt)
                val segment = approach(relative, relativeVelocity, radius, dt, dt)
                if (segment.hitTime != null) {
                    val eta = time + segment.hitTime
                    return GuidedImpact(Approach(segment.closestDistance, time + segment.closestTime, eta),
                        relative + relativeVelocity * segment.hitTime)
                }
                time = next
                position = nextPosition
                relative = nextRelative
                heading += turn
                speed = rotate(speed, turn)
            }
            return null
        }

        fun trackedFacing(facing: Float, turnRate: Float, start: Float, time: Float, bearing: (Float) -> Float): Float {
            if (!turnRate.isFinite() || turnRate <= 0f || !time.isFinite() || time <= start) return facing
            checkSamplingStep(time, MOTION_STEP)
            var heading = facing
            var at = start
            while (at < time) {
                val next = nextSample(at, time, MOTION_STEP)
                val limit = turnRate * (next - at)
                heading += (((bearing(at) - heading) % 360f + 540f) % 360f - 180f).coerceIn(-limit, limit)
                at = next
            }
            return heading
        }

        fun beamCovers(relativeTarget: Point, angle: Float, range: Float, radius: Float): Boolean {
            val direction = direction(angle)
            val along = relativeTarget.dot(direction)
            if (along < -radius || along > range + radius) return false
            val nearest = direction * along.coerceIn(0f, range.coerceAtLeast(0f))
            return (relativeTarget - nearest).length() <= radius
        }

        fun surfaceDistance(bearing: Point, segments: List<Pair<Point, Point>>, fallback: Float): Float {
            val length = bearing.length()
            if (length <= EPSILON) return 0f
            val ray = bearing * (1f / length)
            var distance: Float? = null
            for ((start, end) in segments) {
                val edge = end - start
                val cross = ray.x * edge.y - ray.y * edge.x
                if (abs(cross) <= EPSILON) continue
                val t = (start.x * edge.y - start.y * edge.x) / cross
                val u = (start.x * ray.y - start.y * ray.x) / cross
                if (t >= 0f && u in 0f..1f) distance = max(distance ?: 0f, t)
            }
            return distance ?: fallback
        }

        fun reliablyCharging(previousCharge: Float?, charge: Float, firing: Boolean,
                             cooldown: Float): Boolean = previousCharge != null && firing && cooldown <= 0f &&
            charge > previousCharge + EPSILON && charge > 0f && charge < 1f

        fun sustainedHullLoss(
            defense: Defense,
            damagePerSecond: Float,
            type: DamageType,
            hitStrength: Float = damagePerSecond,
            stage: Stage = Stage.DIRECT,
            cellX: Int = -1,
            cellY: Int = -1,
        ): Float {
            if (!damagePerSecond.isFinite() || damagePerSecond <= 0f) return 0f
            val damage = damagePerSecond * if (stage == Stage.BEAM) {
                defense.beamDamageMult
            } else {
                defense.projectileDamageMult
            }
            val armorMult = type.armorMult * defense.armorDamageMult *
                if (type == DamageType.KINETIC) defense.kineticArmorMult else 1f
            var localArmor = 0f
            for (dx in -2..2) for (dy in -2..2) {
                localArmor += (defense.armor.getOrNull(cellX + dx)?.getOrNull(cellY + dy) ?: 0f) * armorWeight(dx, dy)
            }
            val effective = max(
                defense.armorRating * defense.minimumArmorFraction,
                localArmor * defense.effectiveArmorMult + defense.effectiveArmorFlat,
            )
            val strength = (hitStrength * armorMult).coerceAtLeast(EPSILON)
            val reduction = max(
                1f - defense.maximumArmorReduction.coerceIn(0f, 1f),
                strength / (strength + effective.coerceAtLeast(0f)),
            )
            return damage * reduction * type.hullMult * defense.hullDamageMult
        }

        fun estimate(defense: Defense, hits: List<Hit>, window: Window): Forecast {
            val cells = defense.armor.map { it.toFloatArray() }
            var hull = defense.hull.coerceAtLeast(0f)
            var capacity = defense.fluxCapacity.coerceAtLeast(0f)
            var shield = defense.shieldOn
            var overloadAt: Float? = null
            var lethalAt: Float? = if (hull <= 0f) 0f else null
            var hostile = Loss()
            var friendly = Loss()
            var chargeOut = Loss()
            var reaction = Loss()
            val impacts = hits.asSequence().filter {
                it.stage != Stage.CARRIER && it.time in 0f..window.total && it.damage > 0f
            }.distinctBy { Pair(it.key, it.time) }.sortedBy { it.time }.map { hit ->
                val damage = hit.damage * if (hit.stage == Stage.BEAM) defense.beamDamageMult else defense.projectileDamageMult
                val loss: Loss
                if (shield && hit.shieldCovered) {
                    val flux = (damage * hit.type.shieldMult * defense.shieldFluxPerDamage).coerceAtLeast(0f)
                    val absorbed = min(capacity, flux)
                    capacity -= absorbed
                    if (flux > 0f && capacity <= EPSILON) {
                        shield = false
                        overloadAt = hit.time
                    }
                    loss = Loss(raw = hit.damage, shieldFlux = absorbed, hardFlux = if (hit.softFlux) 0f else absorbed)
                } else {
                    val armorMult = hit.type.armorMult * defense.armorDamageMult *
                        if (hit.type == DamageType.KINETIC) defense.kineticArmorMult else 1f
                    val effective = max(defense.armorRating * defense.minimumArmorFraction,
                        weightedArmor(cells, hit.cellX, hit.cellY) * defense.effectiveArmorMult + defense.effectiveArmorFlat)
                    val strength = (hit.hitStrength * armorMult).coerceAtLeast(EPSILON)
                    val reduction = max(1f - defense.maximumArmorReduction.coerceIn(0f, 1f), strength / (strength + effective.coerceAtLeast(0f)))
                    var armorLoss = 0f
                    var hullLoss = 0f
                    for (dx in -2..2) for (dy in -2..2) {
                        val weight = armorWeight(dx, dy)
                        if (weight == 0f) continue
                        val x = if (hit.cellX >= 0 && hit.cellY >= 0) hit.cellX + dx else -1
                        val y = if (hit.cellX >= 0 && hit.cellY >= 0) hit.cellY + dy else -1
                        val cell = cells.getOrNull(x)?.getOrNull(y) ?: 0f
                        val share = damage * reduction * weight / 15f
                        val removed = min(cell, share * armorMult).coerceAtLeast(0f)
                        if (x in cells.indices && y in cells[x].indices) cells[x][y] -= removed
                        armorLoss += removed
                        hullLoss += (share - if (armorMult > 0f) removed / armorMult else share).coerceAtLeast(0f) *
                            hit.type.hullMult * defense.hullDamageMult
                    }
                    hull -= hullLoss
                    if (hull <= EPSILON && lethalAt == null) lethalAt = hit.time
                    loss = Loss(raw = hit.damage, armor = armorLoss, hull = hullLoss)
                }
                if (hit.friendly) friendly += loss else hostile += loss
                if (hit.time <= window.chargeOut) chargeOut += loss else reaction += loss
                Impact(hit, loss)
            }.toList()
            return Forecast(impacts, hostile, friendly, chargeOut, reaction, hull.coerceAtLeast(0f), overloadAt, lethalAt)
        }

        private fun armorWeight(x: Int, y: Int): Float = when {
            abs(x) == 2 && abs(y) == 2 -> 0f
            abs(x) <= 1 && abs(y) <= 1 -> 1f
            else -> 0.5f
        }
        private fun weightedArmor(cells: List<FloatArray>, x: Int, y: Int): Float {
            if (x < 0 || y < 0) return 0f
            var result = 0f
            for (dx in -2..2) for (dy in -2..2) {
                result += (cells.getOrNull(x + dx)?.getOrNull(y + dy) ?: 0f) * armorWeight(dx, dy)
            }
            return result
        }
        private fun angle(point: Point) = Math.toDegrees(atan2(point.y, point.x).toDouble()).toFloat()
        private fun direction(angle: Float): Point {
            val radians = Math.toRadians(angle.toDouble())
            return Point(cos(radians).toFloat(), sin(radians).toFloat())
        }
        private fun point(vector: Vector2f) = Point(vector.x, vector.y)
    }

    internal data class CarrierObservation(
        val key: Any,
        val source: Any?,
        val payloadSpec: String,
        val position: Point,
    )

    internal data class ProjectileObservation(
        val key: Any,
        val source: Any?,
        val spec: String,
        val position: Point,
    )

    internal class Lineage {
        private data class Carrier(
            val source: Any,
            val payloadSpec: String,
            var position: Point,
            var lastSeen: Float,
            var present: Boolean,
        )

        private val carriers = IdentityHashMap<Any, Carrier>()
        private val ancestors = IdentityHashMap<Any, Set<Any>>()
        private val seenProjectiles = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private var lastTime = 0f
        val linkedPayloads: Set<Any>
            get() = identitySet<Any>().also { it.addAll(ancestors.keys) }

        fun observe(
            observedCarriers: List<CarrierObservation>,
            projectiles: List<ProjectileObservation>,
            now: Float,
        ) {
            lastTime = now
            val currentProjectiles = identitySet<Any>()
            projectiles.forEach { currentProjectiles.add(it.key) }
            seenProjectiles.retainAll(currentProjectiles)
            val newProjectiles = projectiles.filter { seenProjectiles.add(it.key) }
            carriers.values.forEach { it.present = false }
            for (observation in observedCarriers) {
                val source = observation.source ?: continue
                val carrier = carriers[observation.key]
                if (carrier == null) {
                    carriers[observation.key] = Carrier(
                        source, observation.payloadSpec, observation.position, now, true,
                    )
                } else {
                    carrier.position = observation.position
                    carrier.lastSeen = now
                    carrier.present = true
                }
            }
            for (projectile in newProjectiles) {
                val source = projectile.source ?: continue
                if (ancestors.containsKey(projectile.key)) continue
                val inherited = identitySet<Any>()
                for ((key, carrier) in carriers) {
                    if (key === projectile.key || carrier.source !== source ||
                        carrier.payloadSpec != projectile.spec || now - carrier.lastSeen > SPLIT_GRACE ||
                        (carrier.position - projectile.position).length() > MAX_SPLIT_DISTANCE
                    ) {
                        continue
                    }
                    inherited.add(key)
                    ancestors[key]?.let(inherited::addAll)
                }
                if (inherited.isNotEmpty()) ancestors[projectile.key] = inherited
            }
            retainAncestors(currentProjectiles)
            carriers.entries.removeIf { !it.value.present && now - it.value.lastSeen > SPLIT_GRACE }
        }

        fun link(key: Any, parent: Any) {
            val inherited = identitySet<Any>()
            inherited.add(parent)
            ancestors[parent]?.let(inherited::addAll)
            ancestors[key]?.let(inherited::addAll)
            ancestors[key] = inherited
        }

        fun retainActive(activeKeys: Set<Any>) {
            retainAncestors(activeKeys)
            carriers.entries.removeIf { !it.value.present && lastTime - it.value.lastSeen > SPLIT_GRACE }
        }

        fun activeParents(activeKeys: Set<Any>): Set<Any> {
            val result = identitySet<Any>()
            for (key in activeKeys) ancestors[key]?.let(result::addAll)
            return result
        }

        private fun retainAncestors(activeKeys: Set<Any>) {
            ancestors.keys.removeIf { key ->
                if (key in activeKeys) return@removeIf false
                val carrier = carriers[key] ?: return@removeIf true
                !carrier.present && lastTime - carrier.lastSeen > SPLIT_GRACE
            }
        }

        companion object {
            private const val MAX_SPLIT_DISTANCE = 500f
            private const val SPLIT_GRACE = 0.5f

            private fun <T> identitySet(): MutableSet<T> =
                java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        }
    }

    private var lastRejection: String? = null
    private val activeAttackKeys = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val carrierAttackKeys = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val lineage = Lineage()

    fun sample(engine: CombatEngineAPI, ship: ShipAPI, window: Window): Forecast? {
        val motion = Motion(ship, surface = true)
        val exposure = motion.exposure(window) ?: return rejectSample(ship, checkNotNull(motion.rejectionReason))
        try {
            checkSamplingStep(exposure.total, MOTION_STEP)
            val hits = ArrayList<Hit>()
            val seen = java.util.Collections.newSetFromMap(IdentityHashMap<DamagingProjectileAPI, Boolean>())
            val projectiles = engine.projectiles + engine.missiles
            lineage.observe(
                projectiles.mapNotNull { projectile ->
                    val payloadSpec = splitPayloadSpec(projectile) ?: return@mapNotNull null
                    CarrierObservation(projectile, projectile.source, payloadSpec, point(projectile.location))
                },
                projectiles.mapNotNull { projectile ->
                    val spec = projectile.projectileSpecId ?: return@mapNotNull null
                    ProjectileObservation(projectile, projectile.source, spec, point(projectile.location))
                },
                engine.getTotalElapsedTime(false),
            )
            for (projectile in projectiles) {
                if (!seen.add(projectile)) continue
                if (projectile.source !== ship && payloadCarrier(projectile)) {
                    activeAttackKeys.add(projectile)
                    carrierAttackKeys.add(projectile)
                }
                projectileHit(projectile, ship, exposure, motion)?.let(hits::add)
            }
            activeAttackKeys.addAll(lineage.linkedPayloads)
            val weapons = java.util.Collections.newSetFromMap(IdentityHashMap<WeaponAPI, Boolean>())
            val liveBeamWeapons = IdentityHashMap<WeaponAPI, MutableList<com.fs.starfarer.api.combat.BeamAPI>>()
            val liveBeams = java.util.Collections.newSetFromMap(
                IdentityHashMap<com.fs.starfarer.api.combat.BeamAPI, Boolean>(),
            )
            liveBeams.addAll(engine.beams)
            for (beam in liveBeams) {
                beam.damageTarget?.let { beamTargets[beam] = it }
                if (beam.source === ship) continue
                liveBeamWeapons.getOrPut(beam.weapon) { ArrayList() }.add(beam)
            }
            beamTargets.keys.retainAll(liveBeams)
            val now = engine.getTotalElapsedTime(false)
            val retracking = retrackingBeams(engine.missiles, liveBeamWeapons.values.flatten(), ship)
            val demPayloads = demPayloadWeapons(engine.missiles, liveBeamWeapons.values.flatten(), ship)
            for ((weapon, missile) in demPayloads) {
                missile.spec.behaviorJSON?.let { demBehaviors[weapon] = it }
            }
            for ((beam, missile) in retracking) lineage.link(beam, missile)
            for ((weapon, missile) in demPayloads) lineage.link(weapon, missile)
            for ((weapon, beams) in liveBeamWeapons) if (weapons.add(weapon)) {
                beamHits(weapon, ship, exposure, motion, now, hits, beams, retracking, demPayloads[weapon])
            }
            for ((weapon, missile) in demPayloads) {
                if (weapon !in liveBeamWeapons && weapons.add(weapon)) {
                    beamHits(weapon, ship, exposure, motion, now, hits, emptyList(), demCarrier = missile)
                }
            }
            for (source in engine.ships) {
                if (source === ship || !source.isAlive || source.isHulk || source.isPhased) continue
                for (weapon in source.allWeapons) {
                    if (weapon.isBeam && weapon !in liveBeamWeapons && weapons.add(weapon)) {
                        beamHits(weapon, ship, exposure, motion, now, hits, emptyList(),
                            demCarrier = demPayloads[weapon])
                    }
                }
            }
            beamHistory.keys.retainAll(weapons)
            demBehaviors.keys.retainAll(weapons)
            activeAttackKeys.addAll(hits.filter { !it.friendly }.map { it.key })
            activeAttackKeys.removeIf { !attackStillActive(it, seen, weapons, liveBeams, ship, demPayloads) }
            carrierAttackKeys.retainAll(activeAttackKeys)
            lineage.retainActive(activeAttackKeys)
            val activeKeys = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            activeKeys.addAll(activeAttackKeys)
            activeKeys.addAll(lineage.activeParents(activeAttackKeys))
            val estimated = estimate(defense(ship), hits, exposure)
            val impactKeys = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            estimated.impacts.mapTo(impactKeys) { it.hit.key }
            val activeImpactKeys = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            impactKeys.filterTo(activeImpactKeys) { it in activeAttackKeys }
            activeKeys.addAll(lineage.activeParents(activeImpactKeys))
            val forecast = estimated.copy(
                activeThreatKeys = activeKeys,
                lineageThreatKeys = lineage.activeParents(impactKeys),
                carrierThreatKeys = hits.filter { it.stage == Stage.CARRIER && !it.friendly }.mapTo(linkedSetOf()) { it.key },
                impactAncestorKeys = impactKeys.associateWith { key -> lineage.activeParents(setOf(key)) },
            )
            lastRejection = null
            return forecast
        } catch (failure: SamplingFailure) {
            return rejectSample(ship, checkNotNull(failure.message))
        }
    }

    private fun attackStillActive(
        key: Any,
        projectiles: Set<DamagingProjectileAPI>,
        weapons: Set<WeaponAPI>,
        beams: Set<com.fs.starfarer.api.combat.BeamAPI>,
        ship: ShipAPI,
        demPayloads: Map<WeaponAPI, MissileAPI>,
    ): Boolean = when (key) {
        is DamagingProjectileAPI -> key in projectiles && !key.isExpired &&
            (key in carrierAttackKeys || !key.didDamage()) &&
            (key !is MissileAPI || !key.isFizzling || key.isArmedWhileFizzling) &&
            !irreversiblyMissed(key, ship)
        is WeaponAPI -> key in weapons && (key.isFiring || key.beams.any { it.damage.damage > 0f && it.brightness > 0f } ||
            reliablyCharging(beamHistory[key]?.priorCharge, key.chargeLevel, key.isFiring, key.cooldownRemaining) ||
            demPayloads[key]?.let { it in projectiles && !it.isExpired } == true)
        is com.fs.starfarer.api.combat.BeamAPI -> key in beams && key.damage.damage > 0f && key.brightness > 0f
        else -> false
    }

    private fun irreversiblyMissed(projectile: DamagingProjectileAPI, ship: ShipAPI): Boolean {
        if (projectile is MissileAPI || projectile in carrierAttackKeys || projectile.explosionSpecIfExplosion != null) {
            return false
        }
        val relative = point(projectile.location) - point(ship.location)
        val velocity = point(projectile.velocity) - point(ship.velocity)
        val speedSquared = velocity.dot(velocity)
        if (speedSquared <= EPSILON) return false
        val closestApproach = -relative.dot(velocity) / speedSquared
        return closestApproach < 0f && relative.length() > ship.collisionRadius + projectile.collisionRadius
    }

    private fun retrackingBeams(
        missiles: List<MissileAPI>,
        beams: List<com.fs.starfarer.api.combat.BeamAPI>,
        ship: ShipAPI,
    ): Map<com.fs.starfarer.api.combat.BeamAPI, MissileAPI> {
        val result = IdentityHashMap<com.fs.starfarer.api.combat.BeamAPI, MissileAPI>()
        if (beams.isEmpty()) return result
        for (missile in missiles) {
            if (missile.isExpired || missile.unwrappedMissileAI !is com.fs.starfarer.api.impl.combat.dem.DEMScript) continue
            val behavior = missile.spec?.behaviorJSON ?: continue
            if (behavior.optBoolean("bombPumped", false) || behavior.optBoolean("fadeOutEngineWhenFiring", false)) continue
            val at = point(missile.location)
            for (beam in beams) {
                val source = beam.weapon.ship
                if (source.owner != missile.owner || beamTargets[beam] !== ship) continue
                val distance = (point(source.location) - at).length()
                val previous = result[beam]
                if (distance <= DEM_DRONE_MATCH_DISTANCE && (previous == null ||
                        distance < (point(source.location) - point(previous.location)).length())
                ) {
                    result[beam] = missile
                }
            }
        }
        return result
    }

    private fun demPayloadWeapons(
        missiles: List<MissileAPI>,
        beams: List<com.fs.starfarer.api.combat.BeamAPI>,
        ship: ShipAPI,
    ): Map<WeaponAPI, MissileAPI> {
        val result = IdentityHashMap<WeaponAPI, MissileAPI>()
        if (beams.isEmpty()) return result
        for (missile in missiles) {
            if (missile.isExpired || missile.unwrappedMissileAI !is com.fs.starfarer.api.impl.combat.dem.DEMScript) continue
            val behavior = missile.spec?.behaviorJSON ?: continue
            val payloadId = behavior.optString("payloadWeaponId", "")
            if (payloadId.isEmpty()) continue
            val targetingId = behavior.optString("targetingLaserId", "")
            val at = point(missile.location)
            for (beam in beams) {
                val drone = beam.weapon?.ship ?: continue
                if (drone === ship || !drone.isAlive || drone.isHulk || drone.owner != missile.owner) continue
                val beamWeaponId = beam.weapon.id
                if (beamWeaponId != payloadId && beamWeaponId != targetingId) continue
                val distance = (point(drone.location) - at).length()
                if (distance > DEM_DRONE_MATCH_DISTANCE || !beamLocksOn(beam, ship)) continue
                for (weapon in drone.allWeapons) {
                    if (!weapon.isBeam || weapon.id != payloadId) continue
                    val previous = result[weapon]
                    if (previous == null || distance < (point(drone.location) - point(previous.location)).length()) {
                        result[weapon] = missile
                    }
                }
            }
        }
        return result
    }

    private fun beamLocksOn(beam: com.fs.starfarer.api.combat.BeamAPI, ship: ShipAPI): Boolean {
        val from = point(beam.from)
        val ray = point(beam.to) - from
        val length = ray.length()
        if (length <= EPSILON) return false
        val reach = maxOf(length, beam.weapon?.range ?: length)
        val toShip = point(ship.location) - from
        val along = (toShip.dot(ray) / length).coerceIn(0f, reach)
        val closest = toShip - ray * (along / length)
        return closest.length() <= ship.collisionRadius + beam.width / 2f + DEM_LOCK_SWEEP_TOLERANCE
    }

    private fun rejectSample(ship: ShipAPI, reason: String): Forecast? {
        if (lastRejection != reason) Global.getLogger(PhaseThreat::class.java).warn(
            "Phase threat prediction unavailable: $reason (ship=${ship.id}, state=${ship.phaseCloak?.state}, timeMult=${ship.mutableStats.timeMult.modifiedValue})",
        )
        lastRejection = reason
        beamHistory.clear()
        return null
    }

    private fun splitPayloadSpec(projectile: DamagingProjectileAPI): String? =
        (projectile as? MissileAPI)?.spec?.behaviorJSON?.optString("projectileSpec", "")
            ?.takeIf { it.isNotEmpty() }

    private fun payloadCarrier(projectile: DamagingProjectileAPI): Boolean {
        val missile = projectile as? MissileAPI ?: return false
        return missile.isMirv || missile.spec.behaviorJSON?.optString("payloadWeaponId", "")?.isNotEmpty() == true
    }

    private fun projectileHit(projectile: DamagingProjectileAPI, ship: ShipAPI, window: Window, motion: Motion): Hit? {
        if (projectile.isExpired || projectile.source === ship || projectile.damagedAlready?.contains(ship) == true ||
            !canCollide(projectile.collisionClass, projectile.owner == ship.owner)) return null
        val missile = projectile as? MissileAPI
        val explosion = projectile.explosionSpecIfExplosion
        val carrier = explosion == null && payloadCarrier(projectile)
        if (explosion == null && projectile.didDamage()) return null
        if (missile != null && missile.isFizzling && !missile.isArmedWhileFizzling) return null
        val origin = point(projectile.location)
        val velocity = point(projectile.velocity)
        val end = min(remainingLife(projectile, window.total), window.total)
        val radius = ship.collisionRadius + (explosion?.radius ?: projectile.collisionRadius)
        val direct = intercept(origin, velocity, motion, radius, 0f, end)
        val guided = if (direct == null && missile != null && missile.isGuided && !missile.isFizzling &&
            (missile.unwrappedMissileAI as? GuidedMissileAI)?.target === ship && end > 0f
        ) {
            guidedTrajectory(origin, velocity, radius, end,
                Guidance(true, missile.facing, missile.maxTurnRate), motion::position)
        } else null
        val eta = direct ?: guided?.approach?.hitTime ?: return null
        if (!carrier && missile != null && !missile.isArmed && missile.armingTime > missile.elapsed + eta) return null
        val relativeImpact = guided?.relativePosition ?: (origin + velocity * eta - motion.position(eta))
        var impact = relativeImpact
        if (impact.length() > ship.collisionRadius && impact.length() > EPSILON) {
            impact *= ship.collisionRadius / impact.length()
        }
        val damage = if (carrier) 0f else if (explosion == null) projectile.damageAmount else {
            val distance = (relativeImpact.length() - ship.collisionRadius).coerceAtLeast(0f)
            val falloff = ((distance - explosion.coreRadius) / (explosion.radius - explosion.coreRadius).coerceAtLeast(EPSILON)).coerceIn(0f, 1f)
            projectile.damageAmount * (1f - falloff * (1f - explosion.minDamage / explosion.maxDamage.coerceAtLeast(EPSILON)))
        }
        val stage = if (carrier) Stage.CARRIER else if (explosion == null) Stage.DIRECT else Stage.EXPLOSION
        return hit(ship, projectile, eta, damage, projectile.damageType, projectile.owner == ship.owner,
            stage, impact,
            projectile.damage.stats?.hitStrengthBonus?.computeEffective(damage) ?: damage, projectile.damage.isSoftFlux, motion)
    }

    private fun beamHits(weapon: WeaponAPI, ship: ShipAPI, window: Window, targetMotion: Motion,
                         now: Float, hits: MutableList<Hit>,
                         liveBeams: List<com.fs.starfarer.api.combat.BeamAPI>,
                         tracking: Map<com.fs.starfarer.api.combat.BeamAPI, MissileAPI> = emptyMap(),
                         demCarrier: MissileAPI? = null) {
        val spec = weapon.spec as? BeamWeaponSpecAPI ?: return
        val previous = beamHistory[weapon]
        val emitting = liveBeams.any { it.brightness > 0f }
        val beams = liveBeams.filter { it.damage.damage > 0f && it.brightness > 0f }
        val sourceMotion = Motion(weapon.ship)
        val emittingLocalTime = if (!emitting) null else if (previous?.emittingLocalTime != null) {
            previous.emittingLocalTime + previous.motion.localTime((now - previous.time).coerceAtLeast(0f))
        } else 0f
        val priorCharge = if (previous?.time == now) previous.priorCharge else previous?.charge
        beamHistory[weapon] = BeamHistory(now, weapon.chargeLevel, emittingLocalTime, priorCharge, sourceMotion)
        if (weapon.isDisabled || weapon.isDecorative || !canCollide(spec.collisionClass, weapon.ship.owner == ship.owner)) return
        val charging = reliablyCharging(priorCharge, weapon.chargeLevel, weapon.isFiring, weapon.cooldownRemaining)
        val behavior = demCarrier?.spec?.behaviorJSON ?: demBehaviors[weapon]
        val targetingElapsed = if (beams.isEmpty() && behavior != null) {
            val targetingId = behavior.optString("targetingLaserId", "")
            weapon.ship.allWeapons.firstOrNull { it.id == targetingId }?.let { beamHistory[it]?.emittingLocalTime }
        } else null
        val demPrefire = beams.isEmpty() && !charging && demCarrier != null && targetingElapsed != null
        if (beams.isEmpty() && !charging && !demPrefire) return
        val sourceMult = weapon.ship.mutableStats.timeMult.modifiedValue
        val localEnd = sourceMotion.localTime(window.total)
        if (!sourceMult.isFinite() || sourceMult < 0f || !localEnd.isFinite() || localEnd < 0f) {
            throw SamplingFailure("invalid beam source clock")
        }
        if (sourceMult == 0f) return
        val delayLocal = when {
            beams.isNotEmpty() -> 0f
            charging -> (1f - weapon.chargeLevel) * spec.chargeupTime
            else -> (behavior!!.optDouble("targetingTime", 1.0).toFloat() - targetingElapsed!!).coerceAtLeast(0f)
        }
        val delay = sourceMotion.battleTime(delayLocal)
        if (!delay.isFinite() || delay < 0f) throw SamplingFailure("invalid beam charge clock")
        if (delay >= window.total) return
        val bombPumped = behavior?.optBoolean("bombPumped", false) == true
        val end = when {
            demPrefire -> min(window.total, sourceMotion.battleTime(delayLocal +
                behavior!!.optDouble("firingTime", 1.25).toFloat().coerceAtLeast(0f)))
            beams.isNotEmpty() && bombPumped -> min(window.total, sourceMotion.battleTime(
                (behavior!!.optDouble("firingTime", 1.25).toFloat() - (emittingLocalTime ?: 0f)).coerceAtLeast(0f)))
            weapon.isBurstBeam -> min(window.total, sourceMotion.battleTime(delayLocal +
                (spec.chargeupTime + spec.burstDuration + spec.chargedownTime -
                    (emittingLocalTime ?: 0f)).coerceAtLeast(0f)))
            else -> window.total
        }
        checkSamplingStep(end, BEAM_STEP)
        val trackState = IdentityHashMap<com.fs.starfarer.api.combat.BeamAPI, FloatArray>()
        val trackStart = if (ship.isPhased) window.chargeOut else 0f
        val demTracksFire = behavior != null && !bombPumped && !behavior.optBoolean("fadeOutEngineWhenFiring", false)
        val payloadRange = if (demPrefire) {
            (weapon.range - behavior!!.optDouble("targetingLaserRange", 0.0).toFloat()).coerceAtLeast(0f)
        } else weapon.range
        var time = delay
        while (time < end) {
            val boundary = if (time < window.chargeOut) window.chargeOut else window.total
            val impactTime = nextSample(time, min(boundary, end), BEAM_STEP)
            val localDt = sourceMotion.localTime(impactTime) - sourceMotion.localTime(time)
            if (!localDt.isFinite() || localDt < 0f) throw SamplingFailure("invalid beam local time step")
            val center = targetMotion.position(impactTime)
            val arcPoint = sourceMotion.currentFrame(center, impactTime)
            if (beams.isEmpty()) {
                val sourceTime = if (bombPumped) min(impactTime, delay) else impactTime
                val from = sourceMotion.mountedPoint(point(weapon.location), sourceTime)
                val relative = center - from
                val aim = if (!demPrefire) weapon.currAngle + sourceMotion.rotation(impactTime) else {
                    val trackEnd = if (demTracksFire) impactTime else min(impactTime, delay)
                    trackedFacing(weapon.currAngle, demCarrier!!.maxTurnRate, trackStart, trackEnd) { at ->
                        angle(targetMotion.position(at) - sourceMotion.mountedPoint(point(weapon.location), at))
                    }
                }
                if ((demCarrier != null || weapon.distanceFromArc(Vector2f(arcPoint.x, arcPoint.y)) <= 0f) &&
                    beamCovers(relative, aim, payloadRange, ship.collisionRadius)) {
                    val dps = weapon.damage.damage
                    hits += hit(ship, weapon, impactTime, dps * localDt, weapon.damageType,
                        weapon.ship.owner == ship.owner, Stage.BEAM, from - center,
                        beamStrength(weapon, dps), !weapon.damage.isForceHardFlux, targetMotion)
                }
            } else for (beam in beams) {
                val ray = point(beam.to) - point(beam.from)
                if (ray.length() <= EPSILON) continue
                val tracker = tracking[beam]
                val carrierEnd = if (demCarrier != null && !bombPumped) {
                    sourceMotion.battleTime(remainingLife(demCarrier, sourceMotion.localTime(window.total)))
                } else impactTime
                val beamTime = min(impactTime, carrierEnd)
                val beamDt = sourceMotion.localTime(beamTime) - sourceMotion.localTime(time)
                if (beamDt <= EPSILON) continue
                val beamCenter = targetMotion.position(beamTime)
                val from = if (bombPumped) point(beam.from) else sourceMotion.mountedPoint(point(beam.from), beamTime)
                val aim = when {
                    bombPumped -> angle(ray)
                    tracker == null -> angle(ray) + sourceMotion.rotation(beamTime)
                    else -> {
                        val state = trackState.getOrPut(beam) { floatArrayOf(angle(ray), 0f) }
                        state[0] = trackedFacing(state[0], tracker.maxTurnRate, max(state[1], trackStart), beamTime) { at ->
                            angle(targetMotion.position(at) - sourceMotion.mountedPoint(point(beam.from), at))
                        }
                        state[1] = beamTime
                        state[0]
                    }
                }
                val range = min(weapon.range, if (beam.damageTarget != null && beam.damageTarget !== ship) ray.length() else weapon.range)
                if (beamCovers(beamCenter - from, aim, range, ship.collisionRadius + beam.width / 2f)) {
                    val dps = beam.damage.damage
                    hits += hit(ship, beam, beamTime, dps * beamDt, beam.damage.type,
                        weapon.ship.owner == ship.owner, Stage.BEAM, from - beamCenter,
                        beamStrength(weapon, dps), !beam.damage.isForceHardFlux, targetMotion)
                }
            }
            time = impactTime
        }
    }

    private fun beamStrength(weapon: WeaponAPI, dps: Float): Float {
        val strength = dps * Global.getSettings().getFloat("dpsToHitStrengthMult")
        return weapon.ship.mutableStats.hitStrengthBonus.computeEffective(strength)
    }

    private fun hit(ship: ShipAPI, key: Any, time: Float, damage: Float, type: DamageType, friendly: Boolean,
                    stage: Stage, relativeImpact: Point, strength: Float, softFlux: Boolean, motion: Motion): Hit {
        val length = relativeImpact.length()
        val heading = direction(motion.heading(time))
        val local = Point(relativeImpact.dot(heading), relativeImpact.dot(Point(-heading.y, heading.x)))
        val radius = surfaceDistance(local, ship.exactBounds?.origSegments?.map { point(it.p1) to point(it.p2) }
            ?: emptyList(), ship.collisionRadius)
        val localSurface = if (length > EPSILON) local * (min(length, radius) / length) else local
        val currentHeading = direction(ship.facing)
        val surface = Point(localSurface.x * currentHeading.x - localSurface.y * currentHeading.y,
            localSurface.x * currentHeading.y + localSurface.y * currentHeading.x)
        val location = Vector2f(ship.location.x + surface.x, ship.location.y + surface.y)
        val cell = ship.armorGrid.getCellAtLocation(location)
        val shield = ship.shield
        val covered = shield != null && shield.isOn && shield.isWithinArc(location)
        return Hit(key, time, damage, type, friendly, stage, covered, cell?.get(0) ?: -1, cell?.get(1) ?: -1, strength, softFlux)
    }

    fun defense(ship: ShipAPI): Defense {
        val stats = ship.mutableStats
        val effective = stats.effectiveArmorBonus
        val flat = effective.computeEffective(0f)
        return Defense(ship.hitpoints, ship.armorGrid.grid.map { it.toList() }, ship.armorGrid.armorRating,
            stats.minArmorFraction.modifiedValue, stats.maxArmorDamageReduction.modifiedValue,
            effective.computeEffective(1f) - flat, flat, stats.armorDamageTakenMult.modifiedValue,
            stats.kineticArmorDamageTakenMult.modifiedValue, stats.hullDamageTakenMult.modifiedValue,
            ship.shield?.isOn == true && !ship.fluxTracker.isOverloadedOrVenting,
            (ship.maxFlux - ship.currFlux).coerceAtLeast(0f),
            (ship.shield?.fluxPerPointOfDamage ?: 1f) * stats.shieldDamageTakenMult.modifiedValue,
            stats.beamDamageTakenMult.modifiedValue, stats.projectileDamageTakenMult.modifiedValue)
    }

    private fun canCollide(collision: CollisionClass, friendly: Boolean): Boolean = when (collision) {
        CollisionClass.NONE -> false
        CollisionClass.PROJECTILE_NO_FF, CollisionClass.MISSILE_NO_FF, CollisionClass.HITS_SHIPS_ONLY_NO_FF -> !friendly
        else -> true
    }
}
