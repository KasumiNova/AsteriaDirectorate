package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.HIP_RESIST_MOD_ID
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseEmpResistStacks
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseTuning
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseVfx
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 重型离子脉冲场景（规格 02 §4.2 烟测检查点）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class HeavyIonPulseScenario : AbstractAutomationScenario() {
    // === Heavy ion pulse scenario fields ===
    private var hipPhase = HIP_PHASE_MOUNT
    private var hipPhaseStartedAt = 0f

    // SHIELD 相位弹药基线（消耗 ≥8 发证明确实在命中护盾）。
    private var hipShieldAmmoBaseline = -1

    // HULL 相位弹匣节奏观测（满匣倾泻：最小弹药 / 打空时刻）与 mult=1.0 EMP 瘫痪正向对照。
    private var hipHullAmmoBaseline = -1
    private var hipMinAmmo = Int.MAX_VALUE
    private var hipEmptiedAt = -1f
    private var hipHullEnemyMaxDisabled = 0

    // SCALE5_PLAYER / PIERCE 相位遥测基线（相位内差分断言）。
    private var hipScale5PlayerHitsBaseline = 0
    private var hipScale5PiercePlayerBaseline = 0
    private var hipK2EnemyHitsBaseline = 0
    private var hipK2PierceOtherBaseline = 0
    private var hipK5PierceOtherBaseline = 0
    private var hipK5DisabledBaseline = -1
    private var hipK5MaxDisabled = 0

    // COMPLETED 截图门控：最近一次泄放/贯穿事件时刻（截图帧需含电弧/浮字/新鲜拖尾）。
    private var hipLastEventAt = -1f
    private var hipLastTrackedEventCount = 0

    // PIERCE_K5 帧率采样（持续命中下 FPS 证据）。
    private var hipK5FpsTicks = 0
    private var hipK5FpsWallStartNanos = 0L
    private var hipK5Fps = -1f

    override val scenarioId: String = ASTDInGameAutomationScenario.HIP_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isHipEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockHipCamera(engine)
        // 贯穿浮字与 FPS 仅 devMode 渲染（2026-07-29 审批裁定先例）：本场景为 dev-only 舞台，
        // 开启 devMode 以目检 EMP 贯穿补伤浮字（进程被早退杀掉，设置不落盘）。
        Global.getSettings().isDevMode = true
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findHipPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.HIP_SCENARIO_ID} combat plugin initialized")
    }

    private fun findHipPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == HIP_HULL }

    private fun findHipEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == HIP_HULL }

    private fun findHipWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.HIP_WEAPON_ID }

    private fun lockHipCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, HIP_CAMERA_CENTER, HIP_CAMERA_VISIBLE_HEIGHT)
    }

    /** 强制部署 mission reserves（双桑德均非旗舰，范式同 deployGdReserveShips）。 */
    private fun deployHipReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                if (member.hullId != HIP_HULL) continue
                val anchor = if (side == FleetSide.ENEMY) HIP_ENEMY_ANCHOR else HIP_PLAYER_ANCHOR
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionHipPhase(next: String) {
        ctx.log.info("[ASTD-Automation] hip phase $hipPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        hipPhase = next
        hipPhaseStartedAt = ctx.elapsed
    }

    /**
     * 贯穿相位舞台守卫：逐帧摘除目标舰的 EMP 抗性削减叠层 Buff（2026-09 新增机制），
     * 保持舞台钉死的近零抗性（0.01）不被命中叠层抬升——贯穿条件是 mult < 0.1，
     * 叠层两次即越过阈值会令贯穿舞台永久停摆。抗性削减机制本身的正确性由单测承担。
     */
    private fun stripHipResistStacks(ship: ShipAPI?) {
        ship ?: return
        ship.getBuff(HeavyIonPulseEmpResistStacks.BUFF_ID)?.let { ship.buffHost().remove(it) }
    }

    /**
     * 舞台保活与站位（范式同 stabilizeGdShips）：双舰逐帧奶 + 辐能清零 + 钉死锚点 +
     * force fire 独占驱动（autofire 关闭）；护盾按相位策略逐帧拨杆（保留 AI 让原版威胁追踪工作，
     * 拨杆在每帧 AI 之后执行覆盖其决定，范式同电荷针刺相位机）。
     * 敌方开火带部署免疫闸（GD/SS 实机判例：reserves 手动 spawn 舰船部署后数秒内脚本 applyDamage
     * 可能全额无效；且此前相位敌舰武器被 EMP 瘫痪需要恢复窗口）。
     */
    private fun stabilizeHipShips(engine: CombatEngineAPI, playerFire: Boolean, enemyFire: Boolean) {
        val player = findHipPlayer(engine)
        val enemy = findHipEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, HIP_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = true)
            player.shipTarget = enemy
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.fluxTracker.hardFlux = 0f
            // 护盾策略：PIERCE 相位玩家盾关（贯穿须落船体），其余相位常开。
            val shieldOn = hipPhase != HIP_PHASE_PIERCE_K2 && hipPhase != HIP_PHASE_PIERCE_K5 && hipPhase != HIP_PHASE_COMPLETED
            player.shield?.let { if (shieldOn && !it.isOn) it.toggleOn(); if (!shieldOn && it.isOn) it.toggleOff() }
            setHipAutofire(player, false)
            findHipWeapon(player)?.let {
                if (enemy != null) it.currAngle = Misc.getAngleInDegrees(it.location, enemy.location)
                it.setForceFireOneFrame(playerFire)
            }
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, HIP_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            enemy.shipTarget = player
            enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
            // 护盾策略：SHIELD 相位敌盾开（验证护盾命中无电弧），其余相位常关（泄放/贯穿须落船体）。
            val shieldOn = hipPhase == HIP_PHASE_SHIELD
            enemy.shield?.let { if (shieldOn && !it.isOn) it.toggleOn(); if (!shieldOn && it.isOn) it.toggleOff() }
            setHipAutofire(enemy, false)
            findHipWeapon(enemy)?.let {
                if (player != null) it.currAngle = Misc.getAngleInDegrees(it.location, player.location)
                val gated = enemyFire && ctx.elapsed - hipPhaseStartedAt >= HIP_ENEMY_SETTLE_SECONDS
                it.setForceFireOneFrame(gated)
            }
        }
    }

    /** HIP 武器组 autofire 总开关（范式同 setGdAutofire）：force fire 独占驱动时关闭。 */
    private fun setHipAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id == ASTDInGameAutomationScenario.HIP_WEAPON_ID }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /** 玩家舰当前被瘫痪武器数（§2.5 待验证项观测面：贯穿追加 EMP 是否穿透 mult≈0 的抗性减免）。 */
    private fun hipDisabledWeaponCount(ship: ShipAPI?): Int = ship?.allWeapons?.count { it.isDisabled } ?: 0

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployHipReserveShips(engine)
        lockHipCamera(engine)

        val player = findHipPlayer(engine)
        val enemy = findHipEnemy(engine)
        val playerWeapon = findHipWeapon(player)

        val hitsPlayer = HeavyIonPulseVfx.telemetryCount(engine, HeavyIonPulseVfx.TELEMETRY_HULL_HITS_PLAYER)
        val hitsOther = HeavyIonPulseVfx.telemetryCount(engine, HeavyIonPulseVfx.TELEMETRY_HULL_HITS_OTHER)
        val dischargePlayer = HeavyIonPulseVfx.telemetryCount(engine, HeavyIonPulseVfx.TELEMETRY_DISCHARGE_PLAYER)
        val dischargeOther = HeavyIonPulseVfx.telemetryCount(engine, HeavyIonPulseVfx.TELEMETRY_DISCHARGE_OTHER)
        val piercePlayer = HeavyIonPulseVfx.telemetryCount(engine, HeavyIonPulseVfx.TELEMETRY_PIERCE_PLAYER)
        val pierceOther = HeavyIonPulseVfx.telemetryCount(engine, HeavyIonPulseVfx.TELEMETRY_PIERCE_OTHER)

        when (hipPhase) {
            HIP_PHASE_MOUNT -> {
                stabilizeHipShips(engine, playerFire = false, enemyFire = false)
                if (ctx.elapsed - hipPhaseStartedAt >= HIP_MOUNT_SETTLE_SECONDS) {
                    val slot = playerWeapon?.slot?.id
                    val range = playerWeapon?.spec?.maxRange ?: -1f
                    val barrels = playerWeapon?.spec?.let {
                        maxOf(it.turretFireOffsets?.size ?: 0, it.hardpointFireOffsets?.size ?: 0)
                    } ?: 0
                    when {
                        playerWeapon == null || slot != HIP_PLAYER_SLOT -> {
                            ctx.failureReason = "hip mount mismatch: slot=$slot, expect $HIP_PLAYER_SLOT"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }

                        kotlin.math.abs(range - HIP_EXPECT_RANGE) > HIP_RANGE_TOLERANCE -> {
                            ctx.failureReason = "hip range=$range, expect $HIP_EXPECT_RANGE"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }

                        playerWeapon.spec?.maxAmmo != HIP_AMMO -> {
                            ctx.failureReason = "hip spec maxAmmo=${playerWeapon.spec?.maxAmmo}, expect $HIP_AMMO（weapon_data.csv 口径）"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }

                        barrels != HIP_BARRELS -> {
                            ctx.failureReason = "hip barrels=$barrels, expect $HIP_BARRELS（双炮管交替射击，.wpn turretOffsets×2 + ALTERNATING）"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }

                        !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.HIP_PROJECTILE_SPEC_ID) -> {
                            ctx.failureReason = "hip projectile VFX 未登记: ${ASTDInGameAutomationScenario.HIP_PROJECTILE_SPEC_ID}"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }

                        else -> {
                            hipShieldAmmoBaseline = playerWeapon.ammo
                            ctx.log.info("[ASTD-Automation] hip mount ok: slot=$slot range=$range specMaxAmmo=${playerWeapon.spec.maxAmmo} barrels=$barrels（双管 ALTERNATING）")
                            transitionHipPhase(HIP_PHASE_SHIELD)
                        }
                    }
                }
            }

            HIP_PHASE_SHIELD -> {
                stabilizeHipShips(engine, playerFire = true, enemyFire = false)
                if (hipShieldAmmoBaseline >= 0 && playerWeapon != null &&
                    hipShieldAmmoBaseline - playerWeapon.ammo >= HIP_SHIELD_MIN_SPENT
                ) {
                    if (dischargePlayer + dischargeOther > 0) {
                        ctx.failureReason = "hip shield phase discharge=${dischargePlayer + dischargeOther}, expect 0（EMP 对盾无效，命中护盾无电弧）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        hipHullAmmoBaseline = playerWeapon.ammo
                        hipMinAmmo = playerWeapon.ammo
                        hipEmptiedAt = -1f
                        ctx.log.info("[ASTD-Automation] hip shield evidence: spent=${hipShieldAmmoBaseline - playerWeapon.ammo} 发命中护盾、零泄放")
                        transitionHipPhase(HIP_PHASE_HULL)
                    }
                }
            }

            HIP_PHASE_HULL -> {
                stabilizeHipShips(engine, playerFire = true, enemyFire = false)
                playerWeapon?.let {
                    if (it.ammo < hipMinAmmo) {
                        hipMinAmmo = it.ammo
                        if (it.ammo <= 0 && hipEmptiedAt < 0f) {
                            hipEmptiedAt = ctx.elapsed
                            ctx.log.info("[ASTD-Automation] hip magazine emptied at ${"%.2f".format(ctx.elapsed)}s（满匣 24 发倾泻证据）")
                        }
                    }
                }
                // §2.5 待验证项正向对照：mult=1.0 的敌舰在本相位应被面板 EMP 瘫痪武器/引擎
                // （证明舞台 EMP 瘫痪机制生效，反衬 PIERCE_K5 mult≈0 目标的 disabled=0 读数）。
                hipHullEnemyMaxDisabled = maxOf(hipHullEnemyMaxDisabled, hipDisabledWeaponCount(enemy))
                if (dischargePlayer >= HIP_HULL_MIN_DISCHARGE && playerWeapon != null &&
                    hipHullAmmoBaseline - playerWeapon.ammo >= HIP_HULL_MIN_SPENT &&
                    hipHullEnemyMaxDisabled > 0
                ) {
                    ctx.log.info(
                        "[ASTD-Automation] hip hull evidence: discharge=$dischargePlayer hits=$hitsPlayer " +
                                "minAmmo=$hipMinAmmo emptiedAt=${"%.2f".format(hipEmptiedAt)}s " +
                                "enemyMaxDisabled=$hipHullEnemyMaxDisabled（mult=1.0 正向对照：EMP 瘫痪机制生效）",
                    )
                    // k_s=5 玩家恒 v2：玩家来源无贯穿（贯穿为破晓敌版逐项解锁）；敌舰 mult→0.01f 令贯穿条件成立，
                    // 若玩家口径漂移出 v2 则此相位必产出贯穿浮字（反面断言）。
                    // （2026-07-29 A9 修复后 mult 必须为近零而非绝对 0——0 乘区下贯穿按设计整体跳过，泄漏反而不可见。）
                    DifficultyTuningImpl.installScaleForTests(5f)
                    enemy?.mutableStats?.empDamageTakenMult?.modifyMult(HIP_RESIST_MOD_ID, 0.01f)
                    hipScale5PlayerHitsBaseline = hitsPlayer
                    hipScale5PiercePlayerBaseline = piercePlayer
                    transitionHipPhase(HIP_PHASE_SCALE5_PLAYER)
                }
            }

            HIP_PHASE_SCALE5_PLAYER -> {
                stabilizeHipShips(engine, playerFire = true, enemyFire = false)
                val hitsDelta = hitsPlayer - hipScale5PlayerHitsBaseline
                if (hitsDelta >= HIP_SCALE5_MIN_PLAYER_HITS) {
                    val pierceDelta = piercePlayer - hipScale5PiercePlayerBaseline
                    if (pierceDelta > 0) {
                        ctx.failureReason = "hip scale5 player pierce delta=$pierceDelta, expect 0（k_s=5 玩家恒 v2，贯穿为敌版逐项解锁）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        val disDelta = dischargePlayer - 0
                        ctx.log.info(
                            "[ASTD-Automation] hip scale5 player evidence: hitsDelta=$hitsDelta pierceDelta=0 " +
                                    "（k_s=5 玩家恒 v2；本相位玩家累计泄放 $disDelta 次、泄放仍按 v2 口径）",
                        )
                        // 敌版 k_s=2 无贯穿（反面）：玩家停火，敌版开火落玩家船体。
                        // 玩家 mult 钉 0.01f（近零抗性）：原 0f 舞台为 A9 证明所用（绝对 0 乘区下
                        // 任何折算补偿无效，A9 修复后该舞台会令贯穿整体跳过、K5 相位永不触发）；
                        // 0.01f 恰处折算下限，引擎二次乘算后实际结算精确回补 extra（显示值=实际结算量），
                        // K5 相位借此验证补偿链路与 applied 遥测断言（2026-07-29 A9 裁定方案 a）。
                        DifficultyTuningImpl.installScaleForTests(2f)
                        enemy?.mutableStats?.empDamageTakenMult?.unmodifyMult(HIP_RESIST_MOD_ID)
                        player?.mutableStats?.empDamageTakenMult?.modifyMult(HIP_RESIST_MOD_ID, 0.01f)
                        hipK2EnemyHitsBaseline = hitsOther
                        hipK2PierceOtherBaseline = pierceOther
                        transitionHipPhase(HIP_PHASE_PIERCE_K2)
                    }
                }
            }

            HIP_PHASE_PIERCE_K2 -> {
                stabilizeHipShips(engine, playerFire = false, enemyFire = true)
                // 贯穿舞台守卫（2026-09 修订）：逐帧摘除 EMP 抗性削减叠层 Buff，保持玩家侧钉死的
                // 近零抗性（0.01）不被新机制抬升——抗性削减机制另有专项验证，不在贯穿舞台内叠加。
                stripHipResistStacks(player)
                val hitsDelta = hitsOther - hipK2EnemyHitsBaseline
                if (hitsDelta >= HIP_K2_MIN_ENEMY_HITS) {
                    val pierceDelta = pierceOther - hipK2PierceOtherBaseline
                    if (pierceDelta > 0) {
                        ctx.failureReason = "hip k_s=2 enemy pierce delta=$pierceDelta, expect 0（v2 档无 EMP 贯穿特效）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        ctx.log.info("[ASTD-Automation] hip k2 evidence: enemyHitsDelta=$hitsDelta pierceDelta=0（敌版 k_s=2 无贯穿）")
                        DifficultyTuningImpl.installScaleForTests(5f)
                        hipK5PierceOtherBaseline = pierceOther
                        hipK5DisabledBaseline = hipDisabledWeaponCount(player)
                        hipK5MaxDisabled = hipK5DisabledBaseline
                        hipK5FpsTicks = 0
                        hipK5FpsWallStartNanos = System.nanoTime()
                        hipK5Fps = -1f
                        transitionHipPhase(HIP_PHASE_PIERCE_K5)
                    }
                }
            }

            HIP_PHASE_PIERCE_K5 -> {
                stabilizeHipShips(engine, playerFire = false, enemyFire = true)
                stripHipResistStacks(player)
                hipK5FpsTicks++
                hipK5MaxDisabled = maxOf(hipK5MaxDisabled, hipDisabledWeaponCount(player))
                if (pierceOther - hipK5PierceOtherBaseline >= HIP_K5_MIN_PIERCE) {
                    if (hipK5Fps < 0f && hipK5FpsWallStartNanos > 0L) {
                        val wallSeconds = (System.nanoTime() - hipK5FpsWallStartNanos) / 1_000_000_000f
                        if (wallSeconds > 0f) hipK5Fps = hipK5FpsTicks / wallSeconds
                    }
                    val lastExtra = HeavyIonPulseVfx.telemetryFloat(engine, HeavyIonPulseVfx.TELEMETRY_PIERCE_LAST_EXTRA)
                    val lastApplied = HeavyIonPulseVfx.telemetryFloat(engine, HeavyIonPulseVfx.TELEMETRY_PIERCE_LAST_APPLIED)
                    val lastMult = HeavyIonPulseVfx.telemetryFloat(engine, HeavyIonPulseVfx.TELEMETRY_PIERCE_LAST_MULT)
                    val lastBase = HeavyIonPulseVfx.telemetryFloat(engine, HeavyIonPulseVfx.TELEMETRY_PIERCE_LAST_BASE_EMP)
                    val lastArc = HeavyIonPulseVfx.telemetryFloat(engine, HeavyIonPulseVfx.TELEMETRY_PIERCE_LAST_ARC_EMP)
                    // A9 裁定方案 a（2026-07-29）硬断言：施加量 = extra / max(mult, 0.01)（折算补偿），
                    // 引擎二次乘算后实际结算回补到 extra（遥测双证：applied 通道 + 公式复算）。
                    val expectedApplied = lastExtra / maxOf(lastMult, HeavyIonPulseTuning.PIERCE_COMPENSATION_FLOOR)
                    if (kotlin.math.abs(lastApplied - expectedApplied) > 0.5f) {
                        ctx.failureReason = "hip pierce applied 折算不符: applied=$lastApplied expect=$expectedApplied（extra=$lastExtra mult=$lastMult）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        ctx.log.info(
                            "[ASTD-Automation] hip pierce k5 evidence: pierce=${pierceOther - hipK5PierceOtherBaseline} " +
                                    "lastExtra=$lastExtra lastApplied=$lastApplied lastMult=$lastMult lastBaseEmp=$lastBase lastArcEmp=$lastArc " +
                                    "playerDisabledWeapons ${hipK5DisabledBaseline}→max $hipK5MaxDisabled " +
                                    "（A9 已修：applied 遥测断言证明折算链路；disabled 为观测项——mult 低于折算下限时少量欠补，瘫痪非必然）" +
                                    "fps=${"%.1f".format(hipK5Fps)}",
                        )
                        transitionHipPhase(HIP_PHASE_COMPLETED)
                    }
                }
            }

            HIP_PHASE_COMPLETED -> {
                stabilizeHipShips(engine, playerFire = true, enemyFire = true)
            }
        }

        // 最近一次泄放/贯穿事件时刻（COMPLETED 截图门控：事件近期发生才上报，令电弧/浮字/拖尾入帧）
        val eventCount = dischargePlayer + dischargeOther + piercePlayer + pierceOther
        if (eventCount > hipLastTrackedEventCount) {
            hipLastTrackedEventCount = eventCount
            hipLastEventAt = ctx.elapsed
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "hip ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            hipPhase == HIP_PHASE_FAILED -> "Failed"
            hipPhase != HIP_PHASE_COMPLETED &&
                    ctx.elapsed - hipPhaseStartedAt > HIP_PHASE_TIMEOUT -> {
                ctx.failureReason = "hip phase timeout: $hipPhase"
                "Failed"
            }

            hipPhase == HIP_PHASE_COMPLETED -> {
                val recentEvent = hipLastEventAt >= 0f && ctx.elapsed - hipLastEventAt <= HIP_COMPLETED_EVENT_WINDOW
                if (recentEvent || ctx.elapsed - hipPhaseStartedAt >= HIP_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            DifficultyTuningImpl.installScaleForTests(null)
            player?.mutableStats?.empDamageTakenMult?.unmodifyMult(HIP_RESIST_MOD_ID)
            ctx.log.info("[ASTD-Automation] Completed: heavy_ion_pulse_basic mount/shield/discharge/scale5/k2/k5-pierce evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, playerWeapon)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        appendRuntimeTelemetryDiagnostics(json, vfxTelemetry)
    }

    private companion object {
        // 重型离子脉冲场景：相位机、锚点与期望证据（规格 02 §4.2 烟测检查点）。
        private const val HIP_PHASE_MOUNT = "MOUNT"
        private const val HIP_PHASE_SHIELD = "SHIELD"
        private const val HIP_PHASE_HULL = "HULL"
        private const val HIP_PHASE_SCALE5_PLAYER = "SCALE5_PLAYER"
        private const val HIP_PHASE_PIERCE_K2 = "PIERCE_K2"
        private const val HIP_PHASE_PIERCE_K5 = "PIERCE_K5"
        private const val HIP_PHASE_COMPLETED = "COMPLETED"
        private const val HIP_PHASE_FAILED = "FAILED"
        private const val HIP_HULL = "sunder"
        private const val HIP_PLAYER_SLOT = "WS 003"
        private val HIP_PLAYER_ANCHOR = Vector2f(-350f, 0f)
        private val HIP_ENEMY_ANCHOR = Vector2f(300f, 0f)
        private val HIP_CAMERA_CENTER = Vector2f(0f, 0f)
        private const val HIP_CAMERA_VISIBLE_HEIGHT = 760f
        private const val HIP_MOUNT_SETTLE_SECONDS = 0.6f

        // MOUNT 相位校验：射程 800 / spec maxAmmo 24（weapon_data.csv 口径，2026-09 修订）/ 双炮管 offsets（ALTERNATING 交替射击证据）。
        private const val HIP_EXPECT_RANGE = 800f
        private const val HIP_RANGE_TOLERANCE = 5f
        private const val HIP_AMMO = 24
        private const val HIP_BARRELS = 2

        // SHIELD：消耗 ≥8 发证明确实在命中护盾后断言零泄放。
        private const val HIP_SHIELD_MIN_SPENT = 8

        // HULL：泄放 ≥2 且消耗 ≥16 发（弹匣倾泻节奏证据）。
        private const val HIP_HULL_MIN_DISCHARGE = 2
        private const val HIP_HULL_MIN_SPENT = 16

        // SCALE5_PLAYER：相位内玩家船体命中 ≥12 次后断言贯穿增量 0（k_s=5 玩家恒 v2）。
        private const val HIP_SCALE5_MIN_PLAYER_HITS = 12

        // PIERCE_K2：相位内敌方船体命中 ≥8 次后断言贯穿增量 0（v2 档无贯穿）。
        private const val HIP_K2_MIN_ENEMY_HITS = 8

        // PIERCE_K5：mult≈0 目标下每次船体命中必贯穿，采样 ≥3 次贯穿事件强化 §2.5 待验证项读数。
        private const val HIP_K5_MIN_PIERCE = 3

        // 敌方开火部署免疫闸（GD/SS 实机判例同款；兼作敌舰武器 EMP 瘫痪恢复窗口）。
        private const val HIP_ENEMY_SETTLE_SECONDS = 4.0f

        // COMPLETED 截图门控：泄放/贯穿事件近 2.5s 内发生才上报（电弧/浮字/新鲜拖尾入帧）；保底舞台超时。
        private const val HIP_COMPLETED_EVENT_WINDOW = 2.5f
        private const val HIP_COMPLETED_STAGE_TIMEOUT = 30f
        private const val HIP_PHASE_TIMEOUT = 90f
    }
}
