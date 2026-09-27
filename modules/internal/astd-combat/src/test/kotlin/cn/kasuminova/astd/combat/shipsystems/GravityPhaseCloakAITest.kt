package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakAI.Companion.PhaseOrder
import cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakAI.Companion.PhaseSituation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引力相位 AI 决策核心 [GravityPhaseCloakAI.decide] 的规则覆盖：
 * 强制上浮（辐能闸/时长闸）、致命豁免、主动上浮（无威胁/战术/输出窗口）、
 * 下潜（紧急/错峰/系统激活闸/辐能压力/威胁/装填/撤退）。
 */
class GravityPhaseCloakAITest {

    /** 基准快照：相位中、低辐能、无来袭、有交战对象、系统未就绪、武器半数可输出。 */
    private fun phasedSituation() = PhaseSituation(
        phased = true,
        phaseActiveTime = 3f,
        timeSinceUnphase = 0f,
        hardFluxLevel = 0.2f,
        fluxLevel = 0.3f,
        incomingSoonDamage = 0f,
        incomingNearDamage = 0f,
        maxHull = 5000f,
        engagedEnemyNear = true,
        systemReady = false,
        systemActive = false,
        weaponsReadyFrac = 0.5f,
        retreating = false,
        cloakReady = false,
    )

    /** 基准快照：未相位、斗篷就绪、错峰已过、无来袭、有交战对象、武器全就绪。 */
    private fun unphasedSituation() = PhaseSituation(
        phased = false,
        phaseActiveTime = 0f,
        timeSinceUnphase = 10f,
        hardFluxLevel = 0.2f,
        fluxLevel = 0.3f,
        incomingSoonDamage = 0f,
        incomingNearDamage = 0f,
        maxHull = 5000f,
        engagedEnemyNear = true,
        systemReady = false,
        systemActive = false,
        weaponsReadyFrac = 1f,
        retreating = false,
        cloakReady = true,
    )

    @Test
    fun `硬辐能达闸强制上浮`() {
        val s = phasedSituation().copy(hardFluxLevel = GravityPhaseCloakAI.SURFACE_HARD_FLUX)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `相位超时强制上浮`() {
        val s = phasedSituation().copy(phaseActiveTime = GravityPhaseCloakAI.MAX_PHASE_TIME_SEC)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `致命来袭且辐能有余量时强制上浮被豁免`() {
        val lethal = 5000f * GravityPhaseCloakAI.LETHAL_SOON_HULL_FRACTION
        val byFlux = phasedSituation().copy(
            hardFluxLevel = 0.6f,
            incomingSoonDamage = lethal,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(byFlux))

        val byTime = phasedSituation().copy(
            phaseActiveTime = GravityPhaseCloakAI.MAX_PHASE_TIME_SEC + 1f,
            incomingSoonDamage = lethal,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(byTime))
    }

    @Test
    fun `致命豁免在硬辐能顶格后失效`() {
        val s = phasedSituation().copy(
            hardFluxLevel = GravityPhaseCloakAI.HOLD_MAX_HARD_FLUX,
            incomingSoonDamage = 5000f,
        )
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `反闪烁 相位不足最短时长不主动上浮`() {
        val s = phasedSituation().copy(
            phaseActiveTime = GravityPhaseCloakAI.MIN_PHASE_TIME_SEC - 0.1f,
            systemReady = true,
            weaponsReadyFrac = 1f,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `无交战对象且来袭轻微时上浮`() {
        val s = phasedSituation().copy(engagedEnemyNear = false)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `无交战对象但紧急窗口有来袭时不上浮`() {
        // 交战圈外发射的高速弹：无威胁上浮只查 near 窗口，soon 闸必须先行拦下
        val s = phasedSituation().copy(
            engagedEnemyNear = false,
            incomingSoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `无交战对象但撤退赶路且硬辐能低时保持相位`() {
        val s = phasedSituation().copy(
            engagedEnemyNear = false,
            retreating = true,
            hardFluxLevel = 0.2f,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `攻击系统就绪且有交战对象时战术上浮`() {
        val s = phasedSituation().copy(systemReady = true)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `即将受击时不主动上浮`() {
        val s = phasedSituation().copy(
            systemReady = true,
            incomingSoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `武器就绪且来袭可控时输出窗口上浮`() {
        val s = phasedSituation().copy(
            weaponsReadyFrac = GravityPhaseCloakAI.WEAPONS_READY_SURFACE_FRAC,
        )
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `紧急来袭绕过错峰直接下潜`() {
        val s = unphasedSituation().copy(
            timeSinceUnphase = 0f,
            incomingSoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `斗篷未就绪或错峰期内不做常规下潜`() {
        val notReady = unphasedSituation().copy(
            cloakReady = false,
            incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(notReady))

        val staggered = unphasedSituation().copy(
            timeSinceUnphase = GravityPhaseCloakAI.MIN_UNPHASE_TIME_SEC - 0.1f,
            incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(staggered))
    }

    @Test
    fun `攻击系统激活中不主动下潜`() {
        val s = unphasedSituation().copy(
            systemActive = true,
            fluxLevel = GravityPhaseCloakAI.DIVE_FLUX_LEVEL,
            incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `辐能压力下潜需要硬辐能余量`() {
        val allowed = unphasedSituation().copy(fluxLevel = GravityPhaseCloakAI.DIVE_FLUX_LEVEL)
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(allowed))

        val blocked = allowed.copy(hardFluxLevel = GravityPhaseCloakAI.DIVE_HARD_FLUX_MAX)
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(blocked))
    }

    @Test
    fun `威胁窗口伤害达阈值下潜`() {
        val s = unphasedSituation().copy(
            incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `武器装填中下潜且受硬辐能上限约束`() {
        val s = unphasedSituation().copy(
            weaponsReadyFrac = GravityPhaseCloakAI.RECHARGE_DIVE_WEAPONS_FRAC - 0.1f,
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))

        val blocked = s.copy(hardFluxLevel = GravityPhaseCloakAI.RECHARGE_DIVE_HARD_FLUX_MAX)
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(blocked))
    }

    @Test
    fun `撤退且硬辐能低时下潜赶路`() {
        val s = unphasedSituation().copy(
            retreating = true,
            engagedEnemyNear = false,
            weaponsReadyFrac = 1f,
            hardFluxLevel = 0.1f,
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `平稳交战且一切就绪时保持非相位`() {
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(unphasedSituation()))
    }

    @Test
    fun `相位中无新条件时保持相位`() {
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(phasedSituation()))
    }
}
