package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f

/** 坍缩折跃系统 AI：贴近目标后切入，或在高压状态下脱离。 */
class CollapseShiftSystemAI : ShipSystemAIScript {

    companion object {
        private const val ENGAGE_RANGE = 900f
        private const val DISENGAGE_RANGE = 700f
        private const val HIGH_FLUX_THRESHOLD = 0.75f
        private const val LOW_HULL_THRESHOLD = 0.4f
        private const val VIEW_RANGE = 2200f
        private const val WEAPON_RANGE_FALLBACK = 700f
        private const val ENEMY_STRENGTH_ABORT_MULT = 3.0f
        private const val HARD_FLUX_FALLBACK_THRESHOLD = 0.65f
        private const val PHASE_RUN_RANGE = 1300f
        private const val BEHIND_ARC = 95f
        private const val ADVANTAGE_ARC = 120f
        private const val ADVANTAGE_MIN_RANGE_MULT = 0.35f
        private const val ADVANTAGE_MAX_RANGE_MULT = 1.05f
        private const val BEHIND_OFFSET = 280f
        private const val FLANK_OFFSET = 360f
        private const val RETREAT_OFFSET = 900f
        private const val SAFE_RETREAT_RANGE = 2400f
        private const val GLOBAL_RETREAT_RANGE = 4200f
        private const val MAP_EDGE_PADDING = 900f
        private const val SCAN_INTERVAL_SEC = 0.4f
        private const val OFFENSIVE_COMMIT_SEC = 3.0f
        private const val RETREAT_RECOVERY_SEC = 4.0f
        private const val CRITICAL_HARD_FLUX_THRESHOLD = 0.85f
        private const val CRITICAL_HULL_THRESHOLD = 0.25f
        private const val REENGAGE_FLUX_THRESHOLD = 0.45f
        private const val REENGAGE_HARD_FLUX_THRESHOLD = 0.35f
    }

    private enum class ShiftIntent {
        OFFENSE,
        RETREAT,
    }

    private var ship: ShipAPI? = null
    private var system: ShipSystemAPI? = null
    private var engine: CombatEngineAPI? = null
    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)
    private var cachedTarget: ShipAPI? = null
    private var lastIntent: ShiftIntent? = null
    private var lastIntentAt: Float = -999f

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.system = system
        this.engine = engine
        scanInterval.forceIntervalElapsed()
        cachedTarget = null
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val ship = this.ship ?: return
        val system = this.system ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk) return
        if (system.isOn || system.state == ShipSystemAPI.SystemState.IN || system.state == ShipSystemAPI.SystemState.ACTIVE) return
        if (!system.canBeActivated()) return

        scanInterval.advance(amount)
        if (scanInterval.intervalElapsed() || cachedTarget == null || !isValidTarget(ship, engine, cachedTarget)) {
            cachedTarget = findNearestTarget(ship, engine, target)
        }

        val fluxLevel = try {
            ship.fluxTracker?.fluxLevel ?: 0f
        } catch (_: Throwable) {
            0f
        }
        val hardFluxLevel = hardFluxLevel(ship)
        val hullLevel = try {
            ship.hullLevel
        } catch (_: Throwable) {
            1f
        }
        val flags = ship.aiFlags
        val enemies = visibleEnemies(ship, engine, VIEW_RANGE)
        if (missileThreat(ship, engine) && shieldNotCoveringDanger(ship, missileDangerDir)) {
            try { ship.useSystem() } catch (_: Throwable) {}
            return
        }

        if (enemies.isEmpty()) {
            if (flags.hasFlag(ShipwideAIFlags.AIFlags.PURSUING) || flags.hasFlag(ShipwideAIFlags.AIFlags.MOVEMENT_DEST) || flags.hasFlag(ShipwideAIFlags.AIFlags.MANEUVER_TARGET)) {
                try { ship.useSystem() } catch (_: Throwable) {}
            }
            return
        }

        val actualTarget = cachedTarget ?: enemies.minByOrNull { MathUtils.getDistance(ship.location, it.location) } ?: return
        val distance = MathUtils.getDistance(ship.location, actualTarget.location)
        val weaponRange = longestWeaponRange(ship).coerceAtLeast(WEAPON_RANGE_FALLBACK)
        val enemyStrength = enemies.filter { MathUtils.getDistance(actualTarget.location, it.location) <= 650f }.sumOf { strength(it).toDouble() }.toFloat()
        val ownStrength = strength(ship).coerceAtLeast(1f)
        val lockedTarget = try { ship.shipTarget } catch (_: Throwable) { null }
        val hasReserveCharge = system.ammo > 1 || system.maxAmmo <= 1
        val now = engine.getTotalElapsedTime(false)
        val retreatAllowed = retreatAllowed(now, hardFluxLevel, hullLevel, flags)
        val offenseAllowed = offenseAllowed(now, fluxLevel, hardFluxLevel)
        val alreadyAdvantaged = lockedTarget === actualTarget && hasTargetAdvantage(ship, actualTarget, distance, weaponRange)
        val phaseRun = offenseAllowed && !alreadyAdvantaged && wantsPhaseDisplacerRun(ship, actualTarget, flags, distance)
        val wantsBackstab = offenseAllowed && !alreadyAdvantaged && lockedTarget === actualTarget && hasReserveCharge && fluxLevel <= 0.65f && distance <= weaponRange * 1.35f && enemyStrength <= ownStrength * ENEMY_STRENGTH_ABORT_MULT
        val fallbackRetreat = if (retreatAllowed && hardFluxLevel >= HARD_FLUX_FALLBACK_THRESHOLD) {
            farthestSafeRetreatDestination(engine, ship, enemies)
        } else {
            null
        }

        val shouldEngage = offenseAllowed && distance > weaponRange * 1.05f && distance <= VIEW_RANGE && fluxLevel <= 0.55f && enemyStrength <= ownStrength * ENEMY_STRENGTH_ABORT_MULT
        val shouldEscape = retreatAllowed && distance <= DISENGAGE_RANGE && (fluxLevel >= HIGH_FLUX_THRESHOLD || hullLevel <= LOW_HULL_THRESHOLD)

        var intent: ShiftIntent? = null
        val destination = when {
            fallbackRetreat != null -> {
                intent = ShiftIntent.RETREAT
                fallbackRetreat
            }
            shouldEscape -> {
                intent = ShiftIntent.RETREAT
                retreatDestination(ship, actualTarget)
            }
            phaseRun || wantsBackstab -> {
                intent = ShiftIntent.OFFENSE
                backstabDestination(actualTarget, ship.collisionRadius)
            }
            shouldEngage -> {
                intent = ShiftIntent.OFFENSE
                flankDestination(ship, actualTarget)
            }
            else -> null
        }

        if (destination != null) {
            flags.setFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, 0.6f, destination)
            flags.setFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, 0.6f, actualTarget)
            try {
                ship.useSystem()
                lastIntent = intent
                lastIntentAt = now
            } catch (_: Throwable) {
            }
        }
    }

    private fun retreatAllowed(now: Float, hardFluxLevel: Float, hullLevel: Float, flags: ShipwideAIFlags): Boolean {
        if (hardFluxLevel >= CRITICAL_HARD_FLUX_THRESHOLD || hullLevel <= CRITICAL_HULL_THRESHOLD) return true
        if (flags.hasFlag(ShipwideAIFlags.AIFlags.NEEDS_HELP) || flags.hasFlag(ShipwideAIFlags.AIFlags.IN_CRITICAL_DPS_DANGER)) return true
        return lastIntent != ShiftIntent.OFFENSE || now - lastIntentAt >= OFFENSIVE_COMMIT_SEC
    }

    private fun offenseAllowed(now: Float, fluxLevel: Float, hardFluxLevel: Float): Boolean {
        if (lastIntent != ShiftIntent.RETREAT) return true
        if (now - lastIntentAt >= RETREAT_RECOVERY_SEC) return true
        return fluxLevel <= REENGAGE_FLUX_THRESHOLD && hardFluxLevel <= REENGAGE_HARD_FLUX_THRESHOLD
    }

    private fun wantsPhaseDisplacerRun(ship: ShipAPI, target: ShipAPI, flags: ShipwideAIFlags, distance: Float): Boolean {
        if (flags.hasFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN_IN_GOOD_SPOT)) return true
        if (flags.hasFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN_FROM_BEHIND_DIST_CRITICAL)) return true
        if (flags.hasFlag(ShipwideAIFlags.AIFlags.IN_ATTACK_RUN) && distance <= PHASE_RUN_RANGE) return true
        if (flags.hasFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN) && isBehindTarget(ship, target, BEHIND_ARC)) return true
        return false
    }

    private fun hasTargetAdvantage(ship: ShipAPI, target: ShipAPI, distance: Float, weaponRange: Float): Boolean {
        if (!isBehindTarget(ship, target, ADVANTAGE_ARC)) return false
        val minRange = (weaponRange * ADVANTAGE_MIN_RANGE_MULT).coerceAtLeast(target.collisionRadius + ship.collisionRadius + 120f)
        val maxRange = (weaponRange * ADVANTAGE_MAX_RANGE_MULT).coerceAtLeast(minRange + 120f)
        return distance in minRange..maxRange
    }

    private fun hardFluxLevel(ship: ShipAPI): Float {
        val tracker = ship.fluxTracker ?: return 0f
        val maxFlux = ship.maxFlux.coerceAtLeast(1f)
        return try { tracker.hardFlux / maxFlux } catch (_: Throwable) { 0f }
    }

    private fun farthestSafeRetreatDestination(engine: CombatEngineAPI, ship: ShipAPI, enemies: List<ShipAPI>): Vector2f {
        val pressureCenter = pressureCenter(ship, enemies)
        val away = Vector2f.sub(ship.location, pressureCenter, null)
        if (away.lengthSquared() <= 1f) away.set(Misc.getUnitVectorAtDegreeAngle(ship.facing + 180f)) else away.normalise()
        val side = Vector2f(-away.y, away.x)
        val friends = visibleFriends(ship, engine, GLOBAL_RETREAT_RANGE)
        val candidates = ArrayList<Vector2f>(32)
        candidates += Vector2f(ship.location.x + away.x * SAFE_RETREAT_RANGE, ship.location.y + away.y * SAFE_RETREAT_RANGE)
        for (step in 1..4) {
            val dist = SAFE_RETREAT_RANGE * (1f - step * 0.12f)
            val lateral = SAFE_RETREAT_RANGE * step * 0.18f
            candidates += Vector2f(ship.location.x + away.x * dist + side.x * lateral, ship.location.y + away.y * dist + side.y * lateral)
            candidates += Vector2f(ship.location.x + away.x * dist - side.x * lateral, ship.location.y + away.y * dist - side.y * lateral)
        }
        for (range in listOf(3000f, GLOBAL_RETREAT_RANGE)) {
            candidates += Vector2f(ship.location.x + away.x * range, ship.location.y + away.y * range)
            candidates += Vector2f(ship.location.x + away.x * range + side.x * range * 0.35f, ship.location.y + away.y * range + side.y * range * 0.35f)
            candidates += Vector2f(ship.location.x + away.x * range - side.x * range * 0.35f, ship.location.y + away.y * range - side.y * range * 0.35f)
        }
        for (friend in friends) {
            val fromPressure = Vector2f.sub(friend.location, pressureCenter, null)
            if (fromPressure.lengthSquared() > 1f) fromPressure.normalise() else fromPressure.set(away)
            candidates += Vector2f(friend.location.x + fromPressure.x * (friend.collisionRadius + ship.collisionRadius + 260f), friend.location.y + fromPressure.y * (friend.collisionRadius + ship.collisionRadius + 260f))
        }
        val clamped = candidates.map { clampToMap(engine, it) }.distinctBy { Pair((it.x / 100f).toInt(), (it.y / 100f).toInt()) }
        return clamped.maxByOrNull { retreatScore(ship, it, enemies, friends) }
            ?: retreatDestination(ship, enemies.minByOrNull { MathUtils.getDistance(ship.location, it.location) } ?: ship)
    }

    private fun retreatScore(ship: ShipAPI, point: Vector2f, enemies: List<ShipAPI>, friends: List<ShipAPI>): Float {
        val enemyClearance = minDistanceToEnemies(point, enemies).coerceAtMost(5000f)
        val friendSupport = friendlySupportScore(point, friends)
        val openSpace = openSpaceScore(point, enemies, friends)
        val travelPreference = MathUtils.getDistance(ship.location, point).coerceAtMost(GLOBAL_RETREAT_RANGE) * 0.15f
        return enemyClearance * 1.4f + friendSupport * 1.1f + openSpace * 0.8f + travelPreference
    }

    private fun friendlySupportScore(point: Vector2f, friends: List<ShipAPI>): Float {
        if (friends.isEmpty()) return 0f
        var best = 0f
        for (friend in friends) {
            val distance = MathUtils.getDistance(point, friend.location)
            if (distance > 1800f) continue
            best = maxOf(best, (1800f - distance) * strength(friend).coerceAtLeast(0.5f))
        }
        return best.coerceAtMost(2500f)
    }

    private fun openSpaceScore(point: Vector2f, enemies: List<ShipAPI>, friends: List<ShipAPI>): Float {
        var crowding = 0f
        for (ship in enemies + friends) {
            val distance = MathUtils.getDistance(point, ship.location)
            if (distance < 900f) crowding += (900f - distance) / 900f
        }
        return (1800f - crowding * 450f).coerceAtLeast(0f)
    }

    private fun clampToMap(engine: CombatEngineAPI, point: Vector2f): Vector2f {
        val halfW = engine.mapWidth * 0.5f - MAP_EDGE_PADDING
        val halfH = engine.mapHeight * 0.5f - MAP_EDGE_PADDING
        return Vector2f(point.x.coerceIn(-halfW, halfW), point.y.coerceIn(-halfH, halfH))
    }

    private fun pressureCenter(ship: ShipAPI, enemies: List<ShipAPI>): Vector2f {
        var x = 0f
        var y = 0f
        var weight = 0f
        for (enemy in enemies) {
            val distance = MathUtils.getDistance(ship.location, enemy.location)
            val w = strength(enemy) / distance.coerceAtLeast(200f)
            x += enemy.location.x * w
            y += enemy.location.y * w
            weight += w
        }
        if (weight <= 0f) return ship.location
        return Vector2f(x / weight, y / weight)
    }

    private fun minDistanceToEnemies(point: Vector2f, enemies: List<ShipAPI>): Float {
        var best = Float.MAX_VALUE
        for (enemy in enemies) {
            best = minOf(best, MathUtils.getDistance(point, enemy.location))
        }
        return best
    }

    private fun visibleFriends(ship: ShipAPI, engine: CombatEngineAPI, range: Float): List<ShipAPI> {
        val out = ArrayList<ShipAPI>()
        for (candidate in engine.ships) {
            val friend = candidate as? ShipAPI ?: continue
            if (friend === ship || friend.owner != ship.owner || friend.isHulk || !friend.isAlive) continue
            if (friend.isFighter || friend.isDrone) continue
            if (MathUtils.getDistance(ship.location, friend.location) <= range) out += friend
        }
        return out
    }

    private fun backstabDestination(target: ShipAPI, shipRadius: Float): Vector2f {
        val back = Misc.getUnitVectorAtDegreeAngle(target.facing + 180f)
        val dist = target.collisionRadius + shipRadius + BEHIND_OFFSET
        return Vector2f(target.location.x + back.x * dist, target.location.y + back.y * dist)
    }

    private fun flankDestination(ship: ShipAPI, target: ShipAPI): Vector2f {
        val toShip = Vector2f.sub(ship.location, target.location, null)
        val sideSign = if (toShip.lengthSquared() > 1f) {
            val right = Misc.getUnitVectorAtDegreeAngle(target.facing - 90f)
            if (Vector2f.dot(toShip, right) >= 0f) 1f else -1f
        } else {
            1f
        }
        val side = Misc.getUnitVectorAtDegreeAngle(target.facing - 90f * sideSign)
        val back = Misc.getUnitVectorAtDegreeAngle(target.facing + 180f)
        val dist = target.collisionRadius + ship.collisionRadius + FLANK_OFFSET
        return Vector2f(
            target.location.x + back.x * dist * 0.55f + side.x * dist * 0.75f,
            target.location.y + back.y * dist * 0.55f + side.y * dist * 0.75f,
        )
    }

    private fun retreatDestination(ship: ShipAPI, threat: ShipAPI): Vector2f {
        val away = Vector2f.sub(ship.location, threat.location, null)
        if (away.lengthSquared() <= 1f) return MathUtils.getPointOnCircumference(ship.location, RETREAT_OFFSET, ship.facing + 180f)
        away.normalise()
        return Vector2f(ship.location.x + away.x * RETREAT_OFFSET, ship.location.y + away.y * RETREAT_OFFSET)
    }

    private fun isBehindTarget(ship: ShipAPI, target: ShipAPI, arc: Float): Boolean {
        val angleFromTarget = VectorUtils.getAngle(target.location, ship.location)
        val diff = MathUtils.getShortestRotation(target.facing + 180f, angleFromTarget)
        return kotlin.math.abs(diff) <= arc * 0.5f
    }

    private fun findNearestTarget(ship: ShipAPI, engine: CombatEngineAPI, hinted: ShipAPI?): ShipAPI? {
        if (isValidTarget(ship, engine, hinted)) return hinted
        val directTarget = try {
            ship.shipTarget
        } catch (_: Throwable) {
            null
        }
        if (isValidTarget(ship, engine, directTarget)) return directTarget

        var best: ShipAPI? = null
        var bestDistance = Float.MAX_VALUE
        val ships = try {
            engine.ships
        } catch (_: Throwable) {
            null
        } ?: return null

        for (candidate in ships) {
            val target = candidate as? ShipAPI ?: continue
            if (!isValidTarget(ship, engine, target)) continue
            val distance = MathUtils.getDistance(ship.location, target.location)
            if (distance < bestDistance) {
                best = target
                bestDistance = distance
            }
        }
        return best
    }

    private fun visibleEnemies(ship: ShipAPI, engine: CombatEngineAPI, range: Float): List<ShipAPI> {
        val out = ArrayList<ShipAPI>()
        for (candidate in engine.ships) {
            val target = candidate as? ShipAPI ?: continue
            if (!isValidTarget(ship, engine, target)) continue
            if (MathUtils.getDistance(ship.location, target.location) <= range) out += target
        }
        return out
    }

    private fun longestWeaponRange(ship: ShipAPI): Float {
        var best = 0f
        for (weapon in ship.allWeapons) {
            try {
                if (weapon.isDecorative || weapon.spec?.type?.name == "SYSTEM") continue
                best = maxOf(best, weapon.range)
            } catch (_: Throwable) {
            }
        }
        return best
    }

    private fun strength(ship: ShipAPI): Float {
        val hull = ship.hullLevel.coerceIn(0.15f, 1f)
        val size = when {
            ship.isCapital -> 5f
            ship.isCruiser -> 3f
            ship.isDestroyer -> 1.6f
            ship.isFrigate -> 1f
            else -> 0.45f
        }
        return size * hull
    }

    private fun missileThreat(ship: ShipAPI, engine: CombatEngineAPI): Boolean {
        var count = 0
        val dangerRange = ship.collisionRadius * 3f
        val hullDanger = ship.maxHitpoints * 0.2f
        for (m in engine.missiles) {
            if (m.owner == ship.owner || m.isFading || m.isFizzling) continue
            if (MathUtils.getDistance(ship.location, m.location) > dangerRange) continue
            count++
            val damage = try { m.damageAmount } catch (_: Throwable) { 0f }
            if (damage >= hullDanger) return true
        }
        return count >= 5
    }

    private fun shieldNotCoveringDanger(ship: ShipAPI, missileDangerDir: Vector2f?): Boolean {
        val shield = ship.shield ?: return true
        if (!shield.isOn) return true
        val dir = missileDangerDir ?: return false
        if (dir.lengthSquared() <= 1f) return false
        val point = Vector2f(ship.location.x + dir.x * ship.collisionRadius * 2f, ship.location.y + dir.y * ship.collisionRadius * 2f)
        return !shield.isWithinArc(point)
    }

    private fun isValidTarget(ship: ShipAPI, engine: CombatEngineAPI, target: ShipAPI?): Boolean {
        target ?: return false
        if (target === ship) return false
        if (target.owner == ship.owner) return false
        if (target.isHulk) return false
        return engine.isEntityInPlay(target)
    }
}