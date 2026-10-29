package cn.kasuminova.astd.combat.automation.base

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState
import com.fs.starfarer.api.combat.WeaponAPI
import java.util.Collections
import java.util.IdentityHashMap

class PhaseVolleyEvidence {
    data class Volley(
        val weapon: WeaponAPI,
        val fires: MutableList<PhaseCombatEvidence.FireEvent>,
        val expectedShots: Int,
        val burstBeam: Boolean,
        var end: Float = -1f,
        var cancelled: Boolean = false,
        var beamAtFullCharge: Boolean = false,
        var beamFinishedChargeDown: Boolean = false,
        var beamLocalTime: Float = 0f,
        var lastSample: Float = -1f,
        var wasEmitting: Boolean = false,
        var lastTimeMult: Float = 0f,
        var minimumTimeMult: Float = Float.POSITIVE_INFINITY,
    ) {
        val start: Float get() = fires.minOf { it.timeSec }
        val complete: Boolean get() = end >= 0f && !cancelled && if (burstBeam) {
            val spec = weapon.spec as? com.fs.starfarer.api.loading.BeamWeaponSpecAPI
            spec != null && beamAtFullCharge && beamFinishedChargeDown && beamLocalTime >= spec.burstDuration
        } else {
            val spec = weapon.spec as? com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
            val ordered = fires.sortedBy { it.timeSec }
            val intervals = ordered.zipWithNext()
            val validClocks = ordered.all { it.timeMult.isFinite() && it.timeMult > 0f }
            val localDuration = intervals.sumOf { (first, second) ->
                ((second.timeSec - first.timeSec) * minOf(first.timeMult, second.timeMult)).toDouble()
            }.toFloat()
            val minimumTimeMult = intervals.minOfOrNull { (first, second) ->
                minOf(first.timeMult, second.timeMult)
            } ?: 1f
            fires.sumOf { it.shots } >= expectedShots && (expectedShots == 1 || spec != null && validClocks &&
                localDuration + (1f / 60f + 0.0001f) * minimumTimeMult >= spec.burstDelay * (expectedShots - 1))
        }
        val fullBurst: Boolean get() = complete && (expectedShots > 1 || burstBeam)
    }

    val volleys = ArrayList<Volley>()
    private val active = IdentityHashMap<WeaponAPI, Volley>()
    private val seen = Collections.newSetFromMap(IdentityHashMap<PhaseCombatEvidence.FireEvent, Boolean>())

    fun update(ship: ShipAPI, fires: List<PhaseCombatEvidence.FireEvent>, now: Float) {
        for (fire in fires) {
            if (!seen.add(fire)) continue
            val weapon = fire.weapon
            if (weapon.isBeam && !weapon.isBurstBeam) continue
            val volley = active.getOrPut(weapon) {
                Volley(weapon, ArrayList(), weapon.spec.burstSize.coerceAtLeast(1), weapon.isBurstBeam).also(volleys::add)
            }
            volley.fires += fire
        }
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val (weapon, volley) = iterator.next()
            volley.minimumTimeMult = minOf(volley.minimumTimeMult, ship.mutableStats?.timeMult?.modifiedValue ?: Float.NaN)
            val emitting = weapon.isBeam && weapon.beams.any { it.brightness > 0f && it.damage.damage > 0f }
            val diving = ship.phaseCloak?.state == SystemState.IN || ship.isPhased
            if (volley.burstBeam) {
                val timeMult = ship.mutableStats.timeMult.modifiedValue
                if (emitting && volley.wasEmitting && volley.lastSample >= 0f) {
                    volley.beamLocalTime += (now - volley.lastSample) * minOf(timeMult, volley.lastTimeMult)
                }
                if (emitting && weapon.chargeLevel >= 1f) volley.beamAtFullCharge = true
                if (volley.beamAtFullCharge && weapon.chargeLevel <= 0f) volley.beamFinishedChargeDown = true
                volley.lastSample = now
                volley.wasEmitting = emitting
                volley.lastTimeMult = timeMult
            }
            if (weapon.isDisabled || weapon.isForceDisabled || diving) {
                volley.cancelled = true
                volley.end = now
                iterator.remove()
            } else if (!weapon.isInBurst && !emitting && weapon.cooldownRemaining > 0f &&
                now > volley.fires.maxOf { it.timeSec }) {
                volley.end = now
                iterator.remove()
            }
        }
    }

    fun damage(volley: Volley, hits: List<PhaseCombatEvidence.HitEvent>, deadline: Float): Float = hits.filter { hit ->
        volley.fires.any { it === hit.fire } && hit.timeSec in hit.fire.timeSec..deadline
    }.sumOf { it.effectiveDamage.toDouble() }.toFloat()
}
