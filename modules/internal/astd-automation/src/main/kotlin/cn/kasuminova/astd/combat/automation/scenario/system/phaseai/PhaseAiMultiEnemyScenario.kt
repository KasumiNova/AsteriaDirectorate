package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiMultiEnemyScenario : AbstractPhaseAICombatScenario() {

    override val scenarioId = "phase_ai_multi_enemy"
    override val observeTimeoutSec = 150f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-1500f, 0f), 90f, true),
        FleetEntry("friendly_wolf", FleetSide.PLAYER, "wolf_Starting", "wolf", Vector2f(-1200f, -400f), 90f, false),
        FleetEntry("enemy_dominator", FleetSide.ENEMY, "dominator_Assault", "dominator", Vector2f(2200f, 0f), 270f, false),
        FleetEntry("enemy_eagle", FleetSide.ENEMY, "eagle_Assault", "eagle", Vector2f(2200f, -300f), 270f, false),
    )

    override fun verifyEvidence(
        ctx: AutomationCombatContext,
        combatSeconds: Float,
        player: ShipAPI?,
        enemies: List<ShipAPI>,
    ): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = "多敌场景诊断失败: ${evidence.diagnosticsFailureReason}"
            return Result.Failed
        }
        val expectedEnemies = buildFleet().count { it.side == FleetSide.ENEMY }
        if (enemies.size != expectedEnemies || evidence.enemyHpDrops.size != expectedEnemies) {
            ctx.failureReason = "多敌数量或身份不匹配"
            return Result.Failed
        }
        val fire = evidence.completeSurfaceFire()
        if (evidence.surfaceCount >= 2 && evidence.fireEvents.size >= 5 && fire != null &&
            evidence.playerEffectiveDamage(fire.timeSec) > 800f) return Result.Completed
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "多敌场景超时，本舰上浮后有效输出不足: ${evidence.playerHullDamage()}"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        json.appendLine("  \"enemyCount\": ${evidence.enemyHpDrops.size},")
    }
}
