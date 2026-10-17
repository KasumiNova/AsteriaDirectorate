package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseOrder
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.apache.logging.log4j.LogManager
import org.lwjgl.util.vector.Vector2f

/**
 * 引力相位（astd_gravity_phase）防御系统 AI（密蒙级 ZW-002 / 舜华级 ZW-101 / 茑萝级 ZW-103 共用）。
 *
 * 替换原版 PHASE_CLOAK AI（PhaseCloakShieldAI）的动机：原版决策面向纯相位舰，
 * 本系舰船相位之外还携带需要非相位状态施放的攻击系统（引力磁暴/引力空间复制器/引力裂隙），
 * 原版 AI 的「武器装填下潜 + STAY_PHASED 续潜」链路会让舰船长时间相位潜水——
 * 相位维持辐能持续累积硬辐能（noHardDissipation + hardFlux），硬辐能越高相位内航速越低，
 * 最终被压制类舰船堵在相位里憋死，攻击系统全程空转。
 *
 * 本类为多决策树架构的协调器薄壳，决策逻辑全部外迁：
 * - 态势采样：[GravityPhaseSituationSampler]（一拍 [cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation] 快照）；
 * - Mutex A 斗篷开关：[PhaseToggleTree]（DIVE/SURFACE/NONE，23 条规则按优先级评估）；
 * - Mutex B 绕后走位：[FlankAttackTree]（DRIVE/HOLD_SPOT/BACKOFF 旗标组）；
 * - Mutex C vent 链路：[FluxVentTree]（SUPPRESS/VENT/NONE）。
 *
 * 本类只负责：状态时钟推进（phaseActiveTime/timeSinceUnphase/toggleGuard/flankIntent/
 * forcedSurfaceHold 节拍）、无条件旗标维护（相位中 DO_NOT_VENT、冷却期残损 BACK_OFF）、
 * 依次评估 A→B→C、执行开关/旗标/vent 指令与决策留痕日志。
 *
 * 互斥收敛规则（原实现隐式耦合的显式化）：
 * - A=DIVE → C 由 [DiveOrderedSuppressRule] 自然压制（树间互斥入规则）；
 * - A 非 NONE → C 的 VENT 不出手（[TacticalVentRule] 要求 cloakOrder==NONE）；
 * - B 的 DRIVE/HOLD_SPOT 仅在 forcedSurfaceHoldSec <= 0 生效
 *   （内嵌于 [FlankAttackTree.flankDriveActive]，暂缓期间由 [ForcedHoldBackoffRule] 接管后撤）。
 *
 * 上浮僵直保护：强制上浮后挂 [ShipwideAIFlags.AIFlags.BACK_OFF]（2s）让舰船后撤度过
 * 斗篷冷却窗口；斗篷冷却中且硬辐能过半/舰体残损时持续刷新 BACK_OFF。
 */
class GravityPhaseCloakAI : ShipSystemAIScript {

    companion object {
        private val log = LogManager.getLogger(GravityPhaseCloakAI::class.java)

        /** 连续两次斗篷开关指令的最小间隔（s），对齐原版 0.5s 口径。 */
        private const val TOGGLE_GUARD_SEC = 0.5f

        /** 强制上浮后的后撤保护时长（s）。 */
        private const val SURFACE_BACKOFF_SEC = 2.0f

        /** 斗篷冷却期后撤保护的硬辐能闸。 */
        private const val BACKOFF_HARD_FLUX = 0.5f

        /** 斗篷冷却期后撤保护的舰体闸。 */
        private const val BACKOFF_HULL_LEVEL = 0.3f

        /** 压制强制耗散的旗帜时长（s），按评估节奏刷新。 */
        private const val VENT_SUPPRESS_FLAG_SEC = 0.5f
    }

    private var ship: ShipAPI? = null
    private var engine: CombatEngineAPI? = null

    private val sampler = GravityPhaseSituationSampler()
    private val toggleTree = PhaseToggleTree()
    private val flankTree = FlankAttackTree()
    private val ventTree = FluxVentTree()

    private val scanInterval =
        IntervalUtil(PhaseToggleTree.SCAN_INTERVAL_SEC, PhaseToggleTree.SCAN_INTERVAL_SEC)

    /** 已连续相位时长（s），相位外清零。 */
    private var phaseActiveTime = 0f

    /** 距上次完全上浮的时长（s）。 */
    private var timeSinceUnphase = PhaseToggleTree.MIN_UNPHASE_TIME_SEC

    /** 距上次斗篷开关指令的时长（s）。 */
    private var toggleGuardElapsed = TOGGLE_GUARD_SEC

    /** 绕后意图剩余时长（s）：绕后下潜布防后递减，上浮即清零。 */
    private var flankIntentRemaining = 0f

    /** 布防时武装的绕后意图窗口长度（s）：随 [flankIntentRemaining] 一同布防，供相位时长上限推导。 */
    private var flankIntentWindowArmed = FlankAttackTree.FLANK_INTENT_SEC

    /** 强制上浮被落点安全闸按住的累计时长（s）：随决策节拍累计，未触发/落点安全即清零。 */
    private var forcedSurfaceHoldSec = 0f

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.engine = engine
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = this.ship ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk || !ship.isAlive) return
        // 原版接线（ShipSystemSpec.createSystemAI CUSTOM 分支）固定以 ship.getSystem()
        // （舰船主系统，本系舰船即攻击系统）调 init，与 aiScript 挂在哪个系统条目无关；
        // 相位斗篷挂防御槽（ship.phaseCloak），必须显式取用，不得用 init 传入的形参
        val cloak = ship.phaseCloak ?: ship.system ?: return
        if (ship.isDefenseDisabled) return

        val phased = cloak.state == ShipSystemAPI.SystemState.IN ||
                cloak.state == ShipSystemAPI.SystemState.ACTIVE
        if (phased) {
            phaseActiveTime += amount
            timeSinceUnphase = 0f
            // 对齐原版口径：相位中不排气（无条件旗标维护，不进决策树）
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, 0.3f)
        } else {
            timeSinceUnphase += amount
            if (cloak.state == ShipSystemAPI.SystemState.IDLE ||
                cloak.state == ShipSystemAPI.SystemState.COOLDOWN
            ) {
                phaseActiveTime = 0f
            }
            // 上浮即结束绕后意图（达成或中止都回到常规节奏）
            flankIntentRemaining = 0f
        }
        toggleGuardElapsed += amount
        flankIntentRemaining = (flankIntentRemaining - amount).coerceAtLeast(0f)

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val sampled = sampler.sample(
            engine, ship, cloak, phased, target,
            phaseActiveTime, timeSinceUnphase, flankIntentRemaining, flankIntentWindowArmed,
        )
        // 强制上浮落点安全暂缓预算节拍：触发且落点不安全时随决策节拍累计，
        // 致命豁免期暂停（豁免是等这一下过去而非等安全落点，不该烧兜底预算），
        // 未触发/落点安全即清零；与 A 树的强制上浮规则共用
        // [PhaseToggleTree.isForcedSurfaceTrigger]/[PhaseToggleTree.isHoldForLethal] 单一口径
        forcedSurfaceHoldSec = PhaseToggleTree.nextForcedSurfaceHoldSec(sampled, forcedSurfaceHoldSec)
        val situation = sampled.copy(forcedSurfaceHoldSec = forcedSurfaceHoldSec)

        // 上浮僵直保护：斗篷冷却窗口内硬辐能过半或舰体残损且有交战对象时持续后撤
        // （无条件旗标维护，不进决策树）
        if (!phased && cloak.state == ShipSystemAPI.SystemState.COOLDOWN &&
            (ship.hardFluxLevel >= BACKOFF_HARD_FLUX || ship.hullLevel < BACKOFF_HULL_LEVEL) &&
            situation.engagedEnemyNear
        ) {
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, 0.5f)
        }

        // 依次评估 A（斗篷开关）→ B（绕后走位）→ C（vent 链路）；A 结论经上下文传入 B/C
        // 作树间互斥判据（见类 KDoc 互斥收敛规则）
        val cloakOrder = toggleTree.decide(PhaseDecisionContext(situation))
        val flankOrder = flankTree.decide(PhaseDecisionContext(situation, cloakOrder))
        val ventOrder = ventTree.decide(PhaseDecisionContext(situation, cloakOrder))

        // C 树执行：vent 压制/主动耗散（决策优先级：紧急避险 > 相位耗软辐 > 强制耗散）
        when (ventOrder) {
            VentOrder.SUPPRESS ->
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, VENT_SUPPRESS_FLAG_SEC)

            VentOrder.VENT -> ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
            VentOrder.NONE -> Unit
        }

        // B 树执行：绕后走位旗标组/落点暂缓后撤；两旗标短时效滚动刷新，
        // 意图结束/上浮后自然过期，无需手动 unset
        when (flankOrder) {
            FlankOrder.BACKOFF ->
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, 0.5f)

            FlankOrder.DRIVE -> {
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, 0.5f)
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN, 0.5f)
            }

            FlankOrder.HOLD_SPOT -> {
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, 0.5f)
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN_IN_GOOD_SPOT, 0.5f)
            }

            FlankOrder.NONE -> Unit
        }

        // A 树执行：斗篷开关（受开关守卫约束；旗标维护不受守卫约束，已在上面完成）
        if (toggleGuardElapsed < TOGGLE_GUARD_SEC) return
        when (cloakOrder) {
            PhaseOrder.DIVE -> {
                // 下潜决策留痕：与上浮留痕同口径记录命中规则，便于实机回放定位
                val diveDistText =
                    if (situation.threatDistance == Float.MAX_VALUE) "none"
                    else "%.0f".format(situation.threatDistance)
                log.info(
                    "[GravityPhaseAI] ${ship.name} 下潜：rule=${toggleTree.lastFiredRuleId} " +
                            "hardFlux=${"%.2f".format(situation.hardFluxLevel)} " +
                            "flux=${"%.2f".format(situation.fluxLevel)} " +
                            "near=${"%.0f".format(situation.incomingNearDamage)} " +
                            "soon=${"%.0f".format(situation.incomingSoonDamage)} " +
                            "engaged=${situation.engagedEnemyNear} dist=$diveDistText",
                )
                toggleCloak(ship)
                // 绕后意图布防：绕后下潜命中完整闸门且开关指令真正发出时才开启意图窗口
                // （闸门与 A 树的 FlankDiveRule 共用 [FlankAttackTree.isFlankDive]；
                // 开关被守卫拦住时不空布防）
                if (flankTree.shouldArmIntent(situation)) {
                    // 窗口按布防距离缩放：穿透机动的时间预算就是位移预算（见 flankIntentWindowSec）
                    flankIntentRemaining =
                        FlankAttackTree.flankIntentWindowSec(situation.threatDistance)
                    flankIntentWindowArmed = flankIntentRemaining
                    log.info(
                        "[GravityPhaseAI] ${ship.name} 绕后意图布防：目标低机动，" +
                                "PHASE_ATTACK_RUN 驱动穿透窗口 ${"%.1f".format(flankIntentRemaining)}s" +
                                "（距离 ${"%.0f".format(situation.threatDistance)}su）",
                    )
                }
            }

            PhaseOrder.SURFACE -> {
                // 上浮决策留痕：定位相位期过短/意外上浮的判定入口（低频事件，一场战斗十余次）
                val distText =
                    if (situation.threatDistance == Float.MAX_VALUE) "none"
                    else "%.0f".format(situation.threatDistance)
                log.info(
                    "[GravityPhaseAI] ${ship.name} 上浮：rule=${toggleTree.lastFiredRuleId} " +
                            "phaseTime=${"%.1f".format(situation.phaseActiveTime)}s " +
                            "hardFlux=${"%.2f".format(situation.hardFluxLevel)} engaged=${situation.engagedEnemyNear} " +
                            "near=${"%.0f".format(situation.incomingNearDamage)} soon=${"%.0f".format(situation.incomingSoonDamage)} " +
                            "coverage=${situation.weaponCoverage} rearArc=${situation.inTargetRearArc} " +
                            "intent=${situation.flankIntentActive} systemReady=${situation.systemReady} " +
                            "weaponsReady=${"%.2f".format(situation.weaponsReadyFrac)} " +
                            "dist=$distText sepSafe=${situation.rearSurfaceDistanceSafe}",
                )
                toggleCloak(ship)
                if (PhaseToggleTree.isForcedSurface(situation)) {
                    ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, SURFACE_BACKOFF_SEC)
                }
            }

            PhaseOrder.NONE -> Unit
        }
    }

    /** 相位斗篷开关：原版对防御槽相位斗篷走 TOGGLE_SHIELD_OR_PHASE_CLOAK 指令（toggle 系统再次触发即反向充能）。 */
    private fun toggleCloak(ship: ShipAPI) {
        toggleGuardElapsed = 0f
        ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }
}
