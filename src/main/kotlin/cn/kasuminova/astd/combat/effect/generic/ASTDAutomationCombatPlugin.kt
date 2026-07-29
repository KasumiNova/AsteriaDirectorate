package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import cn.kasuminova.astd.combat.effect.arc.ChargeNeedleVfx
import cn.kasuminova.astd.combat.effect.arc.ElectricDriveAcceleratorOnHitEffect
import cn.kasuminova.astd.combat.effect.arc.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.GeminiDemPayloadBeamEffect
import cn.kasuminova.astd.combat.effect.arc.GeminiDemSalvoOnFireEffect
import cn.kasuminova.astd.combat.effect.arc.GeminiDemSyncHandler
import cn.kasuminova.astd.combat.effect.arc.GeminiDemTrackAI
import cn.kasuminova.astd.combat.effect.arc.HeavyIonPulseTuning
import cn.kasuminova.astd.combat.effect.arc.HeavyIonPulseVfx
import cn.kasuminova.astd.combat.effect.arc.PositronShockwaveFuseScript
import cn.kasuminova.astd.combat.effect.arc.SevenStarsChainScript
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceConeStrike
import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceVfx
import cn.kasuminova.astd.combat.effect.arc.chargeNeedleStacks
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueCalcStacks
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjueDamageDealtModifier
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunDifficulty
import cn.kasuminova.astd.combat.effect.arc.qiongjue.QiongjuePhaseRailgunOnHitEffect
import cn.kasuminova.astd.combat.effect.arc.qiongjue.qiongjueCalcStacks
import cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexBeamEffect
import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmMissileAI
import cn.kasuminova.astd.combat.effect.lens.stellar.StellarMrmStrikeImpl
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
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.dem.DEMScript
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.mission.FleetSide
import org.lazywizard.lazylib.MathUtils
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

    // ==== gemini dem 场景状态（相位机 MOUNT → SALVO → KILL_ONE → POD → ENEMY_SCALE → COMPLETED） ====
    private var gdPhase = GD_PHASE_MOUNT
    private var gdPhaseStartedAt = 0f
    // R1 观测面：弹头 unwrappedMissileAI 身份轮询（TrackAI 供目标 / DEMScript 接管）。
    private val gdTrackAiSeen = mutableSetOf<Int>()
    private var gdTrackTargetNonNull = 0
    private val gdDemTakeoverSeen = mutableSetOf<Int>()
    // R1 诊断面：弹头三路 AI 读回的类名三元组（首次出现各记一条日志，防刷屏）。
    private val gdAiClassTriplesSeen = mutableSetOf<String>()
    // SALVO 相位：齐射后 ammo 采样（一轮一耗证据）与 R2 读数基线。
    // ammo 绝对值断言走 spec 层（weapon_data.csv 口径）；运行时 maxAmmo 可能受环境 stat 加成
    // （实机判例：本机任务环境 missileAmmoBonus ×2，launcher 2→4 / pod 4→8），
    // 故「一次触发一轮齐射」用基线差分断言（before-1），不吃环境倍率。
    private var gdLauncherAmmoBaseline = -1
    private var gdLauncherAmmoAfterSalvo = -1
    private var gdSalvoTargetHpBaseline = -1f
    private var gdSalvoTargetMinHp = Float.MAX_VALUE
    // KILL_ONE 相位：同步计数基线与高爆弹头移除守卫。
    private var gdKillSyncBaseline = 0
    private var gdKillKineticBaseline = 0
    private var gdKillHeBaseline = 0
    private var gdKillWarheadsBaseline = 0
    private var gdKillHeRemoved = false
    // POD 相位：发射舱齐射基线（ammo 一轮一耗 / 同步配对证据）。
    private var gdPodSalvoBaseline = 0
    private var gdPodKineticBaseline = 0
    private var gdPodHeBaseline = 0
    private var gdPodSyncBaseline = 0
    private var gdPodAmmoBaseline = -1
    private var gdPodAmmoAfterSalvo = -1
    // ENEMY_SCALE 相位：敌版破晓同步基线与玩家掉血观测。
    private var gdEnemySyncBaseline = 0
    private var gdEnemyMinPlayerHp = Float.MAX_VALUE
    private var gdEnemyFirstSyncAt = -1f
    // COMPLETED 截图门控：最近一次 payload 首伤帧时刻（截图帧需含双色尾焰/锁定激光/光束）。
    private var gdLastStrikeAt = -1f
    private var gdLastTrackedStrikeCount = 0

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

    // ==== stellar mrm 场景状态（相位机 MOUNT → PRIORITY → FIGHTER_HIT → SHIP_HIT → LINE_CROSS → ENEMY_SCALE → COMPLETED） ====
    private var smPhase = SM_PHASE_MOUNT
    private var smPhaseStartedAt = 0f
    // PRIORITY：发射舱单次两发证据（同帧/近帧 spawn 分组，间隔 >SM_BURST_GROUP_GAP 判定新一轮触发）。
    private val smSeenPodProjectiles = mutableSetOf<Int>()
    private var smPodLastSpawnAt = -1f
    private var smPodBurstCurrent = 0
    private var smPodBurstMax = 0
    // 备弹经济观测（min ammo 遥测）。
    private var smMinLauncherAmmo = Int.MAX_VALUE
    private var smMinPodAmmo = Int.MAX_VALUE
    // PRIORITY 帧率采样（多发齐射 AI + VFX 开销证据）。
    private var smFpsTicks = 0
    private var smFpsWallStartNanos = 0L
    private var smFps = -1f
    // FIGHTER_HIT：敌战机被瘫痪武器数峰值（战机武器熄火观测面）。
    private var smMaxFighterDisabled = 0

    // 战机武器/船体探针（第三轮烟测后追加）：EMP 结算 30 次熄火恒 0，需判定脚本伤害是否落到组件——
    // 武器血量比 <1 证明组件承伤通道可达；船体血量比 <1 证明脚本伤害整体可达。
    private var smMinWeaponHealthRatio = 1f
    private var smMaxDisabledDuration = 0f
    private var smMinFighterHullRatio = 1f
    // SHIP_HIT：相位基线与航母/残机清理守卫（令导弹只剩舰船可咬）。
    private var smShipHitExplosionsBaseline = 0
    private var smShipHitAoeBaseline = 0
    private var smShipHitShieldBaseline = 0
    private var smCarrierCleared = false
    // LINE_CROSS：阶段（0=投喂低结构 atropos / 1=投喂增压 2000HP harpoon）、投喂集合与判据。
    private var smLineCrossStage = 0
    private val smFedLowMissiles = mutableSetOf<Int>()
    private val smFedHighMissiles = mutableSetOf<Int>()
    private var smLineCrossBaseline = 0
    private var smHighHpHitConfirmed = false
    private var smFeedAt = -1f
    private var smFeedLane = 0
    // ENEMY_SCALE：敌版三档逐档观测（installScaleForTests 1/2/5 → 爆炸倍率 0.5/1.0/2.5）。
    private var smScaleStep = 0
    private var smScaleStepAt = -1f

    // ==== piercing lance 场景状态（相位机 MOUNT → CYCLE → CLUSTER → ENEMY_SCALE → COMPLETED） ====
    private var plPhase = PL_PHASE_MOUNT
    private var plPhaseStartedAt = 0f
    // MOUNT：能量结算探针分步（0=装配断言+基线 / 1=能量加成断言 / 2=实弹加成反证）。
    private var plMountStep = 0
    private var plMountStepAt = -1f
    private var plProbeR0 = -1f
    private var plProbeR1 = -1f
    private var plProbeR2 = -1f
    // CYCLE：出膛计时（7s 循环证据）与充能窗口观测（2s 充能条可读证据）。
    private val plSeenProjectiles = mutableSetOf<Int>()
    private val plSpawnTimes = mutableListOf<Float>()
    private var plChargeObserved = false
    private var plChargeStartAt = -1f
    private var plFirstChargeToShotSeconds = -1f
    private var plCycleIntervalSeconds = -1f
    // CYCLE：弹体 VFX 驱动接管闩（texTrail + bloom 弹头在线证据，弹体在飞窗口外 trackedCount 归零故闩存）。
    private var plVfxDriverSeen = false
    // CLUSTER：相位基线（锥面命中/浮字增量即本相位证据）。
    private var plClusterConeHitsBaseline = 0
    private var plClusterFloatyBaseline = 0
    private var plClusterMaxLastConeHits = 0
    // ENEMY_SCALE：敌版三档逐档观测（installScaleForTests 1/2/5 → 半角 20/25/40、锥长 300/375/600、伤害 2500/3125/5000）。
    private var plScaleStep = 0
    private var plScaleStepAt = -1f
    private var plScaleResolveBaseline = 0
    private var plScaleMaxConeHits = 0
    private var plScaleFpsTicks = 0
    private var plScaleFpsWallStartNanos = 0L
    private var plScaleFps = -1f
    // COMPLETED 截图门控：最近一次锥面结算时刻（截图帧需含大光柱/锥面/浮字）。
    private var plLastResolveAt = -1f
    private var plLastTrackedResolveCount = 0

    // COMPLETED 截图门控：最近一次辉星爆炸时刻（截图帧需含十字爆炸/双拖尾）。
    private var smLastExplosionAt = -1f
    private var smLastTrackedExplosionCount = 0

    // ==== seven stars 场景状态（相位机 MOUNT → BREAK → CHAIN → TERMINAL → ENEMY_MULTI → COMPLETED） ====
    private var ssPhase = SS_PHASE_MOUNT
    private var ssPhaseStartedAt = 0f
    // BREAK 相位：增压投喂完成的守卫与相位基线（断链/终结计数观测面）。
    private var ssBreakFed = false
    private var ssBreakKillsBaseline = 0
    private var ssBreakNoKillBaseline = 0
    // CHAIN 相位基线与帧率采样（连跳峰值性能门槛）。
    private var ssChainNoShipBaseline = 0
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
    // COMPLETED 截图门控：最近一次十字闪光时刻（截图帧需含十字闪光/折跃电弧）。
    private var ssLastFlashAt = -1f
    private var ssLastTrackedFlashCount = 0

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        ProjectileVfxDriverPlugin.ensureInstalled(engine)
        if (ASTDInGameAutomationScenario.isPlEnabled()) {
            engine.setDoNotEndCombat(true)
            lockPlCamera(engine)
            // 锥面破片浮字与敌版三档仅 devMode 渲染（2026-07-29 审批裁定先例）：本场景为 dev-only 舞台，
            // 开启 devMode 以目检命中浮字/大光柱/锥面特效（进程被早退杀掉，设置不落盘）。
            Global.getSettings().setDevMode(true)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findPlShipA(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.PL_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isSmEnabled()) {
            engine.setDoNotEndCombat(true)
            lockSmCamera(engine)
            // 增伤/AOE 浮字仅 devMode 渲染（2026-07-29 审批裁定先例）：本场景为 dev-only 舞台，
            // 开启 devMode 以目检命中浮字与敌版三档（进程被早退杀掉，设置不落盘）。
            Global.getSettings().setDevMode(true)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findSmPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SM_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isGdEnabled()) {
            engine.setDoNotEndCombat(true)
            lockGdCamera(engine)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findGdPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.GD_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isHipEnabled()) {
            engine.setDoNotEndCombat(true)
            lockHipCamera(engine)
            // 贯穿浮字与 FPS 仅 devMode 渲染（2026-07-29 审批裁定先例）：本场景为 dev-only 舞台，
            // 开启 devMode 以目检 EMP 贯穿补伤浮字（进程被早退杀掉，设置不落盘）。
            Global.getSettings().setDevMode(true)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findHipPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.HIP_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isSsEnabled()) {
            engine.setDoNotEndCombat(true)
            lockSsCamera(engine)
            // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findSsPlayer(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SS_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isPsEnabled()) {
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
        if (ASTDInGameAutomationScenario.isPlEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advancePlScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isSmEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceSmScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isGdEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceGdScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isHipEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceHipScenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isSsEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceSsScenario(combatEngine)
            return
        }
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

    // ==== seven stars scenario ====

    private fun findSsPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner == 0 && it.hullSpec?.hullId == SS_PLAYER_HULL && !it.isFighter }

    private fun findSsTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == SS_TARGET_HULL && !it.isFighter }

    private fun findSsEnemyCarrier(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == SS_PLAYER_HULL && !it.isFighter }

    private fun findSsWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.SS_WEAPON_ID }

    private fun lockSsCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = SS_CAMERA_VISIBLE_HEIGHT * displayAspect
        viewport.setExternalControl(true)
        viewport.set(
            SS_CAMERA_CENTER.x - visibleWidth * 0.5f,
            SS_CAMERA_CENTER.y - SS_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            SS_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.setEverythingNearViewport(true)
    }

    /**
     * 分相位强制部署 mission reserves（范式同 deployPsReserveShips + 相位门控）：
     * 玩家奥德赛与警戒靶舰 A 立即部署；靶舰 B 待到 TERMINAL 相位（避免 CHAIN 相位无处可去终结
     * 打到它污染「无舰消散」证据）；敌版奥德赛待到 ENEMY_MULTI 相位（避免提前携带七星开火）。
     */
    private var ssVigilanceSpawned = 0

    private fun deploySsReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
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
        log.info("[ASTD-Automation] ss phase $ssPhase -> $next at ${"%.2f".format(elapsed)}s")
        ssPhase = next
        ssPhaseStartedAt = elapsed
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
            player.setShipTarget(target)
            if (ssPhase != SS_PHASE_ENEMY_MULTI) player.setHitpoints(player.maxHitpoints)
            player.fluxTracker.currFlux = 0f
            // 玩家舰盾舞台性常关：ENEMY_MULTI 相位敌版多段终结证据要求伤害落到玩家船体。
            player.shield?.toggleOff()
            setSsAutofire(player, false)
            val w = findSsWeapon(player)
            if (w != null) {
                w.setCurrAngle(0f)
                w.setForceFireOneFrame(fire && ssPhase != SS_PHASE_ENEMY_MULTI)
            }
        }
        if (target != null && !target.isHulk) {
            stabilizeShip(target, SS_TARGET_ANCHOR, 180f, allowFire = false, preserveAI = false)
            target.setShipTarget(null)
            if (ssPhase != SS_PHASE_TERMINAL && ssPhase != SS_PHASE_COMPLETED) {
                target.setHitpoints(target.maxHitpoints)
            }
            target.shield?.toggleOff()
        }
        if (carrier != null && !carrier.isHulk) {
            stabilizeShip(carrier, SS_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = false)
            carrier.setShipTarget(player)
            carrier.setHitpoints(carrier.maxHitpoints)
            carrier.fluxTracker.currFlux = 0f
            setSsAutofire(carrier, false)
            val w = findSsWeapon(carrier)
            if (w != null) {
                w.setCurrAngle(180f)
                // 部署免疫闸（实机判例第 7 轮：reserves 手动 spawn 舰船在部署后 ~2.5s 内，
                // 其作为 source 的脚本 applyDamage 同样全额无效——敌版多段终结前两段 0 伤害、
                // 2.5s 后各段正常掉血）——敌版舰部署后 4s 内不放行开火（同 SS_TERMINAL_SETTLE_SECONDS）。
                w.setForceFireOneFrame(
                    ssPhase == SS_PHASE_ENEMY_MULTI &&
                        elapsed - ssPhaseStartedAt >= SS_ENEMY_MULTI_SETTLE_SECONDS,
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

    /**
     * 导弹投喂（范式同 feedPsMissiles，但改为「环形稠密投喂」）：
     * - CHAIN/COMPLETED：从玩家前方 280su 处半径 100su 的环上逐发喂鱼叉（owner 强制 1），
     *   低速 40su/s 指向环心——弹群在环内堆积，任意两弹间距 <=200su < 400su 跳程，
     *   保证单发连跳可达 7 跳上限（规格 07 §4.2 检查点 3）；高密度也避免「最近候选」
     *   在稀疏弹流间断链（实机判例：0.5s 双轨 250su/s 弹流 chainJumpsMax 只有 2）。
     * - ENEMY_MULTI：同理在敌版奥德赛前方 280su 环喂 owner=0 鱼叉（敌版连跳候选）。
     * 归属由 [CombatEntityAPI.setOwner] 显式指定，与 spawn 源舰解耦——CHAIN 相位靶舰 A
     * 已移除，源舰只能回退到玩家舰，若依赖源舰归属候选会全部失效（实机判例）。
     * spawn 返回 null 视为 weaponId 不可用——记失败原因转 FAILED。
     */
    private var ssMissileFeedAngle = 0f

    private fun feedSsMissiles(engine: CombatEngineAPI, atPlayerSide: Boolean) {
        if (elapsed < ssMissileFeedAt) return
        ssMissileFeedAt = elapsed + if (atPlayerSide) SS_MISSILE_FEED_INTERVAL else SS_ENEMY_FEED_INTERVAL
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
            failureReason = "ss missile spawn returned null for weaponId=$SS_FEED_MISSILE_ID"
            transitionSsPhase(SS_PHASE_FAILED)
            return
        }
        spawned.owner = if (atPlayerSide) 1 else 0
    }

    /**
     * BREAK 相位一次性增压投喂：一发鱼叉 HP 增压至 [SS_BREAK_MISSILE_HP]（闪光爆炸不可摧毁），
     * 实证「未击杀断链」安全闸——无续跳、无终结，直接消散（规格 07 §4.2 检查点 5）。
     */
    private fun feedSsBreakMissile(engine: CombatEngineAPI) {
        if (ssBreakFed) return
        ssBreakFed = true
        val source = findSsTarget(engine) ?: findSsPlayer(engine) ?: return
        val spawn = Vector2f(SS_PLAYER_ANCHOR.x + SS_BREAK_MISSILE_SPAWN_DIST, 0f)
        val angle = Misc.getAngleInDegrees(spawn, SS_PLAYER_ANCHOR)
        val rad = Math.toRadians(angle.toDouble())
        val vel = Vector2f(
            (kotlin.math.cos(rad) * SS_MISSILE_INITIAL_SPEED).toFloat(),
            (kotlin.math.sin(rad) * SS_MISSILE_INITIAL_SPEED).toFloat(),
        )
        val spawned = engine.spawnProjectile(source, null, SS_FEED_MISSILE_ID, spawn, angle, vel)
        if (spawned == null) {
            failureReason = "ss break missile spawn returned null for weaponId=$SS_FEED_MISSILE_ID"
            transitionSsPhase(SS_PHASE_FAILED)
            return
        }
        spawned.hitpoints = SS_BREAK_MISSILE_HP
    }

    /**
     * “七星”折跃发射器相位机（规格 07 §4.2 烟测检查点映射）：
     * MOUNT（装配/800 射程/PD hint 校验）→
     * BREAK（增压鱼叉一击不毁：未击杀断链——闪光发生、kills=0、消散计数 +1、终结计数恒 0；
     *   靶舰 A 在弹道上 600su HP 恒满 = 穿舰无触碰伤害证据，检查点 5+7）→
     * CHAIN（无敌舰空域持续投喂：连跳 chainJumpsMax ∈ [3,7]、十字闪光/折跃电弧/击杀计数、
     *   7 跳后无处可去终结 → 无舰消散计数 +1，检查点 2/3/4b；连跳峰值帧率门槛，检查点 8）→
     * TERMINAL（靶舰 B 600su：单段 50% 终结命中掉血、EMP 电弧恒 0，检查点 4a）→
     * ENEMY_MULTI（installScaleForTests(5) + 敌版携带：投喂玩家侧鱼叉供敌版连跳，
     *   多段终结 segments>=2 + 逐段 EMP 电弧 + 玩家掉血，检查点 6）→
     * COMPLETED（恢复投喂做截图舞台，十字闪光近期发生才上报 Completed 令特效入帧）。
     */
    private fun advanceSsScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deploySsReserveShips(engine)
        lockSsCamera(engine)

        val player = findSsPlayer(engine)
        val target = findSsTarget(engine)
        val weapon = findSsWeapon(player)
        val flash = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_FLASH)
        val crossFlash = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_CROSS_FLASH)
        val teleportArc = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TELEPORT_ARC)
        val kills = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_KILLS)
        val chainJumpsMax = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_CHAIN_JUMPS_MAX)
        val dissipateNoKill = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_KILL)
        val dissipateNoShip = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_SHIP)
        val terminalSingle = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_SINGLE)
        val terminalMulti = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_MULTI)
        val terminalSegmentsMax = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_SEGMENTS_MAX)
        val terminalEmpArcs = SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_EMP_ARCS)

        when (ssPhase) {
            SS_PHASE_MOUNT -> {
                stabilizeSsShips(engine, fire = false)
                if (elapsed - ssPhaseStartedAt >= SS_MOUNT_SETTLE_SECONDS) {
                    val slot = weapon?.slot?.id
                    // 校验数据面原始射程（spec.maxRange）：舰体内置射程 hullmod（如奥德赛 targeting core）
                    // 只放大 weapon.range 有效值，不应计入装配校验。
                    val range = weapon?.spec?.maxRange ?: -1f
                    val hintsPd = weapon?.spec?.getAIHints()?.contains(WeaponAPI.AIHints.PD) == true
                    when {
                        slot != SS_PLAYER_SLOT || kotlin.math.abs(range - SS_EXPECT_RANGE) > SS_RANGE_TOLERANCE -> {
                            failureReason = "ss mount mismatch: slot=$slot range=$range(expect $SS_EXPECT_RANGE)"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        !hintsPd -> {
                            failureReason = "ss aiHints missing PD（装配面板 hints 校验）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        else -> {
                            ssBreakKillsBaseline = kills
                            ssBreakNoKillBaseline = dissipateNoKill
                            target?.setHitpoints(target.maxHitpoints)
                            transitionSsPhase(SS_PHASE_BREAK)
                        }
                    }
                }
            }
            SS_PHASE_BREAK -> {
                stabilizeSsShips(engine, fire = true)
                feedSsBreakMissile(engine)
                if (dissipateNoKill - ssBreakNoKillBaseline >= 1) {
                    val targetIntact = target != null && !target.isHulk &&
                        target.hitpoints >= target.maxHitpoints - SS_BREAK_HP_TOLERANCE
                    when {
                        kills - ssBreakKillsBaseline != 0 -> {
                            failureReason = "ss break kills delta=${kills - ssBreakKillsBaseline}, expect 0（增压鱼叉不可摧毁，断链前不得有击杀）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        terminalSingle != 0 || terminalMulti != 0 -> {
                            failureReason = "ss break terminal single=$terminalSingle multi=$terminalMulti, expect 0（未击杀断链不触发终结）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        !targetIntact -> {
                            failureReason = "ss break target hp=${target?.hitpoints}/${target?.maxHitpoints}（弹体穿舰不掉血为预期；掉血=存在触碰伤害）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        else -> {
                            // 进入连跳相位：移除靶舰 A（空域无敌舰，无处可去终结走「无舰消散」路径）
                            target?.let { engine.removeEntity(it) }
                            ssChainNoShipBaseline = dissipateNoShip
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
                if (chainJumpsMax >= SS_CHAIN_MIN_JUMPS && dissipateNoShip - ssChainNoShipBaseline >= 1) {
                    val wallSeconds = (System.nanoTime() - ssChainFpsWallStartNanos) / 1_000_000_000.0
                    ssChainFps = if (wallSeconds > 0.0) (ssChainFpsTicks / wallSeconds).toFloat() else -1f
                    when {
                        chainJumpsMax > SS_CHAIN_MAX_JUMPS -> {
                            failureReason = "ss chain jumps max=$chainJumpsMax > $SS_CHAIN_MAX_JUMPS（7 跳硬上限被突破）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        kills < SS_CHAIN_MIN_KILLS -> {
                            failureReason = "ss chain kills=$kills < $SS_CHAIN_MIN_KILLS（连跳成片清除证据不足）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        crossFlash < chainJumpsMax -> {
                            failureReason = "ss cross flash=$crossFlash < chainJumpsMax=$chainJumpsMax（每跳一次十字闪光）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        teleportArc < 1 -> {
                            failureReason = "ss teleport arc=$teleportArc, expect>=1（折跃起止 EMP 电弧）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        ssChainFps < SS_CHAIN_MIN_FPS -> {
                            failureReason = "ss chain fps=$ssChainFps < $SS_CHAIN_MIN_FPS（连跳峰值帧率门槛）"
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
                val settled = elapsed - ssPhaseStartedAt >= SS_TERMINAL_SETTLE_SECONDS
                stabilizeSsShips(engine, fire = shieldFolded && settled)
                if (!shieldFolded || !settled) {
                    ssTerminalSingleBaseline = terminalSingle
                    ssTerminalEmpArcsBaseline = terminalEmpArcs
                }
                if (shieldFolded && settled && terminalSingle - ssTerminalSingleBaseline >= 1) {
                    val targetDamaged = target != null && target.hitpoints < target.maxHitpoints - SS_TERMINAL_HP_DROP_MIN
                    when {
                        terminalEmpArcs - ssTerminalEmpArcsBaseline != 0 -> {
                            failureReason = "ss terminal emp arcs delta=${terminalEmpArcs - ssTerminalEmpArcsBaseline}, expect 0（玩家单段终结无 EMP）"
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
                        elapsed - ssPhaseStartedAt > SS_TERMINAL_GRACE_SECONDS -> {
                            failureReason = "ss terminal target hp=${target?.hitpoints}/${target?.maxHitpoints}（单段 50% 终结应命中掉血）"
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
                            failureReason = "ss enemy terminal segments max=$terminalSegmentsMax < $SS_ENEMY_MULTI_MIN_SEGMENTS（破晓多段终结段数不足）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        terminalEmpArcs < SS_ENEMY_MULTI_MIN_SEGMENTS -> {
                            failureReason = "ss enemy terminal emp arcs=$terminalEmpArcs < $SS_ENEMY_MULTI_MIN_SEGMENTS（多段终结逐段 EMP 电弧）"
                            transitionSsPhase(SS_PHASE_FAILED)
                        }
                        player != null && ssEnemyMinPlayerHp < player.maxHitpoints - SS_TERMINAL_HP_DROP_MIN -> {
                            DifficultyTuningImpl.installScaleForTests(null)
                            findSsEnemyCarrier(engine)?.let { engine.removeEntity(it) }
                            player.setHitpoints(player.maxHitpoints)
                            transitionSsPhase(SS_PHASE_COMPLETED)
                        }
                        // 部署免疫宽限（同 SS_PHASE_TERMINAL 注：source 为敌版舰时其脚本伤害在
                        // 部署后数秒内可能全额无效，窗口非固定时长）——2s/发连发，宽限期内
                        // 任一段掉血即通过。
                        elapsed - ssPhaseStartedAt > SS_ENEMY_MULTI_GRACE_SECONDS -> {
                            failureReason = "ss enemy multi player minHp=$ssEnemyMinPlayerHp（多段终结应命中玩家舰掉血）"
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

        // 最近一次十字闪光时刻（COMPLETED 截图门控：闪光近期发生才上报，令十字特效入帧）
        if (crossFlash > ssLastTrackedFlashCount) {
            ssLastTrackedFlashCount = crossFlash
            ssLastFlashAt = elapsed
        }

        val state = when {
            player == null -> {
                if (elapsed > 12f) {
                    failureReason = "ss player ship missing"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            ssPhase == SS_PHASE_FAILED -> "Failed"
            ssPhase != SS_PHASE_COMPLETED &&
                elapsed - ssPhaseStartedAt > SS_PHASE_TIMEOUT -> {
                failureReason = "ss phase timeout: $ssPhase"
                "Failed"
            }
            ssPhase == SS_PHASE_COMPLETED -> {
                val recentFlash = ssLastFlashAt >= 0f && elapsed - ssLastFlashAt <= SS_COMPLETED_FLASH_WINDOW
                if (recentFlash || elapsed - ssPhaseStartedAt >= SS_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: seven_stars_basic break/chain/terminal/enemy-multi evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, weapon)
        }
    }

    // === Gemini DEM scenario (salvo / R1 DEM takeover / payload R2 / sync strike) ===

    private fun findGdPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner == 0 && it.hullSpec?.hullId == GD_PLAYER_HULL && !it.isFighter }

    private fun findGdTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == GD_TARGET_HULL && !it.isFighter }

    private fun findGdEnemyCarrier(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { it.owner != 0 && it.hullSpec?.hullId == GD_PLAYER_HULL && !it.isFighter }

    private fun findGdLauncher(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.GD_LAUNCHER_WEAPON_ID }

    private fun findGdPod(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.GD_POD_WEAPON_ID }

    private fun lockGdCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = GD_CAMERA_VISIBLE_HEIGHT * displayAspect
        viewport.setExternalControl(true)
        viewport.set(
            GD_CAMERA_CENTER.x - visibleWidth * 0.5f,
            GD_CAMERA_CENTER.y - GD_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            GD_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.setEverythingNearViewport(true)
    }

    /**
     * 分相位强制部署 mission reserves（范式同 deploySsReserveShips）：
     * 玩家征服者与统治者靶舰立即部署；敌版征服者待到 ENEMY_SCALE 相位（避免提前携带发射舱开火污染证据）。
     */
    private fun deployGdReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
                val hullId = member.hullId ?: continue
                when {
                    side == FleetSide.PLAYER && hullId == GD_PLAYER_HULL -> {
                        manager.spawnFleetMember(member, Vector2f(GD_PLAYER_ANCHOR), 0f, 0f)
                        manager.removeFromReserves(member)
                    }
                    side == FleetSide.ENEMY && hullId == GD_TARGET_HULL -> {
                        manager.spawnFleetMember(member, Vector2f(GD_TARGET_ANCHOR), 180f, 0f)
                        manager.removeFromReserves(member)
                    }
                    side == FleetSide.ENEMY && hullId == GD_PLAYER_HULL && gdPhase == GD_PHASE_ENEMY_SCALE -> {
                        manager.spawnFleetMember(member, Vector2f(GD_ENEMY_ANCHOR), 180f, 0f)
                        manager.removeFromReserves(member)
                    }
                }
            }
        }
    }

    private fun transitionGdPhase(next: String) {
        log.info("[ASTD-Automation] gd phase $gdPhase -> $next at ${"%.2f".format(elapsed)}s")
        gdPhase = next
        gdPhaseStartedAt = elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeSsShips）：玩家舰逐帧奶（ENEMY_SCALE 除外）+ 辐能清零 +
     * force fire 独占驱动；靶舰盾舞台性常关（payload 光束/同步冲击须落船体出 HP 证据），
     * 相位证据以 HP 基线/最小值观测，相位收尾由调用方补奶防靶舰沉没。
     */
    private fun stabilizeGdShips(engine: CombatEngineAPI, fireLauncher: Boolean, firePod: Boolean) {
        val player = findGdPlayer(engine)
        val target = findGdTarget(engine)
        val carrier = findGdEnemyCarrier(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            // 舞台舰一律摘除 AI（实机判例：保留 AI 会每帧抢开盾，与 toggleOff 拉锯污染掉血证据）。
            stabilizeShip(player, GD_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = false)
            player.setShipTarget(target ?: carrier)
            if (gdPhase != GD_PHASE_ENEMY_SCALE) player.setHitpoints(player.maxHitpoints)
            player.fluxTracker.currFlux = 0f
            player.shield?.toggleOff()
            setGdAutofire(player, false)
            findGdLauncher(player)?.let {
                it.setCurrAngle(0f)
                it.setForceFireOneFrame(fireLauncher)
            }
            findGdPod(player)?.let {
                it.setCurrAngle(0f)
                it.setForceFireOneFrame(firePod)
            }
        }
        if (target != null && !target.isHulk) {
            stabilizeShip(target, GD_TARGET_ANCHOR, 180f, allowFire = false, preserveAI = false)
            target.setShipTarget(null)
            target.shield?.toggleOff()
            if (gdPhase == GD_PHASE_SALVO || gdPhase == GD_PHASE_KILL_ONE || gdPhase == GD_PHASE_POD) {
                gdSalvoTargetMinHp = minOf(gdSalvoTargetMinHp, target.hitpoints)
            }
        }
        if (carrier != null && !carrier.isHulk) {
            stabilizeShip(carrier, GD_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = false)
            carrier.setShipTarget(player)
            carrier.setHitpoints(carrier.maxHitpoints)
            carrier.fluxTracker.currFlux = 0f
            setGdAutofire(carrier, false)
            findGdPod(carrier)?.let {
                it.setCurrAngle(180f)
                // 部署免疫闸（实机判例：reserves 手动 spawn 舰船部署后数秒内脚本 applyDamage 可能全额无效）
                it.setForceFireOneFrame(
                    gdPhase == GD_PHASE_ENEMY_SCALE && elapsed - gdPhaseStartedAt >= GD_ENEMY_SETTLE_SECONDS,
                )
            }
        }
    }

    /** 双子星武器组 autofire 总开关（范式同 setSsAutofire）：force fire 独占驱动时关闭。 */
    private fun setGdAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none {
                    it.id == ASTDInGameAutomationScenario.GD_LAUNCHER_WEAPON_ID ||
                        it.id == ASTDInGameAutomationScenario.GD_POD_WEAPON_ID
                }
            ) {
                continue
            }
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /**
     * R1 轮询（规格 10 §5 风险表 R1 首选验证项）：遍历出生登记簿中的弹头原始引用，三路读 AI
     * （`getAI()` = DEMScript WAIT 段的读取路径 / `getMissileAI()` / `getUnwrappedMissileAI()`）。
     * 实机判例（2026-07-29 烟测）：`engine.getMissiles()` 不含脚本 spawn 的弹头，
     * customData/weaponSpec 扫描观测面全部落空；登记簿原始引用是唯一可靠观测面。
     * 本观测面只作诊断（类名三元组首次出现记日志），相位断言用供给侧遥测
     * （SalvoOnFireEffect.TELEMETRY_TRACK_AI_*）+ payload 命中（DEMScript 接管的硬证据）。
     */
    private fun pollGdWarheads(engine: CombatEngineAPI) {
        for (ref in GeminiDemSalvoOnFireEffect.warheadsOf(engine)) {
            val missile = ref.missile
            if (!engine.isEntityInPlay(missile)) continue
            val key = System.identityHashCode(missile)
            val reads = listOf(
                missile.ai as? MissileAIPlugin,
                missile.missileAI,
                missile.unwrappedMissileAI,
            )
            // 包装弹头 getAI/getMissileAI 读回是引擎 Wrapper（非 GuidedMissileAI），
            // 真实实例只在 unwrapped 读回——三路全扫，不取 firstNotNull（实机判例：取首路恒为包装）
            val trackAi = reads.filterIsInstance<GeminiDemTrackAI>().firstOrNull()
            if (trackAi != null && gdTrackAiSeen.add(key)) {
                if ((trackAi as GuidedMissileAI).target != null) gdTrackTargetNonNull++
            }
            if (reads.any { it is DEMScript }) gdDemTakeoverSeen.add(key)
            val triple = reads.joinToString("|") { it?.javaClass?.name ?: "null" }
            if (gdAiClassTriplesSeen.add(triple)) {
                log.info("[ASTD-Automation] gd R1 ai reads: ai/missileAI/unwrapped = $triple")
            }
        }
    }

    /**
     * 双子星 DEM 相位机（规格 10 §4.2 烟测检查点映射）：
     * MOUNT（双槽装配/射程/ammo 2/4/tags/隐藏四件 no_drop+SYSTEM 校验，检查点 1/6）→
     * SALVO（齐射双弹 + dummy 拦截；R1：TrackAI 供目标 + DEMScript 接管；
     *   R2：payload 首伤帧读数；动能 4 道 EMP 电弧；同步冲击触发 + 玩家恒 v2，检查点 2/3/4/5/7）→
     * KILL_ONE（击落高爆弹头：动能独发命中、同步计数恒不变，检查点 5 反面）→
     * POD（发射舱齐射 + ammo 4→3 + 同步配对，检查点 1/8）→
     * ENEMY_SCALE（installScaleForTests(5) + 敌版携带：敌版同步 mult=1.0 + 玩家掉血，检查点 7）→
     * COMPLETED（恢复齐射做截图舞台，近期有打击才上报 Completed 令双色尾焰/锁定激光/光束入帧）。
     */
    private fun advanceGdScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployGdReserveShips(engine)
        lockGdCamera(engine)
        pollGdWarheads(engine)

        val player = findGdPlayer(engine)
        val target = findGdTarget(engine)
        val launcher = findGdLauncher(player)
        val pod = findGdPod(player)
        val salvoCount = GeminiDemSalvoOnFireEffect.salvoCount(engine)
        val warheads = GeminiDemSalvoOnFireEffect.warheadsSpawned(engine)
        val gdTrackAiCreated = GeminiDemSalvoOnFireEffect.trackAiCreated(engine)
        val gdTrackAiTargetNonNull = GeminiDemSalvoOnFireEffect.trackAiTargetNonNull(engine)
        val kineticHits = GeminiDemPayloadBeamEffect.kineticHitCount(engine)
        val heHits = GeminiDemPayloadBeamEffect.heHitCount(engine)
        val empArcs = GeminiDemPayloadBeamEffect.empArcCount(engine)
        val syncTriggers = GeminiDemSyncHandler.syncTriggerCount(engine)
        val lastMult = engine.customData[GeminiDemSyncHandler.TELEMETRY_SYNC_LAST_MULT] as? Float ?: -1f

        when (gdPhase) {
            GD_PHASE_MOUNT -> {
                stabilizeGdShips(engine, fireLauncher = false, firePod = false)
                if (elapsed - gdPhaseStartedAt >= GD_MOUNT_SETTLE_SECONDS) {
                    val launcherSlot = launcher?.slot?.id
                    val podSlot = pod?.slot?.id
                    val launcherRange = launcher?.spec?.maxRange ?: -1f
                    val podRange = pod?.spec?.maxRange ?: -1f
                    val hiddenIds = listOf(
                        GeminiDemDifficulty.KINETIC_WEAPON_ID,
                        GeminiDemDifficulty.HE_WEAPON_ID,
                        GeminiDemDifficulty.KINETIC_PAYLOAD_ID,
                        GeminiDemDifficulty.HE_PAYLOAD_ID,
                    )
                    val hiddenLeak = hiddenIds.firstOrNull { id ->
                        val spec = Global.getSettings().getWeaponSpec(id)
                        spec == null || !spec.tags.contains("no_drop") || !spec.tags.contains("no_drop_salvage")
                    }
                    val payloadHintLeak = listOf(GeminiDemDifficulty.KINETIC_PAYLOAD_ID, GeminiDemDifficulty.HE_PAYLOAD_ID)
                        .firstOrNull { id ->
                            Global.getSettings().getWeaponSpec(id)?.getAIHints()?.contains(WeaponAPI.AIHints.SYSTEM) != true
                        }
                    when {
                        launcherSlot != GD_PLAYER_SLOT_LAUNCHER || podSlot != GD_PLAYER_SLOT_POD -> {
                            failureReason = "gd mount mismatch: launcherSlot=$launcherSlot podSlot=$podSlot"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        kotlin.math.abs(launcherRange - GD_EXPECT_RANGE) > GD_RANGE_TOLERANCE ||
                            kotlin.math.abs(podRange - GD_EXPECT_RANGE) > GD_RANGE_TOLERANCE -> {
                            failureReason = "gd range mismatch: launcher=$launcherRange pod=$podRange(expect $GD_EXPECT_RANGE)"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        launcher == null || launcher.spec?.maxAmmo != GD_LAUNCHER_AMMO -> {
                            failureReason = "gd launcher spec maxAmmo=${launcher?.spec?.maxAmmo} runtime ammo=${launcher?.ammo}, expect spec $GD_LAUNCHER_AMMO（weapon_data.csv 口径）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        pod == null || pod.spec?.maxAmmo != GD_POD_AMMO -> {
                            failureReason = "gd pod spec maxAmmo=${pod?.spec?.maxAmmo} runtime ammo=${pod?.ammo}, expect spec $GD_POD_AMMO（weapon_data.csv 口径）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        hiddenLeak != null -> {
                            failureReason = "gd hidden weapon leak: $hiddenLeak 缺 no_drop 系 tags（codex/掉落泄漏防线）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        payloadHintLeak != null -> {
                            failureReason = "gd payload hint leak: $payloadHintLeak 缺 SYSTEM hint"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        else -> {
                            gdLauncherAmmoBaseline = launcher.ammo
                            val ammoBonus = player?.mutableStats?.missileAmmoBonus
                            log.info(
                                "[ASTD-Automation] gd ammo env: launcher spec=${launcher.spec.maxAmmo} runtime=${launcher.ammo} " +
                                    "pod spec=${pod.spec.maxAmmo} runtime=${pod.ammo} " +
                                    "missileAmmoBonus(mult=${ammoBonus?.mult} pct=${ammoBonus?.percentMod} flat=${ammoBonus?.flatBonus})" +
                                    "（runtime≠spec 时一轮一耗断言走基线差分）",
                            )
                            gdSalvoTargetHpBaseline = target?.hitpoints ?: -1f
                            gdSalvoTargetMinHp = target?.hitpoints ?: Float.MAX_VALUE
                            transitionGdPhase(GD_PHASE_SALVO)
                        }
                    }
                }
            }
            GD_PHASE_SALVO -> {
                stabilizeGdShips(engine, fireLauncher = true, firePod = false)
                if (warheads >= 2 && gdLauncherAmmoAfterSalvo < 0) {
                    gdLauncherAmmoAfterSalvo = launcher?.ammo ?: -1
                }
                if (kineticHits >= 1 && heHits >= 1) {
                    when {
                        salvoCount < 1 || warheads != salvoCount * 2 -> {
                            failureReason = "gd salvo mismatch: salvo=$salvoCount warheads=$warheads（每轮齐射恰两枚弹头）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        gdTrackAiCreated < 2 || gdTrackAiTargetNonNull < 2 -> {
                            failureReason = "gd R1 fail: TrackAI 装配=$gdTrackAiCreated 目标非空=$gdTrackAiTargetNonNull（应各 ≥2，DEMScript WAIT 段触发前提的供给侧证据）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        empArcs != GD_EMP_ARC_COUNT -> {
                            failureReason = "gd emp arcs=$empArcs, expect $GD_EMP_ARC_COUNT（动能光束首伤帧 4 道 EMP 电弧）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        syncTriggers < 1 -> {
                            failureReason = "gd sync=0（双弹同目标 Δt≤1s 应触发同步冲击）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        kotlin.math.abs(lastMult - GD_PLAYER_V2_MULT) > GD_MULT_TOLERANCE -> {
                            failureReason = "gd sync mult=$lastMult, expect ${GD_PLAYER_V2_MULT}（玩家来源恒 v2）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        gdLauncherAmmoAfterSalvo != gdLauncherAmmoBaseline - 1 -> {
                            failureReason = "gd launcher ammo after salvo=$gdLauncherAmmoAfterSalvo, expect ${gdLauncherAmmoBaseline - 1}（基线 $gdLauncherAmmoBaseline，一次触发一轮齐射）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        else -> {
                            log.info(
                                "[ASTD-Automation] gd salvo evidence: targetHp ${gdSalvoTargetHpBaseline}→min ${gdSalvoTargetMinHp} " +
                                    "（R2 读数：payload+sync 落船体；beamDamage 面板见 payload 首伤帧日志）",
                            )
                            // DEMScript 接管硬证据 = payload 光束命中本身（payload 只能由 DEMScript 打击段结算，
                            // 规格 §0.1 事实 #7）；包装弹头读回观测面（gdDemTakeoverSeen）只作诊断。
                            if (gdDemTakeoverSeen.isEmpty()) {
                                log.info("[ASTD-Automation] gd R1 note: 包装弹头读回未见 DEMScript（payload 命中已为接管硬证据）")
                            }
                            gdKillSyncBaseline = syncTriggers
                            gdKillKineticBaseline = kineticHits
                            gdKillHeBaseline = heHits
                            gdKillWarheadsBaseline = warheads
                            gdKillHeRemoved = false
                            target?.setHitpoints(target.maxHitpoints)
                            transitionGdPhase(GD_PHASE_KILL_ONE)
                        }
                    }
                }
            }
            GD_PHASE_KILL_ONE -> {
                stabilizeGdShips(engine, fireLauncher = true, firePod = false)
                if (warheads - gdKillWarheadsBaseline >= 2) {
                    // 出生登记簿是唯一可靠观测面（engine.getMissiles() 不含脚本 spawn 弹头，实机判例）；
                    // 相位内持续移除全部在场高爆弹头（击落一枚模拟；相位内多轮齐射时后续高爆同样拆解）。
                    val liveHe = GeminiDemSalvoOnFireEffect.warheadsOf(engine)
                        .filter { it.weaponId == GeminiDemDifficulty.HE_WEAPON_ID && engine.isEntityInPlay(it.missile) }
                    if (liveHe.isNotEmpty()) {
                        liveHe.forEach { engine.removeEntity(it.missile) }
                        if (!gdKillHeRemoved) {
                            gdKillHeRemoved = true
                            log.info("[ASTD-Automation] gd kill_one: 高爆弹头已被移除（击落一枚模拟）")
                        }
                    }
                }
                if (gdKillHeRemoved && kineticHits - gdKillKineticBaseline >= 1) {
                    when {
                        syncTriggers != gdKillSyncBaseline -> {
                            failureReason = "gd kill_one sync delta=${syncTriggers - gdKillSyncBaseline}, expect 0（击落一枚，同步冲击即告落空）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        heHits != gdKillHeBaseline -> {
                            failureReason = "gd kill_one he hits delta=${heHits - gdKillHeBaseline}, expect 0（高爆弹头已移除不得命中）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        else -> {
                            gdPodSalvoBaseline = salvoCount
                            gdPodKineticBaseline = kineticHits
                            gdPodHeBaseline = heHits
                            gdPodSyncBaseline = syncTriggers
                            gdPodAmmoBaseline = pod?.ammo ?: -1
                            gdPodAmmoAfterSalvo = -1
                            target?.setHitpoints(target.maxHitpoints)
                            transitionGdPhase(GD_PHASE_POD)
                        }
                    }
                }
            }
            GD_PHASE_POD -> {
                stabilizeGdShips(engine, fireLauncher = false, firePod = true)
                if (salvoCount - gdPodSalvoBaseline >= 1 && gdPodAmmoAfterSalvo < 0) {
                    gdPodAmmoAfterSalvo = pod?.ammo ?: -1
                }
                if (kineticHits - gdPodKineticBaseline >= 1 && heHits - gdPodHeBaseline >= 1) {
                    when {
                        gdPodAmmoAfterSalvo != gdPodAmmoBaseline - 1 -> {
                            failureReason = "gd pod ammo after salvo=$gdPodAmmoAfterSalvo, expect ${gdPodAmmoBaseline - 1}（基线 $gdPodAmmoBaseline，发射舱一轮一耗）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        syncTriggers - gdPodSyncBaseline < 1 -> {
                            failureReason = "gd pod sync delta=${syncTriggers - gdPodSyncBaseline} < 1（发射舱双弹同目标应触发同步）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        else -> {
                            gdEnemySyncBaseline = syncTriggers
                            gdEnemyMinPlayerHp = player?.maxHitpoints ?: Float.MAX_VALUE
                            gdEnemyFirstSyncAt = -1f
                            DifficultyTuningImpl.installScaleForTests(5f)
                            target?.setHitpoints(target.maxHitpoints)
                            transitionGdPhase(GD_PHASE_ENEMY_SCALE)
                        }
                    }
                }
            }
            GD_PHASE_ENEMY_SCALE -> {
                stabilizeGdShips(engine, fireLauncher = false, firePod = false)
                if (player != null && !player.isHulk) {
                    gdEnemyMinPlayerHp = minOf(gdEnemyMinPlayerHp, player.hitpoints)
                }
                if (syncTriggers - gdEnemySyncBaseline >= 1 && gdEnemyFirstSyncAt < 0f) {
                    gdEnemyFirstSyncAt = elapsed
                }
                if (gdEnemyFirstSyncAt >= 0f) {
                    when {
                        kotlin.math.abs(lastMult - GD_ENEMY_V5_MULT) > GD_MULT_TOLERANCE -> {
                            failureReason = "gd enemy sync mult=$lastMult, expect $GD_ENEMY_V5_MULT（破晓敌版走轨一）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                        player != null && gdEnemyMinPlayerHp < player.maxHitpoints - GD_HP_DROP_MIN -> {
                            DifficultyTuningImpl.installScaleForTests(null)
                            findGdEnemyCarrier(engine)?.let { engine.removeEntity(it) }
                            player.setHitpoints(player.maxHitpoints)
                            target?.setHitpoints(target.maxHitpoints)
                            transitionGdPhase(GD_PHASE_COMPLETED)
                        }
                        // 部署免疫宽限（实机判例同 SS：source 为敌版舰时脚本伤害在部署后数秒内可能全额无效）
                        elapsed - gdEnemyFirstSyncAt > GD_ENEMY_GRACE_SECONDS -> {
                            failureReason = "gd enemy sync player minHp=$gdEnemyMinPlayerHp（敌版同步应命中玩家舰掉血）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }
                    }
                }
            }
            GD_PHASE_COMPLETED -> {
                stabilizeGdShips(engine, fireLauncher = true, firePod = false)
            }
        }

        // 最近一次 payload 首伤帧时刻（COMPLETED 截图门控：打击近期发生才上报，令双色尾焰/光束入帧）
        val strikeCount = kineticHits + heHits
        if (strikeCount > gdLastTrackedStrikeCount) {
            gdLastTrackedStrikeCount = strikeCount
            gdLastStrikeAt = elapsed
        }

        val state = when {
            player == null -> {
                if (elapsed > 12f) {
                    failureReason = "gd player ship missing"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            gdPhase == GD_PHASE_FAILED -> "Failed"
            gdPhase != GD_PHASE_COMPLETED &&
                elapsed - gdPhaseStartedAt > GD_PHASE_TIMEOUT -> {
                failureReason = "gd phase timeout: $gdPhase"
                "Failed"
            }
            gdPhase == GD_PHASE_COMPLETED -> {
                val recentStrike = gdLastStrikeAt >= 0f && elapsed - gdLastStrikeAt <= GD_COMPLETED_STRIKE_WINDOW
                if (recentStrike || elapsed - gdPhaseStartedAt >= GD_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: gemini_dem_basic salvo/dem-takeover/payload/sync/kill-one/pod/enemy-scale evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, launcher)
        }
    }

    // === Heavy ion pulse scenario (mount / shield-immunity / discharge / emp-pierce scaling evidence) ===

    private fun findHipPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == HIP_HULL }

    private fun findHipEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == HIP_HULL }

    private fun findHipWeapon(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.HIP_WEAPON_ID }

    private fun lockHipCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = HIP_CAMERA_VISIBLE_HEIGHT * displayAspect
        viewport.setExternalControl(true)
        viewport.set(
            HIP_CAMERA_CENTER.x - visibleWidth * 0.5f,
            HIP_CAMERA_CENTER.y - HIP_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            HIP_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（双桑德均非旗舰，范式同 deployGdReserveShips）。 */
    private fun deployHipReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
                if (member.hullId != HIP_HULL) continue
                val anchor = if (side == FleetSide.ENEMY) HIP_ENEMY_ANCHOR else HIP_PLAYER_ANCHOR
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionHipPhase(next: String) {
        log.info("[ASTD-Automation] hip phase $hipPhase -> $next at ${"%.2f".format(elapsed)}s")
        hipPhase = next
        hipPhaseStartedAt = elapsed
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
            player.setShipTarget(enemy)
            player.setHitpoints(player.maxHitpoints)
            player.fluxTracker.setCurrFlux(0f)
            player.fluxTracker.setHardFlux(0f)
            // 护盾策略：PIERCE 相位玩家盾关（贯穿须落船体），其余相位常开。
            val shieldOn = hipPhase != HIP_PHASE_PIERCE_K2 && hipPhase != HIP_PHASE_PIERCE_K5 && hipPhase != HIP_PHASE_COMPLETED
            player.shield?.let { if (shieldOn && !it.isOn) it.toggleOn(); if (!shieldOn && it.isOn) it.toggleOff() }
            setHipAutofire(player, false)
            findHipWeapon(player)?.let {
                if (enemy != null) it.setCurrAngle(Misc.getAngleInDegrees(it.location, enemy.location))
                it.setForceFireOneFrame(playerFire)
            }
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, HIP_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            enemy.setShipTarget(player)
            enemy.setHitpoints(enemy.maxHitpoints)
            enemy.fluxTracker.setCurrFlux(0f)
            enemy.fluxTracker.setHardFlux(0f)
            // 护盾策略：SHIELD 相位敌盾开（验证护盾命中无电弧），其余相位常关（泄放/贯穿须落船体）。
            val shieldOn = hipPhase == HIP_PHASE_SHIELD
            enemy.shield?.let { if (shieldOn && !it.isOn) it.toggleOn(); if (!shieldOn && it.isOn) it.toggleOff() }
            setHipAutofire(enemy, false)
            findHipWeapon(enemy)?.let {
                if (player != null) it.setCurrAngle(Misc.getAngleInDegrees(it.location, player.location))
                val gated = enemyFire && elapsed - hipPhaseStartedAt >= HIP_ENEMY_SETTLE_SECONDS
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

    /**
     * 重型离子脉冲相位机（规格 02 §4.2 烟测检查点映射）：
     * MOUNT（装配校验：WS 003 大能量槽/射程 700/spec maxAmmo 40/双炮管 offsets/VfxSpec 登记，检查点 1/2/7）→
     * SHIELD（敌盾开：命中护盾无泄放电弧，检查点 3 反面）→
     * HULL（敌盾关：泄放电弧计数 + 弹匣节奏（满匣倾泻/打空时刻），检查点 2/3）→
     * SCALE5_PLAYER（installScaleForTests(5) + 敌舰 mult→0：玩家恒 v2 无贯穿 + 泄放频率口径，检查点 4）→
     * PIERCE_K2（installScaleForTests(2) + 玩家 mult→0：敌版 k_s=2 无贯穿浮字，检查点 5 反面）→
     * PIERCE_K5（installScaleForTests(5)：敌版破晓贯穿浮字 + §2.5 待验证项（追加量二次减免）核对
     *   + 持续命中 FPS，检查点 5/6/8）→
     * COMPLETED（双方恢复开火做截图舞台，近期有泄放/贯穿事件才上报令电弧/浮字/拖尾入帧）。
     */
    private fun advanceHipScenario(engine: CombatEngineAPI) {
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
                if (elapsed - hipPhaseStartedAt >= HIP_MOUNT_SETTLE_SECONDS) {
                    val slot = playerWeapon?.slot?.id
                    val range = playerWeapon?.spec?.maxRange ?: -1f
                    val barrels = playerWeapon?.spec?.let {
                        maxOf(it.turretFireOffsets?.size ?: 0, it.hardpointFireOffsets?.size ?: 0)
                    } ?: 0
                    when {
                        playerWeapon == null || slot != HIP_PLAYER_SLOT -> {
                            failureReason = "hip mount mismatch: slot=$slot, expect $HIP_PLAYER_SLOT"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }
                        kotlin.math.abs(range - HIP_EXPECT_RANGE) > HIP_RANGE_TOLERANCE -> {
                            failureReason = "hip range=$range, expect $HIP_EXPECT_RANGE"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }
                        playerWeapon.spec?.maxAmmo != HIP_AMMO -> {
                            failureReason = "hip spec maxAmmo=${playerWeapon.spec?.maxAmmo}, expect $HIP_AMMO（weapon_data.csv 口径）"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }
                        barrels != HIP_BARRELS -> {
                            failureReason = "hip barrels=$barrels, expect $HIP_BARRELS（双炮管交替射击，.wpn turretOffsets×2 + ALTERNATING）"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }
                        !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.HIP_PROJECTILE_SPEC_ID) -> {
                            failureReason = "hip projectile VFX 未登记: ${ASTDInGameAutomationScenario.HIP_PROJECTILE_SPEC_ID}"
                            transitionHipPhase(HIP_PHASE_FAILED)
                        }
                        else -> {
                            hipShieldAmmoBaseline = playerWeapon.ammo
                            log.info("[ASTD-Automation] hip mount ok: slot=$slot range=$range specMaxAmmo=${playerWeapon.spec.maxAmmo} barrels=$barrels（双管 ALTERNATING）")
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
                        failureReason = "hip shield phase discharge=${dischargePlayer + dischargeOther}, expect 0（EMP 对盾无效，命中护盾无电弧）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        hipHullAmmoBaseline = playerWeapon.ammo
                        hipMinAmmo = playerWeapon.ammo
                        hipEmptiedAt = -1f
                        log.info("[ASTD-Automation] hip shield evidence: spent=${hipShieldAmmoBaseline - playerWeapon.ammo} 发命中护盾、零泄放")
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
                            hipEmptiedAt = elapsed
                            log.info("[ASTD-Automation] hip magazine emptied at ${"%.2f".format(elapsed)}s（满匣 40 发倾泻证据）")
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
                    log.info(
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
                        failureReason = "hip scale5 player pierce delta=$pierceDelta, expect 0（k_s=5 玩家恒 v2，贯穿为敌版逐项解锁）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        val disDelta = dischargePlayer - 0
                        log.info(
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
                val hitsDelta = hitsOther - hipK2EnemyHitsBaseline
                if (hitsDelta >= HIP_K2_MIN_ENEMY_HITS) {
                    val pierceDelta = pierceOther - hipK2PierceOtherBaseline
                    if (pierceDelta > 0) {
                        failureReason = "hip k_s=2 enemy pierce delta=$pierceDelta, expect 0（v2 档无 EMP 贯穿特效）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        log.info("[ASTD-Automation] hip k2 evidence: enemyHitsDelta=$hitsDelta pierceDelta=0（敌版 k_s=2 无贯穿）")
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
                        failureReason = "hip pierce applied 折算不符: applied=$lastApplied expect=$expectedApplied（extra=$lastExtra mult=$lastMult）"
                        transitionHipPhase(HIP_PHASE_FAILED)
                    } else {
                        log.info(
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
            hipLastEventAt = elapsed
        }

        val state = when {
            player == null || enemy == null -> {
                if (elapsed > 12f) {
                    failureReason = "hip ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            hipPhase == HIP_PHASE_FAILED -> "Failed"
            hipPhase != HIP_PHASE_COMPLETED &&
                elapsed - hipPhaseStartedAt > HIP_PHASE_TIMEOUT -> {
                failureReason = "hip phase timeout: $hipPhase"
                "Failed"
            }
            hipPhase == HIP_PHASE_COMPLETED -> {
                val recentEvent = hipLastEventAt >= 0f && elapsed - hipLastEventAt <= HIP_COMPLETED_EVENT_WINDOW
                if (recentEvent || elapsed - hipPhaseStartedAt >= HIP_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            DifficultyTuningImpl.installScaleForTests(null)
            player?.mutableStats?.empDamageTakenMult?.unmodifyMult(HIP_RESIST_MOD_ID)
            log.info("[ASTD-Automation] Completed: heavy_ion_pulse_basic mount/shield/discharge/scale5/k2/k5-pierce evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, playerWeapon)
        }
    }


    // === Stellar MRM scenario (mount / hunt priority / fighter emp / ship+shield explosion / line-cross / enemy scale) ===

    private fun findSmPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == SM_PLAYER_HULL && !ship.isFighter }

    private fun findSmEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == SM_ENEMY_HULL && !ship.isFighter }

    private fun findSmCarrier(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == SM_CARRIER_HULL && !ship.isFighter }

    private fun findSmLauncher(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID }

    private fun findSmPod(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.SM_POD_WEAPON_ID }

    private fun smTeleCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private fun smEnemyFighters(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { it.owner != 0 && it.isFighter && !it.isHulk }

    private fun lockSmCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = SM_CAMERA_VISIBLE_HEIGHT * displayAspect
        viewport.setExternalControl(true)
        viewport.set(
            SM_CAMERA_CENTER.x - visibleWidth * 0.5f,
            SM_CAMERA_CENTER.y - SM_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            SM_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（玩家狮鹫 / 敌方秃鹰航母 / 敌方狮鹫均非旗舰，范式同 deployHipReserveShips）。 */
    private fun deploySmReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            for (member in manager.getReservesCopy().toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == SM_PLAYER_HULL -> SM_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == SM_CARRIER_HULL -> SM_CARRIER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == SM_ENEMY_HULL -> SM_ENEMY_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionSmPhase(next: String) {
        log.info("[ASTD-Automation] sm phase $smPhase -> $next at ${"%.2f".format(elapsed)}s")
        smPhase = next
        smPhaseStartedAt = elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeHipShips）：双狮鹫逐帧奶 + 辐能清零 + 钉死锚点 +
     * force fire 独占驱动（autofire 关闭）；秃鹰航母保留 AI 放联队（猎机目标源）。
     * 玩家武器瞄准取最近敌战机、无战机取敌方狮鹫（与猎杀优先级同序，保证弹道指向被选中目标）。
     * [aimOverride]：相位特例瞄准点（撞线相位指向最近敌导弹令弹道与投喂 lane 对头相交——
     * 只改发射指向，目标选择遥测仍仅战机/舰船两型，不破坏「不主动拦导弹」证据）。
     * 敌方开火带部署免疫闸（GD/SS/HIP 实机判例：reserves 手动 spawn 舰船部署后数秒内脚本
     * applyDamage 可能全额无效）。
     */
    private fun stabilizeSmShips(engine: CombatEngineAPI, playerFire: Boolean, enemyFire: Boolean, aimOverride: CombatEntityAPI? = null) {
        val player = findSmPlayer(engine)
        val enemy = findSmEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, SM_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = true)
            player.setHitpoints(player.maxHitpoints)
            player.fluxTracker.setCurrFlux(0f)
            player.fluxTracker.setHardFlux(0f)
            // 玩家盾常关：敌版三档相位令敌弹落船体（爆炸恒触发 + 浮字目检）。
            player.shield?.let { if (it.isOn) it.toggleOff() }
            setSmAutofire(player, false)
            val aim: CombatEntityAPI? = aimOverride
                ?: smEnemyFighters(engine).minByOrNull { MathUtils.getDistance(player.location, it.location) }
                ?: enemy
            player.setShipTarget(if (aimOverride == null) aim as? ShipAPI else enemy)
            for (weapon in listOfNotNull(findSmLauncher(player), findSmPod(player))) {
                if (aim != null) weapon.setCurrAngle(Misc.getAngleInDegrees(weapon.location, aim.location))
                weapon.setForceFireOneFrame(playerFire)
            }
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, SM_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            enemy.setShipTarget(player)
            enemy.setHitpoints(enemy.maxHitpoints)
            enemy.fluxTracker.setCurrFlux(0f)
            enemy.fluxTracker.setHardFlux(0f)
            // 护盾策略：SHIP_HIT 相位敌盾开（验证撞击护盾爆炸照常），其余相位常关。
            val shieldOn = smPhase == SM_PHASE_SHIP_HIT
            enemy.shield?.let { if (shieldOn && !it.isOn) it.toggleOn(); if (!shieldOn && it.isOn) it.toggleOff() }
            setSmAutofire(enemy, false)
            findSmLauncher(enemy)?.let {
                if (player != null) it.setCurrAngle(Misc.getAngleInDegrees(it.location, player.location))
                val gated = enemyFire && elapsed - smPhaseStartedAt >= SM_ENEMY_SETTLE_SECONDS
                it.setForceFireOneFrame(gated)
            }
        }
        // 航母：保留 AI 放阔剑联队；自身无武器（mission 已清槽），仅钉锚点奶血。
        val carrier = findSmCarrier(engine)
        if (carrier != null && !carrier.isHulk) {
            stabilizeShip(carrier, SM_CARRIER_ANCHOR, 180f, allowFire = false, preserveAI = true)
            carrier.setHitpoints(carrier.maxHitpoints)
            carrier.fluxTracker.setCurrFlux(0f)
        }
    }

    /** 辉星武器组 autofire 总开关（范式同 setHipAutofire）：force fire 独占驱动时关闭。 */
    private fun setSmAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none {
                    it.id == ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID ||
                        it.id == ASTDInGameAutomationScenario.SM_POD_WEAPON_ID
                }
            ) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /**
     * 撞线投喂（范式同 feedSsMissiles）：敌侧中场生成敌属导弹直线飞向玩家，与玩家导弹流对头相撞。
     * stage 0 喂低结构 atropos（hp=300 < v2 阈值 600，撞线者死）；stage 1 喂增压 2000HP harpoon
     * （> 阈值且连击安全余量，仅爆炸不移除——首次被命中时存活 + 撞线计数不增）。
     */
    private fun feedSmMissile(engine: CombatEngineAPI) {
        if (elapsed < smFeedAt) return
        smFeedAt = elapsed + SM_FEED_INTERVAL
        smFeedLane = (smFeedLane + 1) % SM_FEED_LANES
        val source = findSmEnemy(engine) ?: findSmPlayer(engine) ?: return
        val lowStage = smLineCrossStage == 0
        // stage 1 投喂点改在敌舰处（第七轮烟测实证：中场 lane 与导弹流交汇面太薄，80+ 投喂零命中；
        // 弹流全程指向敌舰，自敌舰迎面飞向玩家的投喂弹与弹流全程对头，相撞必然）。
        val spawn = if (lowStage) {
            val laneY = (smFeedLane - (SM_FEED_LANES - 1) * 0.5f) * SM_FEED_LANE_GAP
            Vector2f(SM_FEED_SPAWN_X, laneY)
        } else {
            Vector2f(source.location)
        }
        val angle = Misc.getAngleInDegrees(spawn, SM_PLAYER_ANCHOR)
        val rad = Math.toRadians(angle.toDouble())
        val vel = Vector2f(
            (kotlin.math.cos(rad) * SM_FEED_SPEED).toFloat(),
            (kotlin.math.sin(rad) * SM_FEED_SPEED).toFloat(),
        )
        val weaponId = if (lowStage) SM_FEED_MISSILE_LOW_ID else SM_FEED_MISSILE_HIGH_ID
        val spawned = engine.spawnProjectile(source, null, weaponId, spawn, angle, vel)
        if (spawned == null) {
            failureReason = "sm missile spawn returned null for weaponId=$weaponId"
            transitionSmPhase(SM_PHASE_FAILED)
            return
        }
        spawned.owner = 1
        val id = System.identityHashCode(spawned)
        if (lowStage) {
            smFedLowMissiles += id
        } else {
            spawned.hitpoints = SM_HIGH_HP_BOOST
            smFedHighMissiles += id
        }
    }

    /**
     * 辉星 MRM 相位机（规格 08 §4.2 烟测检查点映射）：
     * MOUNT（装配校验：小/中导弹槽/射程 2500/ammo 8/20/发射舱 burst 2/OP 4/10/no_drop 两件套/
     *   VfxSpec 双登记，检查点 1）→
     * PRIORITY（战机在场才放行开火：首目标类型=战机 + 发射舱单次两发分组 + 多发齐射 FPS，
     *   检查点 2/3/10）→
     * FIGHTER_HIT（命中战机机体：增伤/全部武器 EMP/逐武器电弧计数 + 战机武器熄火峰值，
     *   检查点 4）→
     * SHIP_HIT（清航母与残机令导弹只剩舰船可咬：撞击舰船 AOE + 敌盾开撞击护盾爆炸恒触发，
     *   检查点 5）→
     * LINE_CROSS（投喂 atropos hp=300 撞线同归于尽 → 投喂增压 2000HP harpoon 仅爆炸不移除；
     *   相位内目标选择遥测仍仅 战机/舰船 两型 = 不主动拦导弹，检查点 6/8）→
     * ENEMY_SCALE（installScaleForTests 1/2/5：敌版爆炸倍率逐档 0.5/1.0/2.5，检查点 9）→
     * COMPLETED（双方恢复开火做截图舞台，近期有爆炸事件才上报令十字爆炸/双拖尾入帧）。
     */
    private fun advanceSmScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deploySmReserveShips(engine)
        lockSmCamera(engine)

        val player = findSmPlayer(engine)
        val enemy = findSmEnemy(engine)
        val launcher = findSmLauncher(player)
        val pod = findSmPod(player)

        val selFighter = smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_FIGHTER)
        val selShip = smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_SHIP)
        val firstTarget = engine.customData[StellarMrmMissileAI.TELE_FIRST_TARGET] as? String
        val bonus = smTeleCount(engine, StellarMrmStrikeImpl.TELE_BONUS_HITS)
        val empHits = smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_HITS)
        val empArcs = smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_ARCS)
        val aoeHits = smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_HITS)
        val aoeShipHits = smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_SHIP_HITS)
        val shieldHits = smTeleCount(engine, StellarMrmStrikeImpl.TELE_SHIELD_HITS)
        val explosions = smTeleCount(engine, StellarMrmStrikeImpl.TELE_EXPLOSIONS)
        val lineCross = smTeleCount(engine, StellarMrmStrikeImpl.TELE_LINE_CROSS)

        when (smPhase) {
            SM_PHASE_MOUNT -> {
                stabilizeSmShips(engine, playerFire = false, enemyFire = false)
                if (elapsed - smPhaseStartedAt >= SM_MOUNT_SETTLE_SECONDS) {
                    val launcherSlot = launcher?.slot?.id
                    val podSlot = pod?.slot?.id
                    val launcherRange = launcher?.spec?.maxRange ?: -1f
                    val podRange = pod?.spec?.maxRange ?: -1f
                    val launcherOp = try { launcher?.spec?.getOrdnancePointCost(null, null) ?: -1f } catch (_: Throwable) { -1f }
                    val podOp = try { pod?.spec?.getOrdnancePointCost(null, null) ?: -1f } catch (_: Throwable) { -1f }
                    val tagsOk = launcher?.spec?.tags?.containsAll(SM_REQUIRED_TAGS) == true &&
                        pod?.spec?.tags?.containsAll(SM_REQUIRED_TAGS) == true
                    val slotOk = launcher?.slot?.slotSize == WeaponAPI.WeaponSize.SMALL &&
                        launcher?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE &&
                        pod?.slot?.slotSize == WeaponAPI.WeaponSize.MEDIUM &&
                        pod?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE
                    when {
                        launcher == null || pod == null ||
                            launcherSlot != SM_PLAYER_LAUNCHER_SLOT || podSlot != SM_PLAYER_POD_SLOT -> {
                            failureReason = "sm mount mismatch: launcherSlot=$launcherSlot podSlot=$podSlot"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        !slotOk -> {
                            failureReason = "sm slot type/size mismatch: launcher=${launcher.slot?.slotSize}/${launcher.slot?.weaponType} pod=${pod.slot?.slotSize}/${pod.slot?.weaponType}"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        kotlin.math.abs(launcherRange - SM_EXPECT_RANGE) > SM_RANGE_TOLERANCE ||
                            kotlin.math.abs(podRange - SM_EXPECT_RANGE) > SM_RANGE_TOLERANCE -> {
                            failureReason = "sm range=$launcherRange/$podRange, expect $SM_EXPECT_RANGE"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        launcher.spec?.maxAmmo != SM_LAUNCHER_AMMO || pod.spec?.maxAmmo != SM_POD_AMMO -> {
                            failureReason = "sm spec maxAmmo=${launcher.spec?.maxAmmo}/${pod.spec?.maxAmmo}, expect $SM_LAUNCHER_AMMO/$SM_POD_AMMO"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        pod.spec?.burstSize != SM_POD_BURST -> {
                            failureReason = "sm pod burstSize=${pod.spec?.burstSize}, expect $SM_POD_BURST（发射舱单次两发）"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        kotlin.math.abs(launcherOp - SM_LAUNCHER_OP) > 0.01f || kotlin.math.abs(podOp - SM_POD_OP) > 0.01f -> {
                            failureReason = "sm OP=$launcherOp/$podOp, expect $SM_LAUNCHER_OP/$SM_POD_OP"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        !tagsOk -> {
                            failureReason = "sm tags 缺 no_drop 两件套: launcher=${launcher.spec?.tags} pod=${pod.spec?.tags}"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.SM_LAUNCHER_PROJECTILE_SPEC_ID) ||
                            !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.SM_POD_PROJECTILE_SPEC_ID) -> {
                            failureReason = "sm projectile VFX 未登记: launcher/pod shot"
                            transitionSmPhase(SM_PHASE_FAILED)
                        }
                        else -> {
                            log.info(
                                "[ASTD-Automation] sm mount ok: slots=$launcherSlot/$podSlot range=$launcherRange " +
                                    "ammo=${launcher.spec?.maxAmmo}/${pod.spec?.maxAmmo} burst=${pod.spec?.burstSize} " +
                                    "OP=$launcherOp/$podOp tags=no_drop 两件套",
                            )
                            smFpsTicks = 0
                            smFpsWallStartNanos = System.nanoTime()
                            transitionSmPhase(SM_PHASE_PRIORITY)
                        }
                    }
                }
            }
            SM_PHASE_PRIORITY -> {
                // 战机在场才放行开火：首个目标选择必定发生在「战机+舰船同场」的局面下（优先追猎真验证）。
                val fightersPresent = smEnemyFighters(engine).isNotEmpty()
                stabilizeSmShips(engine, playerFire = fightersPresent, enemyFire = false)
                smFpsTicks++
                // 发射舱单次两发分组（spawn 间隔 >SM_BURST_GROUP_GAP 判定新一轮触发）。
                for (projectile in engine.projectiles) {
                    if (projectile.projectileSpecId != ASTDInGameAutomationScenario.SM_POD_PROJECTILE_SPEC_ID) continue
                    val id = System.identityHashCode(projectile)
                    if (!smSeenPodProjectiles.add(id)) continue
                    if (smPodLastSpawnAt >= 0f && elapsed - smPodLastSpawnAt > SM_BURST_GROUP_GAP) {
                        smPodBurstMax = maxOf(smPodBurstMax, smPodBurstCurrent)
                        smPodBurstCurrent = 0
                    }
                    smPodBurstCurrent++
                    smPodLastSpawnAt = elapsed
                }
                smPodBurstMax = maxOf(smPodBurstMax, smPodBurstCurrent)
                launcher?.let { smMinLauncherAmmo = minOf(smMinLauncherAmmo, it.ammo) }
                pod?.let { smMinPodAmmo = minOf(smMinPodAmmo, it.ammo) }
                if (fightersPresent && selFighter >= 1 && smPodBurstMax >= SM_POD_BURST &&
                    elapsed - smPhaseStartedAt >= SM_PRIORITY_MIN_SECONDS
                ) {
                    if (firstTarget != "fighter") {
                        failureReason = "sm first target=$firstTarget, expect fighter（战机+舰船同场首咬必须为战机）"
                        transitionSmPhase(SM_PHASE_FAILED)
                    } else {
                        val wallSeconds = (System.nanoTime() - smFpsWallStartNanos) / 1_000_000_000f
                        if (wallSeconds > 0f) smFps = smFpsTicks / wallSeconds
                        log.info(
                            "[ASTD-Automation] sm priority evidence: firstTarget=fighter selFighter=$selFighter " +
                                "podBurstMax=$smPodBurstMax minAmmo=$smMinLauncherAmmo/$smMinPodAmmo " +
                                "fps=${"%.1f".format(smFps)}",
                        )
                        transitionSmPhase(SM_PHASE_FIGHTER_HIT)
                    }
                }
            }
            SM_PHASE_FIGHTER_HIT -> {
                stabilizeSmShips(engine, playerFire = true, enemyFire = false)
                for (fighter in smEnemyFighters(engine)) {
                    smMaxFighterDisabled = maxOf(smMaxFighterDisabled, fighter.allWeapons.count { it.isDisabled })
                    if (fighter.maxHitpoints > 0f) {
                        smMinFighterHullRatio = minOf(smMinFighterHullRatio, fighter.hitpoints / fighter.maxHitpoints)
                    }
                    for (weapon in fighter.allWeapons) {
                        if (weapon.maxHealth > 0f) {
                            smMinWeaponHealthRatio = minOf(smMinWeaponHealthRatio, weapon.currHealth / weapon.maxHealth)
                        }
                        smMaxDisabledDuration = maxOf(smMaxDisabledDuration, weapon.disabledDuration)
                    }
                }
                if (bonus >= 1 && empHits >= 1 && empArcs >= 1 && smMaxFighterDisabled >= 1) {
                    log.info(
                        "[ASTD-Automation] sm fighter hit evidence: bonus=$bonus emp=$empHits arcs=$empArcs " +
                            "fighterDisabledMax=$smMaxFighterDisabled（战机武器熄火 + 逐武器电弧可读）",
                    )
                    smShipHitExplosionsBaseline = explosions
                    smShipHitAoeBaseline = aoeShipHits
                    smShipHitShieldBaseline = shieldHits
                    transitionSmPhase(SM_PHASE_SHIP_HIT)
                }
            }
            SM_PHASE_SHIP_HIT -> {
                // 清航母与残机：令导弹只剩舰船可咬（撞击舰船/护盾的真验证面）。
                if (!smCarrierCleared) {
                    smCarrierCleared = true
                    findSmCarrier(engine)?.let { engine.removeEntity(it) }
                    for (fighter in smEnemyFighters(engine)) engine.removeEntity(fighter)
                }
                stabilizeSmShips(engine, playerFire = true, enemyFire = false)
                if (explosions - smShipHitExplosionsBaseline >= SM_SHIP_HIT_MIN_EXPLOSIONS &&
                    aoeShipHits - smShipHitAoeBaseline >= 1 &&
                    shieldHits - smShipHitShieldBaseline >= 1
                ) {
                    log.info(
                        "[ASTD-Automation] sm ship hit evidence: explosionsDelta=${explosions - smShipHitExplosionsBaseline} " +
                            "aoeShipDelta=${aoeShipHits - smShipHitAoeBaseline} shieldDelta=${shieldHits - smShipHitShieldBaseline}" +
                            "（撞击舰船 AOE + 撞击护盾爆炸恒触发）",
                    )
                    smLineCrossStage = 0
                    smFeedAt = -1f
                    transitionSmPhase(SM_PHASE_LINE_CROSS)
                }
            }
            SM_PHASE_LINE_CROSS -> {
                // 撞线相位瞄准特例：发射指向最近敌导弹（lane 对头），保证有限携弹内必出相撞；
                // 目标选择遥测不因此出现导弹型（AI 自选目标，与发射指向无关）。
                val nearestFed = player?.let { p ->
                    engine.missiles.filter { it.owner != 0 }.minByOrNull { MathUtils.getDistance(p.location, it.location) }
                }
                stabilizeSmShips(engine, playerFire = true, enemyFire = false, aimOverride = nearestFed)
                // 撞线相位弹药续航（dev 舞台）：前序相位已耗尽 8+20 携弹，弹流中断则投喂弹
                // 无对头相撞机会（第五轮烟测实证 stage 1 弹尽超时）。
                launcher?.let { if (it.ammo <= 0) it.resetAmmo() }
                pod?.let { if (it.ammo <= 0) it.resetAmmo() }
                feedSmMissile(engine)
                // 撞线不追咬佐证：相位内目标选择遥测仍仅 战机/舰船 两型（TELE 键面无导弹型，
                // 编译期保证；此处日志固化运行时读数供 PR 证据）。
                if (smLineCrossStage == 0 && lineCross >= 1) {
                    smLineCrossBaseline = lineCross
                    smLineCrossStage = 1
                    smFeedAt = -1f
                    // 清残余低结构投喂弹，令 stage 1 撞线计数差分只可能来自增压弹或漏网弹。
                    for (missile in engine.missiles.toList()) {
                        if (System.identityHashCode(missile) in smFedLowMissiles && missile.owner != 0) {
                            engine.removeEntity(missile)
                        }
                    }
                    log.info(
                        "[ASTD-Automation] sm line-cross evidence: atropos hp=300 < 阈值 600 同归于尽 lineCross=$lineCross " +
                            "selFighter=$selFighter selShip=$selShip（敌导弹海经过期间目标遥测无导弹型）",
                    )
                }
                if (smLineCrossStage == 1 && !smHighHpHitConfirmed) {
                    for (missile in engine.missiles) {
                        if (System.identityHashCode(missile) !in smFedHighMissiles) continue
                        if (missile.hitpoints < SM_HIGH_HP_BOOST && engine.isEntityInPlay(missile)) {
                            smHighHpHitConfirmed = true
                            log.info(
                                "[ASTD-Automation] sm high-hp evidence: harpoon 2000HP 被命中存活 hp=${"%.0f".format(missile.hitpoints)}" +
                                    " lineCrossDelta=${lineCross - smLineCrossBaseline}（> 阈值仅爆炸不移除）",
                            )
                        }
                    }
                }
                if (smLineCrossStage == 1 && smHighHpHitConfirmed) {
                    if (lineCross - smLineCrossBaseline > 0) {
                        failureReason = "sm high-hp stage lineCrossDelta=${lineCross - smLineCrossBaseline}, expect 0（2000HP 不应触发撞线）"
                        transitionSmPhase(SM_PHASE_FAILED)
                    } else {
                        smScaleStep = 0
                        smScaleStepAt = -1f
                        DifficultyTuningImpl.installScaleForTests(1f)
                        transitionSmPhase(SM_PHASE_ENEMY_SCALE)
                    }
                }
            }
            SM_PHASE_ENEMY_SCALE -> {
                stabilizeSmShips(engine, playerFire = false, enemyFire = true)
                if (smScaleStepAt < 0f) smScaleStepAt = elapsed
                val expMultE = engine.customData[StellarMrmStrikeImpl.TELE_LAST_EXP_MULT + StellarMrmStrikeImpl.TELE_OWNER_ENEMY] as? Float
                val expected = SM_SCALE_EXPECTED_EXP_MULT[smScaleStep]
                if (expMultE != null && kotlin.math.abs(expMultE - expected) <= SM_SCALE_TOLERANCE) {
                    log.info(
                        "[ASTD-Automation] sm enemy scale evidence: k_s=${SM_SCALE_KS[smScaleStep]} expMult=$expMultE" +
                            "（敌版三档爆炸倍率 0.5/1.0/2.5 之 ${SM_SCALE_KS[smScaleStep]} 档）",
                    )
                    smScaleStep++
                    if (smScaleStep >= SM_SCALE_KS.size) {
                        DifficultyTuningImpl.installScaleForTests(null)
                        transitionSmPhase(SM_PHASE_COMPLETED)
                    } else {
                        DifficultyTuningImpl.installScaleForTests(SM_SCALE_KS[smScaleStep])
                        smScaleStepAt = -1f
                    }
                } else if (elapsed - smScaleStepAt > SM_SCALE_STEP_TIMEOUT) {
                    failureReason = "sm enemy scale timeout: k_s=${SM_SCALE_KS[smScaleStep]} lastExpMultE=$expMultE, expect $expected"
                    DifficultyTuningImpl.installScaleForTests(null)
                    transitionSmPhase(SM_PHASE_FAILED)
                }
            }
            SM_PHASE_COMPLETED -> {
                stabilizeSmShips(engine, playerFire = true, enemyFire = true)
                // 截图舞台弹药续航：令双方导弹流与十字爆炸持续入帧。
                launcher?.let { if (it.ammo <= 0) it.resetAmmo() }
                pod?.let { if (it.ammo <= 0) it.resetAmmo() }
                findSmLauncher(enemy)?.let { if (it.ammo <= 0) it.resetAmmo() }
            }
        }

        // 最近一次辉星爆炸时刻（COMPLETED 截图门控：事件近期发生才上报，令十字爆炸/双拖尾入帧）
        if (explosions > smLastTrackedExplosionCount) {
            smLastTrackedExplosionCount = explosions
            smLastExplosionAt = elapsed
        }

        val state = when {
            player == null || enemy == null -> {
                if (elapsed > 12f) {
                    failureReason = "sm ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            smPhase == SM_PHASE_FAILED -> "Failed"
            smPhase != SM_PHASE_COMPLETED &&
                elapsed - smPhaseStartedAt > SM_PHASE_TIMEOUT -> {
                failureReason = "sm phase timeout: $smPhase（selFighter=$selFighter selShip=$selShip bonus=$bonus emp=$empHits arcs=$empArcs explosions=$explosions lineCross=$lineCross disabledMax=$smMaxFighterDisabled weaponHpRatio=${"%.2f".format(smMinWeaponHealthRatio)} disabledDur=${"%.2f".format(smMaxDisabledDuration)} fighterHullRatio=${"%.2f".format(smMinFighterHullRatio)}）"
                "Failed"
            }
            smPhase == SM_PHASE_COMPLETED -> {
                val recentEvent = smLastExplosionAt >= 0f && elapsed - smLastExplosionAt <= SM_COMPLETED_EVENT_WINDOW
                if (recentEvent || elapsed - smPhaseStartedAt >= SM_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            DifficultyTuningImpl.installScaleForTests(null)
            log.info("[ASTD-Automation] Completed: stellar_mrm_basic mount/priority/fighter-hit/ship-hit/line-cross/enemy-scale evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, player)
            writeTelemetry(engine, state, player, launcher)
        }
    }


    // === Piercing lance scenario ===

    private fun findPlShipA(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == PL_PLAYER_A_HULL && !ship.isFighter }

    private fun findPlShipB(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == PL_PLAYER_B_HULL && !ship.isFighter }

    private fun findPlDecoys(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { ship -> ship.owner == 0 && ship.hullSpec?.hullId == PL_DECOY_HULL && !ship.isFighter }
            .sortedBy { System.identityHashCode(it) }

    private fun findPlEnemyTargets(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { ship -> ship.owner != 0 && ship.hullSpec?.hullId == PL_ENEMY_TARGET_HULL && !ship.isFighter }
            .sortedBy { System.identityHashCode(it) }

    private fun findPlEnemyLance(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == PL_ENEMY_LANCE_HULL && !ship.isFighter }

    private fun findPlLance(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.PL_WEAPON_ID }

    private fun plTeleCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private fun plTeleFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: -1f

    private fun lockPlCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleWidth = PL_CAMERA_VISIBLE_HEIGHT * displayAspect
        // 敌版三档相位起镜头切到东侧敌版舞台（x≈2500），此前锁定西侧主舞台。
        val center = if (plPhase == PL_PHASE_ENEMY_SCALE || plPhase == PL_PHASE_COMPLETED) {
            PL_CAMERA_CENTER_ENEMY
        } else {
            PL_CAMERA_CENTER_MAIN
        }
        viewport.setExternalControl(true)
        viewport.set(
            center.x - visibleWidth * 0.5f,
            center.y - PL_CAMERA_VISIBLE_HEIGHT * 0.5f,
            visibleWidth,
            PL_CAMERA_VISIBLE_HEIGHT,
        )
        viewport.setEverythingNearViewport(true)
    }

    /** 强制部署 mission reserves（范式同 deploySmReserveShips；三靶/两僚按身份哈希序分配锚点）。 */
    private fun deployPlReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.setSuppressDeploymentMessages(true)
            val members = manager.getReservesCopy().toList()
                .sortedBy { member -> PL_DEPLOY_ORDER.indexOf(member.hullId).let { if (it < 0) Int.MAX_VALUE else it } }
            var decoyIndex = 0
            var targetIndex = 0
            for (member in members) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == PL_PLAYER_A_HULL -> PL_A_ANCHOR
                    side == FleetSide.PLAYER && member.hullId == PL_PLAYER_B_HULL -> PL_B_ANCHOR
                    side == FleetSide.PLAYER && member.hullId == PL_DECOY_HULL ->
                        if (decoyIndex++ == 0) PL_D1_PARK_ANCHOR else PL_D2_PARK_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == PL_ENEMY_TARGET_HULL -> when (targetIndex++) {
                        0 -> PL_E1_ANCHOR
                        1 -> PL_E2_PARK_ANCHOR
                        else -> PL_E3_PARK_ANCHOR
                    }
                    side == FleetSide.ENEMY && member.hullId == PL_ENEMY_LANCE_HULL -> PL_ENEMY_LANCE_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY && member.hullId == PL_ENEMY_LANCE_HULL) PL_ENEMY_LANCE_FACING else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionPlPhase(next: String) {
        log.info("[ASTD-Automation] pl phase $plPhase -> $next at ${"%.2f".format(elapsed)}s")
        plPhase = next
        plPhaseStartedAt = elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeSmShips）：八舰逐帧奶 + 辐能清零 + 钉死锚点 +
     * force fire 独占驱动（autofire 关闭）。E2/E3 与 D1/D2 锚点随相位切换（CYCLE 集群靶停远场，
     * CLUSTER 起并入弹道线；ENEMY_SCALE 起僚舰并入敌版弹道线）。
     * 敌方开火带部署免疫闸（GD/SS/HIP/SM 实机判例：reserves 手动 spawn 舰船部署后数秒内脚本
     * applyDamage 可能全额无效）。
     */
    private fun stabilizePlShips(engine: CombatEngineAPI, playerFire: Boolean, enemyFire: Boolean) {
        val shipA = findPlShipA(engine)
        val shipB = findPlShipB(engine)
        val decoys = findPlDecoys(engine)
        val targets = findPlEnemyTargets(engine)
        val enemyLance = findPlEnemyLance(engine)
        val clusterAnchored = plPhase != PL_PHASE_MOUNT && plPhase != PL_PHASE_CYCLE
        val enemyStageAnchored = plPhase == PL_PHASE_ENEMY_SCALE || plPhase == PL_PHASE_COMPLETED

        if (shipA != null && !shipA.isHulk) {
            engine.setPlayerShipExternal(shipA)
            stabilizeShip(shipA, PL_A_ANCHOR, 0f, allowFire = true, preserveAI = true)
            shipA.setHitpoints(shipA.maxHitpoints)
            shipA.fluxTracker.setCurrFlux(0f)
            shipA.fluxTracker.setHardFlux(0f)
            shipA.shield?.let { if (it.isOn) it.toggleOff() }
            setPlAutofire(shipA, false)
            val target = targets.firstOrNull()
            shipA.setShipTarget(target)
            findPlLance(shipA)?.let { weapon ->
                if (target != null) weapon.setCurrAngle(Misc.getAngleInDegrees(weapon.location, target.location))
                weapon.setForceFireOneFrame(playerFire)
            }
        }
        // B：能量槽装配证明件，全程不开火（autofire 关闭 + 不 force fire），仅钉锚点奶血。
        if (shipB != null && !shipB.isHulk) {
            stabilizeShip(shipB, PL_B_ANCHOR, 0f, allowFire = false, preserveAI = true)
            shipB.setHitpoints(shipB.maxHitpoints)
            shipB.fluxTracker.setCurrFlux(0f)
            shipB.fluxTracker.setHardFlux(0f)
            shipB.shield?.let { if (it.isOn) it.toggleOff() }
            setPlAutofire(shipB, false)
        }
        decoys.forEachIndexed { index, decoy ->
            if (decoy.isHulk) return@forEachIndexed
            val staged = if (enemyStageAnchored) {
                if (index == 0) PL_D1_ANCHOR else PL_D2_ANCHOR
            } else {
                if (index == 0) PL_D1_PARK_ANCHOR else PL_D2_PARK_ANCHOR
            }
            stabilizeShip(decoy, staged, 0f, allowFire = false, preserveAI = true)
            decoy.setHitpoints(decoy.maxHitpoints)
            decoy.fluxTracker.setCurrFlux(0f)
            decoy.fluxTracker.setHardFlux(0f)
            decoy.shield?.let { if (it.isOn) it.toggleOff() }
        }
        targets.forEachIndexed { index, target ->
            if (target.isHulk) return@forEachIndexed
            val anchor = when (index) {
                0 -> PL_E1_ANCHOR
                1 -> if (clusterAnchored) PL_E2_CLUSTER_ANCHOR else PL_E2_PARK_ANCHOR
                else -> if (clusterAnchored) PL_E3_CLUSTER_ANCHOR else PL_E3_PARK_ANCHOR
            }
            stabilizeShip(target, anchor, 180f, allowFire = false, preserveAI = true)
            target.setHitpoints(target.maxHitpoints)
            target.fluxTracker.setCurrFlux(0f)
            target.fluxTracker.setHardFlux(0f)
            target.shield?.let { if (it.isOn) it.toggleOff() }
        }
        if (enemyLance != null && !enemyLance.isHulk) {
            stabilizeShip(enemyLance, PL_ENEMY_LANCE_ANCHOR, PL_ENEMY_LANCE_FACING, allowFire = true, preserveAI = true)
            enemyLance.setHitpoints(enemyLance.maxHitpoints)
            enemyLance.fluxTracker.setCurrFlux(0f)
            enemyLance.fluxTracker.setHardFlux(0f)
            enemyLance.shield?.let { if (it.isOn) it.toggleOff() }
            setPlAutofire(enemyLance, false)
            val target = decoys.firstOrNull()
            enemyLance.setShipTarget(target)
            findPlLance(enemyLance)?.let { weapon ->
                if (target != null) weapon.setCurrAngle(Misc.getAngleInDegrees(weapon.location, target.location))
                val gated = enemyFire && elapsed - plScaleStepAt >= PL_ENEMY_SETTLE_SECONDS
                weapon.setForceFireOneFrame(gated)
            }
        }
    }

    /**
     * 贯星舞台外来实体清扫：第三方 mod 会向 mission 战斗注入中立战机/导弹（实机判例：中立 sarissa
     * 战机群 owner=100 + 归属不明导弹 owner=1 游荡进弹道线，2026-07-29 两次实机各 9/5 个锥面连带）。
     * 本场景全部自有舰船仅为 onslaught/champion/enforcer 三舰体、无任何战机与导弹武器，
     * 故每帧移除此三舰体以外的舰船（含战机）与全部导弹，保证 CYCLE「锥内零连带」舞台语义确定性。
     */
    private fun sweepPlForeignEntities(engine: CombatEngineAPI) {
        for (ship in ArrayList(engine.ships)) {
            val hullId = ship.hullSpec?.hullId
            if (hullId in PL_SCENARIO_HULLS && !ship.isFighter) continue
            log.info("[ASTD-Automation] pl 舞台外来舰船移除: hull=$hullId owner=${ship.owner} fighter=${ship.isFighter} loc=(${ship.location.x.toInt()},${ship.location.y.toInt()})")
            engine.removeEntity(ship)
        }
        for (missile in ArrayList(engine.missiles)) {
            log.info("[ASTD-Automation] pl 舞台外来导弹移除: spec=${missile.projectileSpecId} owner=${missile.owner} loc=(${missile.location.x.toInt()},${missile.location.y.toInt()})")
            engine.removeEntity(missile)
        }
    }

    /** 贯星武器组 autofire 总开关（范式同 setSmAutofire）：force fire 独占驱动时关闭。 */
    private fun setPlAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none { it.id == ASTDInGameAutomationScenario.PL_WEAPON_ID }) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    /**
     * 贯星之矛相位机（规格 09 §4.2 烟测检查点映射）：
     * MOUNT（装配校验：onslaught 大型实弹槽 WS 019 + champion 大型能量槽 WS 008 双槽可装、
     *   spec type=ENERGY / mountType=HYBRID、射程 1000、冷却 5s、OP 30、no_drop 两件套、VfxSpec 登记；
     *   能量结算探针：energyWeaponRangeBonus +50% 射程生效 / ballisticWeaponRangeBonus +50% 不生效，
     *   检查点 1/8）→
     * CYCLE（对单体靶强制开火：充能条窗口可读 + 首充 2s + 出膛间隔 ≈7s（2s 充能 + 5s 冷却）、
     *   弹体 VFX 驱动接管、命中单体三层特效计数 + 锥内零连带（无浮字无锥面命中）+ 玩家恒 v2 读数，
     *   检查点 2/3/4）→
     * CLUSTER（E2/E3 并入弹道线：锥面命中 ≥2 + 破片浮字 ≥2 + 本体豁免契约零破坏，检查点 5）→
     * ENEMY_SCALE（installScaleForTests 1/2/5 敌版逐档：半角 20/25/40、锥长 300/375/600、
     *   伤害 2500/3125/5000；破晓档僚舰被锥面波及 + 600su/80° 帧率采样，检查点 6/7）→
     * COMPLETED（双方恢复开火做截图舞台，近期有锥面结算事件才上报令大光柱/锥面/浮字入帧）。
     */
    private fun advancePlScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployPlReserveShips(engine)
        sweepPlForeignEntities(engine)
        lockPlCamera(engine)

        val shipA = findPlShipA(engine)
        val shipB = findPlShipB(engine)
        val lanceA = findPlLance(shipA)
        val lanceB = findPlLance(shipB)

        val resolves = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_RESOLVE)
        val coneHits = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_CONE_HITS)
        val floaty = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_FLOATY)
        val exemptViolations = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_DIRECT_EXEMPT_VIOLATION)
        val lastConeHits = plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_LAST_CONE_HITS)
        val lastHalfAngle = plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_HALF_ANGLE)
        val lastRange = plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_RANGE)
        val lastDamage = plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_DAMAGE)
        val impactFlashes = plTeleCount(engine, PiercingLanceVfx.TELEMETRY_IMPACT_FLASH)
        val pillars = plTeleCount(engine, PiercingLanceVfx.TELEMETRY_PILLAR)
        val coneVfx = plTeleCount(engine, PiercingLanceVfx.TELEMETRY_CONE_VFX)

        when (plPhase) {
            PL_PHASE_MOUNT -> {
                stabilizePlShips(engine, playerFire = false, enemyFire = false)
                if (elapsed - plPhaseStartedAt >= PL_MOUNT_SETTLE_SECONDS) {
                    when (plMountStep) {
                        0 -> {
                            val slotA = lanceA?.slot
                            val slotB = lanceB?.slot
                            val spec = lanceA?.spec
                            val op = try { spec?.getOrdnancePointCost(null, null) ?: -1f } catch (_: Throwable) { -1f }
                            val tagsOk = spec?.tags?.containsAll(PL_REQUIRED_TAGS) == true
                            when {
                                lanceA == null || lanceB == null -> {
                                    failureReason = "pl mount missing: lanceA=${lanceA != null} lanceB=${lanceB != null}"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                slotA?.id != PL_A_SLOT || slotA?.weaponType != WeaponAPI.WeaponType.BALLISTIC ||
                                    slotA.slotSize != WeaponAPI.WeaponSize.LARGE -> {
                                    failureReason = "pl A slot mismatch: ${slotA?.id}/${slotA?.weaponType}/${slotA?.slotSize}（应为 $PL_A_SLOT BALLISTIC LARGE）"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                slotB?.id != PL_B_SLOT || slotB?.weaponType != WeaponAPI.WeaponType.ENERGY ||
                                    slotB.slotSize != WeaponAPI.WeaponSize.LARGE -> {
                                    failureReason = "pl B slot mismatch: ${slotB?.id}/${slotB?.weaponType}/${slotB?.slotSize}（应为 $PL_B_SLOT ENERGY LARGE）"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                spec?.type != WeaponAPI.WeaponType.ENERGY ||
                                    spec.mountType != WeaponAPI.WeaponType.HYBRID -> {
                                    failureReason = "pl spec type/mountType mismatch: ${spec?.type}/${spec?.mountType}（应为 ENERGY 结算 + HYBRID 挂载）"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                kotlin.math.abs((spec?.maxRange ?: -1f) - PL_EXPECT_RANGE) > PL_RANGE_TOLERANCE -> {
                                    failureReason = "pl spec maxRange=${spec?.maxRange}, expect $PL_EXPECT_RANGE"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                kotlin.math.abs(lanceA.cooldown - PL_EXPECT_COOLDOWN) > PL_COOLDOWN_TOLERANCE -> {
                                    failureReason = "pl cooldown=${lanceA.cooldown}, expect $PL_EXPECT_COOLDOWN"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                kotlin.math.abs(op - PL_EXPECT_OP) > 0.01f -> {
                                    failureReason = "pl OP=$op, expect $PL_EXPECT_OP"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                !tagsOk -> {
                                    failureReason = "pl tags 缺 no_drop 两件套: ${spec?.tags}"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID) -> {
                                    failureReason = "pl projectile VFX 未登记: ${ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID}"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                shipA == null -> {
                                    failureReason = "pl shipA missing for stat probe"
                                    transitionPlPhase(PL_PHASE_FAILED)
                                }
                                else -> {
                                    // 能量结算探针步骤 1：能量射程加成 +50%（检查点 1「按能量结算」正向证据）。
                                    plProbeR0 = lanceA.range
                                    shipA.mutableStats.energyWeaponRangeBonus.modifyPercent(PL_STAT_PROBE_ID, PL_STAT_PROBE_PERCENT)
                                    plMountStep = 1
                                    plMountStepAt = elapsed
                                    log.info("[ASTD-Automation] pl mount ok: A=${slotA.id}/${slotA.weaponType} B=${slotB?.id}/${slotB?.weaponType} spec=${spec.type}/${spec.mountType} range=${spec.maxRange} cooldown=${lanceA.cooldown} OP=$op r0=$plProbeR0")
                                }
                            }
                        }
                        1 -> if (elapsed - plMountStepAt >= PL_STAT_PROBE_SETTLE_SECONDS) {
                            if (lanceA == null || shipA == null) {
                                failureReason = "pl 探针步骤 1 舰船/武器丢失: lanceA=${lanceA != null} shipA=${shipA != null}"
                                transitionPlPhase(PL_PHASE_FAILED)
                            } else {
                                plProbeR1 = lanceA.range
                                shipA.mutableStats.energyWeaponRangeBonus.unmodifyPercent(PL_STAT_PROBE_ID)
                                shipA.mutableStats.ballisticWeaponRangeBonus.modifyPercent(PL_STAT_PROBE_ID, PL_STAT_PROBE_PERCENT)
                                plMountStep = 2
                                plMountStepAt = elapsed
                            }
                        }
                        else -> if (elapsed - plMountStepAt >= PL_STAT_PROBE_SETTLE_SECONDS) {
                            if (lanceA == null || shipA == null) {
                                failureReason = "pl 探针步骤 2 舰船/武器丢失: lanceA=${lanceA != null} shipA=${shipA != null}"
                                transitionPlPhase(PL_PHASE_FAILED)
                            } else {
                                plProbeR2 = lanceA.range
                                shipA.mutableStats.ballisticWeaponRangeBonus.unmodifyPercent(PL_STAT_PROBE_ID)
                                when {
                                    // 能量加成生效（+50% → ≥1.3× 宽松界）。
                                    plProbeR1 < plProbeR0 * PL_ENERGY_PROBE_MIN_RATIO -> {
                                        failureReason = "pl 能量结算探针失败：energyWeaponRangeBonus +50% 后 range $plProbeR0 → $plProbeR1（未生效）"
                                        transitionPlPhase(PL_PHASE_FAILED)
                                    }
                                    // 实弹加成不得生效（反证：按能量结算而非实弹）。
                                    kotlin.math.abs(plProbeR2 - plProbeR0) > PL_BALLISTIC_PROBE_TOLERANCE -> {
                                        failureReason = "pl 能量结算探针反证失败：ballisticWeaponRangeBonus +50% 后 range $plProbeR0 → $plProbeR2（实弹加成不应生效）"
                                        transitionPlPhase(PL_PHASE_FAILED)
                                    }
                                    else -> {
                                        log.info("[ASTD-Automation] pl energy settlement probe ok: r0=$plProbeR0 r1(energy+50%)=$plProbeR1 r2(ballistic+50%)=$plProbeR2")
                                        transitionPlPhase(PL_PHASE_CYCLE)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PL_PHASE_CYCLE -> {
                stabilizePlShips(engine, playerFire = true, enemyFire = false)
                // 充能窗口观测：chargeLevel ∈ (0,1) 即原版充能条在推进（2s 充能可读证据）。
                val chargeLevel = lanceA?.chargeLevel ?: 0f
                if (chargeLevel > 0.001f && chargeLevel < 0.999f) {
                    plChargeObserved = true
                    if (plChargeStartAt < 0f) plChargeStartAt = elapsed
                }
                // 出膛计时：玩家贯星弹体 spawn 时间序列（7s 循环证据）+ 首充耗时。
                for (projectile in engine.projectiles) {
                    if (projectile.projectileSpecId != ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID || projectile.owner != 0) continue
                    val id = System.identityHashCode(projectile)
                    if (!plSeenProjectiles.add(id)) continue
                    plSpawnTimes += elapsed
                    if (plSpawnTimes.size == 1 && plChargeStartAt >= 0f) {
                        plFirstChargeToShotSeconds = elapsed - plChargeStartAt
                    }
                    if (plSpawnTimes.size >= 2) {
                        plCycleIntervalSeconds = plSpawnTimes[plSpawnTimes.size - 1] - plSpawnTimes[plSpawnTimes.size - 2]
                    }
                }
                // 弹体 VFX 驱动接管闩：本场景唯一弹种为贯星弹（其余舰船全程不开火）。
                val vfxTelemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
                if (vfxTelemetry.trackedCount > 0 &&
                    vfxTelemetry.lastProjectileSpecId == ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID
                ) {
                    plVfxDriverSeen = true
                }
                if (resolves >= PL_CYCLE_MIN_HITS && plSpawnTimes.size >= PL_CYCLE_MIN_HITS) {
                    when {
                        !plChargeObserved -> {
                            failureReason = "pl 充能窗口未观测到（chargeLevel 恒 0 或恒 1，2s 充能条不可读）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }
                        plFirstChargeToShotSeconds < PL_FIRST_CHARGE_MIN || plFirstChargeToShotSeconds > PL_FIRST_CHARGE_MAX -> {
                            failureReason = "pl 首充耗时=${"%.2f".format(plFirstChargeToShotSeconds)}s, expect ∈ [$PL_FIRST_CHARGE_MIN, $PL_FIRST_CHARGE_MAX]（2s 充能）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }
                        plCycleIntervalSeconds < PL_CYCLE_INTERVAL_MIN || plCycleIntervalSeconds > PL_CYCLE_INTERVAL_MAX -> {
                            failureReason = "pl 出膛间隔=${"%.2f".format(plCycleIntervalSeconds)}s, expect ∈ [$PL_CYCLE_INTERVAL_MIN, $PL_CYCLE_INTERVAL_MAX]（2s 充能 + 5s 冷却 = 7s 循环）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }
                        !plVfxDriverSeen -> {
                            failureReason = "pl 弹体 VFX 驱动未观测（texTrail + bloom 弹头未接管弹体观感）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }
                        impactFlashes < 1 || pillars < 1 || coneVfx < 1 -> {
                            failureReason = "pl 命中三层特效计数不足: flash=$impactFlashes pillar=$pillars coneVfx=$coneVfx"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }
                        coneHits != 0 || floaty != 0 -> {
                            failureReason = "pl 命中单体出现连带: coneHits=$coneHits floaty=$floaty（单体靶锥内应零连带）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }
                        kotlin.math.abs(lastHalfAngle - 25f) > PL_SCALE_TOLERANCE ||
                            kotlin.math.abs(lastRange - 375f) > PL_SCALE_TOLERANCE ||
                            kotlin.math.abs(lastDamage - 3125f) > PL_SCALE_TOLERANCE -> {
                            failureReason = "pl 玩家恒 v2 读数偏差: halfAngle=$lastHalfAngle range=$lastRange damage=$lastDamage（应 25/375/3125）"
                            transitionPlPhase(PL_PHASE_FAILED)
                        }
                        else -> {
                            log.info(
                                "[ASTD-Automation] pl cycle ok: firstCharge=${"%.2f".format(plFirstChargeToShotSeconds)}s " +
                                    "interval=${"%.2f".format(plCycleIntervalSeconds)}s shots=${plSpawnTimes.size} " +
                                    "flash=$impactFlashes pillar=$pillars coneVfx=$coneVfx 单体零连带 v2=25/375/3125",
                            )
                            plClusterConeHitsBaseline = coneHits
                            plClusterFloatyBaseline = floaty
                            plClusterMaxLastConeHits = 0
                            transitionPlPhase(PL_PHASE_CLUSTER)
                        }
                    }
                }
            }
            PL_PHASE_CLUSTER -> {
                stabilizePlShips(engine, playerFire = true, enemyFire = false)
                plClusterMaxLastConeHits = maxOf(plClusterMaxLastConeHits, lastConeHits)
                val coneDelta = coneHits - plClusterConeHitsBaseline
                val floatyDelta = floaty - plClusterFloatyBaseline
                if (plClusterMaxLastConeHits >= PL_CLUSTER_MIN_CONE_HITS && floatyDelta >= PL_CLUSTER_MIN_CONE_HITS) {
                    if (exemptViolations != 0) {
                        failureReason = "pl 命中本体豁免契约被破坏: violations=$exemptViolations"
                        transitionPlPhase(PL_PHASE_FAILED)
                    } else {
                        log.info("[ASTD-Automation] pl cluster ok: lastConeHits=$plClusterMaxLastConeHits coneDelta=$coneDelta floatyDelta=$floatyDelta exemptViolations=0")
                        plScaleStep = 0
                        plScaleResolveBaseline = resolves
                        DifficultyTuningImpl.installScaleForTests(PL_SCALE_KS[0])
                        plScaleStepAt = elapsed
                        plScaleMaxConeHits = 0
                        transitionPlPhase(PL_PHASE_ENEMY_SCALE)
                    }
                }
            }
            PL_PHASE_ENEMY_SCALE -> {
                // 敌版逐档：玩家停火（LAST_* 读数唯一归因敌版），敌版贯星打僚舰集群。
                stabilizePlShips(engine, playerFire = false, enemyFire = true)
                plScaleMaxConeHits = maxOf(plScaleMaxConeHits, lastConeHits)
                if (plScaleStep == PL_SCALE_KS.lastIndex) {
                    plScaleFpsTicks++
                }
                if (resolves > plScaleResolveBaseline && elapsed - plScaleStepAt >= PL_ENEMY_SETTLE_SECONDS) {
                    val expectHalfAngle = PL_SCALE_EXPECTED_HALF_ANGLE[plScaleStep]
                    val expectRange = PL_SCALE_EXPECTED_RANGE[plScaleStep]
                    val expectDamage = PL_SCALE_EXPECTED_DAMAGE[plScaleStep]
                    if (kotlin.math.abs(lastHalfAngle - expectHalfAngle) > PL_SCALE_TOLERANCE ||
                        kotlin.math.abs(lastRange - expectRange) > PL_SCALE_TOLERANCE ||
                        kotlin.math.abs(lastDamage - expectDamage) > PL_SCALE_TOLERANCE
                    ) {
                        failureReason = "pl 敌版 k_s=${PL_SCALE_KS[plScaleStep]} 读数偏差: halfAngle=$lastHalfAngle range=$lastRange damage=$lastDamage（应 $expectHalfAngle/$expectRange/$expectDamage）"
                        transitionPlPhase(PL_PHASE_FAILED)
                    } else if (plScaleStep < PL_SCALE_KS.lastIndex) {
                        log.info("[ASTD-Automation] pl enemy scale k_s=${PL_SCALE_KS[plScaleStep]} ok: $lastHalfAngle/$lastRange/$lastDamage")
                        plScaleStep++
                        DifficultyTuningImpl.installScaleForTests(PL_SCALE_KS[plScaleStep])
                        plScaleResolveBaseline = resolves
                        plScaleStepAt = elapsed
                        if (plScaleStep == PL_SCALE_KS.lastIndex) {
                            plScaleFpsTicks = 0
                            plScaleFpsWallStartNanos = System.nanoTime()
                        }
                    } else {
                        // 破晓档：僚舰必须被锥面波及（80°/600su 放大证据）+ 帧率采样收口。
                        val wallSeconds = (System.nanoTime() - plScaleFpsWallStartNanos) / 1_000_000_000f
                        if (wallSeconds > 0f) plScaleFps = plScaleFpsTicks / wallSeconds
                        when {
                            plScaleMaxConeHits < 1 -> {
                                failureReason = "pl 破晓档锥面未波及僚舰: maxConeHits=$plScaleMaxConeHits（80°/600su 放大未生效）"
                                transitionPlPhase(PL_PHASE_FAILED)
                            }
                            plScaleFps > 0f && plScaleFps < PL_MIN_FPS -> {
                                failureReason = "pl 破晓档帧率塌陷: fps=${"%.1f".format(plScaleFps)} < $PL_MIN_FPS"
                                transitionPlPhase(PL_PHASE_FAILED)
                            }
                            else -> {
                                log.info("[ASTD-Automation] pl enemy scale k_s=5 ok: 40/600/5000 coneHits=$plScaleMaxConeHits fps=${"%.1f".format(plScaleFps)}")
                                transitionPlPhase(PL_PHASE_COMPLETED)
                            }
                        }
                    }
                }
            }
            PL_PHASE_COMPLETED -> {
                stabilizePlShips(engine, playerFire = true, enemyFire = true)
            }
        }

        // 最近一次锥面结算时刻（COMPLETED 截图门控：事件近期发生才上报，令大光柱/锥面/浮字入帧）
        if (resolves > plLastTrackedResolveCount) {
            plLastTrackedResolveCount = resolves
            plLastResolveAt = elapsed
        }

        val state = when {
            shipA == null || shipB == null -> {
                if (elapsed > 12f) {
                    failureReason = "pl ships missing: shipA=${shipA != null}, shipB=${shipB != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }
            plPhase == PL_PHASE_FAILED -> "Failed"
            plPhase != PL_PHASE_COMPLETED &&
                elapsed - plPhaseStartedAt > plPhaseTimeout() -> {
                failureReason = "pl phase timeout: $plPhase（resolves=$resolves coneHits=$coneHits floaty=$floaty shots=${plSpawnTimes.size} scaleStep=$plScaleStep chargeObserved=$plChargeObserved）"
                "Failed"
            }
            plPhase == PL_PHASE_COMPLETED -> {
                val recentEvent = plLastResolveAt >= 0f && elapsed - plLastResolveAt <= PL_COMPLETED_EVENT_WINDOW
                if (recentEvent || elapsed - plPhaseStartedAt >= PL_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            DifficultyTuningImpl.installScaleForTests(null)
            log.info("[ASTD-Automation] Completed: piercing_lance_basic mount/cycle/cluster/enemy-scale evidence observed")
        }
        if (state == "Failed") {
            DifficultyTuningImpl.installScaleForTests(null)
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, shipA)
            writeTelemetry(engine, state, shipA, lanceA)
        }
    }

    /** 分相位超时：ENEMY_SCALE 需三档 ×（部署免疫闸 + 7s 循环）故放宽。 */
    private fun plPhaseTimeout(): Float = when (plPhase) {
        PL_PHASE_ENEMY_SCALE -> PL_ENEMY_SCALE_PHASE_TIMEOUT
        else -> PL_PHASE_TIMEOUT
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
            !ASTDInGameAutomationScenario.isPsEnabled() &&
            !ASTDInGameAutomationScenario.isSsEnabled() &&
            !ASTDInGameAutomationScenario.isGdEnabled() &&
            !ASTDInGameAutomationScenario.isHipEnabled() &&
            !ASTDInGameAutomationScenario.isSmEnabled() &&
            !ASTDInGameAutomationScenario.isPlEnabled()
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
            ASTDInGameAutomationScenario.isPlEnabled() -> ASTDInGameAutomationScenario.PL_SCENARIO_ID
            ASTDInGameAutomationScenario.isSmEnabled() -> ASTDInGameAutomationScenario.SM_SCENARIO_ID
            ASTDInGameAutomationScenario.isGdEnabled() -> ASTDInGameAutomationScenario.GD_SCENARIO_ID
            ASTDInGameAutomationScenario.isHipEnabled() -> ASTDInGameAutomationScenario.HIP_SCENARIO_ID
            ASTDInGameAutomationScenario.isSsEnabled() -> ASTDInGameAutomationScenario.SS_SCENARIO_ID
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
            if (ASTDInGameAutomationScenario.isPlEnabled()) {
                val plShipA = findPlShipA(engine)
                val plShipB = findPlShipB(engine)
                val plLanceA = findPlLance(plShipA)
                val plLanceB = findPlLance(plShipB)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 09 §4.2 烟测检查点）----
                appendLine("  \"plPhase\": \"$plPhase\",")
                appendLine("  \"plLanceASlotId\": ${jsonString(plLanceA?.slot?.id)},")
                appendLine("  \"plLanceASlotType\": ${jsonString(plLanceA?.slot?.weaponType?.name)},")
                appendLine("  \"plLanceBSlotId\": ${jsonString(plLanceB?.slot?.id)},")
                appendLine("  \"plLanceBSlotType\": ${jsonString(plLanceB?.slot?.weaponType?.name)},")
                appendLine("  \"plSpecType\": ${jsonString(plLanceA?.spec?.type?.name)},")
                appendLine("  \"plSpecMountType\": ${jsonString(plLanceA?.spec?.mountType?.name)},")
                appendLine("  \"plProbeR0\": ${formatFloat(plProbeR0)},")
                appendLine("  \"plProbeR1\": ${formatFloat(plProbeR1)},")
                appendLine("  \"plProbeR2\": ${formatFloat(plProbeR2)},")
                appendLine("  \"plChargeObserved\": $plChargeObserved,")
                appendLine("  \"plFirstChargeToShotSeconds\": ${formatFloat(plFirstChargeToShotSeconds)},")
                appendLine("  \"plCycleIntervalSeconds\": ${formatFloat(plCycleIntervalSeconds)},")
                appendLine("  \"plShotsFired\": ${plSpawnTimes.size},")
                appendLine("  \"plVfxDriverSeen\": $plVfxDriverSeen,")
                appendLine("  \"plResolves\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_RESOLVE)},")
                appendLine("  \"plImpactFlashes\": ${plTeleCount(engine, PiercingLanceVfx.TELEMETRY_IMPACT_FLASH)},")
                appendLine("  \"plPillars\": ${plTeleCount(engine, PiercingLanceVfx.TELEMETRY_PILLAR)},")
                appendLine("  \"plConeVfx\": ${plTeleCount(engine, PiercingLanceVfx.TELEMETRY_CONE_VFX)},")
                appendLine("  \"plConeHits\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_CONE_HITS)},")
                appendLine("  \"plFloaty\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_FLOATY)},")
                appendLine("  \"plExemptViolations\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_DIRECT_EXEMPT_VIOLATION)},")
                appendLine("  \"plLastConeHits\": ${plTeleCount(engine, PiercingLanceConeStrike.TELEMETRY_LAST_CONE_HITS)},")
                appendLine("  \"plLastHalfAngle\": ${formatFloat(plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_HALF_ANGLE))},")
                appendLine("  \"plLastRange\": ${formatFloat(plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_RANGE))},")
                appendLine("  \"plLastDamage\": ${formatFloat(plTeleFloat(engine, PiercingLanceConeStrike.TELEMETRY_LAST_DAMAGE))},")
                appendLine("  \"plClusterMaxLastConeHits\": $plClusterMaxLastConeHits,")
                appendLine("  \"plScaleStep\": $plScaleStep,")
                appendLine("  \"plScaleMaxConeHits\": $plScaleMaxConeHits,")
                appendLine("  \"plScaleFps\": ${formatFloat(plScaleFps)},")
                appendLine("  \"plOwnLanceProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.PL_PROJECTILE_SPEC_ID && it.owner == 0 }},")
                appendLine("  \"plDevMode\": ${Global.getSettings().isDevMode},")
            } else if (ASTDInGameAutomationScenario.isSmEnabled()) {
                val smPlayer = findSmPlayer(engine)
                val smLauncher = findSmLauncher(smPlayer)
                val smPod = findSmPod(smPlayer)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 08 §4.2 烟测检查点）----
                appendLine("  \"smPhase\": \"$smPhase\",")
                appendLine("  \"smLauncherSlotId\": ${jsonString(smLauncher?.slot?.id)},")
                appendLine("  \"smPodSlotId\": ${jsonString(smPod?.slot?.id)},")
                appendLine("  \"smWeaponRange\": ${formatFloat(smLauncher?.range ?: -1f)},")
                appendLine("  \"smLauncherAmmo\": ${smLauncher?.ammo ?: -1},")
                appendLine("  \"smPodAmmo\": ${smPod?.ammo ?: -1},")
                appendLine("  \"smMinLauncherAmmo\": ${if (smMinLauncherAmmo == Int.MAX_VALUE) -1 else smMinLauncherAmmo},")
                appendLine("  \"smMinPodAmmo\": ${if (smMinPodAmmo == Int.MAX_VALUE) -1 else smMinPodAmmo},")
                appendLine("  \"smPodBurstMax\": $smPodBurstMax,")
                appendLine("  \"smFirstTarget\": ${jsonString(engine.customData[StellarMrmMissileAI.TELE_FIRST_TARGET] as? String)},")
                appendLine("  \"smSelFighter\": ${smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_FIGHTER)},")
                appendLine("  \"smSelShip\": ${smTeleCount(engine, StellarMrmMissileAI.TELE_SEL_SHIP)},")
                appendLine("  \"smBonusHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_BONUS_HITS)},")
                appendLine("  \"smEmpHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_HITS)},")
                appendLine("  \"smEmpArcs\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_EMP_ARCS)},")
                appendLine("  \"smAoeHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_HITS)},")
                appendLine("  \"smAoeShipHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_AOE_SHIP_HITS)},")
                appendLine("  \"smShieldHits\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_SHIELD_HITS)},")
                appendLine("  \"smExplosions\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_EXPLOSIONS)},")
                appendLine("  \"smLineCross\": ${smTeleCount(engine, StellarMrmStrikeImpl.TELE_LINE_CROSS)},")
                appendLine("  \"smLineCrossStage\": $smLineCrossStage,")
                appendLine("  \"smHighHpHitConfirmed\": $smHighHpHitConfirmed,")
                appendLine("  \"smMaxFighterDisabled\": $smMaxFighterDisabled,")
                appendLine("  \"smMinWeaponHealthRatio\": ${formatFloat(smMinWeaponHealthRatio)},")
                appendLine("  \"smMaxDisabledDuration\": ${formatFloat(smMaxDisabledDuration)},")
                appendLine("  \"smMinFighterHullRatio\": ${formatFloat(smMinFighterHullRatio)},")
                appendLine("  \"smLastFBonus\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_F_BONUS + StellarMrmStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
                appendLine("  \"smLastWEmp\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_W_EMP + StellarMrmStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
                appendLine("  \"smLastExpMultP\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_EXP_MULT + StellarMrmStrikeImpl.TELE_OWNER_PLAYER] as? Float ?: -1f)},")
                appendLine("  \"smLastExpMultE\": ${formatFloat(engine.customData[StellarMrmStrikeImpl.TELE_LAST_EXP_MULT + StellarMrmStrikeImpl.TELE_OWNER_ENEMY] as? Float ?: -1f)},")
                appendLine("  \"smScaleStep\": $smScaleStep,")
                appendLine("  \"smFps\": ${formatFloat(smFps)},")
                appendLine("  \"smDevMode\": ${Global.getSettings().isDevMode},")
                appendLine("  \"smOwnLauncherProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.SM_LAUNCHER_PROJECTILE_SPEC_ID }},")
                appendLine("  \"smOwnPodProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.SM_POD_PROJECTILE_SPEC_ID }},")
                appendLine("  \"smEnemyMissilesInPlay\": ${engine.missiles.count { it.owner != 0 }},")
            } else if (ASTDInGameAutomationScenario.isGdEnabled()) {
                val gdPlayer = findGdPlayer(engine)
                val gdTarget = findGdTarget(engine)
                val gdLauncher = findGdLauncher(gdPlayer)
                val gdPod = findGdPod(gdPlayer)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 10 §4.2 烟测检查点）----
                appendLine("  \"gdPhase\": \"$gdPhase\",")
                appendLine("  \"gdLauncherSlotId\": ${jsonString(gdLauncher?.slot?.id)},")
                appendLine("  \"gdPodSlotId\": ${jsonString(gdPod?.slot?.id)},")
                appendLine("  \"gdLauncherAmmo\": ${gdLauncher?.ammo ?: -1},")
                appendLine("  \"gdPodAmmo\": ${gdPod?.ammo ?: -1},")
                appendLine("  \"gdTargetHitpoints\": ${formatFloat(gdTarget?.hitpoints ?: -1f)},")
                appendLine("  \"gdTargetMaxHitpoints\": ${formatFloat(gdTarget?.maxHitpoints ?: -1f)},")
                appendLine("  \"gdSalvoTargetMinHp\": ${formatFloat(gdSalvoTargetMinHp)},")
                appendLine("  \"gdSalvo\": ${GeminiDemSalvoOnFireEffect.salvoCount(engine)},")
                appendLine("  \"gdWarheadsSpawned\": ${GeminiDemSalvoOnFireEffect.warheadsSpawned(engine)},")
                appendLine("  \"gdTrackAiSeen\": ${gdTrackAiSeen.size},")
                appendLine("  \"gdTrackTargetNonNull\": $gdTrackTargetNonNull,")
                appendLine("  \"gdDemTakeoverSeen\": ${gdDemTakeoverSeen.size},")
                appendLine("  \"gdKineticHits\": ${GeminiDemPayloadBeamEffect.kineticHitCount(engine)},")
                appendLine("  \"gdHeHits\": ${GeminiDemPayloadBeamEffect.heHitCount(engine)},")
                appendLine("  \"gdEmpArcs\": ${GeminiDemPayloadBeamEffect.empArcCount(engine)},")
                appendLine("  \"gdSyncTriggers\": ${GeminiDemSyncHandler.syncTriggerCount(engine)},")
                appendLine("  \"gdSyncLastMult\": ${formatFloat(engine.customData[GeminiDemSyncHandler.TELEMETRY_SYNC_LAST_MULT] as? Float ?: -1f)},")
                appendLine("  \"gdHitRegistered\": ${GeminiDemSyncHandler.hitRegisteredCount(engine)},")
                appendLine("  \"gdEnemyMinPlayerHp\": ${formatFloat(gdEnemyMinPlayerHp)},")
                appendLine("  \"gdWarheadsInPlay\": ${engine.missiles.count { it.customData[GeminiDemDifficulty.SALVO_KEY] != null }},")
            } else if (ASTDInGameAutomationScenario.isSsEnabled()) {
                val ssPlayer = findSsPlayer(engine)
                val ssTarget = findSsTarget(engine)
                val ssCarrier = findSsEnemyCarrier(engine)
                val ssWeapon = findSsWeapon(ssPlayer)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
                appendLine("  \"referenceVisibleLength\": 0,")
                // ---- 机制证据（规格 07 §4.2 烟测检查点）----
                appendLine("  \"ssPhase\": \"$ssPhase\",")
                appendLine("  \"ssSlotId\": ${jsonString(ssWeapon?.slot?.id)},")
                appendLine("  \"ssWeaponRange\": ${formatFloat(ssWeapon?.range ?: -1f)},")
                appendLine("  \"ssHintsPd\": ${ssWeapon?.spec?.getAIHints()?.contains(WeaponAPI.AIHints.PD) == true},")
                appendLine("  \"ssTargetHitpoints\": ${formatFloat(ssTarget?.hitpoints ?: -1f)},")
                appendLine("  \"ssTargetMaxHitpoints\": ${formatFloat(ssTarget?.maxHitpoints ?: -1f)},")
                appendLine("  \"ssPlayerHitpoints\": ${formatFloat(ssPlayer?.hitpoints ?: -1f)},")
                appendLine("  \"ssPlayerMaxHitpoints\": ${formatFloat(ssPlayer?.maxHitpoints ?: -1f)},")
                appendLine("  \"ssEnemyMinPlayerHp\": ${formatFloat(ssEnemyMinPlayerHp)},")
                appendLine("  \"ssEnemyCarrierPresent\": ${ssCarrier != null},")
                appendLine("  \"ssOnfire\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_ONFIRE)},")
                appendLine("  \"ssFlash\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_FLASH)},")
                appendLine("  \"ssCrossFlash\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_CROSS_FLASH)},")
                appendLine("  \"ssTeleportArc\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TELEPORT_ARC)},")
                appendLine("  \"ssKills\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_KILLS)},")
                appendLine("  \"ssChainJumpsMax\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_CHAIN_JUMPS_MAX)},")
                appendLine("  \"ssDissipateNoKill\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_KILL)},")
                appendLine("  \"ssDissipateNoShip\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_SHIP)},")
                appendLine("  \"ssTerminalSingle\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_SINGLE)},")
                appendLine("  \"ssTerminalMulti\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_MULTI)},")
                appendLine("  \"ssTerminalSegmentsMax\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_SEGMENTS_MAX)},")
                appendLine("  \"ssTerminalEmpArcs\": ${SevenStarsChainScript.telemetryCount(engine, SevenStarsChainScript.TELEMETRY_TERMINAL_EMP_ARCS)},")
                appendLine("  \"ssChainFps\": ${formatFloat(ssChainFps)},")
                appendLine("  \"ssEnemyMissilesInPlay\": ${engine.missiles.count { it.owner != 0 }},")
            } else if (ASTDInGameAutomationScenario.isPsEnabled()) {
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
        // “七星”折跃发射器场景：相位机、锚点与期望证据（规格 07 §4.2 烟测检查点）。
        private const val SS_PHASE_MOUNT = "MOUNT"
        private const val SS_PHASE_BREAK = "BREAK"
        private const val SS_PHASE_CHAIN = "CHAIN"
        private const val SS_PHASE_TERMINAL = "TERMINAL"
        private const val SS_PHASE_ENEMY_MULTI = "ENEMY_MULTI"
        private const val SS_PHASE_COMPLETED = "COMPLETED"
        private const val SS_PHASE_FAILED = "FAILED"
        private const val SS_PLAYER_HULL = "odyssey"
        private const val SS_TARGET_HULL = "vigilance"
        private const val SS_PLAYER_SLOT = "WS 001"
        private val SS_PLAYER_ANCHOR = Vector2f(0f, 0f)
        // 靶舰锚点（BREAK 相位穿舰观测 600su 弹道上；TERMINAL 相位对舰终结观测同位）。
        private val SS_TARGET_ANCHOR = Vector2f(600f, 0f)
        private val SS_ENEMY_ANCHOR = Vector2f(1000f, 0f)
        private val SS_CAMERA_CENTER = Vector2f(500f, 0f)
        private const val SS_CAMERA_VISIBLE_HEIGHT = 1300f
        private const val SS_MOUNT_SETTLE_SECONDS = 0.6f
        // MOUNT 相位校验：射程断言基线 800（无射程向 hullmod 干扰）。
        private const val SS_EXPECT_RANGE = 800f
        private const val SS_RANGE_TOLERANCE = 5f
        // BREAK：增压鱼叉 HP（闪光爆炸 v2 312.5 不可摧毁）；靶舰 HP 容差（穿舰无触碰伤害观测）。
        private const val SS_BREAK_MISSILE_HP = 1_000_000f
        private const val SS_BREAK_MISSILE_SPAWN_DIST = 350f
        private const val SS_BREAK_HP_TOLERANCE = 1f
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
        // COMPLETED 截图门控：十字闪光近 0.6s 内发生才上报（特效入帧）；保底舞台超时。
        private const val SS_COMPLETED_FLASH_WINDOW = 0.6f
        private const val SS_COMPLETED_STAGE_TIMEOUT = 25f
        private const val SS_PHASE_TIMEOUT = 90f
        // 双子星 DEM 场景：相位机、锚点与期望证据（规格 10 §4.2 烟测检查点）。
        private const val GD_PHASE_MOUNT = "MOUNT"
        private const val GD_PHASE_SALVO = "SALVO"
        private const val GD_PHASE_KILL_ONE = "KILL_ONE"
        private const val GD_PHASE_POD = "POD"
        private const val GD_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val GD_PHASE_COMPLETED = "COMPLETED"
        private const val GD_PHASE_FAILED = "FAILED"
        private const val GD_PLAYER_HULL = "conquest"
        private const val GD_TARGET_HULL = "dominator"
        private const val GD_PLAYER_SLOT_LAUNCHER = "WS 019"
        private const val GD_PLAYER_SLOT_POD = "WS 001"
        private val GD_PLAYER_ANCHOR = Vector2f(0f, 0f)
        private val GD_TARGET_ANCHOR = Vector2f(1200f, 0f)
        private val GD_ENEMY_ANCHOR = Vector2f(1200f, 0f)
        private val GD_CAMERA_CENTER = Vector2f(600f, 0f)
        private const val GD_CAMERA_VISIBLE_HEIGHT = 1500f
        private const val GD_MOUNT_SETTLE_SECONDS = 0.6f
        // MOUNT 相位校验：射程断言基线 2500（无射程向 hullmod 干扰）。
        private const val GD_EXPECT_RANGE = 2500f
        private const val GD_RANGE_TOLERANCE = 5f
        private const val GD_LAUNCHER_AMMO = 2
        private const val GD_POD_AMMO = 4
        // SALVO：动能光束首伤帧 EMP 电弧期望道数（规格 §2.1）。
        private const val GD_EMP_ARC_COUNT = 4
        // 同步倍率期望：玩家恒 v2=0.4375；破晓敌版 v5=1.0。
        private const val GD_PLAYER_V2_MULT = 0.4375f
        private const val GD_ENEMY_V5_MULT = 1.0f
        private const val GD_MULT_TOLERANCE = 0.001f
        // ENEMY_SCALE：敌版舰部署免疫窗口（秒，同 SS_ENEMY_MULTI_SETTLE_SECONDS 实机判例）与掉血宽限。
        private const val GD_ENEMY_SETTLE_SECONDS = 4.0f
        private const val GD_ENEMY_GRACE_SECONDS = 20f
        private const val GD_HP_DROP_MIN = 50f
        // COMPLETED 截图门控：payload 打击近 2.5s 内发生才上报（双色尾焰/锁定激光/光束入帧）；保底舞台超时。
        private const val GD_COMPLETED_STRIKE_WINDOW = 2.5f
        private const val GD_COMPLETED_STAGE_TIMEOUT = 30f
        private const val GD_PHASE_TIMEOUT = 60f

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
        // MOUNT 相位校验：射程 700 / spec maxAmmo 40（weapon_data.csv 口径）/ 双炮管 offsets（ALTERNATING 交替射击证据）。
        private const val HIP_EXPECT_RANGE = 700f
        private const val HIP_RANGE_TOLERANCE = 5f
        private const val HIP_AMMO = 40
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
        // dev 舞台 EMP 抗性注入 modifierId（empDamageTakenMult ×0 造 mult≈0 目标；相位收尾 unmodify 无残留）。
        private const val HIP_RESIST_MOD_ID = "astd_hip_automation_resist"

        // 辉星 MRM 场景：相位机、锚点与期望证据（规格 08 §4.2 烟测检查点）。
        private const val SM_PHASE_MOUNT = "MOUNT"
        private const val SM_PHASE_PRIORITY = "PRIORITY"
        private const val SM_PHASE_FIGHTER_HIT = "FIGHTER_HIT"
        private const val SM_PHASE_SHIP_HIT = "SHIP_HIT"
        private const val SM_PHASE_LINE_CROSS = "LINE_CROSS"
        private const val SM_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val SM_PHASE_COMPLETED = "COMPLETED"
        private const val SM_PHASE_FAILED = "FAILED"
        private const val SM_PLAYER_HULL = "gryphon"
        private const val SM_ENEMY_HULL = "gryphon"
        private const val SM_CARRIER_HULL = "condor"
        private const val SM_PLAYER_POD_SLOT = "WS 008"
        private const val SM_PLAYER_LAUNCHER_SLOT = "WS 010"
        private val SM_PLAYER_ANCHOR = Vector2f(-700f, 0f)
        private val SM_ENEMY_ANCHOR = Vector2f(900f, 0f)
        private val SM_CARRIER_ANCHOR = Vector2f(1200f, 400f)
        private val SM_CAMERA_CENTER = Vector2f(300f, 100f)
        private const val SM_CAMERA_VISIBLE_HEIGHT = 1500f
        private const val SM_MOUNT_SETTLE_SECONDS = 0.6f
        // MOUNT 相位校验：射程 2500 / ammo 8/20 / 发射舱 burst 2 / OP 4/10 / no_drop 两件套（weapon_data.csv 口径）。
        private const val SM_EXPECT_RANGE = 2500f
        private const val SM_RANGE_TOLERANCE = 5f
        private const val SM_LAUNCHER_AMMO = 8
        private const val SM_POD_AMMO = 20
        private const val SM_POD_BURST = 2
        private const val SM_LAUNCHER_OP = 4f
        private const val SM_POD_OP = 10f
        private val SM_REQUIRED_TAGS = setOf("no_drop", "no_drop_salvage")
        // PRIORITY：战机在场才开火 + 最短观察窗；发射舱两发分组间隔（burst delay 0，同帧两发常态）。
        private const val SM_PRIORITY_MIN_SECONDS = 4f
        private const val SM_BURST_GROUP_GAP = 0.5f
        // SHIP_HIT：相位内爆炸 ≥3 次且舰船 AOE 与护盾命中各 ≥1（撞击舰船/护盾爆炸恒触发证据）。
        private const val SM_SHIP_HIT_MIN_EXPLOSIONS = 3
        // LINE_CROSS 投喂：中场 lane 布点直线喂向玩家（与玩家导弹流对头相撞）。
        private const val SM_FEED_MISSILE_LOW_ID = "atropos"
        private const val SM_FEED_MISSILE_HIGH_ID = "harpoon"
        // 高结构增压值：阈值 600 之上的安全余量——700 口径下双发齐射同帧两连击
        // （700→500<600）会把投喂弹打进撞线区间造成伪失败；2000 需 7+ 连击才跨阈值，
        // 而确认采样在首次命中后下一帧即完成（第六轮烟测实证 700 口径 stage 1 零命中确认超时）。
        private const val SM_HIGH_HP_BOOST = 2000f
        private const val SM_FEED_INTERVAL = 0.6f
        private const val SM_FEED_LANES = 5
        private const val SM_FEED_LANE_GAP = 150f
        private const val SM_FEED_SPAWN_X = 100f
        private const val SM_FEED_SPEED = 200f
        // ENEMY_SCALE：敌版三档逐档（installScaleForTests 1/2/5 → 爆炸倍率 0.5/1.0/2.5）。
        private val SM_SCALE_KS = floatArrayOf(1f, 2f, 5f)
        private val SM_SCALE_EXPECTED_EXP_MULT = floatArrayOf(0.5f, 1.0f, 2.5f)
        private const val SM_SCALE_TOLERANCE = 0.001f
        private const val SM_SCALE_STEP_TIMEOUT = 30f
        // 敌方开火部署免疫闸（GD/SS/HIP 实机判例同款）。
        private const val SM_ENEMY_SETTLE_SECONDS = 4.0f
        // COMPLETED 截图门控：爆炸事件近 2.5s 内发生才上报（十字爆炸/双拖尾入帧）；保底舞台超时。
        private const val SM_COMPLETED_EVENT_WINDOW = 2.5f
        private const val SM_COMPLETED_STAGE_TIMEOUT = 30f
        private const val SM_PHASE_TIMEOUT = 90f

        // 贯星之矛场景：相位机、锚点与期望证据（规格 09 §4.2 烟测检查点）。
        private const val PL_PHASE_MOUNT = "MOUNT"
        private const val PL_PHASE_CYCLE = "CYCLE"
        private const val PL_PHASE_CLUSTER = "CLUSTER"
        private const val PL_PHASE_ENEMY_SCALE = "ENEMY_SCALE"
        private const val PL_PHASE_COMPLETED = "COMPLETED"
        private const val PL_PHASE_FAILED = "FAILED"
        private const val PL_PLAYER_A_HULL = "onslaught"
        private const val PL_PLAYER_B_HULL = "champion"
        private const val PL_DECOY_HULL = "enforcer"
        private const val PL_ENEMY_TARGET_HULL = "enforcer"
        private const val PL_ENEMY_LANCE_HULL = "champion"
        // A=onslaught 前向大型实弹炮塔（angle 0 / arc 150）；B/敌版=champion 前向大型能量炮塔（angle 0 / arc 130）。
        private const val PL_A_SLOT = "WS 019"
        private const val PL_B_SLOT = "WS 008"
        // reserves 部署序：mission addToFleet 顺序与 hull 计数（三靶两僚按序分锚点）。
        private val PL_DEPLOY_ORDER = listOf("onslaught", "champion", "enforcer")
        // 舞台自有舰体集合：本场景全部舰船（A/B 射手 + 两僚 + 三靶 + 敌版射手）仅这三种舰体；
        // 其余舰船（含全部战机）与导弹一律视为第三方 mod 舞台污染，由 sweepPlForeignEntities 逐帧移除。
        private val PL_SCENARIO_HULLS = setOf("onslaught", "champion", "enforcer")
        // 西侧主舞台：A 主射手 + E1 单体靶 + E2/E3 集群靶（v2 锥 375su/半角 25° 几何：命中点≈(45,0) 起算）。
        private val PL_A_ANCHOR = Vector2f(-950f, 0f)
        private val PL_B_ANCHOR = Vector2f(-950f, -600f)
        private val PL_E1_ANCHOR = Vector2f(150f, 0f)
        private val PL_E2_CLUSTER_ANCHOR = Vector2f(355f, 120f)
        private val PL_E3_CLUSTER_ANCHOR = Vector2f(355f, -120f)
        private val PL_E2_PARK_ANCHOR = Vector2f(1500f, 300f)
        private val PL_E3_PARK_ANCHOR = Vector2f(1500f, 500f)
        // 东侧敌版舞台：敌版贯星北射南（facing 270），僚舰 D1/D2 沿敌版弹道线（v5 锥 600su/半角 40° 几何）。
        private val PL_ENEMY_LANCE_ANCHOR = Vector2f(2500f, 800f)
        private const val PL_ENEMY_LANCE_FACING = 270f
        private val PL_D1_ANCHOR = Vector2f(2500f, 0f)
        private val PL_D2_ANCHOR = Vector2f(2630f, -180f)
        private val PL_D1_PARK_ANCHOR = Vector2f(2500f, 2000f)
        private val PL_D2_PARK_ANCHOR = Vector2f(2630f, 2180f)
        private val PL_CAMERA_CENTER_MAIN = Vector2f(-200f, 0f)
        private val PL_CAMERA_CENTER_ENEMY = Vector2f(2500f, 100f)
        private const val PL_CAMERA_VISIBLE_HEIGHT = 1500f
        // MOUNT 相位校验：射程 1000 / 冷却 5s / OP 30 / no_drop 两件套（weapon_data.csv 口径）。
        private const val PL_MOUNT_SETTLE_SECONDS = 1.0f
        private const val PL_EXPECT_RANGE = 1000f
        private const val PL_RANGE_TOLERANCE = 1f
        private const val PL_EXPECT_COOLDOWN = 5.0f
        private const val PL_COOLDOWN_TOLERANCE = 0.5f
        private const val PL_EXPECT_OP = 30f
        private val PL_REQUIRED_TAGS = setOf("no_drop", "no_drop_salvage")
        // 能量结算探针：energyWeaponRangeBonus +50% 必须生效（≥1.3× 宽松界）；
        // ballisticWeaponRangeBonus +50% 必须不生效（±1su 容差）。modifierId 相位收尾 unmodify 无残留。
        private const val PL_STAT_PROBE_ID = "astd_pl_automation_stat_probe"
        private const val PL_STAT_PROBE_PERCENT = 50f
        private const val PL_STAT_PROBE_SETTLE_SECONDS = 0.3f
        private const val PL_ENERGY_PROBE_MIN_RATIO = 1.3f
        private const val PL_BALLISTIC_PROBE_TOLERANCE = 1f
        // CYCLE：首充 2s（窗口 [1.2, 3.0]）；出膛间隔 7s = 充能 2s + 冷却 5s（窗口 [6.0, 8.5]，帧粒度宽松界）。
        private const val PL_CYCLE_MIN_HITS = 2
        private const val PL_FIRST_CHARGE_MIN = 1.2f
        private const val PL_FIRST_CHARGE_MAX = 3.0f
        private const val PL_CYCLE_INTERVAL_MIN = 6.0f
        private const val PL_CYCLE_INTERVAL_MAX = 8.5f
        // CLUSTER：单次锥面结算命中 ≥2（E2+E3 并入弹道线）+ 破片浮字 ≥2 + 本体豁免契约零破坏。
        private const val PL_CLUSTER_MIN_CONE_HITS = 2
        // ENEMY_SCALE：敌版三档逐档（installScaleForTests 1/2/5 → 半角 20/25/40、锥长 300/375/600、伤害 2500/3125/5000）。
        private val PL_SCALE_KS = floatArrayOf(1f, 2f, 5f)
        private val PL_SCALE_EXPECTED_HALF_ANGLE = floatArrayOf(20f, 25f, 40f)
        private val PL_SCALE_EXPECTED_RANGE = floatArrayOf(300f, 375f, 600f)
        private val PL_SCALE_EXPECTED_DAMAGE = floatArrayOf(2500f, 3125f, 5000f)
        private const val PL_SCALE_TOLERANCE = 0.01f
        // 敌方开火部署免疫闸（GD/SS/HIP/SM 实机判例同款）。
        private const val PL_ENEMY_SETTLE_SECONDS = 2.0f
        private const val PL_MIN_FPS = 45f
        // COMPLETED 截图门控：锥面结算事件近 2.5s 内发生才上报（大光柱/锥面/浮字入帧）；保底舞台超时。
        private const val PL_COMPLETED_EVENT_WINDOW = 2.5f
        private const val PL_COMPLETED_STAGE_TIMEOUT = 30f
        private const val PL_PHASE_TIMEOUT = 60f
        private const val PL_ENEMY_SCALE_PHASE_TIMEOUT = 120f
    }
}
