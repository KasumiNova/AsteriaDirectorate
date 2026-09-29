package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.combat.CombatState
import org.lwjgl.util.vector.Vector2f

/**
 * 友伤防御下潜实测场景（lens_phase_friendly_beam）：孤立舜华 + 周期性友方直射弹投喂。
 *
 * 敌靶舰远锚 7000su 观测窗内不会接战，舜华孤立待命；插件每 [PFB_FEED_INTERVAL_SEC]s
 * 投喂一发友方直射弹，投喂后 [PFB_HIT_WINDOW_SEC]s 内出现下潜沿记一次命中——验证
 * GravityPhaseCloakAI 的 collectFriendly 防御链路（友军火力并入 defensiveSoon 触发
 * 紧急下潜）在实机端到端生效。成功判据：[PFB_OBSERVE_TIMEOUT]s 观测窗内命中
 * ≥ [PFB_HITS_REQUIRED] 次。
 *
 * 移植自 ai-opt 分支单体插件 advancePfbScenario（0d83a38 + 78383b0 补 AI 修复），
 * 投喂/判定/证据写出口径原样迁移。
 */
class PhaseFriendlyBeamScenario : AbstractAutomationScenario() {
    private var pfbPhase = PFB_PHASE_OBSERVE
    private var pfbCombatStartAt = -1f
    private var pfbNextFeedAt = -1f
    private var pfbLastFeedAt = -1f
    private var pfbFeedCount = 0
    private var pfbDiveTotal = 0
    private var pfbDiveHits = 0
    private var pfbWasPhased = false

    override val scenarioId: String = ASTDInGameAutomationScenario.PFB_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFriendlyBeamEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        // 同 pf 场景：玩家舰留在 reserves 由 deployPfbReserveShips 锚点入场，关断静默部署闸门
        (engine.combatUI as? CombatState)?.setShowDeploymentDialogOnStart(false)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findPfbPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.PFB_SCENARIO_ID} combat plugin initialized")
    }

    private fun findPfbPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == ASTDInGameAutomationScenario.PFB_HULL_ID && !ship.isFighter }

    private fun findPfbEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == ASTDInGameAutomationScenario.PFB_ENEMY_HULL_ID && !ship.isFighter }

    /** 强制部署 mission reserves（范式同 pf 场景）；敌靶舰远远锚定（PFB_ENEMY_ANCHOR），观测窗内不会接战。 */
    private fun deployPfbReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == ASTDInGameAutomationScenario.PFB_HULL_ID -> PFB_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == ASTDInGameAutomationScenario.PFB_ENEMY_HULL_ID -> PFB_ENEMY_ANCHOR
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

    /** 镜头跟随本舰（投喂弹在本舰 400su 圈生成，舞台即本舰周边）。 */
    private fun lockPfbCamera(engine: CombatEngineAPI) {
        val center = findPfbPlayer(engine)?.location ?: PFB_PLAYER_ANCHOR
        lockCameraAt(engine, center, PFB_CAMERA_VISIBLE_HEIGHT)
    }

    /**
     * 投喂一发友方直射弹（范式同 gsrFeedShot，但 source=本舰保证 owner 与本舰同侧——
     * collectFriendly 口径按 owner == ship.owner 收集）：本舰 400su 圈上按投喂序数
     * 取黄金角方向生成 hellbore，直指生成时刻的舰心。hellbore 弹速 500su/s，生成后
     * ~0.2s 进入相位 AI 的 soon 窗口（0.6s），扫描节拍内有多次命中采样机会。
     */
    private fun pfbFeedShot(engine: CombatEngineAPI, player: ShipAPI): Boolean {
        val dirDeg = (pfbFeedCount * 137.5f) % 360f
        val dirRad = Math.toRadians(dirDeg.toDouble())
        val spawn = Vector2f(
            player.location.x + (kotlin.math.cos(dirRad) * PFB_FEED_SPAWN_DIST).toFloat(),
            player.location.y + (kotlin.math.sin(dirRad) * PFB_FEED_SPAWN_DIST).toFloat(),
        )
        val aimDeg = Math.toDegrees(
            kotlin.math.atan2(
                (player.location.y - spawn.y).toDouble(),
                (player.location.x - spawn.x).toDouble(),
            )
        ).toFloat()
        val spawned = engine.spawnProjectile(player, null, PFB_FEED_WEAPON_ID, spawn, aimDeg, Vector2f())
        if (spawned == null) {
            ctx.failureReason = "pfb feed: spawnProjectile($PFB_FEED_WEAPON_ID) 返回 null（weaponId 不可用）"
            pfbPhase = PFB_PHASE_FAILED
            return false
        }
        pfbFeedCount++
        pfbLastFeedAt = ctx.elapsed
        ctx.log.info("[ASTD-Automation] pfb feed#$pfbFeedCount: 友方 hellbore 自 ${"%.0f".format(dirDeg)}° 方向投喂")
        return true
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployPfbReserveShips(engine)
        lockPfbCamera(engine)

        val player = findPfbPlayer(engine)
        val enemy = findPfbEnemy(engine)
        if (player != null && !player.isHulk) player.hitpoints = player.maxHitpoints
        if (enemy != null && !enemy.isHulk) enemy.hitpoints = enemy.maxHitpoints
        // 同 pf 场景：玩家舰身份会清空舰 AI（CombatState.setPlayerShip 口径），补建默认舰 AI
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            if (player.shipAI == null) {
                player.shipAI = Global.getSettings().createDefaultShipAI(player, ShipAIConfig())
            }
        }

        if (player != null && enemy != null && pfbPhase == PFB_PHASE_OBSERVE) {
            if (pfbCombatStartAt < 0f) {
                pfbCombatStartAt = ctx.elapsed
                pfbNextFeedAt = ctx.elapsed + PFB_FIRST_FEED_DELAY_SEC
                ctx.log.info("[ASTD-Automation] pfb combat start: 舜华孤立待命，友方直射弹投喂观测窗开启")
            }
            // 下潜沿检测：投喂后命中窗内的下潜记为友伤防御命中
            val phased = player.isPhased
            if (phased && !pfbWasPhased) {
                pfbDiveTotal++
                val sinceFeed = ctx.elapsed - pfbLastFeedAt
                if (pfbLastFeedAt >= 0f && sinceFeed <= PFB_HIT_WINDOW_SEC) {
                    pfbDiveHits++
                    ctx.log.info("[ASTD-Automation] pfb dive hit#$pfbDiveHits: 投喂后 ${"%.2f".format(sinceFeed)}s 下潜")
                }
            }
            pfbWasPhased = phased

            if (ctx.elapsed >= pfbNextFeedAt) {
                pfbNextFeedAt = ctx.elapsed + PFB_FEED_INTERVAL_SEC
                pfbFeedShot(engine, player)
            }

            val combatSeconds = ctx.elapsed - pfbCombatStartAt
            if (pfbDiveHits >= PFB_HITS_REQUIRED) {
                pfbPhase = PFB_PHASE_COMPLETED
                ctx.log.info(
                    "[ASTD-Automation] pfb friendly-fire evidence: hits=$pfbDiveHits dives=$pfbDiveTotal " +
                            "feeds=$pfbFeedCount at ${"%.1f".format(combatSeconds)}s",
                )
            } else if (combatSeconds >= PFB_OBSERVE_TIMEOUT) {
                ctx.failureReason = "pfb observe timeout: ${PFB_OBSERVE_TIMEOUT.toInt()}s 内友伤防御命中不足" +
                        "（hits=$pfbDiveHits < $PFB_HITS_REQUIRED，dives=$pfbDiveTotal feeds=$pfbFeedCount）"
                pfbPhase = PFB_PHASE_FAILED
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "pfb ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            pfbPhase == PFB_PHASE_FAILED -> "Failed"
            pfbPhase == PFB_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: ${ASTDInGameAutomationScenario.PFB_SCENARIO_ID}")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：投喂舞台在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findPfbPlayer(ctx.engine) }) { lockPfbCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val pfbPlayer = findPfbPlayer(ctx.engine)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（友伤防御下潜：投喂数 / 命中窗内下潜数 / 总下潜数）----
        json.appendLine("  \"pfbPhase\": \"$pfbPhase\",")
        json.appendLine("  \"pfbFeedCount\": $pfbFeedCount,")
        json.appendLine("  \"pfbDiveHits\": $pfbDiveHits,")
        json.appendLine("  \"pfbDiveTotal\": $pfbDiveTotal,")
        json.appendLine("  \"pfbPlayerPhased\": ${pfbPlayer?.isPhased == true},")
        json.appendLine("  \"pfbCombatSeconds\": ${formatFloat(if (pfbCombatStartAt < 0f) 0f else ctx.elapsed - pfbCombatStartAt)},")
    }

    private companion object {
        // 友伤防御下潜实测（lens_phase_friendly_beam）：孤立舜华 + 周期性友方直射弹投喂。
        private const val PFB_PHASE_OBSERVE = "OBSERVE"
        private const val PFB_PHASE_COMPLETED = "COMPLETED"
        private const val PFB_PHASE_FAILED = "FAILED"
        private val PFB_PLAYER_ANCHOR = Vector2f(0f, 0f)
        private val PFB_ENEMY_ANCHOR = Vector2f(7000f, 0f)
        private const val PFB_CAMERA_VISIBLE_HEIGHT = 3000f

        // 投喂弹：原版 hellbore（750 HE、弹速 500su/s），本舰 400su 圈生成直指舰心——
        // 生成后 ~0.2s 进入 soon 窗口（0.6s），单发伤害越过紧急下潜阈值（max(300, 舰体×5%)）。
        private const val PFB_FEED_WEAPON_ID = "hellbore"
        private const val PFB_FEED_SPAWN_DIST = 400f
        private const val PFB_FIRST_FEED_DELAY_SEC = 5f
        private const val PFB_FEED_INTERVAL_SEC = 6f
        private const val PFB_HIT_WINDOW_SEC = 1.5f
        private const val PFB_HITS_REQUIRED = 2
        private const val PFB_OBSERVE_TIMEOUT = 60f
    }
}
