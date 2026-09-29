package cn.kasuminova.astd.combat.automation.base

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.combat.CombatState
import org.lwjgl.util.vector.Vector2f

/**
 * 相位绕后验证矩阵场景基类（舜华/密蒙巡洋/茑萝航母满装配 AI 对抗，锤头为高速对照组）。
 *
 * 四场景共用一套相位机与证据口径，子类只提供舰队构成（[playerHullId]/[enemyHullId]）、
 * 判定模式（[mode]）与启用判定；判据按模式分支：FLANK 判绕后证据（观测窗内下潜 ≥1 且
 * 扫描幅度/上浮方位差达标），HEALTH 判航母相位节奏健康（下潜/上浮各 ≥2，航母走位天然
 * 绕行，验证不憋死不卡潜而非绕后幅度），CONTROL_NO_FLANK 判高速目标全程零绕后旗标帧
 * （且至少下潜一次证明相位 AI 在运行）。
 *
 * 移植自 ai-opt 分支单体插件 advancePfScenario（fb2357f/0d83a38），相位机/断言/证据写出口径原样迁移。
 */
abstract class AbstractPhaseFlankScenario : AbstractAutomationScenario() {

    /** 判定模式：FLANK 绕后证据 / HEALTH 航母相位节奏健康 / CONTROL_NO_FLANK 高速目标零绕后旗标 / SURVIVAL 贴盾上浮与非相位硬吃高威胁投射物零容忍。 */
    enum class PfMode { FLANK, HEALTH, CONTROL_NO_FLANK, SURVIVAL }

    /** 受测玩家舰船体 id（舰队构成由各自 MissionDefinition 决定）。 */
    abstract val playerHullId: String

    /** 对抗目标舰体 id。 */
    abstract val enemyHullId: String

    /** 本场景的判定模式。 */
    abstract val mode: PfMode

    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    // 观测面：相位状态边沿——下潜计数、相位中绕敌舰的方位角扫描幅度（去卷绕累计）、
    // 每次上浮时「敌舰艏向 vs 敌→己方位角」差值。绕后意图生效时扫描幅度/上浮方位差显著。
    private var pfPhase = PF_PHASE_OBSERVE
    private var pfDiveCount = 0
    private var pfSurfaceCount = 0
    private var pfPhaseSweepMax = 0f
    private var pfSurfaceBearingDiffMax = 0f
    private var pfWasPhased = false
    private var pfSweepLastBearing = 0f
    private var pfSweepUnwrapped = 0f
    private var pfSweepMin = 0f
    private var pfSweepMax = 0f
    private var pfCombatStartAt = -1f

    // 走位驱动探针：相位帧数 / 其中 PHASE_ATTACK_RUN 旗标在场的帧数 / 相位中最大航速——
    // 区分「旗标没挂上」与「旗标挂了但走位模块没响应」两类失效。
    private var pfPhasedFrames = 0
    private var pfAttackRunFrames = 0
    private var pfPhasedSpeedMax = 0f

    // 生存判据探针：贴盾上浮计数与上浮最近间距 / 高威胁投射物命中跟踪（弹体消失帧
    // 按最后位置与本舰碰撞圈判命中，区分相位/非相位——相位中不可被命中，命中即硬吃）。
    private var pfSurfaceHugCount = 0
    private var pfSurfaceClosestDist = Float.MAX_VALUE
    private val pfTorpedoActive = HashMap<DamagingProjectileAPI, Vector2f>()
    private var pfTorpedoHits = 0
    private var pfTorpedoHitsUnphased = 0

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        // 玩家后备恰 1 艘时通用块保留 vanilla 静默 deployAll（单舰场景范式）——本场景需要
        // 玩家舰留在 reserves 由 deployPfReserveShips 锚点入场，关断闸门把静默部署一并跳过
        (engine.combatUI as? CombatState)?.setShowDeploymentDialogOnStart(false)
        lockPfCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findPfPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=$scenarioId combat plugin initialized")
    }

    private fun findPfPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == playerHullId && !ship.isFighter }

    private fun findPfEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == enemyHullId && !ship.isFighter }

    /** 强制部署 mission reserves（范式同 deployGsrReserveShips）；两舰锚点相对入场后 AI 自由对抗。 */
    private fun deployPfReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == playerHullId -> PF_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == enemyHullId -> PF_ENEMY_ANCHOR
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

    private fun lockPfCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, PF_CAMERA_CENTER, PF_CAMERA_VISIBLE_HEIGHT)
    }

    /** 相位边沿统计：相位中逐帧累计绕敌方位角的去卷绕扫描幅度，上浮沿记录相对敌艏方位差。 */
    private fun trackPfPhaseEdges(player: ShipAPI, enemy: ShipAPI) {
        val bearing = Math.toDegrees(
            kotlin.math.atan2(
                (player.location.y - enemy.location.y).toDouble(),
                (player.location.x - enemy.location.x).toDouble(),
            )
        ).toFloat()
        val phased = player.isPhased
        if (phased && !pfWasPhased) {
            // 下潜沿：重置本次相位的扫描累计
            pfDiveCount++
            pfSweepLastBearing = bearing
            pfSweepUnwrapped = 0f
            pfSweepMin = 0f
            pfSweepMax = 0f
        } else if (phased) {
            // 去卷绕累计：相邻帧取最小角差，避免 ±180° 跳变污染幅度
            pfSweepUnwrapped += Misc.getAngleDiff(bearing, pfSweepLastBearing)
            pfSweepLastBearing = bearing
            pfSweepMin = minOf(pfSweepMin, pfSweepUnwrapped)
            pfSweepMax = maxOf(pfSweepMax, pfSweepUnwrapped)
            pfPhaseSweepMax = maxOf(pfPhaseSweepMax, pfSweepMax - pfSweepMin)
        } else if (pfWasPhased) {
            // 上浮沿：敌舰艏向与敌→己方位角的差（180° = 正后方）
            pfSurfaceCount++
            val bearingDiff = Math.abs(Misc.getAngleDiff(enemy.facing, bearing))
            pfSurfaceBearingDiffMax = maxOf(pfSurfaceBearingDiffMax, bearingDiff)
            // 贴盾上浮探针：落点间距低于（本舰碰撞半径 + 敌舰有效半径）× [PF_SURFACE_HUG_FRAC]——
            // 敌舰护盾开启时有效半径取护盾半径（贴护盾外缘同样贴脸，对齐 AI 侧口径）
            val enemyShield = enemy.shield
            val enemyEffRadius =
                if (enemyShield != null && enemyShield.isOn) {
                    maxOf(enemy.collisionRadius, enemyShield.radius)
                } else {
                    enemy.collisionRadius
                }
            val sepDist = Misc.getDistance(player.location, enemy.location)
            pfSurfaceClosestDist = minOf(pfSurfaceClosestDist, sepDist)
            if (sepDist < (player.collisionRadius + enemyEffRadius) * PF_SURFACE_HUG_FRAC) {
                pfSurfaceHugCount++
                ctx.log.info(
                    "[ASTD-Automation] pf surface#$pfSurfaceCount 贴盾上浮：dist=${"%.0f".format(sepDist)}su " +
                            "< 贴脸线 ${"%.0f".format((player.collisionRadius + enemyEffRadius) * PF_SURFACE_HUG_FRAC)}su",
                )
            }
            ctx.log.info(
                "[ASTD-Automation] pf surface#$pfSurfaceCount: 本次相位扫描幅度=${"%.1f".format(pfSweepMax - pfSweepMin)}° " +
                        "上浮方位差=${"%.1f".format(bearingDiff)}°（历史峰值 sweep=${"%.1f".format(pfPhaseSweepMax)}° " +
                        "diff=${"%.1f".format(pfSurfaceBearingDiffMax)}°）",
            )
        }
        pfWasPhased = phased
    }

    /**
     * 高威胁投射物命中跟踪（SURVIVAL 判据输入）：敌方伤害 ≥ [PF_TORPEDO_DAMAGE_MIN] 的弹体
     * 逐帧登记，消失帧按最后位置是否落在本舰碰撞圈 + [PF_TORPEDO_HIT_MARGIN] 内判命中；
     * 相位中不可被命中，凡命中即非相位硬吃（命中帧 isPhased 记入遥测佐证）。
     */
    private fun trackTorpedoHits(ctx: AutomationCombatContext, player: ShipAPI) {
        val engine = ctx.engine
        val seen = HashSet<DamagingProjectileAPI>()
        for (projectiles in listOf(engine.missiles, engine.projectiles)) {
            for (proj in projectiles) {
                if (proj.owner == player.owner || proj.isFading || proj.isExpired) continue
                if (proj.damageAmount < PF_TORPEDO_DAMAGE_MIN) continue
                seen.add(proj)
                val last = pfTorpedoActive[proj]
                if (last == null) {
                    pfTorpedoActive[proj] = Vector2f(proj.location)
                } else {
                    last.set(proj.location)
                }
            }
        }
        val iter = pfTorpedoActive.entries.iterator()
        while (iter.hasNext()) {
            val (proj, lastLoc) = iter.next()
            if (seen.contains(proj)) continue
            iter.remove()
            val dx = player.location.x - lastLoc.x
            val dy = player.location.y - lastLoc.y
            val hitR = player.collisionRadius + PF_TORPEDO_HIT_MARGIN
            if (dx * dx + dy * dy > hitR * hitR) continue
            pfTorpedoHits++
            if (!player.isPhased) {
                pfTorpedoHitsUnphased++
                ctx.log.info(
                    "[ASTD-Automation] pf 硬吃高威胁投射物 #$pfTorpedoHitsUnphased：" +
                            "spec=${proj.projectileSpecId} dmg=${"%.0f".format(proj.damageAmount)} " +
                            "落点距本舰 ${"%.0f".format(Misc.getDistance(lastLoc, player.location))}su",
                )
            }
        }
    }

    /**
     * 相位对抗观测：不钉位、不清辐能、不动舰 AI——两舰满装配 AI 自由对抗，
     * 逐帧回满双方舰体（保住对抗时长；辐能保留，相位 AI 的辐能压力链路输入保持真实）。
     */
    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployPfReserveShips(engine)
        lockPfCamera(engine)

        val player = findPfPlayer(engine)
        val enemy = findPfEnemy(engine)
        if (player != null && !player.isHulk) player.hitpoints = player.maxHitpoints
        if (enemy != null && !enemy.isHulk) enemy.hitpoints = enemy.maxHitpoints
        // 玩家舰身份照 GRG 范式赋予（不锁操控，AI 自由对抗）：CombatState.setPlayerShip
        // 会把舰 AI 收进 prevAI 并置空（玩家接管口径），系统 AI 挂在舰 AI 上会随之停转，
        // 必须补建默认舰 AI（config 必须空实例：传 null 会让 BasicShipAI.pickManeuver NPE）
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            if (player.shipAI == null) {
                player.shipAI = Global.getSettings().createDefaultShipAI(player, ShipAIConfig())
            }
        }

        if (player != null && enemy != null && pfPhase == PF_PHASE_OBSERVE) {
            if (pfCombatStartAt < 0f) {
                pfCombatStartAt = ctx.elapsed
                ctx.log.info(
                    "[ASTD-Automation] pf combat start: $playerHullId vs $enemyHullId " +
                            "AI 对抗观测窗开启（mode=$mode）",
                )
            }
            // 走位驱动探针采样（逐帧，不走扫描节拍）
            if (player.isPhased) {
                pfPhasedFrames++
                if (player.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN)) pfAttackRunFrames++
                pfPhasedSpeedMax = maxOf(pfPhasedSpeedMax, player.velocity.length())
            }
            trackPfPhaseEdges(player, enemy)
            trackTorpedoHits(ctx, player)
            val combatSeconds = ctx.elapsed - pfCombatStartAt
            when (mode) {
                PfMode.FLANK -> {
                    if (pfDiveCount >= 1 &&
                        (pfPhaseSweepMax >= PF_SWEEP_MIN_DEG || pfSurfaceBearingDiffMax >= PF_SURFACE_BEARING_MIN_DEG)
                    ) {
                        pfPhase = PF_PHASE_COMPLETED
                        ctx.log.info(
                            "[ASTD-Automation] pf flank evidence: dives=$pfDiveCount surfaces=$pfSurfaceCount " +
                                    "sweepMax=${"%.1f".format(pfPhaseSweepMax)}° " +
                                    "surfaceBearingDiffMax=${"%.1f".format(pfSurfaceBearingDiffMax)}° " +
                                    "at ${"%.1f".format(combatSeconds)}s",
                        )
                    } else if (combatSeconds >= PF_OBSERVE_TIMEOUT) {
                        ctx.failureReason = "pf observe timeout: ${PF_OBSERVE_TIMEOUT.toInt()}s 内绕后证据不足" +
                                "（dives=$pfDiveCount surfaces=$pfSurfaceCount " +
                                "sweepMax=${"%.1f".format(pfPhaseSweepMax)}° < $PF_SWEEP_MIN_DEG° 且 " +
                                "surfaceBearingDiffMax=${"%.1f".format(pfSurfaceBearingDiffMax)}° < $PF_SURFACE_BEARING_MIN_DEG°）"
                        pfPhase = PF_PHASE_FAILED
                    }
                }

                PfMode.HEALTH -> {
                    if (pfDiveCount >= 2 && pfSurfaceCount >= 2) {
                        pfPhase = PF_PHASE_COMPLETED
                        ctx.log.info(
                            "[ASTD-Automation] pf health evidence: dives=$pfDiveCount surfaces=$pfSurfaceCount " +
                                    "at ${"%.1f".format(combatSeconds)}s",
                        )
                    } else if (combatSeconds >= PF_OBSERVE_TIMEOUT) {
                        ctx.failureReason = "pf health timeout: ${PF_OBSERVE_TIMEOUT.toInt()}s 内相位节奏不达标" +
                                "（dives=$pfDiveCount surfaces=$pfSurfaceCount，要求各 ≥2）"
                        pfPhase = PF_PHASE_FAILED
                    }
                }

                PfMode.CONTROL_NO_FLANK -> {
                    if (pfAttackRunFrames > 0) {
                        ctx.failureReason = "pf control violated: 对照组出现绕后旗标帧（attackRunFrames=$pfAttackRunFrames）"
                        pfPhase = PF_PHASE_FAILED
                    } else if (combatSeconds >= PF_CONTROL_WINDOW_SEC) {
                        if (pfDiveCount >= 1) {
                            pfPhase = PF_PHASE_COMPLETED
                            ctx.log.info(
                                "[ASTD-Automation] pf control clean: dives=$pfDiveCount attackRunFrames=0 " +
                                        "at ${"%.1f".format(combatSeconds)}s",
                            )
                        } else {
                            ctx.failureReason = "pf control no dive: ${PF_CONTROL_WINDOW_SEC.toInt()}s 内未下潜，相位 AI 未运行"
                            pfPhase = PF_PHASE_FAILED
                        }
                    }
                }

                PfMode.SURVIVAL -> {
                    // 违规即败：贴盾上浮或非相位硬吃高威胁投射物（用户实机两症状的判据化）；
                    // 生存窗内相位节奏健康（下潜 ≥2）且零违规即 Completed
                    val violation = when {
                        pfSurfaceHugCount > 0 ->
                            "贴盾上浮 $pfSurfaceHugCount 次（上浮最近间距 ${"%.0f".format(pfSurfaceClosestDist)}su）"

                        pfTorpedoHitsUnphased > 0 ->
                            "非相位硬吃高威胁投射物 $pfTorpedoHitsUnphased 次（总命中 $pfTorpedoHits）"

                        else -> null
                    }
                    if (violation != null) {
                        ctx.failureReason = "pf survival violated: $violation"
                        pfPhase = PF_PHASE_FAILED
                    } else if (combatSeconds >= PF_SURVIVAL_WINDOW_SEC && pfDiveCount >= 2) {
                        pfPhase = PF_PHASE_COMPLETED
                        ctx.log.info(
                            "[ASTD-Automation] pf survival clean: dives=$pfDiveCount surfaces=$pfSurfaceCount " +
                                    "hugs=0 torpedoHits=$pfTorpedoHits（相位躲过） " +
                                    "at ${"%.1f".format(combatSeconds)}s",
                        )
                    } else if (combatSeconds >= PF_OBSERVE_TIMEOUT) {
                        ctx.failureReason = "pf survival timeout: ${PF_OBSERVE_TIMEOUT.toInt()}s 内相位节奏不足" +
                                "（dives=$pfDiveCount < 2 且未达生存窗）"
                        pfPhase = PF_PHASE_FAILED
                    }
                }
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "pf ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            pfPhase == PF_PHASE_FAILED -> "Failed"
            pfPhase == PF_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: $scenarioId")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：相位对抗舞台在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findPfPlayer(ctx.engine) }) { lockPfCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val pfPlayer = findPfPlayer(ctx.engine)
        val pfCloak = pfPlayer?.phaseCloak
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（相位 AI：判定模式 / 下潜计数 / 相位扫描幅度 / 上浮方位差 / 走位驱动探针）----
        json.appendLine("  \"pfPhase\": \"$pfPhase\",")
        json.appendLine("  \"pfMode\": \"$mode\",")
        json.appendLine("  \"pfSystemId\": ${jsonString(pfCloak?.id)},")
        json.appendLine("  \"pfPlayerPhased\": ${pfPlayer?.isPhased == true},")
        json.appendLine("  \"pfDiveCount\": $pfDiveCount,")
        json.appendLine("  \"pfSurfaceCount\": $pfSurfaceCount,")
        json.appendLine("  \"pfPhaseSweepMax\": ${formatFloat(pfPhaseSweepMax)},")
        json.appendLine("  \"pfSurfaceBearingDiffMax\": ${formatFloat(pfSurfaceBearingDiffMax)},")
        json.appendLine("  \"pfPhasedFrames\": $pfPhasedFrames,")
        json.appendLine("  \"pfAttackRunFrames\": $pfAttackRunFrames,")
        json.appendLine("  \"pfPhasedSpeedMax\": ${formatFloat(pfPhasedSpeedMax)},")
        json.appendLine("  \"pfCombatSeconds\": ${formatFloat(if (pfCombatStartAt < 0f) 0f else ctx.elapsed - pfCombatStartAt)},")
        json.appendLine("  \"pfPlayerCurrFlux\": ${formatFloat(pfPlayer?.fluxTracker?.currFlux ?: -1f)},")
        // ---- 生存判据探针（贴盾上浮 / 高威胁投射物命中）----
        json.appendLine("  \"pfSurfaceHugCount\": $pfSurfaceHugCount,")
        json.appendLine("  \"pfSurfaceClosestDist\": ${formatFloat(if (pfSurfaceClosestDist == Float.MAX_VALUE) -1f else pfSurfaceClosestDist)},")
        json.appendLine("  \"pfTorpedoHits\": $pfTorpedoHits,")
        json.appendLine("  \"pfTorpedoHitsUnphased\": $pfTorpedoHitsUnphased,")
    }

    private companion object {
        // 相位对抗场景：两舰满装配 AI 对抗的锚点/相机与成功判据。
        private const val PF_PHASE_OBSERVE = "OBSERVE"
        private const val PF_PHASE_COMPLETED = "COMPLETED"
        private const val PF_PHASE_FAILED = "FAILED"
        private val PF_PLAYER_ANCHOR = Vector2f(-1800f, 0f)
        private val PF_ENEMY_ANCHOR = Vector2f(1800f, 0f)
        private val PF_CAMERA_CENTER = Vector2f(0f, 0f)
        private const val PF_CAMERA_VISIBLE_HEIGHT = 4500f

        // FLANK 成功判据（达其一即证明绕后走位生效）：相位中绕敌方位角扫描幅度 / 上浮时相对敌艏方位差。
        // 修复前原版走位旗标 PHASE_ATTACK_RUN 无人管理，舜华下潜后原地罚站，两值都趋近 0。
        private const val PF_SWEEP_MIN_DEG = 60f
        private const val PF_SURFACE_BEARING_MIN_DEG = 100f
        private const val PF_OBSERVE_TIMEOUT = 90f

        // 高速对照组观测窗：60s 内不得出现绕后旗标帧，且至少下潜一次证明相位 AI 在运行。
        private const val PF_CONTROL_WINDOW_SEC = 60f

        // SURVIVAL 判据：生存窗 60s（窗内下潜 ≥2 且零违规即 Completed，违规即 Failed）。
        private const val PF_SURVIVAL_WINDOW_SEC = 60f

        // 贴盾上浮判线：上浮落点间距 <（本舰碰撞半径 + 敌舰有效半径）× 本值（对齐 AI 侧 SURFACE_PROXIMITY_FRAC 口径）。
        private const val PF_SURFACE_HUG_FRAC = 0.9f

        // 高威胁投射物口径：单发伤害 ≥ 本值才跟踪（鱼雷级；滤掉点防/机关炮流弹）。
        private const val PF_TORPEDO_DAMAGE_MIN = 1000f

        // 命中判定余量：弹体消失帧最后位置距本舰中心 ≤ 碰撞半径 + 本值判命中（覆盖爆炸核心半径）。
        private const val PF_TORPEDO_HIT_MARGIN = 100f
    }
}
