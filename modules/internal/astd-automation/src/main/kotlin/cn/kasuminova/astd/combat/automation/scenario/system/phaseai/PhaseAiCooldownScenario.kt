package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario
import cn.kasuminova.astd.combat.automation.base.PhaseEngagementEvidence
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

class PhaseAiCooldownScenario : AbstractPhaseAICombatScenario() {
    override val scenarioId = "phase_ai_cooldown"
    override val observeTimeoutSec = 120f

    override fun buildFleet() = listOf(
        FleetEntry("player_zw101", FleetSide.PLAYER, "astd_zw_101_Standard", "astd_zw_101", Vector2f(-1400f, 0f), 90f, true),
        FleetEntry("enemy_dominator", FleetSide.ENEMY, "dominator_Assault", "dominator", Vector2f(2000f, 0f), 270f, false),
    )

    override fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result {
        if (!evidence.diagnosticsHealthy) {
            ctx.failureReason = evidence.diagnosticsFailureReason
            return Result.Failed
        }
        if (PhaseEngagementEvidence.cooldownAttacks(evidence).isNotEmpty()) return Result.Completed
        if (combatSeconds >= observeTimeoutSec) {
            ctx.failureReason = "未观测到绑定冷却相位周期、剩余冷却和完整前后爆发的提前攻击"
            return Result.Failed
        }
        return Result.Observing
    }

    private fun formatFloatOrNull(value: Float): String =
        if (value.isFinite()) formatFloat(value) else "null"

    override fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {
        val eval = PhaseEngagementEvidence.evaluateCooldownAttacks(evidence)
        json.appendLine("  \"phaseWindowsCount\": ${evidence.phaseCycles.cycles.size},")
        json.appendLine("  \"cooldownSequencesFound\": ${eval.passed.size},")
        json.appendLine("  \"cooldownRejectedAttempts\": ${eval.rejections.size},")

        eval.passed.firstOrNull()?.let {
            json.appendLine("  \"firstCooldownFirstFire\": ${formatFloat(it.before.start)},")
            json.appendLine("  \"firstCooldownSecondFire\": ${formatFloat(it.after.start)},")
            json.appendLine("  \"firstCooldownTimeSaved\": ${formatFloat(it.savedSeconds)},")
            json.appendLine("  \"firstCooldownSecondDamage\": ${formatFloat(it.damage)},")
            json.appendLine("  \"firstCooldownCycleStart\": ${formatFloat(it.cycle.start)},")
            json.appendLine("  \"firstCooldownCycleEnd\": ${formatFloat(it.cycle.end)},")
            val cooldown = it.sample
            json.appendLine("  \"firstCooldownEntryRemaining\": ${formatFloat(cooldown.remaining)},")
            json.appendLine("  \"firstCooldownResource\": ${jsonString(cooldown.resource.name)},")
            json.appendLine("  \"firstCooldownNaturalRate\": ${formatFloat(cooldown.naturalRate)},")
            json.appendLine("  \"firstCooldownEntryAmmo\": ${cooldown.entryAmmo},")
            json.appendLine("  \"firstCooldownRequiredAmmo\": ${cooldown.requiredAmmo},")
            json.appendLine("  \"firstCooldownExitAmmo\": ${cooldown.ammoAtExit},")
            json.appendLine("  \"firstCooldownExitRemaining\": ${formatFloat(cooldown.remainingAtExit)},")
            json.appendLine("  \"firstCooldownBaselineTimeMult\": ${formatFloat(it.cycle.baseTimeMult)},")
        }

        val recoverCycles = evidence.phaseCycles.cycles.filter { it.action == "RECOVER" }
        json.appendLine("  \"recoverCycles\": [")
        recoverCycles.forEachIndexed { idx, cycle ->
            json.append("    { \"cycleIndex\": ${evidence.phaseCycles.cycles.indexOf(cycle)},")
            json.append(" \"command\": ${formatFloat(cycle.commandTime)},")
            json.append(" \"start\": ${formatFloat(cycle.start)},")
            json.append(" \"surface\": ${formatFloatOrNull(cycle.surface)},")
            json.append(" \"end\": ${formatFloatOrNull(cycle.end)},")
            json.append(" \"action\": ${jsonString(cycle.action)},")
            json.append(" \"recoverMode\": ${jsonString(cycle.recoverMode)},")
            json.append(" \"attributed\": ${cycle.attributed},")
            json.append(" \"stableTimeMult\": ${cycle.stableTimeMult},")
            json.append(" \"baseTimeMult\": ${formatFloatOrNull(cycle.baseTimeMult)},")
            json.append(" \"firstClockChangeTime\": ${formatFloatOrNull(cycle.firstClockChangeTime)},")
            json.append(" \"firstChangedBaseTimeMult\": ${formatFloatOrNull(cycle.firstChangedBaseTimeMult)},")
            json.append(" \"targetId\": ${jsonString(cycle.targetId)},")
            json.append(" \"entryHardFlux\": ${formatFloatOrNull(cycle.entryHardFlux)},")
            json.append(" \"maxFlux\": ${formatFloatOrNull(cycle.maxFlux)},")
            json.append(" \"reason\": ${jsonString(cycle.reason)},")

            val cycleRejections = eval.rejections.filter { it.first === cycle }
            json.append(" \"rejections\": [")
            cycleRejections.forEachIndexed { rejIdx, (_, rejection) ->
                json.append("{ \"code\": ${jsonString(rejection.code.name)},")
                json.append(" \"weaponId\": ${jsonString(rejection.weaponId)},")
                json.append(" \"slot\": ${jsonString(rejection.slot)},")
                json.append(" \"detail\": ${jsonString(rejection.detail)},")
                json.append(" \"floatValues\": {")
                rejection.floatValues.entries.forEachIndexed { fIdx, (k, v) ->
                    json.append(" ${jsonString(k)}: ${formatFloatOrNull(v)}")
                    if (fIdx < rejection.floatValues.size - 1) json.append(",")
                }
                json.append(" },")
                if (rejection.beforeCandidate != null) {
                    val bc = rejection.beforeCandidate
                    json.append(" \"beforeCandidate\": {")
                    json.append(" \"found\": ${bc.volley != null},")
                    json.append(" \"fullBurst\": ${bc.fullBurst},")
                    json.append(" \"end\": ${formatFloatOrNull(bc.end)},")
                    json.append(" \"command\": ${formatFloatOrNull(bc.commandTime)},")
                    json.append(" \"allTargetMatch\": ${bc.allTargetMatch},")
                    json.append(" \"targetId\": ${jsonString(bc.targetId)}")
                    json.append(" }")
                } else {
                    json.append(" \"beforeCandidate\": null")
                }
                json.append(" }")
                if (rejIdx < cycleRejections.size - 1) json.append(", ")
            }
            json.append("],")

            json.append(" \"cooldowns\": [")
            cycle.cooldowns.forEachIndexed { wepIdx, cooldown ->
                json.append("{")
                json.append(" \"weaponId\": ${jsonString(cooldown.weapon.spec?.weaponId)},")
                json.append(" \"slot\": ${jsonString(cooldown.weapon.slot?.id)},")
                json.append(" \"remaining\": ${formatFloat(cooldown.remaining)},")
                json.append(" \"remainingAtExit\": ${formatFloatOrNull(cooldown.remainingAtExit)},")
                json.append(" \"resource\": ${jsonString(cooldown.resource.name)},")
                json.append(" \"naturalRate\": ${formatFloat(cooldown.naturalRate)},")
                json.append(" \"entryAmmo\": ${cooldown.entryAmmo},")
                json.append(" \"requiredAmmo\": ${cooldown.requiredAmmo},")
                json.append(" \"ammoAtExit\": ${cooldown.ammoAtExit},")
                json.append(" \"reloadProgress\": ${formatFloat(cooldown.reloadProgress)},")
                json.append(" \"reloadProgressAtExit\": ${formatFloatOrNull(cooldown.reloadProgressAtExit)},")
                json.append(" \"reloadSize\": ${formatFloat(cooldown.reloadSize)},")
                json.append(" \"ammoPerSecond\": ${formatFloat(cooldown.ammoPerSecond)},")
                json.append(" \"refireDelay\": ${formatFloat(cooldown.refireDelay)},")
                json.append(" \"cooldown\": ${formatFloat(cooldown.cooldown)},")
                json.append(" \"stable\": ${cooldown.stable}")
                if (!cooldown.stable) {
                    json.append(", \"instabilityTime\": ${formatFloatOrNull(cooldown.firstInstabilityTime)},")
                    json.append(" \"instabilityReason\": ${jsonString(cooldown.firstInstabilityReason)},")
                    json.append(" \"instabilityRefireDelay\": ${formatFloatOrNull(cooldown.instabilityRefireDelay)},")
                    json.append(" \"instabilityCooldown\": ${formatFloatOrNull(cooldown.instabilityCooldown)}")
                }
                val extraProgress = if (cycle.end.isFinite() && cycle.baseTimeMult.isFinite()) {
                    cooldown.remaining - cooldown.remainingAtExit -
                        (cycle.end - cycle.commandTime) * cycle.baseTimeMult * cooldown.naturalRate
                } else Float.NaN
                json.append(", \"extraProgress\": ${formatFloatOrNull(extraProgress)}")

                val sameWeapon = evidence.volleys.volleys.filter { it.weapon === cooldown.weapon }
                val preceding = sameWeapon.lastOrNull { it.start <= cycle.commandTime }
                val deadline = if (cycle.end >= 0f) cycle.end + 2f else evidence.sampleTimeSec
                val nearby = sameWeapon.filter { it === preceding || it.start in cycle.commandTime..deadline }
                json.append(", \"nearbyVolleys\": [")
                nearby.forEachIndexed { volleyIndex, volley ->
                    json.append("{ \"start\": ${formatFloatOrNull(volley.start)},")
                    json.append(" \"end\": ${formatFloatOrNull(volley.end)},")
                    json.append(" \"fullBurst\": ${volley.fullBurst},")
                    json.append(" \"cancelled\": ${volley.cancelled},")
                    json.append(" \"expectedShots\": ${volley.expectedShots},")
                    json.append(" \"minimumTimeMult\": ${formatFloatOrNull(volley.minimumTimeMult)},")
                    json.append(" \"fires\": [")
                    volley.fires.forEachIndexed { fireIndex, fire ->
                        json.append("{ \"time\": ${formatFloatOrNull(fire.timeSec)},")
                        json.append(" \"targetId\": ${jsonString(fire.targetId)},")
                        json.append(" \"attackWeapon\": ${fire.attackWeapon},")
                        json.append(" \"shots\": ${fire.shots},")
                        json.append(" \"timeMult\": ${formatFloatOrNull(fire.timeMult)},")
                        json.append(" \"refireDelay\": ${formatFloatOrNull(fire.refireDelay)},")
                        json.append(" \"cooldown\": ${formatFloatOrNull(fire.cooldown)} }")
                        if (fireIndex < volley.fires.size - 1) json.append(", ")
                    }
                    json.append("] }")
                    if (volleyIndex < nearby.size - 1) json.append(", ")
                }
                json.append("]")
                json.append(" }")
                if (wepIdx < cycle.cooldowns.size - 1) json.append(", ")
            }
            json.append("]")

            json.append(" }")
            if (idx < recoverCycles.size - 1) json.appendLine(",") else json.appendLine()
        }
        json.appendLine("  ],")
    }
}
