package cn.kasuminova.astd.combat.automation.base

import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import org.lwjgl.util.vector.Vector2f
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PhaseCombatEvidence {
    data class FireEvent(
        val timeSec: Float, val weapon: WeaponAPI, var surfaceTime: Float, var shots: Int = 1,
        val targetId: String? = null, val distance: Float = Float.NaN,
        val range: Float = Float.NaN, val separation: Float = Float.NaN,
        val carrierId: String? = null, val carrierDistance: Float = Float.NaN,
        val observationDelay: Float = 0f,
        val distanceError: Float = 0f, val carrierDistanceError: Float = 0f,
        val attackWeapon: Boolean = true,
        val timeMult: Float = Float.NaN, val refireDelay: Float = Float.NaN, val cooldown: Float = Float.NaN,
        val carrierRanges: Map<String, LaunchRange> = emptyMap(),
    )
    data class LaunchRange(val distance: Float, val error: Float)
    enum class EnemyWindow { NONE, OVERLOAD, VENT }
    data class HitEvent(
        val timeSec: Float, val targetId: String, val hullDamage: Float, val fire: FireEvent,
        val armorDamage: Float = 0f, val shieldDamage: Float = 0f,
        val enemyWindow: EnemyWindow = EnemyWindow.NONE,
    ) {
        val effectiveDamage: Float get() = hullDamage + armorDamage + shieldDamage
    }
    data class SurfaceEvent(val timeSec: Float, val targetId: String, val rearGeometry: Boolean)
    data class RecoveryPhase(
        val startSec: Float, val startHardFlux: Float, val maxFlux: Float,
        val startLoc: Vector2f, val threatDir: Vector2f,
        var endSec: Float = -1f, var lateralDisplacement: Float = 0f,
        var hardFluxActuallyDropped: Float = 0f, var newFireAfterRecovery: Boolean = false,
        var peakHardFlux: Float = startHardFlux,
        var resumedVolley: PhaseVolleyEvidence.Volley? = null,
    )
    data class RouteWaypoint(
        val timeSec: Float, val pathStage: Int, var coord: Vector2f, val start: Vector2f, val targetFacing: Float,
        var arrived: Boolean = false, var observedTravel: Float = 0f,
    )
    data class RouteEvidence(
        val targetId: String, var version: Int, var targetFacing: Float,
        val waypoints: MutableList<RouteWaypoint> = ArrayList(),
        var completedAt: Float = -1f, var failed: Boolean = false,
        var turnReplanAt: Float = -1f, var targetResponseAt: Float = -1f, var replans: Int = 0,
        var rearGeometry: Boolean = false, var lateralTravel: Float = 0f,
        val replanReasons: MutableList<String> = ArrayList(), var failureReason: String = "",
        var failureCause: String = "",
    )
    data class DemThreatEvent(
        val missile: MissileAPI, val appearTimeSec: Float, val damageAtAppearance: Float,
        var defensiveDiveTimeSec: Float = -1f, var threatClearedTimeSec: Float = -1f,
        var resurfaceTimeSec: Float = -1f, var newFireTimeSec: Float = -1f,
        var hit: Boolean = false, var newFire: FireEvent? = null,
        var newFireEffectiveDamage: Float = 0f,
    )
    data class ReceivedHit(val timeSec: Float, val applied: Float, val phased: Boolean, val cloak: String, val detail: String)
    private data class Burst(val start: Float, var shots: Int, val expected: Int)

    val receivedHits = ArrayList<ReceivedHit>()
    val fireEvents = ArrayList<FireEvent>()
    val hitEvents = ArrayList<HitEvent>()
    val wingFireEvents = ArrayList<FireEvent>()
    val wingHitEvents = ArrayList<HitEvent>()
    val phaseCycles = PhaseCycleEvidence()
    val volleys = PhaseVolleyEvidence()
    var unobservableProjectileCount = 0
        private set
    private var observedEnemies: List<ShipAPI> = emptyList()
    val surfaceEvents = ArrayList<SurfaceEvent>()
    val enemyHpDrops = LinkedHashMap<String, Float>()
    val enemyDestroyed = LinkedHashMap<String, Boolean>()
    val recoveryPhases = ArrayList<RecoveryPhase>()
    val routes = ArrayList<RouteEvidence>()
    val routeWaypoints get() = routes.flatMap { it.waypoints }
    val demThreats = ArrayList<DemThreatEvent>()
    val observedDemMissiles = java.util.Collections.newSetFromMap(IdentityHashMap<MissileAPI, Boolean>())
    val interruptionReasons = ArrayList<String>()
    var diveCount = 0
        private set
    var surfaceCount = 0
        private set
    var longestPhaseSecWithoutFire = 0f
        private set
    var burstInterruptionCount = 0
        private set
    var emergencyBurstInterruptionCount = 0
        private set
    var completedValidBurstCount = 0
        private set
    var hardFluxPeak = 0f
        private set
    var maxRouteReplans = 0
        private set
    var diagnosticsHealthy = true
        private set
    var diagnosticsReady = false
        private set
    var diagnosticsFailureReason: String? = null
        private set
    var routeLoopFailure = false
        private set
    var lastSurfaceTime = -1f
        private set
    var sampleTimeSec = 0f
        private set
    private var wasPhased = false
    private var phaseStart = -1f
    private var lastDiagTime = -1f
    private var lastActiveCost = 0f
    private var currentRecovery: RecoveryPhase? = null
    private var currentRoute: RouteEvidence? = null
    private var lastWaypoint: RouteWaypoint? = null
    private var receivedDamage = 0f
    private var lastReceivedDamageLogAt = -1f
    private var bound = false
    private val lastHp = HashMap<String, Float>()
    private val projectileFires = IdentityHashMap<DamagingProjectileAPI, FireEvent?>()
    private val beamActive = IdentityHashMap<WeaponAPI, Boolean>()
    private val bursts = IdentityHashMap<WeaponAPI, Burst>()
    private val lastShot = IdentityHashMap<WeaponAPI, FireEvent>()

    fun bind(engine: CombatEngineAPI, player: ShipAPI, enemies: List<ShipAPI>) {
        check(!bound)
        bound = true
        observedEnemies = enemies
        phaseCycles.bind(player)
        wasPhased = player.isPhased
        engine.projectiles.forEach { projectileFires[it] = null }
        engine.missiles.forEach { projectileFires[it] = null }
        for (weapon in player.allWeapons) beamActive[weapon] = weapon.beams.any { it.brightness > 0f }
        for (enemy in enemies) {
            lastHp[enemy.id] = enemy.hitpoints
            enemyHpDrops[enemy.id] = 0f
            enemy.addListener(object : DamageListener, com.fs.starfarer.api.combat.listeners.DamageTakenModifier {
                private var pendingParam: Any? = null
                private var pendingTime = -1f
                private var pendingWindow = EnemyWindow.NONE

                override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?,
                                               point: Vector2f?, shieldHit: Boolean): String? {
                    pendingParam = param
                    pendingTime = engine.getTotalElapsedTime(false)
                    pendingWindow = when {
                        enemy.fluxTracker.isOverloaded -> EnemyWindow.OVERLOAD
                        enemy.fluxTracker.isVenting -> EnemyWindow.VENT
                        else -> EnemyWindow.NONE
                    }
                    return null
                }

                override fun reportDamageApplied(source: Any?, target: CombatEntityAPI?, result: ApplyDamageResultAPI?) {
                    if (target !== enemy || result == null) return
                    val now = engine.getTotalElapsedTime(false)
                    val param = enemy.paramAboutToApplyDamage
                    val window = if (pendingParam === param && pendingTime == now) pendingWindow else EnemyWindow.NONE
                    pendingParam = null
                    val fire = when (param) {
                        is DamagingProjectileAPI -> observeProjectile(param, player, now)
                        is BeamAPI -> if (ownedSource(param.source, player)) observeBeam(param.weapon, now) else null
                        else -> null
                    } ?: return
                    if (result.damageToHull + result.totalDamageToArmor + result.damageToShields > 0f) {
                        val hits = if (fire.carrierId == null) hitEvents else wingHitEvents
                        hits.add(HitEvent(now, enemy.id, result.damageToHull, fire,
                            result.totalDamageToArmor, result.damageToShields, window))
                    }
                }
            })
        }
        player.addListener(object : DamageListener {
            override fun reportDamageApplied(source: Any?, target: CombatEntityAPI?, result: ApplyDamageResultAPI?) {
                if (target !== player || result == null) return
                val applied = result.damageToHull + result.totalDamageToArmor + result.damageToShields
                receivedDamage += applied
                val param = player.paramAboutToApplyDamage
                val now = engine.getTotalElapsedTime(false)
                val detail = when (param) {
                    is BeamAPI -> "beam weapon=${param.weapon.id} source=${param.source?.id}"
                    is DamagingProjectileAPI -> "projectile spec=${param.projectileSpecId} source=${param.source?.id}"
                    else -> "type=${param?.javaClass?.name}"
                }
                if (applied > 0f) {
                    receivedHits += ReceivedHit(now, applied, player.isPhased, player.phaseCloak?.state?.name ?: "NONE", detail)
                }
                if (demThreats.isNotEmpty() && applied > 0f && now - lastReceivedDamageLogAt >= 0.25f) {
                    lastReceivedDamageLogAt = now
                    val demDetail = when (param) {
                        is BeamAPI -> {
                            val nearest = param.source?.location?.let { source ->
                                demThreats.minOfOrNull { com.fs.starfarer.api.util.Misc.getDistance(source, it.missile.location) }
                            }
                            "$detail from=${param.from} to=${param.to} " +
                                "target=${(param.damageTarget as? ShipAPI)?.id} nearestDem=$nearest"
                        }
                        else -> detail
                    }
                    com.fs.starfarer.api.Global.getLogger(PhaseCombatEvidence::class.java).info(
                        "[ASTD-DemDamage] t=$now applied=$applied phased=${player.isPhased} cloak=${player.phaseCloak?.state} $demDetail",
                    )
                }
                for (event in demThreats) {
                    if (!event.hit && event.threatClearedTimeSec < 0f && isDemDamage(param, event.missile, player)) {
                        event.hit = true
                    }
                }
            }
        })
    }

    fun update(engine: CombatEngineAPI, elapsed: Float, player: ShipAPI?, enemies: List<ShipAPI>, diag: Map<String, Any>?) {
        val now = engine.getTotalElapsedTime(false)
        sampleTimeSec = now
        trackEnemyHp(enemies)
        if (player == null || player.isHulk || !engine.isEntityInPlay(player)) return
        validateDiagnostics(diag, now, elapsed)
        val dive = player.isPhased && !wasPhased
        val surface = !player.isPhased && wasPhased
        if (dive) { diveCount++; phaseStart = now }
        if (surface) {
            if (phaseStart >= 0f) {
                surfaceCount++
                enemies.forEach { enemy -> surfaceEvents.add(SurfaceEvent(now, enemy.id, rearGeometry(player, enemy))) }
            }
            lastSurfaceTime = now
            fireEvents.filter { it.timeSec == now }.forEach { it.surfaceTime = now }
        }
        if (phaseStart >= 0f) longestPhaseSecWithoutFire = maxOf(longestPhaseSecWithoutFire, now - phaseStart)
        if (surface) phaseStart = -1f
        phaseCycles.update(engine, player, now)
        trackFiring(engine, player, now)
        volleys.update(player, fireEvents, now)
        if (diagnosticsReady && diag != null) {
            trackBursts(player, now, dive, diag)
            trackRecovery(player, enemies, now, diag)
            trackRoute(player, enemies, now, diag)
            trackDem(engine, player, now, dive, surface, diag)
        }
        hardFluxPeak = maxOf(hardFluxPeak, player.fluxTracker.hardFlux)
        wasPhased = player.isPhased
    }

    private fun validateDiagnostics(diag: Map<String, Any>?, now: Float, elapsed: Float) {
        val numbers = listOf("sampleTime", "activeBurstCost", "remainingBurstCost", "routeVersion", "pathStage",
            "routeReplans", "routeProgress", "routeLateralTravel", "routeStageTravel", "waypointX", "waypointY", "targetDistance", "effectiveWeaponRange", "hardFlux", "peakHardFlux", "softFlux", "surfaceLoss")
        val strings = listOf("action", "reason", "targetId", "routeStage", "routeReplanReason", "routeFailureReason", "costUncertainties")
        val booleans = listOf("routeCompleted", "routeFailed", "attackInRange", "attackCanFire")
        val missing = numbers.firstOrNull { (diag?.get(it) as? Number)?.toFloat()?.isFinite() != true }
            ?: strings.firstOrNull { diag?.get(it) !is String }
            ?: booleans.firstOrNull { diag?.get(it) !is Boolean }
        val sample = (diag?.get("sampleTime") as? Number)?.toFloat()
        val error = when {
            diag == null || missing != null -> "诊断缺失或类型无效: $missing"
            diag["action"] !in setOf("RETREAT", "EVADE", "RECOVER", "FLANK", "APPROACH", "ATTACK", "TRAVEL") -> "未知动作"
            diag["routeStage"] !in setOf("NONE", "LATERAL", "SIDE_ENTRY", "ATTACK", "RECOVERY") -> "未知路线阶段"
            listOf("routeVersion", "pathStage", "routeReplans").any { diag[it] !is Int } -> "路线索引类型无效"
            sample == null -> "缺少采样时间"
            sample > now + 0.05f || now - sample > 1f || sample < lastDiagTime -> "诊断过期或时间倒退: $sample / $now"
            else -> null
        }
        diagnosticsReady = error == null && diagnosticsHealthy
        if (error != null && elapsed >= 3f) {
            diagnosticsHealthy = false
            diagnosticsFailureReason = error
        }
        if (error == null && sample != null) lastDiagTime = sample
    }

    private fun ownedSource(source: ShipAPI?, player: ShipAPI): Boolean = source === player ||
        source?.isFighter == true && source.wing?.sourceShip === player

    private fun observeProjectile(projectile: DamagingProjectileAPI, player: ShipAPI, now: Float): FireEvent? {
        if (!ownedSource(projectile.source, player)) return null
        if (projectileFires.containsKey(projectile)) return projectileFires[projectile]
        val weapon = projectile.weapon ?: return null
        val age = projectile.elapsed
        if (!age.isFinite() || age < 0f || age > 0.1f || projectile.explosionSpecIfExplosion != null ||
            projectile.isFromMissile && projectile !is MissileAPI) {
            unobservableProjectileCount++
            projectileFires[projectile] = null
            return null
        }
        val fire = recordFire(weapon, now - age, age)
        projectileFires[projectile] = fire
        return fire
    }

    private fun recordFire(weapon: WeaponAPI, now: Float, observationDelay: Float = 0f): FireEvent {
        bursts[weapon]?.let { it.shots++ }
        lastShot[weapon]?.takeIf { it.timeSec == now }?.let {
            it.shots++
            return it
        }
        val ship = weapon.ship
        val carrier = ship.takeIf { it.isFighter }?.wing?.sourceShip
        val group = ship.getWeaponGroupFor(weapon)
        val targetId = if (group?.isAutofiring == true) {
            group.getAutofirePlugin(weapon)?.targetShip?.id
        } else ship.shipTarget?.id
        val target = observedEnemies.firstOrNull { it.id == targetId && !it.isHulk }
        val fire = FireEvent(now, weapon, lastSurfaceTime, targetId = target?.id,
            distance = target?.let { distance(ship.location, it.location) } ?: Float.NaN,
            range = weapon.range,
            separation = target?.let { ship.collisionRadius + it.collisionRadius } ?: Float.NaN,
            carrierId = carrier?.id,
            carrierDistance = if (carrier != null && target != null) distance(carrier.location, target.location) else Float.NaN,
            observationDelay = observationDelay,
            distanceError = if (target != null) movementBound(ship, observationDelay) + movementBound(target, observationDelay) else Float.NaN,
            carrierDistanceError = if (carrier != null && target != null)
                movementBound(carrier, observationDelay) + movementBound(target, observationDelay) else Float.NaN,
            attackWeapon = !weapon.isDecorative && !weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY),
            timeMult = ship.mutableStats?.timeMult?.modifiedValue ?: Float.NaN,
            refireDelay = weapon.refireDelay, cooldown = weapon.cooldown,
            carrierRanges = if (carrier == null) emptyMap() else observedEnemies.filter { !it.isHulk }.associate {
                it.id to LaunchRange(distance(carrier.location, it.location),
                    movementBound(carrier, observationDelay) + movementBound(it, observationDelay))
            })
        lastShot[weapon] = fire
        if (carrier == null) fireEvents.add(fire) else wingFireEvents.add(fire)
        return fire
    }

    private fun movementBound(ship: ShipAPI, delay: Float): Float {
        if (delay <= 0f) return 0f
        val stats = ship.mutableStats ?: return Float.POSITIVE_INFINITY
        val localTime = delay * maxOf(1f, stats.timeMult.modifiedValue)
        val speed = maxOf(ship.velocity.length(), stats.maxSpeed.modifiedValue)
        return speed * localTime + 0.5f * stats.acceleration.modifiedValue.coerceAtLeast(0f) * localTime * localTime
    }

    private fun observeBeam(weapon: WeaponAPI, now: Float): FireEvent? {
        if (beamActive[weapon] != true) recordFire(weapon, now)
        beamActive[weapon] = true
        return lastShot[weapon]
    }

    private fun trackFiring(engine: CombatEngineAPI, player: ShipAPI, now: Float) {
        engine.projectiles.forEach { observeProjectile(it, player, now) }
        engine.missiles.forEach { observeProjectile(it, player, now) }
        val sources = listOf(player) + engine.ships.filter {
            it !== player && ownedSource(it, player) && !it.isHulk && engine.isEntityInPlay(it)
        }
        for (weapon in sources.flatMap { it.allWeapons }) {
            if (weapon.isDecorative) continue
            val active = weapon.isBeam && weapon.beams.any {
                ownedSource(it.source, player) && it.brightness > 0f && it.damage.damage > 0f && distance(it.from, it.to) > 1f
            }
            if (active && beamActive[weapon] != true) recordFire(weapon, now)
            beamActive[weapon] = active
        }
    }

    private fun trackEnemyHp(enemies: List<ShipAPI>) {
        for (enemy in enemies) {
            val hp = if (enemy.isHulk) 0f else enemy.hitpoints.coerceAtLeast(0f)
            val previous = lastHp.put(enemy.id, hp) ?: hp
            enemyHpDrops[enemy.id] = (enemyHpDrops[enemy.id] ?: 0f) + (previous - hp).coerceAtLeast(0f)
            if (enemy.isHulk) enemyDestroyed[enemy.id] = true
        }
    }

    private val endedBursts = ArrayList<Triple<WeaponAPI, Float, Float>>()

    private fun trackBursts(player: ShipAPI, now: Float, dive: Boolean, diag: Map<String, Any>) {
        val active = number(diag, "activeBurstCost")
        if (dive) {
            val cancelled = bursts.any { (weapon, burst) -> burst.shots < burst.expected || weapon.isBurstBeam }
            if (lastActiveCost > 1f && cancelled) {
                val emergency = diag["action"] == "EVADE" && number(diag, "surfaceLoss") > 0f
                if (emergency) emergencyBurstInterruptionCount++ else burstInterruptionCount++
                interruptionReasons.add("$now:${diag["action"]}:${diag["reason"]}:$lastActiveCost")
            }
            bursts.clear()
        }
        if (!player.isPhased) {
            for (weapon in player.allWeapons) {
                val shot = lastShot[weapon] ?: continue
                val expected = if (weapon.isBeam) 1 else weapon.spec.burstSize.coerceAtLeast(1)
                if (weapon !in bursts && shot.timeSec == now && (weapon.isBurstBeam || (!weapon.isBeam && expected > 1))) {
                    bursts[weapon] = Burst(now, shot.shots, expected)
                }
            }
            val iterator = bursts.iterator()
            while (iterator.hasNext()) {
                val (weapon, burst) = iterator.next()
                if (weapon.isDisabled || weapon.isForceDisabled) { iterator.remove(); continue }
                if (!weapon.isInBurst && beamActive[weapon] != true && weapon.cooldownRemaining > 0f && now > burst.start) {
                    if (burst.shots >= burst.expected) endedBursts.add(Triple(weapon, burst.start, now))
                    iterator.remove()
                }
            }
        }
        val ended = endedBursts.iterator()
        while (ended.hasNext()) {
            val (weapon, start, end) = ended.next()
            if (hitEvents.any { it.fire.weapon === weapon && it.fire.timeSec in start..end }) {
                completedValidBurstCount++
                ended.remove()
            }
        }
        lastActiveCost = active
    }

    private fun trackRecovery(player: ShipAPI, enemies: List<ShipAPI>, now: Float, diag: Map<String, Any>) {
        val hf = player.fluxTracker.hardFlux
        val recovering = diag["action"] == "RECOVER"
        if (recovering && currentRecovery == null) {
            val target = enemies.firstOrNull { it.id == diag["targetId"] && !it.isHulk } ?: return
            val dx = target.location.x - player.location.x
            val dy = target.location.y - player.location.y
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1f) return
            currentRecovery = RecoveryPhase(now, hf, player.maxFlux, Vector2f(player.location), Vector2f(dx / length, dy / length))
        }
        currentRecovery?.let { rec ->
            val dx = player.location.x - rec.startLoc.x
            val dy = player.location.y - rec.startLoc.y
            rec.lateralDisplacement = abs(-rec.threatDir.y * dx + rec.threatDir.x * dy)
            rec.peakHardFlux = maxOf(rec.peakHardFlux, hf)
            rec.hardFluxActuallyDropped = rec.peakHardFlux - hf
            if (!recovering) {
                rec.endSec = now
                recoveryPhases.add(rec)
                currentRecovery = null
            }
        }
        for (rec in recoveryPhases) {
            if (rec.resumedVolley == null) rec.resumedVolley = volleys.volleys.firstOrNull {
                it.fullBurst && it.start > rec.endSec && it.end <= rec.endSec + 10f &&
                    volleys.damage(it, hitEvents, rec.endSec + 10f) > 0f
            }
            rec.newFireAfterRecovery = rec.resumedVolley != null
        }
    }

    private fun trackRoute(player: ShipAPI, enemies: List<ShipAPI>, now: Float, diag: Map<String, Any>) {
        val version = number(diag, "routeVersion").toInt()
        val stageName = diag["routeStage"] as String
        val target = enemies.firstOrNull { it.id == diag["targetId"] }
        if (target == null || version <= 0 || stageName == "NONE" || stageName == "RECOVERY") {
            currentRoute = null; lastWaypoint = null; return
        }
        var route = currentRoute
        val reportedStage = listOf("LATERAL", "SIDE_ENTRY", "ATTACK").indexOf(stageName)
        val replaced = lastWaypoint?.let { point ->
            version == route?.version && diag["routeCompleted"] == false && reportedStage < point.pathStage
        } == true
        if (route == null || route.targetId != target.id || version < route.version || replaced ||
            (route.failed && diag["routeFailed"] == false)) {
            route = RouteEvidence(target.id, version, target.facing)
            routes.add(route); currentRoute = route; lastWaypoint = null
        }
        val reportedCoord = Vector2f(number(diag, "waypointX"), number(diag, "waypointY"))
        val previousWaypoint = lastWaypoint
        val stage = reportedStage
        if (stage < 0) return
        val lateralTravel = number(diag, "routeLateralTravel")
        val stageTravel = number(diag, "routeStageTravel")
        route.lateralTravel = maxOf(route.lateralTravel, lateralTravel)
        lastWaypoint?.let { point ->
            val observed = if (point.pathStage == 0) lateralTravel else stageTravel
            point.observedTravel = maxOf(point.observedTravel, observed)
        }
        fun recordArrival(point: RouteWaypoint) {
            val advanced = stage > point.pathStage || diag["routeCompleted"] == true && point.pathStage == 2
            val moved = if (point.pathStage == 0) lateralTravel >= 400f else point.observedTravel >= 50f || advanced
            if (moved && (advanced || player.isPhased && distance(player.location, point.coord) <= 100f)) {
                point.arrived = true
            }
        }
        if (route.targetResponseAt < 0f && version == route.version && previousWaypoint != null &&
            reportedStage == previousWaypoint.pathStage && abs(angleDelta(route.targetFacing, target.facing)) >= 25f &&
            distance(previousWaypoint.coord, reportedCoord) > 1f
        ) {
            route.targetResponseAt = now
        }
        val replans = number(diag, "routeReplans").toInt()
        maxRouteReplans = maxOf(maxRouteReplans, replans)
        if (replans > 2 || diag["routeFailureReason"] == "REPLAN_LIMIT") routeLoopFailure = true
        if (version > route.version) {
            lastWaypoint?.let(::recordArrival)
            route.replanReasons += diag["routeReplanReason"] as? String ?: "UNKNOWN"
            if (diag["routeReplanReason"] == "TARGET_TURNED" && abs(angleDelta(route.targetFacing, target.facing)) >= 25f) {
                route.turnReplanAt = now
            }
            route.version = version; route.targetFacing = target.facing; lastWaypoint = null
            route.completedAt = -1f; route.rearGeometry = false
        }
        route.replans = replans
        if (diag["routeFailed"] == true) {
            route.failed = true
            route.failureReason = diag["routeFailureReason"] as? String ?: "UNKNOWN"
            route.failureCause = diag["routeFailureCause"] as? String ?: "NONE"
        }
        lastWaypoint?.let(::recordArrival)
        if (diag["routeCompleted"] != true && !route.failed) {
            if (lastWaypoint == null || lastWaypoint?.pathStage != stage) {
                val observed = if (stage == 0) lateralTravel else stageTravel
                val waypoint = RouteWaypoint(now, stage, reportedCoord, Vector2f(player.location), target.facing,
                    observedTravel = observed)
                route.waypoints.add(waypoint); lastWaypoint = waypoint
            } else {
                lastWaypoint?.coord = reportedCoord
            }
            lastWaypoint?.let(::recordArrival)
        }
        if (diag["routeCompleted"] == true && route.completedAt < 0f && !route.failed && (player.isPhased || wasPhased)) {
            route.rearGeometry = rearGeometry(player, target) && diag["attackInRange"] == true
            if (route.rearGeometry) {
                val finalPoint = lastWaypoint?.takeIf { it.pathStage == 2 } ?: RouteWaypoint(
                    now, 2, reportedCoord, Vector2f(player.location), target.facing, observedTravel = stageTravel,
                ).also {
                    route.waypoints.add(it)
                    lastWaypoint = it
                }
                finalPoint.arrived = true
                route.completedAt = now
            }
        }
    }

    private fun trackDem(engine: CombatEngineAPI, player: ShipAPI, now: Float, dive: Boolean, surface: Boolean, diag: Map<String, Any>) {
        for (missile in engine.missiles) {
            val spec = missile.projectileSpecId ?: continue
            if (missile.owner == player.owner || !(spec.startsWith("dragon") || spec.startsWith("hydra"))) continue
            observedDemMissiles.add(missile)
            if (demThreats.any { it.missile === missile } || player.isPhased || !isDemThreat(missile, player)) continue
            demThreats.add(DemThreatEvent(missile, now, receivedDamage))
        }
        for (event in demThreats) {
            if (dive && now > event.appearTimeSec && event.defensiveDiveTimeSec < 0f &&
                event.threatClearedTimeSec < 0f && engine.isEntityInPlay(event.missile) && diag["action"] == "EVADE"
            ) {
                event.defensiveDiveTimeSec = now
            }
            if (event.defensiveDiveTimeSec >= 0f && event.threatClearedTimeSec < 0f &&
                (!engine.isEntityInPlay(event.missile) || !isDemThreat(event.missile, player))
            ) {
                event.threatClearedTimeSec = now
            }
            if (surface && event.defensiveDiveTimeSec >= 0f && event.resurfaceTimeSec < 0f) {
                event.resurfaceTimeSec = now
            }
            if (event.resurfaceTimeSec >= 0f && event.newFire == null && !event.hit) {
                fireEvents.firstOrNull {
                    it.surfaceTime == event.resurfaceTimeSec &&
                        it.timeSec in event.resurfaceTimeSec..(event.resurfaceTimeSec + 5f)
                }?.let {
                    event.newFire = it
                    event.newFireTimeSec = it.timeSec
                }
            }
            event.newFire?.let {
                event.newFireEffectiveDamage = fireEffectiveDamage(it, deadline = event.resurfaceTimeSec + 5f)
            }
        }
    }

    fun totalEnemyHpDrop(): Float = enemyHpDrops.values.sum()
    fun playerHullDamage(after: Float = -1f): Float = hitEvents.filter {
        it.timeSec >= after && it.fire.timeSec >= after
    }.sumOf { it.hullDamage.toDouble() }.toFloat()
    fun playerEffectiveDamage(after: Float = -1f): Float = hitEvents.filter {
        it.timeSec >= after && it.fire.timeSec >= after
    }.sumOf { it.effectiveDamage.toDouble() }.toFloat()
    fun enemyDestroyedCount(): Int = enemyDestroyed.count { it.value }
    fun validDemSequenceCount(): Int = demThreats.count { event ->
        val fire = event.newFire
        event.appearTimeSec >= 0f && event.defensiveDiveTimeSec > event.appearTimeSec &&
            event.threatClearedTimeSec > event.defensiveDiveTimeSec &&
            event.resurfaceTimeSec - event.threatClearedTimeSec >= 1f && !event.hit &&
            fire != null && fire.surfaceTime == event.resurfaceTimeSec &&
            fire.timeSec in event.resurfaceTimeSec..(event.resurfaceTimeSec + 5f) &&
            fireEffectiveDamage(fire, deadline = event.resurfaceTimeSec + 5f) > 500f
    }
    fun fireEffectiveDamage(fire: FireEvent, targetId: String? = null, deadline: Float = fire.timeSec + 10f): Float =
        hitEvents.filter {
            it.fire === fire && (targetId == null || it.targetId == targetId) &&
                it.timeSec in fire.timeSec..deadline && it.effectiveDamage > 0f
        }.sumOf { it.effectiveDamage.toDouble() }.toFloat()

    fun surfaceFireAfter(time: Float, targetId: String): FireEvent? = surfaceFiresAfter(time, targetId).firstOrNull()

    fun surfaceEffectiveDamage(time: Float, targetId: String): Float = surfaceFiresAfter(time, targetId)
        .sumOf { fireEffectiveDamage(it, targetId).toDouble() }.toFloat()

    fun surfaceFiresAfter(time: Float, targetId: String): List<FireEvent> {
        if (time < 0f) return emptyList()
        val surfaceTime = surfaceEvents.firstOrNull { it.timeSec >= time }?.timeSec ?: return emptyList()
        if (surfaceTime - time > 3f || surfaceEvents.none {
            it.timeSec == surfaceTime && it.targetId == targetId && it.rearGeometry
        }) return emptyList()
        return fireEvents.filter {
            it.surfaceTime == surfaceTime && it.timeSec in surfaceTime..(surfaceTime + 2f)
        }
    }
    fun completeSurfaceFire(): FireEvent? = fireEvents.firstOrNull { fire ->
        fire.timeSec in fire.surfaceTime..(fire.surfaceTime + 2f) &&
            surfaceEvents.any { it.timeSec == fire.surfaceTime }
    }
    fun completeRoute(route: RouteEvidence): Boolean {
        val reached = route.waypoints.filter { it.arrived }.map { it.pathStage }
        return route.completedAt >= 0f && route.rearGeometry &&
            reached.indexOf(0) >= 0 && reached.indexOf(1) > reached.indexOf(0) && reached.indexOf(2) > reached.indexOf(1)
    }
    private fun rearGeometry(player: ShipAPI, target: ShipAPI): Boolean {
        val radians = Math.toRadians(target.facing.toDouble())
        val dx = player.location.x - target.location.x
        val dy = player.location.y - target.location.y
        val rear = dx * cos(radians) + dy * sin(radians)
        val side = -dx * sin(radians) + dy * cos(radians)
        return rear < -target.collisionRadius * 0.25f && abs(side) > target.collisionRadius * 0.25f
    }
    private fun isDemThreat(missile: MissileAPI, player: ShipAPI): Boolean {
        if (missile.isFading || distance(player.location, missile.location) > 1800f) return false
        if ((missile.missileAI as? GuidedMissileAI)?.target === player) return true
        val dx = player.location.x - missile.location.x
        val dy = player.location.y - missile.location.y
        val vx = missile.velocity.x - player.velocity.x
        val vy = missile.velocity.y - player.velocity.y
        val vv = vx * vx + vy * vy
        val t = if (vv > 1f) (dx * vx + dy * vy) / vv else -1f
        if (t !in 0f..3f) return false
        val missDistance = sqrt((dx - vx * t) * (dx - vx * t) + (dy - vy * t) * (dy - vy * t))
        return missDistance < player.collisionRadius + 150f
    }
    private fun isDemDamage(param: Any?, missile: MissileAPI, player: ShipAPI): Boolean = when (param) {
        missile -> true
        is BeamAPI -> param.source !== player && param.source.owner == missile.owner &&
            (distance(param.source.location, missile.location) <= 5f || distance(param.from, missile.location) <= 5f)
        else -> false
    }
    private fun number(diag: Map<String, Any>, key: String) = (diag.getValue(key) as Number).toFloat()
    private fun angleDelta(from: Float, to: Float) = ((to - from) % 360f + 540f) % 360f - 180f
    private fun distance(a: Vector2f, b: Vector2f): Float = Vector2f.sub(a, b, null).length()
}
