package cn.kasuminova.astd.combat.automation.base

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.mission.MissionDefinitionAPI
import com.fs.starfarer.combat.CombatState
import org.lwjgl.util.vector.Vector2f

abstract class AbstractPhaseAICombatScenario : AbstractAutomationScenario() {
    enum class Result { Observing, Completed, Failed }
    open val observeTimeoutSec: Float = 150f
    override val pausePolicy = PausePolicy.FORCE_UNPAUSE
    override fun isEnabled(): Boolean =
        System.getProperty(cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.ENABLED_PROPERTY)?.equals("true", true) == true &&
            System.getProperty(cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario.SCENARIO_PROPERTY) == scenarioId
    protected val evidence = PhaseCombatEvidence()
    private var result = Result.Observing
    private var combatStartAt = -1f
    private lateinit var fleet: List<FleetEntry>
    private val deployed = LinkedHashMap<String, ShipAPI>()
    protected val deployedShips: Collection<ShipAPI> get() = deployed.values

    data class FleetEntry(
        val uniqueId: String, val side: FleetSide, val variantId: String, val hullId: String,
        val anchor: Vector2f, val facing: Float, val isPlayer: Boolean = false,
    )

    abstract fun buildFleet(): List<FleetEntry>
    abstract fun verifyEvidence(ctx: AutomationCombatContext, combatSeconds: Float, player: ShipAPI?, enemies: List<ShipAPI>): Result
    open fun stageCamera(engine: CombatEngineAPI, player: ShipAPI?) = lockCameraAt(engine, CAMERA_CENTER, 5000f)
    open fun appendScenarioEvidence(ctx: AutomationCombatContext, json: StringBuilder) {}

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        fleet = buildFleet()
        require(fleet.count { it.isPlayer } == 1 && fleet.map { it.uniqueId }.distinct().size == fleet.size)
        ctx.engine.setDoNotEndCombat(true)
        (ctx.engine.combatUI as? CombatState)?.setShowDeploymentDialogOnStart(false)
        stageCamera(ctx.engine, null)
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", null, null)
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine
        if (result == Result.Observing && combatStartAt < 0f) deployFleet(ctx)
        val player = deployed[fleet.single { it.isPlayer }.uniqueId]
        val enemies = fleet.filter { it.side == FleetSide.ENEMY }.mapNotNull { deployed[it.uniqueId] }
        if (player != null && !player.isHulk && engine.isEntityInPlay(player)) {
            engine.setPlayerShipExternal(player)
            if (player.shipAI == null) player.shipAI = Global.getSettings().createDefaultShipAI(player, ShipAIConfig())
        }
        stageCamera(engine, player)
        if (result == Result.Observing) {
            // 观测期间必须持续禁止原版结束战斗：原版 checkCombatOver 一旦判定某方“已被击败”
            // 会绕过任务管理器直接对存活舰船调用 retreat()，污染 AI 行为观测。
            engine.setDoNotEndCombat(true)
            if (combatStartAt < 0f) {
                if (ctx.elapsed >= 15f) fail(ctx, "舰队未在15秒内完整部署: ${deployed.keys}")
            } else {
                val seconds = ctx.elapsed - combatStartAt
                evidence.update(engine, seconds, player, enemies, player?.let { phaseDiagnostics(engine, it) })
                val unexpected = engine.ships.any {
                    !it.isFighter && it.parentStation == null && it.fleetMember != null && it !in deployed.values
                }
                val battleEnded = player == null || player.isHulk || !engine.isEntityInPlay(player) ||
                    enemies.all { it.isHulk || !engine.isEntityInPlay(it) }
                when {
                    unexpected -> fail(ctx, "发现配置外参战舰，无法判定")
                    seconds >= 1f && deployed.values.any { !it.isHulk && it.shipAI == null } -> fail(ctx, "参战舰缺少真实AI")
                    !evidence.diagnosticsHealthy -> fail(ctx, evidence.diagnosticsFailureReason ?: "诊断无效")
                    else -> {
                        val verified = if (evidence.diagnosticsReady || battleEnded) {
                            verifyEvidence(ctx, seconds, player, enemies)
                        } else Result.Observing
                        when {
                            verified == Result.Failed -> fail(ctx, ctx.failureReason ?: "证据不满足验收条件")
                            verified == Result.Completed -> {
                                result = Result.Completed
                                engine.setDoNotEndCombat(false)
                                ctx.markCompleted()
                            }
                            battleEnded -> fail(ctx, "战斗已正常结束，累计证据不足")
                            seconds >= observeTimeoutSec -> fail(ctx, "观察超时 ${observeTimeoutSec}s，证据不足")
                        }
                    }
                }
            }
        }
        val state = when (result) { Result.Completed -> "Completed"; Result.Failed -> "Failed"; else -> "CombatReady" }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || result != Result.Observing) {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    private fun fail(ctx: AutomationCombatContext, reason: String) {
        result = Result.Failed
        ctx.engine.setDoNotEndCombat(false)
        ctx.failureReason = "$scenarioId: $reason"
        ctx.log.info("[ASTD-Automation] Failed: ${ctx.failureReason}")
    }

    private fun deployFleet(ctx: AutomationCombatContext) {
        val engine = ctx.engine
        for (entry in fleet) {
            if (entry.uniqueId in deployed) continue
            val manager = engine.getFleetManager(entry.side)
            manager.isSuppressDeploymentMessages = true
            val existing = engine.ships.filter { !it.isFighter && it.fleetMember?.id == entry.uniqueId }
            if (existing.size > 1) { fail(ctx, "重复参战ID: ${entry.uniqueId}"); return }
            val ship = existing.singleOrNull() ?: manager.reservesCopy.singleOrNull { it.id == entry.uniqueId }?.let { member ->
                manager.spawnFleetMember(member, Vector2f(entry.anchor), entry.facing, 0f)?.also {
                    manager.removeFromReserves(member)
                }
            } ?: continue
            if (ship.hullSpec.hullId != entry.hullId || ship.variant.hullVariantId != entry.variantId ||
                ship.owner != if (entry.side == FleetSide.PLAYER) 0 else 1) {
                fail(ctx, "舰队身份不匹配: ${entry.uniqueId}"); return
            }
            deployed[entry.uniqueId] = ship
        }
        if (deployed.size != fleet.size || deployed.values.any { !engine.isEntityInPlay(it) }) return
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            if (manager.reservesCopy.isNotEmpty()) { fail(ctx, "存在未预期预备舰: $side"); return }
            // 原版统帅 AI 会在舰船超过峰值性能时间且账面实力劣势时下令全军撤退（约 PPT+30 秒），
            // 长观测场景必须屏蔽该行为，否则 90 秒以上的观测窗口永远无法完成。
            manager.getTaskManager(false).setPreventFullRetreat(true)
            manager.getTaskManager(true).setPreventFullRetreat(true)
        }
        val player = deployed.getValue(fleet.single { it.isPlayer }.uniqueId)
        val enemies = fleet.filter { it.side == FleetSide.ENEMY }.map { deployed.getValue(it.uniqueId) }
        if (enemies.isEmpty()) { fail(ctx, "敌舰配置为空"); return }
        evidence.bind(engine, player, enemies)
        engine.setDoNotEndCombat(true)
        combatStartAt = ctx.elapsed
        ctx.log.info("[ASTD-Automation] $scenarioId fleet=${deployed.mapValues { it.value.id }}")
    }

    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { deployed[fleet.single { it.isPlayer }.uniqueId] }) { stageCamera(ctx.engine, null) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        json.appendLine("  \"paiState\": ${jsonString(result.name)},")
        json.appendLine("  \"paiPhase\": ${jsonString(result.name)},")
        json.appendLine("  \"paiCombatSeconds\": ${formatFloat(if (combatStartAt < 0f) 0f else ctx.elapsed - combatStartAt)},")
        json.appendLine("  \"paiPlayerFireCount\": ${evidence.fireEvents.size},")
        json.appendLine("  \"paiHardFluxPeak\": ${formatFloat(evidence.hardFluxPeak)},")
        json.appendLine("  \"paiCompletedRoutes\": ${evidence.routes.count { evidence.completeRoute(it) }},")
        json.appendLine("  \"paiSurfaceFireWithinWindowCount\": ${evidence.fireEvents.count { it.surfaceTime >= 0f && it.timeSec - it.surfaceTime in 0f..2f }},")
        json.appendLine("  \"paiRecoveryLateralDisplacement\": ${formatFloat(evidence.recoveryPhases.maxOfOrNull { it.lateralDisplacement } ?: 0f)},")
        json.appendLine("  \"paiDeploymentCount\": ${deployed.size},")
        json.appendLine("  \"paiFireEventCount\": ${evidence.fireEvents.size},")
        json.appendLine("  \"paiEnemyDestroyedCount\": ${evidence.enemyDestroyedCount()},")
        json.appendLine("  \"paiTotalEnemyHpDrop\": ${formatFloat(evidence.totalEnemyHpDrop())},")
        json.appendLine("  \"paiPlayerHullDamage\": ${formatFloat(evidence.playerHullDamage())},")
        json.appendLine("  \"paiPlayerEffectiveDamage\": ${formatFloat(evidence.playerEffectiveDamage())},")
        json.appendLine("  \"paiDiveCount\": ${evidence.diveCount},")
        json.appendLine("  \"paiSurfaceCount\": ${evidence.surfaceCount},")
        json.appendLine("  \"paiBurstInterruptionCount\": ${evidence.burstInterruptionCount},")
        json.appendLine("  \"paiEmergencyBurstInterruptionCount\": ${evidence.emergencyBurstInterruptionCount},")
        json.appendLine("  \"paiInterruptionReasons\": ${jsonStringList(evidence.interruptionReasons)},")
        json.appendLine("  \"paiCompletedValidBurstCount\": ${evidence.completedValidBurstCount},")
        json.appendLine("  \"paiPhaseCycles\": ${jsonStringList(evidence.phaseCycles.cycles.map {
            "command=${it.commandTime},entry=${it.start},surface=${it.surface},end=${it.end}," +
                "action=${it.action},mode=${it.recoverMode},entryHardFlux=${it.entryHardFlux},maxFlux=${it.maxFlux},attributed=${it.attributed}"
        })},")
        json.appendLine("  \"paiBoundRecoveryBursts\": ${jsonStringList(evidence.recoveryPhases.mapNotNull { rec ->
            rec.resumedVolley?.let { "recoveryEnd=${rec.endSec},weapon=${it.weapon.id},start=${it.start},end=${it.end}" }
        })},")
        json.appendLine("  \"paiWingFireCount\": ${evidence.wingFireEvents.size},")
        json.appendLine("  \"paiUnobservableProjectileCount\": ${evidence.unobservableProjectileCount},")
        json.appendLine("  \"paiUnboundFireCount\": ${evidence.fireEvents.count { it.targetId == null }},")
        json.appendLine("  \"paiWingUnboundFireCount\": ${evidence.wingFireEvents.count { it.targetId == null }},")
        json.appendLine("  \"paiMaxRouteReplans\": ${evidence.maxRouteReplans},")
        json.appendLine("  \"paiDiagnosticsHealthy\": ${evidence.diagnosticsHealthy},")
        json.appendLine("  \"paiDiagnosticsFailure\": ${jsonString(evidence.diagnosticsFailureReason)},")
        val player = deployed[fleet.single { it.isPlayer }.uniqueId]
        val diagnostics = player?.let { phaseDiagnostics(ctx.engine, it) }
        json.appendLine("  \"paiPlayerHull\": ${formatFloat(player?.hitpoints ?: 0f)},")
        json.appendLine("  \"paiPlayerRetreating\": ${player?.isRetreating == true},")
        json.appendLine("  \"paiPlayerInPlay\": ${player != null && ctx.engine.isEntityInPlay(player)},")
        val anchorEmergency = player?.getListeners(com.fs.starfarer.api.impl.hullmods.PhaseAnchor.PhaseAnchorScript::class.java)
            ?.any { it.emergencyDive } == true
        json.appendLine("  \"paiPhaseAnchorEmergency\": $anchorEmergency,")
        json.appendLine("  \"paiLiveCloakState\": ${jsonString(player?.phaseCloak?.state?.name)},")
        json.appendLine("  \"paiLivePhased\": ${player?.isPhased == true},")
        val sampleTime = (diagnostics?.get("sampleTime") as? Number)?.toFloat()
        val sampleAge = sampleTime?.let { ctx.engine.getTotalElapsedTime(false) - it }
        json.appendLine("  \"paiDecisionSampleAge\": ${sampleAge?.takeIf { it.isFinite() }?.let { formatFloat(it) } ?: "null"},")
        json.appendLine("  \"paiAction\": ${jsonString(diagnostics?.get("action") as? String)},")
        json.appendLine("  \"paiReason\": ${jsonString(diagnostics?.get("reason") as? String)},")
        json.appendLine("  \"paiRouteStage\": ${jsonString(diagnostics?.get("routeStage") as? String)},")
        json.appendLine("  \"paiRouteFailure\": ${jsonString(diagnostics?.get("routeFailureReason") as? String)},")
        json.appendLine("  \"paiRouteFailureCause\": ${jsonString(diagnostics?.get("routeFailureCause") as? String)},")
        json.appendLine("  \"paiRoutePathStage\": ${(diagnostics?.get("pathStage") as? Number)?.toInt() ?: -1},")
        json.appendLine("  \"paiRouteProgress\": ${formatFloat((diagnostics?.get("routeProgress") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiRouteLateralTravel\": ${formatFloat((diagnostics?.get("routeLateralTravel") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiRouteStageTravel\": ${formatFloat((diagnostics?.get("routeStageTravel") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiRouteElapsed\": ${formatFloat((diagnostics?.get("routeElapsed") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiRouteLength\": ${formatFloat((diagnostics?.get("routeLength") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiRouteVersion\": ${(diagnostics?.get("routeVersion") as? Number)?.toInt() ?: 0},")
        json.appendLine("  \"paiWaypointX\": ${formatFloat((diagnostics?.get("waypointX") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiWaypointY\": ${formatFloat((diagnostics?.get("waypointY") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiTargetDistance\": ${formatFloat((diagnostics?.get("targetDistance") as? Number)?.toFloat() ?: -1f)},")
        json.appendLine("  \"paiInTargetRearArc\": ${diagnostics?.get("inTargetRearArc") == true},")
        json.appendLine("  \"paiTargetFrontCoverage\": ${(diagnostics?.get("targetFrontCoverage") as? Number)?.toInt() ?: 0},")
        json.appendLine("  \"paiPhaseActiveTime\": ${formatFloat((diagnostics?.get("phaseActiveTime") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiEffectiveWeaponRange\": ${formatFloat((diagnostics?.get("effectiveWeaponRange") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiSurfaceLoss\": ${formatFloat((diagnostics?.get("surfaceLoss") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiCurrentThreatCount\": ${(diagnostics?.get("currentThreatCount") as? Number)?.toInt() ?: 0},")
        json.appendLine("  \"paiActiveThreatCount\": ${(diagnostics?.get("activeThreatCount") as? Number)?.toInt() ?: 0},")
        json.appendLine("  \"paiCommittedThreatCount\": ${(diagnostics?.get("committedThreatCount") as? Number)?.toInt() ?: 0},")
        json.appendLine("  \"paiCurrentHardFlux\": ${formatFloat((diagnostics?.get("hardFlux") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiCurrentSoftFlux\": ${formatFloat((diagnostics?.get("softFlux") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiVenting\": ${diagnostics?.get("venting") == true},")
        json.appendLine("  \"paiCloakState\": ${jsonString(diagnostics?.get("cloakState") as? String)},")
        json.appendLine("  \"paiMaxFlux\": ${formatFloat((diagnostics?.get("maxFlux") as? Number)?.toFloat() ?: 0f)},")
        json.appendLine("  \"paiDoNotVent\": ${diagnostics?.get("doNotVent") == true},")
        json.appendLine("  \"paiAutoFireSuppressed\": ${diagnostics?.get("autoFireSuppressed") == true},")
        json.appendLine("  \"paiAttackInRange\": ${diagnostics?.get("attackInRange") == true},")
        json.appendLine("  \"paiAttackCanFire\": ${diagnostics?.get("attackCanFire") == true},")
        json.appendLine("  \"paiShipTarget\": ${jsonString(diagnostics?.get("shipTarget") as? String)},")
        json.appendLine("  \"paiHandoffTarget\": ${jsonString(diagnostics?.get("handoffTarget") as? String)},")
        val gates = diagnostics?.get("weaponGates") as? List<*> ?: emptyList<Any>()
        json.appendLine("  \"paiWeaponGates\": [")
        gates.filterIsInstance<Map<*, *>>().forEachIndexed { index, gate ->
            if (index > 0) json.appendLine(",")
            val fields = gate.entries.joinToString(", ") { (key, value) ->
                val rendered = when (value) {
                    is Boolean -> value.toString()
                    is Number -> value.toFloat().takeIf { it.isFinite() }?.let(::formatFloat) ?: "null"
                    is String -> jsonString(value)
                    else -> "null"
                }
                "${jsonString(key as String)}: $rendered"
            }
            json.append("    { $fields }")
        }
        json.appendLine("  ],")
        for (key in listOf("recoverMode", "recoveryCandidateMode", "recoveryState", "recoveryReason",
            "recoveryCommittedMode", "recoveryTargetId", "recoveryObjects", "recoveryPhaseCandidateReason",
            "recoveredAttackState", "recoveredAttackReason", "recoveredAttackPending")) {
            json.appendLine("  \"$key\": ${jsonString(diagnostics?.get(key) as? String)},")
        }
        for (key in listOf("recoveryElapsedLocal", "recoveryCooldownRemaining", "recoveryNaturalDuration",
            "recoveryVentDuration", "recoveryPhaseDuration", "recoveredAttackElapsedLocal",
            "recoveredAttackCost", "supportRange", "opportunityAttackImpact", "opportunityVentImpact", "opportunityWindow")) {
            val value = (diagnostics?.get(key) as? Number)?.toFloat()
            json.appendLine("  \"$key\": ${value?.takeIf { it.isFinite() }?.let { formatFloat(it) } ?: "null"},")
        }
        for (key in listOf("recoveryAwaitingSurface", "recoveryAwaitingAttack", "recoveredAttackReady",
            "recoveredAttackAffordable", "supportAvailable", "supportInRange", "supportEngaged",
            "opportunityAttack", "opportunityVent")) {
            json.appendLine("  \"$key\": ${diagnostics?.get(key) == true},")
        }
        json.appendLine("  \"paiEnemyStates\": ${jsonStringList(deployed.values.filter { it.owner != 0 }.map {
            "${it.fleetMember?.id}:${it.hullSpec.hullId},hp=${formatFloat(it.hitpoints)},flux=${formatFloat(it.fluxTracker.currFlux)}," +
                "x=${formatFloat(it.location.x)},y=${formatFloat(it.location.y)},facing=${formatFloat(it.facing)}"
        })},")
        appendScenarioEvidence(ctx, json)
    }

    protected fun phaseDiagnostics(engine: CombatEngineAPI, ship: ShipAPI): Map<String, Any>? {
        @Suppress("UNCHECKED_CAST")
        return engine.customData["astd_phase_diagnostics_${ship.id}"] as? Map<String, Any>
    }

    companion object {
        private val CAMERA_CENTER = Vector2f(0f, 0f)
        @JvmStatic
        fun addMissionFleet(api: MissionDefinitionAPI, scenario: AbstractPhaseAICombatScenario) {
            for (entry in scenario.buildFleet()) {
                api.addToFleet(entry.side, entry.variantId, FleetMemberType.SHIP, entry.isPlayer).id = entry.uniqueId
            }
        }
    }
}
