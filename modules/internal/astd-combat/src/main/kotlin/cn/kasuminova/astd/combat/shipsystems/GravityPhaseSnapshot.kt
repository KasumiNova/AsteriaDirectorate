package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseAttack
import cn.kasuminova.astd.combat.shipsystems.base.PhaseDanger
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecovery
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PhaseTargeting
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import org.lwjgl.util.vector.Vector2f

internal data class PhaseRouteStatus(
    val active: Boolean,
    val complete: Boolean,
    val failed: Boolean,
    val stage: PhaseRouteStage?,
    val recoveryRequired: Boolean = false,
    val progress: Float = 0f,
)

internal class GravityPhaseSnapshot {
    fun sample(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        cloak: ShipSystemAPI,
        preferredTarget: ShipAPI?,
        phased: Boolean,
        phaseActiveTime: Float,
        timeSinceUnphase: Float,
        route: PhaseRouteStatus,
        danger: PhaseDanger,
        recovery: PhaseRecovery,
        remainingBurstCost: Float,
        effectiveOutput: Boolean,
        hardFluxRecoveryActive: Boolean = false,
    ): PhaseSituation {
        val target = PhaseTargeting.selectTarget(engine, ship, preferredTarget)
        val flux = ship.fluxTracker
        val hardFlux = flux.hardFlux
        val maxFlux = flux.maxFlux
        val weapons = ship.allWeapons.filter { PhaseTargeting.usableAttackWeapon(it) }
        val attackWeapons = if (target == null) emptyList() else weapons.filter {
            PhaseTargeting.inRangeAndArc(it, target)
        }
        val canFire = target != null && attackWeapons.any { PhaseTargeting.weaponCanAttack(it, target) }
        val readySoon = target != null && attackWeapons.any {
            PhaseTargeting.weaponCanAttack(it, target, soon = true)
        }
        val cooldown = weapons.map { it.cooldownRemaining }.filter { it > 0f }.minOrNull() ?: 0f
        val killingBlow = target != null && target.shield?.isOn != true && canFire &&
            attackWeapons.sumOf { it.derivedStats.burstDamage.toDouble() }.toFloat() * 0.5f >= target.hitpoints
        val attack = PhaseAttack(
            hasWeapons = weapons.isNotEmpty(),
            inRange = attackWeapons.isNotEmpty(),
            canFire = canFire,
            readySoon = readySoon,
            remainingBurstCost = remainingBurstCost,
            burstWithinCapacity = flux.currFlux + remainingBurstCost <= maxFlux,
            effectiveOutput = effectiveOutput,
            killingBlow = killingBlow,
            cooldownRemaining = cooldown,
        )
        val targetDistance = target?.let { PhaseTargeting.distance(ship.location, it.location) }
            ?: Float.POSITIVE_INFINITY
        val enemyWindow = target?.let { PhaseTargeting.enemyWindow(it) } ?: 0f
        return PhaseSituation(
            targetId = target?.id,
            phased = phased,
            phaseActiveTime = phaseActiveTime,
            timeSinceUnphase = timeSinceUnphase,
            hardFlux = hardFlux,
            softFlux = (flux.currFlux - hardFlux).coerceAtLeast(0f),
            maxFlux = maxFlux,
            hull = ship.hitpoints,
            maxHull = ship.maxHitpoints,
            cloakReady = cloak.state == ShipSystemAPI.SystemState.IDLE &&
                cloak.cooldownRemaining <= 0f && cloak.canBeActivated(),
            retreating = ship.isRetreating,
            unphaseUnsafe = unphaseUnsafe(engine, ship),
            targetDistance = targetDistance,
            effectiveWeaponRange = PhaseTargeting.effectiveRange(ship),
            inTargetRearArc = target != null && PhaseTargeting.inRearArc(ship, target),
            targetFrontCoverage = target?.let { PhaseTargeting.targetCoverage(ship, it) } ?: 0,
            enemyNearby = target != null || engine.ships.any {
                PhaseTargeting.isValidEnemy(ship, it) &&
                    PhaseTargeting.distanceSq(ship.location, it.location) <= 2200f * 2200f
            },
            enemyWindowRemaining = enemyWindow,
            travelDestDistance = travelDestDistance(ship),
            attack = attack,
            danger = danger,
            recovery = recovery,
            routeActive = route.active,
            routeComplete = route.complete,
            routeFailed = route.failed,
            routeRecoveryRequired = route.recoveryRequired,
            routeStage = route.stage,
            routeProgress = route.progress,
            phaseActivationCost = if (phased) 0f else cloak.fluxPerUse,
            support = PhaseTargeting.sampleSupport(ship, target),
            opportunity = cn.kasuminova.astd.combat.shipsystems.base.PhaseOpportunities.sample(
                ship, cloak, target, recovery, remainingBurstCost,
            ),
            hardFluxRecoveryActive = hardFluxRecoveryActive,
        )
    }

    fun unphaseUnsafe(engine: CombatEngineAPI, ship: ShipAPI): Boolean = engine.ships.any {
        if (it === ship || it.isFighter || it.parentStation === ship || !it.isAlive) return@any false
        val limit = (it.collisionRadius + ship.collisionRadius) * 0.5f
        PhaseTargeting.distanceSq(ship.location, it.location) < limit * limit
    }

    private fun travelDestDistance(ship: ShipAPI): Float {
        val dest = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MOVEMENT_DEST) as? Vector2f
            ?: return Float.POSITIVE_INFINITY
        return PhaseTargeting.distance(ship.location, dest)
    }
}
