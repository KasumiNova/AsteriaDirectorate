package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiLongRangeScenario : AbstractPhaseAICombatScenario() {
    override val scenarioId = "phase_ai_long_range_engagement"
    override val observeTimeoutSec = 120f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-3000f, 0f), 0f, true),
        FleetEntry("enemy_dominator", FleetSide.ENEMY, "dominator_Assault", "dominator", Vector2f(3000f, 0f), 180f),
    )

    override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result {
        if (player == null) return Result.Observing
        val inRange = enemies.any { Vector2f.sub(player.location, it.location, null).length() < 1800f }
        return if (inRange && evidence.fireEvents.isNotEmpty() && evidence.playerEffectiveDamage() > 0f) Result.Completed else Result.Observing
    }
}
