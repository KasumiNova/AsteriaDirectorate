package cn.kasuminova.astd.combat.shipsystems.base

import cn.kasuminova.astd.combat.effect.arc.starfallecho.StarfallEchoTuning
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class WeaponOutputState { OUTPUTTING, CHARGING, HOLDING, COOLDOWN, FINISHING }

internal enum class WeaponCostUncertainty {
    BARREL_MODE, UNOBSERVED_SHOTS, CHARGE_DIRECTION, PULSE_CHARGE_FLOOR,
    FRAME_DEPENDENT_FLUX, UNSUPPORTED_WEAPON, CAPACITY_LIMITED_PLAN,
    PARTIAL_AMMO_VOLLEY, PULSE_RELEASE_AMBIGUITY,
}

internal data class WeaponCostEstimate(
    val amount: Float,
    val uncertainties: Set<WeaponCostUncertainty> = emptySet(),
) {
    operator fun plus(other: WeaponCostEstimate) = WeaponCostEstimate(amount + other.amount, uncertainties + other.uncertainties)

    companion object {
        val ZERO = WeaponCostEstimate(0f)
    }
}

internal data class WeaponSnapshot(
    val weapon: WeaponAPI,
    val id: String,
    val cooldownRemaining: Float,
    val cooldown: Float,
    val chargeLevel: Float,
    val isFiring: Boolean,
    val isInBurst: Boolean,
    val burstFireTimeRemaining: Float,
    val isBeam: Boolean,
    val isBurstBeam: Boolean,
    val isPulse: Boolean,
    val pulseRequiresFullCharge: Boolean,
    val isInterruptibleBurst: Boolean,
    val burstSize: Int,
    val burstDelay: Float,
    val burstDuration: Float,
    val chargeupTime: Float,
    val chargedownTime: Float,
    val chargeTime: Float,
    val fluxPerSecond: Float,
    val energyPerShot: Float,
    val fluxMultiplier: Float,
    val fluxFlat: Float,
    val barrels: Int,
    val ammo: Int,
    val ammoPerSecond: Float,
    val usesAmmo: Boolean,
    val isDisabled: Boolean,
    val isDecorative: Boolean,
    val uncertainties: Set<WeaponCostUncertainty>,
) {
    companion object {
        fun from(weapon: WeaponAPI): WeaponSnapshot {
            val spec = weapon.spec
            val proj = spec as? ProjectileWeaponSpecAPI
            val beam = spec as? BeamWeaponSpecAPI
            val stats = weapon.ship?.mutableStats
            val modifier = when (weapon.type) {
                WeaponAPI.WeaponType.ENERGY -> stats?.energyWeaponFluxCostMod
                WeaponAPI.WeaponType.BALLISTIC -> stats?.ballisticWeaponFluxCostMod
                WeaponAPI.WeaponType.MISSILE -> stats?.missileWeaponFluxCostMod
                else -> null
            }
            val beamMult = if (weapon.isBeam) stats?.beamWeaponFluxCostMult?.modifiedValue ?: 1f else 1f
            val flat = modifier?.computeEffective(0f) ?: 0f
            val multiplier = (modifier?.computeEffective(1f) ?: 1f) - flat
            val group = if (proj == null || weapon.isPulse) FireGroup(1, emptySet()) else fireGroup(weapon, proj)
            val unsupported = proj == null && beam == null
            return WeaponSnapshot(
                weapon = weapon,
                id = weapon.id,
                cooldownRemaining = weapon.cooldownRemaining,
                cooldown = weapon.cooldown,
                chargeLevel = if (weapon.isPulse) weapon.chargeLevel else weapon.chargeLevel.coerceIn(0f, 1f),
                isFiring = weapon.isFiring,
                isInBurst = weapon.isInBurst,
                burstFireTimeRemaining = weapon.burstFireTimeRemaining,
                isBeam = weapon.isBeam,
                isBurstBeam = weapon.isBurstBeam,
                isPulse = weapon.isPulse,
                pulseRequiresFullCharge = proj?.isRequiresFullCharge ?: false,
                isInterruptibleBurst = proj?.isInterruptibleBurst ?: false,
                burstSize = spec.burstSize.coerceAtLeast(1),
                burstDelay = proj?.burstDelay ?: 0f,
                burstDuration = beam?.burstDuration ?: 0f,
                chargeupTime = beam?.chargeupTime ?: 0f,
                chargedownTime = beam?.chargedownTime ?: 0f,
                chargeTime = proj?.chargeTime ?: 0f,
                fluxPerSecond = proj?.energyPerSecond ?: beam?.fluxPerSecond ?: 0f,
                energyPerShot = proj?.energyPerShot ?: 0f,
                fluxMultiplier = multiplier * beamMult,
                fluxFlat = flat * beamMult,
                barrels = group.barrels,
                ammo = weapon.ammo,
                ammoPerSecond = weapon.ammoPerSecond,
                usesAmmo = weapon.usesAmmo(),
                isDisabled = weapon.isDisabled,
                isDecorative = weapon.isDecorative,
                uncertainties = group.uncertainties + if (unsupported) setOf(WeaponCostUncertainty.UNSUPPORTED_WEAPON) else emptySet(),
            )
        }

        private fun fireGroup(weapon: WeaponAPI, spec: ProjectileWeaponSpecAPI): FireGroup {
            val original = weapon.originalSpec as? ProjectileWeaponSpecAPI ?: spec
            val turretCount = original.turretFireOffsets.size.coerceAtLeast(1)
            val offsets = when {
                weapon.slot?.isHidden == true -> spec.hiddenFireOffsets
                weapon.slot?.isHardpoint == true -> spec.hardpointFireOffsets
                else -> spec.turretFireOffsets
            }
            val mountedCount = offsets.size.coerceAtLeast(1)
            val derived = original.derivedStats
            val shots = original.burstSize.coerceAtLeast(1)
            val shotFlux = original.energyPerShot * shots
            val candidates = mutableListOf<Int>()
            if (derived != null && shotFlux > 0f) {
                val rawVolley = derived.fluxPerDam * max(1f, derived.damagePerShot * shots)
                val turretGroup = (rawVolley - original.chargeTime * original.energyPerSecond) / shotFlux
                if (abs(turretGroup - 1f) < 0.001f) candidates += 1
                if (abs(turretGroup - 2f) < 0.001f) candidates += 2
                if (abs(turretGroup - turretCount) < 0.001f) candidates += mountedCount
            }
            val distinct = candidates.distinct()
            return if (distinct.size == 1) {
                FireGroup(distinct.single(), emptySet())
            } else {
                FireGroup(distinct.maxOrNull() ?: max(2, mountedCount), setOf(WeaponCostUncertainty.BARREL_MODE))
            }
        }

        private data class FireGroup(val barrels: Int, val uncertainties: Set<WeaponCostUncertainty>)
    }
}

internal data class WeaponState(
    val snapshot: WeaponSnapshot,
    val outputState: WeaponOutputState,
    val remainingShots: Int,
    val prepaid: Boolean,
    val volley: Int,
    val uncertainties: Set<WeaponCostUncertainty>,
    val pulseShotObserved: Boolean,
    val releaseObserved: Boolean,
    val releaseTarget: ShipAPI?,
) {
    val active: Boolean get() = outputState == WeaponOutputState.CHARGING ||
        outputState == WeaponOutputState.OUTPUTTING || outputState == WeaponOutputState.HOLDING
}

internal class WeaponTracker {
    private val history = IdentityHashMap<WeaponAPI, WeaponState>()
    val trackedWeaponCount: Int get() = history.size
    val states: Collection<WeaponState> get() = history.values

    fun sample(weapons: List<WeaponAPI>, elapsedLocalSec: Float = 0f): List<WeaponState> {
        require(elapsedLocalSec >= 0f)
        val present = IdentityHashMap<WeaponAPI, Boolean>()
        val states = weapons.map { weapon ->
            present[weapon] = true
            val snapshot = WeaponSnapshot.from(weapon)
            observe(snapshot, history[weapon], elapsedLocalSec)
        }
        history.keys.retainAll(present.keys)
        states.forEach { history[it.snapshot.weapon] = it }
        return states
    }

    private fun observe(w: WeaponSnapshot, previous: WeaponState?, elapsed: Float): WeaponState {
        val output = WeaponCost.outputState(w, previous)
        val active = output == WeaponOutputState.CHARGING || output == WeaponOutputState.OUTPUTTING || output == WeaponOutputState.HOLDING
        val ammoRelease = previous != null && w.usesAmmo && w.ammo < previous.snapshot.ammo
        val chargeRelease = previous?.active == true && previous.snapshot.chargeLevel > 0.001f &&
            w.chargeLevel <= 0.001f && w.cooldownRemaining > 0f
        val cooldownRelease = previous != null && (previous.active || previous.snapshot.isFiring) &&
            previous.snapshot.cooldownRemaining <= 0f && w.cooldownRemaining > 0f
        val releaseObserved = !w.isBeam && (ammoRelease || cooldownRelease || !w.usesAmmo && chargeRelease)
        val releaseTarget = if (releaseObserved) PhaseTargeting.fireTarget(w.weapon) else null
        val pulseShotObserved = w.isPulse && releaseObserved
        val fresh = active && (previous == null || !previous.active || pulseShotObserved ||
            (w.isInBurst && previous.snapshot.isInBurst && w.burstFireTimeRemaining > previous.snapshot.burstFireTimeRemaining + 0.0001f))
        var uncertainties = w.uncertainties
        if (w.isPulse && active && w.chargeTime > 0f &&
            ((output == WeaponOutputState.CHARGING && w.chargeLevel <= 0.1f / w.chargeTime) || w.chargeTime == 0.1f)
        ) uncertainties += WeaponCostUncertainty.PULSE_CHARGE_FLOOR
        val remaining = when {
            !active || w.isBeam -> 0
            w.isPulse -> 1
            !w.isInBurst -> w.burstSize
            w.burstDelay > 0f -> {
                val shots = w.burstFireTimeRemaining / w.burstDelay
                if (shots.isFinite() && shots >= 1f - 0.001f && shots <= w.burstSize + 0.001f &&
                    abs(shots - shots.roundToInt()) < 0.001f
                ) {
                    shots.roundToInt().coerceIn(0, w.burstSize)
                } else {
                    uncertainties += WeaponCostUncertainty.UNOBSERVED_SHOTS
                    w.burstSize
                }
            }
            else -> {
                val observable = previous != null && w.usesAmmo && w.ammoPerSecond == 0f &&
                    WeaponCostUncertainty.BARREL_MODE !in w.uncertainties &&
                    (elapsed == 0f || elapsed < w.chargeTime + w.cooldown) && w.ammo <= previous.snapshot.ammo
                if (observable) {
                    val consumed = (previous!!.snapshot.ammo - w.ammo) / w.barrels
                    val before = if (previous.snapshot.isInBurst) previous.remainingShots else w.burstSize
                    uncertainties += previous.uncertainties
                    (before - consumed).coerceIn(0, w.burstSize)
                } else {
                    uncertainties += WeaponCostUncertainty.UNOBSERVED_SHOTS
                    w.burstSize
                }
            }
        }
        if (((w.isBeam && !w.isBurstBeam) || w.isPulse) && w.isFiring && w.chargeLevel < 1f &&
            (previous == null || (WeaponCostUncertainty.CHARGE_DIRECTION in previous.uncertainties &&
                w.chargeLevel == previous.snapshot.chargeLevel))
        ) {
            uncertainties += WeaponCostUncertainty.CHARGE_DIRECTION
        }
        return WeaponState(
            w, output, remaining, !w.isPulse && w.isInBurst && !w.isInterruptibleBurst,
            (previous?.volley ?: 0) + if (fresh) 1 else 0, uncertainties, pulseShotObserved, releaseObserved, releaseTarget,
        )
    }
}

internal object WeaponCost {
    fun outputState(w: WeaponSnapshot, previous: WeaponState? = null): WeaponOutputState = when {
        !w.isBeam && w.isInBurst -> WeaponOutputState.OUTPUTTING
        w.cooldownRemaining > 0f -> if (w.isBeam && w.isFiring) WeaponOutputState.FINISHING else WeaponOutputState.COOLDOWN
        !w.isFiring -> WeaponOutputState.COOLDOWN
        w.isPulse -> pulseState(w, previous)
        w.isBurstBeam && w.burstFireTimeRemaining <= 0f -> WeaponOutputState.FINISHING
        w.isBeam && !w.isBurstBeam && previous != null &&
            (w.chargeLevel < previous.snapshot.chargeLevel ||
                (previous.outputState == WeaponOutputState.FINISHING && w.chargeLevel == previous.snapshot.chargeLevel)) -> WeaponOutputState.FINISHING
        w.chargeLevel < 1f -> WeaponOutputState.CHARGING
        w.isBeam -> WeaponOutputState.OUTPUTTING
        else -> WeaponOutputState.COOLDOWN
    }

    fun nextVolleyCost(w: WeaponSnapshot, beamOutputLocalSec: Float): WeaponCostEstimate {
        require(beamOutputLocalSec.isFinite() && beamOutputLocalSec >= 0f)
        if (w.isDecorative || w.isDisabled || (w.usesAmmo && w.ammo <= 0)) return WeaponCostEstimate.ZERO
        if (WeaponCostUncertainty.UNSUPPORTED_WEAPON in w.uncertainties) return unsupported(w)
        val cost = when {
            w.isPulse -> continuousDebit(w, w.chargeTime) + pulseHoldCost(w, 0f) + pulseShotCost(w)
            w.isBurstBeam -> continuousDebit(w, w.chargeupTime + w.burstDuration)
            w.isBeam -> continuousBeamCost(w, w.chargeupTime, beamOutputLocalSec)
            else -> continuousDebit(w, w.chargeTime) + projectileShotCost(w, w.burstSize) + extraShotCost(w, w.burstSize)
        }
        return if (hasAmmoForFullVolley(w)) cost else cost.copy(
            uncertainties = cost.uncertainties + WeaponCostUncertainty.PARTIAL_AMMO_VOLLEY,
        )
    }

    fun continueCost(state: WeaponState, beamOutputLocalSec: Float): WeaponCostEstimate {
        require(beamOutputLocalSec.isFinite() && beamOutputLocalSec >= 0f)
        val w = state.snapshot
        if (!state.active || w.isDecorative) return WeaponCostEstimate.ZERO
        if (WeaponCostUncertainty.UNSUPPORTED_WEAPON in w.uncertainties) return unsupported(w)
        val cost = when {
            w.isPulse -> pulseReleaseCost(state, 0f)
            w.isBurstBeam -> continuousDebit(w, w.burstFireTimeRemaining.coerceIn(0f, w.chargeupTime + w.burstDuration))
            w.isBeam -> continuousBeamCost(w, w.chargeupTime * (1f - w.chargeLevel), beamOutputLocalSec)
            state.prepaid -> extraShotCost(w, state.remainingShots)
            else -> {
                val charge = if (state.outputState == WeaponOutputState.CHARGING) w.chargeTime * (1f - w.chargeLevel) else 0f
                continuousDebit(w, charge) + projectileShotCost(w, state.remainingShots) + extraShotCost(w, state.remainingShots)
            }
        }
        return cost.copy(uncertainties = cost.uncertainties + state.uncertainties)
    }

    fun interruptNowCost(state: WeaponState): WeaponCostEstimate {
        val w = state.snapshot
        if (!state.active || w.isDecorative) return WeaponCostEstimate.ZERO
        if (WeaponCostUncertainty.UNSUPPORTED_WEAPON in w.uncertainties) return unsupported(w)
        if (w.isPulse) {
            if (w.pulseRequiresFullCharge && state.outputState != WeaponOutputState.HOLDING) return WeaponCostEstimate.ZERO
            val timing = if (state.outputState != WeaponOutputState.HOLDING || w.chargeTime < 0.1f) {
                setOf(WeaponCostUncertainty.UNOBSERVED_SHOTS)
            } else emptySet()
            return pulseShotCost(w).copy(uncertainties = state.uncertainties + timing)
        }
        if (!w.isBurstBeam) return WeaponCostEstimate.ZERO
        val duration = w.burstFireTimeRemaining.coerceIn(0f, w.chargeupTime + w.burstDuration)
        return WeaponCostEstimate(debit(w, w.fluxPerSecond * duration), state.uncertainties)
    }

    fun pulseReleaseCost(state: WeaponState, holdLocalSec: Float): WeaponCostEstimate {
        val w = state.snapshot
        require(w.isPulse && holdLocalSec.isFinite() && holdLocalSec >= 0f)
        if (!state.active || w.isDecorative) return WeaponCostEstimate.ZERO
        val chargeRemaining = when {
            WeaponCostUncertainty.PULSE_CHARGE_FLOOR in state.uncertainties -> w.chargeTime
            state.outputState == WeaponOutputState.HOLDING -> 0f
            else -> w.chargeTime * (1f - w.chargeLevel.coerceIn(0f, 1f))
        }
        val cost = continuousDebit(w, chargeRemaining) + pulseHoldCost(w, holdLocalSec) + pulseShotCost(w)
        return cost.copy(uncertainties = cost.uncertainties + state.uncertainties)
    }

    private fun pulseHoldCost(w: WeaponSnapshot, holdLocalSec: Float): WeaponCostEstimate {
        val minimumHold = (0.1f - w.chargeTime).coerceAtLeast(0f)
        val cost = continuousDebit(w.copy(fluxPerSecond = w.fluxPerSecond * 0.5f), max(holdLocalSec, minimumHold))
        return if (minimumHold > holdLocalSec) cost.copy(uncertainties = cost.uncertainties + WeaponCostUncertainty.UNOBSERVED_SHOTS) else cost
    }

    private fun pulseShotCost(w: WeaponSnapshot) = WeaponCostEstimate(debit(w, w.energyPerShot), w.uncertainties)

    private fun pulseState(w: WeaponSnapshot, previous: WeaponState?): WeaponOutputState = when {
        w.chargeLevel == 1f -> WeaponOutputState.HOLDING
        w.chargeTime in 0f..<0.1f && w.chargeLevel < 1f -> WeaponOutputState.FINISHING
        previous != null && w.usesAmmo && w.ammo < previous.snapshot.ammo -> WeaponOutputState.CHARGING
        previous != null && (w.chargeLevel < previous.snapshot.chargeLevel ||
            (previous.outputState == WeaponOutputState.FINISHING && w.chargeLevel == previous.snapshot.chargeLevel)) -> WeaponOutputState.FINISHING
        else -> WeaponOutputState.CHARGING
    }

    fun continuousBeamCost(w: WeaponSnapshot, chargeLocalSec: Float, outputLocalSec: Float): WeaponCostEstimate {
        require(w.isBeam && !w.isBurstBeam)
        require(chargeLocalSec.isFinite() && chargeLocalSec >= 0f && outputLocalSec.isFinite() && outputLocalSec >= 0f)
        return continuousDebit(w, chargeLocalSec + outputLocalSec)
    }

    fun projectileShotCost(w: WeaponSnapshot, shots: Int): WeaponCostEstimate {
        if (shots <= 0) return WeaponCostEstimate.ZERO
        val raw = w.energyPerShot * w.barrels
        val cost = if (w.isInterruptibleBurst) debit(w, raw) * shots else debit(w, raw * shots)
        return WeaponCostEstimate(cost, w.uncertainties)
    }

    fun hasAmmoForFullVolley(w: WeaponSnapshot): Boolean = !w.usesAmmo ||
        w.ammo >= if (w.isBeam || w.isPulse) 1 else w.burstSize * w.barrels

    private fun continuousDebit(w: WeaponSnapshot, duration: Float): WeaponCostEstimate {
        if (duration <= 0f) return WeaponCostEstimate.ZERO
        if (w.fluxFlat > 0f) {
            return WeaponCostEstimate(Float.POSITIVE_INFINITY, w.uncertainties + WeaponCostUncertainty.FRAME_DEPENDENT_FLUX)
        }
        val uncertainty = if (w.fluxFlat < 0f) setOf(WeaponCostUncertainty.FRAME_DEPENDENT_FLUX) else emptySet()
        return WeaponCostEstimate((w.fluxPerSecond * w.fluxMultiplier * duration).coerceAtLeast(0f), w.uncertainties + uncertainty)
    }

    private fun debit(w: WeaponSnapshot, raw: Float): Float = (raw * w.fluxMultiplier + w.fluxFlat).coerceAtLeast(0f)

    private fun extraShotCost(w: WeaponSnapshot, remaining: Int): WeaponCostEstimate =
        if (w.id == "astd_starfall_echo" && remaining > 0) WeaponCostEstimate(StarfallEchoTuning.FINAL_FLUX_EXTRA)
        else WeaponCostEstimate.ZERO

    private fun unsupported(w: WeaponSnapshot) = WeaponCostEstimate(Float.POSITIVE_INFINITY, w.uncertainties)
}
