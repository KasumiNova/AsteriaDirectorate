package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import org.lwjgl.util.vector.Vector2f

internal class GravityPhaseSituationSampler {

    companion object {
        private val log = Global.getLogger(GravityPhaseSituationSampler::class.java)

        internal const val SOON_WINDOW_SEC = 0.6f
        internal const val NEAR_WINDOW_SEC = 1.2f
        internal const val SURFACE_WINDOW_SEC = 2.0f

        private const val PROJECTILE_SCAN_RANGE = 1500f
        private const val MISS_MARGIN = 50f
        private const val APPROACH_SPEED_MIN = 50f

        internal const val ENGAGE_SCAN_RANGE = 1600f

        private const val COVERAGE_ARC_MARGIN = 10f

        internal const val FRIENDLY_CATCH_WINDOW_SEC = 1.5f
        internal const val CONT_BEAM_THREAT_WINDOW_SEC = 1.5f
        internal const val BURST_BEAM_THREAT_WEIGHT = 0.5f

        internal const val SCREEN_SCAN_DIST = 800f
        internal const val SCREEN_AHEAD_HALF_ARC = 60f

        internal const val LOW_MOBILITY_MAX_SPEED = 70f
        internal const val LOW_MOBILITY_TURN_RATE = 25f
        internal const val LOW_MOBILITY_ACCEL = 50f

        internal const val REAR_ARC_MIN_DIFF = 120f
        internal const val SURFACE_PROXIMITY_FRAC = 0.9f
        internal const val SURFACE_FRONT_AXIS_HALF_ARC = 60f
        internal const val SURFACE_FRONT_AXIS_MAX_DIST = 1000f

        internal const val UNPHASE_UNSAFE_OVERLAP_FRAC = 0.35f
        internal const val REAR_SURFACE_SEPARATION_FRAC = 1.5f

        internal fun angleDiffAbs(a: Float, b: Float): Float {
            var d = (a - b) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return kotlin.math.abs(d)
        }

        internal fun isLowMobilityTarget(maxSpeed: Float, acceleration: Float, maxTurnRate: Float): Boolean =
            maxSpeed <= LOW_MOBILITY_MAX_SPEED ||
                    (maxTurnRate <= LOW_MOBILITY_TURN_RATE && acceleration <= LOW_MOBILITY_ACCEL)

        internal fun rearSurfaceMinSeparation(shipRadius: Float, enemyRadius: Float): Float =
            (shipRadius + enemyRadius) * REAR_SURFACE_SEPARATION_FRAC

        internal fun closestApproachDistance(
            px: Float, py: Float, vx: Float, vy: Float, windowSec: Float,
        ): Float {
            val vv = vx * vx + vy * vy
            var t = 0f
            if (vv > 1e-6f) {
                t = -(px * vx + py * vy) / vv
                if (t < 0f) t = 0f
                if (t > windowSec) t = windowSec
            }
            val cx = px + vx * t
            val cy = py + vy * t
            return kotlin.math.sqrt(cx * cx + cy * cy)
        }

        internal fun segmentHitsCircle(
            px: Float, py: Float, dx: Float, dy: Float,
            cx: Float, cy: Float, radius: Float,
        ): Boolean {
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-6f) return false
            var t = ((cx - px) * dx + (cy - py) * dy) / lenSq
            if (t < 0f) t = 0f
            if (t > 1f) t = 1f
            val nearX = px + dx * t - cx
            val nearY = py + dy * t - cy
            return nearX * nearX + nearY * nearY <= radius * radius
        }

        internal fun beamThreatNear(dps: Float): Float = dps * CONT_BEAM_THREAT_WINDOW_SEC

        internal fun beamThreatSoon(burstDamage: Float): Float = burstDamage * BURST_BEAM_THREAT_WEIGHT
    }

    private var nullFluxTrackerWarned = false

    private val payloadProfileCache = HashMap<String, PayloadProfile?>()

    fun sample(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        cloak: ShipSystemAPI,
        phased: Boolean,
        target: ShipAPI?,
        phaseActiveTime: Float,
        timeSinceUnphase: Float,
        flankIntentRemaining: Float,
        flankIntentWindowArmed: Float,
        pressIntentRemaining: Float,
    ): PhaseSituation {
        val incoming = FloatArray(3)
        estimateIncoming(engine.projectiles, ship, incoming, collectFriendly = false)
        estimateIncoming(engine.missiles, ship, incoming, collectFriendly = false)
        estimateBeamThreat(engine, ship, incoming, collectFriendly = false)

        val friendlyIncoming = FloatArray(3)
        estimateIncoming(engine.projectiles, ship, friendlyIncoming, collectFriendly = true)
        estimateIncoming(engine.missiles, ship, friendlyIncoming, collectFriendly = true)
        estimateBeamThreat(engine, ship, friendlyIncoming, collectFriendly = true)

        val threat = pickThreat(engine, ship, target)
        // 机动性取 baseValue：modifiedValue 会被零辐能加速/燃驱等瞬时增益污染
        val lowMobility = threat != null &&
                isLowMobilityTarget(
                    threat.mutableStats.maxSpeed.baseValue,
                    threat.mutableStats.acceleration.baseValue,
                    threat.mutableStats.maxTurnRate.baseValue,
                )
        val threatDist = if (threat == null) Float.MAX_VALUE
        else kotlin.math.sqrt(distanceSq(ship.location, threat.location))
        val shipTarget = ship.shipTarget
        val targetDist =
            if (shipTarget != null && isValidEnemyShip(ship, shipTarget)) {
                kotlin.math.sqrt(distanceSq(ship.location, shipTarget.location))
            } else {
                Float.MAX_VALUE
            }
        val threatToUsBearing = threat?.let {
            Math.toDegrees(
                kotlin.math.atan2(
                    (ship.location.y - it.location.y).toDouble(),
                    (ship.location.x - it.location.x).toDouble(),
                )
            ).toFloat()
        }
        val attackSystem = ship.system?.takeIf { it !== cloak }
        val weaponRange = effectiveWeaponRange(ship)
        val fluxTracker = ship.fluxTracker
        if (fluxTracker == null && !nullFluxTrackerWarned) {
            nullFluxTrackerWarned = true
            log.warn("[GravityPhaseAI] ${ship.name} fluxTracker 为空，战术强制耗散通道关闭（ventTimeSec 置 MAX_VALUE）")
        }
        return PhaseSituation(
            phased = phased,
            phaseActiveTime = phaseActiveTime,
            timeSinceUnphase = timeSinceUnphase,
            hardFluxLevel = ship.hardFluxLevel,
            fluxLevel = ship.fluxLevel,
            incomingSoonDamage = incoming[0],
            incomingNearDamage = incoming[1],
            maxHull = ship.maxHitpoints,
            engagedEnemyNear = hasEngagedEnemy(engine, ship),
            systemReady = attackSystem != null &&
                    attackSystem.state == ShipSystemAPI.SystemState.IDLE &&
                    attackSystem.cooldownRemaining <= 0f,
            systemActive = attackSystem?.isOn == true,
            weaponsReadyFrac = weaponsReadyFrac(ship),
            retreating = ship.isRetreating,
            cloakReady = cloak.state == ShipSystemAPI.SystemState.IDLE &&
                    cloak.cooldownRemaining <= 0f && cloak.canBeActivated(),
            cloakCooldownRemaining = cloak.cooldownRemaining,
            softFluxLevel = ship.fluxLevel - ship.hardFluxLevel,
            weaponsDisabledFrac = weaponsDisabledFrac(ship),
            enginesDisabledFrac = enginesDisabledFrac(ship),
            targetLowMobility = lowMobility,
            inTargetRearArc = threat != null &&
                    angleDiffAbs(threat.facing, threatToUsBearing!!) >= REAR_ARC_MIN_DIFF,
            weaponCoverage = if (threat == null) 0 else weaponCoverageOn(ship, threat),
            friendlyCatchDamage = estimateFriendlyCatch(engine, ship),
            flankIntentActive = flankIntentRemaining > 0f && phased && (threat == null || lowMobility),
            flankIntentWindowSec = flankIntentWindowArmed,
            targetDistance = targetDist,
            effectiveWeaponRange = weaponRange,
            pressIntentActive = FlankAttackTree.pressIntentActive(
                pressIntentRemaining, phased, targetDist, weaponRange,
            ),
            threatDistance = threatDist,
            unphaseUnsafe = isUnphaseUnsafe(engine, ship),
            surfaceTooClose = isSurfaceTooClose(engine, ship),
            incomingSurfaceDamage = incoming[0] + incoming[1] + incoming[2] + friendlyIncoming[0],
            threatFrontAxisClose = threat != null && threatDist <= SURFACE_FRONT_AXIS_MAX_DIST &&
                    angleDiffAbs(threat.facing, threatToUsBearing!!) <= SURFACE_FRONT_AXIS_HALF_ARC,
            rearSurfaceDistanceSafe = isRearSurfaceDistanceSafe(ship, threat),
            forcedSurfaceHoldSec = 0f,
            incomingFriendlySoonDamage = friendlyIncoming[0],
            ventTimeSec = fluxTracker?.timeToVent ?: Float.MAX_VALUE,
            ventRateMult = ship.mutableStats.ventRateMult.modifiedValue,
            venting = fluxTracker?.isVenting == true,
            friendlyScreenAhead = hasFriendlyScreenAhead(engine, ship, threat),
            travelDestDistance = travelDestDistance(ship),
        )
    }

    private fun hasFriendlyScreenAhead(engine: CombatEngineAPI, ship: ShipAPI, threat: ShipAPI?): Boolean {
        if (threat == null) return false
        val toThreatX = threat.location.x - ship.location.x
        val toThreatY = threat.location.y - ship.location.y
        val threatDistSq = toThreatX * toThreatX + toThreatY * toThreatY
        if (threatDistSq < 1e-6f) return false
        val threatBearing =
            Math.toDegrees(kotlin.math.atan2(toThreatY.toDouble(), toThreatX.toDouble())).toFloat()
        for (other in engine.ships) {
            if (other === ship || other.owner != ship.owner || !other.isAlive ||
                other.isHulk || other.isFighter
            ) {
                continue
            }
            val dx = other.location.x - ship.location.x
            val dy = other.location.y - ship.location.y
            val distSq = dx * dx + dy * dy
            if (distSq > SCREEN_SCAN_DIST * SCREEN_SCAN_DIST || distSq < 1e-6f) continue
            val bearing = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angleDiffAbs(bearing, threatBearing) > SCREEN_AHEAD_HALF_ARC) continue
            if (distanceSq(other.location, threat.location) < threatDistSq) return true
        }
        return false
    }

    // MOVEMENT_DEST 为原版 OrderResponseModule 设置的赶路目标点旗标（燃驱 AI 同款用法）
    private fun travelDestDistance(ship: ShipAPI): Float {
        val dest = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MOVEMENT_DEST) as? Vector2f
            ?: return Float.MAX_VALUE
        return kotlin.math.sqrt(distanceSq(ship.location, dest))
    }

    private fun isRearSurfaceDistanceSafe(ship: ShipAPI, threat: ShipAPI?): Boolean {
        if (threat == null) return true
        val shield = threat.shield
        val enemyRadius =
            if (shield != null && shield.isOn) {
                maxOf(threat.collisionRadius, shield.radius)
            } else {
                threat.collisionRadius
            }
        val minSep = rearSurfaceMinSeparation(ship.collisionRadius, enemyRadius)
        val relX = ship.location.x - threat.location.x
        val relY = ship.location.y - threat.location.y
        if (relX * relX + relY * relY < minSep * minSep) return false
        val approach = closestApproachDistance(
            relX, relY,
            ship.velocity.x - threat.velocity.x,
            ship.velocity.y - threat.velocity.y,
            SURFACE_WINDOW_SEC,
        )
        return approach >= minSep
    }

    private fun isSurfaceTooClose(engine: CombatEngineAPI, ship: ShipAPI): Boolean {
        for (other in engine.ships) {
            if (!isValidEnemyShip(ship, other)) continue
            val limit = (other.collisionRadius + ship.collisionRadius) * SURFACE_PROXIMITY_FRAC
            if (distanceSq(ship.location, other.location) < limit * limit) return true
        }
        return false
    }

    // 对齐原版 canBeDeactivated 的 locationSafe 判定；原版对环带小行星豁免但公开 API 无法区分，实测触发源是舰船重叠
    private fun isUnphaseUnsafe(engine: CombatEngineAPI, ship: ShipAPI): Boolean {
        for (other in engine.ships) {
            if (other === ship || other.isFighter) continue
            if (other.parentStation === ship) continue
            val limit = (other.collisionRadius + ship.collisionRadius) * UNPHASE_UNSAFE_OVERLAP_FRAC
            if (distanceSq(ship.location, other.location) < limit * limit) return true
        }
        return false
    }

    private fun pickThreat(engine: CombatEngineAPI, ship: ShipAPI, target: ShipAPI?): ShipAPI? {
        if (target != null && isValidEnemyShip(ship, target) &&
            distanceSq(ship.location, target.location) <= ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        ) {
            return target
        }
        var best: ShipAPI? = null
        var bestDist = ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        for (other in engine.ships) {
            if (!isValidEnemyShip(ship, other)) continue
            val dist = distanceSq(ship.location, other.location)
            if (dist < bestDist) {
                bestDist = dist
                best = other
            }
        }
        return best
    }

    private fun weaponCoverageOn(ship: ShipAPI, threat: ShipAPI): Int {
        var count = 0
        for (weapon in threat.allWeapons) {
            if (weapon.isDecorative || weapon.isDisabled) continue
            if (weapon.usesAmmo() && weapon.ammo <= 0) continue
            val dx = ship.location.x - weapon.location.x
            val dy = ship.location.y - weapon.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > weapon.range + ship.collisionRadius) continue
            val angleToUs = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angleDiffAbs(weapon.currAngle, angleToUs) <= weapon.arc / 2f + COVERAGE_ARC_MARGIN) {
                count++
            }
        }
        return count
    }

    private fun estimateFriendlyCatch(engine: CombatEngineAPI, ship: ShipAPI): Float {
        var catch = 0f
        catch += friendlyCatchFrom(engine.projectiles, engine, ship)
        catch += friendlyCatchFrom(engine.missiles, engine, ship)
        return catch
    }

    private fun friendlyCatchFrom(
        projectiles: List<DamagingProjectileAPI>,
        engine: CombatEngineAPI,
        ship: ShipAPI,
    ): Float {
        val shipLoc = ship.location
        var catch = 0f
        for (proj in projectiles) {
            // fade 段弹体不过滤：引擎伤害判定只看 isExpired，fade 段仍可全额命中
            if (proj.owner == ship.owner || proj.isExpired) continue
            if (proj is MissileAPI && proj.isGuided) continue
            val dx = shipLoc.x - proj.location.x
            val dy = shipLoc.y - proj.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > PROJECTILE_SCAN_RANGE || dist < 1e-3f) continue

            val vel = proj.velocity
            val approach = (vel.x * dx + vel.y * dy) / dist
            if (approach <= APPROACH_SPEED_MIN) continue
            val eta = (dist - ship.collisionRadius) / approach
            if (eta < 0f || eta > NEAR_WINDOW_SEC) continue
            val hitX = proj.location.x + vel.x * eta - shipLoc.x
            val hitY = proj.location.y + vel.y * eta - shipLoc.y
            val hitR = ship.collisionRadius + MISS_MARGIN
            if (hitX * hitX + hitY * hitY > hitR * hitR) continue

            val startX = proj.location.x + vel.x * eta
            val startY = proj.location.y + vel.y * eta
            val remaining = FRIENDLY_CATCH_WINDOW_SEC - eta
            if (remaining <= 0f) continue
            val flyX = vel.x * remaining
            val flyY = vel.y * remaining
            for (friendly in engine.ships) {
                if (friendly === ship || friendly.owner != ship.owner ||
                    !friendly.isAlive || friendly.isHulk || friendly.isFighter
                ) {
                    continue
                }
                if (segmentHitsCircle(
                        startX, startY, flyX, flyY,
                        friendly.location.x, friendly.location.y, friendly.collisionRadius,
                    )
                ) {
                    catch += proj.damageAmount
                    break
                }
            }
        }
        return catch
    }

    private fun isValidEnemyShip(ship: ShipAPI, other: ShipAPI): Boolean =
        other !== ship && other.isAlive && !other.isHulk && !other.isFighter &&
                other.owner != ship.owner && other.owner != 100

    // 光束瞬时命中无弹体可采样，改为扫描正在开火且射界/射程覆盖本舰的光束武器折算威胁
    private fun estimateBeamThreat(engine: CombatEngineAPI, ship: ShipAPI, out: FloatArray, collectFriendly: Boolean) {
        for (source in engine.ships) {
            val isThreatSource = if (collectFriendly) {
                source !== ship && source.owner == ship.owner &&
                        source.isAlive && !source.isHulk && !source.isFighter
            } else {
                isValidEnemyShip(ship, source)
            }
            if (!isThreatSource) continue
            for (weapon in source.allWeapons) {
                if (weapon.isDecorative || weapon.isDisabled || !weapon.isFiring) continue
                // isBurstBeam 是 isBeam 子集
                if (!weapon.isBeam) continue
                if (weapon.usesAmmo() && weapon.ammo <= 0) continue
                val dx = ship.location.x - weapon.location.x
                val dy = ship.location.y - weapon.location.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > weapon.range + ship.collisionRadius) continue
                val angleToUs = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angleDiffAbs(weapon.currAngle, angleToUs) > weapon.arc / 2f + COVERAGE_ARC_MARGIN) {
                    continue
                }
                if (weapon.isBurstBeam) {
                    out[0] += beamThreatSoon(weapon.derivedStats.burstDamage)
                } else if (collectFriendly) {
                    out[0] += beamThreatNear(weapon.derivedStats.dps)
                } else {
                    out[1] += beamThreatNear(weapon.derivedStats.dps)
                }
            }
        }
    }

    private fun estimateIncoming(
        projectiles: List<DamagingProjectileAPI>,
        ship: ShipAPI,
        out: FloatArray,
        collectFriendly: Boolean,
    ) {
        val shipLoc = ship.location
        val radius = ship.collisionRadius
        for (proj in projectiles) {
            // fade 段弹体不过滤：引擎伤害判定只看 isExpired，fade 段仍可全额命中
            if ((proj.owner == ship.owner) != collectFriendly || proj.isExpired) continue
            val guided = proj is MissileAPI && proj.isGuided
            val dx = shipLoc.x - proj.location.x
            val dy = shipLoc.y - proj.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > PROJECTILE_SCAN_RANGE || dist < 1e-3f) continue

            // payload 导弹分支先于接近速度闸：悬停锁定段弹体低速，常规判定会漏
            if (!collectFriendly && proj is MissileAPI) {
                val profile = payloadProfileOf(proj)
                if (profile != null) {
                    if (dist <= profile.standoffDist) {
                        out[0] += profile.burstDamage
                    }
                    continue
                }
            }

            val vel = proj.velocity
            var approach = (vel.x * dx + vel.y * dy) / dist
            if (guided) {
                val missileSpeed = (proj as MissileAPI).maxSpeed * 0.5f
                if (approach < missileSpeed) approach = missileSpeed
            }
            if (approach <= APPROACH_SPEED_MIN) continue

            val eta = (dist - radius) / approach
            if (eta < 0f || eta > SURFACE_WINDOW_SEC) continue

            if (!guided) {
                val hitX = proj.location.x + vel.x * eta - shipLoc.x
                val hitY = proj.location.y + vel.y * eta - shipLoc.y
                if (hitX * hitX + hitY * hitY > (radius + MISS_MARGIN) * (radius + MISS_MARGIN)) continue
            }

            if (eta <= SOON_WINDOW_SEC) {
                out[0] += proj.damageAmount
            } else if (!collectFriendly) {
                if (eta <= NEAR_WINDOW_SEC) {
                    out[1] += proj.damageAmount
                } else {
                    out[2] += proj.damageAmount
                }
            }
        }
    }

    private fun hasEngagedEnemy(engine: CombatEngineAPI, ship: ShipAPI): Boolean =
        engine.ships.any { other ->
            isValidEnemyShip(ship, other) &&
                    distanceSq(ship.location, other.location) <= ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        }

    private fun weaponsReadyFrac(ship: ShipAPI): Float {
        var total = 0
        var ready = 0
        for (weapon in ship.allWeapons) {
            if (weapon.isDecorative || weapon.slot?.isSystemSlot != false) continue
            total++
            if (!weapon.isDisabled && weapon.cooldownRemaining < 0.3f &&
                (!weapon.usesAmmo() || weapon.ammo > 0)
            ) {
                ready++
            }
        }
        return if (total == 0) 1f else ready.toFloat() / total
    }

    private fun effectiveWeaponRange(ship: ShipAPI): Float {
        var maxRange = 0f
        for (weapon in ship.allWeapons) {
            if (weapon.isDecorative || weapon.slot?.isSystemSlot != false || weapon.isDisabled) continue
            if (weapon.type == WeaponAPI.WeaponType.MISSILE ||
                weapon.type == WeaponAPI.WeaponType.LAUNCH_BAY ||
                weapon.type == WeaponAPI.WeaponType.SYSTEM
            ) {
                continue
            }
            val hints = weapon.spec.aiHints
            if (hints.contains(WeaponAPI.AIHints.PD) || hints.contains(WeaponAPI.AIHints.PD_ONLY)) {
                continue
            }
            if (weapon.usesAmmo() && weapon.ammo <= 0) continue
            if (weapon.range > maxRange) maxRange = weapon.range
        }
        return maxRange
    }

    private fun weaponsDisabledFrac(ship: ShipAPI): Float {
        var total = 0
        var disabled = 0
        for (weapon in ship.allWeapons) {
            if (weapon.isDecorative || weapon.slot?.isSystemSlot != false) continue
            total++
            if (weapon.isDisabled) disabled++
        }
        return if (total == 0) 0f else disabled.toFloat() / total
    }

    private fun enginesDisabledFrac(ship: ShipAPI): Float {
        val controller = ship.engineController
        if (controller.isFlamedOut) return 1f
        val engines = controller.shipEngines
        if (engines.isEmpty()) return 0f
        return engines.count { it.isDisabled }.toFloat() / engines.size
    }

    private data class PayloadProfile(val standoffDist: Float, val burstDamage: Float)

    // behaviorSpec 带 payloadWeaponId 的弹体抵近 triggerDistance 悬停后由 payload 结算主伤害，弹体本体在悬停点自爆；推导失败记 WARN 并缓存 null
    private fun payloadProfileOf(proj: DamagingProjectileAPI): PayloadProfile? {
        val specId = proj.projectileSpecId ?: return null
        if (payloadProfileCache.containsKey(specId)) return payloadProfileCache[specId]
        var profile: PayloadProfile? = null
        val params = proj.projectileSpec?.behaviorSpec?.params
        val payloadId = params?.optString("payloadWeaponId", "") ?: ""
        if (params != null && payloadId.isNotEmpty()) {
            val burst =
                Global.getSettings().getWeaponSpec(payloadId)?.derivedStats?.burstDamage ?: 0f
            val trigger = params.optJSONArray("triggerDistance")
            val standoff = if (trigger != null && trigger.length() > 0) {
                (0 until trigger.length()).maxOf { trigger.optDouble(it, 0.0) }.toFloat()
            } else {
                params.optDouble("triggerDistance", 0.0).toFloat()
            }
            if (burst > 0f && standoff > 0f) {
                profile = PayloadProfile(standoff, burst)
            } else {
                log.warn(
                    "[GravityPhaseAI] payload 导弹档案推导失败：projSpec=$specId " +
                            "payloadWeaponId=$payloadId burst=$burst standoff=$standoff，" +
                            "该弹体的 payload 威胁通道不生效",
                )
            }
        }
        payloadProfileCache[specId] = profile
        return profile
    }

    private fun distanceSq(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
