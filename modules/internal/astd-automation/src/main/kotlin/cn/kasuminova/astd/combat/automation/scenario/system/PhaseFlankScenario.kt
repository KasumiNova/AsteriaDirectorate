package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.combat.CombatState
import org.lwjgl.util.vector.Vector2f

/**
 * 舜华相位绕后场景（两舰满装配 AI 对抗）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class PhaseFlankScenario : AbstractAutomationScenario() {
    // === Phase flank scenario（lens_phase_flank_zw101）===
    // 观测面：舜华相位状态边沿——下潜计数、相位中绕敌舰的方位角扫描幅度（去卷绕累计）、
    // 每次上浮时「敌舰艏向 vs 敌→己方位角」差值。绕后意图生效时扫描幅度/上浮方位差显著。
    private var pfPhase = PF_PHASE_OBSERVE
    private var pfPhaseStartedAt = -1f
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

    override val scenarioId: String = ASTDInGameAutomationScenario.PF_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFlankScenarioEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        // 玩家后备恰 1 艘时通用块保留 vanilla 静默 deployAll（单舰场景范式）——本场景需要
        // 舜华留在 reserves 由 deployPfReserveShips 锚点入场，关断闸门把静默部署一并跳过
        (engine.combatUI as? CombatState)?.setShowDeploymentDialogOnStart(false)
        lockPfCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findPfPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.PF_SCENARIO_ID} combat plugin initialized")
    }

    private fun findPfPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == PF_PLAYER_HULL && !ship.isFighter }

    private fun findPfEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == PF_ENEMY_HULL && !ship.isFighter }

    /** 强制部署 mission reserves（范式同 deployGsrReserveShips）；两舰锚点相对入场后 AI 自由对抗。 */
    private fun deployPfReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == PF_PLAYER_HULL -> PF_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == PF_ENEMY_HULL -> PF_ENEMY_ANCHOR
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

    /** 舜华相位边沿统计：相位中逐帧累计绕敌方位角的去卷绕扫描幅度，上浮沿记录相对敌艏方位差。 */
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
            ctx.log.info(
                "[ASTD-Automation] pf surface#$pfSurfaceCount: 本次相位扫描幅度=${"%.1f".format(pfSweepMax - pfSweepMin)}° " +
                        "上浮方位差=${"%.1f".format(bearingDiff)}°（历史峰值 sweep=${"%.1f".format(pfPhaseSweepMax)}° " +
                        "diff=${"%.1f".format(pfSurfaceBearingDiffMax)}°）",
            )
        }
        pfWasPhased = phased
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployPfReserveShips(engine)
        lockPfCamera(engine)

        val player = findPfPlayer(engine)
        val enemy = findPfEnemy(engine)
        if (player != null && !player.isHulk) player.hitpoints = player.maxHitpoints
        if (enemy != null && !enemy.isHulk) enemy.hitpoints = enemy.maxHitpoints
        // 玩家舰身份照 GRG 范式赋予（保留舰 AI——不置空 shipAI、不锁操控，AI 自由对抗）
        if (player != null && !player.isHulk) engine.setPlayerShipExternal(player)

        if (player != null && enemy != null && pfPhase == PF_PHASE_OBSERVE) {
            if (pfCombatStartAt < 0f) {
                pfCombatStartAt = ctx.elapsed
                ctx.log.info("[ASTD-Automation] pf combat start: 舜华 vs 统治者 AI 对抗观测窗开启")
            }
            trackPfPhaseEdges(player, enemy)
            val combatSeconds = ctx.elapsed - pfCombatStartAt
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
            ctx.log.info("[ASTD-Automation] Completed: lens_phase_flank_zw101 dive/sweep/rear-surface evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：舜华绕后对抗舞台在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findPfPlayer(ctx.engine) }) { lockPfCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val pfPlayer = findPfPlayer(engine)
        val pfCloak = pfPlayer?.phaseCloak
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（相位绕后 AI：下潜计数 / 相位扫描幅度 / 上浮方位差）----
        json.appendLine("  \"pfPhase\": \"$pfPhase\",")
        json.appendLine("  \"pfSystemId\": ${jsonString(pfCloak?.id)},")
        json.appendLine("  \"pfPlayerPhased\": ${pfPlayer?.isPhased == true},")
        json.appendLine("  \"pfDiveCount\": $pfDiveCount,")
        json.appendLine("  \"pfSurfaceCount\": $pfSurfaceCount,")
        json.appendLine("  \"pfPhaseSweepMax\": ${formatFloat(pfPhaseSweepMax)},")
        json.appendLine("  \"pfSurfaceBearingDiffMax\": ${formatFloat(pfSurfaceBearingDiffMax)},")
        json.appendLine("  \"pfCombatSeconds\": ${formatFloat(if (pfCombatStartAt < 0f) 0f else ctx.elapsed - pfCombatStartAt)},")
        json.appendLine("  \"pfPlayerCurrFlux\": ${formatFloat(pfPlayer?.fluxTracker?.currFlux ?: -1f)},")
    }

    private companion object {
        // 舜华相位绕后场景：两舰满装配 AI 对抗的锚点/相机与成功判据。
        private const val PF_PHASE_OBSERVE = "OBSERVE"
        private const val PF_PHASE_COMPLETED = "COMPLETED"
        private const val PF_PHASE_FAILED = "FAILED"
        private const val PF_PLAYER_HULL = "astd_zw_101"
        private const val PF_ENEMY_HULL = "dominator"
        private val PF_PLAYER_ANCHOR = Vector2f(-1800f, 0f)
        private val PF_ENEMY_ANCHOR = Vector2f(1800f, 0f)
        private val PF_CAMERA_CENTER = Vector2f(0f, 0f)
        private const val PF_CAMERA_VISIBLE_HEIGHT = 4500f

        // 成功判据（达其一即证明绕后走位生效）：相位中绕敌方位角扫描幅度 / 上浮时相对敌艏方位差。
        // 修复前原版走位旗标 PHASE_ATTACK_RUN 无人管理，舜华下潜后原地罚站，两值都趋近 0。
        private const val PF_SWEEP_MIN_DEG = 60f
        private const val PF_SURFACE_BEARING_MIN_DEG = 100f
        private const val PF_OBSERVE_TIMEOUT = 90f
    }
}
