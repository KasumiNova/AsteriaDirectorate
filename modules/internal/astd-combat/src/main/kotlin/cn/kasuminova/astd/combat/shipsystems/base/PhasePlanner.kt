package cn.kasuminova.astd.combat.shipsystems.base

internal class PhasePlanner {
    var action: PhaseAction = PhaseAction.TRAVEL
        private set
    var reason: String = "初始状态"
        private set
    private var failureCooldown = 0f
    private var outputTime = 0f
    private var evadeClearTime = 0f
    private var evadeThreats: Set<Any> = emptySet()
    val committedThreatCount: Int get() = if (action == PhaseAction.EVADE) evadeThreats.size else 0
    private var recoverMode = PhaseRecoverMode.NONE
    private var interruptedAction: PhaseAction? = null
    private var interruptedRecoverMode = PhaseRecoverMode.NONE

    fun decide(s: PhaseSituation, amount: Float, battleAmount: Float = amount): PhaseDecision {
        failureCooldown = (failureCooldown - amount).coerceAtLeast(0f)
        if (s.routeFailed) failureCooldown = 4f
        outputTime = if (s.attack.effectiveOutput) outputTime + amount else 0f
        if (action == PhaseAction.EVADE && (s.danger.significantSoon || s.danger.lethalSoon)) {
            evadeThreats = evadeThreats + s.danger.significantThreatKeys
        }
        val committedThreatActive = action == PhaseAction.EVADE && evadeThreats.any { it in s.danger.activeThreatKeys }
        evadeClearTime = if (action == PhaseAction.EVADE && !committedThreatActive &&
            !s.danger.significantNear && !s.danger.lethalSoon
        ) {
            evadeClearTime + battleAmount
        } else 0f
        val previousAction = action
        val result = when {
            s.retreating -> retreat(s)
            s.phased -> phased(s, committedThreatActive)
            else -> unphased(s)
        }
        if (result.action == PhaseAction.EVADE && previousAction != PhaseAction.EVADE) {
            evadeThreats = s.danger.significantThreatKeys
            if (previousAction != PhaseAction.APPROACH || interruptedAction == null) {
                interruptedAction = previousAction
                interruptedRecoverMode = recoverMode
            }
        } else if (result.action != PhaseAction.EVADE) {
            evadeThreats = emptySet()
            if (result.surface || !s.phased) {
                interruptedAction = null
                interruptedRecoverMode = PhaseRecoverMode.NONE
            }
        }
        action = result.action
        reason = result.reason
        recoverMode = result.recoverMode
        return result
    }

    private fun retreat(s: PhaseSituation): PhaseDecision {
        val keepPhase = s.phased && s.hardFlux < s.maxFlux * 0.7f
        return PhaseDecision(
            PhaseAction.RETREAT,
            dive = !s.phased && s.cloakReady && !phaseSuppressedForFriendly(s) && !s.attack.effectiveOutput &&
                s.hardFlux < s.maxFlux * 0.55f,
            surface = s.phased && !keepPhase && !s.unphaseUnsafe && !s.danger.lethalSoon,
            suppressVent = s.phased,
            reason = if (keepPhase) "撤退途中保持相位" else "执行撤退",
        )
    }

    private fun phased(s: PhaseSituation, committedThreatActive: Boolean): PhaseDecision {
        val execution = s.recovery.execution
        val flankRoute = isFlankRoute(s) && action != PhaseAction.RECOVER && !execution.continuing &&
            execution.state != PhaseRecoveryState.EXITING
        val phaseTimeLimit = if (flankRoute) 36f else 12f
        val phaseElapsed = if (execution.continuing) execution.elapsedLocal else s.phaseActiveTime
        val phaseBudgetExhausted = phaseElapsed >= phaseTimeLimit || s.hardFlux >= s.maxFlux * 0.75f
        if (s.unphaseUnsafe) {
            return hold(PhaseAction.EVADE, "上浮位置重叠，先脱离阻塞", s).copy(customMovement = true)
        }
        if (s.danger.lethalSoon) {
            if (phaseBudgetExhausted) {
                return surface(
                    if (flankRoute) PhaseAction.FLANK else PhaseAction.RECOVER,
                    "持续致命威胁已耗尽紧急相位预算",
                    s,
                )
            }
            return hold(PhaseAction.EVADE, "充出窗口存在致命攻击", s).copy(customMovement = true)
        }
        if (action == PhaseAction.EVADE && (committedThreatActive || s.danger.significantNear) &&
            !phaseBudgetExhausted
        ) {
            return hold(PhaseAction.EVADE, "指定威胁仍未结束", s).copy(customMovement = true)
        }
        if (action == PhaseAction.EVADE && evadeClearTime < 1f && !phaseBudgetExhausted) {
            return hold(PhaseAction.EVADE, "威胁解除后确认短暂安全窗口", s).copy(customMovement = true)
        }
        if (completedRouteAttack(s)) {
            return surface(PhaseAction.ATTACK, "侧后位置已就绪且齐射不会过载，先攻击再恢复", s)
        }
        if (s.routeRecoveryRequired && isFlankRoute(s)) {
            return surface(PhaseAction.FLANK, "当前航速无法完成剩余绕后，保留路线并恢复", s)
        }
        val completingAttackRoute = isFlankRoute(s) && s.routeStage != PhaseRouteStage.LATERAL
        if (completingAttackRoute && !execution.continuing && !s.routeFailed &&
            (!execution.awaitingSurface || execution.reason == PhaseRecoveryReason.OBJECTIVES_MET) &&
            s.phaseActiveTime < 36f && s.hardFlux < s.maxFlux * 0.75f
        ) {
            return PhaseDecision(
                PhaseAction.FLANK,
                suppressVent = true,
                customMovement = true,
                reason = "侧方进入后优先完成短距离穿后，再恢复或攻击",
            )
        }
        if (execution.awaitingSurface) {
            return surface(PhaseAction.RECOVER, "恢复退出：${execution.reason}", s)
                .copy(recoverMode = execution.mode)
        }
        if (s.routeFailed) {
            return surface(PhaseAction.RECOVER, "路线失败，结束本次相位机动", s)
        }
        if (!execution.continuing && phaseBudgetExhausted) {
            val next = when {
                s.hardFluxRecoveryActive -> PhaseAction.RECOVER
                flankRoute -> PhaseAction.FLANK
                s.attack.inRange -> PhaseAction.ATTACK
                else -> PhaseAction.APPROACH
            }
            return surface(next, "单周期相位资源预算已用尽", s)
        }
        if (s.targetId == null && (action == PhaseAction.APPROACH || action == PhaseAction.FLANK)) {
            val travel = s.travelDestDistance > 1200f && s.travelDestDistance.isFinite()
            return surface(if (travel) PhaseAction.TRAVEL else PhaseAction.ATTACK, "目标失效，结束目标相关相位机动", s)
        }
        if (action == PhaseAction.EVADE && s.targetId != null && !s.attack.inRange &&
            !execution.continuing && !s.hardFluxRecoveryActive &&
            interruptedAction != PhaseAction.RECOVER && !isFlankRoute(s)
        ) {
            return hold(PhaseAction.APPROACH, "威胁解除后继续相位接近到真实武器射程", s).copy(customMovement = true)
        }
        if (action == PhaseAction.APPROACH && interruptedAction != null && !s.hardFluxRecoveryActive) {
            if (!s.attack.inRange) {
                return hold(PhaseAction.APPROACH, "规避后先恢复到真实武器射程，再兑现绕后攻击", s)
                    .copy(customMovement = true)
            }
            if (s.attack.canFire && !s.recovery.required && surfaceAffordable(s)) {
                interruptedAction = null
                return surface(PhaseAction.ATTACK, "规避后已恢复真实射界，直接兑现攻击", s)
            }
        }
        if (s.hardFluxRecoveryActive) {
            return surface(PhaseAction.RECOVER, "硬辐能恢复期间停止相位接近并主动拉开距离", s)
                .copy(recoverMode = PhaseRecoverMode.NATURAL)
        }
        if (!completingAttackRoute && s.opportunity.attack && surfaceAffordable(s)) {
            return surface(PhaseAction.ATTACK, "结束相位并利用敌方空窗完成攻击", s)
        }
        if (!completingAttackRoute && s.opportunity.vent && surfaceAffordable(s) && !s.danger.significantNear) {
            return surface(PhaseAction.RECOVER, "上浮准备利用敌方空窗主动耗散", s)
                .copy(recoverMode = PhaseRecoverMode.VENT)
        }
        if (action == PhaseAction.EVADE && interruptedAction == PhaseAction.FLANK &&
            s.routeActive && !s.routeFailed
        ) {
            interruptedAction = null
            if (s.hardFlux + s.phaseActivationCost >= s.maxFlux * 0.30f && s.routeStage == PhaseRouteStage.LATERAL) {
                return surface(PhaseAction.FLANK, "威胁结束但绕后线圈压力仍需恢复", s)
                    .copy(customMovement = false)
            }
            return PhaseDecision(
                PhaseAction.FLANK,
                suppressVent = true,
                customMovement = true,
                reason = "威胁结束，验证后恢复绕后路线",
            )
        }
        if (s.routeComplete && s.attack.canFire && s.attack.inRange && !s.recovery.required && surfaceAffordable(s)) {
            return surface(PhaseAction.ATTACK, "侧后攻击位置已经就绪，停止可选恢复并立即兑现输出", s)
        }
        if (s.recovery.execution.continuing) {
            interruptedAction = null
            return PhaseDecision(
                PhaseAction.RECOVER,
                suppressVent = true,
                customMovement = s.routeActive && !s.routeComplete,
                recoverMode = s.recovery.execution.mode,
                reason = "执行已承诺恢复：${s.recovery.execution.reason}",
            )
        }
        val candidateMode = s.recovery.mode
        val candidateDuration = if (candidateMode == PhaseRecoverMode.PHASE_COOLDOWN) {
            s.recovery.phaseCooldownDuration
        } else {
            s.recovery.phaseFluxDuration
        }
        if ((candidateMode == PhaseRecoverMode.PHASE_COOLDOWN || candidateMode == PhaseRecoverMode.PHASE_FLUX) &&
            candidateDuration > 0f
        ) {
            return PhaseDecision(
                PhaseAction.RECOVER,
                suppressVent = true,
                customMovement = s.routeActive && !s.routeComplete,
                recoverMode = candidateMode,
                reason = "利用当前相位完成已评估的恢复",
            )
        }
        if (action == PhaseAction.RECOVER || action == PhaseAction.EVADE && interruptedAction == PhaseAction.RECOVER) {
            if (action == PhaseAction.EVADE) recoverMode = interruptedRecoverMode
            interruptedAction = null
            return surface(PhaseAction.RECOVER, "没有正在执行的相位恢复，返回非相位状态", s)
        }
        if (s.support.targetInRange && !s.recovery.required) {
            return if (surfaceAffordable(s)) {
                surface(PhaseAction.ATTACK, "目标已进入有效联队作战范围，返回支援站位", s)
            } else {
                hold(PhaseAction.EVADE, "联队可以交战，母舰先侧移规避上浮损失", s).copy(customMovement = true)
            }
        }
        if (isFlankRoute(s)) {
            val completingAttackRoute = s.routeStage != PhaseRouteStage.LATERAL
            if (s.attack.canFire && s.attack.inRange && !s.recovery.required && surfaceAffordable(s) &&
                !completingAttackRoute && s.inTargetRearArc
            ) {
                return surface(PhaseAction.ATTACK, "绕后途中已进入侧后有效射界，直接兑现攻击", s)
            }
            if (s.hardFlux >= s.maxFlux * 0.30f && s.routeStage == PhaseRouteStage.LATERAL && surfaceAffordable(s)) {
                return surface(PhaseAction.FLANK, "长侧移达到跨周期线圈预算，耗散后保留路线", s)
            }
            return PhaseDecision(
                PhaseAction.FLANK,
                suppressVent = true,
                customMovement = true,
                reason = "沿已承诺路径点绕后",
            )
        }
        if (s.routeComplete && s.recovery.required) {
            return surface(PhaseAction.RECOVER, "到达攻击区但爆发预算不足", s)
        }
        if (action == PhaseAction.APPROACH && !s.attack.inRange &&
            s.targetDistance > s.effectiveWeaponRange && !flankWithinBudget(s)
        ) {
            return hold(action, "保持相位接近到真实武器射程", s).copy(customMovement = true)
        }
        if (s.attack.canFire && s.attack.inRange && !s.recovery.required && surfaceAffordable(s) &&
            !(action == PhaseAction.APPROACH && flankOpportunity(s))
        ) {
            return surface(PhaseAction.ATTACK, "统一目标已进入有效射界", s)
        }
        if (action == PhaseAction.TRAVEL && !s.enemyNearby && s.travelDestDistance > 1200f) {
            return hold(action, "低威胁赶路", s)
        }
        if (action == PhaseAction.APPROACH && flankOpportunity(s)) {
            if (flankWithinBudget(s)) {
                if (s.hardFlux + s.phaseActivationCost >= s.maxFlux * 0.15f && surfaceAffordable(s)) {
                    return surface(PhaseAction.FLANK, "绕后前上浮降低线圈压力并保留路线", s)
                }
                return PhaseDecision(
                    PhaseAction.FLANK,
                    suppressVent = true,
                    customMovement = true,
                    reason = "接近已进入可完成绕后的距离，切换到侧向路线",
                )
            }
            if (s.hardFlux < s.maxFlux * 0.6f) {
                return hold(action, "继续接近到真实武器射程", s).copy(customMovement = true)
            }
        }
        if (s.danger.surfaceLoss > acceptableSurfaceLoss(s) && !s.attack.killingBlow) {
            return hold(PhaseAction.EVADE, "充出后预计损失超过攻击预算", s)
        }
        val next = if (s.attack.inRange) PhaseAction.ATTACK else PhaseAction.APPROACH
        return surface(next, "当前相位动作已结束", s)
    }

    private fun unphased(s: PhaseSituation): PhaseDecision {
        if (s.recovery.execution.continuing) {
            return PhaseDecision(PhaseAction.RECOVER, suppressVent = true,
                recoverMode = s.recovery.execution.mode, reason = "已发出恢复下潜，等待实际充入")
        }
        val unacceptableLoss = s.danger.lethalSoon ||
            s.danger.significantSoon && (!s.attack.effectiveOutput || s.danger.surfaceLoss >= s.maxHull * 0.2f)
        if (unacceptableLoss && s.cloakReady && !phaseSuppressedForFriendly(s)) {
            return PhaseDecision(
                PhaseAction.EVADE,
                dive = true,
                suppressVent = true,
                reason = "躲避预计命中的高代价攻击",
            )
        }
        if (s.targetId == null) {
            val travel = s.travelDestDistance > 2000f && s.travelDestDistance.isFinite()
            return PhaseDecision(
                if (travel) PhaseAction.TRAVEL else PhaseAction.ATTACK,
                dive = travel && s.cloakReady && !phaseSuppressedForFriendly(s) && s.hardFlux < s.maxFlux * 0.3f,
                suppressVent = travel,
                reason = if (travel) "低威胁长距离赶路" else "没有有效攻击目标",
            )
        }
        if (s.attack.killingBlow && s.attack.inRange) {
            return PhaseDecision(PhaseAction.ATTACK, goodSpot = true, reason = "执行可击毁目标的收尾攻击")
        }
        val outputProtection = s.attack.effectiveOutput &&
            (outputTime < 3f || !isFlankRoute(s) && !s.recovery.required)
        if (s.protectingBurst || outputProtection) {
            return PhaseDecision(PhaseAction.ATTACK, suppressVent = true, reason = "保护正在进行的有效攻击")
        }
        if (s.opportunity.attack && !s.danger.lethalSoon) {
            return PhaseDecision(PhaseAction.ATTACK, goodSpot = s.attack.canFire && s.attack.inRange,
                suppressVent = true, reason = "利用敌方空窗完成整轮攻击")
        }
        if (s.opportunity.vent && s.recovery.ventSafe && !s.danger.significantSoon && !s.danger.significantNear) {
            return PhaseDecision(PhaseAction.RECOVER, vent = true, recoverMode = PhaseRecoverMode.VENT,
                reason = "敌方空窗足够完成耗散和下一轮攻击")
        }
        if (completedRouteAttack(s)) {
            return PhaseDecision(PhaseAction.ATTACK, goodSpot = true, suppressVent = true,
                reason = "保持侧后攻击位置，兑现不会过载的完整齐射")
        }
        if (s.routeRecoveryRequired && isFlankRoute(s)) {
            return flankWait(s, "恢复航速并保留剩余绕后路线")
        }
        if (s.recovery.execution.awaitingAttack) {
            return if (s.recoveredAttackReady) {
                PhaseDecision(PhaseAction.ATTACK, goodSpot = true, suppressVent = true,
                    reason = "实际充出结束，兑现承诺武器的完整攻击")
            } else {
                PhaseDecision(PhaseAction.APPROACH, suppressVent = true,
                    reason = "恢复后的有限攻击窗口内重新瞄准统一目标")
            }
        }
        if (s.recovery.required) {
            val phaseAllowed = s.cloakReady && !phaseSuppressedForFriendly(s)
            val mode = when {
                s.routeFailed -> PhaseRecoverMode.NATURAL
                s.recovery.mode == PhaseRecoverMode.PHASE_FLUX && phaseAllowed &&
                    s.recovery.phaseFluxDuration > 0f -> PhaseRecoverMode.PHASE_FLUX
                s.recovery.mode == PhaseRecoverMode.PHASE_COOLDOWN && phaseAllowed &&
                    s.recovery.phaseCooldownDuration > 0f -> PhaseRecoverMode.PHASE_COOLDOWN
                s.recovery.mode == PhaseRecoverMode.VENT && s.recovery.ventSafe -> PhaseRecoverMode.VENT
                else -> PhaseRecoverMode.NATURAL
            }
            val phaseRecovery = mode == PhaseRecoverMode.PHASE_FLUX || mode == PhaseRecoverMode.PHASE_COOLDOWN
            if (isFlankRoute(s) && !s.routeFailed && !phaseRecovery) {
                return flankWait(s, "绕后周期间等待冷却和辐能恢复，保留路线")
            }
            return PhaseDecision(
                PhaseAction.RECOVER,
                dive = phaseRecovery,
                vent = mode == PhaseRecoverMode.VENT && s.routeComplete,
                suppressVent = mode != PhaseRecoverMode.VENT || !s.routeComplete,
                customMovement = s.routeActive && !s.routeComplete,
                recoverMode = mode,
                reason = when (mode) {
                    PhaseRecoverMode.PHASE_FLUX -> "相位恢复完整齐射容量"
                    PhaseRecoverMode.PHASE_COOLDOWN -> "相位加速关键武器冷却"
                    PhaseRecoverMode.VENT -> "侧向脱离后主动耗散"
                    else -> "侧向移动期间自然恢复"
                },
            )
        }
        if (s.support.available) {
            if (s.attack.canFire && s.attack.inRange) {
                return PhaseDecision(PhaseAction.ATTACK, goodSpot = true, reason = "联队支援时兑现母舰直射攻击")
            }
            if (s.support.targetInRange) {
                return PhaseDecision(PhaseAction.ATTACK, reason = "保持联队作战范围内的支援站位")
            }
            val dive = s.cloakReady && !phaseSuppressedForFriendly(s) && !s.hardFluxRecoveryActive &&
                s.hardFlux < s.maxFlux * 0.55f
            return PhaseDecision(PhaseAction.APPROACH, dive = dive, suppressVent = dive,
                reason = "接近到有效联队作战范围")
        }
        if (isFlankRoute(s)) {
            val completingAttackRoute = s.routeStage != PhaseRouteStage.LATERAL
            if (s.attack.canFire && s.attack.inRange && !completingAttackRoute && s.inTargetRearArc) {
                return PhaseDecision(PhaseAction.ATTACK, goodSpot = true, reason = "兑现绕后路线中的已恢复攻击")
            }
            if (!s.cloakReady || s.hardFlux + s.phaseActivationCost >= s.maxFlux * 0.30f) {
                return flankWait(s, "绕后周期间等待冷却和辐能恢复，保留路线")
            }
            return PhaseDecision(
                PhaseAction.FLANK,
                dive = true,
                suppressVent = true,
                customMovement = true,
                reason = "恢复完成，继续已承诺的绕后路线",
            )
        }
        if (failureCooldown <= 0f && flankWithinBudget(s) &&
            (!s.cloakReady || s.hardFlux + s.phaseActivationCost >= s.maxFlux * 0.15f)
        ) {
            return flankWait(s, "建立绕后路线，等待冷却和非相位恢复")
        }
        if (s.cloakReady && !phaseSuppressedForFriendly(s) && failureCooldown <= 0f &&
            s.hardFlux + s.phaseActivationCost < s.maxFlux * 0.15f && flankWithinBudget(s)
        ) {
            return PhaseDecision(
                PhaseAction.FLANK,
                dive = true,
                suppressVent = true,
                customMovement = true,
                reason = "长侧移绕开正面火力",
            )
        }
        if (s.attack.canFire && s.attack.inRange) {
            return PhaseDecision(PhaseAction.ATTACK, goodSpot = true, reason = "保持有效射击")
        }
        if (s.cloakReady && !phaseSuppressedForFriendly(s) && !s.attack.inRange &&
            !s.hardFluxRecoveryActive && s.hardFlux < s.maxFlux * 0.55f
        ) {
            return PhaseDecision(
                PhaseAction.APPROACH,
                dive = true,
                suppressVent = true,
                reason = "相位接近到有效射程",
            )
        }
        return PhaseDecision(PhaseAction.APPROACH, reason = "直接接近目标并保持武器射程")
    }

    private fun flankWait(s: PhaseSituation, reason: String): PhaseDecision {
        val fluxLimit = if (isFlankRoute(s)) 0.30f else 0.15f
        val needsRecovery = s.recovery.required || s.hardFlux + s.phaseActivationCost >= s.maxFlux * fluxLimit
        val vent = needsRecovery && s.recovery.mode == PhaseRecoverMode.VENT && s.recovery.ventSafe &&
            !s.danger.lethalSoon && !s.danger.significantSoon && !s.danger.significantNear
        return PhaseDecision(
            PhaseAction.FLANK,
            vent = vent,
            suppressVent = !vent,
            customMovement = false,
            reason = if (vent) "绕后周期间主动耗散，保留路线" else reason,
        )
    }

    private fun isFlankRoute(s: PhaseSituation): Boolean =
        s.routeActive && !s.routeComplete &&
            (s.routeStage != null && s.routeStage != PhaseRouteStage.RECOVERY ||
                action == PhaseAction.FLANK || interruptedAction == PhaseAction.FLANK)

    private fun flankOpportunity(s: PhaseSituation): Boolean =
        s.attack.hasWeapons && !s.inTargetRearArc && s.targetFrontCoverage > 0

    private fun flankWithinBudget(s: PhaseSituation): Boolean =
        flankOpportunity(s) && s.targetDistance <= s.effectiveWeaponRange * 1.5f

    private fun acceptableSurfaceLoss(s: PhaseSituation): Float =
        (s.maxHull * 0.06f).coerceAtLeast(250f)

    private fun phaseSuppressedForFriendly(s: PhaseSituation): Boolean =
        s.danger.significantSoon && s.hull >= s.maxHull * 0.25f &&
            s.danger.friendlyCatchLoss >= (s.danger.surfaceLoss * 1.25f)
                .coerceAtLeast(s.maxHull * 0.03f)

    private fun completedRouteAttack(s: PhaseSituation): Boolean =
        s.routeComplete && s.inTargetRearArc && s.attack.canFire && s.attack.inRange && s.attack.burstWithinCapacity &&
            !s.danger.significantNear && surfaceAffordable(s)

    private fun surfaceAffordable(s: PhaseSituation): Boolean =
        s.danger.surfaceLoss <= acceptableSurfaceLoss(s) || s.attack.killingBlow

    private fun hold(action: PhaseAction, reason: String, s: PhaseSituation): PhaseDecision =
        PhaseDecision(
            action,
            suppressVent = true,
            customMovement = action == PhaseAction.FLANK && s.routeActive,
            recoverMode = if (action == PhaseAction.RECOVER) recoverMode else PhaseRecoverMode.NONE,
            reason = reason,
        )

    private fun surface(action: PhaseAction, reason: String, s: PhaseSituation): PhaseDecision =
        PhaseDecision(
            action,
            surface = !s.unphaseUnsafe,
            suppressVent = true,
            customMovement = false,
            recoverMode = if (action == PhaseAction.RECOVER) recoverMode else PhaseRecoverMode.NONE,
            reason = reason,
        )
}
