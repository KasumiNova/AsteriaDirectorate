package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseOrder
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRule
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PriorityPhaseTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GravityPhaseCloakAITest {

    private val toggleTree = PhaseToggleTree()

    private fun decide(s: PhaseSituation): PhaseOrder =
        toggleTree.decide(PhaseDecisionContext(s))

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
        cloakCooldownRemaining = 0f,
        softFluxLevel = 0.1f,
        weaponsDisabledFrac = 0f,
        enginesDisabledFrac = 0f,
        targetLowMobility = false,
        inTargetRearArc = false,
        weaponCoverage = 2,
        friendlyCatchDamage = 0f,
        flankIntentActive = false,
        flankIntentWindowSec = FlankAttackTree.FLANK_INTENT_SEC,
        targetDistance = Float.MAX_VALUE,
        effectiveWeaponRange = 0f,
        pressIntentActive = false,
        threatDistance = 1000f,
        unphaseUnsafe = false,
        surfaceTooClose = false,
        incomingSurfaceDamage = 0f,
        threatFrontAxisClose = false,
        rearSurfaceDistanceSafe = true,
        forcedSurfaceHoldSec = 0f,
        incomingFriendlySoonDamage = 0f,
        ventTimeSec = 10f,
        ventRateMult = 1f,
        venting = false,
        friendlyScreenAhead = false,
        travelDestDistance = Float.MAX_VALUE,
    )

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
        cloakCooldownRemaining = 0f,
        softFluxLevel = 0.1f,
        weaponsDisabledFrac = 0f,
        enginesDisabledFrac = 0f,
        targetLowMobility = false,
        inTargetRearArc = false,
        weaponCoverage = 2,
        friendlyCatchDamage = 0f,
        flankIntentActive = false,
        flankIntentWindowSec = FlankAttackTree.FLANK_INTENT_SEC,
        targetDistance = Float.MAX_VALUE,
        effectiveWeaponRange = 0f,
        pressIntentActive = false,
        threatDistance = 1000f,
        unphaseUnsafe = false,
        surfaceTooClose = false,
        incomingSurfaceDamage = 0f,
        threatFrontAxisClose = false,
        rearSurfaceDistanceSafe = true,
        forcedSurfaceHoldSec = 0f,
        incomingFriendlySoonDamage = 0f,
        ventTimeSec = 10f,
        ventRateMult = 1f,
        venting = false,
        friendlyScreenAhead = false,
        travelDestDistance = Float.MAX_VALUE,
    )

    @Test
    fun `硬辐能达闸强制上浮`() {
        val s = phasedSituation().copy(hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX)
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `上浮落点重叠不安全时按住一切上浮指令`() {
        val unsafe = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            unphaseUnsafe = true,
        )
        assertEquals(PhaseOrder.NONE, decide(unsafe))
        assertEquals(PhaseOrder.SURFACE, decide(unsafe.copy(unphaseUnsafe = false)))
    }

    @Test
    fun `相位超时强制上浮`() {
        val s = phasedSituation().copy(phaseActiveTime = PhaseToggleTree.MAX_PHASE_TIME_SEC)
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `致命来袭且辐能有余量时强制上浮被豁免`() {
        val lethal = 5000f * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION
        val byFlux = phasedSituation().copy(
            hardFluxLevel = 0.6f,
            incomingSoonDamage = lethal,
        )
        assertEquals(PhaseOrder.NONE, decide(byFlux))

        val byTime = phasedSituation().copy(
            phaseActiveTime = PhaseToggleTree.MAX_PHASE_TIME_SEC + 1f,
            incomingSoonDamage = lethal,
        )
        assertEquals(PhaseOrder.NONE, decide(byTime))
    }

    @Test
    fun `致命豁免在硬辐能顶格后失效`() {
        val s = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.HOLD_MAX_HARD_FLUX,
            incomingSoonDamage = 5000f,
        )
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `反闪烁 相位不足最短时长不主动上浮`() {
        val s = phasedSituation().copy(
            phaseActiveTime = PhaseToggleTree.MIN_PHASE_TIME_SEC - 0.1f,
            systemReady = true,
            weaponsReadyFrac = 1f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `无交战对象且来袭轻微时上浮`() {
        val s = phasedSituation().copy(engagedEnemyNear = false)
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `无交战对象但紧急窗口有来袭时不上浮`() {
        val s = phasedSituation().copy(
            engagedEnemyNear = false,
            incomingSoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `无交战对象但撤退赶路且硬辐能低时保持相位`() {
        val s = phasedSituation().copy(
            engagedEnemyNear = false,
            retreating = true,
            hardFluxLevel = 0.2f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `攻击系统就绪且有交战对象时战术上浮`() {
        val s = phasedSituation().copy(systemReady = true)
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `即将受击时不主动上浮`() {
        val s = phasedSituation().copy(
            systemReady = true,
            incomingSoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `武器就绪且来袭可控时输出窗口上浮`() {
        val s = phasedSituation().copy(
            weaponsReadyFrac = PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `贴脸时主动上浮按住等待时机`() {
        val s = phasedSituation().copy(
            weaponsReadyFrac = PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC,
            surfaceTooClose = true,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(surfaceTooClose = false)))
    }

    @Test
    fun `弹幕窗口来袭达闸时主动上浮按住`() {
        val s = phasedSituation().copy(
            weaponsReadyFrac = PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC,
            incomingSurfaceDamage = PhaseToggleTree.surfaceDangerThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals(
            PhaseOrder.SURFACE,
            decide(s.copy(incomingSurfaceDamage = 0f)),
        )
    }

    @Test
    fun `正脸火力轴上主动上浮按住`() {
        val s = phasedSituation().copy(
            weaponsReadyFrac = PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC,
            threatFrontAxisClose = true,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(threatFrontAxisClose = false)))
    }

    @Test
    fun `强制上浮暂缓达上限后不受上浮安全闸约束`() {
        val s = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            surfaceTooClose = true,
            incomingSurfaceDamage = PhaseToggleTree.surfaceDangerThreshold(5000f) * 2f,
            threatFrontAxisClose = true,
            forcedSurfaceHoldSec = PhaseToggleTree.FORCED_SURFACE_HOLD_MAX_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(s))
        assertEquals(
            PhaseOrder.NONE,
            decide(s.copy(forcedSurfaceHoldSec = 0f)),
        )
    }

    @Test
    fun `紧急来袭绕过错峰直接下潜`() {
        val s = unphasedSituation().copy(
            timeSinceUnphase = 0f,
            incomingSoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `斗篷未就绪或错峰期内不做常规下潜`() {
        val notReady = unphasedSituation().copy(
            cloakReady = false,
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, decide(notReady))

        val staggered = unphasedSituation().copy(
            timeSinceUnphase = PhaseToggleTree.MIN_UNPHASE_TIME_SEC - 0.1f,
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, decide(staggered))
    }

    @Test
    fun `攻击系统激活中不主动下潜`() {
        val s = unphasedSituation().copy(
            systemActive = true,
            fluxLevel = PhaseToggleTree.DIVE_FLUX_LEVEL,
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `辐能压力下潜需要硬辐能余量`() {
        val allowed = unphasedSituation().copy(fluxLevel = PhaseToggleTree.DIVE_FLUX_LEVEL)
        assertEquals(PhaseOrder.DIVE, decide(allowed))

        val blocked = allowed.copy(hardFluxLevel = PhaseToggleTree.DIVE_HARD_FLUX_MAX)
        assertEquals(PhaseOrder.NONE, decide(blocked))
    }

    @Test
    fun `威胁窗口伤害达阈值下潜`() {
        val s = unphasedSituation().copy(
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f),
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `武器装填中下潜且受硬辐能上限约束`() {
        val s = unphasedSituation().copy(
            weaponsReadyFrac = PhaseToggleTree.RECHARGE_DIVE_WEAPONS_FRAC - 0.1f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))

        val blocked = s.copy(hardFluxLevel = PhaseToggleTree.RECHARGE_DIVE_HARD_FLUX_MAX)
        assertEquals(PhaseOrder.NONE, decide(blocked))
    }

    @Test
    fun `撤退且硬辐能低时下潜赶路`() {
        val s = unphasedSituation().copy(
            retreating = true,
            engagedEnemyNear = false,
            weaponsReadyFrac = 1f,
            hardFluxLevel = 0.1f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `平稳交战且一切就绪时保持非相位`() {
        assertEquals(PhaseOrder.NONE, decide(unphasedSituation()))
    }

    @Test
    fun `相位中无新条件时保持相位`() {
        assertEquals(PhaseOrder.NONE, decide(phasedSituation()))
    }

    @Test
    fun `软辐可观且环境安全时耗软辐下潜`() {
        val s = unphasedSituation().copy(
            softFluxLevel = PhaseToggleTree.SOFT_FLUX_DIVE_MIN,
            fluxLevel = 0.55f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `辐能水平接近过载时压低耗软辐下潜积极性`() {
        val s = unphasedSituation().copy(
            softFluxLevel = 0.4f,
            fluxLevel = PhaseToggleTree.SOFT_FLUX_DIVE_MAX_FLUX_LEVEL,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `耗软辐下潜要求环境安全`() {
        val s = unphasedSituation().copy(
            softFluxLevel = 0.4f,
            fluxLevel = 0.55f,
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f) *
                    PhaseToggleTree.SOFT_FLUX_DIVE_SAFE_NEAR_FRAC,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `低机动目标触发绕后下潜 已在侧后则不重复下潜`() {
        val s = unphasedSituation().copy(targetLowMobility = true)
        assertEquals(PhaseOrder.DIVE, decide(s))

        val alreadyBehind = s.copy(inTargetRearArc = true)
        assertEquals(PhaseOrder.NONE, decide(alreadyBehind))
    }

    @Test
    fun `绕后下潜受武器就绪与硬辐能约束`() {
        val weaponsNotReady = unphasedSituation().copy(
            targetLowMobility = true,
            weaponsReadyFrac = FlankAttackTree.FLANK_DIVE_WEAPONS_FRAC - 0.1f,
        )
        assertEquals(PhaseOrder.NONE, decide(weaponsNotReady))

        val fluxHigh = unphasedSituation().copy(
            targetLowMobility = true,
            hardFluxLevel = FlankAttackTree.FLANK_DIVE_HARD_FLUX_MAX,
        )
        assertEquals(PhaseOrder.NONE, decide(fluxHigh))
    }

    @Test
    fun `相位中进入目标侧后死角且覆盖稀少时立即上浮`() {
        val s = phasedSituation().copy(inTargetRearArc = true, weaponCoverage = 1)
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `武器覆盖超闸时拦截主动上浮继续等死角`() {
        val s = phasedSituation().copy(
            systemReady = true,
            weaponsReadyFrac = 1f,
            weaponCoverage = PhaseToggleTree.SURFACE_COVERAGE_MAX + 1,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `强制上浮不受覆盖闸约束`() {
        val s = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            weaponCoverage = 99,
        )
        assertEquals(PhaseOrder.SURFACE, decide(s))
    }

    @Test
    fun `穿透火力将误伤友军时压制非濒危下潜`() {
        val threatened = unphasedSituation().copy(
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f),
            friendlyCatchDamage = PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN,
        )
        assertEquals(PhaseOrder.NONE, decide(threatened))

        val emergencySuppressed = unphasedSituation().copy(
            incomingSoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
            friendlyCatchDamage = PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN,
        )
        assertEquals(PhaseOrder.NONE, decide(emergencySuppressed))
    }

    @Test
    fun `自身濒危时无视友军接盘风险下潜`() {
        val s = unphasedSituation().copy(
            incomingSoonDamage = 5000f * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION,
            friendlyCatchDamage = 9999f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `低机动代理指标口径`() {
        assertTrue(
            GravityPhaseSituationSampler.isLowMobilityTarget(
                GravityPhaseSituationSampler.LOW_MOBILITY_MAX_SPEED, 200f, 90f,
            ),
        )
        assertTrue(
            GravityPhaseSituationSampler.isLowMobilityTarget(
                120f,
                GravityPhaseSituationSampler.LOW_MOBILITY_ACCEL,
                GravityPhaseSituationSampler.LOW_MOBILITY_TURN_RATE,
            ),
        )
        assertFalse(GravityPhaseSituationSampler.isLowMobilityTarget(120f, 200f, 90f))
    }

    @Test
    fun `穿透弹道线段与友军碰撞圈相交判定`() {
        assertTrue(GravityPhaseSituationSampler.segmentHitsCircle(0f, 0f, 100f, 0f, 80f, 0f, 20f))
        assertFalse(GravityPhaseSituationSampler.segmentHitsCircle(0f, 0f, 100f, 0f, 80f, 30f, 20f))
        assertFalse(GravityPhaseSituationSampler.segmentHitsCircle(0f, 0f, 100f, 0f, -50f, 0f, 20f))
        assertFalse(GravityPhaseSituationSampler.segmentHitsCircle(0f, 0f, 0f, 0f, 5f, 0f, 20f))
    }

    @Test
    fun `角度差纯函数口径`() {
        assertEquals(120f, GravityPhaseSituationSampler.angleDiffAbs(0f, 120f), 1e-4f)
        assertEquals(120f, GravityPhaseSituationSampler.angleDiffAbs(0f, -120f), 1e-4f)
        assertEquals(10f, GravityPhaseSituationSampler.angleDiffAbs(350f, 0f), 1e-4f)
        assertEquals(180f, GravityPhaseSituationSampler.angleDiffAbs(90f, 270f), 1e-4f)
    }

    @Test
    fun `耗软辐闸门单一口径 decide 下潜与 vent 压制宽限共用`() {
        val gateOpen = unphasedSituation().copy(softFluxLevel = 0.4f, fluxLevel = 0.55f)
        assertTrue(PhaseToggleTree.isSoftFluxDumpDive(gateOpen))
        assertTrue(FluxVentTree.isSoftFluxDumpVentHold(gateOpen))
        assertEquals(PhaseOrder.DIVE, decide(gateOpen))

        val disengaged = gateOpen.copy(engagedEnemyNear = false)
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(disengaged))
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(disengaged))
        assertEquals(PhaseOrder.NONE, decide(disengaged))

        val unsafe = gateOpen.copy(
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f) *
                    PhaseToggleTree.SOFT_FLUX_DIVE_SAFE_NEAR_FRAC,
        )
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(unsafe))
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(unsafe))
        assertEquals(PhaseOrder.NONE, decide(unsafe))
    }

    @Test
    fun `耗软辐下潜不受错峰闸约束`() {
        val s = unphasedSituation().copy(
            timeSinceUnphase = PhaseToggleTree.MIN_UNPHASE_TIME_SEC - 0.1f,
            softFluxLevel = 0.4f,
            fluxLevel = 0.55f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `耗软辐下潜侧后占位放宽环境安全口径`() {
        val near = PhaseToggleTree.diveNearThreshold(5000f) * 0.4f
        val rear = unphasedSituation().copy(
            softFluxLevel = 0.4f,
            fluxLevel = 0.55f,
            incomingNearDamage = near,
            inTargetRearArc = true,
            weaponCoverage = PhaseToggleTree.SURFACE_COVERAGE_MAX,
        )
        assertTrue(PhaseToggleTree.isSoftFluxDumpDive(rear))
        assertEquals(PhaseOrder.DIVE, decide(rear))

        val front = rear.copy(inTargetRearArc = false)
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(front))

        val pinned = rear.copy(weaponCoverage = PhaseToggleTree.SURFACE_COVERAGE_MAX + 1)
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(pinned))
    }

    @Test
    fun `耗软辐 vent 压制覆盖斗篷冷却尾声`() {
        val gateOpen = unphasedSituation().copy(softFluxLevel = 0.4f, fluxLevel = 0.55f)

        val cooling = gateOpen.copy(
            cloakReady = false,
            cloakCooldownRemaining = FluxVentTree.SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC,
        )
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(cooling))
        assertTrue(FluxVentTree.isSoftFluxDumpVentHold(cooling))
        assertEquals(PhaseOrder.NONE, decide(cooling))

        val longCooling = cooling.copy(
            cloakCooldownRemaining = FluxVentTree.SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC + 0.1f,
        )
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(longCooling))

        val coolingUnsafe = cooling.copy(
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f) *
                    PhaseToggleTree.SOFT_FLUX_DIVE_SAFE_NEAR_FRAC,
        )
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(coolingUnsafe))

        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(gateOpen.copy(phased = true)))
    }

    @Test
    fun `耗软辐 vent 压制与下潜链封锁闸同口径`() {
        val cooling = unphasedSituation().copy(
            softFluxLevel = 0.4f,
            fluxLevel = 0.55f,
            cloakReady = false,
            cloakCooldownRemaining = 1f,
        )
        assertTrue(FluxVentTree.isSoftFluxDumpVentHold(cooling))
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(cooling.copy(systemActive = true)))

        assertFalse(
            FluxVentTree.isSoftFluxDumpVentHold(
                cooling.copy(friendlyCatchDamage = PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN),
            ),
        )

        val ready = unphasedSituation().copy(softFluxLevel = 0.4f, fluxLevel = 0.55f)
        assertFalse(
            FluxVentTree.isSoftFluxDumpVentHold(ready.copy(systemActive = true)),
        )
    }

    @Test
    fun `武器或引擎下线过半触发修复下潜`() {
        val weaponsDown = unphasedSituation().copy(
            weaponsDisabledFrac = PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC,
        )
        assertEquals(PhaseOrder.DIVE, decide(weaponsDown))

        val enginesDown = unphasedSituation().copy(
            enginesDisabledFrac = PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC,
        )
        assertEquals(PhaseOrder.DIVE, decide(enginesDown))

        assertEquals(
            PhaseOrder.NONE,
            decide(
                unphasedSituation().copy(
                    weaponsDisabledFrac = PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC - 0.1f,
                ),
            ),
        )

        assertEquals(
            PhaseOrder.NONE,
            decide(
                weaponsDown.copy(hardFluxLevel = PhaseToggleTree.REPAIR_DIVE_HARD_FLUX_MAX),
            ),
        )

        assertEquals(
            PhaseOrder.NONE,
            decide(weaponsDown.copy(engagedEnemyNear = false)),
        )
    }

    @Test
    fun `光束威胁折算口径`() {
        assertEquals(
            600f * GravityPhaseSituationSampler.CONT_BEAM_THREAT_WINDOW_SEC,
            GravityPhaseSituationSampler.beamThreatNear(600f),
            1e-4f,
        )
        assertEquals(
            3500f * GravityPhaseSituationSampler.BURST_BEAM_THREAT_WEIGHT,
            GravityPhaseSituationSampler.beamThreatSoon(3500f),
            1e-4f,
        )
        assertTrue(
            GravityPhaseSituationSampler.beamThreatSoon(3500f) >
                    GravityPhaseSituationSampler.beamThreatSoon(500f),
        )
        assertTrue(
            GravityPhaseSituationSampler.beamThreatNear(600f) >
                    GravityPhaseSituationSampler.beamThreatNear(100f),
        )
    }

    @Test
    fun `高伤光束折算进 soon 窗口后触发紧急下潜`() {
        val s = unphasedSituation().copy(
            incomingSoonDamage = GravityPhaseSituationSampler.beamThreatSoon(3500f),
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `绕后闸门单一口径 decide 与意图布防共用`() {
        val gateOpen = unphasedSituation().copy(targetLowMobility = true)
        assertTrue(FlankAttackTree.isFlankDive(gateOpen))
        assertEquals(PhaseOrder.DIVE, decide(gateOpen))

        val mobile = gateOpen.copy(targetLowMobility = false)
        assertFalse(FlankAttackTree.isFlankDive(mobile))
        assertEquals(PhaseOrder.NONE, decide(mobile))

        val behind = gateOpen.copy(inTargetRearArc = true)
        assertFalse(FlankAttackTree.isFlankDive(behind))
        assertEquals(PhaseOrder.NONE, decide(behind))

        val unsafe = gateOpen.copy(incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f))
        assertFalse(FlankAttackTree.isFlankDive(unsafe))
        assertEquals(PhaseOrder.DIVE, decide(unsafe))
    }

    @Test
    fun `绕后意图途中拦截战术上浮保持相位穿透`() {
        val s = phasedSituation().copy(flankIntentActive = true, systemReady = true)
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `绕后意图途中敌舰掉出交战圈不提前上浮`() {
        val s = phasedSituation().copy(flankIntentActive = true, engagedEnemyNear = false)
        assertEquals(PhaseOrder.NONE, decide(s))

        assertEquals(PhaseOrder.SURFACE, decide(s.copy(flankIntentActive = false)))
    }

    @Test
    fun `绕后意图达成但脱间距不足按住不上浮`() {
        val s = phasedSituation().copy(
            flankIntentActive = true,
            inTargetRearArc = true,
            rearSurfaceDistanceSafe = false,
        )
        assertEquals(PhaseOrder.NONE, decide(s))

        assertEquals(
            PhaseOrder.SURFACE,
            decide(s.copy(rearSurfaceDistanceSafe = true)),
        )
    }

    @Test
    fun `死角上浮脱间距不足按住不上浮`() {
        val s = phasedSituation().copy(
            inTargetRearArc = true,
            weaponCoverage = PhaseToggleTree.REAR_SURFACE_MAX_COVERAGE,
            rearSurfaceDistanceSafe = false,
        )
        assertEquals(PhaseOrder.NONE, decide(s))

        assertEquals(
            PhaseOrder.SURFACE,
            decide(s.copy(rearSurfaceDistanceSafe = true)),
        )
    }

    @Test
    fun `相对速度外推最近接近覆盖相向远离与窗口截断`() {
        assertEquals(0f, GravityPhaseSituationSampler.closestApproachDistance(100f, 0f, -50f, 0f, 2f))
        assertEquals(100f, GravityPhaseSituationSampler.closestApproachDistance(100f, 0f, 50f, 0f, 2f))
        assertEquals(100f, GravityPhaseSituationSampler.closestApproachDistance(100f, 0f, 0f, 0f, 2f))
        assertEquals(
            900f,
            GravityPhaseSituationSampler.closestApproachDistance(1000f, 0f, -50f, 0f, 2f),
        )
    }

    @Test
    fun `绕后最小脱间距随敌舰有效半径放大`() {
        val small = GravityPhaseSituationSampler.rearSurfaceMinSeparation(60f, 100f)
        val large = GravityPhaseSituationSampler.rearSurfaceMinSeparation(60f, 300f)
        assertTrue(large > small)
    }

    @Test
    fun `绕后走位脱间距不足时继续穿透不就地保持`() {
        val hugging = phasedSituation().copy(
            flankIntentActive = true,
            inTargetRearArc = true,
            rearSurfaceDistanceSafe = false,
        )
        assertFalse(FlankAttackTree.flankDriveHoldSpot(hugging))

        assertTrue(
            FlankAttackTree.flankDriveHoldSpot(hugging.copy(rearSurfaceDistanceSafe = true)),
        )
        assertFalse(
            FlankAttackTree.flankDriveHoldSpot(
                hugging.copy(rearSurfaceDistanceSafe = true, unphaseUnsafe = true),
            ),
        )
    }

    @Test
    fun `强制上浮落点不安全时有界暂缓超时兜底放行`() {
        val forced = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            surfaceTooClose = true,
        )
        assertEquals(PhaseOrder.NONE, decide(forced))

        assertEquals(
            PhaseOrder.SURFACE,
            decide(
                forced.copy(forcedSurfaceHoldSec = PhaseToggleTree.FORCED_SURFACE_HOLD_MAX_SEC),
            ),
        )

        val timeForced = phasedSituation().copy(
            phaseActiveTime = PhaseToggleTree.MAX_PHASE_TIME_SEC,
            surfaceTooClose = true,
        )
        assertEquals(PhaseOrder.NONE, decide(timeForced))
        assertEquals(
            PhaseOrder.SURFACE,
            decide(timeForced.copy(surfaceTooClose = false)),
        )
    }

    @Test
    fun `强制上浮落点暂缓期间绕后走位驱动让位`() {
        val intentRun = phasedSituation().copy(flankIntentActive = true)
        assertTrue(FlankAttackTree.maneuverDriveActive(intentRun))

        assertFalse(
            FlankAttackTree.maneuverDriveActive(intentRun.copy(forcedSurfaceHoldSec = 0.2f)),
        )

        assertFalse(FlankAttackTree.maneuverDriveActive(phasedSituation()))
    }

    @Test
    fun `强制上浮暂缓预算致命豁免期暂停`() {
        val forced = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            surfaceTooClose = true,
        )
        val step = PhaseToggleTree.nextForcedSurfaceHoldSec(forced, 0f)
        assertTrue(step > 0f)

        val lethal = forced.copy(
            incomingSoonDamage = 5000f * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION,
        )
        assertEquals(2f, PhaseToggleTree.nextForcedSurfaceHoldSec(lethal, 2f), 1e-6f)

        assertEquals(2f + step, PhaseToggleTree.nextForcedSurfaceHoldSec(forced, 2f), 1e-6f)

        val lethalTopped = lethal.copy(hardFluxLevel = PhaseToggleTree.HOLD_MAX_HARD_FLUX)
        assertEquals(2f + step, PhaseToggleTree.nextForcedSurfaceHoldSec(lethalTopped, 2f), 1e-6f)

        assertEquals(
            0f,
            PhaseToggleTree.nextForcedSurfaceHoldSec(forced.copy(surfaceTooClose = false), 2f),
            1e-6f,
        )
        assertEquals(0f, PhaseToggleTree.nextForcedSurfaceHoldSec(phasedSituation(), 2f), 1e-6f)
    }

    @Test
    fun `绕后意图期间放宽相位时长上限`() {
        val intent = phasedSituation().copy(
            flankIntentActive = true,
            phaseActiveTime = PhaseToggleTree.MAX_PHASE_TIME_SEC + 2f,
        )
        assertEquals(PhaseOrder.NONE, decide(intent))

        val noIntent = intent.copy(flankIntentActive = false)
        assertEquals(PhaseOrder.SURFACE, decide(noIntent))

        val overtime = intent.copy(
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(overtime))
    }

    @Test
    fun `绕后意图相位时长上限跟随按距离武装的窗口`() {
        val farArmed = phasedSituation().copy(
            flankIntentActive = true,
            flankIntentWindowSec = FlankAttackTree.FLANK_INTENT_MAX_SEC,
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC + 2f,
        )
        assertEquals(PhaseOrder.NONE, decide(farArmed))

        val overtime = farArmed.copy(
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_MAX_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(overtime))
    }

    @Test
    fun `绕后意图窗口按布防距离缩放且夹取上下限`() {
        assertEquals(
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST * 0.5f),
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST),
        )
        val ref = FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST)
        val doubled =
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST * 2f)
        assertTrue(doubled > ref)
        assertEquals(
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST * 10f),
            doubled,
        )
    }

    @Test
    fun `绕后意图达成后放宽覆盖闸上浮输出`() {
        val s = phasedSituation().copy(
            inTargetRearArc = true,
            weaponCoverage = PhaseToggleTree.REAR_SURFACE_MAX_COVERAGE + 1,
        )
        assertEquals(PhaseOrder.NONE, decide(s))

        val achieved = s.copy(flankIntentActive = true)
        assertEquals(PhaseOrder.SURFACE, decide(achieved))
    }

    @Test
    fun `友军火力达阈触发防御下潜`() {
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `友军火力只走防御链路不触发战术下潜`() {
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = PhaseToggleTree.diveSoonThreshold(5000f) - 1f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `友军火力烧身未濒危时受让渡闸压制`() {
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
            friendlyCatchDamage = PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `友军火力烧身濒危时无视接盘闸下潜`() {
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = 5000f * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION,
            friendlyCatchDamage = 9999f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `相位中被友军火力持续照射时按住不上浮`() {
        val s = phasedSituation().copy(
            systemReady = true,
            incomingFriendlySoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `相位中友军火力烧身致命时强制上浮被豁免`() {
        val s = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            incomingFriendlySoonDamage = 5000f * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `战术强制耗散闸门 低辐高硬辐占比且耗散窗口短时成立`() {
        val s = unphasedSituation().copy(
            fluxLevel = 0.10f,
            hardFluxLevel = 0.09f,
            softFluxLevel = 0.01f,
            ventTimeSec = 1f,
        )
        assertTrue(FluxVentTree.isTacticalVent(s))
        assertFalse(FluxVentTree.isTacticalVent(s.copy(fluxLevel = 0.20f)))
        assertFalse(
            FluxVentTree.isTacticalVent(s.copy(hardFluxLevel = 0.05f, softFluxLevel = 0.05f)),
        )
        assertFalse(FluxVentTree.isTacticalVent(s.copy(ventTimeSec = 2f)))
        assertFalse(
            FluxVentTree.isTacticalVent(
                s.copy(
                    incomingNearDamage =
                        PhaseToggleTree.diveNearThreshold(5000f) *
                                FluxVentTree.TACTICAL_VENT_SAFE_NEAR_FRAC,
                ),
            ),
        )
        assertFalse(FluxVentTree.isTacticalVent(s.copy(venting = true)))
        assertFalse(FluxVentTree.isTacticalVent(s.copy(retreating = true)))
        assertFalse(
            FluxVentTree.isTacticalVent(s.copy(fluxLevel = 0f, hardFluxLevel = 0f, softFluxLevel = 0f)),
        )
        assertFalse(FluxVentTree.isTacticalVent(s.copy(systemActive = true)))
    }

    @Test
    fun `战术强制耗散阈值随强制耗散属性提升`() {
        val s = unphasedSituation().copy(
            fluxLevel = 0.20f,
            hardFluxLevel = 0.18f,
            softFluxLevel = 0.02f,
            ventTimeSec = 1f,
            ventRateMult = 2f,
        )
        assertTrue(FluxVentTree.isTacticalVent(s))
        assertFalse(FluxVentTree.isTacticalVent(s.copy(ventRateMult = 1f)))
    }

    @Test
    fun `前方友军承火时紧急与威胁下潜阈值放宽`() {
        val emergency = unphasedSituation().copy(incomingSoonDamage = 450f)
        assertEquals(PhaseOrder.DIVE, decide(emergency))
        assertEquals(
            PhaseOrder.NONE,
            decide(emergency.copy(friendlyScreenAhead = true)),
        )
        val threat = unphasedSituation().copy(incomingNearDamage = 900f)
        assertEquals(PhaseOrder.DIVE, decide(threat))
        assertEquals(
            PhaseOrder.NONE,
            decide(threat.copy(friendlyScreenAhead = true)),
        )
    }

    @Test
    fun `相位赶路下潜 远目标点脱战低辐时下潜`() {
        val s = unphasedSituation().copy(
            engagedEnemyNear = false,
            travelDestDistance = 3000f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
        assertEquals(
            PhaseOrder.NONE,
            decide(s.copy(travelDestDistance = Float.MAX_VALUE)),
        )
        assertEquals(
            PhaseOrder.NONE,
            decide(s.copy(engagedEnemyNear = true)),
        )
        assertEquals(
            PhaseOrder.NONE,
            decide(s.copy(retreating = true, hardFluxLevel = 0.5f)),
        )
        assertEquals(PhaseOrder.NONE, decide(s.copy(fluxLevel = 0.5f)))
        assertEquals(PhaseOrder.NONE, decide(s.copy(travelDestDistance = 1500f)))
    }

    @Test
    fun `相位赶路途中保持 辐能未超标拦截无威胁上浮`() {
        val s = phasedSituation().copy(
            engagedEnemyNear = false,
            travelDestDistance = 3000f,
            fluxLevel = 0.2f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(fluxLevel = 0.4f)))
        assertEquals(
            PhaseOrder.SURFACE,
            decide(s.copy(travelDestDistance = Float.MAX_VALUE)),
        )
    }

    @Test
    fun `迫近下潜闸门逐项翻转`() {
        val base = unphasedSituation().copy(
            targetDistance = 3000f,
            effectiveWeaponRange = 1000f,
        )
        assertTrue(PhaseToggleTree.isPressApproachDive(base))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(phased = true)))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(cloakReady = false)))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(retreating = true)))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(effectiveWeaponRange = 0f)))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(targetDistance = Float.MAX_VALUE)))
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(targetDistance = PhaseToggleTree.PRESS_APPROACH_MIN_DIST - 1f),
            ),
        )
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(
                    effectiveWeaponRange = 2000f,
                    targetDistance = 2000f * PhaseToggleTree.PRESS_APPROACH_RANGE_MULT,
                ),
            ),
        )
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(weaponsReadyFrac = PhaseToggleTree.PRESS_APPROACH_WEAPONS_FRAC - 0.1f),
            ),
        )
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(hardFluxLevel = PhaseToggleTree.PRESS_APPROACH_HARD_FLUX_MAX),
            ),
        )
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f)),
            ),
        )
    }

    @Test
    fun `迫近意图生效纯函数逐项口径`() {
        val range = 1000f
        val onRouteDist = range * PhaseToggleTree.PRESS_SURFACE_RANGE_FRAC + 1f
        assertTrue(FlankAttackTree.pressIntentActive(5f, true, onRouteDist, range))
        assertFalse(FlankAttackTree.pressIntentActive(0f, true, onRouteDist, range))
        assertFalse(FlankAttackTree.pressIntentActive(5f, false, onRouteDist, range))
        assertFalse(FlankAttackTree.pressIntentActive(5f, true, Float.MAX_VALUE, range))
        assertFalse(FlankAttackTree.pressIntentActive(5f, true, onRouteDist, 0f))
        assertFalse(
            FlankAttackTree.pressIntentActive(
                5f, true, range * PhaseToggleTree.PRESS_SURFACE_RANGE_FRAC, range,
            ),
        )
    }

    @Test
    fun `迫近意图生效期间拦截一切主动上浮`() {
        val active = phasedSituation().copy(pressIntentActive = true, engagedEnemyNear = false)
        assertEquals(PhaseOrder.NONE, PressIntentRule.evaluate(PhaseDecisionContext(active)))
        assertNull(PressIntentRule.evaluate(PhaseDecisionContext(active.copy(phased = false))))
        assertNull(
            PressIntentRule.evaluate(PhaseDecisionContext(active.copy(pressIntentActive = false))),
        )
    }

    @Test
    fun `迫近到位后输出窗口上浮`() {
        val s = phasedSituation().copy(
            pressIntentActive = false,
            engagedEnemyNear = true,
            targetDistance = 800f,
            effectiveWeaponRange = 1000f,
            weaponsReadyFrac = PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(s))
        assertEquals("WeaponsReadySurface", toggleTree.lastFiredRuleId)
    }

    @Test
    fun `迫近下潜优先于相位赶路`() {
        val s = unphasedSituation().copy(
            engagedEnemyNear = false,
            travelDestDistance = 3000f,
            targetDistance = 3000f,
            effectiveWeaponRange = 1000f,
        )
        assertTrue(PhaseToggleTree.isPressApproachDive(s))
        assertTrue(PhaseToggleTree.isTravelDive(s))
        assertEquals(PhaseOrder.DIVE, decide(s))
        assertEquals("PressApproach", toggleTree.lastFiredRuleId)
    }

    @Test
    fun `迫近意图途中目标在交战圈外拦截无威胁上浮`() {
        val s = phasedSituation().copy(
            pressIntentActive = true,
            engagedEnemyNear = false,
            incomingNearDamage = 0f,
            targetDistance = 3000f,
            effectiveWeaponRange = 1000f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals("PressIntent", toggleTree.lastFiredRuleId)
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(pressIntentActive = false)))
    }

    @Test
    fun `迫近意图生效时走位穿透驱动 不做就地保持`() {
        val tree = FlankAttackTree()
        val s = phasedSituation().copy(
            pressIntentActive = true,
            flankIntentActive = false,
            inTargetRearArc = true,
            rearSurfaceDistanceSafe = true,
        )
        assertTrue(FlankAttackTree.maneuverDriveActive(s))
        assertNull(FlankHoldSpotRule.evaluate(PhaseDecisionContext(s)))
        assertEquals(FlankOrder.DRIVE, tree.decide(PhaseDecisionContext(s)))
        assertEquals("FlankDrive", tree.lastFiredRuleId)
        assertFalse(FlankAttackTree.maneuverDriveActive(s.copy(phased = false)))
        assertFalse(FlankAttackTree.maneuverDriveActive(s.copy(forcedSurfaceHoldSec = 0.2f)))
    }

    @Test
    fun `迫近意图期间放宽相位时长上限`() {
        val s = phasedSituation().copy(
            pressIntentActive = true,
            engagedEnemyNear = false,
            targetDistance = 3000f,
            effectiveWeaponRange = 1000f,
            phaseActiveTime = PhaseToggleTree.MAX_PHASE_TIME_SEC + 1f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))

        assertEquals(PhaseOrder.SURFACE, decide(s.copy(pressIntentActive = false)))

        val overtime = s.copy(
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(overtime))
    }

    private class RecordingRule(
        override val id: String,
        private val conclusion: PhaseOrder?,
        private val evaluated: MutableList<String>,
    ) : PhaseRule<PhaseOrder> {
        override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
            evaluated += id
            return conclusion
        }
    }

    private class StubTree(rules: List<PhaseRule<PhaseOrder>>) :
        PriorityPhaseTree<PhaseOrder>("Stub", PhaseOrder.NONE, rules)

    @Test
    fun `树级 高优先规则触发时低优先规则不被评估`() {
        val evaluated = mutableListOf<String>()
        val tree = StubTree(
            listOf(
                RecordingRule("high", PhaseOrder.SURFACE, evaluated),
                RecordingRule("low", PhaseOrder.DIVE, evaluated),
            ),
        )
        val result = tree.decide(PhaseDecisionContext(phasedSituation()))
        assertEquals(PhaseOrder.SURFACE, result)
        assertEquals(listOf("high"), evaluated)
        assertEquals("high", tree.lastFiredRuleId)
    }

    @Test
    fun `树级 全弃权回落 NONE 且无命中规则留痕`() {
        val evaluated = mutableListOf<String>()
        val tree = StubTree(
            listOf(
                RecordingRule("abstainA", null, evaluated),
                RecordingRule("abstainB", null, evaluated),
            ),
        )
        val result = tree.decide(PhaseDecisionContext(phasedSituation()))
        assertEquals(PhaseOrder.NONE, result)
        assertEquals(listOf("abstainA", "abstainB"), evaluated)
        assertNull(tree.lastFiredRuleId)
    }

    @Test
    fun `树级 C 树互斥 A 树下潜指令压制 vent 链路`() {
        val s = unphasedSituation().copy(
            fluxLevel = 0.10f,
            hardFluxLevel = 0.09f,
            softFluxLevel = 0.01f,
            ventTimeSec = 1f,
        )
        val tree = FluxVentTree()
        assertEquals(VentOrder.SUPPRESS, tree.decide(PhaseDecisionContext(s, PhaseOrder.DIVE)))
        assertEquals("DiveOrderedSuppress", tree.lastFiredRuleId)
        assertEquals(VentOrder.VENT, tree.decide(PhaseDecisionContext(s, PhaseOrder.NONE)))
        assertEquals("TacticalVent", tree.lastFiredRuleId)
        assertEquals(VentOrder.NONE, tree.decide(PhaseDecisionContext(s, PhaseOrder.SURFACE)))
        assertNull(tree.lastFiredRuleId)
    }

    @Test
    fun `树级 B 树绕后走位规则表结论映射`() {
        val tree = FlankAttackTree()
        assertEquals(FlankOrder.NONE, tree.decide(PhaseDecisionContext(phasedSituation())))
        val holding = phasedSituation().copy(forcedSurfaceHoldSec = 1f, flankIntentActive = true)
        assertEquals(FlankOrder.BACKOFF, tree.decide(PhaseDecisionContext(holding)))
        assertEquals("ForcedHoldBackoff", tree.lastFiredRuleId)
        val driving = phasedSituation().copy(flankIntentActive = true, inTargetRearArc = false)
        assertEquals(FlankOrder.DRIVE, tree.decide(PhaseDecisionContext(driving)))
        assertEquals("FlankDrive", tree.lastFiredRuleId)
        val inSpot = phasedSituation().copy(flankIntentActive = true, inTargetRearArc = true)
        assertEquals(FlankOrder.HOLD_SPOT, tree.decide(PhaseDecisionContext(inSpot)))
        assertEquals("FlankHoldSpot", tree.lastFiredRuleId)
        val overlap = inSpot.copy(unphaseUnsafe = true)
        assertEquals(FlankOrder.DRIVE, tree.decide(PhaseDecisionContext(overlap)))
    }
}
