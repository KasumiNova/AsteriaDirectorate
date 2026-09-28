package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakAI.Companion.PhaseOrder
import cn.kasuminova.astd.combat.shipsystems.GravityPhaseCloakAI.Companion.PhaseSituation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        softFluxLevel = 0.1f,
        targetLowMobility = false,
        inTargetRearArc = false,
        weaponCoverage = 2,
        friendlyCatchDamage = 0f,
        flankIntentActive = false,
        flankIntentWindowSec = GravityPhaseCloakAI.FLANK_INTENT_SEC,
        threatDistance = 1000f,
        unphaseUnsafe = false,
        incomingFriendlySoonDamage = 0f,
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
        softFluxLevel = 0.1f,
        targetLowMobility = false,
        inTargetRearArc = false,
        weaponCoverage = 2,
        friendlyCatchDamage = 0f,
        flankIntentActive = false,
        flankIntentWindowSec = GravityPhaseCloakAI.FLANK_INTENT_SEC,
        threatDistance = 1000f,
        unphaseUnsafe = false,
        incomingFriendlySoonDamage = 0f,
    )

    @Test
    fun `硬辐能达闸强制上浮`() {
        val s = phasedSituation().copy(hardFluxLevel = GravityPhaseCloakAI.SURFACE_HARD_FLUX)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `上浮落点重叠不安全时按住一切上浮指令`() {
        // 强制上浮路径（硬辐能达闸）：落点重叠时被按住，漂出重叠后放行
        val unsafe = phasedSituation().copy(
            hardFluxLevel = GravityPhaseCloakAI.SURFACE_HARD_FLUX,
            unphaseUnsafe = true,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(unsafe))
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(unsafe.copy(unphaseUnsafe = false)))
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

    @Test
    fun `软辐可观且环境安全时耗软辐下潜`() {
        val s = unphasedSituation().copy(
            softFluxLevel = GravityPhaseCloakAI.SOFT_FLUX_DIVE_MIN,
            fluxLevel = 0.55f,
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `辐能水平接近过载时压低耗软辐下潜积极性`() {
        val s = unphasedSituation().copy(
            softFluxLevel = 0.4f,
            fluxLevel = GravityPhaseCloakAI.SOFT_FLUX_DIVE_MAX_FLUX_LEVEL,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `耗软辐下潜要求环境安全`() {
        val s = unphasedSituation().copy(
            softFluxLevel = 0.4f,
            fluxLevel = 0.55f,
            incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f) *
                    GravityPhaseCloakAI.SOFT_FLUX_DIVE_SAFE_NEAR_FRAC,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `低机动目标触发绕后下潜 已在侧后则不重复下潜`() {
        val s = unphasedSituation().copy(targetLowMobility = true)
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))

        val alreadyBehind = s.copy(inTargetRearArc = true)
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(alreadyBehind))
    }

    @Test
    fun `绕后下潜受武器就绪与硬辐能约束`() {
        val weaponsNotReady = unphasedSituation().copy(
            targetLowMobility = true,
            weaponsReadyFrac = GravityPhaseCloakAI.FLANK_DIVE_WEAPONS_FRAC - 0.1f,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(weaponsNotReady))

        val fluxHigh = unphasedSituation().copy(
            targetLowMobility = true,
            hardFluxLevel = GravityPhaseCloakAI.FLANK_DIVE_HARD_FLUX_MAX,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(fluxHigh))
    }

    @Test
    fun `相位中进入目标侧后死角且覆盖稀少时立即上浮`() {
        val s = phasedSituation().copy(inTargetRearArc = true, weaponCoverage = 1)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `武器覆盖超闸时拦截主动上浮继续等死角`() {
        val s = phasedSituation().copy(
            systemReady = true,
            weaponsReadyFrac = 1f,
            weaponCoverage = GravityPhaseCloakAI.SURFACE_COVERAGE_MAX + 1,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `强制上浮不受覆盖闸约束`() {
        val s = phasedSituation().copy(
            hardFluxLevel = GravityPhaseCloakAI.SURFACE_HARD_FLUX,
            weaponCoverage = 99,
        )
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `穿透火力将误伤友军时压制非濒危下潜`() {
        val threatened = unphasedSituation().copy(
            incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f),
            friendlyCatchDamage = GravityPhaseCloakAI.FRIENDLY_CATCH_DAMAGE_MIN,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(threatened))

        val emergencySuppressed = unphasedSituation().copy(
            incomingSoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f),
            friendlyCatchDamage = GravityPhaseCloakAI.FRIENDLY_CATCH_DAMAGE_MIN,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(emergencySuppressed))
    }

    @Test
    fun `自身濒危时无视友军接盘风险下潜`() {
        val s = unphasedSituation().copy(
            incomingSoonDamage = 5000f * GravityPhaseCloakAI.LETHAL_SOON_HULL_FRACTION,
            friendlyCatchDamage = 9999f,
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `低机动代理指标口径`() {
        // 极速达标即低机动
        assertTrue(GravityPhaseCloakAI.isLowMobilityTarget(GravityPhaseCloakAI.LOW_MOBILITY_MAX_SPEED, 200f, 90f))
        // 转向与加速度双低也是低机动
        assertTrue(GravityPhaseCloakAI.isLowMobilityTarget(120f, GravityPhaseCloakAI.LOW_MOBILITY_ACCEL, GravityPhaseCloakAI.LOW_MOBILITY_TURN_RATE))
        // 三项都不低不算低机动
        assertFalse(GravityPhaseCloakAI.isLowMobilityTarget(120f, 200f, 90f))
    }

    @Test
    fun `穿透弹道线段与友军碰撞圈相交判定`() {
        // 正向贯穿：线段从原点附近直指圆心
        assertTrue(GravityPhaseCloakAI.segmentHitsCircle(0f, 0f, 100f, 0f, 80f, 0f, 20f))
        // 横向偏离超过半径不命中
        assertFalse(GravityPhaseCloakAI.segmentHitsCircle(0f, 0f, 100f, 0f, 80f, 30f, 20f))
        // 圆在线段起点后方不命中（投影截断到线段内）
        assertFalse(GravityPhaseCloakAI.segmentHitsCircle(0f, 0f, 100f, 0f, -50f, 0f, 20f))
        // 零长度线段不命中
        assertFalse(GravityPhaseCloakAI.segmentHitsCircle(0f, 0f, 0f, 0f, 5f, 0f, 20f))
    }

    @Test
    fun `角度差纯函数口径`() {
        assertEquals(120f, GravityPhaseCloakAI.angleDiffAbs(0f, 120f), 1e-4f)
        assertEquals(120f, GravityPhaseCloakAI.angleDiffAbs(0f, -120f), 1e-4f)
        assertEquals(10f, GravityPhaseCloakAI.angleDiffAbs(350f, 0f), 1e-4f)
        assertEquals(180f, GravityPhaseCloakAI.angleDiffAbs(90f, 270f), 1e-4f)
    }

    @Test
    fun `耗软辐闸门单一口径 decide 与 vent 压制共用`() {
        // 完整闸门成立：软辐可观、辐能有余量、环境安全、有交战对象
        val gateOpen = unphasedSituation().copy(softFluxLevel = 0.4f, fluxLevel = 0.55f)
        assertTrue(GravityPhaseCloakAI.isSoftFluxDumpDive(gateOpen))
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(gateOpen))

        // 脱战（无交战对象）：闸门不成立——vent 不应被压制，decide 也不下潜
        val disengaged = gateOpen.copy(engagedEnemyNear = false)
        assertFalse(GravityPhaseCloakAI.isSoftFluxDumpDive(disengaged))
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(disengaged))

        // 来袭偏高：闸门不成立，两侧同样都不动作
        val unsafe = gateOpen.copy(
            incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f) *
                    GravityPhaseCloakAI.SOFT_FLUX_DIVE_SAFE_NEAR_FRAC,
        )
        assertFalse(GravityPhaseCloakAI.isSoftFluxDumpDive(unsafe))
        // 注意：该来袭量低于威胁下潜阈值，decide 整体也不下潜
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(unsafe))

        // 斗篷未就绪（冷却中）：闸门不成立，vent 放行兜底
        val cloakCooling = gateOpen.copy(cloakReady = false)
        assertFalse(GravityPhaseCloakAI.isSoftFluxDumpDive(cloakCooling))
    }

    @Test
    fun `光束威胁折算口径`() {
        // 持续光束：DPS × 窗口秒计入 near
        assertEquals(
            600f * GravityPhaseCloakAI.CONT_BEAM_THREAT_WINDOW_SEC,
            GravityPhaseCloakAI.beamThreatNear(600f),
            1e-4f,
        )
        // 爆发光束：爆发总伤 × 权重计入 soon
        assertEquals(
            3500f * GravityPhaseCloakAI.BURST_BEAM_THREAT_WEIGHT,
            GravityPhaseCloakAI.beamThreatSoon(3500f),
            1e-4f,
        )
        // 折算单调性：高伤光束威胁值高于低伤光束
        assertTrue(GravityPhaseCloakAI.beamThreatSoon(3500f) > GravityPhaseCloakAI.beamThreatSoon(500f))
        assertTrue(GravityPhaseCloakAI.beamThreatNear(600f) > GravityPhaseCloakAI.beamThreatNear(100f))
    }

    @Test
    fun `高伤光束折算进 soon 窗口后触发紧急下潜`() {
        // 爆发光束照射的折算伤害进入 soon 窗口，达到紧急下潜阈值即下潜（相位断照射）
        val s = unphasedSituation().copy(
            incomingSoonDamage = GravityPhaseCloakAI.beamThreatSoon(3500f),
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `绕后闸门单一口径 decide 与意图布防共用`() {
        // 完整闸门成立：低机动目标、未入侧后、武器过半、硬辐能有余量、环境安全
        val gateOpen = unphasedSituation().copy(targetLowMobility = true)
        assertTrue(GravityPhaseCloakAI.isFlankDive(gateOpen))
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(gateOpen))

        // 目标高机动：闸门不成立，decide 也不下潜
        val mobile = gateOpen.copy(targetLowMobility = false)
        assertFalse(GravityPhaseCloakAI.isFlankDive(mobile))
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(mobile))

        // 已在侧后：闸门不成立（无需重复穿透）
        val behind = gateOpen.copy(inTargetRearArc = true)
        assertFalse(GravityPhaseCloakAI.isFlankDive(behind))
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(behind))

        // 来袭达威胁阈值：闸门不成立（此时走威胁下潜链路，不布防绕后意图）
        val unsafe = gateOpen.copy(incomingNearDamage = GravityPhaseCloakAI.diveNearThreshold(5000f))
        assertFalse(GravityPhaseCloakAI.isFlankDive(unsafe))
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(unsafe))
    }

    @Test
    fun `绕后意图途中拦截战术上浮保持相位穿透`() {
        // 攻击系统就绪本可战术上浮，意图途中（未入侧后）保持相位机动
        val s = phasedSituation().copy(flankIntentActive = true, systemReady = true)
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `绕后意图期间放宽相位时长上限`() {
        // 常规上限（8s）到意图上限（基准窗口 12s + 2s 富余）之间：意图生效时不强制上浮
        val intent = phasedSituation().copy(
            flankIntentActive = true,
            phaseActiveTime = GravityPhaseCloakAI.MAX_PHASE_TIME_SEC + 2f,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(intent))

        // 无意图时同一时长早已触发强制上浮
        val noIntent = intent.copy(flankIntentActive = false)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(noIntent))

        // 意图放宽上限（武装窗口 + 富余）到达后同样强制上浮
        val overtime = intent.copy(
            phaseActiveTime = GravityPhaseCloakAI.FLANK_INTENT_SEC +
                    GravityPhaseCloakAI.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(overtime))
    }

    @Test
    fun `绕后意图相位时长上限跟随按距离武装的窗口`() {
        // 远处布防武装的长窗口（24s）下，超过基准意图上限（14s）仍在穿透途中不强制上浮
        val farArmed = phasedSituation().copy(
            flankIntentActive = true,
            flankIntentWindowSec = GravityPhaseCloakAI.FLANK_INTENT_MAX_SEC,
            phaseActiveTime = GravityPhaseCloakAI.FLANK_INTENT_SEC +
                    GravityPhaseCloakAI.FLANK_PHASE_CAP_MARGIN_SEC + 2f,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(farArmed))

        // 到达长窗口 + 富余后强制上浮
        val overtime = farArmed.copy(
            phaseActiveTime = GravityPhaseCloakAI.FLANK_INTENT_MAX_SEC +
                    GravityPhaseCloakAI.FLANK_PHASE_CAP_MARGIN_SEC,
        )
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(overtime))
    }

    @Test
    fun `绕后意图窗口按布防距离缩放且夹取上下限`() {
        // 下限夹取：近距离布防窗口不再缩短（半参考距离与参考距离同窗口）
        assertEquals(
            GravityPhaseCloakAI.flankIntentWindowSec(GravityPhaseCloakAI.FLANK_INTENT_REF_DIST * 0.5f),
            GravityPhaseCloakAI.flankIntentWindowSec(GravityPhaseCloakAI.FLANK_INTENT_REF_DIST),
        )
        // 线性放大：两倍参考距离的窗口大于参考距离
        val ref = GravityPhaseCloakAI.flankIntentWindowSec(GravityPhaseCloakAI.FLANK_INTENT_REF_DIST)
        val doubled = GravityPhaseCloakAI.flankIntentWindowSec(GravityPhaseCloakAI.FLANK_INTENT_REF_DIST * 2f)
        assertTrue(doubled > ref)
        // 上限封顶：到达上限距离后窗口不再增长
        assertEquals(
            GravityPhaseCloakAI.flankIntentWindowSec(GravityPhaseCloakAI.FLANK_INTENT_REF_DIST * 10f),
            doubled,
        )
    }

    @Test
    fun `绕后意图达成后放宽覆盖闸上浮输出`() {
        // 覆盖 2 超过死角上限（1）但未超常规上限（3）：无意图时继续等死角，意图达成即上浮
        val s = phasedSituation().copy(
            inTargetRearArc = true,
            weaponCoverage = GravityPhaseCloakAI.REAR_SURFACE_MAX_COVERAGE + 1,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))

        val achieved = s.copy(flankIntentActive = true)
        assertEquals(PhaseOrder.SURFACE, GravityPhaseCloakAI.decide(achieved))
    }

    @Test
    fun `友军火力达阈触发防御下潜`() {
        // 友军高伤火力在 soon 窗口烧向本舰：与敌方来袭同口径触发紧急下潜
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `友军火力只走防御链路不触发战术下潜`() {
        // 未达紧急阈值的友军火力不引发任何下潜（不参与绕后/耗软辐/威胁等战术链路）
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f) - 1f,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `友军火力烧身未濒危时受让渡闸压制`() {
        // 达紧急阈值但未濒危：穿透误伤友军的接盘风险优先，按住不下潜
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f),
            friendlyCatchDamage = GravityPhaseCloakAI.FRIENDLY_CATCH_DAMAGE_MIN,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `友军火力烧身濒危时无视接盘闸下潜`() {
        // 与友军接盘博弈时自身生存优先：致命友军火力烧身仍可紧急下潜
        val s = unphasedSituation().copy(
            incomingFriendlySoonDamage = 5000f * GravityPhaseCloakAI.LETHAL_SOON_HULL_FRACTION,
            friendlyCatchDamage = 9999f,
        )
        assertEquals(PhaseOrder.DIVE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `相位中被友军火力持续照射时按住不上浮`() {
        // 友军火力并入即将受击闸：上浮即被烧，战术上浮也被拦下
        val s = phasedSituation().copy(
            systemReady = true,
            incomingFriendlySoonDamage = GravityPhaseCloakAI.diveSoonThreshold(5000f),
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }

    @Test
    fun `相位中友军火力烧身致命时强制上浮被豁免`() {
        val s = phasedSituation().copy(
            hardFluxLevel = GravityPhaseCloakAI.SURFACE_HARD_FLUX,
            incomingFriendlySoonDamage = 5000f * GravityPhaseCloakAI.LETHAL_SOON_HULL_FRACTION,
        )
        assertEquals(PhaseOrder.NONE, GravityPhaseCloakAI.decide(s))
    }
}
