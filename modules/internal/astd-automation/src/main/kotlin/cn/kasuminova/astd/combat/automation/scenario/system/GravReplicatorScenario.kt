package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.GSR_FOLD_MARK_KEY
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.hullmods.lens.GravSpaceFoldTuning
import cn.kasuminova.astd.combat.lens.system.GravReplicatorTuning
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 舜华引力空间复制器/折跃器场景（断言点 GSR-A~GSR-F）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class GravReplicatorScenario : AbstractAutomationScenario() {
    // ==== 舜华引力空间复制器/折跃器场景状态（相位机 SPAWN → ACTIVATE → OBSERVE_COOLDOWN → FOLD_FEED → COMPLETED） ====
    private var gsrPhase = GSR_PHASE_SPAWN
    private var gsrPhaseStartedAt = 0f

    // SPAWN（断言点 GSR-A）：系统非冷却期光束承伤乘区峰值（v2 ×0.75）。
    private var gsrBeamMultIdleMax = 0f

    // ACTIVATE（断言点 GSR-B/C）：激活软辐能峰值增量、原发/复制弹分类计数与逐帧辐能尖峰清单。
    private var gsrActivateAttempts = 0
    private var gsrActivatedAt = -1f
    private var gsrActivationFluxDeltaMax = 0f
    private var gsrFired = false
    private var gsrOrigDamage = -1f
    private var gsrOrigFacing = -1f
    private var gsrOriginalShots = 0
    private var gsrReplicaShots = 0
    private var gsrReplicaDamageMax = 0f
    private var gsrReplicaFacingMaxDelta = 0f
    private var gsrPrevFlux = -1f
    private val gsrFluxSpikes = mutableListOf<Float>()
    private val gsrSeenOwnProjectiles = mutableSetOf<Int>()

    // OBSERVE_COOLDOWN（断言点 GSR-D/E）：冷却期光束乘区复原与投喂弹体零判定标记（identityHash → 最小接近距离）。
    private var gsrCooldownBeamMultMax = 0f
    private var gsrCooldownFedAt = -1f
    private var gsrCooldownFedCount = 0
    private var gsrCooldownMarked = 0
    private val gsrCooldownFeedDist = mutableMapOf<Int, Float>()

    // FOLD_FEED（断言点 GSR-F）：折跃恢复后三态标记统计与镜像离场验证。
    private var gsrFoldFedAt = -1f
    private var gsrFoldFedCount = 0
    private var gsrFoldedCount = 0
    private var gsrNoFoldCount = 0
    private var gsrFoldedMovingAway = 0
    private val gsrFoldFeedIds = mutableSetOf<Int>()
    private val gsrFoldFeedMarked = mutableSetOf<Int>()

    override val scenarioId: String = ASTDInGameAutomationScenario.GSR_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isGravReplicatorScenarioEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockGsrCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findGsrPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.GSR_SCENARIO_ID} combat plugin initialized")
    }

    private fun findGsrPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == GSR_PLAYER_HULL && !ship.isFighter }

    private fun findGsrEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == GSR_ENEMY_HULL && !ship.isFighter }

    /** 强制部署 mission reserves（范式同 deployGrgReserveShips；敌靶舰钉远场，仅作投喂弹体的敌对 source）。 */
    private fun deployGsrReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == GSR_PLAYER_HULL -> GSR_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == GSR_ENEMY_HULL -> GSR_ENEMY_ANCHOR
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

    private fun transitionGsrPhase(next: String) {
        ctx.log.info("[ASTD-Automation] gsr phase $gsrPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        gsrPhase = next
        gsrPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeGsShips）：双方逐帧钉死锚点 + 舰 AI 置空。
     * 玩家舰逐帧封锁相位斗篷（zw_101 为相位驱逐舰：进相位折跃停判，本场景观测面不含相位路径）
     * 并压盾兜底；[playerFire] 仅在 ACTIVATE 相位强制单发脉冲激光的那帧放开开火闸
     * （isHoldFireOneFrame 会吞 setForceFireOneFrame，范式同 xc_001 默认场景先清 holdFire 再强火）。
     */
    private fun stabilizeGsrShips(engine: CombatEngineAPI, playerFire: Boolean, zeroPlayerFlux: Boolean, blockSystem: Boolean) {
        val player = findGsrPlayer(engine)
        val enemy = findGsrEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            // 保留舰 AI（preserveAI=true）：实机验证 setForceFireOneFrame 对无舰 AI 的舞台舰不生效
            // （范式同电荷针刺重型直控开火路径）；移动/相位/系统由钉位与命令封锁兜底。
            stabilizeShip(player, GSR_PLAYER_ANCHOR, 0f, allowFire = playerFire, preserveAI = true)
            player.hitpoints = player.maxHitpoints
            if (zeroPlayerFlux) {
                player.fluxTracker.currFlux = 0f
                player.fluxTracker.hardFlux = 0f
            }
            if (blockSystem) player.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
            player.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
            player.shield?.let { if (it.isOn) it.toggleOff() }
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, GSR_ENEMY_ANCHOR, 180f, allowFire = false, preserveAI = false)
            enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
            enemy.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
            enemy.shield?.let { if (it.isOn) it.toggleOff() }
        }
    }

    private fun lockGsrCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, GSR_CAMERA_CENTER, GSR_CAMERA_VISIBLE_HEIGHT)
    }

    /**
     * 投喂一发折跃判定弹（范式同 feedGhostSignalMissiles：weapon=null + weaponId 直生成，
     * source=敌靶舰保证 owner 敌对）：靶舰东侧 GSR_FEED_SPAWN_DIST 起垂直舰心线西射，
     * 纵向散布保证全部穿过折跃判定圈（碰撞圈 +200su）。返回弹体 identityHash；spawn 失败
     * 走 FAILED 上报（Fail Fast，同幽灵信号投喂口径）。
     */
    private fun gsrFeedShot(engine: CombatEngineAPI, player: ShipAPI, enemy: ShipAPI): Int? {
        val spread = MathUtils.getRandomNumberInRange(-GSR_FEED_SPREAD, GSR_FEED_SPREAD)
        val spawn = Vector2f(player.location.x + GSR_FEED_SPAWN_DIST, player.location.y + spread)
        val spawned = engine.spawnProjectile(enemy, null, GSR_FEED_WEAPON_ID, spawn, 180f, Vector2f())
        if (spawned == null) {
            ctx.failureReason = "gsr feed: spawnProjectile($GSR_FEED_WEAPON_ID) 返回 null（weaponId 不可用）"
            transitionGsrPhase(GSR_PHASE_FAILED)
            return null
        }
        return System.identityHashCode(spawned)
    }

    /** 本舰实弹分类统计（断言点 GSR-C 观测面）：首见弹按伤害口径分类——复制体出生即原弹 ×0.5，严格分流。 */
    private fun trackGsrOwnProjectiles(engine: CombatEngineAPI, player: ShipAPI) {
        for (proj in engine.projectiles) {
            if (proj.source !== player || proj is MissileAPI) continue
            val damaging = proj as? DamagingProjectileAPI ?: continue
            val key = System.identityHashCode(proj)
            if (!gsrSeenOwnProjectiles.add(key)) continue
            val damage = damaging.damageAmount
            if (gsrOrigDamage < 0f || damage > gsrOrigDamage * 0.7f) {
                if (gsrOrigDamage < 0f) {
                    gsrOrigDamage = damage
                    gsrOrigFacing = damaging.facing
                }
                gsrOriginalShots++
            } else {
                gsrReplicaShots++
                gsrReplicaDamageMax = maxOf(gsrReplicaDamageMax, damage)
                // 收敛射向证据：复制弹朝向与原发弹朝向的角差（环带随机出生点 → 指向同一终点
                // 必然与原射向有夹角；退化为 0 即复制弹与原弹平行，是收敛失效的直接特征）。
                // 闸门用 gsrOrigDamage 而非 gsrOrigFacing：朝向合法域含负值，不能用负数当未初始化哨兵。
                if (gsrOrigDamage >= 0f) {
                    gsrReplicaFacingMaxDelta = maxOf(
                        gsrReplicaFacingMaxDelta,
                        Math.abs(Misc.getAngleDiff(damaging.facing, gsrOrigFacing)),
                    )
                }
            }
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployGsrReserveShips(engine)
        lockGsrCamera(engine)

        val player = findGsrPlayer(engine)
        val enemy = findGsrEnemy(engine)
        val system = player?.system

        when (gsrPhase) {
            GSR_PHASE_SPAWN -> {
                stabilizeGsrShips(engine, playerFire = false, zeroPlayerFlux = true, blockSystem = true)
                if (player != null && !player.isHulk) {
                    gsrBeamMultIdleMax = maxOf(gsrBeamMultIdleMax, player.mutableStats.beamDamageTakenMult.modifiedValue)
                }
                if (player != null && enemy != null && ctx.elapsed - gsrPhaseStartedAt >= GSR_SPAWN_SETTLE_SECONDS) {
                    if (gsrBeamMultIdleMax !in GSR_BEAM_MULT_IDLE_MIN..GSR_BEAM_MULT_IDLE_MAX) {
                        ctx.failureReason = "gsr beam mult idle=${"%.3f".format(gsrBeamMultIdleMax)}" +
                                " ∉ [$GSR_BEAM_MULT_IDLE_MIN, $GSR_BEAM_MULT_IDLE_MAX]（断言点 GSR-A：非冷却期光束承伤 ×0.75）"
                        transitionGsrPhase(GSR_PHASE_FAILED)
                    } else {
                        ctx.log.info(
                            "[ASTD-Automation] gsr beam dr evidence: idle beamDamageTakenMult=${"%.3f".format(gsrBeamMultIdleMax)}（断言点 GSR-A）",
                        )
                        transitionGsrPhase(GSR_PHASE_ACTIVATE)
                    }
                }
            }

            GSR_PHASE_ACTIVATE -> {
                // 开火窗口：激活 +0.3s 起逐帧强火直至首见原发弹（脉冲激光 1s 射速，窗口内恰一发）。
                // setForceFireOneFrame 必须逐帧调用且舰 AI 在场（无 AI 舞台舰不生效，针刺场景实机验证）；
                // 不得 setRemainingCooldownTo(0f)——逐帧重置会把开火周期反复归零导致零弹体。
                val inFireWindow = gsrActivatedAt >= 0f && gsrOriginalShots < 1 &&
                        ctx.elapsed - gsrActivatedAt >= GSR_FIRE_DELAY_SECONDS
                // 辐能清零闸只认原版状态机：IDLE 才清零。激活代价在首个 apply 帧计入
                // （chargeUp=0 无 IN 帧），按 gsrActivatedAt 闸会在点亮帧把代价同步清零抹掉。
                stabilizeGsrShips(
                    engine, playerFire = inFireWindow,
                    zeroPlayerFlux = system?.state == null || system.state == ShipSystemAPI.SystemState.IDLE,
                    blockSystem = false,
                )
                if (player != null && enemy != null && system != null) {
                    if (system.id != GSR_SYSTEM_ID) {
                        ctx.failureReason = "gsr system id=${system.id}, expect $GSR_SYSTEM_ID（ship_data.csv 生成物未刷新）"
                        transitionGsrPhase(GSR_PHASE_FAILED)
                    } else {
                        val replicaWeapon = player.allWeapons.firstOrNull { it.id == GSR_REPLICA_WEAPON_ID }
                        if (replicaWeapon == null) {
                            ctx.failureReason = "gsr weapon missing: 脉冲激光 $GSR_REPLICA_WEAPON_ID 未装配（MissionDefinition 接线缺失）"
                            transitionGsrPhase(GSR_PHASE_FAILED)
                        } else {
                            if (system.isOn) {
                                if (gsrActivatedAt < 0f) {
                                    gsrActivatedAt = ctx.elapsed
                                    gsrPrevFlux = player.fluxTracker.currFlux
                                    ctx.log.info(
                                        "[ASTD-Automation] gsr activated: state=${system.state} attempts=$gsrActivateAttempts " +
                                                "currFlux=${"%.0f".format(player.fluxTracker.currFlux)}",
                                    )
                                }
                                if (inFireWindow) {
                                    if (!gsrFired) {
                                        gsrFired = true
                                        ctx.log.info("[ASTD-Automation] gsr pulse laser force-fire window open at ${"%.2f".format(ctx.elapsed)}s")
                                    }
                                    replicaWeapon.setForceFireOneFrame(true)
                                }
                            } else if (system.state != ShipSystemAPI.SystemState.COOLDOWN && system.cooldownRemaining <= 0f) {
                                // 按帧重试 useSystem()（单次调用可能被原版闸门吞掉，范式同 FGL ACTIVATE）。
                                gsrActivateAttempts++
                                player.useSystem()
                            }
                            if (gsrActivatedAt >= 0f) {
                                // 断言点 GSR-B/C 观测面：激活软辐能峰值增量只在开火前采样
                                // （开火/复制尖峰会叠加进 currFlux，污染激活代价归因）；
                                // 原发/复制弹分类计数、逐帧辐能尖峰（开火 ≈1×f / 复制 ≈0.5×f）。
                                if (!gsrFired) {
                                    gsrActivationFluxDeltaMax = maxOf(gsrActivationFluxDeltaMax, player.fluxTracker.currFlux)
                                }
                                trackGsrOwnProjectiles(engine, player)
                                val currFlux = player.fluxTracker.currFlux
                                val delta = currFlux - gsrPrevFlux
                                if (gsrPrevFlux >= 0f && delta >= GSR_SPIKE_MIN_DELTA) gsrFluxSpikes += delta
                                gsrPrevFlux = currFlux
                            }
                            // 结算宽限：ACTIVE 2s 转 COOLDOWN 后多等 0.5s——晚发的原发弹复制调度
                            // （发射 +0.5s/+1.0s，由舰船 listener 队列推进，不随系统关闭取消）可能压线落地。
                            if (system.state == ShipSystemAPI.SystemState.COOLDOWN &&
                                ctx.elapsed - gsrActivatedAt >= GSR_EVAL_GRACE_SECONDS
                            ) {
                                val capacity = player.mutableStats.fluxCapacity.baseValue
                                val expectedActivation = capacity * GravReplicatorTuning.ACTIVATION_FLUX_FRACTION
                                val fluxPerShot = replicaWeapon.fluxCostToFire
                                val halfSpikes = gsrFluxSpikes.count {
                                    it >= fluxPerShot * GSR_SPIKE_HALF_MIN && it <= fluxPerShot * GSR_SPIKE_HALF_MAX
                                }
                                val fullSpikes = gsrFluxSpikes.count {
                                    it >= fluxPerShot * GSR_SPIKE_FULL_MIN && it <= fluxPerShot * GSR_SPIKE_FULL_MAX
                                }
                                when {
                                    gsrActivationFluxDeltaMax < expectedActivation * (1f - GSR_ACTIVATION_FLUX_TOLERANCE) ||
                                            gsrActivationFluxDeltaMax > expectedActivation * (1f + GSR_ACTIVATION_FLUX_TOLERANCE) -> {
                                        ctx.failureReason = "gsr activation flux delta=${"%.0f".format(gsrActivationFluxDeltaMax)}" +
                                                "，expect ${"%.0f".format(expectedActivation)} ±${(GSR_ACTIVATION_FLUX_TOLERANCE * 100).toInt()}%" +
                                                "（断言点 GSR-B：基础容量 ×10% 软辐能）"
                                        transitionGsrPhase(GSR_PHASE_FAILED)
                                    }

                                    gsrOriginalShots < 1 -> {
                                        ctx.failureReason = "gsr no original shot: 激活期未观测到脉冲激光原发弹（断言点 GSR-C 无法观测）"
                                        transitionGsrPhase(GSR_PHASE_FAILED)
                                    }

                                    gsrReplicaShots != gsrOriginalShots * GravReplicatorTuning.COPY_COUNT -> {
                                        ctx.failureReason = "gsr replica count=$gsrReplicaShots，expect 原发 $gsrOriginalShots × " +
                                                "${GravReplicatorTuning.COPY_COUNT}（断言点 GSR-C：0.5s/1.0s 各复制 1 发）"
                                        transitionGsrPhase(GSR_PHASE_FAILED)
                                    }

                                    gsrReplicaDamageMax < gsrOrigDamage * GSR_REPLICA_DAMAGE_RATIO_MIN ||
                                            gsrReplicaDamageMax > gsrOrigDamage * GSR_REPLICA_DAMAGE_RATIO_MAX -> {
                                        ctx.failureReason = "gsr replica damage=${"%.1f".format(gsrReplicaDamageMax)}" +
                                                " / orig=${"%.1f".format(gsrOrigDamage)} ∉ [$GSR_REPLICA_DAMAGE_RATIO_MIN, $GSR_REPLICA_DAMAGE_RATIO_MAX]" +
                                                "（断言点 GSR-C：复制体伤害 ×0.5）"
                                        transitionGsrPhase(GSR_PHASE_FAILED)
                                    }

                                    gsrReplicaFacingMaxDelta < GSR_REPLICA_CONVERGE_MIN_DELTA -> {
                                        ctx.failureReason = "gsr replica facing delta=${"%.2f".format(gsrReplicaFacingMaxDelta)}°" +
                                                " < $GSR_REPLICA_CONVERGE_MIN_DELTA°（origFacing=${"%.2f".format(gsrOrigFacing)}）" +
                                                "（断言点 GSR-C：复制弹从环带出生点收敛到主射弹终点，射向必须与原射向有夹角）"
                                        transitionGsrPhase(GSR_PHASE_FAILED)
                                    }

                                    fullSpikes < 1 || halfSpikes < GravReplicatorTuning.COPY_COUNT -> {
                                        ctx.failureReason = "gsr flux spikes: full=$fullSpikes（≥1）half=$halfSpikes（≥${GravReplicatorTuning.COPY_COUNT}）" +
                                                " fluxPerShot=${"%.0f".format(fluxPerShot)} spikes=${gsrFluxSpikes.map { "%.0f".format(it) }}" +
                                                "（断言点 GSR-C：每发复制附加 单发辐能 ×0.5 软辐能）"
                                        transitionGsrPhase(GSR_PHASE_FAILED)
                                    }

                                    else -> {
                                        ctx.log.info(
                                            "[ASTD-Automation] gsr replica evidence: fluxDeltaMax=${"%.0f".format(gsrActivationFluxDeltaMax)} " +
                                                    "originals=$gsrOriginalShots replicas=$gsrReplicaShots " +
                                                    "replicaDamage=${"%.1f".format(gsrReplicaDamageMax)}/${"%.1f".format(gsrOrigDamage)} " +
                                                    "spikes(full=$fullSpikes half=$halfSpikes)（断言点 GSR-B/C）",
                                        )
                                        transitionGsrPhase(GSR_PHASE_OBSERVE_COOLDOWN)
                                    }
                                }
                            } else if (ctx.elapsed - gsrPhaseStartedAt >= GSR_ACTIVATE_TIMEOUT) {
                                ctx.failureReason = "gsr activate timeout: ${GSR_ACTIVATE_TIMEOUT.toInt()}s 内系统未走完激活周期" +
                                        "（attempts=$gsrActivateAttempts state=${system.state} fired=$gsrFired " +
                                        "originals=$gsrOriginalShots replicas=$gsrReplicaShots）"
                                transitionGsrPhase(GSR_PHASE_FAILED)
                            }
                        }
                    }
                }
            }

            GSR_PHASE_OBSERVE_COOLDOWN -> {
                stabilizeGsrShips(engine, playerFire = false, zeroPlayerFlux = false, blockSystem = true)
                if (player != null && enemy != null && system != null) {
                    gsrCooldownBeamMultMax = maxOf(gsrCooldownBeamMultMax, player.mutableStats.beamDamageTakenMult.modifiedValue)
                    if (system.state == ShipSystemAPI.SystemState.COOLDOWN &&
                        gsrCooldownFedCount < GSR_COOLDOWN_FEED_COUNT &&
                        (gsrCooldownFedAt < 0f || ctx.elapsed - gsrCooldownFedAt >= GSR_COOLDOWN_FEED_INTERVAL)
                    ) {
                        val id = gsrFeedShot(engine, player, enemy)
                        if (id != null) {
                            gsrCooldownFedAt = ctx.elapsed
                            gsrCooldownFedCount++
                            gsrCooldownFeedDist[id] = Float.MAX_VALUE
                        }
                    }
                    // 投喂弹跟踪：最小接近距离（穿圈判据）；冷却期出现任何判定标记立即判失败
                    // （断言点 GSR-E：系统 COOLDOWN 折跃停判）。
                    for (proj in engine.projectiles) {
                        val id = System.identityHashCode(proj)
                        val minDist = gsrCooldownFeedDist[id] ?: continue
                        val dist = Misc.getDistance(player.location, proj.location)
                        if (dist < minDist) gsrCooldownFeedDist[id] = dist
                        val mark = proj.customData[GSR_FOLD_MARK_KEY] as? String
                        if (mark != null) {
                            gsrCooldownMarked++
                            ctx.failureReason = "gsr cooldown fold mark: 冷却期投喂弹体被判定标记（mark=$mark，断言点 GSR-E：折跃停判）"
                            transitionGsrPhase(GSR_PHASE_FAILED)
                            break
                        }
                    }
                    if (gsrPhase == GSR_PHASE_OBSERVE_COOLDOWN && system.state != ShipSystemAPI.SystemState.COOLDOWN) {
                        val entered = gsrCooldownFeedDist.values.count { it <= GSR_FOLD_ENTER_DIST }
                        when {
                            gsrCooldownBeamMultMax !in GSR_COOLDOWN_BEAM_MULT_MIN..GSR_COOLDOWN_BEAM_MULT_MAX -> {
                                ctx.failureReason = "gsr cooldown beam mult=${"%.3f".format(gsrCooldownBeamMultMax)}" +
                                        " ∉ [$GSR_COOLDOWN_BEAM_MULT_MIN, $GSR_COOLDOWN_BEAM_MULT_MAX]（断言点 GSR-D：冷却期光束减免复原 1.0）"
                                transitionGsrPhase(GSR_PHASE_FAILED)
                            }

                            entered < GSR_COOLDOWN_MIN_ENTERED -> {
                                ctx.failureReason = "gsr cooldown feed entered=$entered < $GSR_COOLDOWN_MIN_ENTERED" +
                                        "（fed=$gsrCooldownFedCount，断言点 GSR-E 观测面不成立：投喂弹未穿圈）"
                                transitionGsrPhase(GSR_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] gsr cooldown evidence: beamMultMax=${"%.3f".format(gsrCooldownBeamMultMax)} " +
                                            "fed=$gsrCooldownFedCount entered=$entered marked=$gsrCooldownMarked（断言点 GSR-D/E：冷却期减免复原+折跃停判）",
                                )
                                transitionGsrPhase(GSR_PHASE_FOLD_FEED)
                            }
                        }
                    } else if (gsrPhase == GSR_PHASE_OBSERVE_COOLDOWN && ctx.elapsed - gsrPhaseStartedAt >= GSR_COOLDOWN_TIMEOUT) {
                        ctx.failureReason = "gsr cooldown timeout: ${GSR_COOLDOWN_TIMEOUT.toInt()}s 内冷却未结束" +
                                "（state=${system.state} cd=${"%.1f".format(system.cooldownRemaining)} fed=$gsrCooldownFedCount）"
                        transitionGsrPhase(GSR_PHASE_FAILED)
                    }
                }
            }

            GSR_PHASE_FOLD_FEED -> {
                stabilizeGsrShips(engine, playerFire = false, zeroPlayerFlux = false, blockSystem = true)
                if (player != null && enemy != null && system != null) {
                    if (gsrFoldFedCount < GSR_FOLD_FEED_COUNT &&
                        (gsrFoldFedAt < 0f || ctx.elapsed - gsrFoldFedAt >= GSR_FOLD_FEED_INTERVAL)
                    ) {
                        val id = gsrFeedShot(engine, player, enemy)
                        if (id != null) {
                            gsrFoldFedAt = ctx.elapsed
                            gsrFoldFedCount++
                            gsrFoldFeedIds += id
                        }
                    }
                    // 三态标记统计（断言点 GSR-F）：folded 弹体验证镜像后远离舰心
                    // （速度向量不变、位置中心对称 → 速度与离心方向同向）。
                    for (proj in engine.projectiles) {
                        val id = System.identityHashCode(proj)
                        if (id !in gsrFoldFeedIds || id in gsrFoldFeedMarked) continue
                        val mark = proj.customData[GSR_FOLD_MARK_KEY] as? String ?: continue
                        gsrFoldFeedMarked += id
                        when (mark) {
                            GravSpaceFoldTuning.MARK_FOLDED -> {
                                gsrFoldedCount++
                                val away = proj.velocity.x * (proj.location.x - player.location.x) +
                                        proj.velocity.y * (proj.location.y - player.location.y) > 0f
                                if (away) gsrFoldedMovingAway++
                            }

                            GravSpaceFoldTuning.MARK_NO_FOLD -> gsrNoFoldCount++
                            else -> ctx.log.warn("[ASTD-Automation] gsr fold 未知判定标记 mark=$mark（proj=${proj.projectileSpecId}），不计入统计")
                        }
                    }
                    val aliveIds = engine.projectiles.mapTo(HashSet()) { System.identityHashCode(it) }
                    val resolved = gsrFoldFeedIds.count { it in gsrFoldFeedMarked || it !in aliveIds }
                    if (gsrFoldFedCount >= GSR_FOLD_FEED_COUNT && resolved >= GSR_FOLD_FEED_COUNT) {
                        when {
                            gsrFoldedCount < 1 || gsrNoFoldCount < 1 -> {
                                ctx.failureReason = "gsr fold marks one-sided: folded=$gsrFoldedCount no_fold=$gsrNoFoldCount" +
                                        "（fed=$gsrFoldFedCount，断言点 GSR-F：50% 基础概率三态标记各 ≥1）"
                                transitionGsrPhase(GSR_PHASE_FAILED)
                            }

                            gsrFoldedMovingAway < 1 -> {
                                ctx.failureReason = "gsr folded not mirrored away: folded=$gsrFoldedCount 但无一远离舰心" +
                                        "（断言点 GSR-F：镜像折跃保持速度向量）"
                                transitionGsrPhase(GSR_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] gsr fold evidence: fed=$gsrFoldFedCount folded=$gsrFoldedCount " +
                                            "no_fold=$gsrNoFoldCount movingAway=$gsrFoldedMovingAway（断言点 GSR-F）",
                                )
                                transitionGsrPhase(GSR_PHASE_COMPLETED)
                            }
                        }
                    } else if (ctx.elapsed - gsrPhaseStartedAt >= GSR_FOLD_FEED_TIMEOUT) {
                        ctx.failureReason = "gsr fold feed timeout: ${GSR_FOLD_FEED_TIMEOUT.toInt()}s 内未收口" +
                                "（fed=$gsrFoldFedCount resolved=$resolved folded=$gsrFoldedCount no_fold=$gsrNoFoldCount）"
                        transitionGsrPhase(GSR_PHASE_FAILED)
                    }
                }
            }

            GSR_PHASE_COMPLETED -> {
                stabilizeGsrShips(engine, playerFire = false, zeroPlayerFlux = false, blockSystem = true)
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "gsr ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            gsrPhase == GSR_PHASE_FAILED -> "Failed"
            gsrPhase != GSR_PHASE_COMPLETED &&
                    ctx.elapsed - gsrPhaseStartedAt > GSR_PHASE_TIMEOUT -> {
                ctx.failureReason = "gsr phase timeout: $gsrPhase（beamIdle=${"%.3f".format(gsrBeamMultIdleMax)} " +
                        "fluxDelta=${"%.0f".format(gsrActivationFluxDeltaMax)} originals=$gsrOriginalShots replicas=$gsrReplicaShots " +
                        "cooldownMarked=$gsrCooldownMarked folded=$gsrFoldedCount noFold=$gsrNoFoldCount）"
                "Failed"
            }

            gsrPhase == GSR_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: lens_grav_replicator_zw101 beam-dr/activation/replica/cooldown/fold evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：折跃扭曲/红色星云与舜华在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findGsrPlayer(ctx.engine) }) { lockGsrCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val gsrPlayer = findGsrPlayer(engine)
        val gsrSystem = gsrPlayer?.system
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（断言点 GSR-A~GSR-F）----
        json.appendLine("  \"gsrPhase\": \"$gsrPhase\",")
        json.appendLine("  \"gsrSystemId\": ${jsonString(gsrSystem?.id)},")
        json.appendLine("  \"gsrSystemState\": ${jsonString(gsrSystem?.state?.name)},")
        json.appendLine("  \"gsrSystemCooldownRemaining\": ${formatFloat(gsrSystem?.cooldownRemaining ?: -1f)},")
        json.appendLine("  \"gsrBeamMultIdleMax\": ${formatFloat(gsrBeamMultIdleMax)},")
        json.appendLine("  \"gsrActivationFluxDeltaMax\": ${formatFloat(gsrActivationFluxDeltaMax)},")
        json.appendLine("  \"gsrOrigDamage\": ${formatFloat(gsrOrigDamage)},")
        json.appendLine("  \"gsrOriginalShots\": $gsrOriginalShots,")
        json.appendLine("  \"gsrReplicaShots\": $gsrReplicaShots,")
        json.appendLine("  \"gsrReplicaDamageMax\": ${formatFloat(gsrReplicaDamageMax)},")
        json.appendLine("  \"gsrReplicaFacingMaxDelta\": ${formatFloat(gsrReplicaFacingMaxDelta)},")
        json.appendLine("  \"gsrFluxSpikeCount\": ${gsrFluxSpikes.size},")
        json.appendLine("  \"gsrCooldownBeamMultMax\": ${formatFloat(gsrCooldownBeamMultMax)},")
        json.appendLine("  \"gsrCooldownFedCount\": $gsrCooldownFedCount,")
        json.appendLine("  \"gsrCooldownFedEntered\": ${gsrCooldownFeedDist.values.count { it <= GSR_FOLD_ENTER_DIST }},")
        json.appendLine("  \"gsrCooldownMarked\": $gsrCooldownMarked,")
        json.appendLine("  \"gsrFoldFedCount\": $gsrFoldFedCount,")
        json.appendLine("  \"gsrFoldedCount\": $gsrFoldedCount,")
        json.appendLine("  \"gsrNoFoldCount\": $gsrNoFoldCount,")
        json.appendLine("  \"gsrFoldedMovingAway\": $gsrFoldedMovingAway,")
        json.appendLine("  \"gsrPlayerCurrFlux\": ${formatFloat(gsrPlayer?.fluxTracker?.currFlux ?: -1f)},")
    }

    private companion object {
        // 舜华引力空间复制器/折跃器场景：相位机、锚点与期望证据（断言点 GSR-A~GSR-F）。
        private const val GSR_PHASE_SPAWN = "SPAWN"
        private const val GSR_PHASE_ACTIVATE = "ACTIVATE"
        private const val GSR_PHASE_OBSERVE_COOLDOWN = "OBSERVE_COOLDOWN"
        private const val GSR_PHASE_FOLD_FEED = "FOLD_FEED"
        private const val GSR_PHASE_COMPLETED = "COMPLETED"
        private const val GSR_PHASE_FAILED = "FAILED"
        private const val GSR_PLAYER_HULL = "astd_zw_101"
        private const val GSR_ENEMY_HULL = "dominator"
        private const val GSR_SYSTEM_ID = "astd_grav_replicator"

        // 敌靶舰钉远场（3000su 外）：仅作投喂弹体的敌对 source，不进入复制/折跃观测面。
        private val GSR_PLAYER_ANCHOR = Vector2f(-700f, 0f)
        private val GSR_ENEMY_ANCHOR = Vector2f(3000f, 0f)
        private val GSR_CAMERA_CENTER = Vector2f(-350f, 0f)
        private const val GSR_CAMERA_VISIBLE_HEIGHT = 1500f
        private const val GSR_SPAWN_SETTLE_SECONDS = 1.0f

        // 复制器观测武器（MissionDefinition 装入 WS0001 中型协同槽的原版脉冲激光：能量实弹、
        // 非光束非装饰，正落复制口径）与折跃投喂弹种（原版轻机枪：伤害 25 ≤ 50 走基础概率 v2 50%）。
        private const val GSR_REPLICA_WEAPON_ID = "pulselaser"
        private const val GSR_FEED_WEAPON_ID = "lightmg"


        // 投喂弹道：靶舰东侧 450su 起垂直舰心线西射，纵向散布 ±80su。lightmg 射程仅 300su、
        // 折跃判定圈 = 碰撞圈 +200su（驱逐舰 ≈260su），入圈行程 ≈190su 留足衰减余量——
        // fade 中的弹体被 tryFold 跳过（isFading 提前返回），过远投喂会在进圈前 fade 而零判定。
        private const val GSR_FEED_SPAWN_DIST = 450f
        private const val GSR_FEED_SPREAD = 80f

        // 折跃判定入圈距离上限（碰撞圈 +200su 的观测口径；驱逐舰碰撞半径 60 量级，取 300su 宽松界）。
        private const val GSR_FOLD_ENTER_DIST = 300f

        // SPAWN（断言点 GSR-A）：系统非冷却期光束承伤乘区 v2 ×0.75（界 [0.73, 0.77]）。
        private const val GSR_BEAM_MULT_IDLE_MIN = 0.73f
        private const val GSR_BEAM_MULT_IDLE_MAX = 0.77f

        // ACTIVATE（断言点 GSR-B/C）：激活软辐能 = 基础容量 ×10%（运行时读 baseValue 求期望，
        // 界 ±25% 容忍逐帧耗散的帧量化）；+0.3s 强制单发；辐能尖峰口径——开火 ≈1×单发辐能
        // （界 [0.8, 1.2]×f）、每发复制 ≈0.5×单发辐能（界 [0.3, 0.7]×f），尖峰起判 20
        // （远高于逐帧耗散 300/s × 帧间隔 ≈ 5 的底噪）；复制体伤害界 [0.45, 0.55]×原弹。
        private const val GSR_ACTIVATE_TIMEOUT = 10f
        private const val GSR_ACTIVATION_FLUX_TOLERANCE = 0.25f
        private const val GSR_FIRE_DELAY_SECONDS = 0.3f

        // 复制弹收敛射向断言下限（度）：环带出生点偏离原弹道轴 → 收敛射向与原射向必有夹角；
        // 取 max 口径（任一发复制弹明显收敛即通过），随机出生点恰好压在弹道轴上的概率近零。
        private const val GSR_REPLICA_CONVERGE_MIN_DELTA = 0.5f

        // 结算宽限：ACTIVE（2s）转 COOLDOWN 后再等 0.5s 才评估复制证据——晚发原发弹的
        // 第二发复制（发射 +1.0s，listener 队列推进不随系统关闭取消）可能压线落地。
        private const val GSR_EVAL_GRACE_SECONDS = 2.5f
        private const val GSR_SPIKE_MIN_DELTA = 20f
        private const val GSR_SPIKE_HALF_MIN = 0.3f
        private const val GSR_SPIKE_HALF_MAX = 0.7f
        private const val GSR_SPIKE_FULL_MIN = 0.8f
        private const val GSR_SPIKE_FULL_MAX = 1.2f
        private const val GSR_REPLICA_DAMAGE_RATIO_MIN = 0.45f
        private const val GSR_REPLICA_DAMAGE_RATIO_MAX = 0.55f

        // OBSERVE_COOLDOWN（断言点 GSR-D/E）：冷却 12s 期内光束乘区复原 1.0（界 [0.98, 1.02]），
        // 投喂 6 发（0.4s 间隔）全部穿圈但零判定标记；20s 超时覆盖整条冷却窗。
        private const val GSR_COOLDOWN_FEED_COUNT = 6
        private const val GSR_COOLDOWN_FEED_INTERVAL = 0.4f
        private const val GSR_COOLDOWN_MIN_ENTERED = 4
        private const val GSR_COOLDOWN_BEAM_MULT_MIN = 0.98f
        private const val GSR_COOLDOWN_BEAM_MULT_MAX = 1.02f
        private const val GSR_COOLDOWN_TIMEOUT = 20f

        // FOLD_FEED（断言点 GSR-F）：投喂 30 发（0.25s 间隔）统计三态标记——50% 基础概率下
        // 30 发全同侧概率 ~2e-9，folded/no_fold 各 ≥1 且至少一发 folded 镜像后远离舰心。
        private const val GSR_FOLD_FEED_COUNT = 30
        private const val GSR_FOLD_FEED_INTERVAL = 0.25f
        private const val GSR_FOLD_FEED_TIMEOUT = 30f
        private const val GSR_PHASE_TIMEOUT = 90f
    }
}
