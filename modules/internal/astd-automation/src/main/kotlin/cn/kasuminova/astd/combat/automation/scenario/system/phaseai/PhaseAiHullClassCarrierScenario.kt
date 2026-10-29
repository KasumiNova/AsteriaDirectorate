package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import cn.kasuminova.astd.combat.automation.base.PhaseEngagementEvidence
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiHullClassCarrierScenario : AbstractPhaseAICombatScenario() {
    override val scenarioId = "phase_ai_hull_class_carrier"
    override val observeTimeoutSec = 150f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw103", FleetSide.PLAYER, "astd_zw_103_Standard", "astd_zw_103", Vector2f(-1800f, 0f), 90f, true),
        FleetEntry("enemy_dominator", FleetSide.ENEMY, "dominator_Assault", "dominator", Vector2f(2200f, 0f), 270f, false),
    )

    override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = evidence.diagnosticsFailureReason
            return Result.Failed
        }
        val attacks = PhaseEngagementEvidence.carrierAttacks(evidence)
        if (attacks.size >= 3 && attacks.sumOf { it.damage.toDouble() } > 500.0) return Result.Completed
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "未观测到母舰远距支援下所属舰载机对绑定目标的真实开火与命中：${attacks.size}"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        val attacks = PhaseEngagementEvidence.carrierAttacks(evidence)
        val mother = PhaseEngagementEvidence.rangedAttacks(evidence, 0.65f)
        json.appendLine("  \"carrierMotherRangedFireCount\": ${mother.size},")
        json.appendLine("  \"carrierWingFireCount\": ${evidence.wingFireEvents.size},")
        json.appendLine("  \"carrierWingEffectiveDamage\": ${formatFloat(evidence.wingHitEvents.sumOf { it.effectiveDamage.toDouble() }.toFloat())},")
        json.appendLine("  \"carrierRangedWingAttackCount\": ${attacks.size},")
        json.appendLine("  \"carrierRangedWingDamage\": ${formatFloat(attacks.sumOf { it.damage.toDouble() }.toFloat())},")
        json.appendLine("  \"carrierAvgFighterRange\": ${formatFloat(attacks.map { it.distance }.average().takeIf { it.isFinite() }?.toFloat() ?: -1f)},")
    }
}
