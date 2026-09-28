package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueCalcStacks
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueDamageDealtModifier
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunDifficulty
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunOnHitEffect
import cn.kasuminova.astd.combat.effect.arc.qiongjue.qiongjueCalcStacks
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 穷距相位轨道炮场景（规格 05 §2.5 烟测检查点）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class QiongjueRailgunScenario : AbstractAutomationScenario() {
    // ==== qiongjue phase railgun 场景状态（相位机 MOUNT → STACK → DUAL → SWITCH → DECAY → KILL → ENEMY_SCALE → COMPLETED） ====
    private var qjPhase = QJ_PHASE_MOUNT
    private var qjPhaseStartedAt = 0f

    // STACK：满层证据（伤害乘区 / 射速间隔 / spike / HUD / 浮字 / 帧率）。
    private var qjDmgMultAtFull = -1f
    private var qjRefireMinAtFull = Float.MAX_VALUE
    private val qjSeenProjectiles = mutableSetOf<Int>()
    private var qjLastSpawnAtW1 = -1f
    private var qjFullHoldSince = -1f
    private var qjStackFpsTicks = 0
    private var qjStackFpsWallStartNanos = 0L
    private var qjStackFps = -1f

    // DUAL（2026-09 修订：共享证据）：w1 独建满层后 w2 补射，首发同享满层乘区（读 qjW2DealtMult 遥测）。
    // SWITCH：异目标折算证据（floor(10×0.3125)+1=4）。
    private var qjSwitchW1Stacks = -1

    // DECAY：停火窗口衰减证据（3s 窗口 + 1.75 层/s 归零耗时）。
    private var qjDecaySeconds = -1f

    // KILL：旧目标失效不折算证据（击沉 B 转火 C，首中=旧值+1）。
    private var qjStacksBeforeKill = -1
    private var qjStacksAfterKillHit = -1
    private var qjKillRetargeted = false

    // ENEMY_SCALE：敌版三档逐命中伤害乘区证据（v1 2%/v2 4%/v5 10% × 4~5 层）；采样前停火 settle 防层数续爬。
    private var qjScaleStep = 0
    private var qjScaleSampling = false
    private var qjScaleSampleAt = -1f
    private var qjEnemyMult1 = -1f
    private var qjEnemyMult2 = -1f
    private var qjEnemyMult5 = -1f

    override val scenarioId: String = ASTDInGameAutomationScenario.QJ_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isQjEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockQjCamera(engine)
        // HUD 状态条目仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检「持续演算」状态条目与敌版三档（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findQjPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.QJ_SCENARIO_ID} combat plugin initialized")
    }

    private fun findQjPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner == 0 && it.hullSpec?.hullId == QJ_PLAYER_HULL && !it.isFighter }

    private fun findQjEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == QJ_PLAYER_HULL && !it.isFighter }

    /** 切换目标靶舰（两艘警戒级按锚点距离区分；击沉后为 hulk 仍按位置取）。 */
    private fun findQjSwitchTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.filter { it.hullSpec?.hullId == QJ_TARGET_HULL && !it.isFighter }
            .minByOrNull { Misc.getDistance(it.location, QJ_SWITCH_ANCHOR) }

    /** 击杀目标靶舰（KILL 相位击沉 B 后的转火对象，全程保活）。 */
    private fun findQjKillTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.filter { it.hullSpec?.hullId == QJ_TARGET_HULL && !it.isFighter }
            .minByOrNull { Misc.getDistance(it.location, QJ_KILL_ANCHOR) }

    private fun findQjWeapon(ship: ShipAPI?, slotId: String): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.QJ_WEAPON_ID && it.slot?.id == slotId }

    private fun lockQjCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, QJ_CAMERA_CENTER, QJ_CAMERA_VISIBLE_HEIGHT)
    }

    /** 强制部署 mission reserves（玩家/敌版统治者 + 双警戒靶舰全部非旗舰，按舰体与出场顺序分配锚点）。 */
    private fun deployQjReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        var vigilanceIndex = 0
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val hullId = member.hullId ?: continue
                val anchor = when {
                    side == FleetSide.PLAYER && hullId == QJ_PLAYER_HULL -> QJ_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && hullId == QJ_PLAYER_HULL -> QJ_ENEMY_ANCHOR
                    hullId == QJ_TARGET_HULL && vigilanceIndex == 0 -> {
                        vigilanceIndex++
                        QJ_SWITCH_ANCHOR
                    }

                    hullId == QJ_TARGET_HULL -> QJ_KILL_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionQjPhase(next: String) {
        ctx.log.info("[ASTD-Automation] qj phase $qjPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        qjPhase = next
        qjPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeEdaShips）：保留舰 AI + 逐帧 currAngle 对准 + setForceFireOneFrame
     * 绕 AutofireAI 死锁（01/03 实证路径）。双穷距分开火独立控制（DUAL 相位单停 w2）；
     * 玩家/敌版辐能逐帧清零——双穷距 900 辐能/发远超统治者耗散，不清零会在 STACK 相位中途过载卡死
     * （辐能不是本机制观测对象）。警戒靶舰盾舞台性常关（KILL 相位需快速击沉 B）。
     */
    private fun stabilizeQjShips(
        engine: CombatEngineAPI,
        playerTarget: ShipAPI?,
        fireW1: Boolean,
        fireW2: Boolean,
        enemyFire: Boolean = false,
        healSwitchTarget: Boolean = true,
    ) {
        val player = findQjPlayer(engine)
        val enemy = findQjEnemy(engine)
        val switch = findQjSwitchTarget(engine)
        val kill = findQjKillTarget(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, QJ_PLAYER_ANCHOR, 0f, allowFire = fireW1 || fireW2, preserveAI = true)
            player.shipTarget = playerTarget
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            // 变体武器组自带 autofire（第四轮实证：w2 停火相位 AutofireAI 继续开火，层数不衰减），
            // 双穷距一律改由 force fire 独占驱动（开/停逐槽位精确）。
            setQjAutofire(player, false)
            stageQjFireControl(player, playerTarget, fireW1, fireW2)
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, QJ_ENEMY_ANCHOR, 180f, allowFire = enemyFire, preserveAI = true)
            enemy.shipTarget = player
            enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            setQjAutofire(enemy, false)
            for (w in enemy.allWeapons) {
                if (w.id != ASTDInGameAutomationScenario.QJ_WEAPON_ID) continue
                if (player != null) w.currAngle = Misc.getAngleInDegrees(w.location, player.location)
                w.setForceFireOneFrame(enemyFire)
            }
        }
        if (switch != null && !switch.isHulk) {
            stabilizeShip(switch, QJ_SWITCH_ANCHOR, 180f, allowFire = false, preserveAI = true)
            switch.shipTarget = null
            if (healSwitchTarget) switch.hitpoints = switch.maxHitpoints
            switch.shield?.toggleOff()
        }
        if (kill != null && !kill.isHulk) {
            stabilizeShip(kill, QJ_KILL_ANCHOR, 180f, allowFire = false, preserveAI = true)
            kill.shipTarget = null
            kill.hitpoints = kill.maxHitpoints
            kill.shield?.toggleOff()
        }
    }

    /** 穷距武器组 autofire 总开关（范式同 setChargeNeedleAutofire）：force fire 独占驱动时关闭，防 AutofireAI 干扰停火相位。 */
    private fun setQjAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id == ASTDInGameAutomationScenario.QJ_WEAPON_ID }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /** 舞台直控开火（范式同 stageEdaFireControl）：玩家双穷距按槽位独立 fire 开关，逐帧 currAngle 对准。 */
    private fun stageQjFireControl(ship: ShipAPI, target: ShipAPI?, fireW1: Boolean, fireW2: Boolean) {
        for (w in ship.allWeapons) {
            if (w.id != ASTDInGameAutomationScenario.QJ_WEAPON_ID) continue
            val fire = when (w.slot?.id) {
                QJ_PLAYER_SLOT_W1 -> fireW1
                QJ_PLAYER_SLOT_W2 -> fireW2
                else -> false
            }
            if (target != null) w.currAngle = Misc.getAngleInDegrees(w.location, target.location)
            w.setForceFireOneFrame(fire)
        }
    }

    /** 满层射速间隔追踪：w1 新弹 spawn 间隔（w1 满层窗口内取最小值，期望 2.5s 全周期/1.4≈1.79s 的 spike 证据）。 */
    private fun trackQjRefire(engine: CombatEngineAPI, w1: WeaponAPI?, w1Stacks: Int) {
        w1 ?: return
        for (p in engine.projectiles) {
            if (p.projectileSpecId != ASTDInGameAutomationScenario.QJ_PROJECTILE_SPEC_ID) continue
            val damaging = p as? DamagingProjectileAPI ?: continue
            if (damaging.weapon !== w1) continue
            if (!qjSeenProjectiles.add(System.identityHashCode(p))) continue
            if (w1Stacks >= QiongjuePhaseRailgunDifficulty.MAX_STACKS) {
                if (qjLastSpawnAtW1 >= 0f) {
                    val gap = ctx.elapsed - qjLastSpawnAtW1
                    if (gap < qjRefireMinAtFull) qjRefireMinAtFull = gap
                }
                qjLastSpawnAtW1 = ctx.elapsed
            }
        }
    }

    /** 敌版三档换步：移除敌方 Ship 级共享演算 Buff，下一命中从 0 重建（保证采样时层数恰为目标值）。 */
    private fun clearQjEnemyBuff(enemy: ShipAPI?) {
        if (enemy == null) return
        val buff = enemy.getBuff(QiongjueCalcStacks.BUFF_ID) ?: return
        enemy.buffHost().remove(buff)
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployQjReserveShips(engine)
        lockQjCamera(engine)

        val player = findQjPlayer(engine)
        val enemy = findQjEnemy(engine)
        val switch = findQjSwitchTarget(engine)
        val kill = findQjKillTarget(engine)
        val w1 = findQjWeapon(player, QJ_PLAYER_SLOT_W1)
        val w2 = findQjWeapon(player, QJ_PLAYER_SLOT_W2)
        val enemyW = enemy?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.QJ_WEAPON_ID }
        // 2026-09 修订：叠层 Buff 为 Ship 级全舰同型共享——w1/w2/敌舰各至多一份，按舰直取。
        val shipBuff = player?.qiongjueCalcStacks()
        val enemyBuff = enemy?.qiongjueCalcStacks()

        when (qjPhase) {
            QJ_PHASE_MOUNT -> {
                stabilizeQjShips(engine, playerTarget = enemy, fireW1 = false, fireW2 = false)
                if (ctx.elapsed - qjPhaseStartedAt >= QJ_MOUNT_SETTLE_SECONDS) {
                    val slot1 = w1?.slot?.id
                    val slot2 = w2?.slot?.id
                    val range = w1?.range ?: -1f
                    if (slot1 == QJ_PLAYER_SLOT_W1 && slot2 == QJ_PLAYER_SLOT_W2 &&
                        kotlin.math.abs(range - QJ_EXPECT_RANGE) <= QJ_RANGE_TOLERANCE
                    ) {
                        transitionQjPhase(QJ_PHASE_STACK)
                    } else {
                        ctx.failureReason = "qj mount mismatch: slot1=$slot1 slot2=$slot2 range=$range(expect $QJ_EXPECT_RANGE)"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }

            QJ_PHASE_STACK -> {
                stabilizeQjShips(engine, playerTarget = enemy, fireW1 = true, fireW2 = true)
                trackQjRefire(engine, w1, shipBuff?.stacks ?: 0)
                val full = (shipBuff?.stacks ?: 0) >= QiongjuePhaseRailgunDifficulty.MAX_STACKS
                if (full && qjFullHoldSince < 0f) {
                    qjFullHoldSince = ctx.elapsed
                    qjStackFpsTicks = 0
                    qjStackFpsWallStartNanos = System.nanoTime()
                }
                if (qjFullHoldSince >= 0f) qjStackFpsTicks++
                if (qjFullHoldSince >= 0f && ctx.elapsed - qjFullHoldSince >= QJ_FULL_HOLD_SECONDS) {
                    val wallSeconds = (System.nanoTime() - qjStackFpsWallStartNanos) / 1_000_000_000.0
                    qjStackFps = if (wallSeconds > 0.0) (qjStackFpsTicks / wallSeconds).toFloat() else -1f
                    // 伤害乘区证据走逐命中遥测（同 spec 武器共享 damage.modifier stat，武器 stat 读数会被双穷距互乘污染）。
                    qjDmgMultAtFull = if (player != null && w1 != null) {
                        QiongjueDamageDealtModifier.dealtMult(engine, player, w1)
                    } else {
                        -1f
                    }
                    val spike = QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjueCalcStacks.TELEMETRY_SPIKE_APPLIED)
                    val hud = QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjueCalcStacks.TELEMETRY_HUD_FRAMES)
                    val cone = QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_CONE_VFX)
                    when {
                        kotlin.math.abs(qjDmgMultAtFull - QJ_EXPECT_FULL_DMG_MULT) > QJ_DMG_MULT_TOLERANCE -> {
                            ctx.failureReason = "qj full dmg mult=$qjDmgMultAtFull, expect≈$QJ_EXPECT_FULL_DMG_MULT（10 层 × v2 4%）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }

                        qjRefireMinAtFull < QJ_REFIRE_MIN || qjRefireMinAtFull > QJ_REFIRE_MAX -> {
                            ctx.failureReason =
                                "qj full refire min=$qjRefireMinAtFull, expect [$QJ_REFIRE_MIN, $QJ_REFIRE_MAX]（2.5s 全周期/1.4≈1.79s spike，cooldownRemaining 含充能段）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }

                        spike < 1 -> {
                            ctx.failureReason = "qj spike applied=$spike, expect>=1（setRemainingCooldownTo 周期起点扣减）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }

                        hud < 1 -> {
                            ctx.failureReason = "qj hud frames=$hud, expect>=1（「持续演算」状态条目）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }

                        cone < 1 -> {
                            ctx.failureReason = "qj cone vfx=$cone, expect>=1（命中小号锥面特效）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }

                        qjStackFps < QJ_STACK_MIN_FPS -> {
                            ctx.failureReason = "qj stack fps=$qjStackFps < $QJ_STACK_MIN_FPS（叠层期帧率门槛）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }

                        else -> {
                            // dealtMult 为常驻遥测（相位间不清零）：进 DUAL 前抹掉 w2 历史读数，
                            // 保证本相位的 w2Mult>0 只能来自「w1 独建满层后 w2 补射首发」的新命中。
                            if (player != null && w2 != null) {
                                engine.customData.remove(QiongjueDamageDealtModifier.telemetryKey(player, w2))
                            }
                            transitionQjPhase(QJ_PHASE_DUAL)
                        }
                    }
                }
            }

            QJ_PHASE_DUAL -> {
                // 共享验证（2026-09 修订）：w1 独打保持满层，w2 停火 4s 后补射——
                // w2 首发命中同样享受全舰共享满层乘区（Ship 级 Buff 证据）。
                stabilizeQjShips(
                    engine,
                    playerTarget = enemy,
                    fireW1 = true,
                    fireW2 = ctx.elapsed - qjPhaseStartedAt >= QJ_DUAL_W2_FIRE_AFTER,
                )
                val w2Mult = if (player != null && w2 != null) {
                    QiongjueDamageDealtModifier.dealtMult(engine, player, w2)
                } else {
                    -1f
                }
                if (w2Mult > 0f) {
                    if ((shipBuff?.stacks ?: 0) >= QiongjuePhaseRailgunDifficulty.MAX_STACKS &&
                        kotlin.math.abs(w2Mult - QJ_EXPECT_FULL_DMG_MULT) <= QJ_DMG_MULT_TOLERANCE
                    ) {
                        transitionQjPhase(QJ_PHASE_SWITCH)
                    } else {
                        ctx.failureReason =
                            "qj shared w2 mult=$w2Mult stacks=${shipBuff?.stacks}, expect≈$QJ_EXPECT_FULL_DMG_MULT（w1 独建满层，w2 首发同享）"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                } else if (ctx.elapsed - qjPhaseStartedAt >= QJ_DUAL_SECONDS) {
                    ctx.failureReason = "qj dual w2 首发命中未记录（dealtMult=-1），超时 $QJ_DUAL_SECONDS s"
                    transitionQjPhase(QJ_PHASE_FAILED)
                }
            }

            QJ_PHASE_SWITCH -> {
                stabilizeQjShips(engine, playerTarget = switch, fireW1 = true, fireW2 = true)
                if (switch != null && shipBuff?.target === switch) {
                    qjSwitchW1Stacks = shipBuff.stacks
                    val transfer = QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_TRANSFER_PLAYER)
                    if (qjSwitchW1Stacks in QJ_SWITCH_MIN_STACKS..QJ_SWITCH_MAX_STACKS && transfer >= 1) {
                        transitionQjPhase(QJ_PHASE_DECAY)
                    } else {
                        ctx.failureReason =
                            "qj switch stacks=$qjSwitchW1Stacks(expect $QJ_SWITCH_MIN_STACKS~$QJ_SWITCH_MAX_STACKS: floor(10×0.3125)+1=4) transfer=$transfer(expect>=1)"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }

            QJ_PHASE_DECAY -> {
                stabilizeQjShips(engine, playerTarget = null, fireW1 = false, fireW2 = false)
                if ((shipBuff?.stacks ?: 0) == 0) {
                    qjDecaySeconds = ctx.elapsed - qjPhaseStartedAt
                    if (qjDecaySeconds in QJ_DECAY_MIN_SECONDS..QJ_DECAY_MAX_SECONDS) {
                        transitionQjPhase(QJ_PHASE_KILL)
                    } else {
                        ctx.failureReason = "qj decay seconds=$qjDecaySeconds, expect [$QJ_DECAY_MIN_SECONDS, $QJ_DECAY_MAX_SECONDS]（3s 窗口 + 1.75 层/s）"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }

            QJ_PHASE_KILL -> {
                val armed = (shipBuff?.stacks ?: 0) >= QJ_KILL_ARM_STACKS
                // 叠到 ≥3 层后停奶 B（盾已舞台性常关）让其被击沉；击沉瞬间转火 C 并清一次冷却，抢在 3s 窗口内命中。
                stabilizeQjShips(
                    engine,
                    playerTarget = if (qjKillRetargeted) kill else switch,
                    fireW1 = true,
                    fireW2 = true,
                    healSwitchTarget = !armed,
                )
                if (!qjKillRetargeted && switch != null && switch.isHulk) {
                    qjKillRetargeted = true
                    qjStacksBeforeKill = shipBuff?.stacks ?: -1
                    w1?.setRemainingCooldownTo(0f)
                    w2?.setRemainingCooldownTo(0f)
                    ctx.log.info("[ASTD-Automation] qj switch target killed at ${"%.2f".format(ctx.elapsed)}s, stacks=$qjStacksBeforeKill, retarget kill ship")
                }
                if (qjKillRetargeted && kill != null && shipBuff?.target === kill) {
                    qjStacksAfterKillHit = shipBuff.stacks
                    val expected = (qjStacksBeforeKill + 1).coerceAtMost(QiongjuePhaseRailgunDifficulty.MAX_STACKS)
                    if (qjStacksAfterKillHit == expected) {
                        DifficultyTuningImpl.installScaleForTests(1f)
                        clearQjEnemyBuff(enemy)
                        qjScaleStep = 0
                        qjScaleSampling = false
                        transitionQjPhase(QJ_PHASE_ENEMY_SCALE)
                    } else {
                        ctx.failureReason =
                            "qj kill switch stacks=$qjStacksAfterKillHit, expect=$expected（旧目标失效不折算，首中=旧值+1；前值=$qjStacksBeforeKill）"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }

            QJ_PHASE_ENEMY_SCALE -> {
                // 达 5 层即停火 + settle 采样（第 5 发命中后逐命中乘区恰为 4 层值，停火防第 6 发覆盖采样窗口）。
                stabilizeQjShips(
                    engine,
                    playerTarget = null,
                    fireW1 = false,
                    fireW2 = false,
                    enemyFire = !qjScaleSampling,
                )
                if (!qjScaleSampling && (enemyBuff?.stacks ?: 0) >= QJ_ENEMY_SCALE_TARGET_STACKS) {
                    qjScaleSampling = true
                    qjScaleSampleAt = ctx.elapsed
                }
                if (qjScaleSampling && ctx.elapsed - qjScaleSampleAt >= QJ_SCALE_SETTLE_SECONDS && enemyW != null && enemy != null) {
                    val mult = QiongjueDamageDealtModifier.dealtMult(engine, enemy, enemyW)
                    when (qjScaleStep) {
                        0 -> {
                            qjEnemyMult1 = mult
                            ctx.log.info("[ASTD-Automation] qj enemy dealt mult@k_s=1: $qjEnemyMult1")
                            DifficultyTuningImpl.installScaleForTests(2f)
                        }

                        1 -> {
                            qjEnemyMult2 = mult
                            ctx.log.info("[ASTD-Automation] qj enemy dealt mult@k_s=2: $qjEnemyMult2")
                            DifficultyTuningImpl.installScaleForTests(5f)
                        }

                        else -> {
                            qjEnemyMult5 = mult
                            ctx.log.info("[ASTD-Automation] qj enemy dealt mult@k_s=5: $qjEnemyMult5")
                            DifficultyTuningImpl.installScaleForTests(null)
                        }
                    }
                    if (qjScaleStep < 2) {
                        clearQjEnemyBuff(enemy)
                        qjScaleStep++
                        qjScaleSampling = false
                    } else {
                        val ok = qjEnemyMult1 in QJ_ENEMY_MULT_1_MIN..QJ_ENEMY_MULT_1_MAX &&
                                qjEnemyMult2 in QJ_ENEMY_MULT_2_MIN..QJ_ENEMY_MULT_2_MAX &&
                                qjEnemyMult5 in QJ_ENEMY_MULT_5_MIN..QJ_ENEMY_MULT_5_MAX
                        if (ok) {
                            transitionQjPhase(QJ_PHASE_COMPLETED)
                        } else {
                            ctx.failureReason =
                                "qj enemy dealt mult 三档=$qjEnemyMult1/$qjEnemyMult2/$qjEnemyMult5, expect [1.07,1.11]/[1.15,1.21]/[1.39,1.51]（v1 2%/v2 4%/v5 10% × 4~5 层逐命中乘区）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                    }
                }
            }

            QJ_PHASE_COMPLETED -> {
                stageQjCompletedFrame(engine)
            }
        }

        // 切换靶舰 B 在 KILL 相位被击沉（观测对象本身）且 hulk 残骸可能被清出场——转火后不再要求其在场。
        val switchOk = (switch != null && !switch.isHulk) || qjKillRetargeted
        val state = when {
            player == null || enemy == null || !switchOk || kill == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason =
                        "qj ships missing: player=${player != null}, enemy=${enemy != null}, switch=${switch != null}, kill=${kill != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            qjPhase == QJ_PHASE_FAILED -> "Failed"
            qjPhase != QJ_PHASE_COMPLETED &&
                    ctx.elapsed - qjPhaseStartedAt > QJ_PHASE_TIMEOUT -> {
                ctx.failureReason = "qj phase timeout: $qjPhase"
                "Failed"
            }

            qjPhase == QJ_PHASE_COMPLETED -> {
                // 截图门控：叠层回升到可见水位才上报 Completed——SSOptimizer 在上报时刻连拍三帧，
                // 令「持续演算」HUD 条目与白色拖尾/命中锥面入帧（对齐 AV 中段门控先例）；保底超时防舞台卡死。
                val stackedForShot = (shipBuff?.stacks ?: 0) >= QJ_COMPLETED_STACKS_FOR_SHOT
                if (stackedForShot || ctx.elapsed - qjPhaseStartedAt >= QJ_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: qiongjue_railgun_basic stack/dual/switch/decay/kill/scaling evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, w1)
        }
    }

    /** COMPLETED 截图舞台：玩家双穷距回打敌版统治者，细长拖尾/命中锥面/「持续演算」HUD 入帧。 */
    private fun stageQjCompletedFrame(engine: CombatEngineAPI) {
        stabilizeQjShips(engine, playerTarget = findQjEnemy(engine), fireW1 = true, fireW2 = true)
        lockQjCamera(engine)
    }

    // 捕获帧间隔 0.6s：双穷距回打敌版，细长拖尾/命中锥面/「持续演算」HUD 在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findQjPlayer(ctx.engine) }) { stageQjCompletedFrame(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val qjPlayer = findQjPlayer(engine)
        val qjEnemy = findQjEnemy(engine)
        val qjW1 = findQjWeapon(qjPlayer, QJ_PLAYER_SLOT_W1)
        val qjW2 = findQjWeapon(qjPlayer, QJ_PLAYER_SLOT_W2)
        val qjEnemyW = qjEnemy?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.QJ_WEAPON_ID }
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 05 §2.5 烟测检查点）----
        json.appendLine("  \"qjPhase\": \"$qjPhase\",")
        json.appendLine("  \"qjW1SlotId\": ${jsonString(qjW1?.slot?.id)},")
        json.appendLine("  \"qjW2SlotId\": ${jsonString(qjW2?.slot?.id)},")
        json.appendLine("  \"qjWeaponRange\": ${formatFloat(qjW1?.range ?: -1f)},")
        // 2026-09 修订：Ship 级共享 Buff，w1/w2 层数同一份读数（保留双键便于报告对照）。
        json.appendLine("  \"qjW1Stacks\": ${qjPlayer?.qiongjueCalcStacks()?.stacks ?: -1},")
        json.appendLine("  \"qjW2Stacks\": ${qjPlayer?.qiongjueCalcStacks()?.stacks ?: -1},")
        json.appendLine("  \"qjEnemyStacks\": ${qjEnemy?.qiongjueCalcStacks()?.stacks ?: -1},")
        // 逐命中伤害乘区遥测（同 spec 武器共享 damage.modifier stat 后的唯一逐武器证据通道）。
        json.appendLine(
            "  \"qjW1DealtMult\": ${
                formatFloat(
                    if (qjPlayer != null && qjW1 != null) QiongjueDamageDealtModifier.dealtMult(
                        engine,
                        qjPlayer,
                        qjW1
                    ) else -1f
                )
            },"
        )
        json.appendLine(
            "  \"qjW2DealtMult\": ${
                formatFloat(
                    if (qjPlayer != null && qjW2 != null) QiongjueDamageDealtModifier.dealtMult(
                        engine,
                        qjPlayer,
                        qjW2
                    ) else -1f
                )
            },"
        )
        json.appendLine(
            "  \"qjEnemyDealtMult\": ${
                formatFloat(
                    if (qjEnemy != null && qjEnemyW != null) QiongjueDamageDealtModifier.dealtMult(
                        engine,
                        qjEnemy,
                        qjEnemyW
                    ) else -1f
                )
            },"
        )
        // 同舰双穷距伤害 stat 同一性探针（第三轮烟测实证 true：同 spec 武器共享底层 MutableStat）。
        json.appendLine("  \"qjDmgStatShared\": ${qjW1 != null && qjW2 != null && qjW1.damage?.modifier === qjW2.damage?.modifier},")
        json.appendLine("  \"qjDmgMultAtFull\": ${formatFloat(qjDmgMultAtFull)},")
        json.appendLine("  \"qjRefireMinAtFull\": ${formatFloat(if (qjRefireMinAtFull == Float.MAX_VALUE) -1f else qjRefireMinAtFull)},")
        json.appendLine(
            "  \"qjSpikeApplied\": ${
                QiongjuePhaseRailgunOnHitEffect.telemetryCount(
                    engine,
                    QiongjueCalcStacks.TELEMETRY_SPIKE_APPLIED
                )
            },"
        )
        json.appendLine("  \"qjHudFrames\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjueCalcStacks.TELEMETRY_HUD_FRAMES)},")
        json.appendLine(
            "  \"qjHitPlayer\": ${
                QiongjuePhaseRailgunOnHitEffect.telemetryCount(
                    engine,
                    QiongjuePhaseRailgunOnHitEffect.TELEMETRY_HIT_PLAYER
                )
            },"
        )
        json.appendLine(
            "  \"qjHitOther\": ${
                QiongjuePhaseRailgunOnHitEffect.telemetryCount(
                    engine,
                    QiongjuePhaseRailgunOnHitEffect.TELEMETRY_HIT_OTHER
                )
            },"
        )
        json.appendLine(
            "  \"qjTransferPlayer\": ${
                QiongjuePhaseRailgunOnHitEffect.telemetryCount(
                    engine,
                    QiongjuePhaseRailgunOnHitEffect.TELEMETRY_TRANSFER_PLAYER
                )
            },"
        )
        json.appendLine(
            "  \"qjConeVfx\": ${
                QiongjuePhaseRailgunOnHitEffect.telemetryCount(
                    engine,
                    QiongjuePhaseRailgunOnHitEffect.TELEMETRY_CONE_VFX
                )
            },"
        )
        json.appendLine("  \"qjStackFps\": ${formatFloat(qjStackFps)},")
        json.appendLine("  \"qjSwitchW1Stacks\": $qjSwitchW1Stacks,")
        json.appendLine("  \"qjDecaySeconds\": ${formatFloat(qjDecaySeconds)},")
        json.appendLine("  \"qjStacksBeforeKill\": $qjStacksBeforeKill,")
        json.appendLine("  \"qjStacksAfterKillHit\": $qjStacksAfterKillHit,")
        json.appendLine("  \"qjEnemyMult1\": ${formatFloat(qjEnemyMult1)},")
        json.appendLine("  \"qjEnemyMult2\": ${formatFloat(qjEnemyMult2)},")
        json.appendLine("  \"qjEnemyMult5\": ${formatFloat(qjEnemyMult5)},")
        json.appendLine("  \"qjDevMode\": ${Global.getSettings().isDevMode},")
        json.appendLine("  \"qjOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.QJ_PROJECTILE_SPEC_ID }},")
    }

    private companion object {
        // 穷距相位轨道炮场景：相位机、锚点与期望证据（规格 05 §2.5 烟测检查点）。
        private const val QJ_PHASE_MOUNT = "MOUNT"
        private const val QJ_PHASE_STACK = "STACK"
        private const val QJ_PHASE_DUAL = "DUAL"
        private const val QJ_PHASE_SWITCH = "SWITCH"
        private const val QJ_PHASE_DECAY = "DECAY"
        private const val QJ_PHASE_KILL = "KILL"
        private const val QJ_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val QJ_PHASE_COMPLETED = "COMPLETED"
        private const val QJ_PHASE_FAILED = "FAILED"

        // COMPLETED 相位截图门控：叠层回升到该层数才上报（HUD/拖尾/锥面入帧），超时保底防舞台卡死。
        private const val QJ_COMPLETED_STACKS_FOR_SHOT = 3
        private const val QJ_COMPLETED_STAGE_TIMEOUT = 25f
        private const val QJ_PLAYER_HULL = "dominator"
        private const val QJ_TARGET_HULL = "vigilance"
        private const val QJ_PLAYER_SLOT_W1 = "WS 012"
        private const val QJ_PLAYER_SLOT_W2 = "WS 013"
        private val QJ_PLAYER_ANCHOR = Vector2f(-500f, 0f)
        private val QJ_ENEMY_ANCHOR = Vector2f(500f, 0f)
        private val QJ_SWITCH_ANCHOR = Vector2f(500f, 300f)
        private val QJ_KILL_ANCHOR = Vector2f(500f, -300f)
        private val QJ_CAMERA_CENTER = Vector2f(0f, 0f)
        private const val QJ_CAMERA_VISIBLE_HEIGHT = 1250f
        private const val QJ_MOUNT_SETTLE_SECONDS = 0.6f

        // MOUNT 相位校验：dedicated_targeting_core 已在 MissionDefinition 摘除，射程断言基线 1100（2026-09 修订）。
        private const val QJ_EXPECT_RANGE = 1100f
        private const val QJ_RANGE_TOLERANCE = 5f

        // 满层证据期望：v2 每层 4% × 10 → 伤害乘区 1.4（600→840）；
        // 射速 spike 压缩全周期（烟测实证 cooldownRemaining 含充能段）：2.5s/1.4≈1.79s。
        private const val QJ_EXPECT_FULL_DMG_MULT = 1.4f
        private const val QJ_DMG_MULT_TOLERANCE = 0.02f
        private const val QJ_REFIRE_MIN = 1.6f
        private const val QJ_REFIRE_MAX = 2.0f
        private const val QJ_FULL_HOLD_SECONDS = 2.5f
        private const val QJ_STACK_MIN_FPS = 30f

        // DUAL（共享验证）：w1 独打续层，w2 停火 4s 后补射，7s 内必须拿到 w2 首发命中乘区记录。
        private const val QJ_DUAL_W2_FIRE_AFTER = 4f
        private const val QJ_DUAL_SECONDS = 7f

        // SWITCH：w1 10 层折算 floor(10×0.3125)+1=4（采样帧可能已再叠 1 层，容差到 5）。
        private const val QJ_SWITCH_MIN_STACKS = 4
        private const val QJ_SWITCH_MAX_STACKS = 5

        // DECAY：停火至归零期望 ≈ 3s 窗口 + 4 层/1.75 ≈ 5.3s。
        private const val QJ_DECAY_MIN_SECONDS = 4f
        private const val QJ_DECAY_MAX_SECONDS = 8f

        // KILL：叠到 ≥3 层后停奶切换靶舰让其被击沉，转火 C 首中 = 旧值+1（不折算）。
        private const val QJ_KILL_ARM_STACKS = 3

        // ENEMY_SCALE：换档采样前停火 settle；目标 5 层（第 5 发命中的监听器按 4 层结算 → 逐命中乘区恰为 4 层值）。
        private const val QJ_SCALE_SETTLE_SECONDS = 0.3f
        private const val QJ_ENEMY_SCALE_TARGET_STACKS = 5

        // 敌版三档逐命中乘区期望：1 + 4~5 × v1 2% / v2 4% / v5 10% = 1.08~1.10 / 1.16~1.20 / 1.40~1.50；
        // 监听器按命中前层数结算（天然滞后一层），采样窗口内容忍 4~5 层两值，三档区间互不重叠。
        private const val QJ_ENEMY_MULT_1_MIN = 1.07f
        private const val QJ_ENEMY_MULT_1_MAX = 1.11f
        private const val QJ_ENEMY_MULT_2_MIN = 1.15f
        private const val QJ_ENEMY_MULT_2_MAX = 1.21f
        private const val QJ_ENEMY_MULT_5_MIN = 1.39f
        private const val QJ_ENEMY_MULT_5_MAX = 1.51f
        private const val QJ_PHASE_TIMEOUT = 90f
    }
}
