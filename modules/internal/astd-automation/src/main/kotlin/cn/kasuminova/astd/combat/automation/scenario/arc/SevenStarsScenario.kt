package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.sevenstars.SevenStarsChainScript
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * “七星”折跃发射器场景（规格 07 §4.2 烟测检查点）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class SevenStarsScenario : AbstractAutomationScenario() {
    // ==== seven stars 场景状态（相位机 MOUNT → NOKILL → CHAIN → TERMINAL → ENEMY_MULTI → COMPLETED） ====
    private var ssPhase = SS_PHASE_MOUNT
    private var ssPhaseStartedAt = 0f

    // NOKILL 相位：相位基线（固定 7 跳 / 零击杀观测面；投喂走 feedSsNokillMissiles 环形增压）。
    private var ssNokillKillsBaseline = 0
    private var ssNokillFlashBaseline = 0
    private var ssNokillRiftBaseline = 0

    // CHAIN 相位基线与帧率采样（连跳峰值性能门槛）。
    private var ssChainNoShipBaseline = 0
    private var ssChainFlashBaseline = 0
    private var ssChainFpsTicks = 0
    private var ssChainFpsWallStartNanos = 0L
    private var ssChainFps = -1f

    // TERMINAL 相位基线（单段终结与 EMP 电弧观测面）。
    private var ssTerminalSingleBaseline = 0
    private var ssTerminalEmpArcsBaseline = 0

    // ENEMY_MULTI 相位基线与玩家掉血观测（多段终结打玩家舰）。
    private var ssEnemyMultiBaseline = 0
    private var ssEnemyMinPlayerHp = Float.MAX_VALUE

    // 导弹投喂节流（CHAIN/COMPLETED 喂敌方鱼叉；ENEMY_MULTI 喂玩家侧鱼叉；环位角度见 feedSsMissiles）。
    private var ssMissileFeedAt = -1f

    // COMPLETED 截图门控：最近一次裂隙爆炸时刻（截图帧需含裂隙爆炸/折跃电弧）。
    private var ssLastFlashAt = -1f
    private var ssLastTrackedFlashCount = 0
    private var ssVigilanceSpawned = 0
    private var ssMissileFeedAngle = 0f

    override val scenarioId: String = ASTDInGameAutomationScenario.SS_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isSsEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockSsCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findSsPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SS_SCENARIO_ID} combat plugin initialized")
    }

    private fun findSsPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner == 0 && it.hullSpec?.hullId == SS_PLAYER_HULL && !it.isFighter }

    private fun findSsTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == SS_TARGET_HULL && !it.isFighter }

    private fun findSsEnemyCarrier(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == SS_PLAYER_HULL && !it.isFighter }

    private fun findSsWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.SS_WEAPON_ID }

    private fun lockSsCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, SS_CAMERA_CENTER, SS_CAMERA_VISIBLE_HEIGHT)
    }

    private fun deploySsReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val hullId = member.hullId ?: continue
                when {
                    side == FleetSide.PLAYER && hullId == SS_PLAYER_HULL -> {
                        manager.spawnFleetMember(member, Vector2f(SS_PLAYER_ANCHOR), 0f, 0f)
                        manager.removeFromReserves(member)
                    }

                    side == FleetSide.ENEMY && hullId == SS_TARGET_HULL && ssVigilanceSpawned == 0 -> {
                        manager.spawnFleetMember(member, Vector2f(SS_TARGET_ANCHOR), 180f, 0f)
                        manager.removeFromReserves(member)
                        ssVigilanceSpawned++
                    }

                    side == FleetSide.ENEMY && hullId == SS_TARGET_HULL &&
                            ssPhase in listOf(SS_PHASE_TERMINAL, SS_PHASE_ENEMY_MULTI, SS_PHASE_COMPLETED) -> {
                        manager.spawnFleetMember(member, Vector2f(SS_TARGET_ANCHOR), 180f, 0f)
                        manager.removeFromReserves(member)
                        ssVigilanceSpawned++
                    }

                    side == FleetSide.ENEMY && hullId == SS_PLAYER_HULL && ssPhase == SS_PHASE_ENEMY_MULTI -> {
                        manager.spawnFleetMember(member, Vector2f(SS_ENEMY_ANCHOR), 180f, 0f)
                        manager.removeFromReserves(member)
                    }
                }
            }
        }
    }

    private fun transitionSsPhase(next: String) {
        ctx.log.info("[ASTD-Automation] ss phase $ssPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        ssPhase = next
        ssPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizePsShips）：玩家舰逐帧奶 + 辐能清零 + force fire 独占驱动；
     * 靶舰盾舞台性常关（终结单段证据要求伤害落到船体），TERMINAL 相位不奶靶舰 B（HP 下降即
     * 「单段终结命中」观测面）；ENEMY_MULTI 相位不奶玩家（HP 下降即「敌版多段打玩家」观测面）。
     */
    private fun stabilizeSsShips(engine: CombatEngineAPI, fire: Boolean) {
        val player = findSsPlayer(engine)
        val target = findSsTarget(engine)
        val carrier = findSsEnemyCarrier(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            // 舞台舰一律摘除 AI（实机判例：保留 AI 会每帧抢开盾，与 stabilize 的 toggleOff
            // 形成拉锯——终结单段 125 被盾面全额吸收，「命中掉血」观测面拿到 HP 满值误判失败）。
            stabilizeShip(player, SS_PLAYER_ANCHOR, 0f, allowFire = fire, preserveAI = false)
            player.shipTarget = target
            if (ssPhase != SS_PHASE_ENEMY_MULTI) player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            // 玩家舰盾舞台性常关：ENEMY_MULTI 相位敌版多段终结证据要求伤害落到玩家船体。
            player.shield?.toggleOff()
            setSsAutofire(player, false)
            val w = findSsWeapon(player)
            if (w != null) {
                w.currAngle = 0f
                w.setForceFireOneFrame(fire && ssPhase != SS_PHASE_ENEMY_MULTI)
            }
        }
        if (target != null && !target.isHulk) {
            stabilizeShip(target, SS_TARGET_ANCHOR, 180f, allowFire = false, preserveAI = false)
            target.shipTarget = null
            if (ssPhase != SS_PHASE_TERMINAL && ssPhase != SS_PHASE_COMPLETED) {
                target.hitpoints = target.maxHitpoints
            }
            target.shield?.toggleOff()
        }
        if (carrier != null && !carrier.isHulk) {
            stabilizeShip(carrier, SS_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = false)
            carrier.shipTarget = player
            carrier.hitpoints = carrier.maxHitpoints
            carrier.fluxTracker.currFlux = 0f
            setSsAutofire(carrier, false)
            val w = findSsWeapon(carrier)
            if (w != null) {
                w.currAngle = 180f
                // 部署免疫闸（实机判例第 7 轮：reserves 手动 spawn 舰船在部署后 ~2.5s 内，
                // 其作为 source 的脚本 applyDamage 同样全额无效——敌版多段终结前两段 0 伤害、
                // 2.5s 后各段正常掉血）——敌版舰部署后 4s 内不放行开火（同 SS_TERMINAL_SETTLE_SECONDS）。
                w.setForceFireOneFrame(
                    ssPhase == SS_PHASE_ENEMY_MULTI &&
                            ctx.elapsed - ssPhaseStartedAt >= SS_ENEMY_MULTI_SETTLE_SECONDS,
                )
            }
        }
    }

    /** 七星武器组 autofire 总开关（范式同 setPsAutofire）：force fire 独占驱动时关闭。 */
    private fun setSsAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id == ASTDInGameAutomationScenario.SS_WEAPON_ID }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    private fun feedSsMissiles(engine: CombatEngineAPI, atPlayerSide: Boolean, unkillable: Boolean = false) {
        if (ctx.elapsed < ssMissileFeedAt) return
        ssMissileFeedAt = ctx.elapsed + if (atPlayerSide) SS_MISSILE_FEED_INTERVAL else SS_ENEMY_FEED_INTERVAL
        ssMissileFeedAngle = (ssMissileFeedAngle + 137.5f) % 360f
        val source: ShipAPI? = if (atPlayerSide) {
            findSsTarget(engine) ?: findSsPlayer(engine)
        } else {
            findSsPlayer(engine)
        }
        val ringCenter = if (atPlayerSide) {
            Vector2f(SS_PLAYER_ANCHOR.x + SS_MISSILE_RING_OFFSET, SS_PLAYER_ANCHOR.y)
        } else {
            Vector2f(SS_ENEMY_ANCHOR.x - SS_MISSILE_RING_OFFSET, SS_ENEMY_ANCHOR.y)
        }
        val ringRad = Math.toRadians(ssMissileFeedAngle.toDouble())
        val spawn = Vector2f(
            ringCenter.x + (kotlin.math.cos(ringRad) * SS_MISSILE_RING_RADIUS).toFloat(),
            ringCenter.y + (kotlin.math.sin(ringRad) * SS_MISSILE_RING_RADIUS).toFloat(),
        )
        val angle = Misc.getAngleInDegrees(spawn, ringCenter)
        val aimRad = Math.toRadians(angle.toDouble())
        val vel = Vector2f(
            (kotlin.math.cos(aimRad) * SS_MISSILE_RING_SPEED).toFloat(),
            (kotlin.math.sin(aimRad) * SS_MISSILE_RING_SPEED).toFloat(),
        )
        source ?: return
        val spawned = engine.spawnProjectile(source, null, SS_FEED_MISSILE_ID, spawn, angle, vel)
        if (spawned == null) {
            ctx.failureReason = "ss missile spawn returned null for weaponId=$SS_FEED_MISSILE_ID"
            transitionSsPhase(SS_PHASE_FAILED)
            return
        }
        spawned.owner = if (atPlayerSide) 1 else 0
        if (unkillable) spawned.hitpoints = SS_NOKILL_MISSILE_HP
    }

    /**
     * NOKILL 相位投喂：环形稠密投喂（同 feedSsMissiles 路径）且每发 HP 增压至
     * [SS_NOKILL_MISSILE_HP]（裂隙爆炸不可摧毁）——实机判例（裂隙改版首跑）：单发直飞
     * 增压鱼叉 ~4s 飞出折跃范围，链在 1~2 跳即无候选进终结，拿不到「固定 7 跳」证据；
     * 环形投喂令候选持续在场，实证零击杀链跑满 7 跳再进终结（2026-08 裂隙改版移除
     * 击杀续跳门槛）。
     */
    private fun feedSsNokillMissiles(engine: CombatEngineAPI) {
        feedSsMissiles(engine, atPlayerSide = true, unkillable = true)
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deploySsReserveShips(engine)
        lockSsCamera(engine)

        val player = findSsPlayer(engine)
        val target = findSsTarget(engine)
        val weapon = findSsWeapon(player)
        val flash = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_FLASH)
        val rift = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_RIFT)
        val teleportArc = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TELEPORT_ARC)
        val kills = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_KILLS)
        val chainJumpsMax = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_CHAIN_JUMPS_MAX)
        val dissipateNoShip = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_SHIP)
        val terminalSingle = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_SINGLE)
        val terminalMulti = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_MULTI)
        val terminalSegmentsMax = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_SEGMENTS_MAX)
        val terminalEmpArcs = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_EMP_ARCS)

        when (ssPhase) {
            SS_PHASE_MOUNT -> {
                stabilizeSsShips(engine, fire = false)
                if (ctx.elapsed - ssPhaseStartedAt >= SS_MOUNT_SETTLE_SECONDS) {
                    val slot = weapon?.slot?.id
                    // 校验数据面原始射程（spec.maxRange）：舰体内置射程 hullmod（如奥德赛 targeting core）
                    // 只放大 weapon.range 有效值，不应计入装配校验。
                    val range = weapon?.spec?.maxRange ?: -1f
                    val hintsPd = weapon?.spec?.aiHints?.contains(WeaponAPI.AIHints.PD) == true
                    when {
                        slot != SS_PLAYER_SLOT || kotlin.math.abs(range - SS_EXPECT_RANGE) > SS_RANGE_TOLERANCE -> {
                            ctx.failureReason = "ss mount mismatch: slot=$slot range=$range(expect $SS_EXPECT_RANGE)"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        !hintsPd -> {
                            ctx.failureReason = "ss aiHints missing PD（装配面板 hints 校验）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        else -> {
                            ssNokillKillsBaseline = kills
                            ssNokillFlashBaseline = flash
                            ssNokillRiftBaseline = rift
                            target?.hitpoints = target.maxHitpoints
                            transitionSsPhase(SS_PHASE_NOKILL)
                        }
                    }
                }
            }

            SS_PHASE_NOKILL -> {
                stabilizeSsShips(engine, fire = true)
                feedSsNokillMissiles(engine)
                // 固定 7 跳定案证据面：对不可摧毁增压鱼叉零击杀链仍跑满 7 跳且 7 次延迟爆炸
                // 全部结算（flash delta >= 7 保证 pending 队列排空，规避 0.5s 延迟爆炸的
                // 计数竞态）；随后终结判定的延迟段（0.5s 后结算）对靶舰 A 的命中在本相位
                // 不断言——A 在转段即移除，存续终端段命中落空属预期（resolveTerminal 守护）。
                if (chainJumpsMax >= SS_CHAIN_MAX_JUMPS && flash - ssNokillFlashBaseline >= SS_CHAIN_MAX_JUMPS) {
                    when {
                        kills - ssNokillKillsBaseline != 0 -> {
                            ctx.failureReason = "ss nokill kills delta=${kills - ssNokillKillsBaseline}, expect 0（增压鱼叉不可摧毁）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        rift - ssNokillRiftBaseline < SS_CHAIN_MAX_JUMPS -> {
                            ctx.failureReason = "ss nokill rift delta=${rift - ssNokillRiftBaseline} < $SS_CHAIN_MAX_JUMPS（每跳一次裂隙爆炸）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        else -> {
                            // 进入连跳相位：移除靶舰 A（空域无敌舰，无处可去终结走「无舰消散」路径）
                            target?.let { engine.removeEntity(it) }
                            ssChainNoShipBaseline = dissipateNoShip
                            ssChainFlashBaseline = flash
                            ssChainFpsTicks = 0
                            ssChainFpsWallStartNanos = System.nanoTime()
                            transitionSsPhase(SS_PHASE_CHAIN)
                        }
                    }
                }
            }

            SS_PHASE_CHAIN -> {
                stabilizeSsShips(engine, fire = true)
                feedSsMissiles(engine, atPlayerSide = true)
                ssChainFpsTicks++
                // 延迟爆炸排空闸（裂隙改版：爆炸结算滞后折跃 0.5s，「无舰消散」判定点早于末跳
                // 爆炸结算点）——flash delta >= 7 保证至少一条完整 7 跳链的爆炸全部结算完毕，
                // kills/rift 断言不读半结算状态。
                if (chainJumpsMax >= SS_CHAIN_MIN_JUMPS && dissipateNoShip - ssChainNoShipBaseline >= 1 &&
                    flash - ssChainFlashBaseline >= SS_CHAIN_MAX_JUMPS
                ) {
                    val wallSeconds = (System.nanoTime() - ssChainFpsWallStartNanos) / 1_000_000_000.0
                    ssChainFps = if (wallSeconds > 0.0) (ssChainFpsTicks / wallSeconds).toFloat() else -1f
                    when {
                        chainJumpsMax > SS_CHAIN_MAX_JUMPS -> {
                            ctx.failureReason = "ss chain jumps max=$chainJumpsMax > $SS_CHAIN_MAX_JUMPS（7 跳硬上限被突破）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        kills < SS_CHAIN_MIN_KILLS -> {
                            ctx.failureReason = "ss chain kills=$kills < $SS_CHAIN_MIN_KILLS（连跳成片清除证据不足）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        rift < chainJumpsMax -> {
                            ctx.failureReason = "ss rift=$rift < chainJumpsMax=$chainJumpsMax（每跳一次裂隙爆炸）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        teleportArc < 1 -> {
                            ctx.failureReason = "ss teleport arc=$teleportArc, expect>=1（折跃起止 EMP 电弧）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        ssChainFps < SS_CHAIN_MIN_FPS -> {
                            ctx.failureReason = "ss chain fps=$ssChainFps < $SS_CHAIN_MIN_FPS（连跳峰值帧率门槛）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        else -> {
                            ssTerminalSingleBaseline = terminalSingle
                            ssTerminalEmpArcsBaseline = terminalEmpArcs
                            transitionSsPhase(SS_PHASE_TERMINAL)
                        }
                    }
                }
            }

            SS_PHASE_TERMINAL -> {
                // 盾折叠闸（实机判例：靶舰 B 部署时 OMNI 盾处于开启态，toggleOff 后仍有 ~1s 折叠
                // 窗口继续挡伤——窗口内终结单段 125 被盾面全额吸收，「命中掉血」观测面拿到 HP 满值
                // 误判失败）——盾确认关闭且折叠完毕（activeArc 归零）后才放行开火。
                val shieldFolded = target?.shield?.let { !it.isOn && it.activeArc <= 0f } != false
                // 在飞链沉降闸（实机判例第 2 轮：CHAIN 相位末发连跳在 B 舰部署同帧「无处可去」转终结，
                // 盾折叠闸只拦新开火、拦不住已在飞的链脚本——其终结单段打在未折叠盾面上
                // 全额吸收，terminalSingle 基线已过、HP 满值误判失败）——盾未折叠完毕期间
                // 逐帧重定基线，把 stale 终结段吞进基线；盾折叠后的终结段必落船体，皆有效证据。
                val settled = ctx.elapsed - ssPhaseStartedAt >= SS_TERMINAL_SETTLE_SECONDS
                stabilizeSsShips(engine, fire = shieldFolded && settled)
                if (!shieldFolded || !settled) {
                    ssTerminalSingleBaseline = terminalSingle
                    ssTerminalEmpArcsBaseline = terminalEmpArcs
                }
                if (shieldFolded && settled && terminalSingle - ssTerminalSingleBaseline >= 1) {
                    val targetDamaged = target != null && target.hitpoints < target.maxHitpoints - SS_TERMINAL_HP_DROP_MIN
                    when {
                        terminalEmpArcs - ssTerminalEmpArcsBaseline != 0 -> {
                            ctx.failureReason = "ss terminal emp arcs delta=${terminalEmpArcs - ssTerminalEmpArcsBaseline}, expect 0（玩家单段终结无 EMP）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        targetDamaged -> {
                            ssEnemyMultiBaseline = terminalMulti
                            ssEnemyMinPlayerHp = player?.maxHitpoints ?: Float.MAX_VALUE
                            DifficultyTuningImpl.installScaleForTests(5f)
                            transitionSsPhase(SS_PHASE_ENEMY_MULTI)
                        }
                        // 部署免疫宽限（实机判例第 8 轮：spawn 免疫窗口非固定时长——同相位同 4.0s
                        // 时刻第 7 轮掉血、第 8 轮满血，随后 ~11.7s 正常掉血）——首发终结未掉血
                        // 不立即判负，武器保持 force fire（2s/发连发），宽限期内任一段掉血即通过。
                        ctx.elapsed - ssPhaseStartedAt > SS_TERMINAL_GRACE_SECONDS -> {
                            ctx.failureReason = "ss terminal target hp=${target?.hitpoints}/${target?.maxHitpoints}（单段 50% 终结应命中掉血）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                    }
                }
            }

            SS_PHASE_ENEMY_MULTI -> {
                stabilizeSsShips(engine, fire = false)
                feedSsMissiles(engine, atPlayerSide = false)
                if (player != null && !player.isHulk) {
                    ssEnemyMinPlayerHp = minOf(ssEnemyMinPlayerHp, player.hitpoints)
                }
                // 入场闸加逐段电弧计数（实机判例：enterTerminal 入口即 bump multi，段间隔 0.12s
                // 尚未引爆任何一段，按入口判证据会拿到 empArcs=0 误判失败）。
                if (terminalMulti - ssEnemyMultiBaseline >= 1 && terminalEmpArcs >= SS_ENEMY_MULTI_MIN_SEGMENTS) {
                    when {
                        terminalSegmentsMax < SS_ENEMY_MULTI_MIN_SEGMENTS -> {
                            ctx.failureReason = "ss enemy terminal segments max=$terminalSegmentsMax < $SS_ENEMY_MULTI_MIN_SEGMENTS（破晓多段终结段数不足）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        terminalEmpArcs < SS_ENEMY_MULTI_MIN_SEGMENTS -> {
                            ctx.failureReason = "ss enemy terminal emp arcs=$terminalEmpArcs < $SS_ENEMY_MULTI_MIN_SEGMENTS（多段终结逐段 EMP 电弧）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }

                        player != null && ssEnemyMinPlayerHp < player.maxHitpoints - SS_TERMINAL_HP_DROP_MIN -> {
                            DifficultyTuningImpl.installScaleForTests(null)
                            findSsEnemyCarrier(engine)?.let { engine.removeEntity(it) }
                            player.hitpoints = player.maxHitpoints
                            transitionSsPhase(SS_PHASE_COMPLETED)
                        }
                        // 部署免疫宽限（同 SS_PHASE_TERMINAL 注：source 为敌版舰时其脚本伤害在
                        // 部署后数秒内可能全额无效，窗口非固定时长）——2s/发连发，宽限期内
                        // 任一段掉血即通过。
                        ctx.elapsed - ssPhaseStartedAt > SS_ENEMY_MULTI_GRACE_SECONDS -> {
                            ctx.failureReason = "ss enemy multi player minHp=$ssEnemyMinPlayerHp（多段终结应命中玩家舰掉血）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                    }
                }
            }

            SS_PHASE_COMPLETED -> {
                stabilizeSsShips(engine, fire = true)
                feedSsMissiles(engine, atPlayerSide = true)
            }
        }

        // 最近一次裂隙爆炸时刻（COMPLETED 截图门控：裂隙近期发生才上报，令裂隙特效入帧）
        if (rift > ssLastTrackedFlashCount) {
            ssLastTrackedFlashCount = rift
            ssLastFlashAt = ctx.elapsed
        }

        val state = when {
            player == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "ss player ship missing"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            ssPhase == SS_PHASE_FAILED -> "Failed"
            ssPhase != SS_PHASE_COMPLETED &&
                    ctx.elapsed - ssPhaseStartedAt > SS_PHASE_TIMEOUT -> {
                ctx.failureReason = "ss phase timeout: $ssPhase"
                "Failed"
            }

            ssPhase == SS_PHASE_COMPLETED -> {
                val recentFlash = ssLastFlashAt >= 0f && ctx.elapsed - ssLastFlashAt <= SS_COMPLETED_FLASH_WINDOW
                if (recentFlash || ctx.elapsed - ssPhaseStartedAt >= SS_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: seven_stars_basic break/chain/terminal/enemy-multi evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, weapon)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val ssPlayer = findSsPlayer(engine)
        val ssTarget = findSsTarget(engine)
        val ssCarrier = findSsEnemyCarrier(engine)
        val ssWeapon = findSsWeapon(ssPlayer)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 07 §4.2 烟测检查点）----
        json.appendLine("  \"ssPhase\": \"$ssPhase\",")
        json.appendLine("  \"ssSlotId\": ${jsonString(ssWeapon?.slot?.id)},")
        json.appendLine("  \"ssWeaponRange\": ${formatFloat(ssWeapon?.range ?: -1f)},")
        json.appendLine("  \"ssHintsPd\": ${ssWeapon?.spec?.aiHints?.contains(WeaponAPI.AIHints.PD) == true},")
        json.appendLine("  \"ssTargetHitpoints\": ${formatFloat(ssTarget?.hitpoints ?: -1f)},")
        json.appendLine("  \"ssTargetMaxHitpoints\": ${formatFloat(ssTarget?.maxHitpoints ?: -1f)},")
        json.appendLine("  \"ssPlayerHitpoints\": ${formatFloat(ssPlayer?.hitpoints ?: -1f)},")
        json.appendLine("  \"ssPlayerMaxHitpoints\": ${formatFloat(ssPlayer?.maxHitpoints ?: -1f)},")
        json.appendLine("  \"ssEnemyMinPlayerHp\": ${formatFloat(ssEnemyMinPlayerHp)},")
        json.appendLine("  \"ssEnemyCarrierPresent\": ${ssCarrier != null},")
        json.appendLine("  \"ssOnfire\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_ONFIRE)},")
        json.appendLine("  \"ssFlash\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_FLASH)},")
        json.appendLine("  \"ssRift\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_RIFT)},")
        json.appendLine("  \"ssTeleportArc\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TELEPORT_ARC)},")
        json.appendLine("  \"ssKills\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_KILLS)},")
        json.appendLine("  \"ssChainJumpsMax\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_CHAIN_JUMPS_MAX)},")
        json.appendLine(
            "  \"ssDissipateNoShip\": ${
                SevenStarsChainScript.telemetryCount(
                    engine,
                    SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_SHIP
                )
            },"
        )
        json.appendLine(
            "  \"ssTerminalSingle\": ${
                SevenStarsChainScript.telemetryCount(
                    engine,
                    SevenStarsChainScript.TELEMETRY_TERMINAL_SINGLE
                )
            },"
        )
        json.appendLine("  \"ssTerminalMulti\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_MULTI)},")
        json.appendLine(
            "  \"ssTerminalSegmentsMax\": ${
                SevenStarsChainScript.telemetryCount(
                    engine,
                    SevenStarsChainScript.TELEMETRY_TERMINAL_SEGMENTS_MAX
                )
            },"
        )
        json.appendLine(
            "  \"ssTerminalEmpArcs\": ${
                SevenStarsChainScript.telemetryCount(
                    engine,
                    SevenStarsChainScript.TELEMETRY_TERMINAL_EMP_ARCS
                )
            },"
        )
        json.appendLine("  \"ssChainFps\": ${formatFloat(ssChainFps)},")
        json.appendLine("  \"ssEnemyMissilesInPlay\": ${engine.missiles.count { it.owner != 0 }},")
    }

    private companion object {
        // “七星”折跃发射器场景：相位机、锚点与期望证据（规格 07 §4.2 烟测检查点）。
        private const val SS_PHASE_MOUNT = "MOUNT"
        private const val SS_PHASE_NOKILL = "NOKILL"
        private const val SS_PHASE_CHAIN = "CHAIN"
        private const val SS_PHASE_TERMINAL = "TERMINAL"
        private const val SS_PHASE_ENEMY_MULTI = "ENEMY_MULTI"
        private const val SS_PHASE_COMPLETED = "COMPLETED"
        private const val SS_PHASE_FAILED = "FAILED"
        private const val SS_PLAYER_HULL = "odyssey"
        private const val SS_TARGET_HULL = "vigilance"
        private const val SS_PLAYER_SLOT = "WS 001"
        private val SS_PLAYER_ANCHOR = Vector2f(0f, 0f)

        // 靶舰锚点（600su 弹道上；TERMINAL 相位对舰终结观测位）。
        private val SS_TARGET_ANCHOR = Vector2f(600f, 0f)
        private val SS_ENEMY_ANCHOR = Vector2f(1000f, 0f)
        private val SS_CAMERA_CENTER = Vector2f(500f, 0f)
        private const val SS_CAMERA_VISIBLE_HEIGHT = 1300f
        private const val SS_MOUNT_SETTLE_SECONDS = 0.6f

        // MOUNT 相位校验：射程断言基线 800（无射程向 hullmod 干扰）。
        private const val SS_EXPECT_RANGE = 800f
        private const val SS_RANGE_TOLERANCE = 5f

        // NOKILL：增压鱼叉 HP（裂隙爆炸不可摧毁，固定 7 跳零击杀证据面；环形投喂复用通用环位常量）。
        private const val SS_NOKILL_MISSILE_HP = 1_000_000f

        // CHAIN：连跳证据下限/上限（7 跳硬上限断言）；成片清除与帧率门槛。
        private const val SS_CHAIN_MIN_JUMPS = 3
        private const val SS_CHAIN_MAX_JUMPS = 7
        private const val SS_CHAIN_MIN_KILLS = 3
        private const val SS_CHAIN_MIN_FPS = 30f

        // TERMINAL：靶舰掉血下限（单段 50% = 125 能量 vs 装甲减免后实机 ~9 船体，门槛按可见掉血定）。
        private const val SS_TERMINAL_HP_DROP_MIN = 5f

        // TERMINAL：在飞链沉降窗口（秒）——相位入场后该窗口内不放行开火且逐帧重定终结基线，
        // 吞掉 CHAIN 末发 stale 链脚本打在未折叠盾面上的终结段（见 SS_PHASE_TERMINAL 注）。
        // 取 4s 的另一重原因（实机判例第 6 轮）：reserves 手动 spawn 的舰船部署后约 2~3s 内
        // applyDamage 全额无效（部署后 1.3s 舰心+bypass 同点 0 伤害、3.3s 同点正常掉血），
        // 窗口须覆盖该免疫期，否则终结证据必然拿到 HP 满值。
        private const val SS_TERMINAL_SETTLE_SECONDS = 4.0f

        // ENEMY_MULTI：敌版舰部署免疫窗口（秒，同 SS_TERMINAL_SETTLE_SECONDS 实机判例）。
        private const val SS_ENEMY_MULTI_SETTLE_SECONDS = 4.0f

        // 部署免疫宽限（秒）：免疫窗口非固定时长（实机判例第 8 轮同 4.0s 时刻两轮结果相反），
        // 首发终结未掉血不立即判负，2s/发连发在宽限期内补段；远小于相位超时 90s。
        private const val SS_TERMINAL_GRACE_SECONDS = 15f
        private const val SS_ENEMY_MULTI_GRACE_SECONDS = 15f

        // ENEMY_MULTI：破晓敌版多段终结段数下限（连跳 ≥2 跳 → segments = jumps ≥ 2）。
        private const val SS_ENEMY_MULTI_MIN_SEGMENTS = 2

        // 导弹投喂：鱼叉（vanilla MRM）；CHAIN/ENEMY_MULTI 环形稠密投喂（见 feedSsMissiles 文档）。
        private const val SS_FEED_MISSILE_ID = "harpoon"
        private const val SS_MISSILE_FEED_INTERVAL = 0.15f
        private const val SS_ENEMY_FEED_INTERVAL = 0.15f

        // 投喂环：环心距锚点 280su、环半径 100su（任意两弹间距 <=200su < 400su 跳程）、低速 40su/s 堆积。
        private const val SS_MISSILE_RING_OFFSET = 280f
        private const val SS_MISSILE_RING_RADIUS = 100f
        private const val SS_MISSILE_RING_SPEED = 40f
        private const val SS_MISSILE_INITIAL_SPEED = 250f

        // COMPLETED 截图门控：裂隙爆炸近 0.6s 内发生才上报（特效入帧）；保底舞台超时。
        private const val SS_COMPLETED_FLASH_WINDOW = 0.6f
        private const val SS_COMPLETED_STAGE_TIMEOUT = 25f
        private const val SS_PHASE_TIMEOUT = 90f
    }
}
