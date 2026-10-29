package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import cn.kasuminova.astd.combat.automation.base.PhaseEngagementEvidence
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiHullClassCruiserScenario : AbstractPhaseAICombatScenario() {
    override val scenarioId = "phase_ai_hull_class_cruiser"
    override val observeTimeoutSec = 120f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw002", FleetSide.PLAYER, "astd_zw_002_Standard", "astd_zw_002", Vector2f(-1500f, 0f), 90f, true),
        FleetEntry("enemy_eagle", FleetSide.ENEMY, "eagle_Assault", "eagle", Vector2f(2000f, 0f), 270f, false),
    )

    override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result {
        if (!evidence.diagnosticsHealthy || player?.hullSize != ShipAPI.HullSize.CRUISER) {
            ctx.failureReason = "巡洋舰身份或诊断无效"
            return Result.Failed
        }
        val attacks = PhaseEngagementEvidence.rangedAttacks(evidence, 0.65f)
        if (attacks.size >= 3 && attacks.sumOf { it.damage.toDouble() } > 500.0) return Result.Completed
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "巡洋舰非贴身攻击证据不足：${attacks.size}"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        val attacks = PhaseEngagementEvidence.rangedAttacks(evidence, 0.65f)
        json.appendLine("  \"cruiserFireCount\": ${attacks.size},")
        json.appendLine("  \"cruiserAvgRange\": ${formatFloat(attacks.map { it.fire.distance }.average().takeIf { it.isFinite() }?.toFloat() ?: -1f)},")
        json.appendLine("  \"cruiserRangedDamage\": ${formatFloat(attacks.sumOf { it.damage.toDouble() }.toFloat())},")
    }
}
