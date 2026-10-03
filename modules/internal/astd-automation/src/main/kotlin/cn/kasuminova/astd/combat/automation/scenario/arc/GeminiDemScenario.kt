package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemPayloadBeamEffect
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSalvoOnFireEffect
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSyncHandler
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemTrackAI
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.dem.DEMScript
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

/**
 * 双子星 DEM 场景（规格 10 §4.2 烟测检查点）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class GeminiDemScenario : AbstractAutomationScenario() {
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

    // 双弹均命中后的照射期收尾门控：payload 光束 firingTime=1s + EMP 节律宽限 0.3s，
    // 首伤帧即断言会在 EMP 电弧计数尚未累积时误判（实机判例：arcs 终值 9 但首伤帧读数为 0）。
    private var gdBothHitsAt = -1f

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

    override val scenarioId: String = ASTDInGameAutomationScenario.GD_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isGdEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockGdCamera(engine)
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findGdPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.GD_SCENARIO_ID} combat plugin initialized")
    }

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
        lockCameraAt(engine, GD_CAMERA_CENTER, GD_CAMERA_VISIBLE_HEIGHT)
    }

    /**
     * 分相位强制部署 mission reserves（范式同 deploySsReserveShips）：
     * 玩家征服者与统治者靶舰立即部署；敌版征服者待到 ENEMY_SCALE 相位（避免提前携带发射舱开火污染证据）。
     */
    private fun deployGdReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
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
        ctx.log.info("[ASTD-Automation] gd phase $gdPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        gdPhase = next
        gdPhaseStartedAt = ctx.elapsed
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
            player.shipTarget = target ?: carrier
            if (gdPhase != GD_PHASE_ENEMY_SCALE) player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.shield?.toggleOff()
            setGdAutofire(player, false)
            findGdLauncher(player)?.let {
                it.currAngle = 0f
                it.setForceFireOneFrame(fireLauncher)
            }
            findGdPod(player)?.let {
                it.currAngle = 0f
                it.setForceFireOneFrame(firePod)
            }
        }
        if (target != null && !target.isHulk) {
            stabilizeShip(target, GD_TARGET_ANCHOR, 180f, allowFire = false, preserveAI = false)
            target.shipTarget = null
            target.shield?.toggleOff()
            if (gdPhase == GD_PHASE_SALVO || gdPhase == GD_PHASE_KILL_ONE || gdPhase == GD_PHASE_POD) {
                gdSalvoTargetMinHp = minOf(gdSalvoTargetMinHp, target.hitpoints)
            }
        }
        if (carrier != null && !carrier.isHulk) {
            stabilizeShip(carrier, GD_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = false)
            carrier.shipTarget = player
            carrier.hitpoints = carrier.maxHitpoints
            carrier.fluxTracker.currFlux = 0f
            setGdAutofire(carrier, false)
            findGdPod(carrier)?.let {
                it.currAngle = 180f
                // 部署免疫闸（实机判例：reserves 手动 spawn 舰船部署后数秒内脚本 applyDamage 可能全额无效）
                it.setForceFireOneFrame(
                    gdPhase == GD_PHASE_ENEMY_SCALE && ctx.elapsed - gdPhaseStartedAt >= GD_ENEMY_SETTLE_SECONDS,
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
                ctx.log.info("[ASTD-Automation] gd R1 ai reads: ai/missileAI/unwrapped = $triple")
            }
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

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
                if (ctx.elapsed - gdPhaseStartedAt >= GD_MOUNT_SETTLE_SECONDS) {
                    val launcherSlot = launcher?.slot?.id
                    val podSlot = pod?.slot?.id
                    val launcherRange = launcher?.spec?.maxRange ?: -1f
                    val podRange = pod?.spec?.maxRange ?: -1f
                    val hiddenIds = listOf(
                        GeminiDemDifficulty.KINETIC_WEAPON_ID,
                        GeminiDemDifficulty.HE_WEAPON_ID,
                        GeminiDemDifficulty.KINETIC_PAYLOAD_ID,
                        GeminiDemDifficulty.HE_PAYLOAD_ID,
                        // 战机版弹头链（数据驱动 ×0.75 削弱）：四件隐藏 spec 同口径校验
                        GeminiDemDifficulty.KINETIC_FIGHTER_WEAPON_ID,
                        GeminiDemDifficulty.HE_FIGHTER_WEAPON_ID,
                        GeminiDemDifficulty.KINETIC_PAYLOAD_FIGHTER_ID,
                        GeminiDemDifficulty.HE_PAYLOAD_FIGHTER_ID,
                    )
                    val hiddenLeak = hiddenIds.firstOrNull { id ->
                        val spec = Global.getSettings().getWeaponSpec(id)
                        spec == null || !spec.tags.contains("no_drop") || !spec.tags.contains("no_drop_salvage")
                    }
                    val payloadHintLeak = listOf(
                        GeminiDemDifficulty.KINETIC_PAYLOAD_ID, GeminiDemDifficulty.HE_PAYLOAD_ID,
                        GeminiDemDifficulty.KINETIC_PAYLOAD_FIGHTER_ID, GeminiDemDifficulty.HE_PAYLOAD_FIGHTER_ID,
                    )
                        .firstOrNull { id ->
                            Global.getSettings().getWeaponSpec(id)?.aiHints?.contains(WeaponAPI.AIHints.SYSTEM) != true
                        }
                    // 战机型发射武器 spec（射程削弱 2000→1500 的实机核对面）
                    val fighterRange = Global.getSettings().getWeaponSpec(GeminiDemDifficulty.FIGHTER_WEAPON_ID)?.maxRange ?: -1f
                    when {
                        launcherSlot != GD_PLAYER_SLOT_LAUNCHER || podSlot != GD_PLAYER_SLOT_POD -> {
                            ctx.failureReason = "gd mount mismatch: launcherSlot=$launcherSlot podSlot=$podSlot"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        kotlin.math.abs(launcherRange - GD_EXPECT_RANGE) > GD_RANGE_TOLERANCE ||
                                kotlin.math.abs(podRange - GD_EXPECT_RANGE) > GD_RANGE_TOLERANCE -> {
                            ctx.failureReason = "gd range mismatch: launcher=$launcherRange pod=$podRange(expect $GD_EXPECT_RANGE)"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        launcher == null || launcher.spec?.maxAmmo != GD_LAUNCHER_AMMO -> {
                            ctx.failureReason =
                                "gd launcher spec maxAmmo=${launcher?.spec?.maxAmmo} runtime ammo=${launcher?.ammo}, expect spec $GD_LAUNCHER_AMMO（weapon_data.csv 口径）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        pod == null || pod.spec?.maxAmmo != GD_POD_AMMO -> {
                            ctx.failureReason =
                                "gd pod spec maxAmmo=${pod?.spec?.maxAmmo} runtime ammo=${pod?.ammo}, expect spec $GD_POD_AMMO（weapon_data.csv 口径）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        hiddenLeak != null -> {
                            ctx.failureReason = "gd hidden weapon leak: $hiddenLeak 缺 no_drop 系 tags（codex/掉落泄漏防线）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        payloadHintLeak != null -> {
                            ctx.failureReason = "gd payload hint leak: $payloadHintLeak 缺 SYSTEM hint"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        kotlin.math.abs(fighterRange - GD_FIGHTER_EXPECT_RANGE) > GD_RANGE_TOLERANCE -> {
                            ctx.failureReason = "gd fighter range=$fighterRange, expect $GD_FIGHTER_EXPECT_RANGE（战机型射程削弱 2500→2000）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        else -> {
                            gdLauncherAmmoBaseline = launcher.ammo
                            val ammoBonus = player?.mutableStats?.missileAmmoBonus
                            ctx.log.info(
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
                if (warheads >= 2) {
                    // burst=2 第二发在首发 +0.1s 落账：采样须持续刷新到断言时刻，
                    // 否则首帧快照只记到引擎第一次扣弹（实机判例：快照 7、实际终值 6）
                    gdLauncherAmmoAfterSalvo = launcher?.ammo ?: -1
                }
                if (kineticHits >= 1 && heHits >= 1) {
                    if (gdBothHitsAt < 0) {
                        gdBothHitsAt = ctx.elapsed
                        ctx.log.info("[ASTD-Automation] gd both hits at ${"%.2f".format(ctx.elapsed)}s，照射期收尾门控 ${GD_HIT_DWELL_SECONDS}s 后断言")
                    }
                }
                if (kineticHits >= 1 && heHits >= 1 && ctx.elapsed - gdBothHitsAt >= GD_HIT_DWELL_SECONDS) {
                    when {
                        salvoCount < 1 || warheads != salvoCount * 2 -> {
                            ctx.failureReason = "gd salvo mismatch: salvo=$salvoCount warheads=$warheads（每轮齐射恰两枚弹头）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        gdTrackAiCreated < 2 || gdTrackAiTargetNonNull < 2 -> {
                            ctx.failureReason =
                                "gd R1 fail: TrackAI 装配=$gdTrackAiCreated 目标非空=$gdTrackAiTargetNonNull（应各 ≥2，DEMScript WAIT 段触发前提的供给侧证据）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        empArcs !in GD_EMP_ARC_MIN..GD_EMP_ARC_MAX -> {
                            ctx.failureReason =
                                "gd emp arcs=$empArcs, expect $GD_EMP_ARC_MIN..$GD_EMP_ARC_MAX（动能光束每轮打击预算 5 道 EMP 电弧，0.2s 节律）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        syncTriggers < 1 -> {
                            ctx.failureReason = "gd sync=0（双弹同目标 Δt≤1s 应触发同步共振）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        kotlin.math.abs(lastMult - GD_PLAYER_V2_MULT) > GD_MULT_TOLERANCE -> {
                            ctx.failureReason = "gd sync mult=$lastMult, expect ${GD_PLAYER_V2_MULT}（玩家来源恒 v2）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        gdLauncherAmmoAfterSalvo != gdLauncherAmmoBaseline - GD_AMMO_PER_SALVO -> {
                            ctx.failureReason =
                                "gd launcher ammo after salvo=$gdLauncherAmmoAfterSalvo, expect ${gdLauncherAmmoBaseline - GD_AMMO_PER_SALVO}（基线 $gdLauncherAmmoBaseline，一次触发一轮齐射耗 $GD_AMMO_PER_SALVO 弹药）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] gd salvo evidence: targetHp ${gdSalvoTargetHpBaseline}→min ${gdSalvoTargetMinHp} " +
                                        "（R2 读数：payload+sync 落船体；beamDamage 面板见 payload 首伤帧日志）",
                            )
                            // DEMScript 接管硬证据 = payload 光束命中本身（payload 只能由 DEMScript 打击段结算，
                            // 规格 §0.1 事实 #7）；包装弹头读回观测面（gdDemTakeoverSeen）只作诊断。
                            if (gdDemTakeoverSeen.isEmpty()) {
                                ctx.log.info("[ASTD-Automation] gd R1 note: 包装弹头读回未见 DEMScript（payload 命中已为接管硬证据）")
                            }
                            gdKillSyncBaseline = syncTriggers
                            gdKillKineticBaseline = kineticHits
                            gdKillHeBaseline = heHits
                            gdKillWarheadsBaseline = warheads
                            gdKillHeRemoved = false
                            target?.hitpoints = target.maxHitpoints
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
                            ctx.log.info("[ASTD-Automation] gd kill_one: 高爆弹头已被移除（击落一枚模拟）")
                        }
                    }
                }
                if (gdKillHeRemoved && kineticHits - gdKillKineticBaseline >= 1) {
                    when {
                        syncTriggers != gdKillSyncBaseline -> {
                            ctx.failureReason = "gd kill_one sync delta=${syncTriggers - gdKillSyncBaseline}, expect 0（击落一枚，同步冲击即告落空）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        heHits != gdKillHeBaseline -> {
                            ctx.failureReason = "gd kill_one he hits delta=${heHits - gdKillHeBaseline}, expect 0（高爆弹头已移除不得命中）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        else -> {
                            gdPodSalvoBaseline = salvoCount
                            gdPodKineticBaseline = kineticHits
                            gdPodHeBaseline = heHits
                            gdPodSyncBaseline = syncTriggers
                            gdPodAmmoBaseline = pod?.ammo ?: -1
                            gdPodAmmoAfterSalvo = -1
                            target?.hitpoints = target.maxHitpoints
                            transitionGdPhase(GD_PHASE_POD)
                        }
                    }
                }
            }

            GD_PHASE_POD -> {
                stabilizeGdShips(engine, fireLauncher = false, firePod = true)
                if (salvoCount - gdPodSalvoBaseline >= 1) {
                    // 同 SALVO 相位判例：burst 第二发 +0.1s 落账，采样持续刷新到断言时刻
                    gdPodAmmoAfterSalvo = pod?.ammo ?: -1
                }
                if (kineticHits - gdPodKineticBaseline >= 1 && heHits - gdPodHeBaseline >= 1) {
                    when {
                        gdPodAmmoAfterSalvo != gdPodAmmoBaseline - GD_AMMO_PER_SALVO -> {
                            ctx.failureReason =
                                "gd pod ammo after salvo=$gdPodAmmoAfterSalvo, expect ${gdPodAmmoBaseline - GD_AMMO_PER_SALVO}（基线 $gdPodAmmoBaseline，发射舱一轮一耗 $GD_AMMO_PER_SALVO 弹药）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        syncTriggers - gdPodSyncBaseline < 1 -> {
                            ctx.failureReason = "gd pod sync delta=${syncTriggers - gdPodSyncBaseline} < 1（发射舱双弹同目标应触发同步）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        else -> {
                            gdEnemySyncBaseline = syncTriggers
                            gdEnemyMinPlayerHp = player?.maxHitpoints ?: Float.MAX_VALUE
                            gdEnemyFirstSyncAt = -1f
                            DifficultyTuningImpl.installScaleForTests(5f)
                            target?.hitpoints = target.maxHitpoints
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
                    gdEnemyFirstSyncAt = ctx.elapsed
                }
                if (gdEnemyFirstSyncAt >= 0f) {
                    when {
                        kotlin.math.abs(lastMult - GD_ENEMY_V5_MULT) > GD_MULT_TOLERANCE -> {
                            ctx.failureReason = "gd enemy sync mult=$lastMult, expect $GD_ENEMY_V5_MULT（破晓敌版走轨一）"
                            transitionGdPhase(GD_PHASE_FAILED)
                        }

                        player != null && gdEnemyMinPlayerHp < player.maxHitpoints - GD_HP_DROP_MIN -> {
                            DifficultyTuningImpl.installScaleForTests(null)
                            findGdEnemyCarrier(engine)?.let { engine.removeEntity(it) }
                            player.hitpoints = player.maxHitpoints
                            target?.hitpoints = target.maxHitpoints
                            transitionGdPhase(GD_PHASE_COMPLETED)
                        }
                        // 部署免疫宽限（实机判例同 SS：source 为敌版舰时脚本伤害在部署后数秒内可能全额无效）
                        ctx.elapsed - gdEnemyFirstSyncAt > GD_ENEMY_GRACE_SECONDS -> {
                            ctx.failureReason = "gd enemy sync player minHp=$gdEnemyMinPlayerHp（敌版同步应命中玩家舰掉血）"
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
            gdLastStrikeAt = ctx.elapsed
        }

        val state = when {
            player == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "gd player ship missing"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            gdPhase == GD_PHASE_FAILED -> "Failed"
            gdPhase != GD_PHASE_COMPLETED &&
                    ctx.elapsed - gdPhaseStartedAt > GD_PHASE_TIMEOUT -> {
                ctx.failureReason = "gd phase timeout: $gdPhase"
                "Failed"
            }

            gdPhase == GD_PHASE_COMPLETED -> {
                val recentStrike = gdLastStrikeAt >= 0f && ctx.elapsed - gdLastStrikeAt <= GD_COMPLETED_STRIKE_WINDOW
                if (recentStrike || ctx.elapsed - gdPhaseStartedAt >= GD_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: gemini_dem_basic salvo/dem-takeover/payload/sync/kill-one/pod/enemy-scale evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, launcher)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val gdPlayer = findGdPlayer(engine)
        val gdTarget = findGdTarget(engine)
        val gdLauncher = findGdLauncher(gdPlayer)
        val gdPod = findGdPod(gdPlayer)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 10 §4.2 烟测检查点）----
        json.appendLine("  \"gdPhase\": \"$gdPhase\",")
        json.appendLine("  \"gdLauncherSlotId\": ${jsonString(gdLauncher?.slot?.id)},")
        json.appendLine("  \"gdPodSlotId\": ${jsonString(gdPod?.slot?.id)},")
        json.appendLine("  \"gdLauncherAmmo\": ${gdLauncher?.ammo ?: -1},")
        json.appendLine("  \"gdPodAmmo\": ${gdPod?.ammo ?: -1},")
        json.appendLine("  \"gdTargetHitpoints\": ${formatFloat(gdTarget?.hitpoints ?: -1f)},")
        json.appendLine("  \"gdTargetMaxHitpoints\": ${formatFloat(gdTarget?.maxHitpoints ?: -1f)},")
        json.appendLine("  \"gdSalvoTargetMinHp\": ${formatFloat(gdSalvoTargetMinHp)},")
        json.appendLine("  \"gdSalvo\": ${GeminiDemSalvoOnFireEffect.salvoCount(engine)},")
        json.appendLine("  \"gdWarheadsSpawned\": ${GeminiDemSalvoOnFireEffect.warheadsSpawned(engine)},")
        json.appendLine("  \"gdTrackAiSeen\": ${gdTrackAiSeen.size},")
        json.appendLine("  \"gdTrackTargetNonNull\": $gdTrackTargetNonNull,")
        json.appendLine("  \"gdDemTakeoverSeen\": ${gdDemTakeoverSeen.size},")
        json.appendLine("  \"gdKineticHits\": ${GeminiDemPayloadBeamEffect.kineticHitCount(engine)},")
        json.appendLine("  \"gdHeHits\": ${GeminiDemPayloadBeamEffect.heHitCount(engine)},")
        json.appendLine("  \"gdEmpArcs\": ${GeminiDemPayloadBeamEffect.empArcCount(engine)},")
        json.appendLine("  \"gdSyncTriggers\": ${GeminiDemSyncHandler.syncTriggerCount(engine)},")
        json.appendLine("  \"gdSyncLastMult\": ${formatFloat(engine.customData[GeminiDemSyncHandler.TELEMETRY_SYNC_LAST_MULT] as? Float ?: -1f)},")
        json.appendLine("  \"gdHitRegistered\": ${GeminiDemSyncHandler.hitRegisteredCount(engine)},")
        json.appendLine("  \"gdEnemyMinPlayerHp\": ${formatFloat(gdEnemyMinPlayerHp)},")
        json.appendLine("  \"gdWarheadsInPlay\": ${engine.missiles.count { it.customData[GeminiDemDifficulty.SALVO_KEY] != null }},")
    }

    private companion object {
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

        // MOUNT 相位校验：射程断言基线 2000（无射程向 hullmod 干扰）；战机型 spec 射程 1500（削弱口径）。
        private const val GD_EXPECT_RANGE = 2000f
        private const val GD_FIGHTER_EXPECT_RANGE = 1500f
        private const val GD_RANGE_TOLERANCE = 5f
        private const val GD_LAUNCHER_AMMO = 4
        private const val GD_POD_AMMO = 8

        // 一轮齐射弹药消耗（实机判例 2026-09-26：双管 ALTERNATING 挂点单发 onFire 仅一次但引擎按双管各耗 1，
        // 一轮齐射稳定 -2；语义对齐「一轮齐射两枚弹头」）。
        private const val GD_AMMO_PER_SALVO = 2

        // SALVO：动能光束每轮打击 EMP 电弧预算恰 5 道（0.2s 节律固定预算，与照射时长解耦，规格 §2.1）。
        private const val GD_EMP_ARC_MIN = 5
        private const val GD_EMP_ARC_MAX = 5

        // 双弹均命中后到断言的照射期收尾门控（秒）= payload firingTime 1s + EMP 宽限 0.3s + 帧余量。
        private const val GD_HIT_DWELL_SECONDS = 1.6f

        // 同步增伤乘区期望：玩家恒 v2=1+1.0=2.0；破晓敌版 v5=1+2.5=3.5。
        private const val GD_PLAYER_V2_MULT = 2.0f
        private const val GD_ENEMY_V5_MULT = 3.5f
        private const val GD_MULT_TOLERANCE = 0.001f

        // ENEMY_SCALE：敌版舰部署免疫窗口（秒，同 SS_ENEMY_MULTI_SETTLE_SECONDS 实机判例）与掉血宽限。
        private const val GD_ENEMY_SETTLE_SECONDS = 4.0f
        private const val GD_ENEMY_GRACE_SECONDS = 20f
        private const val GD_HP_DROP_MIN = 50f

        // COMPLETED 截图门控：payload 打击近 2.5s 内发生才上报（双色尾焰/锁定激光/光束入帧）；保底舞台超时。
        private const val GD_COMPLETED_STRIKE_WINDOW = 2.5f
        private const val GD_COMPLETED_STAGE_TIMEOUT = 30f
        private const val GD_PHASE_TIMEOUT = 60f
    }
}
