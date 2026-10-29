package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.combat.CombatState
import org.lwjgl.util.vector.Vector2f

/**
 * 友伤防御下潜实测场景（lens_phase_friendly_beam）：舜华 + 真实友军狼级舰船真实武器开火。
 *
 * 友军狼级在 [PFB_FRIENDLY_ANCHOR] 入场，自由使用原版 AI 开火；舜华面对真实弹体威胁。
 * 敌靶舰远锚 7000su，观测窗内不接战。验证 GravityPhaseCloakAI 的友伤防御链路端到端生效。
 * 成功判据：[PFB_OBSERVE_TIMEOUT]s 内友方弹体命中窗口内下潜 >= [PFB_HITS_REQUIRED] 次。
 */
class PhaseFriendlyBeamScenario : AbstractAutomationScenario() {
    private var pfbPhase = PFB_PHASE_OBSERVE
    private var pfbCombatStartAt = -1f
    private var pfbDiveTotal = 0
    private var pfbDiveHits = 0
    private var pfbWasPhased = false
    private var pfbLastProjectileSeenAt = -1f
    private var deployed = false

    override val scenarioId: String = ASTDInGameAutomationScenario.PFB_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFriendlyBeamEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        (engine.combatUI as? CombatState)?.setShowDeploymentDialogOnStart(false)
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findPfbPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.PFB_SCENARIO_ID} combat plugin initialized")
    }

    private fun findPfbPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == ASTDInGameAutomationScenario.PFB_HULL_ID && !ship.isFighter }

    private fun findPfbEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == ASTDInGameAutomationScenario.PFB_ENEMY_HULL_ID && !ship.isFighter }

    private fun findPfbFriendly(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == ASTDInGameAutomationScenario.PFB_FRIENDLY_HULL_ID && !ship.isFighter }

    private fun deployPfbReserveShips(engine: CombatEngineAPI) {
        if (deployed) return
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor: Vector2f? = when {
                    side == FleetSide.PLAYER && member.hullId == ASTDInGameAutomationScenario.PFB_HULL_ID -> PFB_PLAYER_ANCHOR
                    side == FleetSide.PLAYER && member.hullId == ASTDInGameAutomationScenario.PFB_FRIENDLY_HULL_ID -> PFB_FRIENDLY_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == ASTDInGameAutomationScenario.PFB_ENEMY_HULL_ID -> PFB_ENEMY_ANCHOR
                    else -> null
                }
                anchor ?: continue
                if (findShipByHull(engine, member.hullId) != null) {
                    manager.removeFromReserves(member)
                    continue
                }
                val facing = when {
                    side == FleetSide.ENEMY -> 180f
                    member.hullId == ASTDInGameAutomationScenario.PFB_FRIENDLY_HULL_ID -> 90f
                    else -> 0f
                }
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
        deployed = true
    }

    private fun lockPfbCamera(engine: CombatEngineAPI) {
        val center = findPfbPlayer(engine)?.location ?: PFB_PLAYER_ANCHOR
        lockCameraAt(engine, center, PFB_CAMERA_VISIBLE_HEIGHT)
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployPfbReserveShips(engine)
        lockPfbCamera(engine)

        val player = findPfbPlayer(engine)
        val enemy = findPfbEnemy(engine)
        val friendly = findPfbFriendly(engine)

        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            if (player.shipAI == null) {
                player.shipAI = Global.getSettings().createDefaultShipAI(player, ShipAIConfig())
            }
        }

        if (player != null && enemy != null && friendly != null && pfbPhase == PFB_PHASE_OBSERVE) {
            if (pfbCombatStartAt < 0f) {
                pfbCombatStartAt = ctx.elapsed
                ctx.log.info("[ASTD-Automation] pfb combat start: 舜华 + 友军狼级，真实武器开火观测窗开启")
            }

            val phased = player.isPhased
            if (phased && !pfbWasPhased) {
                pfbDiveTotal++
                val sinceFriendlyFire = ctx.elapsed - pfbLastProjectileSeenAt
                if (pfbLastProjectileSeenAt >= 0f && sinceFriendlyFire <= PFB_HIT_WINDOW_SEC) {
                    pfbDiveHits++
                    ctx.log.info("[ASTD-Automation] pfb dive hit#$pfbDiveHits: 友方弹体出现后 ${sinceFriendlyFire}s 下潜")
                }
            }
            pfbWasPhased = phased

            val playerLoc = player.location
            for (proj in engine.projectiles) {
                if (proj.owner != player.owner) continue
                if (proj.source?.id != friendly.id) continue
                if (proj.location.let { (it.x - playerLoc.x) * (it.x - playerLoc.x) + (it.y - playerLoc.y) * (it.y - playerLoc.y) } > PFB_PROXIMITY_SU * PFB_PROXIMITY_SU) continue
                pfbLastProjectileSeenAt = ctx.elapsed
                break
            }
            for (missile in engine.missiles) {
                if (missile.owner != player.owner) continue
                if (missile.source?.id != friendly.id) continue
                if (missile.location.let { (it.x - playerLoc.x) * (it.x - playerLoc.x) + (it.y - playerLoc.y) * (it.y - playerLoc.y) } > PFB_PROXIMITY_SU * PFB_PROXIMITY_SU) continue
                pfbLastProjectileSeenAt = ctx.elapsed
                break
            }

            val combatSeconds = ctx.elapsed - pfbCombatStartAt
            if (pfbDiveHits >= PFB_HITS_REQUIRED) {
                pfbPhase = PFB_PHASE_COMPLETED
                ctx.log.info("[ASTD-Automation] pfb friendly-fire evidence: hits=$pfbDiveHits dives=$pfbDiveTotal at ${combatSeconds}s")
            } else if (combatSeconds >= PFB_OBSERVE_TIMEOUT) {
                ctx.failureReason = "pfb observe timeout: ${PFB_OBSERVE_TIMEOUT.toInt()}s 内友伤防御命中不足（hits=$pfbDiveHits < $PFB_HITS_REQUIRED，dives=$pfbDiveTotal）"
                pfbPhase = PFB_PHASE_FAILED
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "pfb ships missing: player=${player != null}, enemy=${enemy != null}, friendly=${friendly != null}"
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

    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findPfbPlayer(ctx.engine) }) { lockPfbCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val pfbPlayer = findPfbPlayer(ctx.engine)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        json.appendLine("  \"pfbPhase\": \"$pfbPhase\",")
        json.appendLine("  \"pfbDiveHits\": $pfbDiveHits,")
        json.appendLine("  \"pfbDiveTotal\": $pfbDiveTotal,")
        json.appendLine("  \"pfbPlayerPhased\": ${pfbPlayer?.isPhased == true},")
        json.appendLine("  \"pfbCombatSeconds\": ${formatFloat(if (pfbCombatStartAt < 0f) 0f else ctx.elapsed - pfbCombatStartAt)},")
        json.appendLine("  \"pfbLastProjectileSeenAt\": ${formatFloat(pfbLastProjectileSeenAt)},")
    }

    private companion object {
        private const val PFB_PHASE_OBSERVE = "OBSERVE"
        private const val PFB_PHASE_COMPLETED = "COMPLETED"
        private const val PFB_PHASE_FAILED = "FAILED"
        private val PFB_PLAYER_ANCHOR = Vector2f(0f, 0f)
        private val PFB_FRIENDLY_ANCHOR = Vector2f(500f, 400f)
        private val PFB_ENEMY_ANCHOR = Vector2f(7000f, 0f)
        private const val PFB_CAMERA_VISIBLE_HEIGHT = 3000f
        private const val PFB_HIT_WINDOW_SEC = 2.5f
        private const val PFB_HITS_REQUIRED = 2
        private const val PFB_OBSERVE_TIMEOUT = 90f
        private const val PFB_PROXIMITY_SU = 600f
    }
}
