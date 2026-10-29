package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDanger
import cn.kasuminova.astd.combat.shipsystems.base.OwnVentCalc
import cn.kasuminova.astd.combat.shipsystems.base.PhaseEvent
import cn.kasuminova.astd.combat.shipsystems.base.PhaseInputSnapshot
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoverMode
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecovery
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryExecution
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSimResult
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSimulator
import cn.kasuminova.astd.combat.shipsystems.base.PhaseState
import cn.kasuminova.astd.combat.shipsystems.base.PhaseTargeting
import cn.kasuminova.astd.combat.shipsystems.base.WeaponCost
import cn.kasuminova.astd.combat.shipsystems.base.WeaponCostEstimate
import cn.kasuminova.astd.combat.shipsystems.base.WeaponCostUncertainty
import cn.kasuminova.astd.combat.shipsystems.base.WeaponTracker
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.impl.hullmods.PhaseAnchor
import kotlin.math.max
import kotlin.math.min

internal enum class RecoveredAttackState { NONE, WAITING, STARTED, CANCELLED, EXPIRED }

internal enum class RecoveredAttackReason {
    NONE, READY, COOLDOWN_PENDING, OUT_OF_ARC, TARGET_PHASED, WEAPON_DISABLED,
    ATTACK_STARTED, TIME_LIMIT, TARGET_INVALID, WEAPON_INVALID, SYSTEM_INVALID,
    INSUFFICIENT_AMMO, INSUFFICIENT_BUDGET, RETREAT, PHASE_REENTERED, SHIP_UNAVAILABLE, EXECUTION_CANCELLED,
}

internal data class RecoveredAttack(
    val state: RecoveredAttackState = RecoveredAttackState.NONE,
    val reason: RecoveredAttackReason = RecoveredAttackReason.NONE,
    val target: ShipAPI? = null,
    val weapons: List<WeaponAPI> = emptyList(),
    val pendingWeapons: List<WeaponAPI> = emptyList(),
    val elapsedLocal: Float = 0f,
    val canAttack: Boolean = false,
    val budgetSufficient: Boolean = false,
    val fullAmmo: Boolean = false,
    val attackCost: Float = 0f,
)

internal data class PhaseRecoveryAssessment(
    val recovery: PhaseRecovery,
    val remainingBurstCost: Float,
    val activeBurstCost: Float,
    val costUncertainties: Set<WeaponCostUncertainty> = emptySet(),
    val recoveredAttack: RecoveredAttack = RecoveredAttack(),
)

internal class GravityPhaseRecovery(private val beamOutputLocalSec: Float = 2f) {
    private var tracker = WeaponTracker()
    private var trackedShip: ShipAPI? = null
    private var sampledAt: Float? = null
    private var capacityRecovery = false
    private var hardFluxRecovery = false
    val hardFluxRecoveryActive: Boolean get() = hardFluxRecovery

    init {
        require(beamOutputLocalSec.isFinite() && beamOutputLocalSec >= 0f)
    }

    private data class Commitment(
        val target: ShipAPI?,
        val weapons: List<WeaponAPI>,
        val system: ShipSystemAPI?,
        var entered: Boolean = false,
    )

    private var commitment: Commitment? = null
    private var evaluatedCommitment: Commitment? = null
    var execution = PhaseRecoveryExecution()
        private set

    fun committedTarget(ship: ShipAPI): ShipAPI? = commitment?.target?.takeIf { PhaseTargeting.isValidEnemy(ship, it) }
    val canCommit: Boolean get() = commitment == null

    fun effectiveOutput(target: ShipAPI): Boolean = PhaseTargeting.effectiveOutput(target, tracker.states)

    fun protectingBurst(target: ShipAPI?): Boolean = target != null && tracker.states.any {
        val weapon = it.snapshot.weapon
        it.active && PhaseTargeting.attackWeapon(weapon) &&
            (!it.snapshot.isBeam || it.snapshot.isBurstBeam) &&
            (it.releaseTarget === target || PhaseTargeting.fireTarget(weapon) === target) &&
            PhaseTargeting.inRangeAndArc(weapon, target)
    }

    var recoveredAttack = RecoveredAttack()
        private set

    fun commit(ship: ShipAPI, cloak: ShipSystemAPI, target: ShipAPI?, mode: PhaseRecoverMode) {
        check(commitment == null)
        check(mode == PhaseRecoverMode.PHASE_FLUX || mode == PhaseRecoverMode.PHASE_COOLDOWN)
        val evaluated = evaluatedCommitment?.takeIf { trackedShip === ship && it.target === target }
        val weapons = evaluated?.weapons ?: attackWeapons(ship, target)
        check(weapons.isNotEmpty())
        commitment = evaluated ?: Commitment(target, weapons, coolingSystem(ship, cloak))
        evaluatedCommitment = null
        recoveredAttack = RecoveredAttack()
        execution = PhaseRecoveryExecution(
            PhaseRecoveryState.RUNNING, PhaseRecoveryReason.COMMITTED, mode, target?.id.orEmpty(),
        )
        updateObjects(commitment!!)
    }

    fun advance(ship: ShipAPI, cloak: ShipSystemAPI, localAmount: Float) {
        require(localAmount.isFinite() && localAmount >= 0f)
        val ordinary = cloak.state == ShipSystemAPI.SystemState.IDLE || cloak.state == ShipSystemAPI.SystemState.COOLDOWN
        if (ordinary && execution.awaitingSurface && commitment == null) {
            execution = execution.copy(awaitingSurface = false)
        }
        val plan = commitment ?: return
        if (execution.awaitingAttack) {
            recoveredAttack = recoveredAttack.copy(elapsedLocal = recoveredAttack.elapsedLocal + localAmount)
            when {
                ship.isRetreating -> finishAttack(RecoveredAttackState.CANCELLED, RecoveredAttackReason.RETREAT)
                !ordinary -> finishAttack(RecoveredAttackState.CANCELLED, RecoveredAttackReason.PHASE_REENTERED)
                recoveredAttack.elapsedLocal >= 3f -> finishAttack(RecoveredAttackState.EXPIRED, RecoveredAttackReason.TIME_LIMIT)
            }
            return
        }
        execution = execution.copy(elapsedLocal = execution.elapsedLocal + localAmount)
        updateObjects(plan)
        if (ship.isRetreating) {
            cancel(PhaseRecoveryReason.RETREAT)
            return
        }
        when (cloak.state) {
            ShipSystemAPI.SystemState.IN, ShipSystemAPI.SystemState.ACTIVE -> plan.entered = true
            ShipSystemAPI.SystemState.OUT -> {
                plan.entered = true
                if (execution.continuing) exit(PhaseRecoveryReason.PHASE_ENDED)
            }
            else -> if (plan.entered) {
                val completed = execution.reason == PhaseRecoveryReason.OBJECTIVES_MET
                execution = execution.copy(
                    state = if (completed) PhaseRecoveryState.COMPLETED else PhaseRecoveryState.CANCELLED,
                    reason = if (execution.continuing) PhaseRecoveryReason.PHASE_ENDED else execution.reason,
                    awaitingSurface = false,
                    awaitingAttack = completed,
                )
                if (completed) {
                    recoveredAttack = RecoveredAttack(
                        state = RecoveredAttackState.WAITING, target = plan.target,
                        weapons = plan.weapons, pendingWeapons = plan.weapons,
                    )
                } else {
                    commitment = null
                }
            } else if (execution.elapsedLocal >= 1f) {
                cancel(PhaseRecoveryReason.ACTIVATION_UNCONFIRMED)
            }
        }
    }

    private fun finishAttack(state: RecoveredAttackState, reason: RecoveredAttackReason) {
        recoveredAttack = recoveredAttack.copy(state = state, reason = reason, canAttack = false)
        execution = execution.copy(awaitingAttack = false)
        commitment = null
    }

    private fun attackWeapons(ship: ShipAPI, target: ShipAPI?): List<WeaponAPI> {
        if (!PhaseTargeting.isValidEnemy(ship, target)) return emptyList()
        return ship.allWeapons.filter { weapon ->
            PhaseTargeting.attackWeapon(weapon) && !weapon.isForceDisabled &&
                reloadRemaining(weapon).isFinite() && PhaseTargeting.inRangeAndArc(weapon, target!!)
        }
    }

    fun exit(reason: PhaseRecoveryReason) {
        if (commitment != null && execution.continuing) {
            execution = execution.copy(state = PhaseRecoveryState.EXITING, reason = reason, awaitingSurface = true)
        }
    }

    fun cancel(reason: PhaseRecoveryReason) {
        val plan = commitment ?: return
        val handoff = execution.awaitingAttack
        execution = execution.copy(
            state = PhaseRecoveryState.CANCELLED, reason = reason,
            awaitingSurface = plan.entered && !handoff && reason != PhaseRecoveryReason.RETREAT,
            awaitingAttack = false,
        )
        if (handoff) finishAttack(RecoveredAttackState.CANCELLED, when (reason) {
            PhaseRecoveryReason.RETREAT -> RecoveredAttackReason.RETREAT
            PhaseRecoveryReason.TARGET_INVALID -> RecoveredAttackReason.TARGET_INVALID
            PhaseRecoveryReason.WEAPON_INVALID -> RecoveredAttackReason.WEAPON_INVALID
            PhaseRecoveryReason.SYSTEM_INVALID -> RecoveredAttackReason.SYSTEM_INVALID
            else -> RecoveredAttackReason.EXECUTION_CANCELLED
        })
        commitment = null
    }

    private fun validateObjects(ship: ShipAPI) {
        val plan = commitment ?: return
        val reason = when {
            ship.isRetreating -> PhaseRecoveryReason.RETREAT
            !PhaseTargeting.isValidEnemy(ship, plan.target) -> PhaseRecoveryReason.TARGET_INVALID
            plan.weapons.any { weapon -> ship.allWeapons.none { it === weapon } || weapon.isPermanentlyDisabled } ->
                PhaseRecoveryReason.WEAPON_INVALID
            plan.system != null && ship.system !== plan.system -> PhaseRecoveryReason.SYSTEM_INVALID
            else -> return
        }
        if (execution.awaitingAttack) {
            finishAttack(RecoveredAttackState.CANCELLED, when (reason) {
                PhaseRecoveryReason.RETREAT -> RecoveredAttackReason.RETREAT
                PhaseRecoveryReason.TARGET_INVALID -> RecoveredAttackReason.TARGET_INVALID
                PhaseRecoveryReason.SYSTEM_INVALID -> RecoveredAttackReason.SYSTEM_INVALID
                else -> RecoveredAttackReason.WEAPON_INVALID
            })
        } else cancel(reason)
    }

    private fun refreshFailedAttack(ship: ShipAPI, target: ShipAPI?, previouslyActive: List<WeaponAPI>) {
        if (recoveredAttack.state != RecoveredAttackState.CANCELLED && recoveredAttack.state != RecoveredAttackState.EXPIRED) return
        val original = recoveredAttack.target ?: return
        if ((target != null && target !== original) || !PhaseTargeting.isValidEnemy(ship, original)) {
            recoveredAttack = RecoveredAttack()
            return
        }
        val pending = recoveredAttack.pendingWeapons.filter { weapon ->
            val firingEvidence = weapon.isBeam || weapon.isFiring || weapon.isInBurst ||
                weapon.cooldownRemaining > 0f || previouslyActive.any { it === weapon }
            ship.allWeapons.any { it === weapon } && !weapon.isPermanentlyDisabled &&
                (original.isPhased || !firingEvidence || tracker.states.none {
                    it.snapshot.weapon === weapon && PhaseTargeting.effectiveOutput(original, listOf(it))
                })
        }
        recoveredAttack = if (pending.isEmpty()) RecoveredAttack() else recoveredAttack.copy(pendingWeapons = pending)
    }

    private fun observeRecoveredAttack(previouslyActive: List<WeaponAPI>) {
        val plan = commitment ?: return
        val target = plan.target ?: return
        if (!execution.awaitingAttack || target.isPhased) return
        val pending = recoveredAttack.pendingWeapons.filter { weapon ->
            val state = tracker.states.firstOrNull { it.snapshot.weapon === weapon }
            val firingEvidence = weapon.isBeam || weapon.isFiring || weapon.isInBurst ||
                weapon.cooldownRemaining > 0f || previouslyActive.any { it === weapon }
            state == null || !firingEvidence || !PhaseTargeting.effectiveOutput(target, listOf(state))
        }
        recoveredAttack = recoveredAttack.copy(pendingWeapons = pending)
        if (pending.isEmpty()) finishAttack(RecoveredAttackState.STARTED, RecoveredAttackReason.ATTACK_STARTED)
    }

    private fun assessRecoveredAttack(ship: ShipAPI, cloak: ShipSystemAPI, cost: Float, safeLimit: Float) {
        val plan = commitment ?: return
        if (!execution.awaitingAttack) return
        val pending = recoveredAttack.pendingWeapons
        val fullAmmo = pending.all { weapon ->
            WeaponCost.hasAmmoForFullVolley(cn.kasuminova.astd.combat.shipsystems.base.WeaponSnapshot.from(weapon))
        }
        val affordable = cost.isFinite() && ship.fluxTracker.currFlux + cost <= safeLimit &&
            ship.fluxTracker.hardFlux < ship.fluxTracker.maxFlux * 0.6f
        val ordinary = cloak.state == ShipSystemAPI.SystemState.IDLE || cloak.state == ShipSystemAPI.SystemState.COOLDOWN
        val ready = pending.all { it.cooldownRemaining <= 0f && !it.isDisabled && !it.isForceDisabled }
        val inArc = pending.all { PhaseTargeting.inRangeAndArc(it, plan.target!!) }
        val reason = when {
            !ordinary -> RecoveredAttackReason.PHASE_REENTERED
            ship.fluxTracker.isOverloaded || ship.fluxTracker.isVenting -> RecoveredAttackReason.SHIP_UNAVAILABLE
            !fullAmmo -> RecoveredAttackReason.INSUFFICIENT_AMMO
            !affordable -> RecoveredAttackReason.INSUFFICIENT_BUDGET
            pending.any { it.isDisabled || it.isForceDisabled } -> RecoveredAttackReason.WEAPON_DISABLED
            plan.target!!.isPhased -> RecoveredAttackReason.TARGET_PHASED
            !ready -> RecoveredAttackReason.COOLDOWN_PENDING
            !inArc -> RecoveredAttackReason.OUT_OF_ARC
            else -> RecoveredAttackReason.READY
        }
        recoveredAttack = recoveredAttack.copy(
            reason = reason, fullAmmo = fullAmmo, budgetSufficient = affordable, attackCost = cost,
            canAttack = reason == RecoveredAttackReason.READY,
        )
        if (!ordinary || !fullAmmo || !affordable || ship.fluxTracker.isOverloaded || ship.fluxTracker.isVenting) {
            finishAttack(RecoveredAttackState.CANCELLED, reason)
        }
    }

    private fun updateObjects(plan: Commitment) {
        val weaponRemaining = plan.weapons.maxOfOrNull { max(it.cooldownRemaining, reloadRemaining(it)) } ?: 0f
        val systemRemaining = plan.system?.takeIf { it.state == ShipSystemAPI.SystemState.COOLDOWN }
            ?.cooldownRemaining ?: 0f
        execution = execution.copy(
            cooldownRemaining = max(weaponRemaining, systemRemaining),
            objects = (plan.weapons.mapIndexed { index, weapon ->
                "$index:${weapon.id}=${weapon.cooldownRemaining},reload=${reloadRemaining(weapon)}"
            } + listOfNotNull(plan.system?.let { "system=$systemRemaining" })).joinToString(","),
        )
    }

    fun evaluate(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        cloak: ShipSystemAPI,
        target: ShipAPI?,
        danger: PhaseDanger,
    ): PhaseRecoveryAssessment {
        val flux = ship.fluxTracker
        if (trackedShip !== ship) {
            tracker = WeaponTracker()
            trackedShip = ship
            sampledAt = null
            capacityRecovery = false
            hardFluxRecovery = false
        }
        val now = engine.getTotalElapsedTime(false)
        val elapsed = sampledAt?.let { (now - it).coerceAtLeast(0f) * ship.mutableStats.timeMult.modifiedValue } ?: 0f
        val previouslyActive = tracker.states.filter { it.active }.map { it.snapshot.weapon }
        val states = tracker.sample(ship.allWeapons, elapsed)
        sampledAt = now
        refreshFailedAttack(ship, commitment?.target ?: target, previouslyActive)
        validateObjects(ship)
        val attackTarget = commitment?.target ?: target
        val handoff = execution.awaitingAttack
        val weapons = commitment?.weapons ?: attackWeapons(ship, attackTarget)
        observeRecoveredAttack(previouslyActive)
        val nextWeapons = if (handoff) recoveredAttack.pendingWeapons else weapons
        var activeCost = WeaponCostEstimate.ZERO
        var attackCost = WeaponCostEstimate.ZERO
        var interruption = WeaponCostEstimate.ZERO
        var nextAttack = WeaponCostEstimate.ZERO
        for (state in states) {
            val selected = nextWeapons.any { it === state.snapshot.weapon }
            val continuation = WeaponCost.continueCost(state, beamOutputLocalSec)
            val interrupt = WeaponCost.interruptNowCost(state)
            activeCost += continuation
            interruption += if (selected) interrupt else WeaponCostEstimate(
                max(continuation.amount, interrupt.amount), continuation.uncertainties + interrupt.uncertainties,
            )
            val next = if (selected) {
                val weapon = state.snapshot.weapon
                val snapshot = if (state.snapshot.usesAmmo && state.snapshot.ammo <= 0 && reloadRemaining(weapon).isFinite()) {
                    state.snapshot.copy(ammo = requiredAmmo(weapon))
                } else state.snapshot
                WeaponCost.nextVolleyCost(snapshot, beamOutputLocalSec)
            } else WeaponCostEstimate.ZERO
            nextAttack += next
            attackCost += if (state.active) continuation else next
        }
        val activeBurstCost = activeCost.amount
        val qBurst = attackCost.amount
        var uncertainties = attackCost.uncertainties + interruption.uncertainties + nextAttack.uncertainties
        val phaseTimeMult = PhaseCloakStats.getMaxTimeMult(ship.mutableStats).coerceAtLeast(1f)
        val unphasedTimeMult = ship.mutableStats.timeMult.modifiedValue /
            (1f + (phaseTimeMult - 1f) * cloak.effectLevel)
        val timeMult = phaseTimeMult * unphasedTimeMult
        val emergencyCost = cloak.fluxPerUse + cloak.fluxPerSecond * 1.5f
        val reserve = emergencyCost + flux.maxFlux * 0.05f
        val safeLimit = (flux.maxFlux - reserve).coerceAtLeast(0f)
        val pathCost = max(qBurst, interruption.amount)
        val capacityPlan = !pathCost.isFinite() || pathCost > safeLimit
        capacityRecovery = when {
            !capacityPlan || flux.currFlux <= flux.maxFlux * 0.05f -> false
            flux.currFlux >= flux.maxFlux * 0.1f -> true
            else -> capacityRecovery
        }
        val gap = when {
            capacityPlan && capacityRecovery -> {
                uncertainties += WeaponCostUncertainty.CAPACITY_LIMITED_PLAN
                (flux.currFlux - min(safeLimit, flux.maxFlux * 0.05f)).coerceAtLeast(0f)
            }
            capacityPlan -> 0f
            else -> (flux.currFlux + pathCost - safeLimit).coerceAtLeast(0f)
        }
        assessRecoveredAttack(ship, cloak, qBurst, safeLimit)
        val system = if (commitment != null) commitment!!.system else if (handoff) null else coolingSystem(ship, cloak)
        val cooldowns = cooldowns(ship, nextWeapons, system)
        val cooldown = cooldowns.maxOfOrNull { it.naturalRemaining() } ?: 0f
        hardFluxRecovery = when {
            flux.hardFlux >= flux.maxFlux * 0.55f -> true
            flux.hardFlux <= flux.maxFlux * 0.45f -> false
            else -> hardFluxRecovery
        }
        val highHard = hardFluxRecovery
        val cooldownOpportunity = weapons.isNotEmpty() && attackTarget != null && cooldown > 0f &&
            !danger.significantNear && !PhaseTargeting.effectiveOutput(
                attackTarget, states.filter { it.active || it.snapshot.isBeam },
            )
        val required = !execution.awaitingAttack && (gap > 0f || highHard || cooldownOpportunity)
        val dissipation = ship.mutableStats.fluxDissipation
        val anchorMod = dissipation.getMultStatMod("phase_anchor_modifier")
        val naturalDissipation = if (anchorMod != null) {
            dissipation.createCopy().apply { unmodifyMult("phase_anchor_modifier") }.modifiedValue
        } else dissipation.modifiedValue
        val phaseDissipation = naturalDissipation * if (ship.variant?.hasHullMod("phase_anchor") == true) {
            PhaseAnchor.PHASE_DISSIPATION_MULT
        } else 1f
        val input = PhaseInputSnapshot(
            currFlux = flux.currFlux + interruption.amount,
            hardFlux = flux.hardFlux,
            maxFlux = flux.maxFlux,
            softFlux = (flux.currFlux - flux.hardFlux).coerceAtLeast(0f) + interruption.amount,
            naturalDissipationPerSec = phaseDissipation,
            timeMult = timeMult,
            unphasedTimeMult = unphasedTimeMult,
            activationCost = if (cloak.state == ShipSystemAPI.SystemState.ACTIVE ||
                cloak.state == ShipSystemAPI.SystemState.IN) 0f else cloak.fluxPerUse,
            maintenanceCostPerSec = cloak.fluxPerSecond,
            phaseState = phaseState(cloak),
            cooldownRemaining = cloak.cooldownRemaining,
            qBurst = nextAttack.amount,
            fSafe = reserve,
            effectLevel = cloak.effectLevel,
            transitionRemainingLocalSec = when (cloak.state) {
                ShipSystemAPI.SystemState.IN -> cloak.chargeUpDur * (1f - cloak.effectLevel).coerceAtLeast(0f)
                ShipSystemAPI.SystemState.OUT -> cloak.chargeDownDur * cloak.effectLevel.coerceIn(0f, 1f)
                else -> 0f
            },
            phaseCooldownDurationLocalSec = cloak.cooldown,
            canExitPhase = true,
        )
        validateExecution(ship, cloak, danger, input, naturalDissipation, safeLimit)
        val surfaceInput = input.copy(
            currFlux = flux.currFlux, softFlux = (flux.currFlux - flux.hardFlux).coerceAtLeast(0f),
        )
        val surfaced = exitSimulation(surfaceInput, cloak, naturalDissipation)
        val endFlux = surfaced.hardFluxAtEnd + surfaced.softFluxAtEnd
        val fluxTarget = when {
            highHard -> min(flux.maxFlux * 0.45f, (flux.currFlux - gap).coerceAtLeast(0f))
            else -> (flux.currFlux - gap).coerceAtLeast(0f)
        }
        val remainingGap = (endFlux - fluxTarget).coerceAtLeast(0f)
        val fluxDuration = when {
            remainingGap == 0f -> 0f
            naturalDissipation > 0f -> remainingGap / naturalDissipation
            else -> Float.POSITIVE_INFINITY
        }
        val naturalCooldown = cooldowns.maxOfOrNull { it.naturalRemaining(outLocal = surfaced.localDurationSec) } ?: 0f
        val naturalDuration = if (surfaced.accepted) surfaced.battleDurationSec +
            max(fluxDuration, naturalCooldown) / unphasedTimeMult else Float.POSITIVE_INFINITY
        val ventRate = 2f * naturalDissipation * ship.mutableStats.ventRateMult.modifiedValue
        val ventDuration = if (surfaced.accepted && ventRate > 0f) {
            val ventLocal = OwnVentCalc.newVentLocalSec(
                flux, endFlux, ship.mutableStats.fluxDissipation.modifiedValue,
                naturalDissipation, ship.mutableStats.ventRateMult.modifiedValue,
            )
            val ventCooldown = cooldowns.maxOfOrNull {
                val remaining = it.naturalRemaining(outLocal = surfaced.localDurationSec)
                val pause = if (it.pausesOnVent && remaining > 0f && ventLocal > 0f) {
                    min(ventLocal, com.fs.starfarer.api.Global.getSettings().getFloat("minVentTimeForWeaponReload"))
                } else 0f
                remaining + pause
            } ?: 0f
            surfaced.battleDurationSec + max(ventLocal, ventCooldown) / unphasedTimeMult
        } else Float.POSITIVE_INFINITY
        val requiredWindow = ventDuration + attackFlightTime(ship, attackTarget, weapons)
        val ventSafe = !danger.significantNear && engine.ships
            .filter { PhaseTargeting.isValidEnemy(ship, it) && PhaseTargeting.targetCoverage(ship, it) > 0 }
            .all { PhaseTargeting.enemyWindow(it) > requiredWindow }
        val canPhase = cloak.state == ShipSystemAPI.SystemState.IDLE ||
            cloak.state == ShipSystemAPI.SystemState.IN || cloak.state == ShipSystemAPI.SystemState.ACTIVE
        val unfulfilledAttack = recoveredAttack.target === attackTarget &&
            (recoveredAttack.state == RecoveredAttackState.CANCELLED || recoveredAttack.state == RecoveredAttackState.EXPIRED)
        val phase = if (commitment == null && required && !unfulfilledAttack && weapons.isNotEmpty() && canPhase &&
            !danger.significantNear && input.currFlux.isFinite() && input.qBurst.isFinite() &&
            (input.phaseState != PhaseState.IDLE || input.currFlux <= safeLimit)
        ) {
            val phaseGap = (input.currFlux + input.qBurst - safeLimit).coerceAtLeast(0f)
            bestPhase(input, phaseGap, cooldownOpportunity, cooldowns, safeLimit, cloak, naturalDissipation)?.let {
                it.copy(cost = it.cost + interruption.amount)
            }
        } else null
        val candidates = buildList {
            add(Choice(ChoiceKind.NATURAL, naturalDuration, 0f))
            if (ventSafe && (gap > 0f || highHard)) add(Choice(ChoiceKind.VENT, ventDuration, 0f))
            phase?.let { add(it) }
        }
        val best = candidates.minBy { it.duration }
        val recovery = PhaseRecovery(
            required = required,
            mode = if (!required || commitment != null) PhaseRecoverMode.NONE else when (best.kind) {
                ChoiceKind.NATURAL -> PhaseRecoverMode.NATURAL
                ChoiceKind.VENT -> PhaseRecoverMode.VENT
                ChoiceKind.PHASE_FLUX -> PhaseRecoverMode.PHASE_FLUX
                ChoiceKind.PHASE_COOLDOWN -> PhaseRecoverMode.PHASE_COOLDOWN
            },
            ventDuration = ventDuration,
            ventSafe = ventSafe,
            naturalDuration = naturalDuration,
            phaseFluxDuration = if (best.kind == ChoiceKind.PHASE_FLUX) best.duration * timeMult else 0f,
            phaseCooldownDuration = if (best.kind == ChoiceKind.PHASE_COOLDOWN) best.duration * timeMult else 0f,
            plannedPhaseCost = if (phase == best) best.cost else 0f,
            phaseCandidateDuration = phase?.duration ?: Float.POSITIVE_INFINITY,
            phaseCandidateReason = when {
                execution.awaitingAttack -> "ATTACK_HANDOFF"
                commitment != null -> "ALREADY_COMMITTED"
                unfulfilledAttack -> "UNFULFILLED_ATTACK"
                !required -> "NOT_REQUIRED"
                !canPhase -> "CLOAK_UNAVAILABLE"
                danger.significantNear -> "THREAT"
                phase == null -> "NO_SAFE_COMPLETE_ATTACK"
                else -> "ACCEPTED"
            },
            execution = execution,
        )
        evaluatedCommitment = if (commitment == null && required && phase != null) {
            Commitment(attackTarget, weapons, system)
        } else null
        return PhaseRecoveryAssessment(recovery, qBurst, activeBurstCost, uncertainties, recoveredAttack)
    }

    private fun validateExecution(
        ship: ShipAPI,
        cloak: ShipSystemAPI,
        danger: PhaseDanger,
        input: PhaseInputSnapshot,
        naturalDissipation: Float,
        safeLimit: Float,
    ) {
        val plan = commitment ?: return
        updateObjects(plan)
        if (!execution.continuing) return
        if (plan.weapons.any { it.isDisabled || it.isForceDisabled || !reloadRemaining(it).isFinite() }) {
            cancel(PhaseRecoveryReason.WEAPON_INVALID)
            return
        }
        if (execution.elapsedLocal >= 12f) {
            exit(PhaseRecoveryReason.TIME_LIMIT)
            return
        }
        if (input.hardFlux >= input.maxFlux * 0.6f) {
            exit(PhaseRecoveryReason.HARD_FLUX_LIMIT)
            return
        }
        if (!plan.entered) return
        val exit = exitSimulation(input, cloak, naturalDissipation)
        val remainingCooldowns = cooldowns(ship, plan.weapons, plan.system)
        val cooledOnExit = remainingCooldowns.all { it.naturalRemaining(outLocal = exit.localDurationSec) <= 0f }
        if (cooledOnExit && attackAffordable(exit, input, safeLimit)) {
            if (danger.lethalSoon || danger.significantNear) {
                execution = execution.copy(state = PhaseRecoveryState.PAUSED, reason = PhaseRecoveryReason.EVADE)
            } else {
                exit(PhaseRecoveryReason.OBJECTIVES_MET)
            }
            return
        }
        val continuation = bestPhase(
            input, (input.currFlux + input.qBurst - safeLimit).coerceAtLeast(0f),
            execution.cooldownRemaining > 0f, remainingCooldowns, safeLimit, cloak, naturalDissipation,
            (12f - execution.elapsedLocal).coerceAtLeast(0f),
        )
        if (continuation == null) {
            exit(PhaseRecoveryReason.UNSAFE_SIMULATION)
            return
        }
        execution = execution.copy(
            state = if (danger.lethalSoon || danger.significantNear) PhaseRecoveryState.PAUSED else PhaseRecoveryState.RUNNING,
            reason = when {
                danger.lethalSoon || danger.significantNear -> PhaseRecoveryReason.EVADE
                execution.cooldownRemaining > 0f -> PhaseRecoveryReason.COOLDOWN_PENDING
                else -> PhaseRecoveryReason.FLUX_PENDING
            },
        )
    }

    private fun bestPhase(
        input: PhaseInputSnapshot,
        gap: Float,
        cooldownOpportunity: Boolean,
        cooldowns: List<Cooldown>,
        safeLimit: Float,
        cloak: ShipSystemAPI,
        naturalDissipation: Float,
        remainingBudget: Float = 12f,
    ): Choice? {
        if (gap <= 0f && !cooldownOpportunity) return null
        val entering = when (input.phaseState) {
            PhaseState.IDLE -> listOf(PhaseEvent.PhaseIn(input.activationCost, cloak.chargeUpDur))
            PhaseState.IN -> listOf(PhaseEvent.ContinueTransition)
            PhaseState.ACTIVE -> emptyList()
            else -> return null
        }
        var active = PhaseSimulator.simulate(input, entering)
        if (!active.accepted || !peakWithinBudget(active, input, safeLimit)) return null
        while (active.localDurationSec + cloak.chargeDownDur <= remainingBudget + 0.0001f) {
            val out = append(input, active, naturalDissipation, listOf(PhaseEvent.PhaseOut(cloak.chargeDownDur)))
            val cooled = cooldowns.all { it.naturalRemaining(active.localDurationSec, cloak.chargeDownDur) <= 0f }
            if (attackAffordable(out, input, safeLimit) && cooled) {
                return Choice(
                    if (cooldownOpportunity) ChoiceKind.PHASE_COOLDOWN else ChoiceKind.PHASE_FLUX,
                    out.battleDurationSec, input.activationCost + input.maintenanceCostPerSec * active.localDurationSec,
                )
            }
            if (!out.accepted || active.hardFluxAtEnd >= planningHardFluxLimit(input)) return null
            val step = min(0.1f, remainingBudget - active.localDurationSec - cloak.chargeDownDur)
            if (step <= 0.0001f) return null
            active = append(input, active, input.naturalDissipationPerSec,
                listOf(PhaseEvent.PhaseActive(step, input.maintenanceCostPerSec)))
            if (!active.accepted || !peakWithinBudget(active, input, safeLimit)) return null
        }
        return null
    }

    private fun attackAffordable(result: PhaseSimResult, input: PhaseInputSnapshot, safeLimit: Float): Boolean =
        result.accepted && peakWithinBudget(result, input, safeLimit) && result.hardFluxAtEnd < planningHardFluxLimit(input) &&
            input.qBurst.isFinite() && result.hardFluxAtEnd + result.softFluxAtEnd + input.qBurst <= safeLimit

    private fun peakWithinBudget(result: PhaseSimResult, input: PhaseInputSnapshot, safeLimit: Float): Boolean =
        result.peakFlux <= safeLimit || input.phaseState != PhaseState.IDLE && input.currFlux > safeLimit &&
            result.peakFlux <= input.maxFlux

    private fun planningHardFluxLimit(input: PhaseInputSnapshot): Float =
        (input.maxFlux * 0.6f - input.maintenanceCostPerSec * 0.2f).coerceAtLeast(0f)

    private fun exitSimulation(input: PhaseInputSnapshot, cloak: ShipSystemAPI, naturalDissipation: Float): PhaseSimResult {
        val events = when (input.phaseState) {
            PhaseState.IN, PhaseState.ACTIVE -> listOf(PhaseEvent.PhaseOut(cloak.chargeDownDur * input.effectLevel))
            PhaseState.OUT -> listOf(PhaseEvent.ContinueTransition)
            else -> emptyList()
        }
        return PhaseSimulator.simulate(input.copy(naturalDissipationPerSec = naturalDissipation), events)
    }

    private fun append(
        input: PhaseInputSnapshot,
        previous: PhaseSimResult,
        dissipation: Float,
        events: List<PhaseEvent>,
    ): PhaseSimResult {
        if (!previous.accepted) return previous
        val next = PhaseSimulator.simulate(input.copy(
            currFlux = previous.hardFluxAtEnd + previous.softFluxAtEnd,
            hardFlux = previous.hardFluxAtEnd, softFlux = previous.softFluxAtEnd,
            phaseState = previous.phaseStateAtEnd, effectLevel = previous.effectLevelAtEnd,
            cooldownRemaining = previous.cooldownRemainingAtEnd, transitionRemainingLocalSec = 0f,
            naturalDissipationPerSec = dissipation,
        ), events)
        return next.copy(
            peakFlux = max(previous.peakFlux, next.peakFlux),
            localDurationSec = previous.localDurationSec + next.localDurationSec,
            battleDurationSec = previous.battleDurationSec + next.battleDurationSec,
            softFluxCleared = previous.softFluxCleared + next.softFluxCleared,
        )
    }

    private fun attackFlightTime(
        ship: ShipAPI,
        target: ShipAPI?,
        weapons: List<com.fs.starfarer.api.combat.WeaponAPI>,
    ): Float {
        if (target == null) return 0f
        val distance = PhaseTargeting.distance(ship.location, target.location)
        return weapons.mapNotNull { weapon ->
            val speed = weapon.projectileSpeed
            if (speed > 0f) distance / speed else null
        }.minOrNull() ?: 0f
    }

    private data class Cooldown(
        val remaining: Float, val naturalRate: Float, val phaseRate: Float, val pausesOnVent: Boolean = true,
    ) {
        fun naturalRemaining(phaseLocal: Float = 0f, outLocal: Float = 0f): Float {
            val left = (remaining - phaseLocal * phaseRate - outLocal * naturalRate).coerceAtLeast(0f)
            return if (left == 0f) 0f else if (naturalRate > 0f) left / naturalRate else Float.POSITIVE_INFINITY
        }
    }

    private fun cooldowns(ship: ShipAPI, weapons: List<WeaponAPI>, system: ShipSystemAPI?): List<Cooldown> {
        val anchored = ship.variant?.hasHullMod("phase_anchor") == true
        return buildList {
            for (weapon in weapons) {
                if (weapon.cooldownRemaining > 0f) {
                    val natural = naturalRoFMult(ship, weapon)
                    val phased = natural * if (anchored) PhaseAnchor.PHASE_DISSIPATION_MULT else 1f
                    add(Cooldown(weapon.cooldownRemaining, cooldownRate(weapon, natural), cooldownRate(weapon, phased)))
                }
                val reload = reloadRemaining(weapon)
                if (reload > 0f) {
                    val natural = naturalAmmoRegenMult(ship, weapon)
                    val phased = natural * if (anchored) PhaseAnchor.PHASE_DISSIPATION_MULT else 1f
                    add(Cooldown(reload, natural, phased))
                }
            }
            system?.takeIf { it.state == ShipSystemAPI.SystemState.COOLDOWN }?.let {
                add(Cooldown(it.cooldownRemaining, 1f, 1f, pausesOnVent = false))
            }
        }
    }

    private fun coolingSystem(ship: ShipAPI, cloak: ShipSystemAPI): ShipSystemAPI? = ship.system?.takeIf {
        it !== cloak && it.state == ShipSystemAPI.SystemState.COOLDOWN &&
            ship.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)
    }

    private fun phaseState(cloak: ShipSystemAPI): PhaseState = when (cloak.state) {
        ShipSystemAPI.SystemState.IDLE -> PhaseState.IDLE
        ShipSystemAPI.SystemState.IN -> PhaseState.IN
        ShipSystemAPI.SystemState.ACTIVE -> PhaseState.ACTIVE
        ShipSystemAPI.SystemState.OUT -> PhaseState.OUT
        ShipSystemAPI.SystemState.COOLDOWN -> PhaseState.COOLDOWN
    }

    companion object {
        fun naturalCooldownRate(ship: ShipAPI, weapon: WeaponAPI): Float =
            cooldownRate(weapon, naturalRoFMult(ship, weapon))

        private fun naturalRoFMult(ship: ShipAPI, weapon: WeaponAPI): Float {
            val stat = when (weapon.type) {
                WeaponAPI.WeaponType.BALLISTIC -> ship.mutableStats.ballisticRoFMult
                WeaponAPI.WeaponType.ENERGY -> ship.mutableStats.energyRoFMult
                WeaponAPI.WeaponType.MISSILE -> ship.mutableStats.missileRoFMult
                else -> return 1f
            }
            if (stat.getMultStatMod("phase_anchor_modifier") == null) return stat.modifiedValue
            return stat.createCopy().apply { unmodifyMult("phase_anchor_modifier") }.modifiedValue
        }

        private fun cooldownRate(weapon: WeaponAPI, rate: Float): Float {
            if (weapon.isBeam) return if (weapon.isBurstBeam) rate else 1f
            val spec = weapon.spec as? com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI ?: return min(rate, 1f)
            val refireDelay = weapon.refireDelay
            if (refireDelay <= 0f) return 0f
            val effectiveRate = if (rate <= 1f) rate else {
                val interval = if (weapon.isInBurst) spec.burstDelay else spec.refireDelay + spec.chargeTime
                val minimum = com.fs.starfarer.api.Global.getSettings().getFloat("minRefireDelay")
                if (interval / rate < minimum) max(1f, interval / minimum) else rate
            }
            return effectiveRate * spec.refireDelay / refireDelay
        }

        fun requiredAmmo(weapon: WeaponAPI): Int {
            val snapshot = cn.kasuminova.astd.combat.shipsystems.base.WeaponSnapshot.from(weapon)
            return if (!snapshot.usesAmmo || snapshot.isBeam || snapshot.isPulse) 1 else snapshot.burstSize * snapshot.barrels
        }

        fun reloadRemaining(weapon: WeaponAPI): Float {
            if (!weapon.usesAmmo()) return 0f
            val missing = requiredAmmo(weapon) - weapon.ammo
            if (missing <= 0) return 0f
            val tracker = weapon.ammoTracker
            val rate = tracker.ammoPerSecond
            val reloadSize = tracker.reloadSize
            val batchAmmo = reloadSize.toInt()
            val progress = tracker.reloadProgress
            if (requiredAmmo(weapon) > tracker.maxAmmo || rate <= 0f || reloadSize <= 0f || batchAmmo <= 0 ||
                !rate.isFinite() || !reloadSize.isFinite() || !progress.isFinite()) {
                return Float.POSITIVE_INFINITY
            }
            val batches = kotlin.math.ceil(missing.toDouble() / batchAmmo).toFloat()
            val committedProgress = if (progress >= 1f) 0f else progress.coerceIn(0f, 1f)
            return ((batches - committedProgress) * reloadSize / rate).coerceAtLeast(0f)
        }

        fun naturalAmmoRegenMult(ship: ShipAPI, weapon: WeaponAPI): Float {
            val stat = when (weapon.type) {
                WeaponAPI.WeaponType.BALLISTIC -> ship.mutableStats.ballisticAmmoRegenMult
                WeaponAPI.WeaponType.ENERGY -> ship.mutableStats.energyAmmoRegenMult
                WeaponAPI.WeaponType.MISSILE -> ship.mutableStats.missileAmmoRegenMult
                else -> return 1f
            }
            if (stat.getMultStatMod("phase_anchor_modifier") == null) return stat.modifiedValue
            return stat.createCopy().apply { unmodifyMult("phase_anchor_modifier") }.modifiedValue
        }
    }

    private enum class ChoiceKind { NATURAL, VENT, PHASE_FLUX, PHASE_COOLDOWN }
    private data class Choice(val kind: ChoiceKind, val duration: Float, val cost: Float)
}
