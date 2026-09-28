package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.GS_FIELD_MOD_ID_PREFIX
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.GS_STORM_ACTIVATION_KEY
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 密蒙引力磁暴发生器场景（断言点 GS-A~GS-E）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class GravStormScenario : AbstractAutomationScenario() {
    // ==== 密蒙引力磁暴发生器场景状态（相位机 SPAWN → FIELD_OBSERVE → ACTIVATE → RELEASE → COOLDOWN_FIELD_OFF → COMPLETED） ====
    private var gsPhase = GS_PHASE_SPAWN
    private var gsPhaseStartedAt = 0f

    // FIELD_OBSERVE（断言点 GS-A）：力场满效压制采样（敌舰钉在 600su ≤ 半射程 750su 满效区）。
    private var gsFieldMaxSpeedMultMin = Float.MAX_VALUE
    private var gsFieldTurnRateMultMin = Float.MAX_VALUE
    private var gsFieldEmpMultMax = 0f

    // ACTIVATE（断言点 GS-B）：激活软辐能峰值增量与充能期全承伤乘区谷值。
    private var gsActivateAttempts = 0
    private var gsActivatedAt = -1f
    private var gsActivationFluxDeltaMax = 0f
    private var gsChargeDamageTakenMultMin = Float.MAX_VALUE

    // ACTIVATE 锁定窗（断言点 GS-B2）：充能前段相位锁定取证——窗内逐帧对玩家舰发
    // TOGGLE_SHIELD_OR_PHASE_CLOAK（真实按键路径），统计施压帧数、isPhased 成立帧数
    // （必须为 0）与 cloak 被压入 COOLDOWN 的帧数。
    private var gsPhaseLockoutPressFrames = 0
    private var gsPhaseLockoutPhasedFrames = 0
    private var gsPhaseLockoutCloakPinnedFrames = 0

    // RELEASE（断言点 GS-C/D）：释放闩、系统激活期力场修饰键在场对账、靶舰过载时长与电弧结算掉血。
    // 注意：电弧 EMP 会熄火敌舰引擎把 maxSpeed 打到 0，力场存续判定只能对账修饰键，不能读值。
    private var gsStormActivationLatched = false
    private var gsFieldMultMinDuringRelease = Float.MAX_VALUE
    private var gsFieldModifierLostEarly = false
    private var gsFieldModifierLostDetail = ""
    private var gsEnemyOverloadStartedAt = -1f
    private var gsEnemyOverloadSeconds = -1f
    private var gsEnemyHpBeforeRelease = -1f
    private var gsEnemyHpMinAfterRelease = Float.MAX_VALUE

    // COOLDOWN_FIELD_OFF（断言点 GS-E）：系统冷却后力场收口，靶舰力场修饰键移除且 EMP 承伤复原。
    private var gsFieldModifierCleared = false
    private var gsFieldRestoredEmpMult = -1f

    override val scenarioId: String = ASTDInGameAutomationScenario.GS_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isGravStormScenarioEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockGsCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findGsPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.GS_SCENARIO_ID} combat plugin initialized")
    }

    private fun findGsPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == GS_PLAYER_HULL && !ship.isFighter }

    private fun findGsEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == GS_ENEMY_HULL && !ship.isFighter }

    /**
     * 力场修饰键在场对账：电弧 EMP 熄火会把目标 maxSpeed 值打到 0（力场复原断言的值层面读数不可信），
     * 直接查目标 maxSpeed 乘区修饰表中的力场键。键前缀口径同 GravEmFieldHullMod.MOD_ID_PREFIX
     * （其 private 不便开放，此处字面值镜像，改动 hullmod 键名时需同步）。
     */
    private fun hasGsFieldModifier(ship: ShipAPI): Boolean =
        ship.mutableStats.maxSpeed.multMods.keys.any { it.startsWith(GS_FIELD_MOD_ID_PREFIX) }

    /** 力场修饰键缺席取证：记录首丢帧的相位/源舰系统状态/间距/靶舰乘区表现有键，供失败归因。 */
    private fun trackGsFieldModifier(enemy: ShipAPI, player: ShipAPI?) {
        if (gsFieldModifierLostEarly || hasGsFieldModifier(enemy)) return
        gsFieldModifierLostEarly = true
        val keys = enemy.mutableStats.maxSpeed.multMods.keys.joinToString(",")
        val dist = player?.let { Misc.getDistance(it.location, enemy.location).toInt() } ?: -1
        gsFieldModifierLostDetail = "phase=$gsPhase sys=${player?.system?.state} srcAlive=${player?.isAlive} " +
                "dist=${dist}su keys=[$keys]"
    }

    /** 强制部署 mission reserves（范式同 deployGrgReserveShips；已出场成员按 hull 判重跳过并移出后备）。 */
    private fun deployGsReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == GS_PLAYER_HULL -> GS_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == GS_ENEMY_HULL -> GS_ENEMY_ANCHOR
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

    private fun transitionGsPhase(next: String) {
        ctx.log.info("[ASTD-Automation] gs phase $gsPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        gsPhase = next
        gsPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeGrgShips）：双方逐帧钉死锚点 + 舰 AI 置空
     * （密蒙舰载机联队不需要出库，无 AI 即不出击；系统施放时机由插件独占，
     * [blockSystem] 在 ACTIVATE 之前逐帧封锁 USE_SYSTEM 防系统 AI 路径抢跑）。
     * 玩家舰默认逐帧封锁相位斗篷（[blockPhase]）：充能锁定窗（断言点 GS-B2）需要放开
     * 封锁并反向施压相位键，其余阶段封锁——锁定解除后充能中进相位会 deactivate
     * 取消释放（机制口径），本场景主线验证完整释放链路。
     * [healEnemy] 在 RELEASE 起关闭：电弧结算需要真实 hitpoints 读数（靶舰装甲已在
     * 进 ACTIVATE 时剥零，范式同 GRG 的 RIFT_FIRE 前剥甲）。
     */
    private fun stabilizeGsShips(
        engine: CombatEngineAPI,
        healEnemy: Boolean,
        zeroPlayerFlux: Boolean,
        blockSystem: Boolean,
        blockPhase: Boolean = true,
    ) {
        val player = findGsPlayer(engine)
        val enemy = findGsEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, GS_PLAYER_ANCHOR, 0f, allowFire = false, preserveAI = false)
            player.hitpoints = player.maxHitpoints
            if (zeroPlayerFlux) {
                player.fluxTracker.currFlux = 0f
                player.fluxTracker.hardFlux = 0f
            }
            if (blockSystem) player.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
            if (blockPhase) player.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, GS_ENEMY_ANCHOR, 180f, allowFire = false, preserveAI = false)
            if (healEnemy) enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
            // 靶舰护盾压下：电弧与过载结算落船体（范式同 stabilizeGrgShips 的靶舰处理）。
            enemy.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
            enemy.shield?.let { if (it.isOn) it.toggleOff() }
        }
    }

    private fun lockGsCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, GS_CAMERA_CENTER, GS_CAMERA_VISIBLE_HEIGHT)
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployGsReserveShips(engine)
        lockGsCamera(engine)

        val player = findGsPlayer(engine)
        val enemy = findGsEnemy(engine)
        val system = player?.system

        when (gsPhase) {
            GS_PHASE_SPAWN -> {
                stabilizeGsShips(engine, healEnemy = true, zeroPlayerFlux = true, blockSystem = true)
                if (player != null && enemy != null && ctx.elapsed - gsPhaseStartedAt >= GS_SPAWN_SETTLE_SECONDS) {
                    transitionGsPhase(GS_PHASE_FIELD_OBSERVE)
                }
            }

            GS_PHASE_FIELD_OBSERVE -> {
                stabilizeGsShips(engine, healEnemy = true, zeroPlayerFlux = true, blockSystem = true)
                if (enemy != null && !enemy.isHulk) {
                    val stats = enemy.mutableStats
                    gsFieldMaxSpeedMultMin = minOf(gsFieldMaxSpeedMultMin, stats.maxSpeed.modifiedValue / stats.maxSpeed.baseValue)
                    gsFieldTurnRateMultMin = minOf(gsFieldTurnRateMultMin, stats.maxTurnRate.modifiedValue / stats.maxTurnRate.baseValue)
                    gsFieldEmpMultMax = maxOf(gsFieldEmpMultMax, stats.empDamageTakenMult.modifiedValue)
                }
                if (ctx.elapsed - gsPhaseStartedAt >= GS_FIELD_OBSERVE_SECONDS) {
                    when {
                        gsFieldMaxSpeedMultMin !in GS_FIELD_STAT_MULT_MIN..GS_FIELD_STAT_MULT_MAX -> {
                            ctx.failureReason = "gs field maxSpeed mult=${"%.3f".format(gsFieldMaxSpeedMultMin)}" +
                                    " ∉ [$GS_FIELD_STAT_MULT_MIN, $GS_FIELD_STAT_MULT_MAX]（断言点 GS-A：满效 ×0.8）"
                            transitionGsPhase(GS_PHASE_FAILED)
                        }

                        gsFieldTurnRateMultMin !in GS_FIELD_STAT_MULT_MIN..GS_FIELD_STAT_MULT_MAX -> {
                            ctx.failureReason = "gs field turnRate mult=${"%.3f".format(gsFieldTurnRateMultMin)}" +
                                    " ∉ [$GS_FIELD_STAT_MULT_MIN, $GS_FIELD_STAT_MULT_MAX]（断言点 GS-A：满效 ×0.8）"
                            transitionGsPhase(GS_PHASE_FAILED)
                        }

                        gsFieldEmpMultMax !in GS_FIELD_EMP_MULT_MIN..GS_FIELD_EMP_MULT_MAX -> {
                            ctx.failureReason = "gs field empMult=${"%.3f".format(gsFieldEmpMultMax)}" +
                                    " ∉ [$GS_FIELD_EMP_MULT_MIN, $GS_FIELD_EMP_MULT_MAX]（断言点 GS-A：EMP 承伤 +0.5 位移）"
                            transitionGsPhase(GS_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] gs field evidence: maxSpeedMultMin=${"%.3f".format(gsFieldMaxSpeedMultMin)} " +
                                        "turnRateMultMin=${"%.3f".format(gsFieldTurnRateMultMin)} " +
                                        "empMultMax=${"%.3f".format(gsFieldEmpMultMax)}（断言点 GS-A：力场满效压制）",
                            )
                            // 释放前剥光靶舰装甲（范式同 GRG：断言的是电弧出伤链路而非原版装甲数学）。
                            enemy?.armorGrid?.grid?.forEach { row -> row.fill(0f) }
                            transitionGsPhase(GS_PHASE_ACTIVATE)
                        }
                    }
                }
            }

            GS_PHASE_ACTIVATE -> {
                // 辐能清零闸只认原版状态机：IDLE 才清零。toggle 系统充满后 ACTIVE 仅存在
                // 脚本 apply 一帧（随即 forceState OUT），isOn 观测面既留不住激活代价读数
                // （chargeTick 首帧计入的软辐能会被下一帧清零抹掉）也抓不到释放闩，必须直接读 state。
                val systemState = system?.state
                // 断言点 GS-B2 施压窗：充能前段相位锁定期（留 0.2s 余量防锁定解除瞬间误激活），
                // 窗内放开相位封锁并逐帧反向施压相位键（真实 TOGGLE_SHIELD_OR_PHASE_CLOAK 路径）。
                val inPhaseLockout = gsActivatedAt >= 0f && systemState == ShipSystemAPI.SystemState.IN &&
                        ctx.elapsed - gsActivatedAt < GS_PHASE_LOCKOUT_PRESS_SECONDS
                stabilizeGsShips(
                    engine, healEnemy = true,
                    zeroPlayerFlux = systemState == null || systemState == ShipSystemAPI.SystemState.IDLE,
                    blockSystem = false,
                    blockPhase = !inPhaseLockout,
                )
                if (inPhaseLockout && player != null) {
                    gsPhaseLockoutPressFrames++
                    player.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
                    if (player.isPhased) gsPhaseLockoutPhasedFrames++
                    if (player.phaseCloak?.state == ShipSystemAPI.SystemState.COOLDOWN) gsPhaseLockoutCloakPinnedFrames++
                }
                if (player != null && enemy != null && system != null) {
                    if (system.id != GS_SYSTEM_ID) {
                        ctx.failureReason = "gs system id=${system.id}, expect $GS_SYSTEM_ID（ship_data.csv 生成物未刷新）"
                        transitionGsPhase(GS_PHASE_FAILED)
                    } else {
                        if (systemState == ShipSystemAPI.SystemState.IN ||
                            systemState == ShipSystemAPI.SystemState.ACTIVE ||
                            systemState == ShipSystemAPI.SystemState.OUT
                        ) {
                            if (gsActivatedAt < 0f) {
                                gsActivatedAt = ctx.elapsed
                                ctx.log.info(
                                    "[ASTD-Automation] gs activated: state=$systemState attempts=$gsActivateAttempts " +
                                            "currFlux=${"%.0f".format(player.fluxTracker.currFlux)}",
                                )
                            }
                            // 断言点 GS-B 观测面：激活软辐能峰值增量（基线为逐帧清零的 0）与充能期承伤乘区谷值。
                            gsActivationFluxDeltaMax = maxOf(gsActivationFluxDeltaMax, player.fluxTracker.currFlux)
                            gsChargeDamageTakenMultMin = minOf(gsChargeDamageTakenMultMin, player.mutableStats.hullDamageTakenMult.modifiedValue)
                            // 断言点 GS-E 前置观测：系统激活期力场修饰键必须在场（失效条件仅 COOLDOWN/残骸化）。
                            trackGsFieldModifier(enemy, player)
                        }
                        // 释放闩逐帧对账（release() 写入后 OUT 窗口 1.5s 内均可读，不受 isOn 口径影响）。
                        if (engine.customData[GS_STORM_ACTIVATION_KEY + player.id] != null) gsStormActivationLatched = true
                        if (gsStormActivationLatched || systemState == ShipSystemAPI.SystemState.OUT) {
                            when {
                                gsActivationFluxDeltaMax !in GS_EXPECT_ACTIVATION_FLUX_MIN..GS_EXPECT_ACTIVATION_FLUX_MAX -> {
                                    ctx.failureReason = "gs activation flux delta=${"%.0f".format(gsActivationFluxDeltaMax)}" +
                                            " ∉ [$GS_EXPECT_ACTIVATION_FLUX_MIN, $GS_EXPECT_ACTIVATION_FLUX_MAX]" +
                                            "（断言点 GS-B：基础容量 ×20% 软辐能，zw_002 ≈2400）"
                                    transitionGsPhase(GS_PHASE_FAILED)
                                }

                                gsChargeDamageTakenMultMin > GS_EXPECT_DAMAGE_TAKEN_MAX -> {
                                    ctx.failureReason = "gs charge damageTakenMult min=${"%.3f".format(gsChargeDamageTakenMultMin)}" +
                                            " > $GS_EXPECT_DAMAGE_TAKEN_MAX（断言点 GS-B：充能期全承伤 ×0.5）"
                                    transitionGsPhase(GS_PHASE_FAILED)
                                }

                                gsPhaseLockoutPhasedFrames > 0 -> {
                                    ctx.failureReason = "gs phase lockout breached: 锁定窗内 isPhased 成立 $gsPhaseLockoutPhasedFrames 帧" +
                                            "（断言点 GS-B2：充能前段相位锁定，isPhased 须恒 false）"
                                    transitionGsPhase(GS_PHASE_FAILED)
                                }

                                gsPhaseLockoutPressFrames < GS_PHASE_LOCKOUT_MIN_PRESS_FRAMES -> {
                                    ctx.failureReason = "gs phase lockout 施压不足: pressFrames=$gsPhaseLockoutPressFrames" +
                                            " < $GS_PHASE_LOCKOUT_MIN_PRESS_FRAMES（断言点 GS-B2：锁定窗未充分施压，证据不可信）"
                                    transitionGsPhase(GS_PHASE_FAILED)
                                }

                                else -> {
                                    gsEnemyHpBeforeRelease = enemy.hitpoints
                                    gsEnemyHpMinAfterRelease = enemy.hitpoints
                                    ctx.log.info(
                                        "[ASTD-Automation] gs charge evidence: fluxDeltaMax=${"%.0f".format(gsActivationFluxDeltaMax)} " +
                                                "damageTakenMultMin=${"%.3f".format(gsChargeDamageTakenMultMin)}（断言点 GS-B）",
                                    )
                                    ctx.log.info(
                                        "[ASTD-Automation] gs phase lockout evidence: pressFrames=$gsPhaseLockoutPressFrames " +
                                                "phasedFrames=$gsPhaseLockoutPhasedFrames cloakPinnedFrames=$gsPhaseLockoutCloakPinnedFrames" +
                                                "（断言点 GS-B2：充能前段相位锁定，锁定窗内 isPhased 恒 false）",
                                    )
                                    transitionGsPhase(GS_PHASE_RELEASE)
                                }
                            }
                        } else if (systemState == ShipSystemAPI.SystemState.IDLE && system.cooldownRemaining <= 0f) {
                            // 按帧重试 useSystem()（单次调用可能被原版闸门吞掉，范式同 FGL ACTIVATE）；
                            // 只在 IDLE 重试：IN 期间再按对 toggle 系统是提前结束/取消路径。
                            gsActivateAttempts++
                            player.useSystem()
                        }
                    }
                    if (gsPhase == GS_PHASE_ACTIVATE && ctx.elapsed - gsPhaseStartedAt >= GS_ACTIVATE_TIMEOUT) {
                        ctx.failureReason = "gs activate timeout: ${GS_ACTIVATE_TIMEOUT.toInt()}s 内系统未点亮" +
                                "（attempts=$gsActivateAttempts state=${system.state} cd=${"%.1f".format(system.cooldownRemaining)}）"
                        transitionGsPhase(GS_PHASE_FAILED)
                    }
                }
            }

            GS_PHASE_RELEASE -> {
                // 靶舰停奶：电弧结算需要真实 hitpoints 读数；玩家侧继续奶血保活。
                stabilizeGsShips(engine, healEnemy = false, zeroPlayerFlux = false, blockSystem = false)
                if (player != null && enemy != null && system != null && gsActivatedAt >= 0f) {
                    if (engine.customData[GS_STORM_ACTIVATION_KEY + player.id] != null) gsStormActivationLatched = true
                    if (!enemy.isHulk) {
                        // 断言点 GS-E 前置观测：系统激活期力场修饰键必须在场（值层面被电弧熄火污染，只对账键）。
                        // COOLDOWN 一起即停对账：冷却关场是机制口径（断言点 GS-E 验的就是这个收口），
                        //  hullmod 在状态翻转帧即 unmodify，纳入对账会把正常收口误判为提前失效。
                        gsFieldMultMinDuringRelease = minOf(
                            gsFieldMultMinDuringRelease,
                            enemy.mutableStats.maxSpeed.modifiedValue / enemy.mutableStats.maxSpeed.baseValue,
                        )
                        if (system.state != ShipSystemAPI.SystemState.COOLDOWN) trackGsFieldModifier(enemy, player)
                        gsEnemyHpMinAfterRelease = minOf(gsEnemyHpMinAfterRelease, enemy.hitpoints)
                        if (enemy.fluxTracker.isOverloaded) {
                            if (gsEnemyOverloadStartedAt < 0f) gsEnemyOverloadStartedAt = ctx.elapsed
                        } else if (gsEnemyOverloadStartedAt >= 0f && gsEnemyOverloadSeconds < 0f &&
                            !enemy.fluxTracker.isOverloadedOrVenting
                        ) {
                            gsEnemyOverloadSeconds = ctx.elapsed - gsEnemyOverloadStartedAt
                        }
                    }
                    val cooldownReached = system.state == ShipSystemAPI.SystemState.COOLDOWN
                    val overloadPending = gsEnemyOverloadStartedAt >= 0f && gsEnemyOverloadSeconds < 0f
                    if (cooldownReached && !overloadPending) {
                        val hpDrop = gsEnemyHpBeforeRelease - gsEnemyHpMinAfterRelease
                        when {
                            !gsStormActivationLatched -> {
                                ctx.failureReason = "gs release latch missing: 释放闩 ${GS_STORM_ACTIVATION_KEY}* 未出现（断言点 GS-C）"
                                transitionGsPhase(GS_PHASE_FAILED)
                            }

                            gsEnemyOverloadStartedAt < 0f -> {
                                ctx.failureReason = "gs enemy never overloaded: 释放后靶舰未进过载（断言点 GS-C：锥内锁定+强制过载）"
                                transitionGsPhase(GS_PHASE_FAILED)
                            }

                            gsEnemyOverloadSeconds !in GS_OVERLOAD_SECONDS_MIN..GS_OVERLOAD_SECONDS_MAX -> {
                                ctx.failureReason = "gs overload seconds=${"%.2f".format(gsEnemyOverloadSeconds)}" +
                                        " ∉ [$GS_OVERLOAD_SECONDS_MIN, $GS_OVERLOAD_SECONDS_MAX]（断言点 GS-C：巡洋舰 v2 满充能 2s）"
                                transitionGsPhase(GS_PHASE_FAILED)
                            }

                            hpDrop < GS_EXPECT_ENEMY_HP_DROP -> {
                                ctx.failureReason = "gs arc damage shortfall: hpDrop=${"%.0f".format(hpDrop)}" +
                                        " < $GS_EXPECT_ENEMY_HP_DROP（enemyHp=${"%.0f".format(enemy.hitpoints)}" +
                                        "/${"%.0f".format(enemy.maxHitpoints)}，断言点 GS-D：8~16 道电弧剥甲结算）"
                                transitionGsPhase(GS_PHASE_FAILED)
                            }

                            gsFieldModifierLostEarly -> {
                                ctx.failureReason = "gs field lost during system use: 激活期力场修饰键 $GS_FIELD_MOD_ID_PREFIX* 缺席" +
                                        "（$gsFieldModifierLostDetail，断言点 GS-E 前置：IN/ACTIVE/OUT 力场不失效）"
                                transitionGsPhase(GS_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] gs release evidence: overload=${"%.2f".format(gsEnemyOverloadSeconds)}s " +
                                            "hpDrop=${"%.0f".format(hpDrop)}（${"%.0f".format(gsEnemyHpBeforeRelease)} -> " +
                                            "${"%.0f".format(gsEnemyHpMinAfterRelease)}）fieldModifierKept=true（断言点 GS-C/D + GS-E 前置）",
                                )
                                transitionGsPhase(GS_PHASE_COOLDOWN_FIELD_OFF)
                            }
                        }
                    } else if (ctx.elapsed - gsActivatedAt >= GS_RELEASE_TIMEOUT) {
                        ctx.failureReason = "gs release timeout: 激活后 ${GS_RELEASE_TIMEOUT.toInt()}s 内未收口" +
                                "（state=${system.state} latched=$gsStormActivationLatched overloadAt=" +
                                "${"%.2f".format(gsEnemyOverloadStartedAt)} overloadSeconds=${"%.2f".format(gsEnemyOverloadSeconds)}）"
                        transitionGsPhase(GS_PHASE_FAILED)
                    }
                }
            }

            GS_PHASE_COOLDOWN_FIELD_OFF -> {
                stabilizeGsShips(engine, healEnemy = false, zeroPlayerFlux = false, blockSystem = true)
                if (enemy != null && !enemy.isHulk && ctx.elapsed - gsPhaseStartedAt >= GS_FIELD_RESTORE_SETTLE_SECONDS) {
                    // 断言点 GS-E：系统冷却期力场消失——修饰键从靶舰乘区表移除（hullmod 逐帧对账口径），
                    // 且 EMP 承伤乘区复原 1.0。不读 maxSpeed 值：电弧 EMP 熄火会把航速值打到 0，与力场无关。
                    val modifierCleared = !hasGsFieldModifier(enemy)
                    val empMult = enemy.mutableStats.empDamageTakenMult.modifiedValue
                    gsFieldModifierCleared = modifierCleared
                    gsFieldRestoredEmpMult = empMult
                    if (modifierCleared && kotlin.math.abs(empMult - 1f) <= GS_FIELD_RESTORE_TOLERANCE) {
                        ctx.log.info(
                            "[ASTD-Automation] gs field restore evidence: 冷却后修饰键已移除、empMult=${"%.3f".format(empMult)} 复原（断言点 GS-E）",
                        )
                        transitionGsPhase(GS_PHASE_COMPLETED)
                    } else {
                        ctx.failureReason = "gs field not restored: 冷却后 modifierCleared=$modifierCleared empMult=${"%.3f".format(empMult)}" +
                                "（expect 键移除 + empMult 1.0 ± $GS_FIELD_RESTORE_TOLERANCE，断言点 GS-E：COOLDOWN 力场收口）"
                        transitionGsPhase(GS_PHASE_FAILED)
                    }
                } else if (ctx.elapsed - gsPhaseStartedAt >= GS_FIELD_RESTORE_TIMEOUT) {
                    ctx.failureReason = "gs field restore timeout: ${GS_FIELD_RESTORE_TIMEOUT.toInt()}s 内未完成复原采样（enemy=${enemy != null}）"
                    transitionGsPhase(GS_PHASE_FAILED)
                }
            }

            GS_PHASE_COMPLETED -> {
                stabilizeGsShips(engine, healEnemy = true, zeroPlayerFlux = false, blockSystem = true)
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "gs ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            gsPhase == GS_PHASE_FAILED -> "Failed"
            gsPhase != GS_PHASE_COMPLETED &&
                    ctx.elapsed - gsPhaseStartedAt > GS_PHASE_TIMEOUT -> {
                ctx.failureReason = "gs phase timeout: $gsPhase（fieldMultMin=${"%.3f".format(if (gsFieldMaxSpeedMultMin == Float.MAX_VALUE) -1f else gsFieldMaxSpeedMultMin)} " +
                        "fluxDelta=${"%.0f".format(gsActivationFluxDeltaMax)} latched=$gsStormActivationLatched " +
                        "overloadSeconds=${"%.2f".format(gsEnemyOverloadSeconds)}）"
                "Failed"
            }

            gsPhase == GS_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: lens_grav_storm_zw002 field/charge/release/overload/cooldown-restore evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：过载靶舰/力场电弧视觉与密蒙在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findGsPlayer(ctx.engine) }) { lockGsCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val gsPlayer = findGsPlayer(engine)
        val gsSystem = gsPlayer?.system
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（断言点 GS-A~GS-E）----
        json.appendLine("  \"gsPhase\": \"$gsPhase\",")
        json.appendLine("  \"gsSystemId\": ${jsonString(gsSystem?.id)},")
        json.appendLine("  \"gsSystemState\": ${jsonString(gsSystem?.state?.name)},")
        json.appendLine("  \"gsSystemCooldownRemaining\": ${formatFloat(gsSystem?.cooldownRemaining ?: -1f)},")
        json.appendLine("  \"gsFieldMaxSpeedMultMin\": ${formatFloat(if (gsFieldMaxSpeedMultMin == Float.MAX_VALUE) -1f else gsFieldMaxSpeedMultMin)},")
        json.appendLine("  \"gsFieldTurnRateMultMin\": ${formatFloat(if (gsFieldTurnRateMultMin == Float.MAX_VALUE) -1f else gsFieldTurnRateMultMin)},")
        json.appendLine("  \"gsFieldEmpMultMax\": ${formatFloat(gsFieldEmpMultMax)},")
        json.appendLine("  \"gsActivationFluxDeltaMax\": ${formatFloat(gsActivationFluxDeltaMax)},")
        json.appendLine("  \"gsChargeDamageTakenMultMin\": ${formatFloat(if (gsChargeDamageTakenMultMin == Float.MAX_VALUE) -1f else gsChargeDamageTakenMultMin)},")
        json.appendLine("  \"gsPhaseLockoutPressFrames\": $gsPhaseLockoutPressFrames,")
        json.appendLine("  \"gsPhaseLockoutPhasedFrames\": $gsPhaseLockoutPhasedFrames,")
        json.appendLine("  \"gsPhaseLockoutCloakPinnedFrames\": $gsPhaseLockoutCloakPinnedFrames,")
        json.appendLine("  \"gsStormActivationLatched\": $gsStormActivationLatched,")
        json.appendLine("  \"gsFieldMultMinDuringRelease\": ${formatFloat(if (gsFieldMultMinDuringRelease == Float.MAX_VALUE) -1f else gsFieldMultMinDuringRelease)},")
        json.appendLine("  \"gsEnemyOverloadObserved\": ${gsEnemyOverloadStartedAt >= 0f},")
        json.appendLine("  \"gsEnemyOverloadSeconds\": ${formatFloat(gsEnemyOverloadSeconds)},")
        json.appendLine("  \"gsEnemyHpBeforeRelease\": ${formatFloat(gsEnemyHpBeforeRelease)},")
        json.appendLine("  \"gsEnemyHpMinAfterRelease\": ${formatFloat(if (gsEnemyHpMinAfterRelease == Float.MAX_VALUE) -1f else gsEnemyHpMinAfterRelease)},")
        json.appendLine(
            "  \"gsEnemyHpDropMax\": ${
                formatFloat(
                    if (gsEnemyHpBeforeRelease < 0f || gsEnemyHpMinAfterRelease == Float.MAX_VALUE) -1f
                    else gsEnemyHpBeforeRelease - gsEnemyHpMinAfterRelease
                )
            },"
        )
        json.appendLine("  \"gsFieldModifierLostEarly\": $gsFieldModifierLostEarly,")
        json.appendLine("  \"gsFieldModifierCleared\": $gsFieldModifierCleared,")
        json.appendLine("  \"gsFieldRestoredEmpMult\": ${formatFloat(gsFieldRestoredEmpMult)},")
        json.appendLine("  \"gsPlayerCurrFlux\": ${formatFloat(gsPlayer?.fluxTracker?.currFlux ?: -1f)},")
    }

    private companion object {
        // 密蒙引力磁暴发生器场景：相位机、锚点与期望证据（断言点 GS-A~GS-E）。
        private const val GS_PHASE_SPAWN = "SPAWN"
        private const val GS_PHASE_FIELD_OBSERVE = "FIELD_OBSERVE"
        private const val GS_PHASE_ACTIVATE = "ACTIVATE"
        private const val GS_PHASE_RELEASE = "RELEASE"
        private const val GS_PHASE_COOLDOWN_FIELD_OFF = "COOLDOWN_FIELD_OFF"
        private const val GS_PHASE_COMPLETED = "COMPLETED"
        private const val GS_PHASE_FAILED = "FAILED"
        private const val GS_PLAYER_HULL = "astd_zw_002"
        private const val GS_ENEMY_HULL = "dominator"
        private const val GS_SYSTEM_ID = "astd_grav_storm"

        // 靶舰锚点在母舰正前方 600su：锥内（±30°）且在系统射程 1200su 内，
        // 同时 ≤ 力场半射程 750su 满效区（断言点 GS-A 满效口径）。
        private val GS_PLAYER_ANCHOR = Vector2f(-700f, 0f)
        private val GS_ENEMY_ANCHOR = Vector2f(-100f, 0f)
        private val GS_CAMERA_CENTER = Vector2f(-400f, 0f)
        private const val GS_CAMERA_VISIBLE_HEIGHT = 1500f
        private const val GS_SPAWN_SETTLE_SECONDS = 0.6f


        // FIELD_OBSERVE（断言点 GS-A）：玩家恒 v2 → 航速/转向 ×0.8（界 [0.74, 0.86] 容忍帧量化），
        // EMP 承伤 +0.5 绝对位移（dominator 基础 1.0 → 1.5，界 [1.4, 1.6]）。
        private const val GS_FIELD_OBSERVE_SECONDS = 1.2f
        private const val GS_FIELD_STAT_MULT_MIN = 0.74f
        private const val GS_FIELD_STAT_MULT_MAX = 0.86f
        private const val GS_FIELD_EMP_MULT_MIN = 1.4f
        private const val GS_FIELD_EMP_MULT_MAX = 1.6f

        // ACTIVATE（断言点 GS-B）：激活软辐能 = 基础容量 ×20%（zw_002 12000 → ≈2400；
        // 界 [2000, 2800] 容忍逐帧耗散 900/s 的帧量化）；充能期全承伤 ×0.5（界 0.51）。
        // useSystem 按帧重试（同 FGL），10s 超时兜底。
        private const val GS_ACTIVATE_TIMEOUT = 10f
        private const val GS_EXPECT_ACTIVATION_FLUX_MIN = 2000f
        private const val GS_EXPECT_ACTIVATION_FLUX_MAX = 2800f
        private const val GS_EXPECT_DAMAGE_TAKEN_MAX = 0.51f

        // ACTIVATE 锁定窗（断言点 GS-B2）：相位锁定时长 2s（GravStormTuning.PHASE_LOCKOUT_SECONDS
        // 口径，其声明不在本模块可见面，此处字面值镜像，改动锁定时长需同步）；施压窗口留 0.2s
        // 余量（防止锁定解除瞬间的相位键真激活把充能打断）；窗内施压帧数下限容忍帧率抖动。
        private const val GS_PHASE_LOCKOUT_PRESS_SECONDS = 1.8f
        private const val GS_PHASE_LOCKOUT_MIN_PRESS_FRAMES = 30

        // RELEASE（断言点 GS-C/D + GS-E 前置）：充能 4s + 释放窗 1.5s + 过载收尾余量；
        // 巡洋舰 v2 满充能强制过载锚点 2s（界 [1.5, 2.5]，帧粒度宽松）；
        // 电弧 8~16 道 × 300 能量对剥甲靶舰（dominator 14000 HP）结算，下界 1500 取保守口径；
        // 系统激活期（IN/ACTIVE/OUT）力场不失效——对账敌舰乘区表中的力场修饰键在场
        // （键前缀同 GravEmFieldHullMod.MOD_ID_PREFIX；电弧 EMP 熄火会把 maxSpeed 值打 0，值层面不可信）。
        private const val GS_RELEASE_TIMEOUT = 12f
        private const val GS_OVERLOAD_SECONDS_MIN = 1.5f
        private const val GS_OVERLOAD_SECONDS_MAX = 2.5f
        private const val GS_EXPECT_ENEMY_HP_DROP = 1500f

        // COOLDOWN_FIELD_OFF（断言点 GS-E）：冷却首帧 hullmod 逐帧对账 unmodify，
        // settle 0.6s 后力场修饰键必须离场且 EMP 承伤乘区回 1.0（±0.03）。
        private const val GS_FIELD_RESTORE_SETTLE_SECONDS = 0.6f
        private const val GS_FIELD_RESTORE_TOLERANCE = 0.03f
        private const val GS_FIELD_RESTORE_TIMEOUT = 5f
        private const val GS_PHASE_TIMEOUT = 90f
    }
}
