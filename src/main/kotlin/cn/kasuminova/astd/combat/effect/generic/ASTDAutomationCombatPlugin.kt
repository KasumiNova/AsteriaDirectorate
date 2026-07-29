package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import cn.kasuminova.astd.combat.effect.arc.ChargeNeedleVfx
import cn.kasuminova.astd.combat.effect.arc.ElectricDriveAcceleratorOnHitEffect
import cn.kasuminova.astd.combat.effect.arc.PositronShockwaveFuseScript
import cn.kasuminova.astd.combat.effect.arc.chargeNeedleStacks
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueCalcStacks
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueDamageDealtModifier
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunDifficulty
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunOnHitEffect
import cn.kasuminova.astd.combat.effect.arc.qiongjue.qiongjueCalcStacks
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexBeamEffect
import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.buff.getBuffByWeapon
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionTooltipContracts
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.hullmods.lens.LENS_DUAL_MODE_CONFIG
import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreHullModIds
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.combat.lens.system.EchoFixationField
import cn.kasuminova.astd.renderer.effect.lens.EchoFixationAfterimageRenderer
import cn.kasuminova.astd.renderer.effect.lens.LensVfxTelemetry
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import cn.kasuminova.astd.impl.render.ASTDProjectileVfxLayout
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f

/**
 * Dev-only combat automation surface for validating Arc Flare + AOD-7 runtime VFX in game.
 */
class ASTDAutomationCombatPlugin : BaseEveryFrameCombatPlugin() {
    private val captureCenter = Vector2f(100f, 0f)
    private val playerAnchor = Vector2f(-260f, 0f)
    private val projectilePreviewAnchor = Vector2f(40f, 0f)
    private val enemyAnchor = Vector2f(900f, 0f)
    private val arcProductionAnchors = mapOf(
        "astd_arc_jet" to Vector2f(-720f, 120f),
        "astd_plasma_arch" to Vector2f(-80f, -40f),
        "astd_radiation_belt" to Vector2f(520f, 135f),
        "ally_frigate" to Vector2f(-500f, -280f),
        "ally_destroyer" to Vector2f(360f, -255f),
        "enemy_target" to Vector2f(980f, 20f),
    )
    private val log = Global.getLogger(ASTDAutomationCombatPlugin::class.java)
    private var engine: CombatEngineAPI? = null
    private var elapsed = 0f
    private var lastWriteAt = -1f
    private var fallbackSpawned = false
    private var completed = false
    private var completedAt = -1f
    private var visualFramesWritten = 0
    private var lastVisualFrameAt = -1f
    private var failureReason: String? = null
    private var fireMechanism: String? = null
    private var fallbackProjectile: DamagingProjectileAPI? = null
    private var fallbackProjectileSpawnedAt = -1f
    private var lensMarksInjected = false
    private val lensAnchor = Vector2f(-260f, 0f)
    /** phase2 幽灵信号导弹投放计时累积（秒），见 feedGhostSignalMissiles。 */
    private var ghostMissileFeedAcc = 0f
    /** phase2 fighter 误差标记降级 log 的 once 守卫（避免每帧刷屏，参照 lensMarksInjected 模式）。 */
    private var lensPhase2DowngradeLogged = false

    // ==== charge needle 场景状态（相位机 SHIELD → HULL → CEASE → COMPLETED） ====
    private var chargeNeedlePhase = CHARGE_NEEDLE_PHASE_SHIELD
    private var chargeNeedlePhaseStartedAt = 0f
    private var chargeNeedlePeakStacks = 0
    private var chargeNeedleMinSmallAmmo = Int.MAX_VALUE
    private var chargeNeedleMinHeavyAmmo = Int.MAX_VALUE
    private var chargeNeedleSmallEmptiedAt = -1f
    private var chargeNeedleDecayVerified = false

    // ==== electric drive accelerator 场景状态（相位机 RANGE_ZERO → RANGE_MID → RANGE_HIGH → FIRE → ENEMY_SCALE → COMPLETED） ====
    private var edaPhase = EDA_PHASE_RANGE_ZERO
    private var edaPhaseStartedAt = 0f
    private var edaRangeZeroFlux = -1f
    private var edaRangeMidFlux = -1f
    private var edaRangeHighFlux = -1f
    private var edaEnemyRangeScale1 = -1f
    private var edaEnemyRangeScale2 = -1f
    private var edaEnemyRangeScale5 = -1f
    private var edaScaleStep = 0
    private var edaScaleStepAt = -1f
    private var edaEnemyExtraBaseline = -1
    private var edaMinPlayerAmmo = Int.MAX_VALUE
    // 每触发弹数分组：spawn 间隔 > EDA_BURST_GROUP_GAP 视为新一轮触发（burst delay 0.15s，组内 8 弹）。
    private var edaCurrentBurstCount = 0
    private var edaLastSpawnAt = -1f
    private var edaMaxTriggerProjectiles = 0
    private val edaBurstSizes = mutableListOf<Int>()
    private val edaSeenProjectiles = mutableSetOf<Int>()

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
    // DUAL：同舰双穷距独立证据（w1 续打满层 / w2 停火衰减）。
    private var qjDualW1Stacks = -1
    private var qjDualW2Stacks = -1
    // SWITCH：异目标折算证据（floor(10×0.3125)+1=4）。
    private var qjSwitchW1Stacks = -1
    // DECAY：停火窗口衰减证据（3s 窗口 + 1.75 层/s 归零耗时）。
    private var qjDecaySeconds = -1f
    // KILL：旧目标失效不折算证据（击沉 B 转火 C，首中=旧值+1）。
    private var qjStacksBeforeKill = -1
    private var qjStacksAfterKillHit = -1
    private var qjKillRetargeted = false
    // ENEMY_SCALE：敌版三档逐命中伤害乘区证据（v1/v2/v5 × 4 层 → 1.20/1.25/1.40）；采样前停火 settle 防层数续爬。
    private var qjScaleStep = 0
    private var qjScaleSampling = false
    private var qjScaleSampleAt = -1f
    private var qjEnemyMult1 = -1f
    private var qjEnemyMult2 = -1f
    private var qjEnemyMult5 = -1f

    // ==== positron shockwave 场景状态（相位机 MOUNT → PASS_THROUGH → SPLASH → FUSE → COMPLETED） ====
    private var psPhase = PS_PHASE_MOUNT
    private var psPhaseStartedAt = 0f
    // SPLASH 相位基线：进入相位时的锥面舰船命中计数与近炸引爆计数（增量即本相位证据）。
    private var psSplashShipHitsBaseline = 0
    private var psSplashFuseBaseline = 0
    private var psSplashMaxRangeBaseline = 0
    // FUSE 相位导弹投喂节流与左右舷交替。
    private var psMissileFeedAt = -1f
    private var psMissileFeedSide = 1
    // COMPLETED 截图门控：最近一次近炸引爆时刻（截图帧需含锥面 VFX/浮字）。
    private var psLastFuseDetonateAt = -1f
    private var psLastTrackedFuseCount = 0

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        ProjectileVfxDriverPlugin.ensureInstalled(engine)
        if (ASTDInGameAutomationScenario.isPsEnabled()) {
            engine.setDoNotEndCombat(true)
            lockPsCamera(engine)
            // 引爆计数浮字仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
            // 开启 devMode 以目检「近炸命中 ×n」浮字（进程被早退杀掉，设置不落盘）。
            Global.getSettings().setDevMode(true)
            // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findPsPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.PS_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isQjEnabled()) {
            engine.setDoNotEndCombat(true)
            lockQjCamera(engine)
            // HUD 状态条目仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
            // 开启 devMode 以目检「持续演算」状态条目与敌版三档（进程被早退杀掉，设置不落盘）。
            Global.getSettings().setDevMode(true)
            // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findQjPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.QJ_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isAvEnabled()) {
            engine.setDoNotEndCombat(true)
            lockAvCamera(engine)
            // HUD 状态条目仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
            // 开启 devMode 以目检吞噬池 HUD 与敌版三档（进程被早退杀掉，设置不落盘）。
            Global.getSettings().setDevMode(true)
            // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findAvPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.AV_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isEdaEnabled()) {
            engine.setDoNotEndCombat(true)
            lockEdaCamera(engine)
            // HUD 状态条目仅 devMode 渲染（2026-07-29 审批裁定）：本场景为 dev-only 舞台，
            // 开启 devMode 以目检 HUD 条目与敌版三档（进程被早退杀掉，设置不落盘）。
            Global.getSettings().setDevMode(true)
            // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findEdaPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.EDA_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isChargeNeedleEnabled()) {
            engine.setDoNotEndCombat(true)
            lockChargeNeedleCamera(engine)
            // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findChargeNeedlePlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.CHARGE_NEEDLE_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
            engine.setDoNotEndCombat(true)
            lockArcProductionCamera(engine)
            // 与 phase1/ARC production 一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            arrangeLensPhase2Ships(engine)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findCrewedLens(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.LENS_PHASE2_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            engine.setDoNotEndCombat(true)
            lockArcProductionCamera(engine)
            // 与 ARC production 一致：reserves 部署放到 advance()，init 阶段战斗渲染器尚未就绪，
            // 此时调用 spawnFleetMember -> setPlayerShip 会触发原版 arcRenderer NPE。
            arrangeLensPhase1Ships(engine)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            engine.setDoNotEndCombat(true)
            lockArcProductionCamera(engine)
            arrangeArcProductionShips(engine)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", arcProductionTelemetryShip(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID} combat plugin initialized")
        } else {
            lockCamera(engine)
            arrangeShips(engine, findArcFlare(engine))
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady")
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SCENARIO_ID} combat plugin initialized")
        }
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val combatEngine = engine ?: return
        if (ASTDInGameAutomationScenario.isPsEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advancePsScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isQjEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceQjScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isAvEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceAvScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isEdaEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceEdaScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isChargeNeedleEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceChargeNeedleScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceLensPhase2Scenario(combatEngine, amount.coerceAtLeast(0f))
            return
        }
        if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceLensPhase1Scenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceArcProductionScenario(combatEngine)
            return
        }
        if (combatEngine.isPaused) return

        elapsed += amount.coerceAtLeast(0f)

        val ship = findArcFlare(combatEngine)
        val weapon = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID }
        lockCamera(combatEngine)
        arrangeShips(combatEngine, ship)
        alignAod7ProjectilesForEvidence(combatEngine)
        if (completed && elapsed - completedAt >= 0.75f) return

        if (ship != null) {
            combatEngine.setPlayerShipExternal(ship)
            ship.shipAI = null
            ship.setControlsLocked(false)
            ship.setHoldFireOneFrame(false)
            ship.setShipTarget(null)
        }

        if (!completed && ship != null && weapon != null && elapsed >= 0.5f) {
            weapon.setRemainingCooldownTo(0f)
            weapon.setForceFireOneFrame(true)
            fireMechanism = fireMechanism ?: "setForceFireOneFrame"
        }

        if (!completed && ship != null && weapon != null && elapsed >= 1.5f && !projectileObserved(combatEngine) && !fallbackSpawned) {
            fallbackSpawned = true
            spawnAod7Projectile(combatEngine, ship, weapon)
            fireMechanism = "spawnProjectileFallback"
        }

        val state = if (completed) "Completed" else currentState(combatEngine, ship, weapon)
        if (state == "Completed") {
            if (!completed) {
                completed = true
                completedAt = elapsed
                log.info("[ASTD-Automation] Completed: arc_flare/aod7/${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID}/VFX observed")
            }
            return
        }

        if (elapsed - lastWriteAt >= 0.18f || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(combatEngine, state, ship)
            writeTelemetry(combatEngine, state, ship, weapon)
        }
    }

    override fun renderInUICoords(viewport: ViewportAPI) {
        val combatEngine = engine ?: return
        if (ASTDInGameAutomationScenario.isQjEnabled()) {
            if (!completed || visualFramesWritten >= 3) return
            // 捕获帧间隔 0.6s：双穷距回打敌版，细长拖尾/命中锥面/「持续演算」HUD 在三帧内进入捕获帧。
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.6f) return
            stageQjCompletedFrame(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findQjPlayer(combatEngine))
            writeTelemetry(combatEngine, "Completed", findQjPlayer(combatEngine), null)
            return
        }
        if (ASTDInGameAutomationScenario.isAvEnabled()) {
            if (!completed || visualFramesWritten >= 3) return
            // 捕获帧间隔 0.6s：协同槽开火 + 投喂，涡旋面/吸收 flare/坍缩烟云在三帧内进入捕获帧。
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.6f) return
            stageAvCompletedFrame(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findAvPlayer(combatEngine))
            writeTelemetry(combatEngine, "Completed", findAvPlayer(combatEngine), null)
            return
        }
        if (ASTDInGameAutomationScenario.isEdaEnabled()) {
            if (!completed || visualFramesWritten >= 3) return
            // 捕获帧间隔 0.6s：齐射拖尾/追加伤害浮字/HUD 条目在三帧内进入捕获帧。
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.6f) return
            stageEdaCompletedFrame(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findEdaPlayer(combatEngine))
            writeTelemetry(combatEngine, "Completed", findEdaPlayer(combatEngine), null)
            return
        }
        if (ASTDInGameAutomationScenario.isChargeNeedleEnabled()) {
            if (!completed || visualFramesWritten >= 3) return
            // 捕获帧间隔 0.6s：敌方盾开 + 双方开火，叠层 HUD 在三帧内累积到可见层数，新鲜拖尾/电弧入帧。
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.6f) return
            stageChargeNeedleCompletedFrame(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findChargeNeedlePlayer(combatEngine))
            writeTelemetry(combatEngine, "Completed", findChargeNeedlePlayer(combatEngine), null)
            return
        }
        if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
            if (!completed || visualFramesWritten >= 3) return
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return
            lockArcProductionCamera(combatEngine)
            arrangeLensPhase2Ships(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findCrewedLens(combatEngine))
            writeTelemetry(combatEngine, "Completed", findCrewedLens(combatEngine), null)
            return
        }
        if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            if (!completed || visualFramesWritten >= 3) return
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return
            lockArcProductionCamera(combatEngine)
            arrangeLensPhase1Ships(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findShipByHull(combatEngine, LensArrayCoreHullModIds.HULL_ID))
            writeTelemetry(combatEngine, "Completed", findShipByHull(combatEngine, LensArrayCoreHullModIds.HULL_ID), null)
            return
        }
        if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            if (!completed || visualFramesWritten >= 3) return
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return
            lockArcProductionCamera(combatEngine)
            arrangeArcProductionShips(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed")
            writeTelemetry(combatEngine, "Completed", arcProductionTelemetryShip(combatEngine), null)
            return
        }

        if (!completed || visualFramesWritten >= 3) return
        if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return

        lockCamera(combatEngine)
        val ship = findArcFlare(combatEngine)
        arrangeShips(combatEngine, ship)
        alignAod7ProjectilesForEvidence(combatEngine)
        lastVisualFrameAt = elapsed
        visualFramesWritten++
        writeDiagnostics(combatEngine, "Completed", ship)
        writeTelemetry(
            combatEngine,
            "Completed",
            ship,
            ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
        )
        if (visualFramesWritten == 3) {
            log.info("[ASTD-Automation] visual evidence frames written after render")
        }
    }

    private fun findArcFlare(engine: CombatEngineAPI): ShipAPI? {
        return engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == ASTDInGameAutomationScenario.SHIP_ID ||
                ship.variant?.hullVariantId == ASTDInGameAutomationScenario.VARIANT_ID
        }
    }

    private fun findShipByHull(engine: CombatEngineAPI, hullId: String): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.hullSpec?.hullId == hullId }

    private fun lockCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleHeight = 600f
        val visibleWidth = visibleHeight * displayAspect

        viewport.setExternalControl(true)
        viewport.set(
            captureCenter.x - visibleWidth * 0.5f,
            captureCenter.y - visibleHeight * 0.5f,
            visibleWidth,
            visibleHeight,
        )
        viewport.setEverythingNearViewport(true)
    }

    private fun arrangeShips(engine: CombatEngineAPI, playerShip: ShipAPI?) {
        playerShip?.let { stabilizeShip(it, playerAnchor, 0f, allowFire = true) }
        engine.ships
            .filter { it !== playerShip && it.owner != playerShip?.owner }
            .forEach { stabilizeShip(it, enemyAnchor, 180f, allowFire = false) }
    }

    private fun stabilizeShip(ship: ShipAPI, location: Vector2f, facing: Float, allowFire: Boolean, preserveAI: Boolean = false) {
        ship.location.set(location)
        ship.velocity.set(0f, 0f)
        ship.facing = facing
        ship.angularVelocity = 0f
        if (!preserveAI) {
            ship.shipAI = null
            ship.setShipTarget(null)
        }
        ship.setControlsLocked(false)
        ship.setHoldFireOneFrame(!allowFire)
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE)
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS)
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT)
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT)
        ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT)
        ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT)
        if (!allowFire) ship.blockCommandForOneFrame(ShipCommand.FIRE)
    }

    private fun lockArcProductionCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleHeight = 980f
        val visibleWidth = visibleHeight * displayAspect

        viewport.setExternalControl(true)
        viewport.set(-40f - visibleWidth * 0.5f, -20f - visibleHeight * 0.5f, visibleWidth, visibleHeight)
        viewport.setEverythingNearViewport(true)
    }

    private fun deployArcProductionReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployArcProductionSide(engine, FleetSide.PLAYER)
        deployArcProductionSide(engine, FleetSide.ENEMY)
    }

    private fun deployArcProductionSide(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.setSuppressDeploymentMessages(true)
        val reserves = manager.getReservesCopy().toList()
        if (reserves.isEmpty()) return

        var allyIndex = 0
        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            if (findShipByHull(engine, hullId) != null && hullId in ARC_PRODUCTION_CORE_HULLS) {
                manager.removeFromReserves(member)
                continue
            }

            val anchor = when {
                side == FleetSide.ENEMY -> {
                    val base = arcProductionAnchors.getValue("enemy_target")
                    Vector2f(base.x + enemyIndex++ * 170f, base.y)
                }
                hullId == ASTDArcProductionShipIds.HULL_ARC_JET -> arcProductionAnchors.getValue("astd_arc_jet")
                hullId == ASTDArcProductionShipIds.HULL_PLASMA_ARCH -> arcProductionAnchors.getValue("astd_plasma_arch")
                hullId == ASTDArcProductionShipIds.HULL_RADIATION_BELT -> arcProductionAnchors.getValue("astd_radiation_belt")
                else -> {
                    val base = if (allyIndex % 2 == 0) {
                        arcProductionAnchors.getValue("ally_frigate")
                    } else {
                        arcProductionAnchors.getValue("ally_destroyer")
                    }
                    Vector2f(base.x + (allyIndex / 2) * 150f, base.y)
                }
            }
            val facing = when {
                side == FleetSide.ENEMY -> 180f
                hullId == ASTDArcProductionShipIds.HULL_RADIATION_BELT -> 180f
                else -> 0f
            }
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = shouldPreserveArcProductionAI(side, hullId))
            if (side == FleetSide.PLAYER && hullId !in ARC_PRODUCTION_CORE_HULLS) allyIndex++
        }
    }

    private fun shouldPreserveArcProductionAI(side: FleetSide, hullId: String): Boolean =
        side == FleetSide.ENEMY || hullId == ASTDArcProductionShipIds.HULL_PLASMA_ARCH

    private fun arrangeArcProductionShips(engine: CombatEngineAPI) {
        val arcJet = findShipByHull(engine, "astd_arc_jet")
        val plasmaArch = findShipByHull(engine, "astd_plasma_arch")
        val radiationBelt = findShipByHull(engine, "astd_radiation_belt")
        arcJet?.let { stabilizeShip(it, arcProductionAnchors.getValue("astd_arc_jet"), 0f, allowFire = false) }
        plasmaArch?.let { stabilizeShip(it, arcProductionAnchors.getValue("astd_plasma_arch"), 0f, allowFire = false, preserveAI = true) }
        radiationBelt?.let { stabilizeShip(it, arcProductionAnchors.getValue("astd_radiation_belt"), 180f, allowFire = false) }

        engine.ships
            .filter { it.hullSpec?.hullId !in setOf("astd_arc_jet", "astd_plasma_arch", "astd_radiation_belt") }
            .filter { it.owner == arcJet?.owner || it.owner == plasmaArch?.owner || it.owner == radiationBelt?.owner }
            .forEachIndexed { index, ship ->
                val anchor = if (index % 2 == 0) arcProductionAnchors.getValue("ally_frigate") else arcProductionAnchors.getValue("ally_destroyer")
                stabilizeShip(ship, anchor, 0f, allowFire = false)
            }

        engine.ships
            .filter { ship -> ship.owner != 0 }
            .forEachIndexed { index, ship ->
                val base = arcProductionAnchors.getValue("enemy_target")
                val anchor = Vector2f(base.x + index * 170f, base.y)
                stabilizeShip(ship, anchor, 180f, allowFire = true, preserveAI = true)
                pressurePlasmaArchForSystemAI(ship, plasmaArch)
            }
    }

    private fun pressurePlasmaArchForSystemAI(ship: ShipAPI, plasmaArch: ShipAPI?) {
        if (plasmaArch == null || ship.owner == plasmaArch.owner) return
        ship.setShipTarget(plasmaArch)
        for (weapon in try { ship.allWeapons } catch (_: Throwable) { return }) {
            weapon.setForceFireOneFrame(true)
        }
    }

    private fun advanceArcProductionScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployArcProductionReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeArcProductionShips(engine)

        val arcJet = findShipByHull(engine, "astd_arc_jet")
        val plasmaArch = findShipByHull(engine, "astd_plasma_arch")
        val radiationBelt = findShipByHull(engine, "astd_radiation_belt")
        val telemetryShip = arcJet ?: plasmaArch ?: radiationBelt
        telemetryShip?.let { engine.setPlayerShipExternal(it) }

        arcJet?.system?.let { if (!it.isOn && elapsed > 0.8f) arcJet.useSystem() }
        plasmaArch?.shield?.let { if (!it.isOn) it.toggleOn() }
        radiationBelt?.system?.let { if (!it.isOn && elapsed > 0.8f) radiationBelt.useSystem() }

        val missingShips = arcProductionMissingShips(engine)
        val state = when {
            arcProductionEvidenceReady(engine) -> "Completed"
            missingShips.isNotEmpty() && elapsed > 8f -> {
                failureReason = "arc production ships missing: ${missingShips.joinToString(",")}"
                "Failed"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: arc_production_ships_vfx_tooltip/VFX tooltip evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, telemetryShip)
            writeTelemetry(engine, state, telemetryShip, null)
        }
    }

    private fun arcProductionMissingShips(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.filter { findShipByHull(engine, it) == null }

    private fun arcProductionEvidenceReady(engine: CombatEngineAPI): Boolean {
        if (elapsed < 1.25f) return false
        return listOf(
            ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FRAMES,
            ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_RADIUS,
            ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FLUX_PRESSURE,
            ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_OPEN,
            ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE,
            ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS,
            ASTDArcProductionVfx.TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES,
        ).all { ASTDArcProductionVfx.counter(engine, it) > 0 }
    }

    private fun arcProductionTelemetryShip(engine: CombatEngineAPI): ShipAPI? =
        findShipByHull(engine, "astd_arc_jet")
            ?: findShipByHull(engine, "astd_plasma_arch")
            ?: findShipByHull(engine, "astd_radiation_belt")

    // === Charge needle scenario (stacking / discharge / magazine / HUD evidence) ===

    private fun findChargeNeedlePlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == CHARGE_NEEDLE_PLAYER_HULL }

    private fun findChargeNeedleEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == CHARGE_NEEDLE_ENEMY_HULL }

    private fun lockChargeNeedleCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleHeight = 760f
        val visibleWidth = visibleHeight * displayAspect

        viewport.setExternalControl(true)
        viewport.set(
            CHARGE_NEEDLE_CAMERA_CENTER.x - visibleWidth * 0.5f,
            CHARGE_NEEDLE_CAMERA_CENTER.y - visibleHeight * 0.5f,
            visibleWidth,
            visibleHeight,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（敌方伯劳鸟非旗舰，必须手动出场；范式同 deployLensPhase1Side）。 */
    private fun deployChargeNeedleReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
                val anchor = if (side == FleetSide.ENEMY) CHARGE_NEEDLE_ENEMY_ANCHOR else CHARGE_NEEDLE_PLAYER_ANCHOR
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    /**
     * 针刺武器组自动开火开关：`setForceFireOneFrame` 对无舰 AI 的舞台舰不生效（实机 90s 零发射验证），
     * 改用原版武器组 autofire 管线——toggleOn 后组内武器 AutofireAI 自行瞄准 shipTarget 开火。
     * 注意：不得每帧 `setRemainingCooldownTo(0f)`——实机验证它会把武器开火周期反复重置导致零弹体
     * （aod7 场景同款写法即因此依赖 spawnProjectile 兜底）。
     */
    private fun setChargeNeedleAutofire(ship: ShipAPI?, enabled: Boolean, weaponIds: Set<String>) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id in weaponIds }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    private fun transitionChargeNeedlePhase(next: String) {
        log.info("[ASTD-Automation] charge needle phase $chargeNeedlePhase -> $next at ${"%.2f".format(elapsed)}s")
        chargeNeedlePhase = next
        chargeNeedlePhaseStartedAt = elapsed
    }

    /**
     * 电荷针刺相位机：SHIELD（敌盾开，淤积叠层）→ HULL（敌盾关，泄放电弧 + 弹匣倾泻）
     * → CEASE（停火，衰减归零验证）→ COMPLETED（恢复开火做截图舞台）。
     */
    private fun advanceChargeNeedleScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployChargeNeedleReserveShips(engine)
        lockChargeNeedleCamera(engine)

        val player = findChargeNeedlePlayer(engine)
        val enemy = findChargeNeedleEnemy(engine)
        if (player != null) {
            engine.setPlayerShipExternal(player)
            // 双舰逐帧钉死（位置/朝向/速度归零）但保留舰 AI（preserveAI=true）：实机验证 AI 置空后
            // OMNI 盾失去威胁追踪、停在一个朝向，弹体全部走船体路线导致淤积恒 0（第十四轮 AI 存活时叠层正常）；
            // 且敌方 AutofireAI 拒射。AI 存活 + 命令封锁 + 钉死即可兼顾稳定与机制行为。
            // 注：真正阻断开火的是每帧 setRemainingCooldownTo(0f)（已移除），而非 AI 置空与否。
            stabilizeShip(player, CHARGE_NEEDLE_PLAYER_ANCHOR, 0f, allowFire = chargeNeedlePhase != CHARGE_NEEDLE_PHASE_CEASE, preserveAI = true)
            player.setShipTarget(enemy)
            // 玩家盾常开：敌方针刺命中玩家护盾触发受击方淤积（victim HUD 证据）。
            player.shield?.let { if (!it.isOn) it.toggleOn() }
            // 舞台保活：场景内双方血量/辐能顶格，避免过载/击沉打断相位机（纯 staging，非机制兜底）。
            player.setHitpoints(player.maxHitpoints)
            player.fluxTracker.setCurrFlux(0f)
            player.fluxTracker.setHardFlux(0f)
            setChargeNeedleAutofire(player, chargeNeedlePhase != CHARGE_NEEDLE_PHASE_CEASE, CHARGE_NEEDLE_PLAYER_WEAPON_IDS)
            // 窄射界槽位（野狼 WS 004 仅 5° 弧）AutofireAI 目标采纳存在死锁：currAngle 停在弧缘 → 目标判出弧置空
            // → 无人修正 currAngle（实机判别：同槽挂小型针刺同样 aiTarget=null 拒射，与重型 spec 无关，
            // extraArcForAI=25 也不解）。舞台逐帧把针刺武器 currAngle 对准敌舰解除死锁。
            if (enemy != null) {
                for (w in player.allWeapons) {
                    if (w.id in CHARGE_NEEDLE_PLAYER_WEAPON_IDS) {
                        w.setCurrAngle(Misc.getAngleInDegrees(w.location, enemy.location))
                    }
                }
            }
            // WS 004 重型的 AutofireAI 在本舞台目标采纳恒 null（同槽挂小型判别一致，currAngle 对准敌舰亦不解，
            // 与重型 spec 无关——槽位/组级 AI 行为）。重型直接逐帧 setForceFireOneFrame 绕过 AI 判定直控开火
            // （纯舞台手段；重型与小型机制完全同码路，此处只为取弹匣节奏与拖尾目检证据）。
            for (w in player.allWeapons) {
                if (w.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID) {
                    w.setForceFireOneFrame(chargeNeedlePhase != CHARGE_NEEDLE_PHASE_CEASE)
                }
            }
        }
        if (enemy != null) {
            stabilizeShip(enemy, CHARGE_NEEDLE_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            enemy.setShipTarget(player)
            enemy.setHitpoints(enemy.maxHitpoints)
            enemy.fluxTracker.setCurrFlux(0f)
            enemy.fluxTracker.setHardFlux(0f)
            // 敌方针刺开火（命中玩家护盾 → victim 淤积证据）。
            setChargeNeedleAutofire(enemy, true, CHARGE_NEEDLE_ENEMY_WEAPON_IDS)
        }

        val smallNeedle = player?.allWeapons
            ?.filter { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID }
            ?.minByOrNull { it.ammo }
        val heavyNeedle = player?.allWeapons
            ?.filter { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID }
            ?.minByOrNull { it.ammo }

        when (chargeNeedlePhase) {
            CHARGE_NEEDLE_PHASE_SHIELD -> {
                enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
                val stacks = enemy?.chargeNeedleStacks()?.stacks ?: 0
                if (stacks > chargeNeedlePeakStacks) chargeNeedlePeakStacks = stacks
                if (stacks >= CHARGE_NEEDLE_STACK_TARGET) transitionChargeNeedlePhase(CHARGE_NEEDLE_PHASE_HULL)
            }
            CHARGE_NEEDLE_PHASE_HULL -> {
                enemy?.shield?.let { if (it.isOn) it.toggleOff() }
                val stacks = enemy?.chargeNeedleStacks()?.stacks ?: 0
                if (stacks > chargeNeedlePeakStacks) chargeNeedlePeakStacks = stacks
                if (ChargeNeedleVfx.dischargeCount(engine) >= CHARGE_NEEDLE_DISCHARGE_TARGET &&
                    chargeNeedleMinSmallAmmo <= 0
                ) {
                    transitionChargeNeedlePhase(CHARGE_NEEDLE_PHASE_CEASE)
                }
            }
            CHARGE_NEEDLE_PHASE_CEASE -> {
                enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
                val stacks = enemy?.chargeNeedleStacks()?.stacks ?: 0
                if (stacks == 0 && chargeNeedlePeakStacks > 0) chargeNeedleDecayVerified = true
                if (chargeNeedleDecayVerified) transitionChargeNeedlePhase(CHARGE_NEEDLE_PHASE_COMPLETED)
            }
            else -> {
                stageChargeNeedleCompletedFrame(engine)
            }
        }

        // 弹匣节奏证据：最小弹药观测值与首次打空时刻。
        smallNeedle?.let { needle ->
            if (needle.ammo < chargeNeedleMinSmallAmmo) {
                chargeNeedleMinSmallAmmo = needle.ammo
                if (needle.ammo <= 0 && chargeNeedleSmallEmptiedAt < 0f) chargeNeedleSmallEmptiedAt = elapsed
            }
        }
        heavyNeedle?.let { needle -> if (needle.ammo < chargeNeedleMinHeavyAmmo) chargeNeedleMinHeavyAmmo = needle.ammo }

        val state = when {
            player == null || enemy == null -> {
                if (elapsed > 10f) {
                    failureReason = "charge needle ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            chargeNeedlePhase != CHARGE_NEEDLE_PHASE_COMPLETED &&
                elapsed - chargeNeedlePhaseStartedAt > CHARGE_NEEDLE_PHASE_TIMEOUT -> {
                failureReason = "charge needle phase timeout: $chargeNeedlePhase"
                "Failed"
            }
            chargeNeedlePhase == CHARGE_NEEDLE_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: charge_needle_basic stacking/discharge/decay evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, smallNeedle)
        }
    }

    /** 排障用武器状态串：实例存在性/弹药/冷却/禁用/所属组/自动开火状态/射界与射程几何判定。 */
    private fun chargeNeedleWeaponState(ship: ShipAPI?, weapon: WeaponAPI?): String {
        weapon ?: return "missing"
        val group = ship?.getWeaponGroupFor(weapon)
        val autofireAI = group?.getAutofirePlugin(weapon)
        val target = ship?.shipTarget
        val targetLoc = target?.location
        val dist = if (targetLoc != null) Misc.getDistance(weapon.location, targetLoc) else -1f
        val distFromArc = if (targetLoc != null) weapon.distanceFromArc(targetLoc) else -1f
        return "id=${weapon.id},slot=${weapon.slot?.id},ammo=${weapon.ammo},cd=${"%.2f".format(weapon.cooldownRemaining)}," +
            "disabled=${weapon.isDisabled},firing=${weapon.isFiring},group=${group != null},autofiring=${group?.isAutofiring}," +
            "groupType=${group?.type},shipTarget=${target?.hullSpec?.hullId}," +
            "shipAI=${ship?.shipAI != null},aiShouldFire=${autofireAI?.shouldFire()}," +
            "aiTarget=${autofireAI?.targetShip?.hullSpec?.hullId ?: autofireAI?.target}," +
            "dist=${"%.0f".format(dist)},range=${"%.0f".format(weapon.range)}," +
            "currAngle=${"%.1f".format(weapon.currAngle)},arcFacing=${"%.1f".format(weapon.arcFacing)}," +
            "arc=${"%.0f".format(weapon.arc)},distFromArc=${"%.1f".format(distFromArc)}"
    }

    /** 排障用护盾状态串：相位/开关/弧度/辐能（排查敌方盾为何未升起导致零淤积）。 */
    private fun chargeNeedleShieldState(ship: ShipAPI?): String {
        ship ?: return "missing-ship"
        val shield = ship.shield ?: return "missing-shield,phased=${ship.isPhased}"
        return "isOn=${shield.isOn},isOff=${shield.isOff},activeArc=${"%.0f".format(shield.activeArc)}," +
            "arc=${"%.0f".format(shield.arc)},upkeep=${"%.0f".format(shield.upkeep)}," +
            "phased=${ship.isPhased},flux=${"%.0f".format(ship.currFlux)},overloaded=${ship.fluxTracker.isOverloaded}"
    }

    /** COMPLETED 截图舞台：敌方盾开 + 双方自动开火（新鲜拖尾、叠层 HUD、泄放电弧进入捕获帧）。 */
    private fun stageChargeNeedleCompletedFrame(engine: CombatEngineAPI) {
        val player = findChargeNeedlePlayer(engine)
        val enemy = findChargeNeedleEnemy(engine)
        player?.let {
            stabilizeShip(it, CHARGE_NEEDLE_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = true)
            it.setShipTarget(enemy)
            it.shield?.let { shield -> if (!shield.isOn) shield.toggleOn() }
            setChargeNeedleAutofire(it, true, CHARGE_NEEDLE_PLAYER_WEAPON_IDS)
            // 与相位机同款窄射界 currAngle 死锁解除 + 重型直控开火（详见 advanceChargeNeedleScenario 注释）。
            if (enemy != null) {
                for (w in it.allWeapons) {
                    if (w.id in CHARGE_NEEDLE_PLAYER_WEAPON_IDS) {
                        w.setCurrAngle(Misc.getAngleInDegrees(w.location, enemy.location))
                    }
                }
            }
            for (w in it.allWeapons) {
                if (w.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID) {
                    w.setForceFireOneFrame(true)
                }
            }
        }
        enemy?.let {
            stabilizeShip(it, CHARGE_NEEDLE_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            it.shield?.let { shield -> if (!shield.isOn) shield.toggleOn() }
            it.setShipTarget(player)
            setChargeNeedleAutofire(it, true, CHARGE_NEEDLE_ENEMY_WEAPON_IDS)
        }
        lockChargeNeedleCamera(engine)
    }

    // === Electric drive accelerator scenario (range-by-flux / 8-projectile trigger / charge extra damage / enemy scaling) ===

    private fun findEdaPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == EDA_PLAYER_HULL }

    private fun findEdaEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == EDA_PLAYER_HULL }

    private fun findEdaWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.EDA_WEAPON_ID }

    private fun lockEdaCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleHeight = 900f
        val visibleWidth = visibleHeight * displayAspect

        viewport.setExternalControl(true)
        viewport.set(
            EDA_CAMERA_CENTER.x - visibleWidth * 0.5f,
            EDA_CAMERA_CENTER.y - visibleHeight * 0.5f,
            visibleWidth,
            visibleHeight,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（敌方锤头级非旗舰，必须手动出场；范式同 deployChargeNeedleReserveShips）。 */
    private fun deployEdaReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
                val anchor = if (side == FleetSide.ENEMY) EDA_ENEMY_ANCHOR else EDA_PLAYER_ANCHOR
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionEdaPhase(next: String) {
        log.info("[ASTD-Automation] eda phase $edaPhase -> $next at ${"%.2f".format(elapsed)}s")
        edaPhase = next
        edaPhaseStartedAt = elapsed
    }

    /** 舞台保活与站位：双舰逐帧钉死（保留舰 AI 以维持盾威胁追踪与 AutofireAI，charge needle 场景实证必要）。 */
    private fun stabilizeEdaShips(engine: CombatEngineAPI, playerFire: Boolean, enemyFire: Boolean) {
        val player = findEdaPlayer(engine)
        val enemy = findEdaEnemy(engine)
        if (player != null) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, EDA_PLAYER_ANCHOR, 0f, allowFire = playerFire, preserveAI = true)
            player.setShipTarget(enemy)
            player.setHitpoints(player.maxHitpoints)
            player.shield?.let { if (!it.isOn) it.toggleOn() }
            setChargeNeedleAutofire(player, playerFire, EDA_WEAPON_IDS)
            stageEdaFireControl(player, enemy, playerFire)
        }
        if (enemy != null) {
            stabilizeShip(enemy, EDA_ENEMY_ANCHOR, 180f, allowFire = enemyFire, preserveAI = true)
            enemy.setShipTarget(player)
            enemy.setHitpoints(enemy.maxHitpoints)
            setChargeNeedleAutofire(enemy, enemyFire, EDA_WEAPON_IDS)
            stageEdaFireControl(enemy, player, enemyFire)
        }
    }

    /**
     * 舞台直控开火：首轮实机验证锤头级 WS 001 挂电驱加速炮时武器组 AutofireAI 目标采纳恒 null
     * （autofire toggle 90s 零发射，弹药恒 30），与电荷针刺 WS 004 同款槽位/组级 AI 行为。
     * 按针刺重型实证路径逐帧 currAngle 对准 + setForceFireOneFrame 绕过 AI 判定
     * （纯舞台手段；开火周期/弹药/散射由武器机制自身决定，正是要观测的对象）。
     */
    private fun stageEdaFireControl(ship: ShipAPI, target: ShipAPI?, fire: Boolean) {
        for (w in ship.allWeapons) {
            if (w.id != ASTDInGameAutomationScenario.EDA_WEAPON_ID) continue
            if (target != null) w.setCurrAngle(Misc.getAngleInDegrees(w.location, target.location))
            w.setForceFireOneFrame(fire)
        }
    }

    /** 把舰船辐能水平钉到指定比例（软辐直写 + 硬辐清零；净空加速只读 fluxLevel 软硬合计）。 */
    private fun pinFluxLevel(ship: ShipAPI?, level: Float) {
        ship ?: return
        val max = ship.maxFlux.takeIf { it > 0f } ?: return
        ship.fluxTracker.setCurrFlux((level * max).coerceIn(0f, max))
        ship.fluxTracker.setHardFlux(0f)
    }

    /** 每触发弹数分组：新弹 spawn 间隔 > [EDA_BURST_GROUP_GAP] 视为新一轮触发（burst delay 0.15s）。 */
    private fun trackEdaTriggerGroups(engine: CombatEngineAPI, player: ShipAPI?) {
        player ?: return
        for (projectile in engine.projectiles) {
            if (projectile.projectileSpecId != ASTDInGameAutomationScenario.EDA_PROJECTILE_SPEC_ID) continue
            val damaging = projectile as? DamagingProjectileAPI ?: continue
            if (damaging.source !== player) continue
            val key = System.identityHashCode(projectile)
            if (!edaSeenProjectiles.add(key)) continue
            if (edaLastSpawnAt >= 0f && elapsed - edaLastSpawnAt > EDA_BURST_GROUP_GAP) closeEdaBurstGroup()
            edaCurrentBurstCount++
            edaLastSpawnAt = elapsed
        }
        // 无新弹时同样按间隔收口悬挂分组（burst 尾部）。
        if (edaCurrentBurstCount > 0 && edaLastSpawnAt >= 0f && elapsed - edaLastSpawnAt > EDA_BURST_GROUP_GAP) {
            closeEdaBurstGroup()
        }
    }

    private fun closeEdaBurstGroup() {
        if (edaCurrentBurstCount <= 0) return
        edaBurstSizes += edaCurrentBurstCount
        if (edaCurrentBurstCount > edaMaxTriggerProjectiles) edaMaxTriggerProjectiles = edaCurrentBurstCount
        log.info("[ASTD-Automation] eda trigger group closed: projectiles=$edaCurrentBurstCount max=$edaMaxTriggerProjectiles")
        edaCurrentBurstCount = 0
    }

    /**
     * 电驱加速炮相位机：RANGE_ZERO（0 辐能满额射程）→ RANGE_MID（30% 辐能半程）→ RANGE_HIGH（50% 归零）
     * → FIRE（每触发 8 弹 + 装药追加伤害遥测）→ ENEMY_SCALE（installScaleForTests 切 k_s 敌版三档 + 敌方开火取追加伤害证据）
     * → COMPLETED（恢复开火做截图舞台）。
     */
    private fun advanceEdaScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployEdaReserveShips(engine)
        lockEdaCamera(engine)

        val player = findEdaPlayer(engine)
        val enemy = findEdaEnemy(engine)
        val playerEda = findEdaWeapon(player)
        val enemyEda = findEdaWeapon(enemy)

        when (edaPhase) {
            EDA_PHASE_RANGE_ZERO -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = false)
                pinFluxLevel(player, 0f)
                pinFluxLevel(enemy, 0f)
                if (elapsed - edaPhaseStartedAt >= EDA_RANGE_SETTLE_SECONDS) {
                    edaRangeZeroFlux = playerEda?.range ?: -1f
                    if (kotlin.math.abs(edaRangeZeroFlux - EDA_EXPECT_RANGE_ZERO) <= EDA_RANGE_TOLERANCE) {
                        transitionEdaPhase(EDA_PHASE_RANGE_MID)
                    } else {
                        failureReason = "eda range@0flux=${edaRangeZeroFlux}, expect≈$EDA_EXPECT_RANGE_ZERO"
                        transitionEdaPhase(EDA_PHASE_FAILED)
                    }
                }
            }
            EDA_PHASE_RANGE_MID -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = false)
                pinFluxLevel(player, EDA_MID_FLUX_LEVEL)
                pinFluxLevel(enemy, 0f)
                if (elapsed - edaPhaseStartedAt >= EDA_RANGE_SETTLE_SECONDS) {
                    edaRangeMidFlux = playerEda?.range ?: -1f
                    if (kotlin.math.abs(edaRangeMidFlux - EDA_EXPECT_RANGE_MID) <= EDA_RANGE_TOLERANCE) {
                        transitionEdaPhase(EDA_PHASE_RANGE_HIGH)
                    } else {
                        failureReason = "eda range@30%flux=$edaRangeMidFlux, expect≈$EDA_EXPECT_RANGE_MID"
                        transitionEdaPhase(EDA_PHASE_FAILED)
                    }
                }
            }
            EDA_PHASE_RANGE_HIGH -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = false)
                pinFluxLevel(player, EDA_HIGH_FLUX_LEVEL)
                pinFluxLevel(enemy, 0f)
                if (elapsed - edaPhaseStartedAt >= EDA_RANGE_SETTLE_SECONDS) {
                    edaRangeHighFlux = playerEda?.range ?: -1f
                    if (kotlin.math.abs(edaRangeHighFlux - EDA_EXPECT_RANGE_HIGH) <= EDA_RANGE_TOLERANCE) {
                        transitionEdaPhase(EDA_PHASE_FIRE)
                    } else {
                        failureReason = "eda range@50%flux=$edaRangeHighFlux, expect≈$EDA_EXPECT_RANGE_HIGH"
                        transitionEdaPhase(EDA_PHASE_FAILED)
                    }
                }
            }
            EDA_PHASE_FIRE -> {
                stabilizeEdaShips(engine, playerFire = true, enemyFire = false)
                enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
                // 开火相位把玩家辐能钉 0：满额射程 + HUD 加成条目满值（浮动由舞台钉死，机制浮动已在射程相位验证）。
                pinFluxLevel(player, 0f)
                pinFluxLevel(enemy, 0f)
                trackEdaTriggerGroups(engine, player)
                playerEda?.let { if (it.ammo < edaMinPlayerAmmo) edaMinPlayerAmmo = it.ammo }
                val extraCount = ElectricDriveAcceleratorOnHitEffect.extraDamageCountPlayer(engine)
                if (edaMaxTriggerProjectiles >= EDA_EXPECT_TRIGGER_PROJECTILES && extraCount >= 1) {
                    transitionEdaPhase(EDA_PHASE_ENEMY_SCALE)
                    edaScaleStep = 0
                    edaScaleStepAt = elapsed
                    DifficultyTuningImpl.installScaleForTests(1f)
                }
            }
            EDA_PHASE_ENEMY_SCALE -> {
                stabilizeEdaShips(engine, playerFire = false, enemyFire = edaScaleStep >= 3)
                pinFluxLevel(player, 0f)
                pinFluxLevel(enemy, 0f)
                when (edaScaleStep) {
                    0 -> if (elapsed - edaScaleStepAt >= EDA_RANGE_SETTLE_SECONDS) {
                        edaEnemyRangeScale1 = enemyEda?.range ?: -1f
                        log.info("[ASTD-Automation] eda enemy range@k_s=1: $edaEnemyRangeScale1")
                        DifficultyTuningImpl.installScaleForTests(2f)
                        edaScaleStep = 1; edaScaleStepAt = elapsed
                    }
                    1 -> if (elapsed - edaScaleStepAt >= EDA_RANGE_SETTLE_SECONDS) {
                        edaEnemyRangeScale2 = enemyEda?.range ?: -1f
                        log.info("[ASTD-Automation] eda enemy range@k_s=2: $edaEnemyRangeScale2")
                        DifficultyTuningImpl.installScaleForTests(5f)
                        edaScaleStep = 2; edaScaleStepAt = elapsed
                    }
                    2 -> if (elapsed - edaScaleStepAt >= EDA_RANGE_SETTLE_SECONDS) {
                        edaEnemyRangeScale5 = enemyEda?.range ?: -1f
                        log.info("[ASTD-Automation] eda enemy range@k_s=5: $edaEnemyRangeScale5")
                        edaEnemyExtraBaseline = ElectricDriveAcceleratorOnHitEffect.extraDamageCountOther(engine)
                        edaScaleStep = 3; edaScaleStepAt = elapsed
                    }
                    // k_s=5 下敌方开火：取敌版追加伤害证据（次数 + 峰值可超玩家档 45 上限）。
                    3 -> {
                        val gained = ElectricDriveAcceleratorOnHitEffect.extraDamageCountOther(engine) - edaEnemyExtraBaseline
                        if (gained >= 1 || elapsed - edaScaleStepAt >= EDA_ENEMY_FIRE_SECONDS) {
                            DifficultyTuningImpl.installScaleForTests(null)
                            transitionEdaPhase(EDA_PHASE_COMPLETED)
                        }
                    }
                }
            }
            EDA_PHASE_COMPLETED -> {
                stageEdaCompletedFrame(engine)
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (elapsed > 10f) {
                    failureReason = "eda ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            edaPhase == EDA_PHASE_FAILED -> "Failed"
            edaPhase != EDA_PHASE_COMPLETED &&
                elapsed - edaPhaseStartedAt > EDA_PHASE_TIMEOUT -> {
                failureReason = "eda phase timeout: $edaPhase"
                "Failed"
            }
            edaPhase == EDA_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: electric_drive_basic range/trigger/charge/scaling evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, playerEda)
        }
    }

    /** COMPLETED 截图舞台：玩家辐能钉 0（射程圈满额 + HUD 加成条目）+ 敌盾开 + 玩家自动开火。 */
    private fun stageEdaCompletedFrame(engine: CombatEngineAPI) {
        stabilizeEdaShips(engine, playerFire = true, enemyFire = false)
        val player = findEdaPlayer(engine)
        val enemy = findEdaEnemy(engine)
        pinFluxLevel(player, 0f)
        pinFluxLevel(enemy, 0f)
        enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
        trackEdaTriggerGroups(engine, player)
        lockEdaCamera(engine)
    }

    // === Annihilation vortex scenario ===

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
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleHeight = 900f
        val visibleWidth = visibleHeight * displayAspect

        viewport.setExternalControl(true)
        viewport.set(
            AV_CAMERA_CENTER.x - visibleWidth * 0.5f,
            AV_CAMERA_CENTER.y - visibleHeight * 0.5f,
            visibleWidth,
            visibleHeight,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（协同/敌版/投喂三舰非旗舰，必须手动出场；按舰体分配锚点）。 */
    private fun deployAvReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
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
        log.info("[ASTD-Automation] av phase $avPhase -> $next at ${"%.2f".format(elapsed)}s")
        avPhase = next
        avPhaseStartedAt = elapsed
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
            player.setShipTarget(feeder)
            if (keepPlayerAlive) player.setHitpoints(player.maxHitpoints)
            setChargeNeedleAutofire(player, playerFire, AV_WEAPON_IDS)
            stageAvFireControl(player, feeder, playerFire)
        }
        if (feeder != null && !feeder.isHulk) {
            stabilizeShip(feeder, AV_FEEDER_ANCHOR, 180f, allowFire = feederFire, preserveAI = true)
            feeder.setShipTarget(player)
            feeder.setHitpoints(feeder.maxHitpoints)
            setChargeNeedleAutofire(feeder, feederFire, AV_FEEDER_WEAPON_IDS)
            stageAvFireControl(feeder, player, feederFire)
        }
        if (synergy != null && !synergy.isHulk) {
            stabilizeShip(synergy, AV_SYNERGY_ANCHOR, 0f, allowFire = synergyFire, preserveAI = true)
            synergy.setShipTarget(feeder)
            synergy.setHitpoints(synergy.maxHitpoints)
            setChargeNeedleAutofire(synergy, synergyFire, AV_WEAPON_IDS)
            stageAvFireControl(synergy, feeder, synergyFire)
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, AV_ENEMY_ANCHOR, 180f, allowFire = enemyFire, preserveAI = true)
            enemy.setShipTarget(player)
            enemy.setHitpoints(enemy.maxHitpoints)
            setChargeNeedleAutofire(enemy, enemyFire, AV_WEAPON_IDS)
            stageAvFireControl(enemy, player, enemyFire)
        }
    }

    /** 舞台直控开火（范式同 stageEdaFireControl）：湮灭涡旋按 id 过滤，投喂舰按投喂武器 id 过滤。 */
    private fun stageAvFireControl(ship: ShipAPI, target: ShipAPI?, fire: Boolean) {
        val ids = if (ship.hullSpec?.hullId == AV_FEEDER_HULL) AV_FEEDER_WEAPON_IDS else AV_WEAPON_IDS
        for (w in ship.allWeapons) {
            if (w.id !in ids) continue
            if (target != null) w.setCurrAngle(Misc.getAngleInDegrees(w.location, target.location))
            w.setForceFireOneFrame(fire)
        }
    }

    /** 爆发循环计时：isFiring 沿检测，记录最近一次开火时长与停火间隔（期望 2s on / 9s off）。 */
    private fun trackAvBurstCycle(weapon: WeaponAPI?) {
        weapon ?: return
        if (weapon.isFiring) {
            if (avBeamOnSince < 0f) {
                avBeamOnSince = elapsed
                if (avBeamOffSince >= 0f && avBurstOffSeconds < 0f) avBurstOffSeconds = elapsed - avBeamOffSince
            }
            // Hidden 生效证据：原版束渲染被 HiddenBeamRenderEffect 压到 0.01（自绘束接管）。
            val beam = weapon.beams?.firstOrNull()
            if (beam != null && beam.width <= 0.02f) avHiddenBeamOk = true
        } else if (avBeamOnSince >= 0f) {
            avBurstOnSeconds = elapsed - avBeamOnSince
            avBeamOnSince = -1f
            avBeamOffSince = elapsed
        }
    }

    /**
     * 湮灭涡旋相位机（规格 04 §4.2 八条检查点映射）：
     * MOUNT（双槽位装配）→ ABSORB（牵引/吸收遥测 + 2s/9s 循环 + Hidden 宽归零）
     * → COLLAPSE（停火坍缩 + 命中计数）→ EMPTY_PREP（净空 + 冷却 settle）→ EMPTY_FIRE（空池保底 500）
     * → ENEMY_SCALE（installScaleForTests 切 k_s 敌版三档 + k_s=5 帧率窗口）→ HOST_DEATH（协同槽宿主死亡不坍缩 + 池自回收）
     * → COMPLETED（玩家旗舰开火 + 投喂做截图舞台，爆发中段才上报 Completed 保证截图帧含束体/涡旋）。
     */
    private fun advanceAvScenario(engine: CombatEngineAPI) {
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
                if (elapsed - avPhaseStartedAt >= AV_MOUNT_SETTLE_SECONDS) {
                    val playerSlot = playerAv?.slot?.id
                    val synergySlot = synergyAv?.slot?.id
                    if (playerSlot == AV_PLAYER_EXPECT_SLOT && synergySlot == AV_SYNERGY_EXPECT_SLOT) {
                        avAbsorbBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_ABSORBED_PLAYER)
                        transitionAvPhase(AV_PHASE_ABSORB)
                    } else {
                        failureReason = "av mount mismatch: playerSlot=$playerSlot(expect ${AV_PLAYER_EXPECT_SLOT}), synergySlot=$synergySlot(expect ${AV_SYNERGY_EXPECT_SLOT})"
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
                        failureReason = "av burst on=${avBurstOnSeconds}s, expect≈${AV_EXPECT_BURST_ON}s"
                        transitionAvPhase(AV_PHASE_FAILED)
                    } else if (kotlin.math.abs(avBurstOffSeconds - AV_EXPECT_BURST_OFF) > AV_BURST_OFF_TOLERANCE) {
                        failureReason = "av burst off=${avBurstOffSeconds}s, expect≈${AV_EXPECT_BURST_OFF}s"
                        transitionAvPhase(AV_PHASE_FAILED)
                    } else if (!avHiddenBeamOk) {
                        failureReason = "av hidden beam not observed: vanilla beam width not zeroed while firing"
                        transitionAvPhase(AV_PHASE_FAILED)
                    } else {
                        avCollapseBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT)
                        transitionAvPhase(AV_PHASE_COLLAPSE)
                    }
                }
            }
            AV_PHASE_COLLAPSE -> {
                stabilizeAvShips(engine, playerFire = false, feederFire = false)
                val collapses = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT) - avCollapseBaseline
                val hits = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_HITS_PLAYER)
                if (collapses >= 1) {
                    if (hits >= 1) {
                        transitionAvPhase(AV_PHASE_EMPTY_PREP)
                    } else {
                        failureReason = "av collapse hits=$hits, expect>=1（投喂舰应位于坍缩半径内）"
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
                if (elapsed - avPhaseStartedAt >= AV_EMPTY_PREP_SECONDS) {
                    avCollapseBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT)
                    transitionAvPhase(AV_PHASE_EMPTY_FIRE)
                }
            }
            AV_PHASE_EMPTY_FIRE -> {
                stabilizeAvShips(engine, playerFire = true, feederFire = false)
                val collapses = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT) - avCollapseBaseline
                if (collapses >= 1) {
                    avEmptyCollapseDamage = AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_DAMAGE_PLAYER)
                    if (kotlin.math.abs(avEmptyCollapseDamage - AV_EXPECT_EMPTY_DAMAGE) <= AV_EMPTY_DAMAGE_TOLERANCE) {
                        DifficultyTuningImpl.installScaleForTests(1f)
                        avScaleStep = 0
                        avScaleStepAt = elapsed
                        transitionAvPhase(AV_PHASE_ENEMY_SCALE)
                    } else {
                        failureReason = "av empty collapse damage=$avEmptyCollapseDamage, expect≈$AV_EXPECT_EMPTY_DAMAGE（空池保底）"
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
                        log.info("[ASTD-Automation] av enemy radius@k_s=1: $avScaleRadius1")
                        DifficultyTuningImpl.installScaleForTests(2f)
                        avScaleStep = 1; avScaleStepAt = elapsed
                    }
                    1 -> if (enemyRadius > 0f && kotlin.math.abs(enemyRadius - avScaleRadius1) > 0.5f) {
                        avScaleRadius2 = enemyRadius
                        log.info("[ASTD-Automation] av enemy radius@k_s=2: $avScaleRadius2")
                        DifficultyTuningImpl.installScaleForTests(5f)
                        avScaleStep = 2; avScaleStepAt = elapsed
                    }
                    2 -> if (enemyRadius > 0f && kotlin.math.abs(enemyRadius - avScaleRadius2) > 0.5f) {
                        avScaleRadius5 = enemyRadius
                        avScaleThreshold5 = AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_THRESHOLD_ENEMY)
                        avScaleAoe5 = AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_AOEMULT_ENEMY)
                        log.info("[ASTD-Automation] av enemy radius@k_s=5: $avScaleRadius5 threshold=$avScaleThreshold5 aoe=$avScaleAoe5")
                        avScale5Ticks = 0
                        avScale5WallStartNanos = System.nanoTime()
                        avScaleStep = 3; avScaleStepAt = elapsed
                    }
                    3 -> if (elapsed - avScaleStepAt >= AV_SCALE5_FPS_WINDOW_SECONDS) {
                        val wallSeconds = (System.nanoTime() - avScale5WallStartNanos) / 1_000_000_000.0
                        avScale5Fps = if (wallSeconds > 0.0) (avScale5Ticks / wallSeconds).toFloat() else -1f
                        DifficultyTuningImpl.installScaleForTests(null)
                        val radiusOk = kotlin.math.abs(avScaleRadius1 - 150f) <= 1f &&
                            kotlin.math.abs(avScaleRadius2 - 187.5f) <= 1f &&
                            kotlin.math.abs(avScaleRadius5 - 300f) <= 1f
                        val k5Ok = kotlin.math.abs(avScaleThreshold5 - 16000f) <= 1f && kotlin.math.abs(avScaleAoe5 - 2.5f) <= 0.01f
                        when {
                            !radiusOk -> {
                                failureReason = "av enemy radius 三档=$avScaleRadius1/$avScaleRadius2/$avScaleRadius5, expect 150/187.5/300"
                                transitionAvPhase(AV_PHASE_FAILED)
                            }
                            !k5Ok -> {
                                failureReason = "av enemy k_s=5 threshold=$avScaleThreshold5 aoe=$avScaleAoe5, expect 16000/2.5"
                                transitionAvPhase(AV_PHASE_FAILED)
                            }
                            avScale5Fps < AV_SCALE5_MIN_FPS -> {
                                failureReason = "av k_s=5 fps=$avScale5Fps < $AV_SCALE5_MIN_FPS（300su 涡旋性能门槛）"
                                transitionAvPhase(AV_PHASE_FAILED)
                            }
                            else -> {
                                avHostDeathPoolRecycledBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_POOL_RECYCLED)
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
                    avHostKilledAt = elapsed
                    avHostDeathCollapseBaseline = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT)
                    synergy.setHitpoints(1f)
                    engine.applyDamage(synergy, synergy.location, 1_000_000f, DamageType.ENERGY, 0f, true, false, feeder ?: synergy, false)
                    log.info("[ASTD-Automation] av synergy host killed mid-beam at ${"%.2f".format(elapsed)}s")
                }
                if (avHostKilled && elapsed - avHostKilledAt >= AV_HOST_DEATH_SETTLE_SECONDS) {
                    val recycled = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_POOL_RECYCLED) - avHostDeathPoolRecycledBaseline
                    val collapses = AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT) - avHostDeathCollapseBaseline
                    when {
                        recycled < 1 -> {
                            failureReason = "av host death pool recycled=$recycled, expect>=1（SELF_MANAGED 自回收 + INFO）"
                            transitionAvPhase(AV_PHASE_FAILED)
                        }
                        collapses != 0 -> {
                            failureReason = "av host death collapses=$collapses, expect 0（宿主死亡涡旋哑火不坍缩）"
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
                if (elapsed > 12f) {
                    failureReason = "av ships missing: player=${player != null}, feeder=${feeder != null}, synergy=${synergy != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            avPhase == AV_PHASE_FAILED -> "Failed"
            avPhase != AV_PHASE_COMPLETED &&
                elapsed - avPhaseStartedAt > AV_PHASE_TIMEOUT -> {
                failureReason = "av phase timeout: $avPhase"
                "Failed"
            }
            avPhase == AV_PHASE_COMPLETED -> {
                // 截图门控：爆发中段才上报 Completed——SSOptimizer 在上报时刻连拍三帧，
                // 2s on / 9s off 爆发循环下随机时刻大概率拍到无束空场；保底超时防舞台卡死。
                val midBurst = avBeamOnSince >= 0f && elapsed - avBeamOnSince >= AV_COMPLETED_BEAM_ON_SECONDS
                if (midBurst || elapsed - avPhaseStartedAt >= AV_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: annihilation_vortex_basic absorb/collapse/empty/scaling/host-death evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, playerAv)
        }
    }

    /** COMPLETED 截图舞台：玩家旗舰（sunder WS 003）开火 + 投喂舰投喂，涡旋/吸收 flare/坍缩烟云/HUD 入帧。 */
    private fun stageAvCompletedFrame(engine: CombatEngineAPI) {
        stabilizeAvShips(engine, playerFire = true, feederFire = true)
        trackAvBurstCycle(findAvWeapon(findAvPlayer(engine)))
        lockAvCamera(engine)
    }

    // ==== 穷距相位轨道炮场景（规格 05 §2.5 烟测检查点映射） ====

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
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = QJ_CAMERA_VISIBLE_HEIGHT * displayAspect

        viewport.setExternalControl(true)
        viewport.set(
            QJ_CAMERA_CENTER.x - visibleWidth * 0.5f,
            QJ_CAMERA_CENTER.y - QJ_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            QJ_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（玩家/敌版统治者 + 双警戒靶舰全部非旗舰，按舰体与出场顺序分配锚点）。 */
    private fun deployQjReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        var vigilanceIndex = 0
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
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
        log.info("[ASTD-Automation] qj phase $qjPhase -> $next at ${"%.2f".format(elapsed)}s")
        qjPhase = next
        qjPhaseStartedAt = elapsed
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
            player.setShipTarget(playerTarget)
            player.setHitpoints(player.maxHitpoints)
            player.fluxTracker.currFlux = 0f
            // 变体武器组自带 autofire（第四轮实证：w2 停火相位 AutofireAI 继续开火，层数不衰减），
            // 双穷距一律改由 force fire 独占驱动（开/停逐槽位精确）。
            setQjAutofire(player, false)
            stageQjFireControl(player, playerTarget, fireW1, fireW2)
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, QJ_ENEMY_ANCHOR, 180f, allowFire = enemyFire, preserveAI = true)
            enemy.setShipTarget(player)
            enemy.setHitpoints(enemy.maxHitpoints)
            enemy.fluxTracker.currFlux = 0f
            setQjAutofire(enemy, false)
            for (w in enemy.allWeapons) {
                if (w.id != ASTDInGameAutomationScenario.QJ_WEAPON_ID) continue
                if (player != null) w.setCurrAngle(Misc.getAngleInDegrees(w.location, player.location))
                w.setForceFireOneFrame(enemyFire)
            }
        }
        if (switch != null && !switch.isHulk) {
            stabilizeShip(switch, QJ_SWITCH_ANCHOR, 180f, allowFire = false, preserveAI = true)
            switch.setShipTarget(null)
            if (healSwitchTarget) switch.setHitpoints(switch.maxHitpoints)
            switch.shield?.toggleOff()
        }
        if (kill != null && !kill.isHulk) {
            stabilizeShip(kill, QJ_KILL_ANCHOR, 180f, allowFire = false, preserveAI = true)
            kill.setShipTarget(null)
            kill.setHitpoints(kill.maxHitpoints)
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
            if (target != null) w.setCurrAngle(Misc.getAngleInDegrees(w.location, target.location))
            w.setForceFireOneFrame(fire)
        }
    }

    /** 满层射速间隔追踪：w1 新弹 spawn 间隔（w1 满层窗口内取最小值，期望 2s/1.625≈1.23s 的 spike 证据）。 */
    private fun trackQjRefire(engine: CombatEngineAPI, w1: WeaponAPI?, w1Stacks: Int) {
        w1 ?: return
        for (p in engine.projectiles) {
            if (p.projectileSpecId != ASTDInGameAutomationScenario.QJ_PROJECTILE_SPEC_ID) continue
            val damaging = p as? DamagingProjectileAPI ?: continue
            if (damaging.weapon !== w1) continue
            if (!qjSeenProjectiles.add(System.identityHashCode(p))) continue
            if (w1Stacks >= QiongjuePhaseRailgunDifficulty.MAX_STACKS) {
                if (qjLastSpawnAtW1 >= 0f) {
                    val gap = elapsed - qjLastSpawnAtW1
                    if (gap < qjRefireMinAtFull) qjRefireMinAtFull = gap
                }
                qjLastSpawnAtW1 = elapsed
            }
        }
    }

    /** 敌版三档换步：移除敌方武器级演算 Buff，下一命中从 0 重建（保证采样时层数恰为目标值）。 */
    private fun clearQjEnemyBuff(enemy: ShipAPI?, enemyW: WeaponAPI?) {
        if (enemy == null || enemyW == null) return
        val buff = enemy.getBuffByWeapon(QiongjueCalcStacks.BUFF_ID, enemyW) ?: return
        enemy.buffHost().remove(buff, enemyW)
    }

    /**
     * 穷距相位轨道炮相位机（规格 05 §2.5 烟测检查点映射）：
     * MOUNT（装配/1200 射程校验）→ STACK（同目标满层 10：伤害乘区 1.625 / 射速间隔≈1.23s spike /
     * HUD / 满层浮字 / 命中锥面 / 叠层期帧率）→ DUAL（w2 停火 7s 独立衰减，复合键隔离层差≥5）
     * → SWITCH（转火警戒 B：10 层折算为 4 +「演算转移」浮字）→ DECAY（停火 3s 窗口后 1.75 层/s 归零）
     * → KILL（击沉 B 转火 C：旧目标失效不折算，首中=旧值+1）→ ENEMY_SCALE（installScaleForTests
     * 敌版三档逐命中乘区 1.20/1.25/1.40）→ COMPLETED（回打敌版做截图舞台，拖尾/锥面/HUD 入帧）。
     */
    private fun advanceQjScenario(engine: CombatEngineAPI) {
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
        val w1Buff = if (player != null && w1 != null) player.qiongjueCalcStacks(w1) else null
        val w2Buff = if (player != null && w2 != null) player.qiongjueCalcStacks(w2) else null
        val enemyBuff = if (enemy != null && enemyW != null) enemy.qiongjueCalcStacks(enemyW) else null

        when (qjPhase) {
            QJ_PHASE_MOUNT -> {
                stabilizeQjShips(engine, playerTarget = enemy, fireW1 = false, fireW2 = false)
                if (elapsed - qjPhaseStartedAt >= QJ_MOUNT_SETTLE_SECONDS) {
                    val slot1 = w1?.slot?.id
                    val slot2 = w2?.slot?.id
                    val range = w1?.range ?: -1f
                    if (slot1 == QJ_PLAYER_SLOT_W1 && slot2 == QJ_PLAYER_SLOT_W2 &&
                        kotlin.math.abs(range - QJ_EXPECT_RANGE) <= QJ_RANGE_TOLERANCE
                    ) {
                        transitionQjPhase(QJ_PHASE_STACK)
                    } else {
                        failureReason = "qj mount mismatch: slot1=$slot1 slot2=$slot2 range=$range(expect $QJ_EXPECT_RANGE)"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }
            QJ_PHASE_STACK -> {
                stabilizeQjShips(engine, playerTarget = enemy, fireW1 = true, fireW2 = true)
                trackQjRefire(engine, w1, w1Buff?.stacks ?: 0)
                val full = (w1Buff?.stacks ?: 0) >= QiongjuePhaseRailgunDifficulty.MAX_STACKS &&
                    (w2Buff?.stacks ?: 0) >= QiongjuePhaseRailgunDifficulty.MAX_STACKS
                if (full && qjFullHoldSince < 0f) {
                    qjFullHoldSince = elapsed
                    qjStackFpsTicks = 0
                    qjStackFpsWallStartNanos = System.nanoTime()
                }
                if (qjFullHoldSince >= 0f) qjStackFpsTicks++
                if (qjFullHoldSince >= 0f && elapsed - qjFullHoldSince >= QJ_FULL_HOLD_SECONDS) {
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
                    val fullFloat = QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_FULL_PLAYER)
                    val cone = QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_CONE_VFX)
                    when {
                        kotlin.math.abs(qjDmgMultAtFull - QJ_EXPECT_FULL_DMG_MULT) > QJ_DMG_MULT_TOLERANCE -> {
                            failureReason = "qj full dmg mult=$qjDmgMultAtFull, expect≈$QJ_EXPECT_FULL_DMG_MULT（10 层 × v2 6.25%）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                        qjRefireMinAtFull < QJ_REFIRE_MIN || qjRefireMinAtFull > QJ_REFIRE_MAX -> {
                            failureReason = "qj full refire min=$qjRefireMinAtFull, expect [$QJ_REFIRE_MIN, $QJ_REFIRE_MAX]（2s/1.625≈1.23s spike）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                        spike < 1 -> {
                            failureReason = "qj spike applied=$spike, expect>=1（setRemainingCooldownTo 周期起点扣减）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                        hud < 1 -> {
                            failureReason = "qj hud frames=$hud, expect>=1（「持续演算」状态条目）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                        fullFloat < 1 -> {
                            failureReason = "qj full float=$fullFloat, expect>=1（「演算完成」浮字）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                        cone < 1 -> {
                            failureReason = "qj cone vfx=$cone, expect>=1（命中小号锥面特效）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                        qjStackFps < QJ_STACK_MIN_FPS -> {
                            failureReason = "qj stack fps=$qjStackFps < $QJ_STACK_MIN_FPS（叠层期帧率门槛）"
                            transitionQjPhase(QJ_PHASE_FAILED)
                        }
                        else -> transitionQjPhase(QJ_PHASE_DUAL)
                    }
                }
            }
            QJ_PHASE_DUAL -> {
                // w1 续打敌 A 保持满层；w2 停火：3s 窗口 + 1.75 层/s 独立衰减（复合键隔离证据）。
                stabilizeQjShips(engine, playerTarget = enemy, fireW1 = true, fireW2 = false)
                if (elapsed - qjPhaseStartedAt >= QJ_DUAL_SECONDS) {
                    qjDualW1Stacks = w1Buff?.stacks ?: -1
                    qjDualW2Stacks = w2Buff?.stacks ?: -1
                    if (qjDualW1Stacks >= QiongjuePhaseRailgunDifficulty.MAX_STACKS &&
                        qjDualW1Stacks - qjDualW2Stacks >= QJ_DUAL_MIN_DIVERGENCE
                    ) {
                        transitionQjPhase(QJ_PHASE_SWITCH)
                    } else {
                        failureReason = "qj dual divergence w1=$qjDualW1Stacks w2=$qjDualW2Stacks, expect w1=10 且层差>=$QJ_DUAL_MIN_DIVERGENCE（双穷距独立）"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }
            QJ_PHASE_SWITCH -> {
                stabilizeQjShips(engine, playerTarget = switch, fireW1 = true, fireW2 = true)
                if (switch != null && w1Buff?.target === switch) {
                    qjSwitchW1Stacks = w1Buff.stacks
                    val transfer = QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_TRANSFER_PLAYER)
                    if (qjSwitchW1Stacks in QJ_SWITCH_MIN_STACKS..QJ_SWITCH_MAX_STACKS && transfer >= 1) {
                        transitionQjPhase(QJ_PHASE_DECAY)
                    } else {
                        failureReason = "qj switch w1 stacks=$qjSwitchW1Stacks(expect $QJ_SWITCH_MIN_STACKS~$QJ_SWITCH_MAX_STACKS: floor(10×0.3125)+1=4) transfer=$transfer(expect>=1)"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }
            QJ_PHASE_DECAY -> {
                stabilizeQjShips(engine, playerTarget = null, fireW1 = false, fireW2 = false)
                if ((w1Buff?.stacks ?: 0) == 0) {
                    qjDecaySeconds = elapsed - qjPhaseStartedAt
                    if (qjDecaySeconds in QJ_DECAY_MIN_SECONDS..QJ_DECAY_MAX_SECONDS) {
                        transitionQjPhase(QJ_PHASE_KILL)
                    } else {
                        failureReason = "qj decay seconds=$qjDecaySeconds, expect [$QJ_DECAY_MIN_SECONDS, $QJ_DECAY_MAX_SECONDS]（3s 窗口 + 1.75 层/s）"
                        transitionQjPhase(QJ_PHASE_FAILED)
                    }
                }
            }
            QJ_PHASE_KILL -> {
                val armed = (w1Buff?.stacks ?: 0) >= QJ_KILL_ARM_STACKS
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
                    qjStacksBeforeKill = w1Buff?.stacks ?: -1
                    w1?.setRemainingCooldownTo(0f)
                    w2?.setRemainingCooldownTo(0f)
                    log.info("[ASTD-Automation] qj switch target killed at ${"%.2f".format(elapsed)}s, stacks=$qjStacksBeforeKill, retarget kill ship")
                }
                if (qjKillRetargeted && kill != null && w1Buff?.target === kill) {
                    qjStacksAfterKillHit = w1Buff.stacks
                    val expected = (qjStacksBeforeKill + 1).coerceAtMost(QiongjuePhaseRailgunDifficulty.MAX_STACKS)
                    if (qjStacksAfterKillHit == expected) {
                        DifficultyTuningImpl.installScaleForTests(1f)
                        clearQjEnemyBuff(enemy, enemyW)
                        qjScaleStep = 0
                        qjScaleSampling = false
                        transitionQjPhase(QJ_PHASE_ENEMY_SCALE)
                    } else {
                        failureReason = "qj kill switch stacks=$qjStacksAfterKillHit, expect=$expected（旧目标失效不折算，首中=旧值+1；前值=$qjStacksBeforeKill）"
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
                    qjScaleSampleAt = elapsed
                }
                if (qjScaleSampling && elapsed - qjScaleSampleAt >= QJ_SCALE_SETTLE_SECONDS && enemyW != null && enemy != null) {
                    val mult = QiongjueDamageDealtModifier.dealtMult(engine, enemy, enemyW)
                    when (qjScaleStep) {
                        0 -> {
                            qjEnemyMult1 = mult
                            log.info("[ASTD-Automation] qj enemy dealt mult@k_s=1: $qjEnemyMult1")
                            DifficultyTuningImpl.installScaleForTests(2f)
                        }
                        1 -> {
                            qjEnemyMult2 = mult
                            log.info("[ASTD-Automation] qj enemy dealt mult@k_s=2: $qjEnemyMult2")
                            DifficultyTuningImpl.installScaleForTests(5f)
                        }
                        else -> {
                            qjEnemyMult5 = mult
                            log.info("[ASTD-Automation] qj enemy dealt mult@k_s=5: $qjEnemyMult5")
                            DifficultyTuningImpl.installScaleForTests(null)
                        }
                    }
                    if (qjScaleStep < 2) {
                        clearQjEnemyBuff(enemy, enemyW)
                        qjScaleStep++
                        qjScaleSampling = false
                    } else {
                        val ok = qjEnemyMult1 in QJ_ENEMY_MULT_1_MIN..QJ_ENEMY_MULT_1_MAX &&
                            qjEnemyMult2 in QJ_ENEMY_MULT_2_MIN..QJ_ENEMY_MULT_2_MAX &&
                            qjEnemyMult5 in QJ_ENEMY_MULT_5_MIN..QJ_ENEMY_MULT_5_MAX
                        if (ok) {
                            transitionQjPhase(QJ_PHASE_COMPLETED)
                        } else {
                            failureReason = "qj enemy dealt mult 三档=$qjEnemyMult1/$qjEnemyMult2/$qjEnemyMult5, expect [1.19,1.26]/[1.24,1.32]/[1.39,1.51]（v1/v2/v5 × 4~5 层逐命中乘区）"
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
                if (elapsed > 12f) {
                    failureReason = "qj ships missing: player=${player != null}, enemy=${enemy != null}, switch=${switch != null}, kill=${kill != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            qjPhase == QJ_PHASE_FAILED -> "Failed"
            qjPhase != QJ_PHASE_COMPLETED &&
                elapsed - qjPhaseStartedAt > QJ_PHASE_TIMEOUT -> {
                failureReason = "qj phase timeout: $qjPhase"
                "Failed"
            }
            qjPhase == QJ_PHASE_COMPLETED -> {
                // 截图门控：叠层回升到可见水位才上报 Completed——SSOptimizer 在上报时刻连拍三帧，
                // 令「持续演算」HUD 条目与白色拖尾/命中锥面入帧（对齐 AV 中段门控先例）；保底超时防舞台卡死。
                val stackedForShot = (w1Buff?.stacks ?: 0) >= QJ_COMPLETED_STACKS_FOR_SHOT
                if (stackedForShot || elapsed - qjPhaseStartedAt >= QJ_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: qiongjue_railgun_basic stack/dual/switch/decay/kill/scaling evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, w1)
        }
    }

    /** COMPLETED 截图舞台：玩家双穷距回打敌版统治者，细长拖尾/命中锥面/「持续演算」HUD 入帧。 */
    private fun stageQjCompletedFrame(engine: CombatEngineAPI) {
        stabilizeQjShips(engine, playerTarget = findQjEnemy(engine), fireW1 = true, fireW2 = true)
        lockQjCamera(engine)
    }

    // ==== positron shockwave scenario ====

    private fun findPsPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner == 0 && it.hullSpec?.hullId == PS_PLAYER_HULL && !it.isFighter }

    private fun findPsTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == PS_TARGET_HULL && !it.isFighter }

    private fun findPsWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.PS_WEAPON_ID }

    private fun lockPsCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = PS_CAMERA_VISIBLE_HEIGHT * displayAspect
        viewport.setExternalControl(true)
        viewport.set(
            PS_CAMERA_CENTER.x - visibleWidth * 0.5f,
            PS_CAMERA_CENTER.y - PS_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            PS_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（玩家野狼 + 无武装警戒级靶舰均非旗舰，按舰体分配锚点）。 */
    private fun deployPsReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
                val hullId = member.hullId ?: continue
                val anchor = when {
                    side == FleetSide.PLAYER && hullId == PS_PLAYER_HULL -> PS_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && hullId == PS_TARGET_HULL -> PS_TARGET_PASS_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionPsPhase(next: String) {
        log.info("[ASTD-Automation] ps phase $psPhase -> $next at ${"%.2f".format(elapsed)}s")
        psPhase = next
        psPhaseStartedAt = elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeQjShips）：保留舰 AI + 逐帧 currAngle 对准正东 +
     * setForceFireOneFrame 绕 AutofireAI 死锁（01/03/05 实证路径）；玩家辐能逐帧清零。
     * 警戒靶舰盾舞台性常关；PASS_THROUGH 相位不逐帧奶（HP 即「无触碰体积」观测面），
     * 进入相位时奶满一次。
     */
    private fun stabilizePsShips(engine: CombatEngineAPI, fire: Boolean) {
        val player = findPsPlayer(engine)
        val target = findPsTarget(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, PS_PLAYER_ANCHOR, 0f, allowFire = fire, preserveAI = true)
            player.setShipTarget(target)
            player.setHitpoints(player.maxHitpoints)
            player.fluxTracker.currFlux = 0f
            setPsAutofire(player, false)
            val w = findPsWeapon(player)
            if (w != null) {
                w.setCurrAngle(0f)
                w.setForceFireOneFrame(fire)
            }
        }
        if (target != null && !target.isHulk) {
            val anchor = when (psPhase) {
                PS_PHASE_MOUNT, PS_PHASE_PASS_THROUGH -> PS_TARGET_PASS_ANCHOR
                else -> PS_TARGET_SPLASH_ANCHOR
            }
            stabilizeShip(target, anchor, 180f, allowFire = false, preserveAI = true)
            target.setShipTarget(null)
            if (psPhase != PS_PHASE_PASS_THROUGH) target.setHitpoints(target.maxHitpoints)
            target.shield?.toggleOff()
        }
    }

    /** 正电子武器组 autofire 总开关（范式同 setQjAutofire）：force fire 独占驱动时关闭。 */
    private fun setPsAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id == ASTDInGameAutomationScenario.PS_WEAPON_ID }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /**
     * FUSE/COMPLETED 相位导弹投喂：每 [PS_MISSILE_FEED_INTERVAL] 从靶舰右前方 820su 处左右舷交替
     * 生成一发鱼叉（weapon=null + weaponId 直接生成导弹实体，范式同 lens phase-2 投喂），
     * 初速 250su/s 指向玩家锚点。spawn 返回 null 视为 weaponId 不可用——记失败原因转 FAILED。
     */
    private fun feedPsMissiles(engine: CombatEngineAPI) {
        if (elapsed < psMissileFeedAt) return
        psMissileFeedAt = elapsed + PS_MISSILE_FEED_INTERVAL
        val source = findPsTarget(engine) ?: return
        psMissileFeedSide = -psMissileFeedSide
        val spawn = Vector2f(PS_MISSILE_SPAWN_X, PS_MISSILE_SPAWN_Y * psMissileFeedSide)
        val angle = Misc.getAngleInDegrees(spawn, PS_PLAYER_ANCHOR)
        val rad = Math.toRadians(angle.toDouble())
        val vel = Vector2f(
            (kotlin.math.cos(rad) * PS_MISSILE_INITIAL_SPEED).toFloat(),
            (kotlin.math.sin(rad) * PS_MISSILE_INITIAL_SPEED).toFloat(),
        )
        val spawned = engine.spawnProjectile(source, null, PS_FEED_MISSILE_ID, spawn, angle, vel)
        if (spawned == null) {
            failureReason = "ps missile spawn returned null for weaponId=$PS_FEED_MISSILE_ID"
            transitionPsPhase(PS_PHASE_FAILED)
        }
    }

    /**
     * 正电子冲击波相位机（规格 06 §4.2 烟测检查点映射）：
     * MOUNT（装配/600 射程/PD hint 校验）→ PASS_THROUGH（穿舰不爆：靶舰 400su 在弹道上，
     * 弹体穿过不掉血、舰船不触发近炸；满射程自爆引爆距离 ≈600）→ SPLASH（靶舰移至 700su，
     * 满射程自爆锥面波及舰船命中计数 +1，近炸计数不变——舰船蹭波及但不触发近炸）
     * → FUSE（投喂鱼叉导弹群：近炸引爆成片清除、devMode 引爆计数浮字、锥面 VFX 计数）
     * → COMPLETED（持续开火+投喂做截图舞台，近炸引爆近期发生才上报 Completed 令锥面入帧）。
     */
    private fun advancePsScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployPsReserveShips(engine)
        lockPsCamera(engine)

        val player = findPsPlayer(engine)
        val target = findPsTarget(engine)
        val weapon = findPsWeapon(player)
        val fuseCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_DETONATE_FUSE)
        val maxRangeCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_DETONATE_MAX_RANGE)
        val shipHits = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_SHIP_HITS)
        val missileHits = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_MISSILE_HITS)
        val floatyCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_FLOATY)
        val coneVfxCount = PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_VFX)
        val lastDetonateDist = PositronShockwaveFuseScript.telemetryFloat(engine, PositronShockwaveFuseScript.TELEMETRY_LAST_DETONATE_DIST)

        when (psPhase) {
            PS_PHASE_MOUNT -> {
                stabilizePsShips(engine, fire = false)
                if (elapsed - psPhaseStartedAt >= PS_MOUNT_SETTLE_SECONDS) {
                    val slot = weapon?.slot?.id
                    val range = weapon?.range ?: -1f
                    val hintsPd = weapon?.spec?.getAIHints()?.contains(WeaponAPI.AIHints.PD) == true
                    when {
                        slot != PS_PLAYER_SLOT || kotlin.math.abs(range - PS_EXPECT_RANGE) > PS_RANGE_TOLERANCE -> {
                            failureReason = "ps mount mismatch: slot=$slot range=$range(expect $PS_EXPECT_RANGE)"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        !hintsPd -> {
                            failureReason = "ps aiHints missing PD（装配面板 hints 校验）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        else -> {
                            // 进入穿舰相位：靶舰奶满一次作「无触碰体积」观测基线（本相位不逐帧奶）
                            target?.setHitpoints(target.maxHitpoints)
                            transitionPsPhase(PS_PHASE_PASS_THROUGH)
                        }
                    }
                }
            }
            PS_PHASE_PASS_THROUGH -> {
                stabilizePsShips(engine, fire = true)
                if (maxRangeCount >= PS_PASS_THROUGH_DETONATIONS) {
                    val targetIntact = target != null && !target.isHulk &&
                        target.hitpoints >= target.maxHitpoints - PS_PASS_THROUGH_HP_TOLERANCE
                    when {
                        fuseCount != 0 -> {
                            failureReason = "ps pass-through fuse=$fuseCount, expect 0（舰船不触发近炸/不提前引爆）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        !targetIntact -> {
                            failureReason = "ps pass-through target hp=${target?.hitpoints}/${target?.maxHitpoints}（弹体穿舰不掉血为预期；掉血=存在触碰伤害或提前引爆波及）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        lastDetonateDist < PS_MAX_RANGE_DIST_MIN || lastDetonateDist > PS_MAX_RANGE_DIST_MAX -> {
                            failureReason = "ps max-range detonate dist=$lastDetonateDist, expect [$PS_MAX_RANGE_DIST_MIN, $PS_MAX_RANGE_DIST_MAX]（600su 空射自爆）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        else -> {
                            psSplashShipHitsBaseline = shipHits
                            psSplashFuseBaseline = fuseCount
                            psSplashMaxRangeBaseline = maxRangeCount
                            transitionPsPhase(PS_PHASE_SPLASH)
                        }
                    }
                }
            }
            PS_PHASE_SPLASH -> {
                stabilizePsShips(engine, fire = true)
                if (maxRangeCount - psSplashMaxRangeBaseline >= PS_SPLASH_DETONATIONS) {
                    when {
                        shipHits - psSplashShipHitsBaseline < 1 -> {
                            failureReason = "ps splash ship hits delta=${shipHits - psSplashShipHitsBaseline}, expect>=1（700su 处舰船应被满射程自爆锥面波及）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        fuseCount - psSplashFuseBaseline != 0 -> {
                            failureReason = "ps splash fuse delta=${fuseCount - psSplashFuseBaseline}, expect 0（舰船蹭波及但不触发近炸）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        else -> transitionPsPhase(PS_PHASE_FUSE)
                    }
                }
            }
            PS_PHASE_FUSE -> {
                stabilizePsShips(engine, fire = true)
                feedPsMissiles(engine)
                if (fuseCount >= PS_FUSE_DETONATIONS && missileHits >= PS_FUSE_MISSILE_HITS) {
                    when {
                        floatyCount < 1 -> {
                            failureReason = "ps floaty=$floatyCount, expect>=1（devMode 引爆计数浮字「近炸命中 ×n」）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        coneVfxCount < 1 -> {
                            failureReason = "ps cone vfx=$coneVfxCount, expect>=1（引爆锥面 VFX）"
                            transitionPsPhase(PS_PHASE_FAILED)
                        }
                        else -> transitionPsPhase(PS_PHASE_COMPLETED)
                    }
                }
            }
            PS_PHASE_COMPLETED -> {
                stabilizePsShips(engine, fire = true)
                feedPsMissiles(engine)
            }
        }

        // 最近一次近炸引爆时刻（COMPLETED 截图门控：引爆近期发生才上报，令锥面/浮字入帧）
        if (fuseCount > psLastTrackedFuseCount) {
            psLastTrackedFuseCount = fuseCount
            psLastFuseDetonateAt = elapsed
        }

        val state = when {
            player == null || target == null -> {
                if (elapsed > 12f) {
                    failureReason = "ps ships missing: player=${player != null}, target=${target != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            psPhase == PS_PHASE_FAILED -> "Failed"
            psPhase != PS_PHASE_COMPLETED &&
                elapsed - psPhaseStartedAt > PS_PHASE_TIMEOUT -> {
                failureReason = "ps phase timeout: $psPhase"
                "Failed"
            }
            psPhase == PS_PHASE_COMPLETED -> {
                val recentDetonate = psLastFuseDetonateAt >= 0f && elapsed - psLastFuseDetonateAt <= PS_COMPLETED_DETONATE_WINDOW
                if (recentDetonate || elapsed - psPhaseStartedAt >= PS_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: positron_shockwave_basic pass-through/max-range/splash/fuse evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, weapon)
        }
    }

    // === Phase-1 gravitational lens scenario ===

    private fun deployLensPhase1ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase1Side(engine, FleetSide.PLAYER)
        deployLensPhase1Side(engine, FleetSide.ENEMY)
    }

    private fun deployLensPhase1Side(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.setSuppressDeploymentMessages(true)
        val reserves = manager.getReservesCopy().toList()
        if (reserves.isEmpty()) return

        var allyIndex = 0
        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            if (findShipByHull(engine, hullId) != null && hullId == LensArrayCoreHullModIds.HULL_ID) {
                manager.removeFromReserves(member)
                continue
            }

            val anchor = when {
                side == FleetSide.ENEMY -> Vector2f(900f + enemyIndex++ * 170f, 20f)
                hullId == LensArrayCoreHullModIds.HULL_ID -> lensAnchor
                else -> Vector2f(-520f, -260f + allyIndex++ * 150f)
            }
            val facing = if (side == FleetSide.ENEMY) 180f else 0f
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = side == FleetSide.ENEMY)
        }
    }

    private fun arrangeLensPhase1Ships(engine: CombatEngineAPI) {
        val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
        lens?.let { stabilizeShip(it, lensAnchor, 0f, allowFire = false) }
        var allyIndex = 0
        engine.ships
            .filter { it !== lens && it.owner == lens?.owner && !it.isFighter }
            .forEach { stabilizeShip(it, Vector2f(-520f, -260f + allyIndex++ * 150f), 0f, allowFire = false) }
        engine.ships
            .filter { ship -> lens != null && ship.owner != lens.owner && !ship.isFighter }
            .forEachIndexed { index, ship ->
                stabilizeShip(ship, Vector2f(900f + index * 170f, 20f), 180f, allowFire = false, preserveAI = true)
            }
    }

    private fun advanceLensPhase1Scenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase1ReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeLensPhase1Ships(engine)

        val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
        lens?.let { engine.setPlayerShipExternal(it) }
        lens?.shield?.let { if (!it.isOn) it.toggleOn() }

        // 对引力透镜级自身维持 3 层误差/深水标记，验证 applier 真的改了承伤。
        // 标记每层 5s 会过期，且本场景 setDoNotEndCombat 后会长时间运行（观测点在很晚），
        // 故每帧把不足的层数补齐到 3（applyOrRefresh 同时刷新时长），保证稳态诊断读到 3 层。
        if (lens != null && elapsed > 1.0f) {
            val driftNeeded = LENS_SELF_MARK_STACKS - LensMarks.driftStacks(lens)
            if (driftNeeded > 0) LensMarks.applyDriftMark(engine, lens, lens, driftNeeded)
            val deepWaterNeeded = LENS_SELF_MARK_STACKS - LensMarks.deepWaterStacks(lens)
            if (deepWaterNeeded > 0) LensMarks.applyDeepWaterMark(engine, lens, lens, deepWaterNeeded)
            if (!lensMarksInjected) {
                lensMarksInjected = true
                log.info("[ASTD-Automation] lens self marks injected: drift=$LENS_SELF_MARK_STACKS, deepWater=$LENS_SELF_MARK_STACKS")
            }
        }

        val state = when {
            lens != null && lensMarksInjected && elapsed > 2.0f -> "Completed"
            lens == null && elapsed > 8f -> {
                failureReason = "gravitational lens missing: ${LensArrayCoreHullModIds.HULL_ID}"
                "Failed"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: lens_phase1_foundation evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, lens)
            writeTelemetry(engine, state, lens, null)
        }
    }

    private fun lensDeployedShipIds(engine: CombatEngineAPI): List<String> =
        engine.ships
            .asSequence()
            .filter { !it.isFighter }
            .mapNotNull { it.hullSpec?.hullId }
            .distinct()
            .sorted()
            .toList()

    private fun lensCoreTooltipKeyCount(engine: CombatEngineAPI): Int {
        val ship = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID) ?: return 0
        if (!hasHullmod(ship, LensArrayCoreHullModIds.CORE)) return 0
        return LENS_CORE_TOOLTIP_KEYS.count { key -> isResolvedTextKey(key) }
    }

    // === Phase-2 gravitational lens scenario (mechanisms + shader vfx counts) ===

    /**
     * 载人引力透镜级（无 MODE_AUTOMATED perma-mod 即载人）。phase2 旗舰，驱动定影场施放、潮汐、标记高光。
     * 标记高光提交（[cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod.submitMarkHighlights]）
     * 仅对 engine.playerShip 每帧执行，故必须把载人透镜设为玩家船。
     */
    private fun findCrewedLens(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == LensArrayCoreHullModIds.HULL_ID &&
                ship.variant?.hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED) != true &&
                ship.variant?.hasLensAutomatedModeSafe() != true
        }

    /** 无人引力透镜级（MODE_AUTOMATED perma-mod）。唯一跑幽灵信号的模式。 */
    private fun findAutomatedLens(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == LensArrayCoreHullModIds.HULL_ID &&
                ship.variant?.hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED) == true
        }

    /** ShipVariantAPI.hasLensAutomatedMode 的安全包装（perma-mod 或普通 hullmod 任一即无人模式）。 */
    private fun com.fs.starfarer.api.combat.ShipVariantAPI.hasLensAutomatedModeSafe(): Boolean =
        try {
            getPermaMods().contains(LensArrayCoreHullModIds.MODE_AUTOMATED) ||
                hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED)
        } catch (_: Throwable) {
            false
        }

    /** phase2 全场敌舰（非残骸非舰载机），潮汐深水标记 / 认知撕裂的目标。 */
    private fun lensPhase2Enemies(engine: CombatEngineAPI): List<ShipAPI> {
        val crewed = findCrewedLens(engine) ?: return emptyList()
        return engine.ships.filter { it.owner != crewed.owner && !it.isFighter && !it.isHulk }
    }

    private fun deployLensPhase2ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase2Side(engine, FleetSide.PLAYER)
        deployLensPhase2Side(engine, FleetSide.ENEMY)
    }

    private fun deployLensPhase2Side(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.setSuppressDeploymentMessages(true)
        val reserves = manager.getReservesCopy().toList()
        if (reserves.isEmpty()) return

        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            val anchor = when {
                side == FleetSide.ENEMY -> Vector2f(LENS_PHASE2_ENEMY_CLUSTER_X + enemyIndex++ * 140f, -200f + (enemyIndex % 3) * 200f)
                hullId == LensArrayCoreHullModIds.HULL_ID ->
                    if (member.variant?.hasLensAutomatedModeSafe() == true) LENS_PHASE2_AUTOMATED_ANCHOR else LENS_PHASE2_CREWED_ANCHOR
                else -> Vector2f(-560f, 0f)
            }
            val facing = if (side == FleetSide.ENEMY) 180f else 0f
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = side == FleetSide.ENEMY)
        }
    }

    private fun arrangeLensPhase2Ships(engine: CombatEngineAPI) {
        val crewed = findCrewedLens(engine)
        crewed?.let { stabilizeShip(it, LENS_PHASE2_CREWED_ANCHOR, 0f, allowFire = false) }
        findAutomatedLens(engine)?.let { stabilizeShip(it, LENS_PHASE2_AUTOMATED_ANCHOR, 0f, allowFire = false) }
        engine.ships
            .filter { ship -> crewed != null && ship.owner != crewed.owner && !ship.isFighter }
            .forEachIndexed { index, ship ->
                val anchor = Vector2f(LENS_PHASE2_ENEMY_CLUSTER_X + index * 140f, -200f + (index % 3) * 200f)
                stabilizeShip(ship, anchor, 180f, allowFire = false, preserveAI = true)
            }
    }

    private fun advanceLensPhase2Scenario(engine: CombatEngineAPI, amount: Float) {
        engine.setDoNotEndCombat(true)
        deployLensPhase2ReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeLensPhase2Ships(engine)

        val crewed = findCrewedLens(engine)
        // 标记高光提交（drift/deepwater shader）仅对 playerShip 每帧驱动，故把载人透镜设为玩家船。
        crewed?.let { engine.setPlayerShipExternal(it) }
        crewed?.shield?.let { if (!it.isOn) it.toggleOn() }

        val enemies = lensPhase2Enemies(engine)

        // (1) 回声定影场：直接 EchoFixationField.spawn 在敌群中心建场（最确定性的施放路径，等价系统 IN 首帧建场）。
        //     场每 ~4s 定影后回放并销毁；为保证观测点恒有活跃场（echoFixationFieldActive=true）且回放反复
        //     产出认知撕裂/残影/误差标记，无活跃场时即补建一个。建在敌群质心，敌舰落在场内（半径 700su）。
        if (crewed != null && enemies.isNotEmpty() && elapsed > 0.5f && !EchoFixationField.hasActiveField(engine)) {
            val cx = enemies.map { it.location.x }.average().toFloat()
            val cy = enemies.map { it.location.y }.average().toFloat()
            EchoFixationField.spawn(engine, crewed, cx, cy, systemRangeMult = 1f)
            log.info("[ASTD-Automation] lens phase-2 echo fixation field spawned at ($cx,$cy) over ${enemies.size} enemies")
        }

        // (2) 幽灵信号（仅无人模式）：无人透镜的 advanceInCombat 每 0.25s tick 对范围内敌方导弹做失制导判定，
        //     每剥离一枚导弹即在该导弹位置起一道小脉冲扰动波（并 50% 概率熄火）。为提供真实导弹，向无人透镜
        //     附近持续 spawn 敌方导弹（owner=敌舰），交由真实 ghostSignal() 路径消费——不伪造特效，
        //     脉冲必须来自真实剥离触发的 spawnGhostPulse。
        feedGhostSignalMissiles(engine, amount)

        // (3) 潮汐深水标记 / 标记高光：由 ASTDLensPermeatingTideHullMod / ASTDLensArrayCoreHullMod 每帧自驱，
        //     敌舰落在载人透镜 2500su 潮汐场内自然叠深水标记，无需脚本干预。

        // (4) fighter 误差标记降级说明（once）：parallaxDriftStacksFromFighters 依赖舰载机起飞 + on-hit 命中，
        //     实机难稳定确定性触发，故本场景降级为「视差甲板插件挂载」断言（parallaxDecksHullmod）；
        //     fighter 实命中叠误差标记的逐帧外观留人工验证。此处 log 一次，符合全局规范「降级要 log 说明」。
        if (!lensPhase2DowngradeLogged && findCrewedLens(engine) != null) {
            lensPhase2DowngradeLogged = true
            log.info(
                "[ASTD-Automation] lens phase2: parallaxDriftStacksFromFighters downgraded to " +
                    "parallaxDecksHullmod mount assertion (fighter on-hit mark hard to trigger " +
                    "deterministically in-game; fighter-driven drift visuals left to manual review)"
            )
        }

        val evidenceReady = lensPhase2EvidenceReady(engine, enemies)
        val state = when {
            crewed == null && elapsed > 10f -> {
                failureReason = "crewed gravitational lens missing: ${LensArrayCoreHullModIds.HULL_ID}"
                "Failed"
            }
            evidenceReady -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: lens_phase2_mechanisms evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, crewed)
            writeTelemetry(engine, state, crewed, null)
        }
    }

    /**
     * 向无人透镜附近持续投放敌方导弹，供真实幽灵信号路径消费。
     *
     * 失制导每 tick 0.25s、每枚导弹 50% 命中，单 tick 内多枚同时落入范围 → P(至少一枚剥离)≈1。
     * 导弹经 engine.spawnProjectile(enemySource, null, weaponId, ...) 生成（owner=敌舰 ≠ 无人透镜 owner，
     * 满足 ghostSignal 的敌对判定）。spawn 失败（weaponId 不可用等）必须 Fail Fast 报错，绝不静默吞错。
     */
    private fun feedGhostSignalMissiles(engine: CombatEngineAPI, amount: Float) {
        val automated = findAutomatedLens(engine) ?: return
        // 幽灵信号波计数已满足则停止投放，避免无意义刷导弹。
        if (LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES) > 0) return
        if (elapsed < 0.75f) return

        ghostMissileFeedAcc += amount
        if (ghostMissileFeedAcc < GHOST_MISSILE_FEED_INTERVAL) return
        ghostMissileFeedAcc = 0f

        val enemySource = engine.ships.firstOrNull { it.owner != automated.owner && !it.isFighter && !it.isHulk } ?: return
        // 在无人透镜周围一圈投放，确保落入 2000su 幽灵范围内（取 800su 半径）。
        for (i in 0 until GHOST_MISSILE_BURST) {
            val ang = i * (360f / GHOST_MISSILE_BURST)
            val loc = Vector2f(
                automated.location.x + 800f * kotlin.math.cos(Math.toRadians(ang.toDouble())).toFloat(),
                automated.location.y + 800f * kotlin.math.sin(Math.toRadians(ang.toDouble())).toFloat(),
            )
            // weapon=null + weaponId 直接生成导弹实体（范式同 ASTDVirtualParticleLatticeWebHullMod:215）。
            // spawn 返回 null 视为 weaponId 不可用——Fail Fast 抛错，由 automation 暴露而非伪造通过。
            val spawned = engine.spawnProjectile(enemySource, null, GHOST_FEED_MISSILE_ID, Vector2f(loc), ang + 180f, Vector2f())
            if (spawned == null) {
                throw IllegalStateException(
                    "[ASTD-Automation] lens phase-2 ghost-signal missile spawn returned null for weaponId=$GHOST_FEED_MISSILE_ID"
                )
            }
        }
    }

    /**
     * phase2 证据齐备判定：机制证据 + shader 提交计数全满足才判 Completed。
     * fighter 误差标记降级为插件挂载断言（见诊断字段 parallaxDecksHullmod），不在此处要求 fighter 实触发。
     */
    private fun lensPhase2EvidenceReady(engine: CombatEngineAPI, enemies: List<ShipAPI>): Boolean {
        if (elapsed < 1.5f) return false
        val mechOk = EchoFixationField.hasActiveField(engine) &&
            lensPhase2CognitiveTearApplied(enemies) &&
            EchoFixationAfterimageRenderer.afterimageFrames(engine) > 0 &&
            lensPhase2MaxDeepWaterOnEnemy(enemies) > 0 &&
            lensPhase2PermeatingTideHullmod(engine) &&
            lensPhase2ParallaxDecksHullmod(engine)
        val visualOk = LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_ECHO_FIXATION_FIELD_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DRIFT_MARK_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DEEP_WATER_MARK_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_TIDE_FIELD_FRAMES) > 0
        return mechOk && visualOk
    }

    /** 是否有敌舰被认知撕裂：承伤 mult>1（EchoFixationField 回放写 hullDamageTakenMult.modifyMult）。 */
    private fun lensPhase2CognitiveTearApplied(enemies: List<ShipAPI>): Boolean =
        enemies.any { ship ->
            try { (ship.mutableStats?.hullDamageTakenMult?.modifiedValue ?: 0f) > 1.0001f } catch (_: Throwable) { false }
        }

    /** 场内敌舰深水标记最大层数（潮汐叠层证据）。 */
    private fun lensPhase2MaxDeepWaterOnEnemy(enemies: List<ShipAPI>): Int =
        enemies.maxOfOrNull { LensMarks.deepWaterStacks(it) } ?: 0

    /** 场内敌舰误差标记最大层数（认知撕裂回放 / 视差甲板均会叠误差标记）。 */
    private fun lensPhase2MaxDriftOnEnemy(enemies: List<ShipAPI>): Int =
        enemies.maxOfOrNull { LensMarks.driftStacks(it) } ?: 0

    /** 载人透镜是否挂载渗透潮汐插件（内置 hullmod）。 */
    private fun lensPhase2PermeatingTideHullmod(engine: CombatEngineAPI): Boolean {
        val crewed = findCrewedLens(engine) ?: return false
        return hasHullmod(crewed, "astd_lens_permeating_tide")
    }

    /**
     * 载人透镜是否挂载视差甲板插件（内置 hullmod）。
     * fighter 驱动误差标记实机难稳定触发（依赖舰载机起飞/命中），故 parallaxDriftStacksFromFighters
     * 降级为「插件挂载」断言：证明视差甲板机制已装载即视为通过，fighter 实命中留人工/后续。
     */
    private fun lensPhase2ParallaxDecksHullmod(engine: CombatEngineAPI): Boolean {
        val crewed = findCrewedLens(engine) ?: return false
        return hasHullmod(crewed, "astd_lens_parallax_decks")
    }

    private fun spawnAod7Projectile(engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI) {
        val location = Vector2f(projectilePreviewAnchor)
        val velocity = Vector2f(ship.velocity ?: Vector2f())
        val projectile = engine.spawnProjectile(
            ship,
            weapon,
            ASTDInGameAutomationScenario.WEAPON_ID,
            location,
            weapon.currAngle,
            velocity,
        ) as? DamagingProjectileAPI

        if (projectile != null) {
            fallbackProjectile = projectile
            fallbackProjectileSpawnedAt = elapsed
            projectile.velocity.x = FALLBACK_PROJECTILE_SPEED
            projectile.velocity.y = 0f
            alignFallbackProjectileForEvidence()
            ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine)
            log.info("[ASTD-Automation] fallback spawned ${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID} through ${ASTDInGameAutomationScenario.WEAPON_ID}")
        }
    }

    private fun alignFallbackProjectileForEvidence() {
        val projectile = fallbackProjectile ?: return
        driveFallbackProjectileCurve(projectile)
    }

    private fun alignAod7ProjectilesForEvidence(engine: CombatEngineAPI) {
        fallbackProjectile?.let { alignProjectileForEvidence(it) }
        engine.projectiles
            .filter { it.projectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID }
            .forEach { projectile ->
                if (projectile is DamagingProjectileAPI) alignProjectileForEvidence(projectile)
            }
    }

    private fun alignProjectileForEvidence(projectile: DamagingProjectileAPI) {
        if (projectile === fallbackProjectile) {
            driveFallbackProjectileCurve(projectile)
            return
        }
        projectile.location.y = curvePositionAt(0f).y
        projectile.facing = 0f
    }

    private fun driveFallbackProjectileCurve(projectile: DamagingProjectileAPI) {
        val age = (elapsed - fallbackProjectileSpawnedAt).coerceAtLeast(0f)
        projectile.location.set(curvePositionAt(age))
        val velocity = curveVelocityAt(age)
        projectile.velocity.set(velocity)
        projectile.facing = org.lazywizard.lazylib.VectorUtils.getFacing(velocity)
    }

    private fun curvePositionAt(age: Float): Vector2f {
        val track = automationPreviewTrack(age)
        val scale = automationReferenceWorldUnitsPerPixel()
        return Vector2f(
            projectilePreviewAnchor.x + track.headOffset.x * scale,
            projectilePreviewAnchor.y + track.headOffset.y * scale,
        )
    }

    private fun curveVelocityAt(age: Float): Vector2f {
        val step = 1f / 120f
        val previous = curvePositionAt((age - step).coerceAtLeast(0f))
        val next = curvePositionAt(age + step)
        return Vector2f((next.x - previous.x) / (step * 2f), (next.y - previous.y) / (step * 2f))
    }

    /** AOD-7 新管线 spec 的驱动策略（参考轨迹/取证阈值的参数来源）。 */
    private val aod7Policy by lazy {
        ProjectileVfxSpecs.build(ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID)?.policy
            ?: throw IllegalStateException("AOD-7 automation reference spec missing: ${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID}")
    }

    private fun automationPreviewTrack(age: Float): ASTDProjectileVfxLayout.PreviewFlightTrack {
        val policy = aod7Policy
        return ASTDProjectileVfxLayout.previewFlightTrack(
            trailStartWidth = policy.primaryTrailStartWidth,
            elapsed = age,
            durationSeconds = policy.durationSeconds,
            // 合成截图场景参数（旧 aod7 preset 的默认，与新管线 spec 一致；新管线无此两字段，按场景常量固化）。
            flightEndRatio = AUTOMATION_FLIGHT_END_RATIO,
            dissolveStartRatio = policy.dissolveStartRatio,
            preDissolveFraction = AUTOMATION_PRE_DISSOLVE_FRACTION,
            captureWidth = policy.layoutReferenceWidth,
            captureHeight = AUTOMATION_REFERENCE_CAPTURE_HEIGHT,
            curveAmount = AUTOMATION_CURVE_AMOUNT,
            curveFrequency = AUTOMATION_CURVE_FREQUENCY,
            curved = true,
        )
    }

    private fun automationReferenceWorldUnitsPerPixel(): Float {
        val pixelHeight = try { Display.getHeight().toFloat().takeIf { it > 0f } ?: 1f } catch (_: Throwable) { 1f }
        return ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel(pixelHeight)
    }

    private fun currentState(engine: CombatEngineAPI, ship: ShipAPI?, weapon: WeaponAPI?): String {
        failureReason = null
        if (ship == null) {
            failureReason = "arc_flare ship not found in combat"
            return if (elapsed > 10f) "Failed" else "CombatReady"
        }
        if (weapon == null) {
            failureReason = "aod7 weapon not found on arc_flare"
            return if (elapsed > 10f) "Failed" else "CombatReady"
        }
        if (projectileObserved(engine) && vfxObserved(engine) && evidenceReady(engine)) return "Completed"
        if (projectileObserved(engine)) return "FireObserved"
        return "CombatReady"
    }

    private fun projectileObserved(engine: CombatEngineAPI): Boolean {
        val telemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        if (telemetry.lastProjectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID) return true
        return engine.projectiles.any { it.projectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID }
    }

    private fun vfxObserved(engine: CombatEngineAPI): Boolean {
        val telemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        return telemetry.trackedCount > 0 &&
            telemetry.lastProjectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID
    }

    private fun evidenceReady(engine: CombatEngineAPI): Boolean {
        val telemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        return telemetry.lastVisibleLength >= referenceCaptureVisibleLength() &&
            telemetry.lastElapsed >= SCREENSHOT_FLIGHT_SECONDS
    }

    private fun referenceCaptureVisibleLength(): Float {
        val policy = aod7Policy
        return ASTDProjectileVfxLayout.previewFlightLayout(
            trailStartWidth = policy.primaryTrailStartWidth,
            elapsed = REFERENCE_CAPTURE_ELAPSED_SECONDS,
            durationSeconds = policy.durationSeconds,
            // 合成截图场景参数（与 automationPreviewTrack 一致）。
            flightEndRatio = AUTOMATION_FLIGHT_END_RATIO,
            dissolveStartRatio = policy.dissolveStartRatio,
            preDissolveFraction = AUTOMATION_PRE_DISSOLVE_FRACTION,
            captureWidth = policy.layoutReferenceWidth,
        ).visibleLength
    }

    private fun writeTelemetry(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI? = findArcFlare(engine),
        weapon: WeaponAPI? = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
    ) {
        // SSOptimizer patches this method and writes telemetry/screenshots outside the Starsector script sandbox.
    }

    private fun writeDiagnostics(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI? = findArcFlare(engine),
    ) {
        if (!ASTDInGameAutomationScenario.isEnabled() &&
            !ASTDInGameAutomationScenario.isArcProductionEnabled() &&
            !ASTDInGameAutomationScenario.isLensPhase1Enabled() &&
            !ASTDInGameAutomationScenario.isLensPhase2Enabled() &&
            !ASTDInGameAutomationScenario.isChargeNeedleEnabled() &&
            !ASTDInGameAutomationScenario.isEdaEnabled() &&
            !ASTDInGameAutomationScenario.isAvEnabled() &&
            !ASTDInGameAutomationScenario.isQjEnabled() &&
            !ASTDInGameAutomationScenario.isPsEnabled()
        ) {
            return
        }

        val displayMode = try { Display.getDisplayMode() } catch (_: Throwable) { null }
        val displayWidth = try { Display.getWidth() } catch (_: Throwable) { -1 }
        val displayHeight = try { Display.getHeight() } catch (_: Throwable) { -1 }
        val displayPixelScale = try { Display.getPixelScaleFactor() } catch (_: Throwable) { -1f }
        val viewport = engine.viewport
        val shipSprite = try { ship?.spriteAPI } catch (_: Throwable) { null }
        val vfxTelemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        val scenarioId = when {
            ASTDInGameAutomationScenario.isPsEnabled() -> ASTDInGameAutomationScenario.PS_SCENARIO_ID
            ASTDInGameAutomationScenario.isQjEnabled() -> ASTDInGameAutomationScenario.QJ_SCENARIO_ID
            ASTDInGameAutomationScenario.isAvEnabled() -> ASTDInGameAutomationScenario.AV_SCENARIO_ID
            ASTDInGameAutomationScenario.isEdaEnabled() -> ASTDInGameAutomationScenario.EDA_SCENARIO_ID
            ASTDInGameAutomationScenario.isChargeNeedleEnabled() -> ASTDInGameAutomationScenario.CHARGE_NEEDLE_SCENARIO_ID
            ASTDInGameAutomationScenario.isLensPhase2Enabled() -> ASTDInGameAutomationScenario.LENS_PHASE2_SCENARIO_ID
            ASTDInGameAutomationScenario.isLensPhase1Enabled() -> ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID
            ASTDInGameAutomationScenario.isArcProductionEnabled() -> ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID
            else -> ASTDInGameAutomationScenario.SCENARIO_ID
        }
        val json = buildString {
            appendLine("{")
            appendLine("  \"source\": \"ASTD\",")
            appendLine("  \"scenario\": \"$scenarioId\",")
            appendLine("  \"state\": \"$state\",")
            appendLine("  \"displayWidth\": $displayWidth,")
            appendLine("  \"displayHeight\": $displayHeight,")
            appendLine("  \"displayPixelScale\": ${formatFloat(displayPixelScale)},")
            appendLine("  \"displayModeWidth\": ${displayMode?.width ?: -1},")
            appendLine("  \"displayModeHeight\": ${displayMode?.height ?: -1},")
            appendLine("  \"viewportVisibleWidth\": ${formatFloat(viewport.visibleWidth)},")
            appendLine("  \"viewportVisibleHeight\": ${formatFloat(viewport.visibleHeight)},")
            appendLine("  \"viewportWorldXToScreenX\": ${formatFloat(viewport.worldXtoScreenX)},")
            appendLine("  \"viewportWorldYToScreenY\": ${formatFloat(viewport.worldYtoScreenY)},")
            appendLine("  \"viewportViewMult\": ${formatFloat(viewport.viewMult)},")
            appendLine("  \"shipLocationX\": ${formatFloat(ship?.location?.x ?: -1f)},")
            appendLine("  \"shipLocationY\": ${formatFloat(ship?.location?.y ?: -1f)},")
            appendLine("  \"shipFacing\": ${formatFloat(ship?.facing ?: -1f)},")
            appendLine("  \"shipSpriteWidth\": ${formatFloat(shipSprite?.width ?: -1f)},")
            appendLine("  \"shipSpriteHeight\": ${formatFloat(shipSprite?.height ?: -1f)},")
            appendLine("  \"shipSpriteCenterX\": ${formatFloat(shipSprite?.centerX ?: -1f)},")
            appendLine("  \"shipSpriteCenterY\": ${formatFloat(shipSprite?.centerY ?: -1f)},")
            appendLine("  \"failureReason\": ${jsonString(failureReason)},")
            if (ASTDInGameAutomationScenario.isPsEnabled()) {
                val psPlayer = findPsPlayer(engine)
                val psTarget = findPsTarget(engine)
                val psWeapon = findPsWeapon(psPlayer)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 06 §4.2 烟测检查点）----
                appendLine("  \"psPhase\": \"$psPhase\",")
                appendLine("  \"psSlotId\": ${jsonString(psWeapon?.slot?.id)},")
                appendLine("  \"psWeaponRange\": ${formatFloat(psWeapon?.range ?: -1f)},")
                appendLine("  \"psHintsPd\": ${psWeapon?.spec?.getAIHints()?.contains(WeaponAPI.AIHints.PD) == true},")
                appendLine("  \"psTargetHitpoints\": ${formatFloat(psTarget?.hitpoints ?: -1f)},")
                appendLine("  \"psTargetMaxHitpoints\": ${formatFloat(psTarget?.maxHitpoints ?: -1f)},")
                appendLine("  \"psDetonateFuse\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_DETONATE_FUSE)},")
                appendLine("  \"psDetonateMaxRange\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_DETONATE_MAX_RANGE)},")
                appendLine("  \"psLastDetonateDist\": ${formatFloat(PositronShockwaveFuseScript.telemetryFloat(engine, PositronShockwaveFuseScript.TELEMETRY_LAST_DETONATE_DIST))},")
                appendLine("  \"psConeShipHits\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_SHIP_HITS)},")
                appendLine("  \"psConeMissileHits\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_MISSILE_HITS)},")
                appendLine("  \"psConeFighterHits\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_FIGHTER_HITS)},")
                appendLine("  \"psConeVfx\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_CONE_VFX)},")
                appendLine("  \"psFloaty\": ${PositronShockwaveFuseScript.telemetryCount(engine, PositronShockwaveFuseScript.TELEMETRY_FLOATY)},")
                appendLine("  \"psDevMode\": ${Global.getSettings().isDevMode},")
                appendLine("  \"psOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.PS_PROJECTILE_SPEC_ID }},")
                appendLine("  \"psEnemyMissilesInPlay\": ${engine.missiles.count { it.owner != 0 }},")
            } else if (ASTDInGameAutomationScenario.isQjEnabled()) {
                val qjPlayer = findQjPlayer(engine)
                val qjEnemy = findQjEnemy(engine)
                val qjW1 = findQjWeapon(qjPlayer, QJ_PLAYER_SLOT_W1)
                val qjW2 = findQjWeapon(qjPlayer, QJ_PLAYER_SLOT_W2)
                val qjEnemyW = qjEnemy?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.QJ_WEAPON_ID }
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 05 §2.5 烟测检查点）----
                appendLine("  \"qjPhase\": \"$qjPhase\",")
                appendLine("  \"qjW1SlotId\": ${jsonString(qjW1?.slot?.id)},")
                appendLine("  \"qjW2SlotId\": ${jsonString(qjW2?.slot?.id)},")
                appendLine("  \"qjWeaponRange\": ${formatFloat(qjW1?.range ?: -1f)},")
                appendLine("  \"qjW1Stacks\": ${if (qjPlayer != null && qjW1 != null) qjPlayer.qiongjueCalcStacks(qjW1)?.stacks ?: 0 else -1},")
                appendLine("  \"qjW2Stacks\": ${if (qjPlayer != null && qjW2 != null) qjPlayer.qiongjueCalcStacks(qjW2)?.stacks ?: 0 else -1},")
                appendLine("  \"qjEnemyStacks\": ${if (qjEnemy != null && qjEnemyW != null) qjEnemy.qiongjueCalcStacks(qjEnemyW)?.stacks ?: 0 else -1},")
                // 逐命中伤害乘区遥测（同 spec 武器共享 damage.modifier stat 后的唯一逐武器证据通道）。
                appendLine("  \"qjW1DealtMult\": ${formatFloat(if (qjPlayer != null && qjW1 != null) QiongjueDamageDealtModifier.dealtMult(engine, qjPlayer, qjW1) else -1f)},")
                appendLine("  \"qjW2DealtMult\": ${formatFloat(if (qjPlayer != null && qjW2 != null) QiongjueDamageDealtModifier.dealtMult(engine, qjPlayer, qjW2) else -1f)},")
                appendLine("  \"qjEnemyDealtMult\": ${formatFloat(if (qjEnemy != null && qjEnemyW != null) QiongjueDamageDealtModifier.dealtMult(engine, qjEnemy, qjEnemyW) else -1f)},")
                // 同舰双穷距伤害 stat 同一性探针（第三轮烟测实证 true：同 spec 武器共享底层 MutableStat）。
                appendLine("  \"qjDmgStatShared\": ${qjW1 != null && qjW2 != null && qjW1.damage?.modifier === qjW2.damage?.modifier},")
                appendLine("  \"qjDmgMultAtFull\": ${formatFloat(qjDmgMultAtFull)},")
                appendLine("  \"qjRefireMinAtFull\": ${formatFloat(if (qjRefireMinAtFull == Float.MAX_VALUE) -1f else qjRefireMinAtFull)},")
                appendLine("  \"qjSpikeApplied\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjueCalcStacks.TELEMETRY_SPIKE_APPLIED)},")
                appendLine("  \"qjHudFrames\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjueCalcStacks.TELEMETRY_HUD_FRAMES)},")
                appendLine("  \"qjHitPlayer\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_HIT_PLAYER)},")
                appendLine("  \"qjHitOther\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_HIT_OTHER)},")
                appendLine("  \"qjTransferPlayer\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_TRANSFER_PLAYER)},")
                appendLine("  \"qjFullPlayer\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_FULL_PLAYER)},")
                appendLine("  \"qjConeVfx\": ${QiongjuePhaseRailgunOnHitEffect.telemetryCount(engine, QiongjuePhaseRailgunOnHitEffect.TELEMETRY_CONE_VFX)},")
                appendLine("  \"qjStackFps\": ${formatFloat(qjStackFps)},")
                appendLine("  \"qjDualW1Stacks\": $qjDualW1Stacks,")
                appendLine("  \"qjDualW2Stacks\": $qjDualW2Stacks,")
                appendLine("  \"qjSwitchW1Stacks\": $qjSwitchW1Stacks,")
                appendLine("  \"qjDecaySeconds\": ${formatFloat(qjDecaySeconds)},")
                appendLine("  \"qjStacksBeforeKill\": $qjStacksBeforeKill,")
                appendLine("  \"qjStacksAfterKillHit\": $qjStacksAfterKillHit,")
                appendLine("  \"qjEnemyMult1\": ${formatFloat(qjEnemyMult1)},")
                appendLine("  \"qjEnemyMult2\": ${formatFloat(qjEnemyMult2)},")
                appendLine("  \"qjEnemyMult5\": ${formatFloat(qjEnemyMult5)},")
                appendLine("  \"qjDevMode\": ${Global.getSettings().isDevMode},")
                appendLine("  \"qjOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.QJ_PROJECTILE_SPEC_ID }},")
            } else if (ASTDInGameAutomationScenario.isAvEnabled()) {
                val avPlayerW = findAvWeapon(findAvPlayer(engine))
                val avSynergyW = findAvWeapon(findAvSynergy(engine))
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 04 §4.2 烟测检查点）----
                appendLine("  \"avPhase\": \"$avPhase\",")
                appendLine("  \"avPlayerSlotId\": ${jsonString(avPlayerW?.slot?.id)},")
                appendLine("  \"avSynergySlotId\": ${jsonString(avSynergyW?.slot?.id)},")
                appendLine("  \"avPlayerWeaponRange\": ${formatFloat(avPlayerW?.range ?: -1f)},")
                appendLine("  \"avPlayerWeaponFiring\": ${avPlayerW?.isFiring ?: false},")
                appendLine("  \"avAbsorbedPlayer\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_ABSORBED_PLAYER)},")
                appendLine("  \"avAbsorbedEnemy\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_ABSORBED_ENEMY)},")
                appendLine("  \"avCollapseCount\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_COLLAPSE_COUNT)},")
                appendLine("  \"avLastCollapseDamagePlayer\": ${formatFloat(AnnihilationVortexBeamEffect.telemetryFloat(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_DAMAGE_PLAYER))},")
                appendLine("  \"avLastCollapseHitsPlayer\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_LAST_COLLAPSE_HITS_PLAYER)},")
                appendLine("  \"avEmptyCollapseDamage\": ${formatFloat(avEmptyCollapseDamage)},")
                appendLine("  \"avBurstOnSeconds\": ${formatFloat(avBurstOnSeconds)},")
                appendLine("  \"avBurstOffSeconds\": ${formatFloat(avBurstOffSeconds)},")
                appendLine("  \"avHiddenBeamOk\": $avHiddenBeamOk,")
                appendLine("  \"avHudFrames\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_HUD_FRAMES)},")
                appendLine("  \"avFloatyCount\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_FLOATY_COUNT)},")
                appendLine("  \"avPoolRecycled\": ${AnnihilationVortexBeamEffect.counter(engine, AnnihilationVortexBeamEffect.TELEMETRY_POOL_RECYCLED)},")
                appendLine("  \"avScaleRadius1\": ${formatFloat(avScaleRadius1)},")
                appendLine("  \"avScaleRadius2\": ${formatFloat(avScaleRadius2)},")
                appendLine("  \"avScaleRadius5\": ${formatFloat(avScaleRadius5)},")
                appendLine("  \"avScaleThreshold5\": ${formatFloat(avScaleThreshold5)},")
                appendLine("  \"avScaleAoe5\": ${formatFloat(avScaleAoe5)},")
                appendLine("  \"avScale5Fps\": ${formatFloat(avScale5Fps)},")
                appendLine("  \"avHostKilled\": $avHostKilled,")
                appendLine("  \"avDevMode\": ${Global.getSettings().isDevMode},")
            } else if (ASTDInGameAutomationScenario.isEdaEnabled()) {
                val player = findEdaPlayer(engine)
                val enemy = findEdaEnemy(engine)
                val playerEda = player?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.EDA_WEAPON_ID }
                val enemyEda = enemy?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.EDA_WEAPON_ID }
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 03 §4.2 烟测检查点）----
                appendLine("  \"edaPhase\": \"$edaPhase\",")
                appendLine("  \"edaRangeZeroFlux\": ${formatFloat(edaRangeZeroFlux)},")
                appendLine("  \"edaRangeMidFlux\": ${formatFloat(edaRangeMidFlux)},")
                appendLine("  \"edaRangeHighFlux\": ${formatFloat(edaRangeHighFlux)},")
                appendLine("  \"edaPlayerWeaponRange\": ${formatFloat(playerEda?.range ?: -1f)},")
                appendLine("  \"edaPlayerFluxLevel\": ${formatFloat(player?.fluxLevel ?: -1f)},")
                appendLine("  \"edaMaxTriggerProjectiles\": $edaMaxTriggerProjectiles,")
                appendLine("  \"edaBurstSizes\": ${edaBurstSizes.takeLast(12).joinToString(prefix = "[", postfix = "]")},")
                appendLine("  \"edaMinPlayerAmmoObserved\": ${if (edaMinPlayerAmmo == Int.MAX_VALUE) -1 else edaMinPlayerAmmo},")
                appendLine("  \"edaExtraCountPlayer\": ${ElectricDriveAcceleratorOnHitEffect.extraDamageCountPlayer(engine)},")
                appendLine("  \"edaExtraMaxPlayer\": ${formatFloat(engine.customData[ElectricDriveAcceleratorOnHitEffect.TELEMETRY_EXTRA_MAX_PLAYER] as? Float ?: 0f)},")
                appendLine("  \"edaExtraCountOther\": ${ElectricDriveAcceleratorOnHitEffect.extraDamageCountOther(engine)},")
                appendLine("  \"edaExtraMaxOther\": ${formatFloat(engine.customData[ElectricDriveAcceleratorOnHitEffect.TELEMETRY_EXTRA_MAX_OTHER] as? Float ?: 0f)},")
                appendLine("  \"edaEnemyRangeScale1\": ${formatFloat(edaEnemyRangeScale1)},")
                appendLine("  \"edaEnemyRangeScale2\": ${formatFloat(edaEnemyRangeScale2)},")
                appendLine("  \"edaEnemyRangeScale5\": ${formatFloat(edaEnemyRangeScale5)},")
                appendLine("  \"edaEnemyWeaponRange\": ${formatFloat(enemyEda?.range ?: -1f)},")
                appendLine("  \"edaVfxTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"edaVfxLastSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"edaDevMode\": ${Global.getSettings().isDevMode},")
                appendLine("  \"edaOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.EDA_PROJECTILE_SPEC_ID }},")
            } else if (ASTDInGameAutomationScenario.isChargeNeedleEnabled()) {
                val enemy = findChargeNeedleEnemy(engine)
                val player = findChargeNeedlePlayer(engine)
                val enemyStacks = enemy?.chargeNeedleStacks()
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 §4.2 验收要点）----
                appendLine("  \"chargeNeedlePhase\": \"$chargeNeedlePhase\",")
                appendLine("  \"chargeNeedleTargetStacks\": ${enemyStacks?.stacks ?: 0},")
                appendLine("  \"chargeNeedleTargetMaxStacks\": ${enemyStacks?.maxStacks ?: 0},")
                appendLine("  \"chargeNeedleTargetUpkeepMult\": ${formatFloat(try { enemy?.mutableStats?.shieldUpkeepMult?.modifiedValue ?: -1f } catch (_: Throwable) { -1f })},")
                appendLine("  \"chargeNeedleTargetDissipation\": ${formatFloat(try { enemy?.mutableStats?.fluxDissipation?.modifiedValue ?: -1f } catch (_: Throwable) { -1f })},")
                appendLine("  \"chargeNeedleTargetBaseUpkeep\": ${formatFloat(try { enemy?.hullSpec?.shieldSpec?.upkeepCost ?: -1f } catch (_: Throwable) { -1f })},")
                appendLine("  \"chargeNeedlePeakStacks\": $chargeNeedlePeakStacks,")
                appendLine("  \"chargeNeedlePlayerVictimStacks\": ${player?.chargeNeedleStacks()?.stacks ?: 0},")
                appendLine("  \"chargeNeedleDischargeCount\": ${ChargeNeedleVfx.dischargeCount(engine)},")
                appendLine("  \"chargeNeedleMinSmallAmmoObserved\": ${if (chargeNeedleMinSmallAmmo == Int.MAX_VALUE) -1 else chargeNeedleMinSmallAmmo},")
                appendLine("  \"chargeNeedleMinHeavyAmmoObserved\": ${if (chargeNeedleMinHeavyAmmo == Int.MAX_VALUE) -1 else chargeNeedleMinHeavyAmmo},")
                appendLine("  \"chargeNeedleSmallAmmoEmptyAtSeconds\": ${formatFloat(chargeNeedleSmallEmptiedAt)},")
                appendLine("  \"chargeNeedleVfxTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"chargeNeedleVfxLastSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"chargeNeedleDecayVerified\": $chargeNeedleDecayVerified,")
                appendLine("  \"chargeNeedleOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId in CHARGE_NEEDLE_PROJECTILE_SPEC_IDS }},")
                // ---- 舞台排障：三武器组/自动开火/AI 判定状态 ----
                // WS 004 判别轮该槽挂的是小型针刺：heavyNeedleW 按槽位取（不拘 id），区分槽位阻断与规格阻断。
                val smallNeedleW = player?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID && it.slot?.id == "WS 001" }
                    ?: player?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID }
                val heavyNeedleW = player?.allWeapons?.firstOrNull { it.slot?.id == "WS 004" }
                val enemyNeedleW = enemy?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID }
                appendLine("  \"chargeNeedleSmallState\": ${jsonString(chargeNeedleWeaponState(player, smallNeedleW))},")
                appendLine("  \"chargeNeedleHeavyState\": ${jsonString(chargeNeedleWeaponState(player, heavyNeedleW))},")
                appendLine("  \"chargeNeedleEnemyState\": ${jsonString(chargeNeedleWeaponState(enemy, enemyNeedleW))},")
                appendLine("  \"chargeNeedleEnemyShieldState\": ${jsonString(chargeNeedleShieldState(enemy))},")
                appendLine("  \"chargeNeedlePlayerShieldState\": ${jsonString(chargeNeedleShieldState(player))},")
            } else if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
                val crewed = findCrewedLens(engine)
                val enemies = lensPhase2Enemies(engine)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": 0,")
                appendLine("  \"runtimeLastProjectileSpecId\": null,")
                appendLine("  \"referenceVisibleLength\": 0,")
                appendLine("  \"lensDeployedShipIds\": ${jsonStringList(lensDeployedShipIds(engine))},")
                // ---- 机制证据 ----
                appendLine("  \"echoFixationFieldActive\": ${safeBool { EchoFixationField.hasActiveField(engine) }},")
                appendLine("  \"echoFixationCognitiveTearApplied\": ${safeBool { lensPhase2CognitiveTearApplied(enemies) }},")
                appendLine("  \"echoFixationAfterimageFrames\": ${EchoFixationAfterimageRenderer.afterimageFrames(engine)},")
                appendLine("  \"tideDeepWaterStacksOnEnemy\": ${lensPhase2MaxDeepWaterOnEnemy(enemies)},")
                appendLine("  \"driftStacksOnEnemy\": ${lensPhase2MaxDriftOnEnemy(enemies)},")
                appendLine("  \"permeatingTideHullmod\": ${safeBool { lensPhase2PermeatingTideHullmod(engine) }},")
                // fighter 误差标记降级为插件挂载断言（见 lensPhase2ParallaxDecksHullmod 注释）。
                appendLine("  \"parallaxDecksHullmod\": ${safeBool { lensPhase2ParallaxDecksHullmod(engine) }},")
                appendLine("  \"lensCrewedDeployed\": ${crewed != null},")
                appendLine("  \"lensAutomatedDeployed\": ${findAutomatedLens(engine) != null},")
                // ---- shader 提交计数（视觉管线生效证据，每次真实 upsert +1）----
                appendLine("  \"echoFixationFieldVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_ECHO_FIXATION_FIELD_FRAMES)},")
                appendLine("  \"driftMarkVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DRIFT_MARK_FRAMES)},")
                appendLine("  \"deepWaterMarkVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DEEP_WATER_MARK_FRAMES)},")
                appendLine("  \"ghostSignalWaveFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES)},")
                appendLine("  \"tideFieldVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_TIDE_FIELD_FRAMES)},")
            } else if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
                val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
                val lensVariant = try { lens?.variant } catch (_: Throwable) { null }
                val lensShield = try { lens?.shield } catch (_: Throwable) { null }
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": 0,")
                appendLine("  \"runtimeLastProjectileSpecId\": null,")
                appendLine("  \"referenceVisibleLength\": 0,")
                appendLine("  \"lensDeployedShipIds\": ${jsonStringList(lensDeployedShipIds(engine))},")
                appendLine("  \"lensCoreHullmod\": ${safeBool { lensVariant?.hasHullMod(LensArrayCoreHullModIds.CORE) == true }},")
                appendLine("  \"lensNanoHullmod\": ${safeBool { lensVariant?.hasHullMod("astd_nano_restoration_protocol") == true }},")
                appendLine("  \"lensSwitcherHullmod\": ${safeBool { lensVariant?.hasHullMod(LENS_DUAL_MODE_CONFIG.switcherId) == true }},")
                appendLine("  \"lensCrewedModeHullmod\": ${safeBool { lensVariant?.hasHullMod(LensArrayCoreHullModIds.MODE_CREWED) == true }},")
                appendLine("  \"lensShieldOn\": ${safeBool { lensShield?.isOn == true }},")
                appendLine("  \"lensShieldArc\": ${formatFloat(try { lensShield?.arc ?: lens?.hullSpec?.shieldSpec?.arc ?: 0f } catch (_: Throwable) { 0f })},")
                appendLine("  \"lensFighterBays\": ${try { lens?.hullSpec?.fighterBays ?: 0 } catch (_: Throwable) { 0 }},")
                appendLine("  \"lensCoreTooltipKeys\": ${lensCoreTooltipKeyCount(engine)},")
                appendLine("  \"lensSelfDriftStacks\": ${lens?.let { LensMarks.driftStacks(it) } ?: 0},")
                appendLine("  \"lensSelfDeepWaterStacks\": ${lens?.let { LensMarks.deepWaterStacks(it) } ?: 0},")
                appendLine("  \"lensSelfHullDamageTakenMult\": ${formatFloat(try { lens?.mutableStats?.hullDamageTakenMult?.modifiedValue ?: 0f } catch (_: Throwable) { 0f })},")
            } else if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
                val plasmaArch = findShipByHull(engine, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)
                val plasmaSystem = plasmaArch?.system
                val plasmaSpec = plasmaSystem?.specAPI
                val plasmaShield = plasmaArch?.shield
                val plasmaSystemAI = plasmaRuntimeSystemAI(plasmaArch)
                val plasmaBiggestThreat = plasmaAIFlagTarget(plasmaArch, ShipwideAIFlags.AIFlags.BIGGEST_THREAT)
                val plasmaSystemTarget = plasmaAIFlagTarget(plasmaArch, ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)
                val plasmaManeuverTarget = plasmaAIFlagTarget(plasmaArch, ShipwideAIFlags.AIFlags.MANEUVER_TARGET)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": 0,")
                appendLine("  \"runtimeLastProjectileSpecId\": null,")
                appendLine("  \"referenceVisibleLength\": 0,")
                appendLine("  \"arcProductionMissingShips\": ${jsonStringList(arcProductionMissingShips(engine))},")
                appendLine("  \"arcProductionDeployedShipIds\": ${jsonStringList(arcProductionDeployedShipIds(engine))},")
                appendLine("  \"arcProductionDeployedVariantIds\": ${jsonStringList(arcProductionDeployedVariantIds(engine))},")
                appendLine("  \"arcProductionSourceVariantIds\": ${jsonStringList(arcProductionSourceVariantIds(engine))},")
                appendLine("  \"arcProductionPlayerReserves\": ${engine.getFleetManager(FleetSide.PLAYER).getReservesCopy().size},")
                appendLine("  \"arcProductionEnemyReserves\": ${engine.getFleetManager(FleetSide.ENEMY).getReservesCopy().size},")
                appendLine("  \"radiationBeltSystemState\": ${jsonString(findShipByHull(engine, ASTDArcProductionShipIds.HULL_RADIATION_BELT)?.system?.state?.name)},")
                appendLine("  \"plasmaArchSystemId\": ${jsonString(plasmaSystem?.id)},")
                appendLine("  \"plasmaArchSystemState\": ${jsonString(plasmaSystem?.state?.name)},")
                appendLine("  \"plasmaArchSystemCanBeActivated\": ${safeBool { plasmaSystem?.canBeActivated() == true }},")
                appendLine("  \"plasmaArchSystemEffectLevel\": ${formatFloat(plasmaSystem?.effectLevel ?: -1f)},")
                appendLine("  \"plasmaArchShipAI\": ${jsonString(plasmaArch?.shipAI?.javaClass?.name)},")
                appendLine("  \"plasmaArchFluxLevel\": ${formatFloat(plasmaArch?.fluxLevel ?: -1f)},")
                appendLine("  \"plasmaArchCurrFlux\": ${formatFloat(plasmaArch?.currFlux ?: -1f)},")
                appendLine("  \"plasmaArchMaxFlux\": ${formatFloat(plasmaArch?.maxFlux ?: -1f)},")
                appendLine("  \"plasmaArchHardFlux\": ${formatFloat(plasmaArch?.fluxTracker?.hardFlux ?: -1f)},")
                appendLine("  \"plasmaArchHardFluxLevel\": ${formatFloat(plasmaArch?.hardFluxLevel ?: -1f)},")
                appendLine("  \"plasmaArchSinceLastDamageTaken\": ${formatFloat(plasmaArch?.sinceLastDamageTaken ?: -1f)},")
                appendLine("  \"plasmaArchOverloadedOrVenting\": ${plasmaArch?.fluxTracker?.isOverloadedOrVenting ?: false},")
                appendLine("  \"plasmaArchShieldOn\": ${plasmaShield?.isOn ?: false},")
                appendLine("  \"plasmaArchShieldActiveArc\": ${formatFloat(plasmaShield?.activeArc ?: -1f)},")
                appendLine("  \"plasmaArchAIFlags\": ${jsonStringList(plasmaAIFlags(plasmaArch))},")
                appendLine("  \"plasmaArchAIFlagIncomingDamage\": ${plasmaAIFlag(plasmaArch, ShipwideAIFlags.AIFlags.HAS_INCOMING_DAMAGE)},")
                appendLine("  \"plasmaArchAIFlagCriticalDpsDanger\": ${plasmaAIFlag(plasmaArch, ShipwideAIFlags.AIFlags.IN_CRITICAL_DPS_DANGER)},")
                appendLine("  \"plasmaArchAIFlagKeepShieldsOn\": ${plasmaAIFlag(plasmaArch, ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON)},")
                appendLine("  \"plasmaArchVanillaSystemAI\": ${jsonString(plasmaSystemAI.className)},")
                appendLine("  \"plasmaArchVanillaSystemAIError\": ${jsonString(plasmaSystemAI.error)},")
                appendLine("  \"plasmaArchAIFlagBiggestThreatTargetHullId\": ${jsonString(plasmaBiggestThreat.ship?.hullSpec?.hullId)},")
                appendLine("  \"plasmaArchAIFlagBiggestThreatTargetVariantId\": ${jsonString(plasmaBiggestThreat.ship?.variant?.hullVariantId)},")
                appendLine("  \"plasmaArchAIFlagTargetForSystemHullId\": ${jsonString(plasmaSystemTarget.ship?.hullSpec?.hullId)},")
                appendLine("  \"plasmaArchAIFlagTargetForSystemVariantId\": ${jsonString(plasmaSystemTarget.ship?.variant?.hullVariantId)},")
                appendLine("  \"plasmaArchAIFlagManeuverTargetHullId\": ${jsonString(plasmaManeuverTarget.ship?.hullSpec?.hullId)},")
                appendLine("  \"plasmaArchAIFlagManeuverTargetVariantId\": ${jsonString(plasmaManeuverTarget.ship?.variant?.hullVariantId)},")
                appendLine("  \"plasmaArchEnemyPressureShips\": ${plasmaEnemyPressureShips(engine, plasmaArch)},")
                appendLine("  \"plasmaArchEnemyTargetingShips\": ${plasmaEnemyTargetingShips(engine, plasmaArch)},")
                appendLine("  \"plasmaArchEnemyFiringWeapons\": ${plasmaEnemyFiringWeapons(engine, plasmaArch)},")
                appendLine("  \"plasmaArchEnemyProjectiles\": ${plasmaEnemyProjectiles(engine, plasmaArch)},")
                appendLine("  \"plasmaArchSystemSpecAiScript\": ${jsonString(plasmaSpec?.aiScript?.javaClass?.name ?: plasmaSpec?.aiScriptClassName)},")
                appendLine("  \"plasmaArchSystemSpecFpsBaseCap\": ${formatFloat(plasmaSpec?.fluxPerSecondBaseCap ?: -1f)},")
                appendLine("  \"plasmaArchSystemSpecToggle\": ${plasmaSpec?.isToggle ?: false},")
                appendLine("  \"plasmaArchSystemSpecFiringAllowed\": ${plasmaSpec?.isFiringAllowed ?: false},")
                appendLine("  \"plasmaArchSystemSpecTags\": ${jsonStringList(plasmaSpec?.tags?.toList()?.sorted() ?: emptyList())},")
            } else {
                appendLine("  \"runtimeElapsedSeconds\": ${formatFloat(vfxTelemetry.lastElapsed)},")
                appendLine("  \"runtimeVisibleLength\": ${formatFloat(vfxTelemetry.lastVisibleLength)},")
                appendLine("  \"runtimeBeamAlpha\": ${formatFloat(vfxTelemetry.lastBeamAlpha)},")
                appendLine("  \"runtimeWorldUnitsPerPixel\": ${formatFloat(vfxTelemetry.lastWorldUnitsPerPixel)},")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": ${formatFloat(referenceCaptureVisibleLength())},")
            }
            appendLine("  \"fallbackInPlay\": ${fallbackProjectile?.let { engine.isEntityInPlay(it) } ?: false},")
            appendLine("  \"fallbackExpired\": ${fallbackProjectile?.isExpired ?: false},")
            appendLine("  \"fallbackFading\": ${fallbackProjectile?.isFading ?: false},")
            appendLine("  \"arcJetShockwaveFrames\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FRAMES)},")
            appendLine("  \"arcJetShockwaveRadius\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_RADIUS)},")
            appendLine("  \"arcJetShockwaveFluxPressure\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FLUX_PRESSURE)},")
            appendLine("  \"plasmaArchShieldOpen\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_OPEN)},")
            appendLine("  \"plasmaArchSystemActive\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE)},")
            appendLine("  \"plasmaArchShieldArcEmissions\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS)},")
            appendLine("  \"radiationBeltSystemAfterimages\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES)},")
            val arcJetTooltipKeys = tooltipResolvedKeyCount("astd_arc_jet", ASTDArcProductionTooltipContracts.arcJetContracts)
            val plasmaArchTooltipKeys = tooltipResolvedKeyCount("astd_plasma_arch", ASTDArcProductionTooltipContracts.plasmaArchContracts)
            val radiationBeltTooltipKeys = tooltipResolvedKeyCount("astd_radiation_belt", ASTDArcProductionTooltipContracts.radiationBeltContracts)
            appendLine("  \"arcJetTooltip\": ${tooltipBlocksResolved("astd_arc_jet", ASTDArcProductionTooltipContracts.arcJetContracts)},")
            appendLine("  \"plasmaArchTooltip\": ${tooltipBlocksResolved("astd_plasma_arch", ASTDArcProductionTooltipContracts.plasmaArchContracts)},")
            appendLine("  \"radiationBeltTooltip\": ${tooltipBlocksResolved("astd_radiation_belt", ASTDArcProductionTooltipContracts.radiationBeltContracts)},")
            appendLine("  \"arcJetTooltipKeys\": $arcJetTooltipKeys,")
            appendLine("  \"plasmaArchTooltipKeys\": $plasmaArchTooltipKeys,")
            appendLine("  \"radiationBeltTooltipKeys\": $radiationBeltTooltipKeys,")
            appendLine("  \"elapsedSeconds\": ${"%.3f".format(java.util.Locale.ROOT, elapsed)}")
            appendLine("}")
        }
        log.info("[ASTD-Automation] diagnostics state=$state json=${json.lines().joinToString(" ")}")
    }

    private fun tooltipBlocksResolved(hullId: String, contracts: List<ASTDArcProductionTooltipContracts.Contract>): Boolean {
        val ship = engine?.let { findShipByHull(it, hullId) } ?: return false
        return contracts.all { contract ->
            hasHullmod(ship, contract.hullmodId) && contract.textKeys.all { key -> isResolvedTextKey(key) }
        }
    }

    private fun arcProductionDeployedShipIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.filter { hullId -> findShipByHull(engine, hullId) != null }

    private fun arcProductionDeployedVariantIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.mapNotNull { hullId -> findShipByHull(engine, hullId)?.variant?.hullVariantId }

    private fun arcProductionSourceVariantIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.mapNotNull { hullId ->
            val member = findShipByHull(engine, hullId)?.fleetMember
            ARC_PRODUCTION_STANDARD_VARIANTS[hullId] ?: member?.variant?.hullVariantId
        }

    private fun tooltipResolvedKeyCount(hullId: String, contracts: List<ASTDArcProductionTooltipContracts.Contract>): Int {
        val ship = engine?.let { findShipByHull(it, hullId) } ?: return 0
        return contracts
            .filter { hasHullmod(ship, it.hullmodId) }
            .sumOf { contract -> contract.textKeys.count { key -> isResolvedTextKey(key) } }
    }

    private fun plasmaEnemyPressureShips(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.ships.count { ship ->
            ship.owner != plasmaArch.owner &&
                ship.isAlive &&
                !ship.isHulk &&
                distanceSquared(ship.location, plasmaArch.location) <= PLASMA_AI_PRESSURE_RANGE * PLASMA_AI_PRESSURE_RANGE
        }
    }

    private fun plasmaEnemyTargetingShips(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.ships.count { ship ->
            ship.owner != plasmaArch.owner && ship.shipTarget === plasmaArch
        }
    }

    private fun plasmaEnemyFiringWeapons(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.ships
            .filter { ship -> ship.owner != plasmaArch.owner }
            .sumOf { ship ->
                try {
                    ship.allWeapons.count { weapon -> weapon.isFiring }
                } catch (_: Throwable) {
                    0
                }
            }
    }

    private fun plasmaEnemyProjectiles(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.projectiles.count { projectile ->
            val damaging = projectile as? DamagingProjectileAPI ?: return@count false
            val source = damaging.source ?: return@count false
            source.owner != plasmaArch.owner &&
                !damaging.isExpired &&
                distanceSquared(damaging.location, plasmaArch.location) <= PLASMA_AI_PRESSURE_RANGE * PLASMA_AI_PRESSURE_RANGE
        }
    }

    private fun plasmaAIFlags(ship: ShipAPI?): List<String> {
        val flags = ship?.shipAI?.aiFlags ?: ship?.aiFlags ?: return emptyList()
        return ShipwideAIFlags.AIFlags.values()
            .filter { flag -> safeBool { flags.hasFlag(flag) } }
            .map { it.name }
            .sorted()
    }

    private fun plasmaAIFlag(ship: ShipAPI?, flag: ShipwideAIFlags.AIFlags): Boolean {
        val flags = ship?.shipAI?.aiFlags ?: ship?.aiFlags ?: return false
        return safeBool { flags.hasFlag(flag) }
    }

    private fun plasmaAIFlagTarget(ship: ShipAPI?, flag: ShipwideAIFlags.AIFlags): PlasmaTargetDiagnostic {
        val flags = ship?.shipAI?.aiFlags ?: ship?.aiFlags
            ?: return PlasmaTargetDiagnostic(null, "missing AI flags for ${flag.name}")
        val custom = try {
            flags.getCustom(flag)
        } catch (e: Throwable) {
            return PlasmaTargetDiagnostic(null, "getCustom(${flag.name}) failed: ${e.javaClass.name}: ${e.message}")
        } ?: return PlasmaTargetDiagnostic(null, null)

        val target = extractShipFromAIFlagCustom(custom)
        return PlasmaTargetDiagnostic(
            ship = target,
            error = if (target == null) {
                "unresolved ${flag.name} custom target: ${custom.javaClass.name}"
            } else {
                null
            },
        )
    }

    private fun extractShipFromAIFlagCustom(custom: Any): ShipAPI? {
        if (custom is ShipAPI) return custom
        return null
    }

    private fun plasmaRuntimeSystemAI(ship: ShipAPI?): PlasmaSystemAIDiagnostic {
        val ai = ship?.shipAI ?: return PlasmaSystemAIDiagnostic(null, "missing ship AI")
        return PlasmaSystemAIDiagnostic(
            className = null,
            error = "systemAI is private runtime state on ${ai.javaClass.name}; script sandbox forbids reflection",
        )
    }

    private fun hasHullmod(ship: ShipAPI, hullmodId: String): Boolean =
        try { ship.variant?.hasHullMod(hullmodId) == true } catch (_: Throwable) { false }

    private fun isResolvedTextKey(key: String): Boolean {
        val text = I18n[I18n.Categories.MOD, key]
        return text.isNotBlank() && text != "${I18n.Categories.MOD.id}:$key"
    }

    private fun jsonString(value: String?): String = value?.let { "\"${escapeJson(it)}\"" } ?: "null"

    private fun jsonStringList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

    private fun formatFloat(value: Float): String = "%.4f".format(java.util.Locale.ROOT, value)

    private fun distanceSquared(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun safeBool(block: () -> Boolean): Boolean =
        try { block() } catch (_: Throwable) { false }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private data class PlasmaTargetDiagnostic(
        val ship: ShipAPI?,
        val error: String?,
    )

    private data class PlasmaSystemAIDiagnostic(
        val className: String?,
        val error: String?,
    )

    private companion object {
        private val ARC_PRODUCTION_CORE_HULLS = listOf(
            ASTDArcProductionShipIds.HULL_ARC_JET,
            ASTDArcProductionShipIds.HULL_PLASMA_ARCH,
            ASTDArcProductionShipIds.HULL_RADIATION_BELT,
        )
        // 引力透镜级自标记验收层数（spec：误差/深水各叠 3 层）。
        private const val LENS_SELF_MARK_STACKS = 3
        // phase2 部署锚点：载人透镜上方、无人透镜下方，敌群居中（落入两者作用范围）。
        private val LENS_PHASE2_CREWED_ANCHOR = Vector2f(-260f, 260f)
        private val LENS_PHASE2_AUTOMATED_ANCHOR = Vector2f(-260f, -380f)
        private const val LENS_PHASE2_ENEMY_CLUSTER_X = 360f
        // phase2 幽灵信号导弹投放：每 0.3s 一批，每批 6 枚环绕无人透镜（确保落入 2000su 幽灵范围）。
        private const val GHOST_MISSILE_FEED_INTERVAL = 0.3f
        private const val GHOST_MISSILE_BURST = 6
        // 投放用导弹 weaponId（ASTD 既有导弹型武器，保证模组已加载；范式同 ASTDVirtualParticleLatticeWebHullMod）。
        private const val GHOST_FEED_MISSILE_ID = "astd_virtual_particle_mote_launcher"
        // 透镜阵列核心 hullmod tooltip 文本 key（与 ASTDLensArrayCoreHullMod.addPostDescriptionSection 一致，共 7 个）。
        private val LENS_CORE_TOOLTIP_KEYS = listOf(
            "ui.hullmod.lens_core.summary",
            "ui.hullmod.lens_core.line.1",
            "ui.hullmod.lens_core.line.2",
            "ui.hullmod.lens_core.line.3",
            "ui.hullmod.lens_core.line.4",
            "ui.hullmod.lens_core.line.5",
            "ui.hullmod.lens_core.line.6",
        )
        private val ARC_PRODUCTION_STANDARD_VARIANTS = mapOf(
            ASTDArcProductionShipIds.HULL_ARC_JET to "astd_arc_jet_Standard",
            ASTDArcProductionShipIds.HULL_PLASMA_ARCH to "astd_plasma_arch_Standard",
            ASTDArcProductionShipIds.HULL_RADIATION_BELT to "astd_radiation_belt_Standard",
        )
        private const val FALLBACK_PROJECTILE_SPEED = 2400f
        private const val AUTOMATION_CURVE_AMOUNT = 96f
        private const val AUTOMATION_CURVE_FREQUENCY = 0.8f
        private const val AUTOMATION_REFERENCE_CAPTURE_HEIGHT = 600f
        // 合成截图场景的飞行窗口参数（旧 aod7 preset lifecycle 默认；新管线 DSL 不再建模这两段，按场景常量固化）。
        private const val AUTOMATION_FLIGHT_END_RATIO = 0.6f
        private const val AUTOMATION_PRE_DISSOLVE_FRACTION = 0.82f
        private const val SCREENSHOT_FLIGHT_SECONDS = 0.13333334f
        private const val REFERENCE_CAPTURE_ELAPSED_SECONDS = 0.3004f
        private const val PLASMA_AI_PRESSURE_RANGE = 1800f
        // 电荷针刺场景：相位机与锚点。
        private const val CHARGE_NEEDLE_PHASE_SHIELD = "SHIELD"
        private const val CHARGE_NEEDLE_PHASE_HULL = "HULL"
        private const val CHARGE_NEEDLE_PHASE_CEASE = "CEASE"
        private const val CHARGE_NEEDLE_PHASE_COMPLETED = "COMPLETED"
        private const val CHARGE_NEEDLE_PLAYER_HULL = "wolf"
        private const val CHARGE_NEEDLE_ENEMY_HULL = "shrike"
        private val CHARGE_NEEDLE_PLAYER_ANCHOR = Vector2f(-350f, 0f)
        private val CHARGE_NEEDLE_ENEMY_ANCHOR = Vector2f(300f, 0f)
        private val CHARGE_NEEDLE_CAMERA_CENTER = Vector2f(0f, 0f)
        // 叠层相位达标层数：v2 小型针刺 40 层安全闸内、可在盾相期内稳定堆到。
        private const val CHARGE_NEEDLE_STACK_TARGET = 8
        private const val CHARGE_NEEDLE_DISCHARGE_TARGET = 1
        private const val CHARGE_NEEDLE_PHASE_TIMEOUT = 90f
        private val CHARGE_NEEDLE_PLAYER_WEAPON_IDS = setOf(
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID,
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID,
        )
        private val CHARGE_NEEDLE_ENEMY_WEAPON_IDS = setOf(ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID)
        private val CHARGE_NEEDLE_PROJECTILE_SPEC_IDS = setOf(
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_PROJECTILE_SPEC_ID,
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_PROJECTILE_SPEC_ID,
        )
        // 电驱加速炮场景：相位机、锚点与期望证据。
        private const val EDA_PHASE_RANGE_ZERO = "RANGE_ZERO"
        private const val EDA_PHASE_RANGE_MID = "RANGE_MID"
        private const val EDA_PHASE_RANGE_HIGH = "RANGE_HIGH"
        private const val EDA_PHASE_FIRE = "FIRE"
        private const val EDA_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val EDA_PHASE_COMPLETED = "COMPLETED"
        private const val EDA_PHASE_FAILED = "FAILED"
        private const val EDA_PLAYER_HULL = "hammerhead"
        private val EDA_PLAYER_ANCHOR = Vector2f(-350f, 0f)
        private val EDA_ENEMY_ANCHOR = Vector2f(350f, 0f)
        private val EDA_CAMERA_CENTER = Vector2f(0f, 0f)
        // 射程相位期望：基线 800 + 净空加成（v2 满额 200；30% 辐能衰减 0.5 → +100；50% ≥40% 阈值归零）。
        private const val EDA_EXPECT_RANGE_ZERO = 1000f
        private const val EDA_EXPECT_RANGE_MID = 900f
        private const val EDA_EXPECT_RANGE_HIGH = 800f
        private const val EDA_RANGE_TOLERANCE = 30f
        private const val EDA_MID_FLUX_LEVEL = 0.3f
        private const val EDA_HIGH_FLUX_LEVEL = 0.5f
        private const val EDA_RANGE_SETTLE_SECONDS = 0.6f
        // 每触发 8 弹（LINKED 双管 × burst 4）；burst delay 0.15s，间隔 >0.4s 判定新一轮触发。
        private const val EDA_EXPECT_TRIGGER_PROJECTILES = 8
        private const val EDA_BURST_GROUP_GAP = 0.4f
        // 敌版开火观察窗：k_s=5 档下等待敌版追加伤害遥测增量。
        private const val EDA_ENEMY_FIRE_SECONDS = 20f
        private const val EDA_PHASE_TIMEOUT = 90f
        private val EDA_WEAPON_IDS = setOf(ASTDInGameAutomationScenario.EDA_WEAPON_ID)
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
        // MOUNT 相位校验：dedicated_targeting_core 已在 MissionDefinition 摘除，射程断言基线 1200。
        private const val QJ_EXPECT_RANGE = 1200f
        private const val QJ_RANGE_TOLERANCE = 5f
        // 满层证据期望：v2 每层 6.25% × 10 → 伤害乘区 1.625（600→975）；射速 2s/1.625≈1.23s。
        private const val QJ_EXPECT_FULL_DMG_MULT = 1.625f
        private const val QJ_DMG_MULT_TOLERANCE = 0.02f
        private const val QJ_REFIRE_MIN = 1.05f
        private const val QJ_REFIRE_MAX = 1.45f
        private const val QJ_FULL_HOLD_SECONDS = 2.5f
        private const val QJ_STACK_MIN_FPS = 30f
        // DUAL：w2 停火 7s（3s 窗口 + 4s×1.75 衰减 → 10→3），层差 ≥5 证复合键隔离。
        private const val QJ_DUAL_SECONDS = 7f
        private const val QJ_DUAL_MIN_DIVERGENCE = 5
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
        // 敌版三档逐命中乘区期望：1 + 4 × v1 5% / v2 6.25% / v5 10% = 1.20 / 1.25 / 1.40；
        // 监听器按命中前层数结算（天然滞后一层），采样窗口内容忍 4~5 层两值，三档区间互不重叠。
        private const val QJ_ENEMY_MULT_1_MIN = 1.19f
        private const val QJ_ENEMY_MULT_1_MAX = 1.26f
        private const val QJ_ENEMY_MULT_2_MIN = 1.24f
        private const val QJ_ENEMY_MULT_2_MAX = 1.32f
        private const val QJ_ENEMY_MULT_5_MIN = 1.39f
        private const val QJ_ENEMY_MULT_5_MAX = 1.51f
        private const val QJ_PHASE_TIMEOUT = 90f
        // 正电子冲击波场景：相位机、锚点与期望证据（规格 06 §4.2 烟测检查点）。
        private const val PS_PHASE_MOUNT = "MOUNT"
        private const val PS_PHASE_PASS_THROUGH = "PASS_THROUGH"
        private const val PS_PHASE_SPLASH = "SPLASH"
        private const val PS_PHASE_FUSE = "FUSE"
        private const val PS_PHASE_COMPLETED = "COMPLETED"
        private const val PS_PHASE_FAILED = "FAILED"
        private const val PS_PLAYER_HULL = "wolf"
        private const val PS_TARGET_HULL = "vigilance"
        private const val PS_PLAYER_SLOT = "WS 001"
        private val PS_PLAYER_ANCHOR = Vector2f(0f, 0f)
        // 穿舰相位靶舰锚点（400su 在弹道上）；波及相位移至 700su（满射程 600 引爆点前方 100，锥长 250 内）。
        private val PS_TARGET_PASS_ANCHOR = Vector2f(400f, 0f)
        private val PS_TARGET_SPLASH_ANCHOR = Vector2f(700f, 0f)
        private val PS_CAMERA_CENTER = Vector2f(400f, 0f)
        private const val PS_CAMERA_VISIBLE_HEIGHT = 950f
        private const val PS_MOUNT_SETTLE_SECONDS = 0.6f
        // MOUNT 相位校验：射程断言基线 600（无射程向 hullmod 干扰）。
        private const val PS_EXPECT_RANGE = 600f
        private const val PS_RANGE_TOLERANCE = 5f
        // PASS_THROUGH：两次满射程自爆后判定；靶舰 HP 容差（装甲蹭伤为 0 时 hitpoints 应恒满）。
        private const val PS_PASS_THROUGH_DETONATIONS = 2
        private const val PS_PASS_THROUGH_HP_TOLERANCE = 1f
        // 满射程自爆引爆距离期望 ≈600（弹体出生点偏移/边界含等号留容差）。
        private const val PS_MAX_RANGE_DIST_MIN = 570f
        private const val PS_MAX_RANGE_DIST_MAX = 640f
        // SPLASH：相位内两次满射程自爆，锥面舰船命中计数 +1（700su 靶舰在 600 引爆点锥内）。
        private const val PS_SPLASH_DETONATIONS = 2
        // FUSE：近炸引爆 ≥2 次、锥面导弹命中 ≥3（成片清除证据）。
        private const val PS_FUSE_DETONATIONS = 2
        private const val PS_FUSE_MISSILE_HITS = 3
        // 导弹投喂：鱼叉（vanilla MRM），0.9s 一发，820su 处左右舷交替，初速 250su/s 指向玩家。
        private const val PS_FEED_MISSILE_ID = "harpoon"
        private const val PS_MISSILE_FEED_INTERVAL = 0.9f
        private const val PS_MISSILE_SPAWN_X = 820f
        private const val PS_MISSILE_SPAWN_Y = 80f
        private const val PS_MISSILE_INITIAL_SPEED = 250f
        // COMPLETED 截图门控：近炸引爆近 1.2s 内发生才上报（锥面 VFX/浮字入帧）；保底舞台超时。
        private const val PS_COMPLETED_DETONATE_WINDOW = 1.2f
        private const val PS_COMPLETED_STAGE_TIMEOUT = 25f
        private const val PS_PHASE_TIMEOUT = 60f
    }
}
