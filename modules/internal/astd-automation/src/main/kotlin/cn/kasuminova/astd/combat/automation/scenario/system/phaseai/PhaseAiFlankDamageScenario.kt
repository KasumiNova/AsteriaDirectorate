package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiFlankDamageScenario : AbstractPhaseAICombatScenario() {

    override val scenarioId = "phase_ai_flank_damage"
    override val observeTimeoutSec = 150f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-2000f, 0f), 90f, true),
        FleetEntry("enemy_dominator", FleetSide.ENEMY, "dominator_Assault", "dominator", Vector2f(2500f, 0f), 270f, false),
    )

    override fun verifyEvidence(
        ctx: AutomationCombatContext,
        combatSeconds: Float,
        player: ShipAPI?,
        enemies: List<ShipAPI>,
    ): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = "绕后场景诊断失败: ${evidence.diagnosticsFailureReason}"
            return Result.Failed
        }
        val completed = evidence.routes.any { route ->
            evidence.completeRoute(route) &&
                evidence.surfaceEffectiveDamage(route.completedAt, route.targetId) > 800f
        }
        if (completed) return Result.Completed
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "未完成三阶段实际到达、侧后上浮两秒内开火及后续本舰伤害"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        json.appendLine("  \"routeWaypointsTotal\": ${evidence.routeWaypoints.size},")
        json.appendLine("  \"routeStage0Count\": ${evidence.routeWaypoints.count { it.pathStage == 0 }},")
        json.appendLine("  \"routeStage1Count\": ${evidence.routeWaypoints.count { it.pathStage == 1 }},")
        json.appendLine("  \"routeStage2Count\": ${evidence.routeWaypoints.count { it.pathStage == 2 }},")
        val reports = evidence.routes.joinToString(",") { route ->
            "{\"targetId\":${jsonString(route.targetId)},\"version\":${route.version}," +
                "\"completedAt\":${formatFloat(route.completedAt)},\"complete\":${evidence.completeRoute(route)}," +
                "\"arrivedStages\":${jsonString(route.waypoints.filter { it.arrived }.map { it.pathStage }.toString())}," +
                "\"rearGeometry\":${route.rearGeometry},\"failed\":${route.failed}," +
                "\"surfaceDamage\":${formatFloat(evidence.surfaceEffectiveDamage(route.completedAt, route.targetId))}," +
                "\"surfaceFireCount\":${evidence.surfaceFiresAfter(route.completedAt, route.targetId).size}}"
        }
        json.appendLine("  \"routeReports\": [$reports],")
    }
}
