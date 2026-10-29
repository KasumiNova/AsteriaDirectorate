package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseAction
import cn.kasuminova.astd.combat.shipsystems.base.PhaseAttack
import cn.kasuminova.astd.combat.shipsystems.base.PhaseDanger
import cn.kasuminova.astd.combat.shipsystems.base.PhasePlanner
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoverMode
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRecovery
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GravityPhaseCloakAITest {
    private fun runningRecovery(mode: PhaseRecoverMode) =
        cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryExecution(
            state = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState.RUNNING,
            reason = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.COOLDOWN_PENDING,
            mode = mode, cooldownRemaining = 2f,
        )

    @Test
    fun `既有完整爆发无瞬时伤害也不会被普通恢复中断`() {
        val planner = PhasePlanner()
        val firing = situation().copy(protectingBurst = true,
            recovery = PhaseRecovery(true, PhaseRecoverMode.PHASE_COOLDOWN, phaseCooldownDuration = 2f))
        repeat(30) {
            val decision = planner.decide(firing, 0.2f)
            assertEquals(PhaseAction.ATTACK, decision.action)
            assertFalse(decision.dive)
            assertFalse(decision.vent)
        }
    }

    @Test
    fun `下潜命令未确认不会重复承诺或重新请求下潜`() {
        val decision = PhasePlanner().decide(situation().copy(
            recovery = PhaseRecovery(true, execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN)),
        ), 0.2f)
        assertEquals(PhaseAction.RECOVER, decision.action)
        assertFalse(decision.dive)
    }

    @Test
    fun `恢复完成或取消后仍先检查上浮致命风险`() {
        val reasons = listOf(
            cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.OBJECTIVES_MET,
            cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.UNSAFE_SIMULATION,
        )
        for (reason in reasons) {
            val planner = PhasePlanner()
            val exiting = situation().copy(
                phased = true,
                phaseActiveTime = 3f,
                recovery = PhaseRecovery(false, execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN).copy(
                    state = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState.EXITING,
                    reason = reason,
                    awaitingSurface = true,
                )),
                danger = PhaseDanger(true, true, true, 5000f, 0f),
            )
            val evade = planner.decide(exiting, 0.2f)
            assertEquals(PhaseAction.EVADE, evade.action)
            assertFalse(evade.surface)
            assertTrue(evade.customMovement)
            val clearSituation = exiting.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
            repeat(4) {
                val waiting = planner.decide(clearSituation, 0.2f)
                assertEquals(PhaseAction.EVADE, waiting.action)
                assertFalse(waiting.surface)
            }
            val clear = planner.decide(clearSituation, 0.2f)
            assertTrue(clear.surface)
            assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, clear.recoverMode)
        }
    }

    @Test
    fun `航母到达联队范围就上浮而不是冲到直射距离`() {
        val planner = PhasePlanner()
        val carrier = situation().copy(
            targetDistance = 5000f, effectiveWeaponRange = 0f,
            attack = situation().attack.copy(hasWeapons = false, inRange = false, canFire = false),
            support = cn.kasuminova.astd.combat.shipsystems.base.PhaseSupport(true, 4000f, false, false),
        )
        assertTrue(planner.decide(carrier, 0.2f).dive)
        val closing = planner.decide(carrier.copy(phased = true), 0.2f)
        assertFalse(closing.surface)
        assertTrue(closing.customMovement)
        val reached = carrier.copy(phased = true, targetDistance = 3500f,
            support = carrier.support.copy(targetInRange = true))
        val surface = planner.decide(reached, 0.2f)
        assertTrue(surface.surface)
        assertEquals(PhaseAction.ATTACK, surface.action)
        repeat(20) {
            val support = planner.decide(reached.copy(phased = false), 0.2f)
            assertEquals(PhaseAction.ATTACK, support.action)
            assertFalse(support.dive)
            assertFalse(support.customMovement)
            assertFalse(support.goodSpot)
        }
    }

    @Test
    fun `联队支援仍保留母舰直射机会并服从致命规避`() {
        val supporting = situation().copy(
            targetDistance = 700f,
            attack = situation().attack.copy(inRange = true),
            support = cn.kasuminova.astd.combat.shipsystems.base.PhaseSupport(true, 4000f, true, true),
        )
        val attack = PhasePlanner().decide(supporting, 0.2f)
        assertEquals(PhaseAction.ATTACK, attack.action)
        assertTrue(attack.goodSpot)
        assertFalse(attack.dive)
        val danger = supporting.copy(phased = true, danger = PhaseDanger(true, true, true, 5000f, 0f))
        val evade = PhasePlanner().decide(danger, 0.2f)
        assertEquals(PhaseAction.EVADE, evade.action)
        assertFalse(evade.surface)
        assertTrue(evade.customMovement)
        val recalled = PhasePlanner().decide(supporting.copy(
            support = cn.kasuminova.astd.combat.shipsystems.base.PhaseSupport()), 0.2f)
        assertEquals(PhaseAction.FLANK, recalled.action)
    }

    @Test
    fun `完整攻击能利用空窗时不被可选冷却或绕后抢占`() {
        val ready = situation().copy(
            targetDistance = 800f,
            attack = situation().attack.copy(inRange = true),
            recovery = PhaseRecovery(true, PhaseRecoverMode.PHASE_COOLDOWN, phaseCooldownDuration = 3f),
            opportunity = cn.kasuminova.astd.combat.shipsystems.base.PhaseOpportunity(attack = true),
        )
        val attack = PhasePlanner().decide(ready, 0.2f)
        assertEquals(PhaseAction.ATTACK, attack.action)
        assertFalse(attack.dive)
        assertFalse(attack.vent)
        val phased = PhasePlanner().decide(ready.copy(phased = true), 0.2f)
        assertEquals(PhaseAction.ATTACK, phased.action)
        assertTrue(phased.surface)
    }

    @Test
    fun `空窗允许耗散时立即耗散但其他敌舰威胁仍可否决`() {
        val recover = situation().copy(
            attack = situation().attack.copy(inRange = true, canFire = false),
            recovery = PhaseRecovery(true, PhaseRecoverMode.VENT, ventSafe = true),
            opportunity = cn.kasuminova.astd.combat.shipsystems.base.PhaseOpportunity(vent = true),
        )
        val vent = PhasePlanner().decide(recover, 0.2f)
        assertEquals(PhaseAction.RECOVER, vent.action)
        assertTrue(vent.vent)
        assertFalse(vent.dive)
        assertFalse(vent.suppressVent)
        val threatened = PhasePlanner().decide(recover.copy(
            danger = PhaseDanger(true, true, true, 5000f, 0f)), 0.2f)
        assertEquals(PhaseAction.EVADE, threatened.action)
        assertFalse(threatened.vent)
    }

    @Test
    fun `侧后进入阶段已有真实射界时直接上浮攻击`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 4f,
            targetDistance = 700f,
            inTargetRearArc = true,
            routeActive = true,
            routeComplete = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.SIDE_ENTRY,
            attack = situation().attack.copy(inRange = true, canFire = true),
        ), 0.2f)
        assertEquals(PhaseAction.ATTACK, decision.action)
        assertTrue(decision.surface)
    }

    @Test
    fun `侧后路线完成后不等待可选系统冷却而立即上浮攻击`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 4f,
            targetDistance = 650f,
            inTargetRearArc = true,
            routeComplete = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.ATTACK,
            attack = situation().attack.copy(inRange = true, canFire = true),
            recovery = PhaseRecovery(required = false, execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN)),
        ), 0.2f)

        assertEquals(PhaseAction.ATTACK, decision.action)
        assertTrue(decision.surface)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `侧后路线完成后真实容量允许时先齐射再恢复`() {
        val ready = situation().copy(
            targetDistance = 650f,
            hardFlux = 3000f,
            routeComplete = true,
            inTargetRearArc = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.ATTACK,
            hardFluxRecoveryActive = true,
            attack = situation().attack.copy(inRange = true, canFire = true, burstWithinCapacity = true),
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.NATURAL),
        )
        val phased = PhasePlanner().decide(ready.copy(phased = true), 0.2f)
        assertEquals(PhaseAction.ATTACK, phased.action)
        assertTrue(phased.surface)
        val unphased = PhasePlanner().decide(ready, 0.2f)
        assertEquals(PhaseAction.ATTACK, unphased.action)
        assertTrue(unphased.goodSpot)

        val overloaded = ready.copy(attack = ready.attack.copy(burstWithinCapacity = false))
        assertEquals(PhaseAction.RECOVER, PhasePlanner().decide(overloaded.copy(phased = true), 0.2f).action)
        assertEquals(PhaseAction.RECOVER, PhasePlanner().decide(overloaded, 0.2f).action)
    }

    @Test
    fun `长侧移尚未形成实际进度时不被正面开火打断`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 1f,
            targetDistance = 700f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            attack = situation().attack.copy(inRange = true, canFire = true),
            danger = PhaseDanger(false, false, false, 200f, 0f),
        ), 0.2f)

        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `攻击段接近后弧时先完成穿后但不得超过紧急硬辐能上限`() {
        val route = situation().copy(
            phased = true,
            phaseActiveTime = 30f,
            targetDistance = 250f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.ATTACK,
            hardFluxRecoveryActive = true,
        )
        val completing = PhasePlanner().decide(route.copy(hardFlux = 3000f), 0.2f)
        assertEquals(PhaseAction.FLANK, completing.action)
        assertFalse(completing.surface)
        assertTrue(completing.customMovement)

        val exhausted = PhasePlanner().decide(route.copy(hardFlux = 3800f), 0.2f)
        assertEquals(PhaseAction.RECOVER, exhausted.action)
        assertTrue(exhausted.surface)
    }

    @Test
    fun `长侧移只有真正进入侧后射界才提前攻击`() {
        val early = situation().copy(
            phased = true,
            phaseActiveTime = 4f,
            targetDistance = 700f,
            inTargetRearArc = false,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            routeProgress = 500f,
            attack = situation().attack.copy(inRange = true, canFire = true),
            danger = PhaseDanger(false, false, false, 200f, 0f),
        )
        val frontalPlanner = PhasePlanner()
        frontalPlanner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val frontal = frontalPlanner.decide(early, 0.2f)
        assertEquals(PhaseAction.FLANK, frontal.action)
        assertFalse(frontal.surface)
        assertTrue(frontal.customMovement)

        val rearPlanner = PhasePlanner()
        rearPlanner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val rear = rearPlanner.decide(early.copy(inTargetRearArc = true), 0.2f)
        assertEquals(PhaseAction.ATTACK, rear.action)
        assertTrue(rear.surface)
        assertFalse(rear.customMovement)
    }

    @Test
    fun `长侧移跨周期恢复不把正面射界误判为绕后完成`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 12f,
            targetDistance = 700f,
            hardFlux = 2000f,
            inTargetRearArc = false,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            routeProgress = 1200f,
            attack = situation().attack.copy(inRange = true, canFire = true),
        ), 0.2f)

        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.surface)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `侧后路线优先推进不会提前解除已绑定威胁的规避`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val threat = setOf<Any>("missile")
        val attacking = situation().copy(
            phased = true,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.SIDE_ENTRY,
        )
        val lethal = attacking.copy(
            danger = PhaseDanger(true, true, true, 5000f, 0f, threat, threat),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(lethal, 0.2f).action)
        val continuing = planner.decide(attacking.copy(
            danger = PhaseDanger(false, false, true, 500f, 0f, threat, threat),
        ), 0.2f)

        assertEquals(PhaseAction.EVADE, continuing.action)
        assertFalse(continuing.surface)
        assertEquals(1, planner.committedThreatCount)
    }

    @Test
    fun `高硬辐能回差期间不继续相位接近`() {
        val phased = PhasePlanner().decide(situation().copy(
            phased = true,
            hardFluxRecoveryActive = true,
        ), 0.2f)
        assertEquals(PhaseAction.RECOVER, phased.action)
        assertTrue(phased.surface)

        val unphased = PhasePlanner().decide(situation().copy(hardFluxRecoveryActive = true), 0.2f)
        assertEquals(PhaseAction.APPROACH, unphased.action)
        assertFalse(unphased.dive)
    }

    @Test
    fun `侧方进入尚未形成侧后射界时优先完成短距离穿后`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 4f,
            targetDistance = 700f,
            inTargetRearArc = false,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.SIDE_ENTRY,
            attack = situation().attack.copy(inRange = true, canFire = true),
            danger = PhaseDanger(false, false, false, 200f, 0f),
        ), 0.2f)

        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `侧方进入中的恢复完成等待穿后而非立即上浮`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN).copy(
            state = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState.EXITING,
            reason = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.OBJECTIVES_MET,
            awaitingSurface = true,
        )
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 4f,
            targetDistance = 700f,
            inTargetRearArc = false,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.SIDE_ENTRY,
            attack = situation().attack.copy(inRange = true, canFire = true),
            recovery = PhaseRecovery(required = false, execution = execution),
        ), 0.2f)

        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `重叠规避结束后确认安全再完成攻击段`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN).copy(
            state = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState.EXITING,
            reason = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.OBJECTIVES_MET,
            awaitingSurface = true,
        )
        val attacking = situation().copy(
            phased = true,
            phaseActiveTime = 4f,
            targetDistance = 700f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.ATTACK,
            attack = situation().attack.copy(inRange = true, canFire = true),
            recovery = PhaseRecovery(required = false, execution = execution),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(attacking.copy(unphaseUnsafe = true), 0.2f).action)
        repeat(4) {
            assertEquals(PhaseAction.EVADE, planner.decide(attacking, 0.2f).action)
        }
        val decision = planner.decide(attacking, 0.2f)
        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `攻击段尚未形成真实攻击条件时不因恢复完成提前上浮`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN).copy(
            state = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState.EXITING,
            reason = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.OBJECTIVES_MET,
            awaitingSurface = true,
        )
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 4f,
            targetDistance = 700f,
            inTargetRearArc = true,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.ATTACK,
            attack = situation().attack.copy(inRange = false, canFire = false),
            recovery = PhaseRecovery(required = true, execution = execution),
        ), 0.2f)

        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `绕后中途建立的恢复使用独立预算而不被总相位计时终止`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(situation().copy(
            phased = true,
            phaseActiveTime = 13f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.SIDE_ENTRY,
            recovery = PhaseRecovery(required = true, execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN)),
        ), 0.2f)
        assertEquals(PhaseAction.RECOVER, decision.action)
        assertFalse(decision.surface)
    }

    @Test
    fun `绕后被规避打断且脱离射程时恢复原路线而不是正面接近`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            targetDistance = 1400f,
            hardFlux = 100f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            danger = PhaseDanger(true, true, true, 5000f, 0f),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)
        val clear = threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
        var decision = planner.decide(clear, 0.2f)
        repeat(4) { decision = planner.decide(clear, 0.2f) }
        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)

        decision = planner.decide(clear, 0.2f)
        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `规避后接近不得抢占高硬辐能恢复`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            targetDistance = 1400f,
            routeActive = false,
            routeStage = null,
            danger = PhaseDanger(true, true, true, 5000f, 0f),
        )
        planner.decide(threatened, 0.2f)
        val clear = threatened.copy(
            danger = PhaseDanger(false, false, false, 0f, 0f),
            hardFluxRecoveryActive = true,
        )

        var decision = planner.decide(clear, 0.2f)
        repeat(4) { decision = planner.decide(clear, 0.2f) }

        assertEquals(PhaseAction.RECOVER, decision.action)
        assertTrue(decision.surface)
    }

    @Test
    fun `高硬辐能不提前结束仍有效的非致命规避`() {
        val planner = PhasePlanner()
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            hardFlux = 3500f,
            danger = PhaseDanger(false, true, true, 500f, 0f,
                threatKeys = setOf("dem-a"), activeThreatKeys = setOf("dem-a")),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)

        val continued = planner.decide(threatened.copy(
            hardFluxRecoveryActive = true,
            danger = threatened.danger.copy(
                threatKeys = setOf("dem-b"), activeThreatKeys = setOf("dem-a", "dem-b"),
                significantThreatKeys = setOf("dem-b"),
            ),
        ), 0.2f)

        assertEquals(PhaseAction.EVADE, continued.action)
        assertFalse(continued.surface)
        assertEquals(2, planner.committedThreatCount)
        val originalCleared = planner.decide(threatened.copy(
            danger = threatened.danger.copy(threatKeys = emptySet(), activeThreatKeys = setOf("dem-b")),
        ), 0.2f)
        assertEquals(PhaseAction.EVADE, originalCleared.action)
        assertFalse(originalCleared.surface)
    }

    @Test
    fun `规避上浮后不会把后续恢复路线误认为绕后路线`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            danger = PhaseDanger(true, true, true, 5000f, 0f),
        )
        planner.decide(threatened, 0.2f)
        val clear = threatened.copy(
            routeActive = false,
            routeStage = null,
            inTargetRearArc = true,
            attack = threatened.attack.copy(inRange = true),
            danger = PhaseDanger(false, false, false, 0f, 0f),
        )
        repeat(5) { planner.decide(clear, 0.2f) }

        val recovery = planner.decide(clear.copy(
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.RECOVERY,
            recovery = PhaseRecovery(required = true, execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN)),
        ), 0.2f)

        assertEquals(PhaseAction.RECOVER, recovery.action)
        assertFalse(recovery.surface)
        assertTrue(recovery.customMovement)
    }

    @Test
    fun `规避期间不会绑定没有显著伤害的新威胁`() {
        val planner = PhasePlanner()
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            danger = PhaseDanger(false, true, true, 500f, 0f,
                threatKeys = setOf("dem-a"), activeThreatKeys = setOf("dem-a")),
        )
        planner.decide(threatened, 0.2f)
        val trivial = threatened.copy(
            danger = PhaseDanger(false, false, true, 1f, 0f,
                threatKeys = setOf("trivial"), activeThreatKeys = setOf("dem-a", "trivial"),
                significantThreatKeys = emptySet()),
        )
        planner.decide(trivial, 0.2f)

        assertEquals(1, planner.committedThreatCount)
        val cleared = trivial.copy(
            attack = trivial.attack.copy(inRange = true),
            danger = PhaseDanger(false, false, false, 0f, 0f,
                threatKeys = emptySet(), activeThreatKeys = setOf("trivial")),
        )
        var decision = planner.decide(cleared, 0.2f)
        repeat(4) { decision = planner.decide(cleared, 0.2f) }

        assertEquals(PhaseAction.ATTACK, decision.action)
        assertTrue(decision.surface)
    }

    @Test
    fun `当前航速不可完成绕后时跨相位保留路线并在恢复后继续`() {
        val planner = PhasePlanner()
        val waiting = situation().copy(
            targetDistance = 1000f,
            hardFlux = 2000f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            routeRecoveryRequired = true,
        )

        val unphased = planner.decide(waiting, 0.2f)
        assertEquals(PhaseAction.FLANK, unphased.action)
        assertFalse(unphased.dive)
        assertFalse(unphased.customMovement)
        val phased = planner.decide(waiting.copy(phased = true, phaseActiveTime = 4f), 0.2f)
        assertEquals(PhaseAction.FLANK, phased.action)
        assertTrue(phased.surface)
        assertFalse(phased.customMovement)

        val stillWaiting = planner.decide(waiting.copy(hardFlux = 100f), 0.2f)
        assertEquals(PhaseAction.FLANK, stillWaiting.action)
        assertFalse(stillWaiting.dive)
        assertFalse(stillWaiting.customMovement)
        val resumed = planner.decide(waiting.copy(hardFlux = 100f, routeRecoveryRequired = false), 0.2f)
        assertEquals(PhaseAction.FLANK, resumed.action)
        assertTrue(resumed.dive)
        assertTrue(resumed.customMovement)
    }

    private fun situation() = PhaseSituation(
        targetId = "enemy",
        phased = false,
        phaseActiveTime = 0f,
        timeSinceUnphase = 5f,
        hardFlux = 100f,
        softFlux = 100f,
        maxFlux = 5000f,
        hull = 5000f,
        maxHull = 5000f,
        cloakReady = true,
        retreating = false,
        unphaseUnsafe = false,
        targetDistance = 1800f,
        effectiveWeaponRange = 1000f,
        inTargetRearArc = false,
        targetFrontCoverage = 3,
        enemyNearby = true,
        enemyWindowRemaining = 0f,
        travelDestDistance = Float.POSITIVE_INFINITY,
        attack = PhaseAttack(
            hasWeapons = true,
            inRange = false,
            canFire = true,
            readySoon = true,
            remainingBurstCost = 800f,
            burstWithinCapacity = true,
            effectiveOutput = false,
            killingBlow = false,
            cooldownRemaining = 0f,
        ),
        danger = PhaseDanger(false, false, false, 0f, 0f),
        recovery = PhaseRecovery(required = false),
        routeActive = false,
        routeComplete = false,
        routeFailed = false,
    )

    @Test
    fun `远程正面目标先相位接近而不生成超时绕后路线`() {
        val decision = PhasePlanner().decide(situation(), 0.2f)
        assertEquals(PhaseAction.APPROACH, decision.action)
        assertTrue(decision.dive)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `远程尚未观察到正面火力时保持相位接近`() {
        val planner = PhasePlanner()
        planner.decide(situation(), 0.2f)
        val decision = planner.decide(
            situation().copy(phased = true, phaseActiveTime = 0.4f, targetFrontCoverage = 0),
            0.2f,
        )

        assertEquals(PhaseAction.APPROACH, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `进入路线时间预算距离后启动长侧移绕后`() {
        val decision = PhasePlanner().decide(situation().copy(targetDistance = 1000f), 0.2f)
        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.dive)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `绕后到达射程和射界后立即上浮`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val arrived = situation().copy(
            phased = true,
            phaseActiveTime = 3f,
            targetDistance = 800f,
            inTargetRearArc = true,
            routeActive = true,
            routeComplete = true,
            attack = situation().attack.copy(inRange = true),
        )
        val decision = planner.decide(arrived, 0.2f)
        assertEquals(PhaseAction.ATTACK, decision.action)
        assertTrue(decision.surface)
    }

    @Test
    fun `绕后途中不会只因部分武器就绪上浮`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(
            situation().copy(phased = true, phaseActiveTime = 2f, routeActive = true),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.surface)
    }

    @Test
    fun `未完成绕后使用整路时限而不是二十四秒时返回正面`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val continuing = planner.decide(
            situation().copy(phased = true, phaseActiveTime = 24f, routeActive = true),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, continuing.action)
        assertFalse(continuing.surface)
        val exhausted = planner.decide(
            situation().copy(phased = true, phaseActiveTime = 36f, routeActive = true),
            0.2f,
        )
        assertTrue(exhausted.surface)
    }

    @Test
    fun `有效输出期间普通恢复不能下潜或耗散`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                attack = situation().attack.copy(inRange = true, effectiveOutput = true),
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_FLUX, phaseFluxDuration = 1f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.ATTACK, decision.action)
        assertFalse(decision.dive)
        assertTrue(decision.suppressVent)
    }

    @Test
    fun `有效输出期间不建立下一条绕后路线`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                targetDistance = 800f,
                hardFlux = 2000f,
                attack = situation().attack.copy(inRange = true, effectiveOutput = true),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.ATTACK, decision.action)
        assertFalse(decision.dive)
    }

    @Test
    fun `紧急威胁可以打断有效输出`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                attack = situation().attack.copy(inRange = true, effectiveOutput = true),
                danger = PhaseDanger(true, true, true, 2000f, 0f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.EVADE, decision.action)
        assertTrue(decision.dive)
    }

    @Test
    fun `正面可以开火时仍优先绕后而不是消耗正面输出窗口`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                targetDistance = 800f,
                attack = situation().attack.copy(inRange = true),
                danger = PhaseDanger(false, false, false, 0f, 0f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.dive)
    }

    @Test
    fun `高线圈压力建立绕后路线但不在非相位状态移动`() {
        val decision = PhasePlanner().decide(
            situation().copy(targetDistance = 1000f, hardFlux = 2000f),
            0.2f,
        )

        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.dive)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `已承诺绕后等待当前有效输出结束`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(
            situation().copy(
                routeActive = true,
                attack = situation().attack.copy(effectiveOutput = true),
            ),
            0.2f,
        )

        assertEquals(PhaseAction.ATTACK, decision.action)
        assertFalse(decision.dive)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `紧急规避后保留绕后路线并执行相位冷却`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        planner.decide(
            situation().copy(
                phased = true,
                routeActive = true,
                routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
                danger = PhaseDanger(true, true, true, 2000f, 0f),
            ),
            0.2f,
        )

        val decision = planner.decide(
            situation().copy(
                routeActive = true,
                routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_COOLDOWN, phaseCooldownDuration = 1f),
            ),
            0.2f,
        )

        assertEquals(PhaseAction.RECOVER, decision.action)
        assertTrue(decision.dive)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, decision.recoverMode)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `保留绕后路线的相位恢复仍使用十二秒恢复预算`() {
        val planner = PhasePlanner()
        val recovering = situation().copy(
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_COOLDOWN, phaseCooldownDuration = 1f),
        )
        planner.decide(recovering, 0.2f)
        val decision = planner.decide(recovering.copy(phased = true, phaseActiveTime = 12.1f), 0.2f)
        assertTrue(decision.surface)
    }

    @Test
    fun `候选排名变化不终止安全的已承诺恢复`() {
        val planner = PhasePlanner()
        val recovering = situation().copy(
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_COOLDOWN, phaseCooldownDuration = 1f),
        )
        planner.decide(recovering, 0.2f)
        val changed = recovering.copy(
            phased = true,
            phaseActiveTime = 1f,
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.NATURAL, naturalDuration = 4f,
                execution = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN)),
        )
        val decision = planner.decide(changed, 0.2f)
        assertEquals(PhaseAction.RECOVER, decision.action)
        assertEquals(PhaseRecoverMode.PHASE_COOLDOWN, decision.recoverMode)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `恢复完成且武器就绪时先兑现攻击而不是再次下潜`() {
        val planner = PhasePlanner()
        val decision = planner.decide(
            situation().copy(
                routeActive = true,
                routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
                routeProgress = 500f,
                inTargetRearArc = true,
                attack = situation().attack.copy(inRange = true, canFire = true),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.ATTACK, decision.action)
        assertFalse(decision.dive)
    }

    @Test
    fun `长侧移预算上浮时真实侧后射界仍先攻击`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(situation().copy(
            phased = true,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            hardFlux = 1500f,
            inTargetRearArc = true,
            attack = situation().attack.copy(inRange = true, canFire = true),
        ), 0.2f)

        assertEquals(PhaseAction.ATTACK, decision.action)
        assertTrue(decision.surface)
        assertTrue(decision.suppressVent)
    }

    @Test
    fun `绕后周期间线圈压力未恢复不会重新下潜`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(
            situation().copy(routeActive = true, hardFlux = 1600f),
            0.2f,
        )

        assertEquals(PhaseAction.FLANK, decision.action)
        assertFalse(decision.dive)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `侧后射界就绪时直接保持攻击`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                targetDistance = 800f,
                inTargetRearArc = true,
                attack = situation().attack.copy(inRange = true),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.ATTACK, decision.action)
        assertFalse(decision.dive)
    }

    @Test
    fun `持续致命威胁不能绕过紧急相位预算`() {
        val planner = PhasePlanner()
        val decision = planner.decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 20f,
                hardFlux = 4000f,
                danger = PhaseDanger(true, true, true, 5000f, 0f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.RECOVER, decision.action)
        assertTrue(decision.surface)
    }

    @Test
    fun `致命规避中的恢复按自身本地预算而非总相位时间终止`() {
        val planner = PhasePlanner()
        val executing = runningRecovery(PhaseRecoverMode.PHASE_COOLDOWN).copy(elapsedLocal = 2f)
        val danger = PhaseDanger(true, true, true, 5000f, 0f)
        val continuing = planner.decide(situation().copy(
            phased = true, phaseActiveTime = 20f, danger = danger,
            recovery = PhaseRecovery(required = true, execution = executing),
        ), 0.2f)
        assertEquals(PhaseAction.EVADE, continuing.action)
        assertFalse(continuing.surface)

        val expired = planner.decide(situation().copy(
            phased = true, phaseActiveTime = 20f, danger = danger,
            recovery = PhaseRecovery(required = true, execution = executing.copy(elapsedLocal = 12f)),
        ), 0.2f)
        assertEquals(PhaseAction.RECOVER, expired.action)
        assertTrue(expired.surface)
    }

    @Test
    fun `相位接近中目标失效立即结束目标相关机动`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 3000f, targetFrontCoverage = 0), 0.2f)
        val decision = planner.decide(
            situation().copy(phased = true, targetId = null, enemyNearby = false),
            0.2f,
        )
        assertEquals(PhaseAction.ATTACK, decision.action)
        assertTrue(decision.surface)
    }

    @Test
    fun `相位接近持续执行自定义目标推进`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(cloakReady = false, targetDistance = 6000f), 0.2f)
        val decision = planner.decide(
            situation().copy(phased = true, phaseActiveTime = 2f, targetDistance = 6000f),
            0.2f,
        )
        assertEquals(PhaseAction.APPROACH, decision.action)
        assertTrue(decision.customMovement)
        assertFalse(decision.surface)
    }

    @Test
    fun `相位接近进入绕后预算距离后切换侧向路线`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(cloakReady = false, targetDistance = 6000f), 0.2f)
        val decision = planner.decide(
            situation().copy(phased = true, phaseActiveTime = 2f, targetDistance = 1000f),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.customMovement)
        assertFalse(decision.surface)
    }

    @Test
    fun `相位接近已能射击但仍先完成正面绕后`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(cloakReady = false, targetDistance = 6000f), 0.2f)
        val approaching = planner.decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 2f,
                targetDistance = 1600f,
                attack = situation().attack.copy(inRange = true),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.APPROACH, approaching.action)
        assertTrue(approaching.customMovement)
        assertFalse(approaching.surface)

        val flanking = planner.decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 2.2f,
                targetDistance = 1000f,
                attack = situation().attack.copy(inRange = true),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, flanking.action)
        assertFalse(flanking.surface)
    }

    @Test
    fun `恢复方式选择相位并保留费用理由`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                targetDistance = 800f,
                attack = situation().attack.copy(inRange = true, canFire = false),
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_FLUX, phaseFluxDuration = 1.5f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.RECOVER, decision.action)
        assertEquals(PhaseRecoverMode.PHASE_FLUX, decision.recoverMode)
        assertTrue(decision.dive)
    }

    @Test
    fun `没有相位收益时选择自然恢复而非下潜`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                attack = situation().attack.copy(canFire = false),
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.NATURAL, naturalDuration = 3f),
            ),
            0.2f,
        )
        assertEquals(PhaseRecoverMode.NATURAL, decision.recoverMode)
        assertFalse(decision.dive)
    }

    @Test
    fun `恢复完成后不会停留在相位等待`() {
        val planner = PhasePlanner()
        planner.decide(
            situation().copy(
                attack = situation().attack.copy(canFire = false),
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_FLUX, phaseFluxDuration = 1f),
            ),
            0.2f,
        )
        val decision = planner.decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 1.2f,
                attack = situation().attack.copy(inRange = true),
            ),
            0.2f,
        )
        assertTrue(decision.surface)
    }

    @Test
    fun `预算内致命危险保持紧急侧移而不强制上浮`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 3f,
                danger = PhaseDanger(true, true, true, 5000f, 0f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.EVADE, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `路线失败后有冷却且不会无限重启同一绕后`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(routeFailed = true), 0.2f)
        val decision = planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        assertEquals(PhaseAction.APPROACH, decision.action)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `低威胁赶路使用相位但接敌后重新决策`() {
        val planner = PhasePlanner()
        val travel = planner.decide(
            situation().copy(
                targetId = null,
                enemyNearby = false,
                travelDestDistance = 4000f,
            ),
            0.2f,
        )
        assertEquals(PhaseAction.TRAVEL, travel.action)
        assertTrue(travel.dive)

        val contact = planner.decide(
            situation().copy(phased = true, phaseActiveTime = 2f),
            0.2f,
        )
        assertFalse(contact.action == PhaseAction.TRAVEL && contact.surface.not())
    }

    @Test
    fun `紧急威胁解除后保留一秒确认期再上浮`() {
        val planner = PhasePlanner()
        val threatened = situation().copy(
            phased = true,
            attack = situation().attack.copy(inRange = true),
            danger = PhaseDanger(false, true, true, 1000f, 0f),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)
        repeat(4) {
            val waiting = planner.decide(threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f)), 0.2f)
            assertEquals(PhaseAction.EVADE, waiting.action)
            assertFalse(waiting.surface)
        }
        val cleared = planner.decide(threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f)), 0.2f)
        assertTrue(cleared.surface)
    }

    @Test
    fun `紧急威胁解除但仍在射程外时保持相位接近`() {
        val planner = PhasePlanner()
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            danger = PhaseDanger(false, true, true, 1000f, 0f),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)
        val clear = threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
        repeat(4) { planner.decide(clear, 0.2f) }

        val decision = planner.decide(clear, 0.2f)

        assertEquals(PhaseAction.APPROACH, decision.action)
        assertFalse(decision.surface)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `已绑定攻击仍存活时预测伤害下降不会提前结束规避`() {
        val planner = PhasePlanner()
        val threat = Any()
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            attack = situation().attack.copy(inRange = true),
            danger = PhaseDanger(false, true, true, 1000f, 0f,
                threatKeys = setOf(threat), activeThreatKeys = setOf(threat)),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)
        val temporarilySafe = threatened.copy(
            danger = PhaseDanger(false, false, false, 0f, 0f, activeThreatKeys = setOf(threat)),
        )
        repeat(6) {
            val waiting = planner.decide(temporarilySafe, 0.2f)
            assertEquals(PhaseAction.EVADE, waiting.action)
            assertFalse(waiting.surface)
        }
        val cleared = temporarilySafe.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
        repeat(4) { assertFalse(planner.decide(cleared, 0.2f).surface) }
        assertTrue(planner.decide(cleared, 0.2f).surface)
    }

    @Test
    fun `威胁解除确认使用战场秒而不是相位本地秒`() {
        val planner = PhasePlanner()
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            attack = situation().attack.copy(inRange = true),
            danger = PhaseDanger(false, true, true, 1000f, 0f),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.6f, 0.2f).action)
        val cleared = threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
        repeat(4) { assertFalse(planner.decide(cleared, 0.6f, 0.2f).surface) }
        assertTrue(planner.decide(cleared, 0.6f, 0.2f).surface)
    }

    @Test
    fun `低血量时致命威胁优先于友军接盘代价`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                hull = 1000f,
                danger = PhaseDanger(true, true, true, 1500f, 5000f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.EVADE, decision.action)
        assertTrue(decision.dive)
    }

    @Test
    fun `绕后单周期预算耗尽后保留路线并在冷却后继续`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val surfacing = planner.decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 4f,
                hardFlux = 3500f,
                routeActive = true,
                routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            ),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, surfacing.action)
        assertTrue(surfacing.surface)
        val sideEntry = planner.decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 4f,
                hardFlux = 3500f,
                routeActive = true,
                routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.SIDE_ENTRY,
            ),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, sideEntry.action)
        assertFalse(sideEntry.surface)
        assertTrue(sideEntry.customMovement)
        val waiting = planner.decide(
            situation().copy(
                cloakReady = false,
                routeActive = true,
                hardFlux = 3500f,
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.NATURAL, naturalDuration = 2f),
            ),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, waiting.action)
        assertFalse(waiting.dive)
        assertFalse(waiting.customMovement)
        val resumed = planner.decide(
            situation().copy(routeActive = true, inTargetRearArc = true, targetFrontCoverage = 0),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, resumed.action)
        assertTrue(resumed.dive)
        assertTrue(resumed.customMovement)
    }

    @Test
    fun `绕后恢复资格扣除下一次下潜激活费`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val waiting = planner.decide(
            situation().copy(
                targetDistance = 1000f,
                routeActive = true,
                hardFlux = 1200f,
                phaseActivationCost = 400f,
            ),
            0.2f,
        )
        assertEquals(PhaseAction.FLANK, waiting.action)
        assertFalse(waiting.dive)

        val resumed = planner.decide(
            situation().copy(
                targetDistance = 1000f,
                routeActive = true,
                hardFlux = 200f,
                phaseActivationCost = 400f,
            ),
            0.2f,
        )
        assertTrue(resumed.dive)
        assertTrue(resumed.customMovement)
    }

    @Test
    fun `紧急规避结束后恢复被打断的绕后路线`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            targetDistance = 800f,
            routeActive = true,
            attack = situation().attack.copy(inRange = true),
            danger = PhaseDanger(true, true, true, 5000f, 0f),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)
        val clear = threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
        repeat(4) { planner.decide(clear, 0.2f) }
        val decision = planner.decide(clear, 0.2f)
        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.customMovement)
        assertFalse(decision.surface)
    }

    @Test
    fun `紧急规避后长侧移段未恢复到下潜资格时先上浮`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val threatened = situation().copy(
            phased = true,
            phaseActiveTime = 2f,
            targetDistance = 800f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            attack = situation().attack.copy(inRange = true),
            danger = PhaseDanger(true, true, true, 5000f, 0f),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)

        val clear = threatened.copy(
            hardFlux = 2200f,
            danger = PhaseDanger(false, false, false, 0f, 0f),
        )
        repeat(4) { planner.decide(clear, 0.2f) }
        val decision = planner.decide(clear, 0.2f)

        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.surface)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `紧急规避结束后恢复没有路线的相位恢复`() {
        val planner = PhasePlanner()
        val recovering = situation().copy(
            attack = situation().attack.copy(canFire = false),
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_FLUX, phaseFluxDuration = 2f),
        )
        planner.decide(recovering, 0.2f)
        val active = recovering.copy(phased = true, phaseActiveTime = 1f,
            recovery = recovering.recovery.copy(execution = runningRecovery(PhaseRecoverMode.PHASE_FLUX)))
        val waiting = planner.decide(active, 0.2f)
        assertEquals(PhaseRecoverMode.PHASE_FLUX, waiting.recoverMode)
        val threatened = active.copy(
            danger = PhaseDanger(true, true, true, 5000f, 0f),
        )
        assertEquals(PhaseAction.EVADE, planner.decide(threatened, 0.2f).action)
        val clear = threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
        repeat(4) { planner.decide(clear, 0.2f) }
        val decision = planner.decide(clear, 0.2f)
        assertEquals(PhaseAction.RECOVER, decision.action)
        assertEquals(PhaseRecoverMode.PHASE_FLUX, decision.recoverMode)
        assertFalse(decision.surface)
    }

    @Test
    fun `非相位恢复被规避打断后上浮仍保留原恢复方式`() {
        val planner = PhasePlanner()
        val recovering = situation().copy(
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.VENT, ventDuration = 4f, ventSafe = true),
            routeActive = true,
        )
        planner.decide(recovering, 0.2f)
        val threatened = recovering.copy(
            phased = true,
            phaseActiveTime = 1f,
            danger = PhaseDanger(true, true, true, 5000f, 0f),
        )
        planner.decide(threatened, 0.2f)
        val clear = threatened.copy(danger = PhaseDanger(false, false, false, 0f, 0f))
        repeat(4) { planner.decide(clear, 0.2f) }
        val decision = planner.decide(clear, 0.2f)
        assertEquals(PhaseAction.RECOVER, decision.action)
        assertEquals(PhaseRecoverMode.VENT, decision.recoverMode)
        assertTrue(decision.surface)
    }

    @Test
    fun `持续光束不能无限推迟无需恢复的已承诺绕后`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val firing = situation().copy(
            targetDistance = 1000f,
            routeActive = true,
            routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
            attack = situation().attack.copy(inRange = true, effectiveOutput = true),
        )
        repeat(14) {
            assertEquals(PhaseAction.ATTACK, planner.decide(firing, 0.2f).action)
        }

        val decision = planner.decide(firing, 0.2f)

        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.dive)
        assertTrue(decision.customMovement)
    }

    @Test
    fun `持续有效输出只获得有限恢复宽限窗口`() {
        val planner = PhasePlanner()
        val firing = situation().copy(
            attack = situation().attack.copy(inRange = true, effectiveOutput = true),
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_FLUX, phaseFluxDuration = 1f),
        )
        repeat(14) {
            assertEquals(PhaseAction.ATTACK, planner.decide(firing, 0.2f).action)
        }
        assertEquals(PhaseAction.RECOVER, planner.decide(firing, 0.2f).action)
    }

    @Test
    fun `相位恢复只在重新评估满足完整预算后结束`() {
        val planner = PhasePlanner()
        val recovering = situation().copy(
            attack = situation().attack.copy(canFire = false),
            recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.PHASE_FLUX, phaseFluxDuration = 2f),
        )
        planner.decide(recovering, 0.2f)
        val active = recovering.copy(phased = true, phaseActiveTime = 1f, softFlux = 500f,
            recovery = recovering.recovery.copy(execution = runningRecovery(PhaseRecoverMode.PHASE_FLUX)))
        val waiting = planner.decide(active, 0.2f)
        assertEquals(PhaseAction.RECOVER, waiting.action)
        assertFalse(waiting.surface)
        val finished = planner.decide(
            active.copy(phaseActiveTime = 2.2f, recovery = PhaseRecovery(required = false,
                execution = active.recovery.execution.copy(
                    state = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryState.EXITING,
                    reason = cn.kasuminova.astd.combat.shipsystems.base.PhaseRecoveryReason.OBJECTIVES_MET,
                    awaitingSurface = true,
                ))),
            0.2f,
        )
        assertTrue(finished.surface)
    }

    @Test
    fun `友军接盘代价较高时非濒危不转移伤害`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                danger = PhaseDanger(false, true, true, 600f, 900f),
            ),
            0.2f,
        )
        assertFalse(decision.dive)
    }

    @Test
    fun `绕后周期间按短时威胁主动耗散硬辐能`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val waiting = planner.decide(
            situation().copy(
                routeActive = true,
                hardFlux = 2000f,
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.VENT, ventDuration = 4f, ventSafe = true, naturalDuration = 8f),
            ),
            0.2f,
        )

        assertEquals(PhaseAction.FLANK, waiting.action)
        assertTrue(waiting.vent)
        assertFalse(waiting.suppressVent)
        assertFalse(waiting.dive)
    }

    @Test
    fun `长侧移到达跨周期线圈预算后立即上浮`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val decision = planner.decide(
            situation().copy(
                phased = true,
                phaseActiveTime = 2f,
                routeActive = true,
                routeStage = cn.kasuminova.astd.combat.shipsystems.base.PhaseRouteStage.LATERAL,
                hardFlux = 1600f,
            ),
            0.2f,
        )

        assertEquals(PhaseAction.FLANK, decision.action)
        assertTrue(decision.surface)
        assertFalse(decision.customMovement)
    }

    @Test
    fun `绕后周期间迫近高伤威胁禁止主动耗散`() {
        val planner = PhasePlanner()
        planner.decide(situation().copy(targetDistance = 1000f), 0.2f)
        val waiting = planner.decide(
            situation().copy(
                cloakReady = false,
                routeActive = true,
                hardFlux = 2000f,
                recovery = PhaseRecovery(required = true, mode = PhaseRecoverMode.VENT, ventDuration = 4f, ventSafe = true),
                danger = PhaseDanger(false, true, true, 600f, 0f),
            ),
            0.2f,
        )

        assertEquals(PhaseAction.FLANK, waiting.action)
        assertFalse(waiting.vent)
        assertTrue(waiting.suppressVent)
    }

    @Test
    fun `相位冷却且正在有效输出时保护输出而不另建绕后`() {
        val decision = PhasePlanner().decide(
            situation().copy(
                cloakReady = false,
                targetDistance = 800f,
                attack = situation().attack.copy(inRange = true, effectiveOutput = true),
            ),
            0.2f,
        )

        assertEquals(PhaseAction.ATTACK, decision.action)
        assertFalse(decision.dive)
        assertFalse(decision.customMovement)
    }
}
