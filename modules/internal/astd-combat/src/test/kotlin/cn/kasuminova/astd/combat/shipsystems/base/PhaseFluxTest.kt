package cn.kasuminova.astd.combat.shipsystems.base

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseFluxTest {

    private fun input(
        currFlux: Float = 0f,
        hardFlux: Float = 0f,
        maxFlux: Float = 10000f,
        naturalDissipationPerSec: Float = 200f,
        timeMult: Float = 3f,
        activationCost: Float = 100f,
        maintenanceCostPerSec: Float = 50f,
        phaseState: PhaseState = PhaseState.IDLE,
        cooldownRemaining: Float = 0f,
    ) = PhaseInputSnapshot(
        currFlux = currFlux,
        hardFlux = hardFlux,
        maxFlux = maxFlux,
        softFlux = currFlux - hardFlux,
        naturalDissipationPerSec = naturalDissipationPerSec,
        timeMult = timeMult,
        activationCost = activationCost,
        maintenanceCostPerSec = maintenanceCostPerSec,
        phaseState = phaseState,
        cooldownRemaining = cooldownRemaining,
        qBurst = 0f,
        fSafe = 500f,
    )

    @Test
    fun `稳态软辐能清除只取决于散耗`() {
        val duration = 5f
        val dissipation = 200f
        val softCleared = PhaseSteadyState.softFluxClearedStable(duration, dissipation)
        assertEquals(duration * dissipation, softCleared, 1e-3f)
        for (maintenance in listOf(50f, 200f, 300f)) {
            val initial = input(currFlux = 3000f, phaseState = PhaseState.ACTIVE)
            val result = PhaseSimulator.simulate(initial, listOf(PhaseEvent.PhaseActive(duration, maintenance)))
            assertTrue(result.accepted)
            assertEquals(softCleared, result.softFluxCleared, 0.01f)
            assertEquals(duration * maintenance, result.hardFluxAtEnd, 0.01f)
            assertEquals(initial.currFlux + duration * maintenance - softCleared,
                result.hardFluxAtEnd + result.softFluxAtEnd, 0.01f)
        }
    }

    @Test
    fun `硬辐能增量仅取决于维护费率`() {
        assertEquals(500f, PhaseSteadyState.hardFluxDeltaStable(10f, 50f), 1e-3f)
    }

    @Test
    fun `requiredSoftFlux包含激活费且不等于缺口`() {
        val gap = 1000f
        val activation = 200f
        val dissipation = 400f
        val maintenance = 100f
        val x = PhaseSteadyState.requiredSoftFlux(gap, activation, dissipation, maintenance)
        assertEquals((gap + activation) / (1f - maintenance / dissipation), x, 1e-3f)
        assertTrue(x > gap)
    }

    @Test
    fun `requiredSoftFlux在无法净恢复时不可行`() {
        assertEquals(Float.MAX_VALUE, PhaseSteadyState.requiredSoftFlux(1000f, 100f, 200f, 200f))
        assertEquals(Float.MAX_VALUE, PhaseSteadyState.requiredSoftFlux(1000f, 100f, 200f, 250f))
        assertEquals(Float.MAX_VALUE, PhaseSteadyState.requiredSoftFlux(1000f, 100f, 0f, 0f))
    }

    @Test
    fun `相位净恢复只有比自然散耗更快才有优势`() {
        assertTrue(PhaseSteadyState.phaseNetsBetter(200f, 50f, 3f))
        assertFalse(PhaseSteadyState.phaseNetsBetter(200f, 50f, 1f))
        assertFalse(PhaseSteadyState.phaseNetsBetter(200f, 150f, 3f))
        assertFalse(PhaseSteadyState.phaseNetsBetter(200f, 200f, 3f))
        assertFalse(PhaseSteadyState.phaseNetsBetter(200f, 300f, 3f))
    }

    @Test
    fun `敌舰耗散时间按真实当前辐能和耗散率计算`() {
        assertEquals(10f, EnemyVentCalc.remainingLocalVentSec(4000f, 200f, 1f), 1e-4f)
        assertEquals(5f, EnemyVentCalc.remainingLocalVentSec(4000f, 200f, 2f), 1e-4f)
        assertEquals(0f, EnemyVentCalc.remainingLocalVentSec(0f, 200f, 1f), 1e-4f)
        assertEquals(Float.MAX_VALUE, EnemyVentCalc.remainingLocalVentSec(1000f, 0f, 1f))
        assertEquals(Float.MAX_VALUE, EnemyVentCalc.remainingLocalVentSec(1000f, 200f, 0f))
    }

    @Test
    fun `本地时间按双方各自倍率换算`() {
        assertEquals(5f, EnemyVentCalc.localToBattleTime(15f, 3f), 1e-4f)
        assertEquals(7.5f, EnemyVentCalc.ourLocalToBattleTime(15f, 2f), 1e-4f)
        assertEquals(Float.MAX_VALUE, EnemyVentCalc.localToBattleTime(10f, 0f))
        assertEquals(Float.MAX_VALUE, EnemyVentCalc.ourLocalToBattleTime(10f, 0f))
    }

    @Test
    fun `空事件保留初始状态`() {
        val result = PhaseSimulator.simulate(input(currFlux = 1000f, hardFlux = 200f), emptyList())
        assertTrue(result.accepted)
        assertEquals(1000f, result.peakFlux)
        assertEquals(200f, result.hardFluxAtEnd)
        assertEquals(800f, result.softFluxAtEnd)
        assertEquals(0f, result.localDurationSec)
        assertEquals(PhaseState.IDLE, result.phaseStateAtEnd)
    }

    @Test
    fun `完整相位序列积分动态充入充出倍率`() {
        val initial = input(currFlux = 3000f)
        val result = PhaseSimulator.simulate(initial, listOf(
            PhaseEvent.PhaseIn(100f, 0.5f),
            PhaseEvent.PhaseActive(5f, 50f),
            PhaseEvent.PhaseOut(0.5f),
        ))
        assertTrue(result.accepted)
        assertEquals(6f, result.localDurationSec, 1e-3f)
        assertEquals(5f / 3f + 0.5f * ln(3f), result.battleDurationSec, 1e-3f)
        assertEquals(6f * initial.naturalDissipationPerSec, result.softFluxCleared, 0.01f)
        assertEquals(100f + 5.5f * 50f, result.hardFluxAtEnd, 0.01f)
        assertEquals(PhaseState.IDLE, result.phaseStateAtEnd)
    }

    @Test
    fun `充入和稳定阶段都逐步支付后检查峰值再散耗`() {
        for (state in listOf(PhaseState.IDLE, PhaseState.ACTIVE)) {
            val initial = input(currFlux = 900f, maxFlux = 1000f,
                naturalDissipationPerSec = 300f, maintenanceCostPerSec = 200f, phaseState = state)
            val event = if (state == PhaseState.IDLE) PhaseEvent.PhaseIn(50f, 0.5f)
                else PhaseEvent.PhaseActive(0.5f, 200f)
            val result = PhaseSimulator.simulate(initial, listOf(event))
            val activation = if (state == PhaseState.IDLE) 50f else 0f
            assertTrue(result.accepted)
            assertEquals(initial.currFlux + activation + 0.1f * 200f, result.peakFlux, 0.001f)
            assertEquals(activation + 0.5f * 200f, result.hardFluxAtEnd, 0.001f)
            assertEquals(initial.softFlux - 0.5f * 300f, result.softFluxAtEnd, 0.001f)
        }
    }

    @Test
    fun `充入和稳定阶段不能用同一步散耗抵消支付越界`() {
        for (state in listOf(PhaseState.IDLE, PhaseState.ACTIVE)) {
            val initial = input(currFlux = 990f, maxFlux = 1000f,
                naturalDissipationPerSec = 1000f, maintenanceCostPerSec = 200f, phaseState = state)
            val event = if (state == PhaseState.IDLE) PhaseEvent.PhaseIn(0f, 0.5f)
                else PhaseEvent.PhaseActive(0.5f, 200f)
            val result = PhaseSimulator.simulate(initial, listOf(event))
            assertFalse(result.accepted)
            assertEquals(1010f, result.peakFlux, 0.001f)
            assertEquals(0f, result.softFluxCleared)
            assertEquals(0f, result.localDurationSec)
        }
    }

    @Test
    fun `激活费立即增加硬辐能且不能用后续散耗救回`() {
        val result = PhaseSimulator.simulate(input(currFlux = 990f, maxFlux = 1000f),
            listOf(PhaseEvent.PhaseIn(20f)))
        assertFalse(result.accepted)
        assertEquals(20f, result.hardFluxAtEnd)
        assertEquals(1010f, result.peakFlux)
        assertEquals(0f, result.localDurationSec)
    }

    @Test
    fun `硬辐能预算独立于总容量检查`() {
        val initial = input(currFlux = 965f, hardFlux = 965f, maxFlux = 1000f)
        val result = PhaseSimulator.simulate(initial, listOf(PhaseEvent.PhaseIn(10f)))
        assertFalse(result.accepted)
        assertTrue(result.peakFlux < initial.maxFlux)
    }

    @Test
    fun `软辐能清空后维护仍继续且OUT不散耗硬辐能`() {
        val initial = input(currFlux = 520f, hardFlux = 500f, phaseState = PhaseState.ACTIVE)
        val result = PhaseSimulator.simulate(initial, listOf(
            PhaseEvent.PhaseActive(4f, 50f), PhaseEvent.PhaseOut(0.5f),
        ))
        assertTrue(result.accepted)
        assertEquals(500f + 4f * 50f, result.hardFluxAtEnd, 0.01f)
        assertEquals(20f, result.softFluxCleared, 0.001f)
        assertEquals(0f, result.softFluxAtEnd)
    }

    @Test
    fun `武器支付只增加软辐能且可以填入硬预算以上的总容量`() {
        val result = PhaseSimulator.simulate(input(currFlux = 960f, hardFlux = 960f, maxFlux = 1000f),
            listOf(PhaseEvent.WeaponFire(30f)))
        assertTrue(result.accepted)
        assertEquals(990f, result.peakFlux)
        assertEquals(960f, result.hardFluxAtEnd)
        assertEquals(30f, result.softFluxAtEnd)
    }

    @Test
    fun `武器支付超总容量立即拒绝`() {
        val result = PhaseSimulator.simulate(input(currFlux = 950f, maxFlux = 1000f), listOf(
            PhaseEvent.WeaponFire(60f), PhaseEvent.Dissipate(100f),
        ))
        assertFalse(result.accepted)
        assertEquals(1010f, result.peakFlux)
        assertEquals(0f, result.hardFluxAtEnd)
        assertEquals(0f, result.softFluxCleared)
    }

    @Test
    fun `非相位耗散先清软辐能再清硬辐能`() {
        val result = PhaseSimulator.simulate(input(currFlux = 2000f, hardFlux = 500f), listOf(
            PhaseEvent.Dissipate(800f), PhaseEvent.Dissipate(900f),
        ))
        assertTrue(result.accepted)
        assertEquals(0f, result.softFluxAtEnd)
        assertEquals(300f, result.hardFluxAtEnd)
        assertEquals(1500f, result.softFluxCleared)
    }

    @Test
    fun `已有IN仅支付剩余维护且积分剩余倍率区间`() {
        val initial = input(currFlux = 1000f, hardFlux = 100f, phaseState = PhaseState.IN)
            .copy(effectLevel = 0.5f, transitionRemainingLocalSec = 0.25f)
        val result = PhaseSimulator.simulate(initial, listOf(PhaseEvent.ContinueTransition))
        assertTrue(result.accepted)
        assertEquals(100f + 0.25f * 50f, result.hardFluxAtEnd, 0.001f)
        assertEquals(0.25f, result.localDurationSec, 0.001f)
        assertEquals(0.25f * ln(3f / 2f), result.battleDurationSec, 0.001f)
        assertEquals(PhaseState.ACTIVE, result.phaseStateAtEnd)
        assertEquals(1f, result.effectLevelAtEnd)
    }

    @Test
    fun `已有OUT只完成剩余充出且进入真实冷却`() {
        val initial = input(currFlux = 1000f, hardFlux = 1000f, phaseState = PhaseState.OUT)
            .copy(effectLevel = 0.5f, transitionRemainingLocalSec = 0.25f,
                phaseCooldownDurationLocalSec = 2f)
        val result = PhaseSimulator.simulate(initial, listOf(PhaseEvent.ContinueTransition))
        assertTrue(result.accepted)
        assertEquals(initial.hardFlux, result.hardFluxAtEnd)
        assertEquals(0.25f * ln(2f), result.battleDurationSec, 0.001f)
        assertEquals(PhaseState.COOLDOWN, result.phaseStateAtEnd)
        assertEquals(2f, result.cooldownRemainingAtEnd)
    }

    @Test
    fun `过渡状态缺少进度时不能猜测为完整过渡`() {
        for (state in listOf(PhaseState.IN, PhaseState.OUT)) {
            assertFalse(PhaseSimulator.simulate(input(phaseState = state),
                listOf(PhaseEvent.ContinueTransition)).accepted)
        }
    }

    @Test
    fun `非ACTIVE状态不能直接追加ACTIVE`() {
        for (state in listOf(PhaseState.IDLE, PhaseState.IN, PhaseState.OUT, PhaseState.COOLDOWN)) {
            val initial = input(phaseState = state, cooldownRemaining = 1f)
                .copy(effectLevel = if (state == PhaseState.IN || state == PhaseState.OUT) 0.5f else 0f,
                    transitionRemainingLocalSec = 0.25f)
            assertFalse(PhaseSimulator.simulate(initial, listOf(PhaseEvent.PhaseActive(1f, 50f))).accepted)
        }
    }

    @Test
    fun `冷却必须等待结束才能重新支付激活费`() {
        val initial = input(currFlux = 1000f, hardFlux = 1000f,
            phaseState = PhaseState.COOLDOWN, cooldownRemaining = 2f)
        assertFalse(PhaseSimulator.simulate(initial, listOf(PhaseEvent.PhaseIn(100f))).accepted)
        assertFalse(PhaseSimulator.simulate(initial, listOf(
            PhaseEvent.Wait(1f), PhaseEvent.PhaseIn(100f),
        )).accepted)
        val waiting = PhaseSimulator.simulate(initial, listOf(PhaseEvent.Wait(1f)))
        assertEquals(1f, waiting.cooldownRemainingAtEnd)
        assertEquals(PhaseState.COOLDOWN, waiting.phaseStateAtEnd)
        val result = PhaseSimulator.simulate(initial, listOf(
            PhaseEvent.Wait(2f), PhaseEvent.PhaseIn(100f, 0.5f),
        ))
        assertTrue(result.accepted)
        assertEquals(1000f - 2f * 200f + 100f + 0.5f * 50f, result.hardFluxAtEnd, 0.001f)
        assertEquals(2f + 0.25f * ln(3f), result.battleDurationSec, 0.001f)
    }

    @Test
    fun `OUT必须完成冷却并重新激活才能ACTIVE`() {
        val initial = input(currFlux = 1000f, hardFlux = 1000f, phaseState = PhaseState.OUT)
            .copy(effectLevel = 0.5f, transitionRemainingLocalSec = 0.25f,
                phaseCooldownDurationLocalSec = 1f)
        assertFalse(PhaseSimulator.simulate(initial, listOf(
            PhaseEvent.ContinueTransition, PhaseEvent.PhaseActive(1f, 50f),
        )).accepted)
        val result = PhaseSimulator.simulate(initial, listOf(
            PhaseEvent.ContinueTransition, PhaseEvent.Wait(1f), PhaseEvent.PhaseIn(100f),
            PhaseEvent.PhaseActive(1f, 50f), PhaseEvent.PhaseOut(),
        ))
        assertTrue(result.accepted)
        assertEquals(1000f - 200f + 100f + 1.5f * 50f, result.hardFluxAtEnd, 0.001f)
        assertEquals(PhaseState.COOLDOWN, result.phaseStateAtEnd)
    }

    @Test
    fun `倍率相等及非一基础倍率均正确积分`() {
        for ((base, phased) in listOf(1f to 1f, 2f to 2f, 2f to 6f)) {
            val initial = input(timeMult = phased).copy(unphasedTimeMult = base)
            val result = PhaseSimulator.simulate(initial, listOf(
                PhaseEvent.Wait(2f), PhaseEvent.PhaseIn(0f),
                PhaseEvent.PhaseActive(1f, 0f), PhaseEvent.PhaseOut(),
            ))
            assertTrue(result.accepted)
            val transitions = if (base == phased) 1f / base else ln(phased / base) / (phased - base)
            assertEquals(2f / base + transitions + 1f / phased, result.battleDurationSec, 0.001f)
        }
    }

    @Test
    fun `退出阻塞或相位中主动耗散必须拒绝`() {
        val initial = input(currFlux = 1000f, hardFlux = 1000f, phaseState = PhaseState.ACTIVE)
        assertFalse(PhaseSimulator.simulate(initial.copy(canExitPhase = false),
            listOf(PhaseEvent.PhaseOut())).accepted)
        assertFalse(PhaseSimulator.simulate(initial, listOf(PhaseEvent.Dissipate(500f))).accepted)
        assertFalse(PhaseSimulator.simulate(initial, listOf(PhaseEvent.Wait(1f))).accepted)
    }

    @Test
    fun `失败结果保留已经完成的小步时间`() {
        val initial = input(currFlux = 950f, hardFlux = 950f, maxFlux = 1000f,
            phaseState = PhaseState.ACTIVE)
        val result = PhaseSimulator.simulate(initial, listOf(PhaseEvent.PhaseActive(1f, 100f)))
        assertFalse(result.accepted)
        assertEquals(0.2f, result.localDurationSec, 0.001f)
        assertEquals(0.2f / initial.timeMult, result.battleDurationSec, 0.001f)
        assertEquals(980f, result.peakFlux, 0.001f)
    }
}
