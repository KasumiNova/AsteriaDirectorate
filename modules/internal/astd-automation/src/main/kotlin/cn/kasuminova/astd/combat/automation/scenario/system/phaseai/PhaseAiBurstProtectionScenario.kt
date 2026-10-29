package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiBurstProtectionScenario : AbstractPhaseAICombatScenario() {

    override val scenarioId = "phase_ai_burst_protection"
    override val observeTimeoutSec = 120f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-1200f, 0f), 90f, true),
        FleetEntry("enemy_dominator", FleetSide.ENEMY, "dominator_Assault", "dominator", Vector2f(1800f, 0f), 270f, false),
    )

    override fun verifyEvidence(
        ctx: AutomationCombatContext,
        combatSeconds: Float,
        player: ShipAPI?,
        enemies: List<ShipAPI>,
    ): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = "爆发保护场景诊断失败: ${evidence.diagnosticsFailureReason}"
            return Result.Failed
        }
        if (player != null && (phaseDiagnostics(ctx.engine, player)?.get("costUncertainties") as? String)?.isNotEmpty() == true) {
            ctx.failureReason = "爆发成本包含未解决的不确定项，不能判定爆发保护"
            return Result.Failed
        }
        if (evidence.burstInterruptionCount > 0) {
            ctx.failureReason = "发生非紧急有效爆发中断: ${evidence.interruptionReasons}"
            return Result.Failed
        }
        val battleEnded = player == null || player.isHulk || player.isRetreating ||
            !ctx.engine.isEntityInPlay(player) || enemies.all { it.isHulk }
        if ((combatSeconds >= 30f || battleEnded) && evidence.surfaceCount >= 1 &&
            evidence.completedValidBurstCount >= 1 && evidence.playerEffectiveDamage() > 300f
        ) {
            return Result.Completed
        }
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "爆发保护场景超时：completedValidBurst=${evidence.completedValidBurstCount}, interruption=${evidence.burstInterruptionCount}"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        json.appendLine("  \"burstInterruption\": ${evidence.burstInterruptionCount},")
        json.appendLine("  \"completedValidBurst\": ${evidence.completedValidBurstCount},")
    }
}
