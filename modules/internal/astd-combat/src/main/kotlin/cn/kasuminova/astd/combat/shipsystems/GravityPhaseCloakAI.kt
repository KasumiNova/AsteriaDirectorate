package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseOrder
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lwjgl.util.vector.Vector2f

class GravityPhaseCloakAI : ShipSystemAIScript {

    companion object {
        private val log = Global.getLogger(GravityPhaseCloakAI::class.java)

        // 对齐原版斗篷开关最小间隔 0.5s
        private const val TOGGLE_GUARD_SEC = 0.5f

        private const val SURFACE_BACKOFF_SEC = 2.0f

        private const val BACKOFF_HARD_FLUX = 0.5f

        private const val BACKOFF_HULL_LEVEL = 0.3f

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

    private var phaseActiveTime = 0f

    private var timeSinceUnphase = PhaseToggleTree.MIN_UNPHASE_TIME_SEC

    private var toggleGuardElapsed = TOGGLE_GUARD_SEC

    private var flankIntentRemaining = 0f

    private var flankIntentWindowArmed = FlankAttackTree.FLANK_INTENT_SEC

    private var pressIntentRemaining = 0f

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
        // 原版 ShipSystemSpec.createSystemAI 的 CUSTOM 分支固定以 ship.getSystem()（主系统，本系为攻击系统）调 init，
        // 相位斗篷挂防御槽，必须显式取 ship.phaseCloak，不得用 init 传入的形参
        val cloak = ship.phaseCloak ?: ship.system ?: return
        if (ship.isDefenseDisabled) return

        val phased = cloak.state == ShipSystemAPI.SystemState.IN ||
                cloak.state == ShipSystemAPI.SystemState.ACTIVE
        if (phased) {
            phaseActiveTime += amount
            timeSinceUnphase = 0f
            // 原版行为：相位中不排气
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, 0.3f)
        } else {
            timeSinceUnphase += amount
            if (cloak.state == ShipSystemAPI.SystemState.IDLE ||
                cloak.state == ShipSystemAPI.SystemState.COOLDOWN
            ) {
                phaseActiveTime = 0f
            }
            flankIntentRemaining = 0f
            pressIntentRemaining = 0f
        }
        toggleGuardElapsed += amount
        flankIntentRemaining = (flankIntentRemaining - amount).coerceAtLeast(0f)
        pressIntentRemaining = (pressIntentRemaining - amount).coerceAtLeast(0f)

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val sampled = sampler.sample(
            engine, ship, cloak, phased, target,
            phaseActiveTime, timeSinceUnphase, flankIntentRemaining, flankIntentWindowArmed,
            pressIntentRemaining,
        )
        forcedSurfaceHoldSec = PhaseToggleTree.nextForcedSurfaceHoldSec(sampled, forcedSurfaceHoldSec)
        val situation = sampled.copy(forcedSurfaceHoldSec = forcedSurfaceHoldSec)

        if (!phased && cloak.state == ShipSystemAPI.SystemState.COOLDOWN &&
            (ship.hardFluxLevel >= BACKOFF_HARD_FLUX || ship.hullLevel < BACKOFF_HULL_LEVEL) &&
            situation.engagedEnemyNear
        ) {
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, 0.5f)
        }

        val cloakOrder = toggleTree.decide(PhaseDecisionContext(situation))
        val flankOrder = flankTree.decide(PhaseDecisionContext(situation, cloakOrder))
        val ventOrder = ventTree.decide(PhaseDecisionContext(situation, cloakOrder))

        when (ventOrder) {
            VentOrder.SUPPRESS ->
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, VENT_SUPPRESS_FLAG_SEC)

            VentOrder.VENT -> ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
            VentOrder.NONE -> Unit
        }

        // 走位旗标短时效滚动刷新，意图结束或上浮后自然过期，无需手动 unset
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

        if (toggleGuardElapsed < TOGGLE_GUARD_SEC) return
        when (cloakOrder) {
            PhaseOrder.DIVE -> {
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
                if (flankTree.shouldArmIntent(situation)) {
                    flankIntentRemaining =
                        FlankAttackTree.flankIntentWindowSec(situation.threatDistance)
                    flankIntentWindowArmed = flankIntentRemaining
                    log.info(
                        "[GravityPhaseAI] ${ship.name} 绕后意图布防：目标低机动，" +
                                "PHASE_ATTACK_RUN 驱动穿透窗口 ${"%.1f".format(flankIntentRemaining)}s" +
                                "（距离 ${"%.0f".format(situation.threatDistance)}su）",
                    )
                } else if (PhaseToggleTree.isPressApproachDive(situation)) {
                    // 交集窗口内 DIVE 可能由迫近规则触发，此处优先武装绕后意图
                    pressIntentRemaining =
                        FlankAttackTree.flankIntentWindowSec(situation.targetDistance)
                    flankIntentWindowArmed = pressIntentRemaining
                    log.info(
                        "[GravityPhaseAI] ${ship.name} 迫近意图布防：目标超出有效射程，" +
                                "PHASE_ATTACK_RUN 驱动迫近窗口 ${"%.1f".format(pressIntentRemaining)}s" +
                                "（距离 ${"%.0f".format(situation.targetDistance)}su）",
                    )
                }
            }

            PhaseOrder.SURFACE -> {
                val distText =
                    if (situation.threatDistance == Float.MAX_VALUE) "none"
                    else "%.0f".format(situation.threatDistance)
                log.info(
                    "[GravityPhaseAI] ${ship.name} 上浮：rule=${toggleTree.lastFiredRuleId} " +
                            "phaseTime=${"%.1f".format(situation.phaseActiveTime)}s " +
                            "hardFlux=${"%.2f".format(situation.hardFluxLevel)} engaged=${situation.engagedEnemyNear} " +
                            "near=${"%.0f".format(situation.incomingNearDamage)} soon=${"%.0f".format(situation.incomingSoonDamage)} " +
                            "coverage=${situation.weaponCoverage} rearArc=${situation.inTargetRearArc} " +
                            "intent=${situation.flankIntentActive} press=${situation.pressIntentActive} " +
                            "systemReady=${situation.systemReady} " +
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

    // 原版对防御槽相位斗篷走 TOGGLE_SHIELD_OR_PHASE_CLOAK 指令，toggle 系统再次触发即反向充能
    private fun toggleCloak(ship: ShipAPI) {
        toggleGuardElapsed = 0f
        ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }
}
