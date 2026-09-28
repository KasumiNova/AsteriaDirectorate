package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexBeamEffect
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 湮灭涡旋场景。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class AnnihilationVortexScenario : AbstractAutomationScenario() {
    // ==== annihilation vortex 场景状态（相位机 MOUNT → ABSORB → COLLAPSE → EMPTY_PREP → EMPTY_FIRE → ENEMY_SCALE → HOST_DEATH → COMPLETED） ====
    private var avPhase = AV_PHASE_MOUNT
    private var avPhaseStartedAt = 0f
    private var avAbsorbBaseline = 0
    private var avCollapseBaseline = 0
    private var avEmptyCollapseDamage = -1f

    // 爆发循环计时：beam on/off 沿（isFiring 沿检测），验证 2s 开火 / 9s 循环。
    private var avBeamOnSince = -1f
    private var avBeamOffSince = -1f
    private var avBurstOnSeconds = -1f
    private var avBurstOffSeconds = -1f
    private var avHiddenBeamOk = false
    private var avScaleStep = 0
    private var avScaleStepAt = -1f
    private var avScaleRadius1 = -1f
    private var avScaleRadius2 = -1f
    private var avScaleRadius5 = -1f
    private var avScaleThreshold5 = -1f
    private var avScaleAoe5 = -1f
    private var avScale5Ticks = 0
    private var avScale5WallStartNanos = 0L
    private var avScale5Fps = -1f
    private var avHostDeathPoolRecycledBaseline = 0
    private var avHostDeathCollapseBaseline = 0
    private var avHostKilled = false
    private var avHostKilledAt = -1f
    private var avProjectilesSwept = false

    override val scenarioId: String = ASTDInGameAutomationScenario.AV_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isAvEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockAvCamera(engine)
        // HUD 状态条目仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检吞噬池 HUD 与敌版三档（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findAvPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.AV_SCENARIO_ID} combat plugin initialized")
    }

    private fun findAvPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == AV_PLAYER_HULL }

    private fun findAvSynergy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == AV_ODYSSEY_HULL }

    private fun findAvEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == AV_ODYSSEY_HULL }

    private fun findAvFeeder(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == AV_FEEDER_HULL }

    private fun findAvWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.AV_WEAPON_ID }

    private fun lockAvCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, AV_CAMERA_CENTER, 900f)
    }

    /** 强制部署 mission reserves（协同/敌版/投喂三舰非旗舰，必须手动出场；按舰体分配锚点）。 */
    private fun deployAvReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val hullId = member.hullId ?: continue
                val anchor = when {
                    side == FleetSide.PLAYER && hullId == AV_PLAYER_HULL -> AV_PLAYER_ANCHOR
                    side == FleetSide.PLAYER && hullId == AV_ODYSSEY_HULL -> AV_SYNERGY_ANCHOR
                    side == FleetSide.ENEMY && hullId == AV_ODYSSEY_HULL -> AV_ENEMY_ANCHOR
                    side == FleetSide.ENEMY && hullId == AV_FEEDER_HULL -> AV_FEEDER_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionAvPhase(next: String) {
        ctx.log.info("[ASTD-Automation] av phase $avPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        avPhase = next
        avPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeEdaShips）：保留舰 AI + 逐帧 currAngle 对准 + setForceFireOneFrame
     * 绕 AutofireAI 死锁（01/03 实证路径）。[keepPlayerAlive]=false 时不再回满玩家舰体。
     */
    private fun stabilizeAvShips(
        engine: CombatEngineAPI,
        playerFire: Boolean,
        feederFire: Boolean,
        synergyFire: Boolean = false,
        enemyFire: Boolean = false,
        keepPlayerAlive: Boolean = true,
    ) {
        val player = findAvPlayer(engine)
        val feeder = findAvFeeder(engine)
        val synergy = findAvSynergy(engine)
        val enemy = findAvEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, AV_PLAYER_ANCHOR, 0f, allowFire = playerFire, preserveAI = true)
            player.shipTarget = feeder
            if (keepPlayerAlive) player.hitpoints = player.maxHitpoints
            setWeaponGroupAutofire(player, playerFire, AV_WEAPON_IDS)
            stageAvFireControl(player, feeder, playerFire)
        }
        if (feeder != null && !feeder.isHulk) {
            stabilizeShip(feeder, AV_FEEDER_ANCHOR, 180f, allowFire = feederFire, preserveAI = true)
            feeder.shipTarget = player
            feeder.hitpoints = feeder.maxHitpoints
            setWeaponGroupAutofire(feeder, feederFire, AV_FEEDER_WEAPON_IDS)
            stageAvFireControl(feeder, player, feederFire)
        }
        if (synergy != null && !synergy.isHulk) {
            stabilizeShip(synergy, AV_SYNERGY_ANCHOR, 0f, allowFire = synergyFire, preserveAI = true)
            synergy.shipTarget = feeder
            synergy.hitpoints = synergy.maxHitpoints
            setWeaponGroupAutofire(synergy, synergyFire, AV_WEAPON_IDS)
            stageAvFireControl(synergy, feeder, synergyFire)
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, AV_ENEMY_ANCHOR, 180f, allowFire = enemyFire, preserveAI = true)
            enemy.shipTarget = player
            enemy.hitpoints = enemy.maxHitpoints
            setWeaponGroupAutofire(enemy, enemyFire, AV_WEAPON_IDS)
            stageAvFireControl(enemy, player, enemyFire)
        }
    }

    /** 舞台直控开火（范式同 stageEdaFireControl）：湮灭涡旋按 id 过滤，投喂舰按投喂武器 id 过滤。 */
    private fun stageAvFireControl(ship: ShipAPI, target: ShipAPI?, fire: Boolean) {
        val ids = if (ship.hullSpec?.hullId == AV_FEEDER_HULL) AV_FEEDER_WEAPON_IDS else AV_WEAPON_IDS
        for (w in ship.allWeapons) {
            if (w.id !in ids) continue
            if (target != null) w.currAngle = Misc.getAngleInDegrees(w.location, target.location)
            w.setForceFireOneFrame(fire)
        }
    }

    /** 爆发循环计时：isFiring 沿检测，记录最近一次开火时长与停火间隔（期望 2s on / 9s off）。 */
    private fun trackAvBurstCycle(weapon: WeaponAPI?) {
        weapon ?: return
        if (weapon.isFiring) {
            if (avBeamOnSince < 0f) {
                avBeamOnSince = ctx.elapsed
                if (avBeamOffSince >= 0f && avBurstOffSeconds < 0f) avBurstOffSeconds = ctx.elapsed - avBeamOffSince
            }
            // Hidden 生效证据：原版束渲染被 HiddenBeamRenderEffect 压到 0.01（自绘束接管）。
            val beam = weapon.beams?.firstOrNull()
            if (beam != null && beam.width <= 0.02f) avHiddenBeamOk = true
        } else if (avBeamOnSince >= 0f) {
            avBurstOnSeconds = ctx.elapsed - avBeamOnSince
            avBeamOnSince = -1f
            avBeamOffSince = ctx.elapsed
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployAvReserveShips(engine)
        lockAvCamera(engine)

        val player = findAvPlayer(engine)
        val feeder = findAvFeeder(engine)
        val synergy = findAvSynergy(engine)
        val enemy = findAvEnemy(engine)
        val playerAv = findAvWeapon(player)
        val synergyAv = findAvWeapon(synergy)

        when (avPhase) {
            AV_PHASE_MOUNT -> {
                stabilizeAvShips(engine, playerFire = false, feederFire = false)
                if (ctx.elapsed - avPhaseStartedAt >= AV_MOUNT_SETTLE_SECONDS) {
                    val playerSlot = playerAv?.slot?.id
                    val synergySlot = synergyAv?.slot?.id
                    if (playerSlot == AV_PLAYER_EXPECT_SLOT && synergySlot == AV_SYNERGY_EXPECT_SLOT) {
                        avAbsorbBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_ABSORBED_PLAYER)
                        transitionAvPhase(AV_PHASE_ABSORB)
                    } else {
                        ctx.failureReason =
                            "av mount mismatch: playerSlot=$playerSlot(expect ${AV_PLAYER_EXPECT_SLOT}), synergySlot=$synergySlot(expect ${AV_SYNERGY_EXPECT_SLOT})"
                        transitionAvPhase(AV_PHASE_FAILED)
                    }
                }
            }

            AV_PHASE_ABSORB -> {
                stabilizeAvShips(engine, playerFire = true, feederFire = true)
                trackAvBurstCycle(playerAv)
                val absorbed = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_ABSORBED_PLAYER) - avAbsorbBaseline
                if (absorbed >= AV_ABSORB_TARGET && avBurstOnSeconds > 0f && avBurstOffSeconds > 0f) {
                    if (kotlin.math.abs(avBurstOnSeconds - AV_EXPECT_BURST_ON) > AV_BURST_ON_TOLERANCE) {
                        ctx.failureReason = "av burst on=${avBurstOnSeconds}s, expect≈${AV_EXPECT_BURST_ON}s"
                        transitionAvPhase(AV_PHASE_FAILED)
                    } else if (kotlin.math.abs(avBurstOffSeconds - AV_EXPECT_BURST_OFF) > AV_BURST_OFF_TOLERANCE) {
                        ctx.failureReason = "av burst off=${avBurstOffSeconds}s, expect≈${AV_EXPECT_BURST_OFF}s"
                        transitionAvPhase(AV_PHASE_FAILED)
                    } else if (!avHiddenBeamOk) {
                        ctx.failureReason = "av hidden beam not observed: vanilla beam width not zeroed while firing"
                        transitionAvPhase(AV_PHASE_FAILED)
                    } else {
                        avCollapseBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT)
                        transitionAvPhase(AV_PHASE_COLLAPSE)
                    }
                }
            }

            AV_PHASE_COLLAPSE -> {
                stabilizeAvShips(engine, playerFire = false, feederFire = false)
                val collapses =
                    AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT) - avCollapseBaseline
                val hits = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_HITS_PLAYER)
                if (collapses >= 1) {
                    if (hits >= 1) {
                        transitionAvPhase(AV_PHASE_EMPTY_PREP)
                    } else {
                        ctx.failureReason = "av collapse hits=$hits, expect>=1（投喂舰应位于坍缩半径内）"
                        transitionAvPhase(AV_PHASE_FAILED)
                    }
                }
            }

            AV_PHASE_EMPTY_PREP -> {
                stabilizeAvShips(engine, playerFire = false, feederFire = false)
                if (!avProjectilesSwept) {
                    avProjectilesSwept = true
                    // 净空：清掉在场弹体，保证下一发爆发为空池（保底 500 证据不被残余投喂污染）。
                    for (p in engine.projectiles.toList()) engine.removeEntity(p)
                }
                if (ctx.elapsed - avPhaseStartedAt >= AV_EMPTY_PREP_SECONDS) {
                    avCollapseBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT)
                    transitionAvPhase(AV_PHASE_EMPTY_FIRE)
                }
            }

            AV_PHASE_EMPTY_FIRE -> {
                stabilizeAvShips(engine, playerFire = true, feederFire = false)
                val collapses =
                    AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT) - avCollapseBaseline
                if (collapses >= 1) {
                    avEmptyCollapseDamage =
                        AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_DAMAGE_PLAYER)
                    if (kotlin.math.abs(avEmptyCollapseDamage - AV_EXPECT_EMPTY_DAMAGE) <= AV_EMPTY_DAMAGE_TOLERANCE) {
                        DifficultyTuningImpl.installScaleForTests(1f)
                        avScaleStep = 0
                        avScaleStepAt = ctx.elapsed
                        transitionAvPhase(AV_PHASE_ENEMY_SCALE)
                    } else {
                        ctx.failureReason = "av empty collapse damage=$avEmptyCollapseDamage, expect≈$AV_EXPECT_EMPTY_DAMAGE（空池保底）"
                        transitionAvPhase(AV_PHASE_FAILED)
                    }
                }
            }

            AV_PHASE_ENEMY_SCALE -> {
                // k_s=5 帧率窗口：step3 期间累计 tick 数 / 墙钟秒。
                if (avScaleStep == 3) avScale5Ticks++
                stabilizeAvShips(engine, playerFire = false, feederFire = false, enemyFire = true)
                val enemyRadius = AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_RADIUS_ENEMY)
                when (avScaleStep) {
                    0 -> if (enemyRadius > 0f) {
                        avScaleRadius1 = enemyRadius
                        ctx.log.info("[ASTD-Automation] av enemy radius@k_s=1: $avScaleRadius1")
                        DifficultyTuningImpl.installScaleForTests(2f)
                        avScaleStep = 1; avScaleStepAt = ctx.elapsed
                    }

                    1 -> if (enemyRadius > 0f && kotlin.math.abs(enemyRadius - avScaleRadius1) > 0.5f) {
                        avScaleRadius2 = enemyRadius
                        ctx.log.info("[ASTD-Automation] av enemy radius@k_s=2: $avScaleRadius2")
                        DifficultyTuningImpl.installScaleForTests(5f)
                        avScaleStep = 2; avScaleStepAt = ctx.elapsed
                    }

                    2 -> if (enemyRadius > 0f && kotlin.math.abs(enemyRadius - avScaleRadius2) > 0.5f) {
                        avScaleRadius5 = enemyRadius
                        avScaleThreshold5 =
                            AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_THRESHOLD_ENEMY)
                        avScaleAoe5 = AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_AOEMULT_ENEMY)
                        ctx.log.info("[ASTD-Automation] av enemy radius@k_s=5: $avScaleRadius5 threshold=$avScaleThreshold5 aoe=$avScaleAoe5")
                        avScale5Ticks = 0
                        avScale5WallStartNanos = System.nanoTime()
                        avScaleStep = 3; avScaleStepAt = ctx.elapsed
                    }

                    3 -> if (ctx.elapsed - avScaleStepAt >= AV_SCALE5_FPS_WINDOW_SECONDS) {
                        val wallSeconds = (System.nanoTime() - avScale5WallStartNanos) / 1_000_000_000.0
                        avScale5Fps = if (wallSeconds > 0.0) (avScale5Ticks / wallSeconds).toFloat() else -1f
                        DifficultyTuningImpl.installScaleForTests(null)
                        val radiusOk = kotlin.math.abs(avScaleRadius1 - 150f) <= 1f &&
                                kotlin.math.abs(avScaleRadius2 - 187.5f) <= 1f &&
                                kotlin.math.abs(avScaleRadius5 - 300f) <= 1f
                        val k5Ok = kotlin.math.abs(avScaleThreshold5 - 16000f) <= 1f && kotlin.math.abs(avScaleAoe5 - 2.5f) <= 0.01f
                        when {
                            !radiusOk -> {
                                ctx.failureReason = "av enemy radius 三档=$avScaleRadius1/$avScaleRadius2/$avScaleRadius5, expect 150/187.5/300"
                                transitionAvPhase(AV_PHASE_FAILED)
                            }

                            !k5Ok -> {
                                ctx.failureReason = "av enemy k_s=5 threshold=$avScaleThreshold5 aoe=$avScaleAoe5, expect 16000/2.5"
                                transitionAvPhase(AV_PHASE_FAILED)
                            }

                            avScale5Fps < AV_SCALE5_MIN_FPS -> {
                                ctx.failureReason = "av k_s=5 fps=$avScale5Fps < $AV_SCALE5_MIN_FPS（300su 涡旋性能门槛）"
                                transitionAvPhase(AV_PHASE_FAILED)
                            }

                            else -> {
                                avHostDeathPoolRecycledBaseline =
                                    AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_POOL_RECYCLED)
                                transitionAvPhase(AV_PHASE_HOST_DEATH)
                            }
                        }
                    }
                }
            }

            AV_PHASE_HOST_DEATH -> {
                // 击杀对象 = 协同槽 odyssey_A（非旗舰）：宿主死亡机制验证与旗舰解耦——
                // 杀旗舰会弹出增援/换旗舰对话框遮住整个画面，COMPLETED 截图舞台将无画面可拍（第三轮实证）。
                stabilizeAvShips(engine, playerFire = false, feederFire = false, synergyFire = !avHostKilled)
                if (!avHostKilled && synergyAv?.isFiring == true && synergy != null) {
                    // 中束击杀宿主：池应自回收（INFO + telemetry），不得触发坍缩。
                    avHostKilled = true
                    avHostKilledAt = ctx.elapsed
                    avHostDeathCollapseBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT)
                    synergy.hitpoints = 1f
                    engine.applyDamage(synergy, synergy.location, 1_000_000f, DamageType.ENERGY, 0f, true, false, feeder ?: synergy, false)
                    ctx.log.info("[ASTD-Automation] av synergy host killed mid-beam at ${"%.2f".format(ctx.elapsed)}s")
                }
                if (avHostKilled && ctx.elapsed - avHostKilledAt >= AV_HOST_DEATH_SETTLE_SECONDS) {
                    val recycled = AnnihilationVortexBeamEffect.counter(
                        engine,
                        AnnihilationVortexBeamEffect.TELEMETRY_POOL_RECYCLED
                    ) - avHostDeathPoolRecycledBaseline
                    val collapses = AnnihilationVortexBeamEffect.counter(
                        engine,
                        AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT
                    ) - avHostDeathCollapseBaseline
                    when {
                        recycled < 1 -> {
                            ctx.failureReason = "av host death pool recycled=$recycled, expect>=1（SELF_MANAGED 自回收 + INFO）"
                            transitionAvPhase(AV_PHASE_FAILED)
                        }

                        collapses != 0 -> {
                            ctx.failureReason = "av host death collapses=$collapses, expect 0（宿主死亡涡旋哑火不坍缩）"
                            transitionAvPhase(AV_PHASE_FAILED)
                        }

                        else -> transitionAvPhase(AV_PHASE_COMPLETED)
                    }
                }
            }

            AV_PHASE_COMPLETED -> {
                stageAvCompletedFrame(engine)
            }
        }

        val state = when {
            // 宿主死亡相位后协同舰允许缺席（中束击杀即消失正是观测对象）；其余三舰全程必须在场。
            (synergy == null && !avHostKilled) || player == null || feeder == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason =
                        "av ships missing: player=${player != null}, feeder=${feeder != null}, synergy=${synergy != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            avPhase == AV_PHASE_FAILED -> "Failed"
            avPhase != AV_PHASE_COMPLETED &&
                    ctx.elapsed - avPhaseStartedAt > AV_PHASE_TIMEOUT -> {
                ctx.failureReason = "av phase timeout: $avPhase"
                "Failed"
            }

            avPhase == AV_PHASE_COMPLETED -> {
                // 截图门控：爆发中段才上报 Completed——SSOptimizer 在上报时刻连拍三帧，
                // 2s on / 9s off 爆发循环下随机时刻大概率拍到无束空场；保底超时防舞台卡死。
                val midBurst = avBeamOnSince >= 0f && ctx.elapsed - avBeamOnSince >= AV_COMPLETED_BEAM_ON_SECONDS
                if (midBurst || ctx.elapsed - avPhaseStartedAt >= AV_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: annihilation_vortex_basic absorb/collapse/empty/scaling/host-death evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, playerAv)
        }
    }

    /** COMPLETED 截图舞台：玩家旗舰（sunder WS 003）开火 + 投喂舰投喂，涡旋/吸收 flare/坍缩烟云/HUD 入帧。 */
    private fun stageAvCompletedFrame(engine: CombatEngineAPI) {
        stabilizeAvShips(engine, playerFire = true, feederFire = true)
        trackAvBurstCycle(findAvWeapon(findAvPlayer(engine)))
        lockAvCamera(engine)
    }

    // 捕获帧间隔 0.6s：协同槽开火 + 投喂，涡旋面/吸收 flare/坍缩烟云在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findAvPlayer(ctx.engine) }) { stageAvCompletedFrame(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val avPlayerW = findAvWeapon(findAvPlayer(engine))
        val avSynergyW = findAvWeapon(findAvSynergy(engine))
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 04 §4.2 烟测检查点）----
        json.appendLine("  \"avPhase\": \"$avPhase\",")
        json.appendLine("  \"avPlayerSlotId\": ${jsonString(avPlayerW?.slot?.id)},")
        json.appendLine("  \"avSynergySlotId\": ${jsonString(avSynergyW?.slot?.id)},")
        json.appendLine("  \"avPlayerWeaponRange\": ${formatFloat(avPlayerW?.range ?: -1f)},")
        json.appendLine("  \"avPlayerWeaponFiring\": ${avPlayerW?.isFiring ?: false},")
        json.appendLine(
            "  \"avAbsorbedPlayer\": ${
                AnnihilationVortexBeamEffect.counter(
                    engine,
                    AnnihilationVortexBeamEffect.TELEMETRY_ABSORBED_PLAYER
                )
            },"
        )
        json.appendLine(
            "  \"avAbsorbedEnemy\": ${
                AnnihilationVortexBeamEffect.counter(
                    engine,
                    AnnihilationVortexBeamEffect.TELEMETRY_ABSORBED_ENEMY
                )
            },"
        )
        json.appendLine(
            "  \"avCollapseCount\": ${
                AnnihilationVortexBeamEffect.counter(
                    engine,
                    AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT
                )
            },"
        )
        json.appendLine(
            "  \"avLastCollapseDamagePlayer\": ${
                formatFloat(
                    AnnihilationVortexBeamEffect.telemetryFloat(
                        engine,
                        AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_DAMAGE_PLAYER
                    )
                )
            },"
        )
        json.appendLine(
            "  \"avLastCollapseHitsPlayer\": ${
                AnnihilationVortexBeamEffect.counter(
                    engine,
                    AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_HITS_PLAYER
                )
            },"
        )
        json.appendLine("  \"avEmptyCollapseDamage\": ${formatFloat(avEmptyCollapseDamage)},")
        json.appendLine("  \"avBurstOnSeconds\": ${formatFloat(avBurstOnSeconds)},")
        json.appendLine("  \"avBurstOffSeconds\": ${formatFloat(avBurstOffSeconds)},")
        json.appendLine("  \"avHiddenBeamOk\": $avHiddenBeamOk,")
        json.appendLine("  \"avHudFrames\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_HUD_FRAMES)},")
        json.appendLine(
            "  \"avFloatyCount\": ${
                AnnihilationVortexBeamEffect.counter(
                    engine,
                    AnnihilationVortexBeamEffect.TELEMETRY_FLOATY_COUNT
                )
            },"
        )
        json.appendLine(
            "  \"avPoolRecycled\": ${
                AnnihilationVortexBeamEffect.counter(
                    engine,
                    AnnihilationVortexBeamEffect.TELEMETRY_POOL_RECYCLED
                )
            },"
        )
        json.appendLine("  \"avScaleRadius1\": ${formatFloat(avScaleRadius1)},")
        json.appendLine("  \"avScaleRadius2\": ${formatFloat(avScaleRadius2)},")
        json.appendLine("  \"avScaleRadius5\": ${formatFloat(avScaleRadius5)},")
        json.appendLine("  \"avScaleThreshold5\": ${formatFloat(avScaleThreshold5)},")
        json.appendLine("  \"avScaleAoe5\": ${formatFloat(avScaleAoe5)},")
        json.appendLine("  \"avScale5Fps\": ${formatFloat(avScale5Fps)},")
        json.appendLine("  \"avHostKilled\": $avHostKilled,")
        json.appendLine("  \"avDevMode\": ${Global.getSettings().isDevMode},")
    }

    private companion object {
        // 湮灭涡旋场景：相位机、锚点与期望证据。
        private const val AV_PHASE_MOUNT = "MOUNT"
        private const val AV_PHASE_ABSORB = "ABSORB"
        private const val AV_PHASE_COLLAPSE = "COLLAPSE"
        private const val AV_PHASE_EMPTY_PREP = "EMPTY_PREP"
        private const val AV_PHASE_EMPTY_FIRE = "EMPTY_FIRE"
        private const val AV_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val AV_PHASE_HOST_DEATH = "HOST_DEATH"
        private const val AV_PHASE_COMPLETED = "COMPLETED"
        private const val AV_PHASE_FAILED = "FAILED"
        private const val AV_PLAYER_HULL = "sunder"
        private const val AV_ODYSSEY_HULL = "odyssey"
        private const val AV_FEEDER_HULL = "vigilance"
        private const val AV_PLAYER_EXPECT_SLOT = "WS 003"
        private const val AV_SYNERGY_EXPECT_SLOT = "WS 001"
        private val AV_PLAYER_ANCHOR = Vector2f(-400f, 0f)
        private val AV_SYNERGY_ANCHOR = Vector2f(-400f, -350f)
        private val AV_FEEDER_ANCHOR = Vector2f(400f, 0f)
        private val AV_ENEMY_ANCHOR = Vector2f(600f, 320f)
        private val AV_CAMERA_CENTER = Vector2f(60f, -60f)
        private val AV_WEAPON_IDS = setOf(ASTDInGameAutomationScenario.AV_WEAPON_ID)
        private val AV_FEEDER_WEAPON_IDS = setOf("lightac", "annihilatorpod")

        // MOUNT 相位 settle；装配检查在渲染器就绪后一次判定。
        private const val AV_MOUNT_SETTLE_SECONDS = 0.6f

        // ABSORB 相位达标：玩家侧累计吸收 3 发 + 观察到一个完整 2s/9s 爆发循环。
        private const val AV_ABSORB_TARGET = 3
        private const val AV_EXPECT_BURST_ON = 2f
        private const val AV_BURST_ON_TOLERANCE = 0.7f
        private const val AV_EXPECT_BURST_OFF = 9f
        private const val AV_BURST_OFF_TOLERANCE = 2f

        // EMPTY_PREP：清场后等待武器 9s 冷却 settle 再点空池爆发。
        private const val AV_EMPTY_PREP_SECONDS = 10f

        // 空池保底：玩家 v2 AOE 倍率 1.0 → max(0, 500)×1.0 = 500。
        private const val AV_EXPECT_EMPTY_DAMAGE = 500f
        private const val AV_EMPTY_DAMAGE_TOLERANCE = 1f

        // k_s=5 帧率窗口（墙钟 3s）与最低帧率门槛（300su 涡旋性能检查点）。
        private const val AV_SCALE5_FPS_WINDOW_SECONDS = 3f
        private const val AV_SCALE5_MIN_FPS = 30f

        // HOST_DEATH：击杀后观察窗（池自回收 + 无坍缩断言）。
        private const val AV_HOST_DEATH_SETTLE_SECONDS = 3f

        // COMPLETED 截图门控：爆发进行中满此时长才上报 Completed（保证截图帧含束体/涡旋）；保底舞台超时。
        private const val AV_COMPLETED_BEAM_ON_SECONDS = 0.8f
        private const val AV_COMPLETED_STAGE_TIMEOUT = 15f
        private const val AV_PHASE_TIMEOUT = 90f
    }
}
