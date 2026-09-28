package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

/**
 * 飞蓬战机引力联结器场景（断言点 A~G）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class FighterGravLinkScenario : AbstractAutomationScenario() {
    // ==== 飞蓬战机引力联结器场景状态（相位机 SPAWN → WAIT_WINGS → ACTIVATE → OBSERVE_ACTIVE → WAIT_RECALL → RELAUNCH → COMPLETED） ====
    private var fglPhase = FGL_PHASE_SPAWN
    private var fglPhaseStartedAt = 0f

    // WAIT_WINGS（断言点 A）：任一已装联队甲板 extraDeploymentLimit 峰值 / 单联队在场数峰值 / 全场该机战机数峰值。
    private var fglExtraDeploymentLimitMax = 0
    private var fglWingSizeMax = 0
    private var fglFightersInPlayMax = 0

    // ACTIVATE：激活时刻基线（母舰辐能读数与在外战机 identity 集合，断言点 E 的清点底账）。
    private var fglActivatedAt = -1f
    private var fglActivationCurrFlux = -1f
    private var fglActivationHardFlux = -1f

    // WAIT_RECALL：toggle 主动关闭（useSystem fire 路径）的发出节流时间戳；<0 表示尚未发出。
    // 与 ACTIVATE 同款考虑：单次 useSystem() 可能被原版闸门吞掉，未检测到召回前按
    // FGL_MANUAL_CANCEL_RETRY_SECONDS 节流补发，日志只记首次。
    private var fglManualCancelAt = -1f

    // ACTIVATE 重试计数（useSystem 被原版起飞动画窗吞掉时按帧重试，激活确认日志附带）。
    private var fglActivateAttempts = 0

    // 断言点 E 强化底账：各已装联队甲板 numLost 合计（原版语义：numLost 只在战机真正被击毁
    // 路径自增，land 召回不计），召回前后不变才能区分「被召回」与「被击毁」。
    // fglNumLostAtActivation 仅作诊断证据；判定基线是 fglNumLostBeforeRecall——与
    // fglHardFluxBeforeRecall 同样按 ACTIVE 最后一帧滚动采样，把归因窗收窄到召回前后 ~1s，
    // 避免激活→召回整段交战期（约 16s）内正常战损被误判为「召回与击毁混淆」。
    private var fglNumLostAtActivation = -1
    private var fglNumLostBeforeRecall = -1
    private var fglNumLostAfterRecall = -1
    private val fglRecordedFighterIds = mutableSetOf<Int>()
    private var fglRecordedFighterCount = 0

    // OBSERVE_ACTIVE（断言点 B/C/D）：时流/承伤/辐能涨幅采样峰值。
    private var fglTimeMultMax = 0f
    private var fglHullDamageTakenMultMin = Float.MAX_VALUE

    // 断言点 D 基线：OBSERVE_ACTIVE 进入（forceShield 已生效）后 settle 0.5s 才采样，
    // 避开开盾瞬间的辐能扰动；护盾维持 640/s 恒定贯穿整个观测窗后，currFlux 净涨只能
    // 来自系统 1120/s 软辐能产出（无系统时净 -760/s，判别力成立）。
    private var fglObserveFluxBaseline = -1f
    private var fglCurrFluxDeltaMax = 0f

    // WAIT_RECALL（断言点 E/F）：召回前后硬辐能读数与 identity 清点结果。
    private var fglHardFluxBeforeRecall = -1f
    private var fglHardFluxAfterRecall = -1f
    private var fglRecallDetectedAt = -1f
    private var fglRecordedFightersCleared = false

    // RELAUNCH（断言点 G）：召回后新 identity 战机重新出击证据。
    private var fglRelaunchObserved = false

    override val scenarioId: String = ASTDInGameAutomationScenario.FGL_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isFighterGravLinkScenarioEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockFglCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findFglPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.FGL_SCENARIO_ID} combat plugin initialized")
    }

    private fun findFglPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == FGL_PLAYER_HULL && !ship.isFighter }

    private fun findFglEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == FGL_ENEMY_HULL && !ship.isFighter }

    /** 该舰全部联队的在外战机（wing 枚举口径，与系统脚本 applyFighterBuffs 同一观测面）。 */
    private fun fglPlayerFighters(player: ShipAPI): List<ShipAPI> =
        player.allWings.flatMap { wing -> wing.wingMembers }.filter { !it.isHulk }

    /**
     * 强制部署 mission reserves（范式同 deploySmReserveShips）。
     * 玩家侧仅飞蓬一艘（后备 == 1 → vanilla 静默 deployAll 会自动部署），已出场成员
     * 按 findShipByHull 判重跳过并移出后备（范式同 deployArcProductionSide），避免重复 spawn。
     */
    private fun deployFglReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == FGL_PLAYER_HULL -> FGL_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == FGL_ENEMY_HULL -> FGL_ENEMY_ANCHOR
                    else -> continue
                }
                if (findShipByHull(engine, member.hullId) != null) {
                    manager.removeFromReserves(member)
                    continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionFglPhase(next: String) {
        ctx.log.info("[ASTD-Automation] fgl phase $fglPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        fglPhase = next
        fglPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeSmShips）：双方逐帧奶血 + 钉死锚点 + 保留舰 AI
     * （航母联队出库/补员链路依赖 AI 存活，SM 秃鹰航母判例同款）。
     * 母舰辐能绝不重置——断言点 D（软辐能累积）与 F（软→硬转化）依赖真实辐能读数；
     * [forceShield] 于系统激活期强制开盾：护盾维持耗散（640/s）压低净耗散，
     * 令 1120/s 软辐能产出转为母舰 currFlux 净上涨（口径见 FGL_EXPECT_FLUX_RISE）。
     */
    private fun stabilizeFglShips(engine: CombatEngineAPI, forceShield: Boolean) {
        val player = findFglPlayer(engine)
        val enemy = findFglEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, FGL_PLAYER_ANCHOR, 0f, allowFire = false, preserveAI = true)
            player.hitpoints = player.maxHitpoints
            if (forceShield) {
                player.shield?.let { if (!it.isOn) it.toggleOn() }
            }
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, FGL_ENEMY_ANCHOR, 180f, allowFire = false, preserveAI = true)
            enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
        }
    }

    private fun lockFglCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, FGL_CAMERA_CENTER, FGL_CAMERA_VISIBLE_HEIGHT)
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployFglReserveShips(engine)
        lockFglCamera(engine)

        val player = findFglPlayer(engine)
        val enemy = findFglEnemy(engine)
        val system = player?.system
        val fighters = if (player != null && !player.isHulk) fglPlayerFighters(player) else emptyList()
        fglFightersInPlayMax = maxOf(fglFightersInPlayMax, fighters.size)
        // 单联队在场数峰值全程采样（不限 WAIT_WINGS）：联队在 WAIT_WINGS 阶段仍可能处于
        // 爬编途中，峰值可能到 ACTIVE/RELAUNCH 才出现（仅作诊断证据，口径见常量区注释）。
        if (player != null) {
            for (wing in player.allWings) {
                fglWingSizeMax = maxOf(fglWingSizeMax, wing.wingMembers.count { !it.isHulk })
            }
        }

        when (fglPhase) {
            FGL_PHASE_SPAWN -> {
                stabilizeFglShips(engine, forceShield = false)
                if (player != null && enemy != null && ctx.elapsed - fglPhaseStartedAt >= FGL_SPAWN_SETTLE_SECONDS) {
                    transitionFglPhase(FGL_PHASE_WAIT_WINGS)
                }
            }

            FGL_PHASE_WAIT_WINGS -> {
                stabilizeFglShips(engine, forceShield = false)
                if (player != null) {
                    for (bay in player.launchBaysCopy) {
                        if (bay.wing != null) {
                            fglExtraDeploymentLimitMax = maxOf(fglExtraDeploymentLimitMax, bay.extraDeploymentLimit)
                        }
                    }
                }
                if (fglExtraDeploymentLimitMax == FGL_EXPECT_EXTRA_DEPLOYMENT_LIMIT &&
                    fglWingSizeMax >= FGL_EXPANDED_WING_MIN
                ) {
                    ctx.log.info(
                        "[ASTD-Automation] fgl deck evidence: extraDeploymentLimitMax=$fglExtraDeploymentLimitMax " +
                                "wingSizeMax=$fglWingSizeMax fightersInPlayMax=$fglFightersInPlayMax（断言点 A：每甲板锚定 5 / 单联队在场 ≥3）",
                    )
                    transitionFglPhase(FGL_PHASE_ACTIVATE)
                }
            }

            FGL_PHASE_ACTIVATE -> {
                stabilizeFglShips(engine, forceShield = false)
                if (player != null && system != null) {
                    if (system.id != FGL_SYSTEM_ID) {
                        ctx.failureReason = "fgl system id=${system.id}, expect $FGL_SYSTEM_ID（ship_data.csv 生成物未刷新）"
                        transitionFglPhase(FGL_PHASE_FAILED)
                    } else if (system.isOn) {
                        // 激活确认（state IN/ACTIVE）：基线与断言点 E 底账在此刻采样。
                        // useSystem() 的单次调用可能被原版起飞动画窗（giveCommand 的
                        // isLiftingOffOrLanding 闸门）静默吞掉，故下方按帧重试直到真正点亮。
                        fglActivatedAt = ctx.elapsed
                        fglActivationCurrFlux = player.fluxTracker.currFlux
                        fglActivationHardFlux = player.fluxTracker.hardFlux
                        // 断言点 E 底账扩展：各已装联队甲板 numLost 合计（击毁计数基线）。
                        // AtActivation 仅作诊断证据；判定基线 BeforeRecall 在 ACTIVE 期滚动刷新。
                        fglNumLostAtActivation = player.launchBaysCopy
                            .filter { it.wing != null }
                            .sumOf { it.numLost }
                        fglNumLostBeforeRecall = fglNumLostAtActivation
                        fglRecordedFighterIds.clear()
                        for (fighter in fighters) {
                            fglRecordedFighterIds += System.identityHashCode(fighter)
                        }
                        fglRecordedFighterCount = fglRecordedFighterIds.size
                        ctx.log.info(
                            "[ASTD-Automation] fgl activated: state=${system.state} fighters=$fglRecordedFighterCount " +
                                    "currFlux=${"%.0f".format(fglActivationCurrFlux)} hardFlux=${"%.0f".format(fglActivationHardFlux)} " +
                                    "retries=$fglActivateAttempts",
                        )
                        transitionFglPhase(FGL_PHASE_OBSERVE_ACTIVE)
                    } else {
                        // 按帧重试 useSystem()：起飞动画窗内 giveCommand 会被原版静默丢弃
                        // （Ship.giveCommand 的 isLiftingOffOrLanding 闸门），点亮前不停尝试。
                        if (system.cooldownRemaining <= 0f && fighters.size >= FGL_MIN_FIGHTERS_FOR_ACTIVATION) {
                            fglActivateAttempts++
                            player.useSystem()
                        }
                        // 超时兜底独立于冷却分支：即便系统处于长冷却，相位也必须收口判失败。
                        if (ctx.elapsed - fglPhaseStartedAt >= FGL_ACTIVATE_TIMEOUT) {
                            ctx.failureReason = "fgl activate timeout: ${FGL_ACTIVATE_TIMEOUT}s 内系统未点亮" +
                                    "（attempts=$fglActivateAttempts state=${system.state} cd=${system.cooldownRemaining}）"
                            transitionFglPhase(FGL_PHASE_FAILED)
                        }
                    }
                }
            }

            FGL_PHASE_OBSERVE_ACTIVE -> {
                stabilizeFglShips(engine, forceShield = true)
                if (player != null) {
                    for (fighter in fighters) {
                        fglTimeMultMax = maxOf(fglTimeMultMax, fighter.mutableStats.timeMult.modifiedValue)
                        fglHullDamageTakenMultMin = minOf(fglHullDamageTakenMultMin, fighter.mutableStats.hullDamageTakenMult.modifiedValue)
                    }
                    // 断言点 D 基线延迟采样：相位进入即开盾，settle 0.5s 待护盾维持耗散
                    // 稳定后才采基线（口径见 fglObserveFluxBaseline 注释）。
                    if (fglObserveFluxBaseline < 0f && ctx.elapsed - fglActivatedAt >= FGL_OBSERVE_BASELINE_SETTLE_SECONDS) {
                        fglObserveFluxBaseline = player.fluxTracker.currFlux
                    }
                    if (fglObserveFluxBaseline >= 0f) {
                        fglCurrFluxDeltaMax = maxOf(fglCurrFluxDeltaMax, player.fluxTracker.currFlux - fglObserveFluxBaseline)
                    }
                    // ACTIVE 期间持续刷新召回前硬辐能采样（断言点 F 的前值）与 numLost 合计
                    // （断言点 E 的判定基线，归因窗收窄到召回前后）。
                    if (system != null && system.state == ShipSystemAPI.SystemState.ACTIVE) {
                        fglHardFluxBeforeRecall = player.fluxTracker.hardFlux
                        fglNumLostBeforeRecall = player.launchBaysCopy
                            .filter { it.wing != null }
                            .sumOf { it.numLost }
                    }
                }
                val observedLongEnough = ctx.elapsed - fglActivatedAt >= FGL_OBSERVE_MIN_SECONDS
                if (observedLongEnough &&
                    fglTimeMultMax >= FGL_EXPECT_TIME_MULT_MIN &&
                    fglHullDamageTakenMultMin <= FGL_EXPECT_DAMAGE_TAKEN_MAX &&
                    fglCurrFluxDeltaMax >= FGL_EXPECT_FLUX_RISE
                ) {
                    ctx.log.info(
                        "[ASTD-Automation] fgl active evidence: timeMultMax=${"%.2f".format(fglTimeMultMax)} " +
                                "hullDamageTakenMultMin=${"%.2f".format(fglHullDamageTakenMultMin)} " +
                                "currFluxDeltaMax=${"%.0f".format(fglCurrFluxDeltaMax)}（断言点 B/C/D）",
                    )
                    transitionFglPhase(FGL_PHASE_WAIT_RECALL)
                } else if (ctx.elapsed - fglActivatedAt >= FGL_OBSERVE_TIMEOUT) {
                    ctx.failureReason = "fgl observe timeout: timeMultMax=${"%.2f".format(fglTimeMultMax)}（≥$FGL_EXPECT_TIME_MULT_MIN）" +
                            " hullDamageTakenMultMin=${"%.2f".format(if (fglHullDamageTakenMultMin == Float.MAX_VALUE) -1f else fglHullDamageTakenMultMin)}（≤$FGL_EXPECT_DAMAGE_TAKEN_MAX）" +
                            " currFluxDeltaMax=${"%.0f".format(fglCurrFluxDeltaMax)}（≥$FGL_EXPECT_FLUX_RISE）"
                    transitionFglPhase(FGL_PHASE_FAILED)
                }
            }

            FGL_PHASE_WAIT_RECALL -> {
                stabilizeFglShips(engine, forceShield = true)
                // toggle 主动关闭路径验证：玩家再次按键同路径补发 useSystem()（ACTIVE → OUT →
                // 召回结算）。不用 system.deactivate()——其等价 forceDeactivate，直接跳
                // COOLDOWN、跳过 OUT 窗口，召回结算不会触发。
                // 关闭时机等 currFlux ≥ FGL_MANUAL_CANCEL_MIN_FLUX：断言点 F（软硬转化 ≥1000）
                // 需要转化前有足够软辐能存量（净涨 ~360/s，约 ACTIVE 4s 后达成，远早于 15s 上限）。
                // 未检测到召回前按节流补发（单次 useSystem() 可能被原版闸门吞掉，同 ACTIVATE）。
                if (player != null && system != null &&
                    system.state == ShipSystemAPI.SystemState.ACTIVE &&
                    player.fluxTracker.currFlux >= FGL_MANUAL_CANCEL_MIN_FLUX &&
                    (fglManualCancelAt < 0f || ctx.elapsed - fglManualCancelAt >= FGL_MANUAL_CANCEL_RETRY_SECONDS)
                ) {
                    val firstIssue = fglManualCancelAt < 0f
                    fglManualCancelAt = ctx.elapsed
                    player.useSystem()
                    if (firstIssue) {
                        ctx.log.info(
                            "[ASTD-Automation] fgl manual cancel issued at ${"%.2f".format(ctx.elapsed)}s " +
                                    "currFlux=${"%.0f".format(player.fluxTracker.currFlux)}（toggle 主动关闭路径）",
                        )
                    }
                }
                if (player != null && system != null && system.state == ShipSystemAPI.SystemState.ACTIVE) {
                    fglHardFluxBeforeRecall = player.fluxTracker.hardFlux
                    fglNumLostBeforeRecall = player.launchBaysCopy
                        .filter { it.wing != null }
                        .sumOf { it.numLost }
                }
                val recallDetected = system != null &&
                        (system.state == ShipSystemAPI.SystemState.OUT ||
                                (!system.isOn && system.cooldownRemaining > 0f))
                if (recallDetected && fglRecallDetectedAt < 0f) {
                    fglRecallDetectedAt = ctx.elapsed
                    ctx.log.info("[ASTD-Automation] fgl recall detected at ${"%.2f".format(ctx.elapsed)}s（ACTIVE→OUT）")
                }
                if (fglRecallDetectedAt >= 0f && player != null) {
                    // settle 1s 内取硬辐能峰值（软→硬转化为 OUT 首帧 setHardFlux(currFlux) 结算，
                    // 峰值即转化后读数）；同步采样 numLost 合计（断言点 E 的击毁计数后值）。
                    fglHardFluxAfterRecall = maxOf(fglHardFluxAfterRecall, player.fluxTracker.hardFlux)
                    fglNumLostAfterRecall = player.launchBaysCopy
                        .filter { it.wing != null }
                        .sumOf { it.numLost }
                }
                if (fglRecallDetectedAt >= 0f && ctx.elapsed - fglRecallDetectedAt >= FGL_RECALL_SETTLE_SECONDS) {
                    // 断言点 E：底账 identity 全部消失 且 numLost 合计不变——identity 全清单独
                    // 无法区分「被击毁」与「被 land 召回」（原版语义：numLost 只在击毁路径自增，
                    // land 召回不计），两者同时成立才证明召回路径生效。
                    fglRecordedFightersCleared = engine.ships.none {
                        System.identityHashCode(it) in fglRecordedFighterIds && !it.isHulk
                    }
                    when {
                        !fglRecordedFightersCleared -> {
                            ctx.failureReason = "fgl recall incomplete: 底账 $fglRecordedFighterCount 架仍有 identity 在场（断言点 E）"
                            transitionFglPhase(FGL_PHASE_FAILED)
                        }

                        fglNumLostAfterRecall != fglNumLostBeforeRecall -> {
                            ctx.failureReason = "fgl recall confused with kill: numLost $fglNumLostBeforeRecall -> $fglNumLostAfterRecall" +
                                    "（断言点 E：召回窗内有击毁，无法归因 land 召回）"
                            transitionFglPhase(FGL_PHASE_FAILED)
                        }
                        // 断言点 F：软→硬转化阈显式 ≥1000。外部来源不可能满足——敌方仅秃鹰
                        // 双阔剑联队，1s settle 窗内战机火力对护盾产生的硬辐能贡献在数十量级。
                        fglHardFluxAfterRecall - fglHardFluxBeforeRecall < FGL_EXPECT_HARD_FLUX_RISE -> {
                            ctx.failureReason = "fgl hard flux rise=${"%.0f".format(fglHardFluxAfterRecall - fglHardFluxBeforeRecall)}" +
                                    " < $FGL_EXPECT_HARD_FLUX_RISE（before=${"%.0f".format(fglHardFluxBeforeRecall)}" +
                                    " after=${"%.0f".format(fglHardFluxAfterRecall)}，断言点 F：软→硬转化）"
                            transitionFglPhase(FGL_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] fgl recall evidence: recorded=$fglRecordedFighterCount 全清 " +
                                        "numLost=$fglNumLostBeforeRecall 不变（激活时 $fglNumLostAtActivation） " +
                                        "hardFlux ${"%.0f".format(fglHardFluxBeforeRecall)} -> ${"%.0f".format(fglHardFluxAfterRecall)}（断言点 E/F）",
                            )
                            transitionFglPhase(FGL_PHASE_RELAUNCH)
                        }
                    }
                }
            }

            FGL_PHASE_RELAUNCH -> {
                stabilizeFglShips(engine, forceShield = false)
                // 断言点 G：底账之外的新 identity 战机出现即重新出击证据（旧机已全部召回/战损）。
                if (!fglRelaunchObserved && fighters.any { System.identityHashCode(it) !in fglRecordedFighterIds }) {
                    fglRelaunchObserved = true
                    ctx.log.info(
                        "[ASTD-Automation] fgl relaunch evidence: 召回后 ${"%.2f".format(ctx.elapsed - fglRecallDetectedAt)}s " +
                                "出现新 identity 战机（fighters=${fighters.size}，断言点 G；" +
                                "在场峰值 $fglFightersInPlayMax / 单联队峰值 $fglWingSizeMax，诊断证据）",
                    )
                    transitionFglPhase(FGL_PHASE_COMPLETED)
                } else if (!fglRelaunchObserved && ctx.elapsed - fglRecallDetectedAt > FGL_RELAUNCH_TIMEOUT) {
                    ctx.failureReason = "fgl relaunch timeout: 召回后 ${FGL_RELAUNCH_TIMEOUT.toInt()}s 内无新 identity 战机（断言点 G）"
                    transitionFglPhase(FGL_PHASE_FAILED)
                }
            }

            FGL_PHASE_COMPLETED -> {
                stabilizeFglShips(engine, forceShield = false)
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "fgl ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            fglPhase == FGL_PHASE_FAILED -> "Failed"
            fglPhase != FGL_PHASE_COMPLETED &&
                    ctx.elapsed - fglPhaseStartedAt > FGL_PHASE_TIMEOUT -> {
                ctx.failureReason = "fgl phase timeout: $fglPhase（limitMax=$fglExtraDeploymentLimitMax wingMax=$fglWingSizeMax " +
                        "fighters=${fighters.size}/$fglFightersInPlayMax timeMult=${"%.2f".format(fglTimeMultMax)} " +
                        "dmgTaken=${"%.2f".format(if (fglHullDamageTakenMultMin == Float.MAX_VALUE) -1f else fglHullDamageTakenMultMin)} " +
                        "fluxDelta=${"%.0f".format(fglCurrFluxDeltaMax)} recallAt=${"%.2f".format(fglRecallDetectedAt)} relaunch=$fglRelaunchObserved）"
                "Failed"
            }

            fglPhase == FGL_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: lens_fighter_grav_link deck/active/recall/relaunch evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：召回后重新出击的机群（系统收尾 jitter 已退）与母舰在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findFglPlayer(ctx.engine) }) { lockFglCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val fglPlayer = findFglPlayer(engine)
        val fglSystem = fglPlayer?.system
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（断言点 A~G）----
        json.appendLine("  \"fglPhase\": \"$fglPhase\",")
        json.appendLine("  \"fglSystemId\": ${jsonString(fglSystem?.id)},")
        json.appendLine("  \"fglSystemState\": ${jsonString(fglSystem?.state?.name)},")
        json.appendLine("  \"fglSystemCooldownRemaining\": ${formatFloat(fglSystem?.cooldownRemaining ?: -1f)},")
        json.appendLine("  \"fglExtraDeploymentLimitMax\": $fglExtraDeploymentLimitMax,")
        json.appendLine("  \"fglWingSizeMax\": $fglWingSizeMax,")
        json.appendLine("  \"fglFightersInPlayMax\": $fglFightersInPlayMax,")
        json.appendLine("  \"fglFightersInPlay\": ${if (fglPlayer != null) fglPlayerFighters(fglPlayer).size else -1},")
        json.appendLine("  \"fglRecordedFighterCount\": $fglRecordedFighterCount,")
        json.appendLine("  \"fglTimeMultMax\": ${formatFloat(fglTimeMultMax)},")
        json.appendLine("  \"fglHullDamageTakenMultMin\": ${formatFloat(if (fglHullDamageTakenMultMin == Float.MAX_VALUE) -1f else fglHullDamageTakenMultMin)},")
        json.appendLine("  \"fglActivationCurrFlux\": ${formatFloat(fglActivationCurrFlux)},")
        json.appendLine("  \"fglObserveFluxBaseline\": ${formatFloat(fglObserveFluxBaseline)},")
        json.appendLine("  \"fglCurrFluxDeltaMax\": ${formatFloat(fglCurrFluxDeltaMax)},")
        json.appendLine("  \"fglActivationHardFlux\": ${formatFloat(fglActivationHardFlux)},")
        json.appendLine("  \"fglNumLostAtActivation\": $fglNumLostAtActivation,")
        json.appendLine("  \"fglNumLostBeforeRecall\": $fglNumLostBeforeRecall,")
        json.appendLine("  \"fglNumLostAfterRecall\": $fglNumLostAfterRecall,")
        json.appendLine("  \"fglHardFluxBeforeRecall\": ${formatFloat(fglHardFluxBeforeRecall)},")
        json.appendLine("  \"fglHardFluxAfterRecall\": ${formatFloat(fglHardFluxAfterRecall)},")
        json.appendLine("  \"fglRecallDetected\": ${fglRecallDetectedAt >= 0f},")
        json.appendLine("  \"fglRecordedFightersCleared\": $fglRecordedFightersCleared,")
        json.appendLine("  \"fglRelaunchObserved\": $fglRelaunchObserved,")
        json.appendLine("  \"fglPlayerCurrFlux\": ${formatFloat(fglPlayer?.fluxTracker?.currFlux ?: -1f)},")
        json.appendLine("  \"fglPlayerHardFlux\": ${formatFloat(fglPlayer?.fluxTracker?.hardFlux ?: -1f)},")
    }

    private companion object {
        // 飞蓬战机引力联结器场景：相位机、锚点与期望证据（断言点 A~G）。
        private const val FGL_PHASE_SPAWN = "SPAWN"
        private const val FGL_PHASE_WAIT_WINGS = "WAIT_WINGS"
        private const val FGL_PHASE_ACTIVATE = "ACTIVATE"
        private const val FGL_PHASE_OBSERVE_ACTIVE = "OBSERVE_ACTIVE"
        private const val FGL_PHASE_WAIT_RECALL = "WAIT_RECALL"
        private const val FGL_PHASE_RELAUNCH = "RELAUNCH"
        private const val FGL_PHASE_COMPLETED = "COMPLETED"
        private const val FGL_PHASE_FAILED = "FAILED"
        private const val FGL_PLAYER_HULL = "astd_zw_102"
        private const val FGL_ENEMY_HULL = "condor"
        private const val FGL_SYSTEM_ID = "astd_fighter_grav_link"
        private val FGL_PLAYER_ANCHOR = Vector2f(-700f, 0f)
        private val FGL_ENEMY_ANCHOR = Vector2f(1300f, 0f)
        private val FGL_CAMERA_CENTER = Vector2f(300f, 0f)
        private const val FGL_CAMERA_VISIBLE_HEIGHT = 1500f
        private const val FGL_SPAWN_SETTLE_SECONDS = 0.6f

        // WAIT_WINGS（断言点 A）：玩家侧固定 v2 档 → 每甲板 extraDeploymentLimit 锚定 round(2×(1+1.5))=5；
        // 单联队在场 ≥3 超出基础编制 num=2（wing_data.csv 口径），为扩容生效的直接证据
        // （总在场数口径无效：3 甲板基础编制合计已达 6）。
        private const val FGL_EXPECT_EXTRA_DEPLOYMENT_LIMIT = 5
        private const val FGL_EXPANDED_WING_MIN = 3

        // 在场峰值/单联队峰值全程采样，仅作诊断证据，不作硬断言：满编口径（总在场 15 /
        // 单联队 5）受交战 RNG 影响——敌方阔剑可在爬编期击落战机（实机曾 numLost 0→1 致
        // 峰值 14），且 wingMembers 计数不覆盖额外编制（实机曾在场 15 而单联队峰值仅 3）。
        // 扩容的机制级硬证据由 WAIT_WINGS 的 extraDeploymentLimit==5 + 单联队超基础编制承担。
        private const val FGL_MIN_FIGHTERS_FOR_ACTIVATION = 2

        // ACTIVATE：useSystem() 可能被原版起飞动画窗（giveCommand 的 isLiftingOffOrLanding
        // 闸门）静默吞掉，按帧重试直到 system.isOn；超时兜底判失败（点亮本身即机制断言）。
        private const val FGL_ACTIVATE_TIMEOUT = 10f

        // OBSERVE_ACTIVE（断言点 B/C/D）：玩家恒 v2 → 时流 ×2.5（界 2.4）、四承伤 ×0.5（界 0.51）；
        // 软辐能 1120/s（基础最大辐能 16000×7%）对冲盾开净耗散 760/s（耗散 1400 − 护盾维持 640）
        // 后净涨 ≈360/s。断言点 D 基线在相位进入（开盾生效）后 settle 0.5s 采样：护盾维持 640/s
        // 恒定贯穿整个观测窗，净涨只能来自系统产出（无系统时净 -760/s，判别力成立），
        // 基线后约 2.5s 达成 +800，观测窗 2~5s 收口。
        private const val FGL_OBSERVE_MIN_SECONDS = 2f
        private const val FGL_OBSERVE_TIMEOUT = 5f
        private const val FGL_OBSERVE_BASELINE_SETTLE_SECONDS = 0.5f
        private const val FGL_EXPECT_TIME_MULT_MIN = 2.4f
        private const val FGL_EXPECT_DAMAGE_TAKEN_MAX = 0.51f
        private const val FGL_EXPECT_FLUX_RISE = 800f

        // WAIT_RECALL（断言点 E/F）：currFlux ≥ FGL_MANUAL_CANCEL_MIN_FLUX 时补发 useSystem()
        // 验证 toggle 主动关闭路径（玩家再次按键同路径；不用 deactivate()——其直接跳 COOLDOWN、
        // 跳过 OUT 窗口）；召回检测后 settle 1.5s 采样硬辐能峰值与 identity 清点——OUT 召回
        // 特效窗为 1.0s（CSV down），land 在窗口末（effectLevel ≤0.05，约 0.95s）执行，settle 需覆盖。
        // 关闭前的最低辐能门槛：断言点 F 阈值显式 ≥1000——敌方仅秃鹰双阔剑联队，settle 窗内
        // 战机火力对护盾的硬辐能贡献在数十量级，外部来源不可能满足，上升只能归因 OUT 首帧
        // setHardFlux(currFlux) 转化；ACTIVE 期净涨 ~360/s，1500 约 4s 达成（远早于 15s 上限）。
        private const val FGL_MANUAL_CANCEL_MIN_FLUX = 1500f

        // toggle 主动关闭的补发节流（秒）：单次 useSystem() 可能被原版闸门吞掉（同 ACTIVATE），
        // 未检测到召回前按该间隔重发，确保「主动取消」路径被真实验证而非静默落到 15s 上限收口。
        private const val FGL_MANUAL_CANCEL_RETRY_SECONDS = 0.5f
        private const val FGL_RECALL_SETTLE_SECONDS = 1.5f
        private const val FGL_EXPECT_HARD_FLUX_RISE = 1000f

        // RELAUNCH（断言点 G）：召回后 15s 内必须出现新 identity 战机（快速整备 0.3~0.8s/架）。
        private const val FGL_RELAUNCH_TIMEOUT = 15f
        private const val FGL_PHASE_TIMEOUT = 90f
    }
}
