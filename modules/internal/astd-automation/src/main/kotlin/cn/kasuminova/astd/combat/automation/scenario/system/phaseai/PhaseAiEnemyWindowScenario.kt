package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import cn.kasuminova.astd.combat.automation.base.PhaseCombatEvidence.EnemyWindow
import cn.kasuminova.astd.combat.automation.base.PhaseEngagementEvidence
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiEnemyWindowScenario : AbstractPhaseAICombatScenario() {
    override val scenarioId = "phase_ai_enemy_window"
    override val observeTimeoutSec = 150f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-1200f, 0f), 90f, true),
        FleetEntry("enemy_conquest", FleetSide.ENEMY, "astd_test_conquest_dem", "conquest", Vector2f(1800f, 0f), 270f, false),
    )

    override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = evidence.diagnosticsFailureReason
            return Result.Failed
        }
        val overload = PhaseEngagementEvidence.windowDamage(evidence, EnemyWindow.OVERLOAD)
        val vent = PhaseEngagementEvidence.windowDamage(evidence, EnemyWindow.VENT)
        if (overload > 300f && vent > 300f) return Result.Completed
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "真实敌方窗口内伤害不足：overload=$overload, vent=$vent"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        json.appendLine("  \"overloadWindowDamage\": ${formatFloat(PhaseEngagementEvidence.windowDamage(evidence, EnemyWindow.OVERLOAD))},")
        json.appendLine("  \"ventWindowDamage\": ${formatFloat(PhaseEngagementEvidence.windowDamage(evidence, EnemyWindow.VENT))},")
    }
}
