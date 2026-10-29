package cn.kasuminova.astd.combat.shipsystems.base

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

sealed class PhaseEvent {
    data class PhaseIn(val activationCost: Float, val localDuration: Float = 0.5f) : PhaseEvent()
    data class PhaseActive(val localDuration: Float, val maintenanceCostPerSec: Float) : PhaseEvent()
    data class PhaseOut(val localDuration: Float = 0.5f) : PhaseEvent()
    object ContinueTransition : PhaseEvent()
    data class Wait(val localDuration: Float) : PhaseEvent()
    data class WeaponFire(val fluxCost: Float) : PhaseEvent()
    data class Dissipate(val fluxReduction: Float) : PhaseEvent()
}

enum class PhaseState { IDLE, IN, ACTIVE, OUT, COOLDOWN }

data class PhaseInputSnapshot(
    val currFlux: Float,
    val hardFlux: Float,
    val maxFlux: Float,
    val softFlux: Float,
    val naturalDissipationPerSec: Float,
    // timeMult 是稳定 ACTIVE 的总倍率，不是当前过渡帧倍率。
    val timeMult: Float,
    val activationCost: Float,
    val maintenanceCostPerSec: Float,
    val phaseState: PhaseState,
    val cooldownRemaining: Float,
    val qBurst: Float,
    val fSafe: Float,
    val effectLevel: Float = when (phaseState) {
        PhaseState.ACTIVE -> 1f
        PhaseState.IN, PhaseState.OUT -> Float.NaN
        else -> 0f
    },
    val transitionRemainingLocalSec: Float = when (phaseState) {
        PhaseState.IN, PhaseState.OUT -> Float.NaN
        else -> 0f
    },
    val unphasedTimeMult: Float = 1f,
    val phaseCooldownDurationLocalSec: Float = 0f,
    val canExitPhase: Boolean = true,
)

data class PhaseSimResult(
    val accepted: Boolean,
    val peakFlux: Float,
    val hardFluxAtEnd: Float,
    val softFluxAtEnd: Float,
    val localDurationSec: Float,
    val battleDurationSec: Float,
    val softFluxCleared: Float,
    val rejectionReason: String,
    val phaseStateAtEnd: PhaseState,
    val effectLevelAtEnd: Float,
    val cooldownRemainingAtEnd: Float,
)

object EnemyVentCalc {

    fun remainingLocalVentSec(currFlux: Float, fluxDissipation: Float, ventRateMult: Float): Float {
        val ventRate = 2f * fluxDissipation * ventRateMult
        if (ventRate <= 0f) return Float.MAX_VALUE
        return max(0f, currFlux / ventRate)
    }

    fun localToBattleTime(localSec: Float, enemyTimeMult: Float): Float {
        if (enemyTimeMult <= 0f) return Float.MAX_VALUE
        return localSec / enemyTimeMult
    }

    fun ourLocalToBattleTime(localSec: Float, ourTimeMult: Float): Float {
        if (ourTimeMult <= 0f) return Float.MAX_VALUE
        return localSec / ourTimeMult
    }
}

object OwnVentCalc {
    fun newVentLocalSec(
        flux: com.fs.starfarer.api.combat.FluxTrackerAPI,
        endFlux: Float,
        currentDissipation: Float,
        naturalDissipation: Float,
        ventRateMult: Float,
    ): Float {
        val currentRate = 2f * currentDissipation * ventRateMult
        val ventRate = 2f * naturalDissipation * ventRateMult
        val minimumStart = flux.timeToVent * max(1f, currentRate)
        if (!currentRate.isFinite() || currentRate < 0f || !ventRate.isFinite() || ventRate <= 0f ||
            !minimumStart.isFinite() || minimumStart < 0f || !endFlux.isFinite() || endFlux < 0f ||
            !flux.currFlux.isFinite() || flux.currFlux < 0f
        ) return Float.POSITIVE_INFINITY
        return max(flux.currFlux, max(endFlux, minimumStart)) / ventRate
    }
}

object PhaseSteadyState {

    fun softFluxClearedStable(localDuration: Float, dissipation: Float): Float =
        localDuration * dissipation

    fun hardFluxDeltaStable(localDuration: Float, maintenancePerSec: Float): Float =
        localDuration * maintenancePerSec

    fun peakFluxEstimate(
        currFlux: Float,
        activationCost: Float,
        maintenancePerSec: Float,
        localActiveDuration: Float,
        inDuration: Float = 0.5f,
    ): Float = currFlux + activationCost + (inDuration + localActiveDuration) * maintenancePerSec

    fun requiredSoftFlux(gap: Float, activationCost: Float, dissipation: Float, maintenancePerSec: Float): Float {
        if (dissipation <= 0f || maintenancePerSec >= dissipation) return Float.MAX_VALUE
        return (gap + activationCost) / (1f - maintenancePerSec / dissipation)
    }

    fun phaseNetsBetter(dissipation: Float, maintenancePerSec: Float, timeMult: Float): Boolean {
        if (timeMult <= 1f) return false
        if (maintenancePerSec >= dissipation) return false
        return timeMult * (dissipation - maintenancePerSec) > dissipation
    }
}

object PhaseSimulator {

    private const val HARD_FLUX_BUDGET_FRAC = 0.97f
    private const val SUBSTEP_SEC = 0.1f

    fun simulate(input: PhaseInputSnapshot, events: List<PhaseEvent>): PhaseSimResult {
        var currFlux = input.currFlux
        var hardFlux = input.hardFlux
        var softFlux = input.softFlux
        var state = input.phaseState
        var effectLevel = input.effectLevel
        var cooldown = if (state == PhaseState.COOLDOWN) input.cooldownRemaining else 0f
        val hardBudget = input.maxFlux * HARD_FLUX_BUDGET_FRAC
        var localTime = 0f
        var battleTime = 0f
        var peakFlux = currFlux
        var softCleared = 0f

        fun result(reason: String = "") = PhaseSimResult(
            accepted = reason.isEmpty(),
            peakFlux = peakFlux,
            hardFluxAtEnd = hardFlux,
            softFluxAtEnd = softFlux,
            localDurationSec = localTime,
            battleDurationSec = battleTime,
            softFluxCleared = softCleared,
            rejectionReason = reason,
            phaseStateAtEnd = state,
            effectLevelAtEnd = effectLevel,
            cooldownRemainingAtEnd = cooldown,
        )

        if (!input.timeMult.isFinite() || input.timeMult <= 0f ||
            !input.unphasedTimeMult.isFinite() || input.unphasedTimeMult <= 0f ||
            !effectLevel.isFinite() || effectLevel !in 0f..1f ||
            !input.transitionRemainingLocalSec.isFinite() || input.transitionRemainingLocalSec < 0f ||
            !input.cooldownRemaining.isFinite() || input.cooldownRemaining < 0f ||
            !input.phaseCooldownDurationLocalSec.isFinite() || input.phaseCooldownDurationLocalSec < 0f ||
            !input.naturalDissipationPerSec.isFinite() || input.naturalDissipationPerSec < 0f ||
            !input.maintenanceCostPerSec.isFinite() || input.maintenanceCostPerSec < 0f ||
            !currFlux.isFinite() || !hardFlux.isFinite() || !softFlux.isFinite() ||
            !input.maxFlux.isFinite() || input.maxFlux <= 0f ||
            hardFlux < 0f || softFlux < 0f || currFlux > input.maxFlux ||
            abs(currFlux - hardFlux - softFlux) > 0.01f
        ) return result("相位快照或过渡进度无效")

        if (state == PhaseState.ACTIVE && effectLevel != 1f ||
            (state == PhaseState.IDLE || state == PhaseState.COOLDOWN) && effectLevel != 0f
        ) return result("相位状态与效果等级不一致")
        if (state == PhaseState.COOLDOWN && cooldown == 0f) state = PhaseState.IDLE

        fun payFlux(amount: Float, hard: Boolean): Boolean {
            currFlux += amount
            if (hard) hardFlux += amount else softFlux += amount
            peakFlux = max(peakFlux, currFlux)
            return currFlux <= input.maxFlux && hardFlux <= hardBudget
        }

        fun dissipate(amount: Float, phased: Boolean) {
            val fromSoft = min(softFlux, amount)
            softFlux -= fromSoft
            currFlux -= fromSoft
            softCleared += fromSoft
            if (!phased) {
                val fromHard = min(hardFlux, amount - fromSoft)
                hardFlux -= fromHard
                currFlux -= fromHard
            }
        }

        fun advancePhased(duration: Float, maintenance: Float, endEffect: Float): Boolean {
            val startEffect = effectLevel
            var elapsed = 0f
            while (elapsed < duration) {
                val dt = min(SUBSTEP_SEC, duration - elapsed)
                if (maintenance > 0f && !payFlux(dt * maintenance, hard = true)) return false
                dissipate(dt * input.naturalDissipationPerSec, phased = true)
                val nextElapsed = min(duration, elapsed + dt)
                val nextEffect = startEffect + (endEffect - startEffect) * (nextElapsed / duration)
                battleTime += transitionBattleTime(dt, effectLevel, nextEffect, input)
                localTime += dt
                effectLevel = nextEffect
                elapsed = nextElapsed
            }
            effectLevel = endEffect
            return true
        }

        fun finishOut() {
            cooldown = input.phaseCooldownDurationLocalSec
            state = if (cooldown > 0f) PhaseState.COOLDOWN else PhaseState.IDLE
        }

        for (event in events) {
            when (event) {
                is PhaseEvent.PhaseIn -> {
                    if (state != PhaseState.IDLE) return result("仅空闲相位可重新激活")
                    if (!validDuration(event.localDuration) || !validDuration(event.activationCost)) {
                        return result("充入费用或时长无效")
                    }
                    if (!payFlux(event.activationCost, hard = true)) return result("激活费超辐能预算")
                    state = PhaseState.IN
                    if (!advancePhased(event.localDuration, input.maintenanceCostPerSec, 1f)) {
                        return result("IN 维护费超辐能预算")
                    }
                    state = PhaseState.ACTIVE
                }
                is PhaseEvent.PhaseActive -> {
                    if (state != PhaseState.ACTIVE) return result("非 ACTIVE 状态不能保持相位")
                    if (!validDuration(event.localDuration) || !validDuration(event.maintenanceCostPerSec)) {
                        return result("维护费用或时长无效")
                    }
                    if (!advancePhased(event.localDuration, event.maintenanceCostPerSec, 1f)) {
                        return result("ACTIVE 维护费超辐能预算")
                    }
                }
                is PhaseEvent.PhaseOut -> {
                    if (state != PhaseState.ACTIVE && state != PhaseState.IN) {
                        return result("当前状态不能开始充出")
                    }
                    if (!input.canExitPhase) return result("当前相位无法退出")
                    if (!validDuration(event.localDuration)) return result("充出时长无效")
                    state = PhaseState.OUT
                    advancePhased(event.localDuration, 0f, 0f)
                    finishOut()
                }
                PhaseEvent.ContinueTransition -> {
                    if (state != PhaseState.IN && state != PhaseState.OUT) {
                        return result("当前没有待完成过渡")
                    }
                    val entering = state == PhaseState.IN
                    if (!advancePhased(
                            input.transitionRemainingLocalSec,
                            if (entering) input.maintenanceCostPerSec else 0f,
                            if (entering) 1f else 0f,
                        )
                    ) return result("IN 维护费超辐能预算")
                    if (entering) state = PhaseState.ACTIVE else finishOut()
                }
                is PhaseEvent.Wait -> {
                    if (state != PhaseState.IDLE && state != PhaseState.COOLDOWN) {
                        return result("相位中必须显式模拟维护或过渡")
                    }
                    if (!validDuration(event.localDuration)) return result("等待时长无效")
                    dissipate(event.localDuration * input.naturalDissipationPerSec, phased = false)
                    localTime += event.localDuration
                    battleTime += event.localDuration / input.unphasedTimeMult
                    cooldown = max(0f, cooldown - event.localDuration)
                    if (cooldown == 0f) state = PhaseState.IDLE
                }
                is PhaseEvent.WeaponFire -> {
                    if (!validDuration(event.fluxCost)) return result("武器费用无效")
                    if (!payFlux(event.fluxCost, hard = false)) return result("武器支付超辐能预算")
                }
                is PhaseEvent.Dissipate -> {
                    if (state != PhaseState.IDLE && state != PhaseState.COOLDOWN) {
                        return result("相位中不能主动耗散")
                    }
                    if (!validDuration(event.fluxReduction)) return result("耗散量无效")
                    dissipate(event.fluxReduction, phased = false)
                }
            }
        }
        return result()
    }

    private fun validDuration(value: Float): Boolean = value.isFinite() && value >= 0f

    private fun transitionBattleTime(
        localDuration: Float,
        startEffect: Float,
        endEffect: Float,
        input: PhaseInputSnapshot,
    ): Float {
        val startMult = input.unphasedTimeMult + (input.timeMult - input.unphasedTimeMult) * startEffect
        val endMult = input.unphasedTimeMult + (input.timeMult - input.unphasedTimeMult) * endEffect
        val delta = endMult - startMult
        if (abs(delta) < 0.00001f) return localDuration / startMult
        return localDuration * ln(endMult / startMult) / delta
    }
}
