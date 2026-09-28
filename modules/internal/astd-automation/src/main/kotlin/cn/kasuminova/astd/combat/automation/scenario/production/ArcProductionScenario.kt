package cn.kasuminova.astd.combat.automation.scenario.production

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

/**
 * ARC production 舰船 VFX/tooltip 场景。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class ArcProductionScenario : AbstractAutomationScenario() {
    private val arcProductionAnchors = mapOf(
        ASTDArcProductionShipIds.HULL_XC_102 to Vector2f(-720f, 120f),
        ASTDArcProductionShipIds.HULL_XC_101 to Vector2f(-80f, -40f),
        ASTDArcProductionShipIds.HULL_XC_103 to Vector2f(520f, 135f),
        "ally_frigate" to Vector2f(-500f, -280f),
        "ally_destroyer" to Vector2f(360f, -255f),
        "enemy_target" to Vector2f(980f, 20f),
    )

    override val scenarioId: String = ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isArcProductionEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockArcProductionCamera(engine)
        arrangeArcProductionShips(engine)
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", arcProductionTelemetryShip(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID} combat plugin initialized")
    }

    private fun deployArcProductionReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployArcProductionSide(engine, FleetSide.PLAYER)
        deployArcProductionSide(engine, FleetSide.ENEMY)
    }

    private fun deployArcProductionSide(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.isSuppressDeploymentMessages = true
        val reserves = manager.reservesCopy.toList()
        if (reserves.isEmpty()) return

        var allyIndex = 0
        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            if (findShipByHull(engine, hullId) != null && hullId in ARC_PRODUCTION_CORE_HULLS) {
                manager.removeFromReserves(member)
                continue
            }

            val anchor = when {
                side == FleetSide.ENEMY -> {
                    val base = arcProductionAnchors.getValue("enemy_target")
                    Vector2f(base.x + enemyIndex++ * 170f, base.y)
                }

                hullId == ASTDArcProductionShipIds.HULL_XC_102 -> arcProductionAnchors.getValue(ASTDArcProductionShipIds.HULL_XC_102)
                hullId == ASTDArcProductionShipIds.HULL_XC_101 -> arcProductionAnchors.getValue(ASTDArcProductionShipIds.HULL_XC_101)
                hullId == ASTDArcProductionShipIds.HULL_XC_103 -> arcProductionAnchors.getValue(ASTDArcProductionShipIds.HULL_XC_103)
                else -> {
                    val base = if (allyIndex % 2 == 0) {
                        arcProductionAnchors.getValue("ally_frigate")
                    } else {
                        arcProductionAnchors.getValue("ally_destroyer")
                    }
                    Vector2f(base.x + (allyIndex / 2) * 150f, base.y)
                }
            }
            val facing = when {
                side == FleetSide.ENEMY -> 180f
                hullId == ASTDArcProductionShipIds.HULL_XC_103 -> 180f
                else -> 0f
            }
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = shouldPreserveArcProductionAI(side, hullId))
            if (side == FleetSide.PLAYER && hullId !in ARC_PRODUCTION_CORE_HULLS) allyIndex++
        }
    }

    private fun shouldPreserveArcProductionAI(side: FleetSide, hullId: String): Boolean =
        side == FleetSide.ENEMY || hullId == ASTDArcProductionShipIds.HULL_XC_101

    private fun arrangeArcProductionShips(engine: CombatEngineAPI) {
        val xc102 = findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_102)
        val xc101 = findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_101)
        val xc103 = findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_103)
        xc102?.let { stabilizeShip(it, arcProductionAnchors.getValue(ASTDArcProductionShipIds.HULL_XC_102), 0f, allowFire = false) }
        xc101?.let {
            stabilizeShip(
                it,
                arcProductionAnchors.getValue(ASTDArcProductionShipIds.HULL_XC_101),
                0f,
                allowFire = false,
                preserveAI = true
            )
        }
        xc103?.let { stabilizeShip(it, arcProductionAnchors.getValue(ASTDArcProductionShipIds.HULL_XC_103), 180f, allowFire = false) }

        engine.ships
            .filter {
                it.hullSpec?.hullId !in setOf(
                    ASTDArcProductionShipIds.HULL_XC_102,
                    ASTDArcProductionShipIds.HULL_XC_101,
                    ASTDArcProductionShipIds.HULL_XC_103
                )
            }
            .filter { it.owner == xc102?.owner || it.owner == xc101?.owner || it.owner == xc103?.owner }
            .forEachIndexed { index, ship ->
                val anchor = if (index % 2 == 0) arcProductionAnchors.getValue("ally_frigate") else arcProductionAnchors.getValue("ally_destroyer")
                stabilizeShip(ship, anchor, 0f, allowFire = false)
            }

        engine.ships
            .filter { ship -> ship.owner != 0 }
            .forEachIndexed { index, ship ->
                val base = arcProductionAnchors.getValue("enemy_target")
                val anchor = Vector2f(base.x + index * 170f, base.y)
                stabilizeShip(ship, anchor, 180f, allowFire = true, preserveAI = true)
                pressureXc101ForSystemAI(ship, xc101)
            }
    }

    private fun pressureXc101ForSystemAI(ship: ShipAPI, xc101: ShipAPI?) {
        if (xc101 == null || ship.owner == xc101.owner) return
        ship.shipTarget = xc101
        for (weapon in try {
            ship.allWeapons
        } catch (_: Throwable) {
            return
        }) {
            weapon.setForceFireOneFrame(true)
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployArcProductionReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeArcProductionShips(engine)

        val xc102 = findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_102)
        val xc101 = findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_101)
        val xc103 = findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_103)
        val telemetryShip = xc102 ?: xc101 ?: xc103
        telemetryShip?.let { engine.setPlayerShipExternal(it) }

        xc102?.system?.let { if (!it.isOn && ctx.elapsed > 0.8f) xc102.useSystem() }
        xc101?.shield?.let { if (!it.isOn) it.toggleOn() }
        xc103?.system?.let { if (!it.isOn && ctx.elapsed > 0.8f) xc103.useSystem() }

        val missingShips = arcProductionMissingShips(engine)
        val state = when {
            arcProductionEvidenceReady(engine) -> "Completed"
            missingShips.isNotEmpty() && ctx.elapsed > 8f -> {
                ctx.failureReason = "arc production ships missing: ${missingShips.joinToString(",")}"
                "Failed"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: arc_production_ships_vfx_tooltip/VFX tooltip evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, telemetryShip)
            ctx.writeTelemetry(state, telemetryShip, null)
        }
    }

    private fun arcProductionMissingShips(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.filter { findShipByHull(engine, it) == null }

    private fun arcProductionEvidenceReady(engine: CombatEngineAPI): Boolean {
        if (ctx.elapsed < 1.25f) return false
        return listOf(
            ASTDArcProductionVfx.TELEMETRY_XC_102_SHOCKWAVE_FRAMES,
            ASTDArcProductionVfx.TELEMETRY_XC_102_SHOCKWAVE_RADIUS,
            ASTDArcProductionVfx.TELEMETRY_XC_102_SHOCKWAVE_FLUX_PRESSURE,
            ASTDArcProductionVfx.TELEMETRY_XC_101_SHIELD_OPEN,
            ASTDArcProductionVfx.TELEMETRY_XC_101_SYSTEM_ACTIVE,
            ASTDArcProductionVfx.TELEMETRY_XC_101_SHIELD_ARC_EMISSIONS,
            ASTDArcProductionVfx.TELEMETRY_XC_103_SYSTEM_AFTERIMAGES,
        ).all { ASTDArcProductionVfx.counter(engine, it) > 0 }
    }

    private fun arcProductionTelemetryShip(engine: CombatEngineAPI): ShipAPI? =
        findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_102)

    private fun arcProductionDeployedShipIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.filter { hullId -> findShipByHull(engine, hullId) != null }

    private fun arcProductionDeployedVariantIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.mapNotNull { hullId -> findShipByHull(engine, hullId)?.variant?.hullVariantId }

    private fun arcProductionSourceVariantIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.mapNotNull { hullId ->
            val member = findShipByHull(engine, hullId)?.fleetMember
            ARC_PRODUCTION_STANDARD_VARIANTS[hullId] ?: member?.variant?.hullVariantId
        }

    private fun plasmaEnemyPressureShips(engine: CombatEngineAPI, xc101: ShipAPI?): Int {
        if (xc101 == null) return 0
        return engine.ships.count { ship ->
            ship.owner != xc101.owner &&
                    ship.isAlive &&
                    !ship.isHulk &&
                    distanceSquared(ship.location, xc101.location) <= PLASMA_AI_PRESSURE_RANGE * PLASMA_AI_PRESSURE_RANGE
        }
    }

    private fun plasmaEnemyTargetingShips(engine: CombatEngineAPI, xc101: ShipAPI?): Int {
        if (xc101 == null) return 0
        return engine.ships.count { ship ->
            ship.owner != xc101.owner && ship.shipTarget === xc101
        }
    }

    private fun plasmaEnemyFiringWeapons(engine: CombatEngineAPI, xc101: ShipAPI?): Int {
        if (xc101 == null) return 0
        return engine.ships
            .filter { ship -> ship.owner != xc101.owner }
            .sumOf { ship ->
                try {
                    ship.allWeapons.count { weapon -> weapon.isFiring }
                } catch (_: Throwable) {
                    0
                }
            }
    }

    private fun plasmaEnemyProjectiles(engine: CombatEngineAPI, xc101: ShipAPI?): Int {
        if (xc101 == null) return 0
        return engine.projectiles.count { projectile ->
            val damaging = projectile as? DamagingProjectileAPI ?: return@count false
            val source = damaging.source ?: return@count false
            source.owner != xc101.owner &&
                    !damaging.isExpired &&
                    distanceSquared(damaging.location, xc101.location) <= PLASMA_AI_PRESSURE_RANGE * PLASMA_AI_PRESSURE_RANGE
        }
    }

    private fun plasmaAIFlags(ship: ShipAPI?): List<String> {
        val flags = ship?.shipAI?.aiFlags ?: ship?.aiFlags ?: return emptyList()
        return ShipwideAIFlags.AIFlags.values()
            .filter { flag -> safeBool { flags.hasFlag(flag) } }
            .map { it.name }
            .sorted()
    }

    private fun plasmaAIFlag(ship: ShipAPI?, flag: ShipwideAIFlags.AIFlags): Boolean {
        val flags = ship?.shipAI?.aiFlags ?: ship?.aiFlags ?: return false
        return safeBool { flags.hasFlag(flag) }
    }

    private fun plasmaAIFlagTarget(ship: ShipAPI?, flag: ShipwideAIFlags.AIFlags): PlasmaTargetDiagnostic {
        val flags = ship?.shipAI?.aiFlags ?: ship?.aiFlags
        ?: return PlasmaTargetDiagnostic(null, "missing AI flags for ${flag.name}")
        val custom = try {
            flags.getCustom(flag)
        } catch (e: Throwable) {
            return PlasmaTargetDiagnostic(null, "getCustom(${flag.name}) failed: ${e.javaClass.name}: ${e.message}")
        } ?: return PlasmaTargetDiagnostic(null, null)

        val target = extractShipFromAIFlagCustom(custom)
        return PlasmaTargetDiagnostic(
            ship = target,
            error = if (target == null) {
                "unresolved ${flag.name} custom target: ${custom.javaClass.name}"
            } else {
                null
            },
        )
    }

    private fun extractShipFromAIFlagCustom(custom: Any): ShipAPI? {
        if (custom is ShipAPI) return custom
        return null
    }

    private fun plasmaRuntimeSystemAI(ship: ShipAPI?): PlasmaSystemAIDiagnostic {
        val ai = ship?.shipAI ?: return PlasmaSystemAIDiagnostic(null, "missing ship AI")
        return PlasmaSystemAIDiagnostic(
            className = null,
            error = "systemAI is private runtime state on ${ai.javaClass.name}; script sandbox forbids reflection",
        )
    }

    override fun renderCapture(ctx: AutomationCombatContext) {
        if (!ctx.completed || ctx.visualFramesWritten >= 3) return
        if (ctx.visualFramesWritten > 0 && ctx.elapsed - ctx.lastVisualFrameAt < 0.18f) return
        lockArcProductionCamera(ctx.engine)
        arrangeArcProductionShips(ctx.engine)
        ctx.lastVisualFrameAt = ctx.elapsed
        ctx.visualFramesWritten++
        ctx.writeDiagnostics("Completed")
        ctx.writeTelemetry("Completed", arcProductionTelemetryShip(ctx.engine), null)
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val xc101 = findShipByHull(engine, ASTDArcProductionShipIds.HULL_XC_101)
        val plasmaSystem = xc101?.system
        val plasmaSpec = plasmaSystem?.specAPI
        val plasmaShield = xc101?.shield
        val plasmaSystemAI = plasmaRuntimeSystemAI(xc101)
        val plasmaBiggestThreat = plasmaAIFlagTarget(xc101, ShipwideAIFlags.AIFlags.BIGGEST_THREAT)
        val plasmaSystemTarget = plasmaAIFlagTarget(xc101, ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)
        val plasmaManeuverTarget = plasmaAIFlagTarget(xc101, ShipwideAIFlags.AIFlags.MANEUVER_TARGET)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": 0,")
        json.appendLine("  \"runtimeLastProjectileSpecId\": null,")
        json.appendLine("  \"arcProductionMissingShips\": ${jsonStringList(arcProductionMissingShips(engine))},")
        json.appendLine("  \"arcProductionDeployedShipIds\": ${jsonStringList(arcProductionDeployedShipIds(engine))},")
        json.appendLine("  \"arcProductionDeployedVariantIds\": ${jsonStringList(arcProductionDeployedVariantIds(engine))},")
        json.appendLine("  \"arcProductionSourceVariantIds\": ${jsonStringList(arcProductionSourceVariantIds(engine))},")
        json.appendLine("  \"arcProductionPlayerReserves\": ${engine.getFleetManager(FleetSide.PLAYER).reservesCopy.size},")
        json.appendLine("  \"arcProductionEnemyReserves\": ${engine.getFleetManager(FleetSide.ENEMY).reservesCopy.size},")
        json.appendLine(
            "  \"xc103SystemState\": ${
                jsonString(
                    findShipByHull(
                        engine,
                        ASTDArcProductionShipIds.HULL_XC_103
                    )?.system?.state?.name
                )
            },"
        )
        json.appendLine("  \"xc101SystemId\": ${jsonString(plasmaSystem?.id)},")
        json.appendLine("  \"xc101SystemState\": ${jsonString(plasmaSystem?.state?.name)},")
        json.appendLine("  \"xc101SystemCanBeActivated\": ${safeBool { plasmaSystem?.canBeActivated() == true }},")
        json.appendLine("  \"xc101SystemEffectLevel\": ${formatFloat(plasmaSystem?.effectLevel ?: -1f)},")
        json.appendLine("  \"xc101ShipAI\": ${jsonString(xc101?.shipAI?.javaClass?.name)},")
        json.appendLine("  \"xc101FluxLevel\": ${formatFloat(xc101?.fluxLevel ?: -1f)},")
        json.appendLine("  \"xc101CurrFlux\": ${formatFloat(xc101?.currFlux ?: -1f)},")
        json.appendLine("  \"xc101MaxFlux\": ${formatFloat(xc101?.maxFlux ?: -1f)},")
        json.appendLine("  \"xc101HardFlux\": ${formatFloat(xc101?.fluxTracker?.hardFlux ?: -1f)},")
        json.appendLine("  \"xc101HardFluxLevel\": ${formatFloat(xc101?.hardFluxLevel ?: -1f)},")
        json.appendLine("  \"xc101SinceLastDamageTaken\": ${formatFloat(xc101?.sinceLastDamageTaken ?: -1f)},")
        json.appendLine("  \"xc101OverloadedOrVenting\": ${xc101?.fluxTracker?.isOverloadedOrVenting ?: false},")
        json.appendLine("  \"xc101ShieldOn\": ${plasmaShield?.isOn ?: false},")
        json.appendLine("  \"xc101ShieldActiveArc\": ${formatFloat(plasmaShield?.activeArc ?: -1f)},")
        json.appendLine("  \"xc101AIFlags\": ${jsonStringList(plasmaAIFlags(xc101))},")
        json.appendLine("  \"xc101AIFlagIncomingDamage\": ${plasmaAIFlag(xc101, ShipwideAIFlags.AIFlags.HAS_INCOMING_DAMAGE)},")
        json.appendLine("  \"xc101AIFlagCriticalDpsDanger\": ${plasmaAIFlag(xc101, ShipwideAIFlags.AIFlags.IN_CRITICAL_DPS_DANGER)},")
        json.appendLine("  \"xc101AIFlagKeepShieldsOn\": ${plasmaAIFlag(xc101, ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON)},")
        json.appendLine("  \"xc101VanillaSystemAI\": ${jsonString(plasmaSystemAI.className)},")
        json.appendLine("  \"xc101VanillaSystemAIError\": ${jsonString(plasmaSystemAI.error)},")
        json.appendLine("  \"xc101AIFlagBiggestThreatTargetHullId\": ${jsonString(plasmaBiggestThreat.ship?.hullSpec?.hullId)},")
        json.appendLine("  \"xc101AIFlagBiggestThreatTargetVariantId\": ${jsonString(plasmaBiggestThreat.ship?.variant?.hullVariantId)},")
        json.appendLine("  \"xc101AIFlagTargetForSystemHullId\": ${jsonString(plasmaSystemTarget.ship?.hullSpec?.hullId)},")
        json.appendLine("  \"xc101AIFlagTargetForSystemVariantId\": ${jsonString(plasmaSystemTarget.ship?.variant?.hullVariantId)},")
        json.appendLine("  \"xc101AIFlagManeuverTargetHullId\": ${jsonString(plasmaManeuverTarget.ship?.hullSpec?.hullId)},")
        json.appendLine("  \"xc101AIFlagManeuverTargetVariantId\": ${jsonString(plasmaManeuverTarget.ship?.variant?.hullVariantId)},")
        json.appendLine("  \"xc101EnemyPressureShips\": ${plasmaEnemyPressureShips(engine, xc101)},")
        json.appendLine("  \"xc101EnemyTargetingShips\": ${plasmaEnemyTargetingShips(engine, xc101)},")
        json.appendLine("  \"xc101EnemyFiringWeapons\": ${plasmaEnemyFiringWeapons(engine, xc101)},")
        json.appendLine("  \"xc101EnemyProjectiles\": ${plasmaEnemyProjectiles(engine, xc101)},")
        json.appendLine("  \"xc101SystemSpecAiScript\": ${jsonString(plasmaSpec?.aiScript?.javaClass?.name ?: plasmaSpec?.aiScriptClassName)},")
        json.appendLine("  \"xc101SystemSpecFpsBaseCap\": ${formatFloat(plasmaSpec?.fluxPerSecondBaseCap ?: -1f)},")
        json.appendLine("  \"xc101SystemSpecToggle\": ${plasmaSpec?.isToggle ?: false},")
        json.appendLine("  \"xc101SystemSpecFiringAllowed\": ${plasmaSpec?.isFiringAllowed ?: false},")
        json.appendLine("  \"xc101SystemSpecTags\": ${jsonStringList(plasmaSpec?.tags?.toList()?.sorted() ?: emptyList())},")
    }

    private data class PlasmaTargetDiagnostic(
        val ship: ShipAPI?,
        val error: String?,
    )

    private data class PlasmaSystemAIDiagnostic(
        val className: String?,
        val error: String?,
    )

    private companion object {
        private val ARC_PRODUCTION_CORE_HULLS = listOf(
            ASTDArcProductionShipIds.HULL_XC_102,
            ASTDArcProductionShipIds.HULL_XC_101,
            ASTDArcProductionShipIds.HULL_XC_103,
        )
        private val ARC_PRODUCTION_STANDARD_VARIANTS = mapOf(
            ASTDArcProductionShipIds.HULL_XC_102 to "astd_xc_102_Standard",
            ASTDArcProductionShipIds.HULL_XC_101 to "astd_xc_101_Standard",
            ASTDArcProductionShipIds.HULL_XC_103 to "astd_xc_103_Standard",
        )
        private const val PLASMA_AI_PRESSURE_RANGE = 1800f
    }
}
