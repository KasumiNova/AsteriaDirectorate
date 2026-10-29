package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiRecoveryScenario : AbstractPhaseAICombatScenario() {
    override val scenarioId = "phase_ai_recovery"
    override val observeTimeoutSec = 120f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-1200f, 0f), 90f, true),
        FleetEntry("enemy_conquest", FleetSide.ENEMY, "astd_test_conquest_dem", "conquest", Vector2f(1800f, 0f), 270f, false),
    )

    override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = evidence.diagnosticsFailureReason
            return Result.Failed
        }
        val externalEmergency = player?.isRetreating == true &&
            player.getListeners(com.fs.starfarer.api.impl.hullmods.PhaseAnchor.PhaseAnchorScript::class.java)
                .any { it.emergencyDive }
        val plannerCycles = evidence.phaseCycles.cycles.filter {
            it.attributed || it.end >= 0f || !externalEmergency
        }
        if (plannerCycles.any {
                !it.entryHardFlux.isFinite() || !it.maxFlux.isFinite() || it.maxFlux <= 0f || it.action == "UNKNOWN"
            }) {
            ctx.failureReason = "观测到真实下潜，但缺少进入前资源与激活决策事实，不能判定恢复行为"
            return Result.Failed
        }
        if (plannerCycles.any { it.entryHardFlux / it.maxFlux > 0.6f && it.action != "EVADE" }) {
            ctx.failureReason = "高硬辐能期间真实发起非紧急下潜"
            return Result.Failed
        }
        val valid = evidence.recoveryPhases.any {
            it.maxFlux > 0f && it.peakHardFlux / it.maxFlux > 0.6f && it.lateralDisplacement >= 400f &&
                it.hardFluxActuallyDropped > it.peakHardFlux * 0.1f && it.endSec >= 0f && it.newFireAfterRecovery
        }
        if (valid) return Result.Completed
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "未观测到高硬辐能侧移恢复后完整爆发及其真实伤害"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        val cycles = evidence.phaseCycles.cycles
        json.appendLine("  \"recoveryPhasesCount\": ${evidence.recoveryPhases.size},")
        json.appendLine("  \"highHardFluxPhasesCount\": ${cycles.count { it.entryHardFlux / it.maxFlux > 0.6f }},")
        json.appendLine("  \"unattributedPhaseCount\": ${cycles.count { !it.attributed }},")
        json.appendLine("  \"recoveryCompleteBurstCount\": ${evidence.recoveryPhases.count { it.newFireAfterRecovery }},")
        val phases = evidence.recoveryPhases.joinToString(",") { phase ->
            "{\"start\":${phase.startSec},\"end\":${phase.endSec},\"startHardFlux\":${phase.startHardFlux}," +
                "\"peakHardFlux\":${phase.peakHardFlux},\"maxFlux\":${phase.maxFlux}," +
                "\"peakRatio\":${phase.peakHardFlux / phase.maxFlux},\"drop\":${phase.hardFluxActuallyDropped}," +
                "\"lateral\":${phase.lateralDisplacement},\"newFire\":${phase.newFireAfterRecovery}," +
                "\"fullBurst\":${phase.resumedVolley?.fullBurst == true}}"
        }
        json.appendLine("  \"recoveryPhaseDetails\": [$phases],")
    }
}
