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

/**
 * 引力相位 AI 多决策树的规则覆盖：
 * A 树 [PhaseToggleTree]（强制上浮/致命豁免/主动上浮/迫近与下潜全链路）、
 * B 树 [FlankAttackTree]（绕后/迫近意图与走位口径）、C 树 [FluxVentTree]（vent 压制与战术耗散），
 * 以及树级行为（优先级截断、全弃权回落、树间互斥）。
 */
class GravityPhaseCloakAITest {

    /** A 树实例：规则无状态，全测试共用。 */
    private val toggleTree = PhaseToggleTree()

    /** 原 GravityPhaseCloakAI.decide 的等价入口：A 树评估（cloakOrder 恒 NONE）+ 落点重叠收尾闸。 */
    private fun decide(s: PhaseSituation): PhaseOrder =
        toggleTree.decide(PhaseDecisionContext(s))

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
        // 强制上浮路径（硬辐能达闸）：落点重叠时被按住，漂出重叠后放行
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
        // 交战圈外发射的高速弹：无威胁上浮只查 near 窗口，soon 闸必须先行拦下
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
        // 输出窗口本可上浮，与敌舰贴脸（碰撞圈深度贴近）时按住，漂离后放行
        val s = phasedSituation().copy(
            weaponsReadyFrac = PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC,
            surfaceTooClose = true,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(surfaceTooClose = false)))
    }

    @Test
    fun `弹幕窗口来袭达闸时主动上浮按住`() {
        // 已在途的弹幕/鱼雷在 2s 上浮弹幕窗口内合计达闸即按住，低于阈值放行
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
        // 位于主威胁舰艏火力轴锥内时按住，离开火力轴放行
        val s = phasedSituation().copy(
            weaponsReadyFrac = PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC,
            threatFrontAxisClose = true,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(threatFrontAxisClose = false)))
    }

    @Test
    fun `强制上浮暂缓达上限后不受上浮安全闸约束`() {
        // 硬辐能达闸必须上浮：暂缓累计达上限后，贴脸/弹幕/正脸火力轴全部不安全也兜底放行
        val s = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            surfaceTooClose = true,
            incomingSurfaceDamage = PhaseToggleTree.surfaceDangerThreshold(5000f) * 2f,
            threatFrontAxisClose = true,
            forcedSurfaceHoldSec = PhaseToggleTree.FORCED_SURFACE_HOLD_MAX_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(s))
        // 暂缓未达上限时同参数按住（口径见「强制上浮落点不安全时有界暂缓超时兜底放行」）
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
        // 极速达标即低机动
        assertTrue(
            GravityPhaseSituationSampler.isLowMobilityTarget(
                GravityPhaseSituationSampler.LOW_MOBILITY_MAX_SPEED, 200f, 90f,
            ),
        )
        // 转向与加速度双低也是低机动
        assertTrue(
            GravityPhaseSituationSampler.isLowMobilityTarget(
                120f,
                GravityPhaseSituationSampler.LOW_MOBILITY_ACCEL,
                GravityPhaseSituationSampler.LOW_MOBILITY_TURN_RATE,
            ),
        )
        // 三项都不低不算低机动
        assertFalse(GravityPhaseSituationSampler.isLowMobilityTarget(120f, 200f, 90f))
    }

    @Test
    fun `穿透弹道线段与友军碰撞圈相交判定`() {
        // 正向贯穿：线段从原点附近直指圆心
        assertTrue(GravityPhaseSituationSampler.segmentHitsCircle(0f, 0f, 100f, 0f, 80f, 0f, 20f))
        // 横向偏离超过半径不命中
        assertFalse(GravityPhaseSituationSampler.segmentHitsCircle(0f, 0f, 100f, 0f, 80f, 30f, 20f))
        // 圆在线段起点后方不命中（投影截断到线段内）
        assertFalse(GravityPhaseSituationSampler.segmentHitsCircle(0f, 0f, 100f, 0f, -50f, 0f, 20f))
        // 零长度线段不命中
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
        // 完整闸门成立：软辐可观、辐能有余量、环境安全、有交战对象
        val gateOpen = unphasedSituation().copy(softFluxLevel = 0.4f, fluxLevel = 0.55f)
        assertTrue(PhaseToggleTree.isSoftFluxDumpDive(gateOpen))
        assertTrue(FluxVentTree.isSoftFluxDumpVentHold(gateOpen))
        assertEquals(PhaseOrder.DIVE, decide(gateOpen))

        // 脱战（无交战对象）：闸门不成立——vent 不应被压制，decide 也不下潜
        val disengaged = gateOpen.copy(engagedEnemyNear = false)
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(disengaged))
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(disengaged))
        assertEquals(PhaseOrder.NONE, decide(disengaged))

        // 来袭偏高：闸门不成立，两侧同样都不动作
        val unsafe = gateOpen.copy(
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f) *
                    PhaseToggleTree.SOFT_FLUX_DIVE_SAFE_NEAR_FRAC,
        )
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(unsafe))
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(unsafe))
        // 注意：该来袭量低于威胁下潜阈值，decide 整体也不下潜
        assertEquals(PhaseOrder.NONE, decide(unsafe))
    }

    @Test
    fun `耗软辐下潜不受错峰闸约束`() {
        // 上浮后错峰期内软辐已可观：相位耗散优先于错峰（否则 VentModule 必在错峰窗口抢先 vent）
        val s = unphasedSituation().copy(
            timeSinceUnphase = PhaseToggleTree.MIN_UNPHASE_TIME_SEC - 0.1f,
            softFluxLevel = 0.4f,
            fluxLevel = 0.55f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `耗软辐下潜侧后占位放宽环境安全口径`() {
        // near 来袭超过常规安全口径（0.25×）但低于侧后放宽口径（0.5×）：
        // 侧后占位且覆盖不超闸时仍下潜耗散（原地相位，上浮仍在输出位）
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

        // 非侧后占位：同来袭量闸门不成立
        val front = rear.copy(inTargetRearArc = false)
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(front))

        // 侧后但覆盖超闸（被咬住）：不放宽
        val pinned = rear.copy(weaponCoverage = PhaseToggleTree.SURFACE_COVERAGE_MAX + 1)
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(pinned))
    }

    @Test
    fun `耗软辐 vent 压制覆盖斗篷冷却尾声`() {
        val gateOpen = unphasedSituation().copy(softFluxLevel = 0.4f, fluxLevel = 0.55f)

        // 冷却尾声（≤ 宽限）：下潜闸门不成立（斗篷未就绪），但 vent 压制提前接手——
        // 否则 VentModule 必在冷却窗口内抢先 vent（vent 启动即不可取消）
        val cooling = gateOpen.copy(
            cloakReady = false,
            cloakCooldownRemaining = FluxVentTree.SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC,
        )
        assertFalse(PhaseToggleTree.isSoftFluxDumpDive(cooling))
        assertTrue(FluxVentTree.isSoftFluxDumpVentHold(cooling))
        assertEquals(PhaseOrder.NONE, decide(cooling))

        // 冷却前段（> 宽限）：压制不成立，vent 放行兜底
        val longCooling = cooling.copy(
            cloakCooldownRemaining = FluxVentTree.SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC + 0.1f,
        )
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(longCooling))

        // 冷却尾声但环境转差：闸门失效，vent 放行兜底（不构成「压了 vent 却不下潜」的卡死）
        val coolingUnsafe = cooling.copy(
            incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f) *
                    PhaseToggleTree.SOFT_FLUX_DIVE_SAFE_NEAR_FRAC,
        )
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(coolingUnsafe))

        // 相位中：压制口径不适用（相位中本就不排气）
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(gateOpen.copy(phased = true)))
    }

    @Test
    fun `耗软辐 vent 压制与下潜链封锁闸同口径`() {
        // 冷却尾声宽限期内，攻击系统激活中：decide 绝不下潜，vent 压制必须放行——
        // 否则构成「压了 vent 却不下潜」的真实窗口（审查判例）
        val cooling = unphasedSituation().copy(
            softFluxLevel = 0.4f,
            fluxLevel = 0.55f,
            cloakReady = false,
            cloakCooldownRemaining = 1f,
        )
        assertTrue(FluxVentTree.isSoftFluxDumpVentHold(cooling))
        assertFalse(FluxVentTree.isSoftFluxDumpVentHold(cooling.copy(systemActive = true)))

        // 友军接盘风险达闸：同样放行 vent（穿透误伤优先于耗散节奏）
        assertFalse(
            FluxVentTree.isSoftFluxDumpVentHold(
                cooling.copy(friendlyCatchDamage = PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN),
            ),
        )

        // 斗篷就绪且完整闸门成立时：封锁闸同样生效（decide 在封锁闸后才会查耗软辐闸门）
        val ready = unphasedSituation().copy(softFluxLevel = 0.4f, fluxLevel = 0.55f)
        assertFalse(
            FluxVentTree.isSoftFluxDumpVentHold(ready.copy(systemActive = true)),
        )
    }

    @Test
    fun `武器或引擎下线过半触发修复下潜`() {
        // 相位 3x 时间倍率随舰时钟加速组件修复，下线过半时优先相位窗口
        val weaponsDown = unphasedSituation().copy(
            weaponsDisabledFrac = PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC,
        )
        assertEquals(PhaseOrder.DIVE, decide(weaponsDown))

        val enginesDown = unphasedSituation().copy(
            enginesDisabledFrac = PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC,
        )
        assertEquals(PhaseOrder.DIVE, decide(enginesDown))

        // 未达闸：照常作战
        assertEquals(
            PhaseOrder.NONE,
            decide(
                unphasedSituation().copy(
                    weaponsDisabledFrac = PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC - 0.1f,
                ),
            ),
        )

        // 硬辐能过高：下潜只会加速憋死，不触发
        assertEquals(
            PhaseOrder.NONE,
            decide(
                weaponsDown.copy(hardFluxLevel = PhaseToggleTree.REPAIR_DIVE_HARD_FLUX_MAX),
            ),
        )

        // 脱战：无生存压力，常规时钟修复即可
        assertEquals(
            PhaseOrder.NONE,
            decide(weaponsDown.copy(engagedEnemyNear = false)),
        )
    }

    @Test
    fun `光束威胁折算口径`() {
        // 持续光束：DPS × 窗口秒计入 near
        assertEquals(
            600f * GravityPhaseSituationSampler.CONT_BEAM_THREAT_WINDOW_SEC,
            GravityPhaseSituationSampler.beamThreatNear(600f),
            1e-4f,
        )
        // 爆发光束：爆发总伤 × 权重计入 soon
        assertEquals(
            3500f * GravityPhaseSituationSampler.BURST_BEAM_THREAT_WEIGHT,
            GravityPhaseSituationSampler.beamThreatSoon(3500f),
            1e-4f,
        )
        // 折算单调性：高伤光束威胁值高于低伤光束
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
        // 爆发光束照射的折算伤害进入 soon 窗口，达到紧急下潜阈值即下潜（相位断照射）
        val s = unphasedSituation().copy(
            incomingSoonDamage = GravityPhaseSituationSampler.beamThreatSoon(3500f),
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `绕后闸门单一口径 decide 与意图布防共用`() {
        // 完整闸门成立：低机动目标、未入侧后、武器过半、硬辐能有余量、环境安全
        val gateOpen = unphasedSituation().copy(targetLowMobility = true)
        assertTrue(FlankAttackTree.isFlankDive(gateOpen))
        assertEquals(PhaseOrder.DIVE, decide(gateOpen))

        // 目标高机动：闸门不成立，decide 也不下潜
        val mobile = gateOpen.copy(targetLowMobility = false)
        assertFalse(FlankAttackTree.isFlankDive(mobile))
        assertEquals(PhaseOrder.NONE, decide(mobile))

        // 已在侧后：闸门不成立（无需重复穿透）
        val behind = gateOpen.copy(inTargetRearArc = true)
        assertFalse(FlankAttackTree.isFlankDive(behind))
        assertEquals(PhaseOrder.NONE, decide(behind))

        // 来袭达威胁阈值：闸门不成立（此时走威胁下潜链路，不布防绕后意图）
        val unsafe = gateOpen.copy(incomingNearDamage = PhaseToggleTree.diveNearThreshold(5000f))
        assertFalse(FlankAttackTree.isFlankDive(unsafe))
        assertEquals(PhaseOrder.DIVE, decide(unsafe))
    }

    @Test
    fun `绕后意图途中拦截战术上浮保持相位穿透`() {
        // 攻击系统就绪本可战术上浮，意图途中（未入侧后）保持相位机动
        val s = phasedSituation().copy(flankIntentActive = true, systemReady = true)
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `绕后意图途中敌舰掉出交战圈不提前上浮`() {
        // 穿透机动途中敌舰短暂掉出交战圈：无威胁上浮被意图拦截按住，布防不早夭
        val s = phasedSituation().copy(flankIntentActive = true, engagedEnemyNear = false)
        assertEquals(PhaseOrder.NONE, decide(s))

        // 无意图时同参数走无威胁上浮
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(flankIntentActive = false)))
    }

    @Test
    fun `绕后意图达成但脱间距不足按住不上浮`() {
        // 已入侧后、覆盖达标但贴着敌舰护盾/碰撞箱：按住保持相位穿出拉开
        val s = phasedSituation().copy(
            flankIntentActive = true,
            inTargetRearArc = true,
            rearSurfaceDistanceSafe = false,
        )
        assertEquals(PhaseOrder.NONE, decide(s))

        // 同参数脱间距满足即放行上浮输出
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
        // 相向且窗口内到达：最近接近为 0（撞上去）
        assertEquals(0f, GravityPhaseSituationSampler.closestApproachDistance(100f, 0f, -50f, 0f, 2f))
        // 相互远离：最近接近即当前间距
        assertEquals(100f, GravityPhaseSituationSampler.closestApproachDistance(100f, 0f, 50f, 0f, 2f))
        // 相对静止：当前间距
        assertEquals(100f, GravityPhaseSituationSampler.closestApproachDistance(100f, 0f, 0f, 0f, 2f))
        // 相向但到达点在窗口外：截断到窗口末的位置
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
        // 侧后但贴脸：走位层挂穿透系（flankDriveHoldSpot=false），与 decide 的按住上浮同口径
        val hugging = phasedSituation().copy(
            flankIntentActive = true,
            inTargetRearArc = true,
            rearSurfaceDistanceSafe = false,
        )
        assertFalse(FlankAttackTree.flankDriveHoldSpot(hugging))

        // 脱间距满足：就地保持现状不变
        assertTrue(
            FlankAttackTree.flankDriveHoldSpot(hugging.copy(rearSurfaceDistanceSafe = true)),
        )
        // 落点重叠：即使脱间距满足也不就地保持（原版斗篷拒退，继续穿透漂出）
        assertFalse(
            FlankAttackTree.flankDriveHoldSpot(
                hugging.copy(rearSurfaceDistanceSafe = true, unphaseUnsafe = true),
            ),
        )
    }

    @Test
    fun `强制上浮落点不安全时有界暂缓超时兜底放行`() {
        // 辐能强制上浮 + 贴脸：暂缓按住（贴脸强制上浮是实机贴盾/吃鱼雷入口）
        val forced = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            surfaceTooClose = true,
        )
        assertEquals(PhaseOrder.NONE, decide(forced))

        // 暂缓累计达上限：兜底放行（防永久相位幽灵船）
        assertEquals(
            PhaseOrder.SURFACE,
            decide(
                forced.copy(forcedSurfaceHoldSec = PhaseToggleTree.FORCED_SURFACE_HOLD_MAX_SEC),
            ),
        )

        // 时长强制上浮同口径暂缓；落点恢复安全即放行（暂缓清零后的正常路径）
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
        // 意图途中且未暂缓：穿透驱动照常（DO_NOT_BACK_OFF + PHASE_ATTACK_RUN 系旗标）
        val intentRun = phasedSituation().copy(flankIntentActive = true)
        assertTrue(FlankAttackTree.maneuverDriveActive(intentRun))

        // 落点暂缓中：穿透驱动让位给保命后撤，防止 DO_NOT_BACK_OFF 与后撤旗标并挂打架
        assertFalse(
            FlankAttackTree.maneuverDriveActive(intentRun.copy(forcedSurfaceHoldSec = 0.2f)),
        )

        // 非意图期本就不驱动
        assertFalse(FlankAttackTree.maneuverDriveActive(phasedSituation()))
    }

    @Test
    fun `强制上浮暂缓预算致命豁免期暂停`() {
        // 触发且落点不安全：预算随决策节拍累计
        val forced = phasedSituation().copy(
            hardFluxLevel = PhaseToggleTree.SURFACE_HARD_FLUX,
            surfaceTooClose = true,
        )
        val step = PhaseToggleTree.nextForcedSurfaceHoldSec(forced, 0f)
        assertTrue(step > 0f)

        // 致命豁免期：预算暂停不累计（豁免是等这一下过去而非等安全落点，不该烧预算）
        val lethal = forced.copy(
            incomingSoonDamage = 5000f * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION,
        )
        assertEquals(2f, PhaseToggleTree.nextForcedSurfaceHoldSec(lethal, 2f), 1e-6f)

        // 豁免结束：从暂停处续计而非清零重攒
        assertEquals(2f + step, PhaseToggleTree.nextForcedSurfaceHoldSec(forced, 2f), 1e-6f)

        // 豁免在硬辐能顶格后失效（辐能本身更危险），预算照常累计
        val lethalTopped = lethal.copy(hardFluxLevel = PhaseToggleTree.HOLD_MAX_HARD_FLUX)
        assertEquals(2f + step, PhaseToggleTree.nextForcedSurfaceHoldSec(lethalTopped, 2f), 1e-6f)

        // 落点恢复安全/触发解除：预算清零
        assertEquals(
            0f,
            PhaseToggleTree.nextForcedSurfaceHoldSec(forced.copy(surfaceTooClose = false), 2f),
            1e-6f,
        )
        assertEquals(0f, PhaseToggleTree.nextForcedSurfaceHoldSec(phasedSituation(), 2f), 1e-6f)
    }

    @Test
    fun `绕后意图期间放宽相位时长上限`() {
        // 常规上限（8s）到意图上限（基准窗口 12s + 2s 富余）之间：意图生效时不强制上浮
        val intent = phasedSituation().copy(
            flankIntentActive = true,
            phaseActiveTime = PhaseToggleTree.MAX_PHASE_TIME_SEC + 2f,
        )
        assertEquals(PhaseOrder.NONE, decide(intent))

        // 无意图时同一时长早已触发强制上浮
        val noIntent = intent.copy(flankIntentActive = false)
        assertEquals(PhaseOrder.SURFACE, decide(noIntent))

        // 意图放宽上限（武装窗口 + 富余）到达后同样强制上浮
        val overtime = intent.copy(
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(overtime))
    }

    @Test
    fun `绕后意图相位时长上限跟随按距离武装的窗口`() {
        // 远处布防武装的长窗口（24s）下，超过基准意图上限（14s）仍在穿透途中不强制上浮
        val farArmed = phasedSituation().copy(
            flankIntentActive = true,
            flankIntentWindowSec = FlankAttackTree.FLANK_INTENT_MAX_SEC,
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC + 2f,
        )
        assertEquals(PhaseOrder.NONE, decide(farArmed))

        // 到达长窗口 + 富余后强制上浮
        val overtime = farArmed.copy(
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_MAX_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(overtime))
    }

    @Test
    fun `绕后意图窗口按布防距离缩放且夹取上下限`() {
        // 下限夹取：近距离布防窗口不再缩短（半参考距离与参考距离同窗口）
        assertEquals(
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST * 0.5f),
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST),
        )
        // 线性放大：两倍参考距离的窗口大于参考距离
        val ref = FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST)
        val doubled =
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST * 2f)
        assertTrue(doubled > ref)
        // 上限封顶：到达上限距离后窗口不再增长
        assertEquals(
            FlankAttackTree.flankIntentWindowSec(FlankAttackTree.FLANK_INTENT_REF_DIST * 10f),
            doubled,
        )
    }

    @Test
    fun `绕后意图达成后放宽覆盖闸上浮输出`() {
        // 覆盖 2 超过死角上限（1）但未超常规上限（3）：无意图时继续等死角，意图达成即上浮
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
        // 友军高伤火力在 soon 窗口烧向本舰：与敌方来袭同口径触发紧急下潜
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `友军火力只走防御链路不触发战术下潜`() {
        // 未达紧急阈值的友军火力不引发任何下潜（不参与绕后/耗软辐/威胁等战术链路）
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = PhaseToggleTree.diveSoonThreshold(5000f) - 1f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `友军火力烧身未濒危时受让渡闸压制`() {
        // 达紧急阈值但未濒危：穿透误伤友军的接盘风险优先，按住不下潜
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = PhaseToggleTree.diveSoonThreshold(5000f),
            friendlyCatchDamage = PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
    }

    @Test
    fun `友军火力烧身濒危时无视接盘闸下潜`() {
        // 与友军接盘博弈时自身生存优先：致命友军火力烧身仍可紧急下潜
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = 5000f * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION,
            friendlyCatchDamage = 9999f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
    }

    @Test
    fun `相位中被友军火力持续照射时按住不上浮`() {
        // 友军火力并入即将受击闸：上浮即被烧，战术上浮也被拦下
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
        // 基准：交战、无来袭、总辐能 0.10、硬辐 0.09（占比 0.9）、预计耗散 1s
        val s = unphasedSituation().copy(
            fluxLevel = 0.10f,
            hardFluxLevel = 0.09f,
            softFluxLevel = 0.01f,
            ventTimeSec = 1f,
        )
        assertTrue(FluxVentTree.isTacticalVent(s))
        // 总辐能超闸不成立
        assertFalse(FluxVentTree.isTacticalVent(s.copy(fluxLevel = 0.20f)))
        // 硬辐占比不足（软辐为主走相位耗散链路）不成立
        assertFalse(
            FluxVentTree.isTacticalVent(s.copy(hardFluxLevel = 0.05f, softFluxLevel = 0.05f)),
        )
        // 预计耗散窗口过长不成立
        assertFalse(FluxVentTree.isTacticalVent(s.copy(ventTimeSec = 2f)))
        // near 来袭达安全口径上限不成立
        assertFalse(
            FluxVentTree.isTacticalVent(
                s.copy(
                    incomingNearDamage =
                        PhaseToggleTree.diveNearThreshold(5000f) *
                                FluxVentTree.TACTICAL_VENT_SAFE_NEAR_FRAC,
                ),
            ),
        )
        // 已在耗散中/撤退中/辐能归零不成立
        assertFalse(FluxVentTree.isTacticalVent(s.copy(venting = true)))
        assertFalse(FluxVentTree.isTacticalVent(s.copy(retreating = true)))
        assertFalse(
            FluxVentTree.isTacticalVent(s.copy(fluxLevel = 0f, hardFluxLevel = 0f, softFluxLevel = 0f)),
        )
        // 攻击系统激活中不 vent（停火会作废磁暴充能/复制窗口，与下潜链封锁闸同口径）
        assertFalse(FluxVentTree.isTacticalVent(s.copy(systemActive = true)))
    }

    @Test
    fun `战术强制耗散阈值随强制耗散属性提升`() {
        // ventRateMult=2 时阈值上浮到 0.30：0.20 的总辐能从超闸变为入闸
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
        // 紧急链路：soon=450 达阈值（300）未达放宽后阈值（600）——承火时不潜水，否则紧急下潜
        val emergency = unphasedSituation().copy(incomingSoonDamage = 450f)
        assertEquals(PhaseOrder.DIVE, decide(emergency))
        assertEquals(
            PhaseOrder.NONE,
            decide(emergency.copy(friendlyScreenAhead = true)),
        )
        // 威胁链路：near=900 达阈值（600）未达放宽后阈值（1200）——承火时不潜水，否则威胁下潜
        val threat = unphasedSituation().copy(incomingNearDamage = 900f)
        assertEquals(PhaseOrder.DIVE, decide(threat))
        assertEquals(
            PhaseOrder.NONE,
            decide(threat.copy(friendlyScreenAhead = true)),
        )
    }

    @Test
    fun `相位赶路下潜 远目标点脱战低辐时下潜`() {
        // 基准：脱战、赶路目标 3000su、辐能低——下潜赶路
        val s = unphasedSituation().copy(
            engagedEnemyNear = false,
            travelDestDistance = 3000f,
        )
        assertEquals(PhaseOrder.DIVE, decide(s))
        // 无赶路目标点（空闲）不下潜
        assertEquals(
            PhaseOrder.NONE,
            decide(s.copy(travelDestDistance = Float.MAX_VALUE)),
        )
        // 交战状态下赶路下潜无效
        assertEquals(
            PhaseOrder.NONE,
            decide(s.copy(engagedEnemyNear = true)),
        )
        // 撤退态由撤退下潜链路处理，赶路下潜不重复出手
        assertEquals(
            PhaseOrder.NONE,
            decide(s.copy(retreating = true, hardFluxLevel = 0.5f)),
        )
        // 辐能过高/目标过近不下潜
        assertEquals(PhaseOrder.NONE, decide(s.copy(fluxLevel = 0.5f)))
        assertEquals(PhaseOrder.NONE, decide(s.copy(travelDestDistance = 1500f)))
    }

    @Test
    fun `相位赶路途中保持 辐能未超标拦截无威胁上浮`() {
        // 赶路相位中、辐能 0.2 < 0.35：拦截无威胁上浮保持相位赶路
        val s = phasedSituation().copy(
            engagedEnemyNear = false,
            travelDestDistance = 3000f,
            fluxLevel = 0.2f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        // 辐能积累超标（≥0.35）：放行上浮耗散，维持低辐赶路循环
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(fluxLevel = 0.4f)))
        // 无赶路目标点：拦截失效，无威胁上浮正常触发
        assertEquals(
            PhaseOrder.SURFACE,
            decide(s.copy(travelDestDistance = Float.MAX_VALUE)),
        )
    }

    // ---------- 迫近意图 ----------

    @Test
    fun `迫近下潜闸门逐项翻转`() {
        // 基准：非相位、斗篷就绪、攻击目标 3000su、有效射程 1000su、武器全就绪、硬辐能低、无来袭
        val base = unphasedSituation().copy(
            targetDistance = 3000f,
            effectiveWeaponRange = 1000f,
        )
        assertTrue(PhaseToggleTree.isPressApproachDive(base))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(phased = true)))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(cloakReady = false)))
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(retreating = true)))
        // 纯导弹舰（无非导弹武器射程）无迫近收益
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(effectiveWeaponRange = 0f)))
        // 无有效攻击目标
        assertFalse(PhaseToggleTree.isPressApproachDive(base.copy(targetDistance = Float.MAX_VALUE)))
        // 距离低于最小迫近距离（开关僵直大于迫近收益）
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(targetDistance = PhaseToggleTree.PRESS_APPROACH_MIN_DIST - 1f),
            ),
        )
        // 距离未超射程裕量（其余闸门均满足的隔离口径）
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(
                    effectiveWeaponRange = 2000f,
                    targetDistance = 2000f * PhaseToggleTree.PRESS_APPROACH_RANGE_MULT,
                ),
            ),
        )
        // 武器就绪不足（到位后没有东西可打）
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(weaponsReadyFrac = PhaseToggleTree.PRESS_APPROACH_WEAPONS_FRAC - 0.1f),
            ),
        )
        // 硬辐能超标（相位维持持续涨硬辐，远距迫近需留余量）
        assertFalse(
            PhaseToggleTree.isPressApproachDive(
                base.copy(hardFluxLevel = PhaseToggleTree.PRESS_APPROACH_HARD_FLUX_MAX),
            ),
        )
        // 来袭达威胁阈值（重火力压制下不迫近）
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
        // 途中：窗口未过期、相位中、目标有效且未到位、有效射程非零 → 意图生效
        assertTrue(FlankAttackTree.pressIntentActive(5f, true, onRouteDist, range))
        // 窗口过期
        assertFalse(FlankAttackTree.pressIntentActive(0f, true, onRouteDist, range))
        // 未相位
        assertFalse(FlankAttackTree.pressIntentActive(5f, false, onRouteDist, range))
        // 无有效攻击目标
        assertFalse(FlankAttackTree.pressIntentActive(5f, true, Float.MAX_VALUE, range))
        // 武器途中打光/禁用（有效射程为零）：意图失效，防白潜
        assertFalse(FlankAttackTree.pressIntentActive(5f, true, onRouteDist, 0f))
        // 到位（≤ 射程 × 放行系数）：意图失效，时长闸放宽与走位驱动同拍停止
        assertFalse(
            FlankAttackTree.pressIntentActive(
                5f, true, range * PhaseToggleTree.PRESS_SURFACE_RANGE_FRAC, range,
            ),
        )
    }

    @Test
    fun `迫近意图生效期间拦截一切主动上浮`() {
        // 生效口径已收口到采样端纯函数：规则只看 pressIntentActive，生效即按住
        val active = phasedSituation().copy(pressIntentActive = true, engagedEnemyNear = false)
        assertEquals(PhaseOrder.NONE, PressIntentRule.evaluate(PhaseDecisionContext(active)))
        // 未相位/意图失效（含到位收口）：弃权放行
        assertNull(PressIntentRule.evaluate(PhaseDecisionContext(active.copy(phased = false))))
        assertNull(
            PressIntentRule.evaluate(PhaseDecisionContext(active.copy(pressIntentActive = false))),
        )
    }

    @Test
    fun `迫近到位后输出窗口上浮`() {
        // 到位后意图已由采样端收口失效（pressIntentActive=false），目标在射程内且交战、
        // 武器就绪达闸、其余上浮闸门安全：输出窗口规则接管，上浮开打
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
        // 同时满足迫近（攻击目标 3000su 超出射程）与赶路（赶路目标点 3000su 且脱战）：
        // 迫近有明确攻击目标，先于无目标赶路触发
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
        // 来袭轻微且交战圈无敌舰：无威胁上浮本会把船拉出水面，迫近意图途中必须按住
        val s = phasedSituation().copy(
            pressIntentActive = true,
            engagedEnemyNear = false,
            incomingNearDamage = 0f,
            targetDistance = 3000f,
            effectiveWeaponRange = 1000f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))
        assertEquals("PressIntent", toggleTree.lastFiredRuleId)
        // 无意图时同参数走无威胁上浮
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(pressIntentActive = false)))
    }

    @Test
    fun `迫近意图生效时走位穿透驱动 不做就地保持`() {
        val tree = FlankAttackTree()
        // 迫近意图（无绕后意图）且已入目标侧后、脱间距满足：仍穿透驱动——
        // 迫近到位由 A 树放行上浮输出，不挂 PHASE_ATTACK_RUN_IN_GOOD_SPOT 罚站
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
        // 未相位/暂缓中：驱动不生效
        assertFalse(FlankAttackTree.maneuverDriveActive(s.copy(phased = false)))
        assertFalse(FlankAttackTree.maneuverDriveActive(s.copy(forcedSurfaceHoldSec = 0.2f)))
    }

    @Test
    fun `迫近意图期间放宽相位时长上限`() {
        // 常规上限（8s）到意图上限（武装窗口 12s + 2s 富余）之间：迫近意图生效时不强制上浮
        // （硬辐能维持低位，避开不受放宽的辐能闸）
        val s = phasedSituation().copy(
            pressIntentActive = true,
            engagedEnemyNear = false,
            targetDistance = 3000f,
            effectiveWeaponRange = 1000f,
            phaseActiveTime = PhaseToggleTree.MAX_PHASE_TIME_SEC + 1f,
        )
        assertEquals(PhaseOrder.NONE, decide(s))

        // 无意图时同一时长早已触发强制上浮
        assertEquals(PhaseOrder.SURFACE, decide(s.copy(pressIntentActive = false)))

        // 意图放宽上限（武装窗口 + 富余）到达后同样强制上浮
        val overtime = s.copy(
            phaseActiveTime = FlankAttackTree.FLANK_INTENT_SEC +
                    PhaseToggleTree.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, decide(overtime))
    }

    // ---------- 树级行为 ----------

    /** 记录评估次序的 stub 规则：命中时把 id 记入 [evaluated]。 */
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

    /** stub 树：全弃权回落 [PhaseOrder.NONE]。 */
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
        // 全弃权时规则按优先级全部被评估
        assertEquals(listOf("abstainA", "abstainB"), evaluated)
        assertNull(tree.lastFiredRuleId)
    }

    @Test
    fun `树级 C 树互斥 A 树下潜指令压制 vent 链路`() {
        // 战术耗散口径成立的态势：A 树已决 DIVE 时压制优先，无开关指令时才 VENT
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
        // A 树决出上浮：战术耗散不出手，也不压制（无耗软辐宽限口径）
        assertEquals(VentOrder.NONE, tree.decide(PhaseDecisionContext(s, PhaseOrder.SURFACE)))
        assertNull(tree.lastFiredRuleId)
    }

    @Test
    fun `树级 B 树绕后走位规则表结论映射`() {
        val tree = FlankAttackTree()
        // 意图未生效：全弃权回落 NONE
        assertEquals(FlankOrder.NONE, tree.decide(PhaseDecisionContext(phasedSituation())))
        // 强制上浮落点暂缓：最高优先后撤（意图生效也被压过）
        val holding = phasedSituation().copy(forcedSurfaceHoldSec = 1f, flankIntentActive = true)
        assertEquals(FlankOrder.BACKOFF, tree.decide(PhaseDecisionContext(holding)))
        assertEquals("ForcedHoldBackoff", tree.lastFiredRuleId)
        // 意图途中未入侧后：穿透驱动
        val driving = phasedSituation().copy(flankIntentActive = true, inTargetRearArc = false)
        assertEquals(FlankOrder.DRIVE, tree.decide(PhaseDecisionContext(driving)))
        assertEquals("FlankDrive", tree.lastFiredRuleId)
        // 意图途中已入侧后且脱间距满足：就地保持
        val inSpot = phasedSituation().copy(flankIntentActive = true, inTargetRearArc = true)
        assertEquals(FlankOrder.HOLD_SPOT, tree.decide(PhaseDecisionContext(inSpot)))
        assertEquals("FlankHoldSpot", tree.lastFiredRuleId)
        // 已入侧后但落点重叠（原版斗篷拒绝退相位）：不就地保持，继续穿透
        val overlap = inSpot.copy(unphaseUnsafe = true)
        assertEquals(FlankOrder.DRIVE, tree.decide(PhaseDecisionContext(overlap)))
    }
}
