package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiTargetTurnScenario : AbstractPhaseAICombatScenario() {
    override val scenarioId = "phase_ai_target_turn"
    override val observeTimeoutSec = 150f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-2000f, 300f), 0f, true),
        FleetEntry("enemy_dominator", FleetSide.ENEMY, "dominator_Assault", "dominator", Vector2f(2000f, -300f), 180f),
    )

    override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result {
        if (evidence.routeLoopFailure || evidence.routes.sumOf { it.replans } > 6) {
            ctx.failureReason = "目标转向后重规划超过单路线2次或全场6次上限"
            return Result.Failed
        }
        val recovered = evidence.routes.any { route ->
            val fire = evidence.surfaceFireAfter(route.completedAt, route.targetId)
            route.targetResponseAt >= 0f && route.completedAt > route.targetResponseAt &&
                evidence.completeRoute(route) && fire != null && evidence.fireEffectiveDamage(fire, route.targetId) > 300f
        }
        if (recovered && combatSeconds >= 90f) return Result.Completed
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        val reports = evidence.routes.joinToString(",") { route ->
            val fire = evidence.surfaceFireAfter(route.completedAt, route.targetId)
            val fireDamage = fire?.let { evidence.fireEffectiveDamage(it, route.targetId) } ?: 0f
            "{\"version\":${route.version},\"replans\":${route.replans}," +
                "\"targetResponseAt\":${formatFloat(route.targetResponseAt)}," +
                "\"completedAt\":${formatFloat(route.completedAt)},\"complete\":${evidence.completeRoute(route)}," +
                "\"arrivedStages\":${jsonString(route.waypoints.filter { it.arrived }.map { it.pathStage }.toString())}," +
                "\"replanReasons\":${jsonString(route.replanReasons.toString())}," +
                "\"failed\":${route.failed},\"failureReason\":${jsonString(route.failureReason)}," +
                "\"failureCause\":${jsonString(route.failureCause)}," +
                "\"surfaceFire\":${fire != null},\"surfaceFireDamage\":${formatFloat(fireDamage)}}"
        }
        json.appendLine("  \"targetTurnRouteReports\": [$reports],")
        val hits = evidence.receivedHits.filter { it.applied >= 100f }.joinToString(",") { hit ->
            "{\"time\":${formatFloat(hit.timeSec)},\"applied\":${formatFloat(hit.applied)}," +
                "\"phased\":${hit.phased},\"cloak\":${jsonString(hit.cloak)},\"detail\":${jsonString(hit.detail)}}"
        }
        json.appendLine("  \"targetTurnReceivedHits\": [$hits],")
    }
}
