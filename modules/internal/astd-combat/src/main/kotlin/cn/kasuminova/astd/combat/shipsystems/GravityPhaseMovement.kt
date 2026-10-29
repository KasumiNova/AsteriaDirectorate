package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseBounds
import cn.kasuminova.astd.combat.shipsystems.base.PhaseMotion
import cn.kasuminova.astd.combat.shipsystems.base.PhaseObstacle
import cn.kasuminova.astd.combat.shipsystems.base.PhasePosition
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteRequest
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteSpace
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteTarget
import cn.kasuminova.astd.combat.shipsystems.base.PhaseTargeting
import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

internal object GravityPhaseMovement {
    fun target(ship: ShipAPI): PhaseRouteTarget = PhaseRouteTarget(
        id = ship.id,
        position = point(ship.location),
        facing = ship.facing,
        radius = ship.collisionRadius,
        valid = ship.isAlive && !ship.isHulk,
        velocity = point(ship.velocity) * ship.mutableStats.timeMult.modifiedValue,
        angularVelocity = ship.angularVelocity * ship.mutableStats.timeMult.modifiedValue,
    )

    fun request(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        target: ShipAPI,
        attackRange: Float,
        maxElapsed: Float = 24f,
        continuation: Boolean = false,
    ): PhaseRouteRequest {
        val stats = ship.mutableStats
        val threshold = stats.dynamic.getMod("phase_cloak_flux_level_for_min_speed_mod")
            .computeEffective(com.fs.starfarer.api.impl.combat.PhaseCloakStats.BASE_FLUX_LEVEL_FOR_MIN_SPEED)
        val currentDisruption = if (threshold > 0f) (ship.hardFluxLevel / threshold).coerceIn(0f, 1f) else 1f
        val currentEffect = ship.phaseCloak?.effectLevel?.coerceIn(0f, 1f) ?: 0f
        val activationCost = ship.phaseCloak?.fluxPerUse ?: 0f
        val activationFluxLevel = if (activationCost > 0f) activationCost / ship.maxFlux else 0f
        val planningHardFlux = if (currentEffect > 0f) {
            ship.hardFluxLevel
        } else {
            val budgetLevel = if (continuation) 0.30f else 0.15f
            maxOf(ship.hardFluxLevel + activationFluxLevel, budgetLevel).coerceAtMost(1f)
        }
        val planningDisruption = if (threshold > 0f) {
            (planningHardFlux / threshold).coerceIn(0f, 1f)
        } else 1f
        val minSpeed = com.fs.starfarer.api.impl.combat.PhaseCloakStats.MIN_SPEED_MULT
        val coilAtFullEffect = minSpeed + (1f - minSpeed) * (1f - planningDisruption)
        val currentCoil = minSpeed + (1f - minSpeed) * (1f - currentDisruption * currentEffect)
        val speedMod = stats.dynamic.getMod("phase_cloak_speed")
        val speedPercent = speedMod.computeEffective(0f) * 0.01f
        val currentPhaseSpeed = if (currentEffect > 0f) {
            (1f + speedPercent * currentEffect) * speedMod.mult * currentEffect
        } else {
            1f
        }
        val fullPhaseSpeed = (1f + speedPercent) * speedMod.mult
        val unphasedSpeed = stats.maxSpeed.modifiedValue / (currentCoil * currentPhaseSpeed)
        val speed = (unphasedSpeed * fullPhaseSpeed * coilAtFullEffect).coerceAtLeast(20f)
        engine.customData["astd_phase_route_planning_${ship.id}"] = mapOf(
            "statsMaxSpeed" to stats.maxSpeed.modifiedValue,
            "planningSpeed" to speed,
            "unphasedSpeed" to unphasedSpeed,
            "hardFluxLevel" to ship.hardFluxLevel,
            "threshold" to threshold,
            "currentEffect" to currentEffect,
            "currentCoil" to currentCoil,
            "coilAtFullEffect" to coilAtFullEffect,
            "phaseSpeedPercent" to speedMod.computeEffective(0f),
            "phaseSpeedMult" to speedMod.mult,
            "currentTimeMult" to stats.timeMult.modifiedValue,
            "targetPosition" to target.location,
            "targetVelocity" to target.velocity,
            "targetFacing" to target.facing,
            "attackRange" to attackRange,
            "maxElapsed" to maxElapsed,
        )
        val frontHalfArc = target.allWeapons.mapNotNull { weapon ->
            if (!PhaseTargeting.usableAttackWeapon(weapon)) return@mapNotNull null
            val centerOffset = kotlin.math.abs(PhaseTargeting.angleDiff(weapon.arcFacing, target.facing))
            if (centerOffset <= 45f) centerOffset + weapon.arc / 2f else null
        }.maxOrNull()?.coerceIn(20f, 35f) ?: 30f
        return PhaseRouteRequest(
            position = point(ship.location),
            target = target(target),
            shipRadius = ship.collisionRadius,
            speed = speed,
            planningInterval = 0.2f,
            attackRange = attackRange.coerceAtLeast(100f),
            lateralDistance = (ship.collisionRadius * 6f).coerceIn(600f, 1200f),
            frontHalfArc = frontHalfArc,
            clearance = 30f,
            sampleStep = (speed * 0.2f).coerceAtMost(100f),
            maxDamage = ship.hitpoints,
            maxElapsed = maxElapsed,
        )
    }

    fun space(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        target: ShipAPI,
        phasedRoute: Boolean = false,
        useCurrentClock: Boolean = false,
    ): PhaseRouteSpace {
        val obstacles = buildList {
            engine.ships.forEach { other ->
                if (other !== ship && other !== target && other.isAlive && !other.isHulk && !other.isFighter) {
                    val timeMult = other.mutableStats.timeMult.modifiedValue
                    add(PhaseObstacle(point(other.location), other.collisionRadius, point(other.velocity) * timeMult))
                }
            }
            engine.asteroids.forEach { asteroid ->
                add(PhaseObstacle(point(asteroid.location), asteroid.collisionRadius, point(asteroid.velocity)))
            }
        }
        val enemies = engine.ships.filter { PhaseTargeting.isValidEnemy(ship, it) }
        val threat = PhaseThreat()
        val defense = threat.defense(ship)
        val maxPhaseMult = com.fs.starfarer.api.impl.combat.PhaseCloakStats.getMaxTimeMult(ship.mutableStats)
        val effectLevel = ship.phaseCloak?.effectLevel?.coerceIn(0f, 1f) ?: 0f
        val currentPhaseMult = 1f + (maxPhaseMult - 1f).coerceAtLeast(0f) * effectLevel
        val baseMult = ship.mutableStats.timeMult.modifiedValue / currentPhaseMult
        val timeMult = if (useCurrentClock) {
            ship.mutableStats.timeMult.modifiedValue
        } else {
            if (phasedRoute) baseMult * maxPhaseMult else baseMult
        }
        val validClock = timeMult.isFinite() && timeMult > 0f
        if (!validClock) {
            com.fs.starfarer.api.Global.getLogger(GravityPhaseMovement::class.java).warn(
                "Route clock unavailable for ${ship.id}: $timeMult; direct movement uses current-frame collision prediction",
            )
        }
        return PhaseRouteSpace(
            battleTimePerLocalSecond = if (validClock) 1f / timeMult else 1f,
            collides = !phasedRoute,
            bounds = PhaseBounds(
                -engine.mapWidth / 2f,
                engine.mapWidth / 2f,
                -engine.mapHeight / 2f,
                engine.mapHeight / 2f,
            ),
            obstacles = obstacles,
            damagePerSecond = { position, time ->
                enemies.sumOf { enemy ->
                    val travel = point(enemy.velocity) * (enemy.mutableStats.timeMult.modifiedValue * time)
                    enemy.allWeapons.sumOf { weapon ->
                        if (!PhaseTargeting.usableAttackWeapon(weapon)) return@sumOf 0.0
                        val arcPoint = Vector2f(position.x - travel.x, position.y - travel.y)
                        if (weapon.distanceFromArc(arcPoint) > 0f) return@sumOf 0.0
                        val origin = point(weapon.location) + travel
                        if (origin.distance(position) > weapon.range + ship.collisionRadius) return@sumOf 0.0
                        val dps = weapon.derivedStats.dps
                        val beam = weapon.isBeam || weapon.isBurstBeam
                        val baseStrength = if (beam) {
                            dps * com.fs.starfarer.api.Global.getSettings().getFloat("dpsToHitStrengthMult")
                        } else {
                            weapon.derivedStats.damagePerShot
                        }
                        val strength = weapon.ship.mutableStats.hitStrengthBonus.computeEffective(baseStrength)
                        val impact = position + (origin - position).unit() * ship.collisionRadius
                        val cell = ship.armorGrid.getCellAtLocation(Vector2f(impact.x, impact.y))
                        PhaseThreat.sustainedHullLoss(
                            defense = defense,
                            damagePerSecond = dps,
                            type = weapon.damageType,
                            hitStrength = strength,
                            stage = if (beam) PhaseThreat.Stage.BEAM else PhaseThreat.Stage.DIRECT,
                            cellX = cell?.get(0) ?: -1,
                            cellY = cell?.get(1) ?: -1,
                        ).toDouble()
                    }
                }.toFloat() * if (phasedRoute) {
                    com.fs.starfarer.api.impl.combat.PhaseCloakStats.INCOMING_DAMAGE_MULT
                } else 1f
            },
        )
    }

    fun motion(ship: ShipAPI, canAttackTarget: Boolean, surfaceUnsafe: Boolean = false): PhaseMotion {
        val stats = ship.mutableStats
        return PhaseMotion(
            position = point(ship.location),
            velocity = point(ship.velocity),
            facing = ship.facing,
            angularVelocity = ship.angularVelocity,
            radius = ship.collisionRadius,
            maxSpeed = stats.maxSpeed.modifiedValue,
            acceleration = stats.acceleration.modifiedValue,
            deceleration = stats.deceleration.modifiedValue,
            strafeAcceleration = stats.acceleration.modifiedValue * when (ship.hullSpec.hullSize) {
                ShipAPI.HullSize.CAPITAL_SHIP -> 0.25f
                ShipAPI.HullSize.CRUISER -> 0.5f
                ShipAPI.HullSize.DESTROYER -> 0.75f
                else -> 1f
            },
            turnAcceleration = stats.turnAcceleration.modifiedValue,
            maxTurnRate = stats.maxTurnRate.modifiedValue,
            canAttackTarget = canAttackTarget,
            surfaceUnsafe = surfaceUnsafe,
        )
    }

    fun point(vector: Vector2f): PhasePosition = PhasePosition(vector.x, vector.y)
}
