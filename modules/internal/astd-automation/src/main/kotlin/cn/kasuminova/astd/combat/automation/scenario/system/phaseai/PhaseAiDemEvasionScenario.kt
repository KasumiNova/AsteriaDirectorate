package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiDemEvasionScenario : AbstractPhaseAICombatScenario() {

    override val scenarioId = "phase_ai_dem_evasion"
    override val observeTimeoutSec = 180f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-1500f, 0f), 90f, true),
        FleetEntry("enemy_conquest", FleetSide.ENEMY, "astd_test_conquest_dem", "conquest", Vector2f(2500f, 0f), 270f, false),
    )

    override fun verifyEvidence(
        ctx: AutomationCombatContext,
        combatSeconds: Float,
        player: ShipAPI?,
        enemies: List<ShipAPI>,
    ): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = "DEM 场景诊断失败: ${evidence.diagnosticsFailureReason}"
            return Result.Failed
        }
        val validDemSequence = evidence.validDemSequenceCount()
        if (validDemSequence >= 1) {
            return Result.Completed
        }
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "DEM 场景超时：未观测到完整的 DEM 威胁→防御下潜→威胁解除→上浮→新射击序列"
            return Result.Failed
        }
        return Result.Observing
    }

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        val validSeq = evidence.validDemSequenceCount()
        json.appendLine("  \"demThreatCount\": ${evidence.demThreats.size},")
        json.appendLine("  \"demMissileObservedCount\": ${evidence.observedDemMissiles.size},")
        json.appendLine("  \"demValidSequenceCount\": $validSeq,")
        val demEvents = evidence.demThreats.take(40).joinToString(",") { event ->
            "{\"appear\":${event.appearTimeSec},\"dive\":${event.defensiveDiveTimeSec}," +
                "\"cleared\":${event.threatClearedTimeSec},\"surface\":${event.resurfaceTimeSec}," +
                "\"newFire\":${event.newFireTimeSec},\"damage\":${event.newFireEffectiveDamage},\"hit\":${event.hit}}"
        }
        json.appendLine("  \"demEvents\": [$demEvents],")
        val enemyStates = deployedShips.filter { it.owner != 0 }.flatMap { ship ->
            ship.allWeapons.filter { it.id == "hydra" || it.id == "dragon" }.map { weapon ->
                val autofire = ship.getWeaponGroupFor(weapon)?.getAutofirePlugin(weapon)
                val state = linkedMapOf<String, Any?>(
                    "shipId" to ship.id, "shipTarget" to ship.shipTarget?.id, "weapon" to weapon.spec.weaponId,
                    "slot" to weapon.slot?.id, "ammo" to weapon.ammo, "cooldown" to weapon.cooldownRemaining,
                    "inBurst" to weapon.isInBurst, "firing" to weapon.isFiring,
                    "autofireRequested" to (autofire?.shouldFire() == true),
                    "target" to (autofire?.targetShip?.id ?: ship.shipTarget?.id),
                    "distance" to (ship.shipTarget?.let {
                        com.fs.starfarer.api.util.Misc.getDistance(weapon.location, it.location)
                    } ?: -1f),
                )
                state.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    val rendered = when (value) {
                        null -> "null"
                        is String -> jsonString(value)
                        is Boolean, is Number -> value.toString()
                        else -> jsonString(value.toString())
                    }
                    "${jsonString(key)}:$rendered"
                }
            }
        }
        json.appendLine("  \"demEnemyStates\": [${enemyStates.joinToString(",")}],")
    }
}
