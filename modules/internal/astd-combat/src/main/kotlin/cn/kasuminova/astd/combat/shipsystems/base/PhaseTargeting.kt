package cn.kasuminova.astd.combat.shipsystems.base

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.WingRole
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

internal object PhaseTargeting {
    fun selectTarget(engine: CombatEngineAPI, ship: ShipAPI, preferred: ShipAPI?): ShipAPI? {
        if (isValidEnemy(ship, preferred)) return preferred
        val current = ship.shipTarget
        if (isValidEnemy(ship, current)) return current
        return engine.ships.asSequence()
            .filter { isValidEnemy(ship, it) }
            .minByOrNull { distanceSq(ship.location, it.location) }
    }

    fun isValidEnemy(ship: ShipAPI, other: ShipAPI?): Boolean =
        other != null && other !== ship && other.isAlive && !other.isHulk && !other.isFighter &&
            other.owner != ship.owner && other.owner != 100

    fun distance(a: Vector2f, b: Vector2f): Float = sqrt(distanceSq(a, b))

    fun distanceSq(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    fun angleDiff(a: Float, b: Float): Float {
        var diff = (a - b) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return abs(diff)
    }

    fun bearing(from: Vector2f, to: Vector2f): Float =
        Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()

    fun inRearArc(ship: ShipAPI, target: ShipAPI): Boolean =
        angleDiff(target.facing, bearing(target.location, ship.location)) >= 110f

    fun weaponCanAttack(weapon: WeaponAPI, target: ShipAPI, soon: Boolean = false): Boolean {
        if (!usableAttackWeapon(weapon) || target.isPhased) return false
        if (weapon.isInBurst) return inRangeAndArc(weapon, target)
        if (soon) {
            if (weapon.cooldownRemaining <= 0f || weapon.cooldownRemaining > 2f) return false
        } else if (weapon.cooldownRemaining > 0.05f) {
            return false
        }
        return inRangeAndArc(weapon, target)
    }

    fun inRangeAndArc(weapon: WeaponAPI, target: ShipAPI): Boolean {
        if (distance(weapon.location, target.location) > weapon.range + target.collisionRadius) return false
        return weapon.distanceFromArc(target.location) <= 0.01f
    }

    fun attackWeapon(weapon: WeaponAPI): Boolean =
        !weapon.isDecorative && !weapon.isDisabled && !weapon.isForceDisabled && weapon.slot?.isSystemSlot != true &&
            weapon.type != WeaponAPI.WeaponType.LAUNCH_BAY && weapon.type != WeaponAPI.WeaponType.SYSTEM &&
            !weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY)

    fun usableAttackWeapon(weapon: WeaponAPI): Boolean = attackWeapon(weapon) &&
        (weapon.isInBurst || (weapon.isBeam && weapon.isFiring) || !weapon.usesAmmo() ||
            WeaponCost.hasAmmoForFullVolley(WeaponSnapshot.from(weapon)))

    fun effectiveRange(ship: ShipAPI): Float = ship.allWeapons.asSequence()
        .filter { usableAttackWeapon(it) }
        .maxOfOrNull { it.range } ?: 0f

    fun sampleSupport(ship: ShipAPI, target: ShipAPI?): PhaseSupport {
        if (ship.isPullBackFighters ||
            ship.forceCarrierPullBackTime > 0f && ship.forceCarrierTargetTime <= 0f
        ) return PhaseSupport()
        val enemy = target?.takeIf { isValidEnemy(ship, it) }
        val targetDistance = enemy?.let { distance(ship.location, it.location) }
            ?: Float.POSITIVE_INFINITY
        var effectiveRange = 0f
        var targetInRange = false
        var engaged = false
        for (wing in ship.allWings) {
            if (wing.sourceShip !== ship || wing.isDestroyed) continue
            when (wing.role) {
                WingRole.BOMBER, WingRole.FIGHTER, WingRole.INTERCEPTOR, WingRole.ASSAULT -> Unit
                else -> continue
            }
            val range = wing.range
            if (!range.isFinite() || range <= 0f) continue
            val fighters = wing.wingMembers.filter { fighter ->
                fighter.isFighter && fighter.isAlive && !fighter.isHulk &&
                    fighter.owner == ship.owner && !fighter.isShuttlePod && !fighter.isPhased &&
                    !fighter.isLanding && !fighter.isFinishedLanding && !fighter.isRetreating &&
                    !wing.isReturning(fighter) && !fighter.isNonCombat(false) &&
                    fighter.allWeapons.any { usableAttackWeapon(it) }
            }
            if (fighters.isEmpty()) continue
            effectiveRange = maxOf(effectiveRange, range)
            if (enemy == null || targetDistance > range + ship.collisionRadius + enemy.collisionRadius) continue
            targetInRange = true
            if (!enemy.isPhased && fighters.any { fighter ->
                fighter.shipTarget === enemy && fighter.allWeapons.any { weapon ->
                    usableAttackWeapon(weapon) && inRangeAndArc(weapon, enemy) &&
                        if (weapon.isBeam) weapon.isFiring && weapon.beams.any { beam ->
                            beam.damageTarget === enemy && beam.brightness > 0f && beam.damage.damage > 0f
                        } else weapon.isFiring || weapon.isInBurst
                }
            }) engaged = true
        }
        return PhaseSupport(
            available = effectiveRange > 0f,
            effectiveRange = effectiveRange,
            targetInRange = targetInRange,
            engaged = engaged,
        )
    }

    fun targetCoverage(ship: ShipAPI, target: ShipAPI): Int = target.allWeapons.count { weapon ->
        usableAttackWeapon(weapon) && inRangeAndArc(weapon, ship)
    }

    fun fireTarget(weapon: WeaponAPI): ShipAPI? {
        val ship = weapon.ship ?: return null
        val group = ship.getWeaponGroupFor(weapon)
        return if (group?.isAutofiring == true) group.getAutofirePlugin(weapon)?.targetShip else ship.shipTarget
    }

    fun effectiveOutput(target: ShipAPI, states: Collection<WeaponState>): Boolean = states.any { state ->
        val weapon = state.snapshot.weapon
        if (!attackWeapon(weapon) || !inRangeAndArc(weapon, target)) return@any false
        when {
            weapon.isBeam -> weapon.isFiring && weapon.beams.any {
                it.damageTarget === target && it.brightness > 0f && it.damage.damage > 0f
            }
            else -> state.releaseObserved && state.releaseTarget === target ||
                weapon.isInBurst && state.outputState == WeaponOutputState.OUTPUTTING && fireTarget(weapon) === target
        }
    }

    fun enemyWindow(target: ShipAPI): Float {
        val flux = target.fluxTracker ?: return 0f
        val targetTimeMult = target.mutableStats.timeMult.modifiedValue
        if (!targetTimeMult.isFinite() || targetTimeMult <= 0f) return 0f
        val localRemaining = when {
            flux.isOverloaded -> flux.overloadTimeRemaining
            flux.isVenting -> {
                val rate = 2f * target.mutableStats.fluxDissipation.modifiedValue *
                    target.mutableStats.ventRateMult.modifiedValue
                if (!rate.isFinite() || rate <= 0f) return 0f
                flux.currFlux / rate
            }
            else -> 0f
        }
        val remaining = localRemaining / targetTimeMult
        return remaining.takeIf { it.isFinite() && it >= 0f } ?: 0f
    }
}
