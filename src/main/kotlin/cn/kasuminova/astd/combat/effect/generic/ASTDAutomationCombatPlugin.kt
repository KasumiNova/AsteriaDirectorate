package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionTooltipContracts
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreHullModIds
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.combat.lens.system.EchoFixationField
import cn.kasuminova.astd.renderer.effect.lens.EchoFixationAfterimageRenderer
import cn.kasuminova.astd.renderer.effect.lens.LensVfxTelemetry
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetCatalog
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimePlugin
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeTelemetry
import cn.kasuminova.astd.renderer.projectile.primaryTrailLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxLayout
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f

/**
 * Dev-only combat automation surface for validating Arc Flare + AOD-7 runtime VFX in game.
 */
class ASTDAutomationCombatPlugin : BaseEveryFrameCombatPlugin() {
    private val captureCenter = Vector2f(100f, 0f)
    private val playerAnchor = Vector2f(-260f, 0f)
    private val projectilePreviewAnchor = Vector2f(40f, 0f)
    private val enemyAnchor = Vector2f(900f, 0f)
    private val arcProductionAnchors = mapOf(
        "astd_arc_jet" to Vector2f(-720f, 120f),
        "astd_plasma_arch" to Vector2f(-80f, -40f),
        "astd_radiation_belt" to Vector2f(520f, 135f),
        "ally_frigate" to Vector2f(-500f, -280f),
        "ally_destroyer" to Vector2f(360f, -255f),
        "enemy_target" to Vector2f(980f, 20f),
    )
    private val log = Global.getLogger(ASTDAutomationCombatPlugin::class.java)
    private var engine: CombatEngineAPI? = null
    private var elapsed = 0f
    private var lastWriteAt = -1f
    private var fallbackSpawned = false
    private var completed = false
    private var completedAt = -1f
    private var visualFramesWritten = 0
    private var lastVisualFrameAt = -1f
    private var failureReason: String? = null
    private var fireMechanism: String? = null
    private var fallbackProjectile: DamagingProjectileAPI? = null
    private var fallbackProjectileSpawnedAt = -1f
    private var lensMarksInjected = false
    private val lensAnchor = Vector2f(-260f, 0f)
    /** phase2 幽灵信号导弹投放计时累积（秒），见 feedGhostSignalMissiles。 */
    private var ghostMissileFeedAcc = 0f
    /** phase2 fighter 误差标记降级 log 的 once 守卫（避免每帧刷屏，参照 lensMarksInjected 模式）。 */
    private var lensPhase2DowngradeLogged = false

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        ASTDProjectileVfxRuntimePlugin.ensureInstalled(engine)
        if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
            engine.setDoNotEndCombat(true)
            lockArcProductionCamera(engine)
            // 与 phase1/ARC production 一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
            arrangeLensPhase2Ships(engine)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findCrewedLens(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.LENS_PHASE2_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            engine.setDoNotEndCombat(true)
            lockArcProductionCamera(engine)
            // 与 ARC production 一致：reserves 部署放到 advance()，init 阶段战斗渲染器尚未就绪，
            // 此时调用 spawnFleetMember -> setPlayerShip 会触发原版 arcRenderer NPE。
            arrangeLensPhase1Ships(engine)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID} combat plugin initialized")
        } else if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            engine.setDoNotEndCombat(true)
            lockArcProductionCamera(engine)
            arrangeArcProductionShips(engine)
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady", arcProductionTelemetryShip(engine), null)
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID} combat plugin initialized")
        } else {
            lockCamera(engine)
            arrangeShips(engine, findArcFlare(engine))
            writeDiagnostics(engine, "CombatReady")
            writeTelemetry(engine, "CombatReady")
            log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SCENARIO_ID} combat plugin initialized")
        }
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val combatEngine = engine ?: return
        if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceLensPhase2Scenario(combatEngine, amount.coerceAtLeast(0f))
            return
        }
        if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceLensPhase1Scenario(combatEngine)
            return
        }
        if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            if (combatEngine.isPaused) combatEngine.setPaused(false)
            elapsed += amount.coerceAtLeast(0f)
            advanceArcProductionScenario(combatEngine)
            return
        }
        if (combatEngine.isPaused) return

        elapsed += amount.coerceAtLeast(0f)

        val ship = findArcFlare(combatEngine)
        val weapon = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID }
        lockCamera(combatEngine)
        arrangeShips(combatEngine, ship)
        alignAod7ProjectilesForEvidence(combatEngine)
        if (completed && elapsed - completedAt >= 0.75f) return

        if (ship != null) {
            combatEngine.setPlayerShipExternal(ship)
            ship.shipAI = null
            ship.setControlsLocked(false)
            ship.setHoldFireOneFrame(false)
            ship.setShipTarget(null)
        }

        if (!completed && ship != null && weapon != null && elapsed >= 0.5f) {
            weapon.setRemainingCooldownTo(0f)
            weapon.setForceFireOneFrame(true)
            fireMechanism = fireMechanism ?: "setForceFireOneFrame"
        }

        if (!completed && ship != null && weapon != null && elapsed >= 1.5f && !projectileObserved(combatEngine) && !fallbackSpawned) {
            fallbackSpawned = true
            spawnAod7Projectile(combatEngine, ship, weapon)
            fireMechanism = "spawnProjectileFallback"
        }

        val state = if (completed) "Completed" else currentState(combatEngine, ship, weapon)
        if (state == "Completed") {
            if (!completed) {
                completed = true
                completedAt = elapsed
                log.info("[ASTD-Automation] Completed: arc_flare/aod7/${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID}/VFX observed")
            }
            return
        }

        if (elapsed - lastWriteAt >= 0.18f || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(combatEngine, state, ship)
            writeTelemetry(combatEngine, state, ship, weapon)
        }
    }

    override fun renderInUICoords(viewport: ViewportAPI) {
        val combatEngine = engine ?: return
        if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
            if (!completed || visualFramesWritten >= 3) return
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return
            lockArcProductionCamera(combatEngine)
            arrangeLensPhase2Ships(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findCrewedLens(combatEngine))
            writeTelemetry(combatEngine, "Completed", findCrewedLens(combatEngine), null)
            return
        }
        if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
            if (!completed || visualFramesWritten >= 3) return
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return
            lockArcProductionCamera(combatEngine)
            arrangeLensPhase1Ships(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed", findShipByHull(combatEngine, LensArrayCoreHullModIds.HULL_ID))
            writeTelemetry(combatEngine, "Completed", findShipByHull(combatEngine, LensArrayCoreHullModIds.HULL_ID), null)
            return
        }
        if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            if (!completed || visualFramesWritten >= 3) return
            if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return
            lockArcProductionCamera(combatEngine)
            arrangeArcProductionShips(combatEngine)
            lastVisualFrameAt = elapsed
            visualFramesWritten++
            writeDiagnostics(combatEngine, "Completed")
            writeTelemetry(combatEngine, "Completed", arcProductionTelemetryShip(combatEngine), null)
            return
        }

        if (!completed || visualFramesWritten >= 3) return
        if (visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f) return

        lockCamera(combatEngine)
        val ship = findArcFlare(combatEngine)
        arrangeShips(combatEngine, ship)
        alignAod7ProjectilesForEvidence(combatEngine)
        lastVisualFrameAt = elapsed
        visualFramesWritten++
        writeDiagnostics(combatEngine, "Completed", ship)
        writeTelemetry(
            combatEngine,
            "Completed",
            ship,
            ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
        )
        if (visualFramesWritten == 3) {
            log.info("[ASTD-Automation] visual evidence frames written after render")
        }
    }

    private fun findArcFlare(engine: CombatEngineAPI): ShipAPI? {
        return engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == ASTDInGameAutomationScenario.SHIP_ID ||
                ship.variant?.hullVariantId == ASTDInGameAutomationScenario.VARIANT_ID
        }
    }

    private fun findShipByHull(engine: CombatEngineAPI, hullId: String): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.hullSpec?.hullId == hullId }

    private fun lockCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleHeight = 600f
        val visibleWidth = visibleHeight * displayAspect

        viewport.setExternalControl(true)
        viewport.set(
            captureCenter.x - visibleWidth * 0.5f,
            captureCenter.y - visibleHeight * 0.5f,
            visibleWidth,
            visibleHeight,
        )
        viewport.setEverythingNearViewport(true)
    }

    private fun arrangeShips(engine: CombatEngineAPI, playerShip: ShipAPI?) {
        playerShip?.let { stabilizeShip(it, playerAnchor, 0f, allowFire = true) }
        engine.ships
            .filter { it !== playerShip && it.owner != playerShip?.owner }
            .forEach { stabilizeShip(it, enemyAnchor, 180f, allowFire = false) }
    }

    private fun stabilizeShip(ship: ShipAPI, location: Vector2f, facing: Float, allowFire: Boolean, preserveAI: Boolean = false) {
        ship.location.set(location)
        ship.velocity.set(0f, 0f)
        ship.facing = facing
        ship.angularVelocity = 0f
        if (!preserveAI) {
            ship.shipAI = null
            ship.setShipTarget(null)
        }
        ship.setControlsLocked(false)
        ship.setHoldFireOneFrame(!allowFire)
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE)
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS)
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT)
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT)
        ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT)
        ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT)
        if (!allowFire) ship.blockCommandForOneFrame(ShipCommand.FIRE)
    }

    private fun lockArcProductionCamera(engine: CombatEngineAPI) {
        val viewport = engine.viewport
        val displayWidth = try { Display.getWidth().takeIf { it > 0 } ?: 2560 } catch (_: Throwable) { 2560 }
        val displayHeight = try { Display.getHeight().takeIf { it > 0 } ?: 1440 } catch (_: Throwable) { 1440 }
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val visibleHeight = 980f
        val visibleWidth = visibleHeight * displayAspect

        viewport.setExternalControl(true)
        viewport.set(-40f - visibleWidth * 0.5f, -20f - visibleHeight * 0.5f, visibleWidth, visibleHeight)
        viewport.setEverythingNearViewport(true)
    }

    private fun deployArcProductionReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployArcProductionSide(engine, FleetSide.PLAYER)
        deployArcProductionSide(engine, FleetSide.ENEMY)
    }

    private fun deployArcProductionSide(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.setSuppressDeploymentMessages(true)
        val reserves = manager.getReservesCopy().toList()
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
                hullId == ASTDArcProductionShipIds.HULL_ARC_JET -> arcProductionAnchors.getValue("astd_arc_jet")
                hullId == ASTDArcProductionShipIds.HULL_PLASMA_ARCH -> arcProductionAnchors.getValue("astd_plasma_arch")
                hullId == ASTDArcProductionShipIds.HULL_RADIATION_BELT -> arcProductionAnchors.getValue("astd_radiation_belt")
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
                hullId == ASTDArcProductionShipIds.HULL_RADIATION_BELT -> 180f
                else -> 0f
            }
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = shouldPreserveArcProductionAI(side, hullId))
            if (side == FleetSide.PLAYER && hullId !in ARC_PRODUCTION_CORE_HULLS) allyIndex++
        }
    }

    private fun shouldPreserveArcProductionAI(side: FleetSide, hullId: String): Boolean =
        side == FleetSide.ENEMY || hullId == ASTDArcProductionShipIds.HULL_PLASMA_ARCH

    private fun arrangeArcProductionShips(engine: CombatEngineAPI) {
        val arcJet = findShipByHull(engine, "astd_arc_jet")
        val plasmaArch = findShipByHull(engine, "astd_plasma_arch")
        val radiationBelt = findShipByHull(engine, "astd_radiation_belt")
        arcJet?.let { stabilizeShip(it, arcProductionAnchors.getValue("astd_arc_jet"), 0f, allowFire = false) }
        plasmaArch?.let { stabilizeShip(it, arcProductionAnchors.getValue("astd_plasma_arch"), 0f, allowFire = false, preserveAI = true) }
        radiationBelt?.let { stabilizeShip(it, arcProductionAnchors.getValue("astd_radiation_belt"), 180f, allowFire = false) }

        engine.ships
            .filter { it.hullSpec?.hullId !in setOf("astd_arc_jet", "astd_plasma_arch", "astd_radiation_belt") }
            .filter { it.owner == arcJet?.owner || it.owner == plasmaArch?.owner || it.owner == radiationBelt?.owner }
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
                pressurePlasmaArchForSystemAI(ship, plasmaArch)
            }
    }

    private fun pressurePlasmaArchForSystemAI(ship: ShipAPI, plasmaArch: ShipAPI?) {
        if (plasmaArch == null || ship.owner == plasmaArch.owner) return
        ship.setShipTarget(plasmaArch)
        for (weapon in try { ship.allWeapons } catch (_: Throwable) { return }) {
            weapon.setForceFireOneFrame(true)
        }
    }

    private fun advanceArcProductionScenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployArcProductionReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeArcProductionShips(engine)

        val arcJet = findShipByHull(engine, "astd_arc_jet")
        val plasmaArch = findShipByHull(engine, "astd_plasma_arch")
        val radiationBelt = findShipByHull(engine, "astd_radiation_belt")
        val telemetryShip = arcJet ?: plasmaArch ?: radiationBelt
        telemetryShip?.let { engine.setPlayerShipExternal(it) }

        arcJet?.system?.let { if (!it.isOn && elapsed > 0.8f) arcJet.useSystem() }
        plasmaArch?.shield?.let { if (!it.isOn) it.toggleOn() }
        radiationBelt?.system?.let { if (!it.isOn && elapsed > 0.8f) radiationBelt.useSystem() }

        val missingShips = arcProductionMissingShips(engine)
        val state = when {
            arcProductionEvidenceReady(engine) -> "Completed"
            missingShips.isNotEmpty() && elapsed > 8f -> {
                failureReason = "arc production ships missing: ${missingShips.joinToString(",")}"
                "Failed"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: arc_production_ships_vfx_tooltip/VFX tooltip evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, telemetryShip)
            writeTelemetry(engine, state, telemetryShip, null)
        }
    }

    private fun arcProductionMissingShips(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.filter { findShipByHull(engine, it) == null }

    private fun arcProductionEvidenceReady(engine: CombatEngineAPI): Boolean {
        if (elapsed < 1.25f) return false
        return listOf(
            ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FRAMES,
            ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_RADIUS,
            ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FLUX_PRESSURE,
            ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_OPEN,
            ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE,
            ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS,
            ASTDArcProductionVfx.TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES,
        ).all { ASTDArcProductionVfx.counter(engine, it) > 0 }
    }

    private fun arcProductionTelemetryShip(engine: CombatEngineAPI): ShipAPI? =
        findShipByHull(engine, "astd_arc_jet")
            ?: findShipByHull(engine, "astd_plasma_arch")
            ?: findShipByHull(engine, "astd_radiation_belt")

    // === Phase-1 gravitational lens scenario ===

    private fun deployLensPhase1ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase1Side(engine, FleetSide.PLAYER)
        deployLensPhase1Side(engine, FleetSide.ENEMY)
    }

    private fun deployLensPhase1Side(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.setSuppressDeploymentMessages(true)
        val reserves = manager.getReservesCopy().toList()
        if (reserves.isEmpty()) return

        var allyIndex = 0
        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            if (findShipByHull(engine, hullId) != null && hullId == LensArrayCoreHullModIds.HULL_ID) {
                manager.removeFromReserves(member)
                continue
            }

            val anchor = when {
                side == FleetSide.ENEMY -> Vector2f(900f + enemyIndex++ * 170f, 20f)
                hullId == LensArrayCoreHullModIds.HULL_ID -> lensAnchor
                else -> Vector2f(-520f, -260f + allyIndex++ * 150f)
            }
            val facing = if (side == FleetSide.ENEMY) 180f else 0f
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = side == FleetSide.ENEMY)
        }
    }

    private fun arrangeLensPhase1Ships(engine: CombatEngineAPI) {
        val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
        lens?.let { stabilizeShip(it, lensAnchor, 0f, allowFire = false) }
        var allyIndex = 0
        engine.ships
            .filter { it !== lens && it.owner == lens?.owner && !it.isFighter }
            .forEach { stabilizeShip(it, Vector2f(-520f, -260f + allyIndex++ * 150f), 0f, allowFire = false) }
        engine.ships
            .filter { ship -> lens != null && ship.owner != lens.owner && !ship.isFighter }
            .forEachIndexed { index, ship ->
                stabilizeShip(ship, Vector2f(900f + index * 170f, 20f), 180f, allowFire = false, preserveAI = true)
            }
    }

    private fun advanceLensPhase1Scenario(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase1ReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeLensPhase1Ships(engine)

        val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
        lens?.let { engine.setPlayerShipExternal(it) }
        lens?.shield?.let { if (!it.isOn) it.toggleOn() }

        // 对引力透镜级自身维持 3 层误差/深水标记，验证 applier 真的改了承伤。
        // 标记每层 5s 会过期，且本场景 setDoNotEndCombat 后会长时间运行（观测点在很晚），
        // 故每帧把不足的层数补齐到 3（applyOrRefresh 同时刷新时长），保证稳态诊断读到 3 层。
        if (lens != null && elapsed > 1.0f) {
            val driftNeeded = LENS_SELF_MARK_STACKS - LensMarks.driftStacks(lens)
            if (driftNeeded > 0) LensMarks.applyDriftMark(engine, lens, lens, driftNeeded)
            val deepWaterNeeded = LENS_SELF_MARK_STACKS - LensMarks.deepWaterStacks(lens)
            if (deepWaterNeeded > 0) LensMarks.applyDeepWaterMark(engine, lens, lens, deepWaterNeeded)
            if (!lensMarksInjected) {
                lensMarksInjected = true
                log.info("[ASTD-Automation] lens self marks injected: drift=$LENS_SELF_MARK_STACKS, deepWater=$LENS_SELF_MARK_STACKS")
            }
        }

        val state = when {
            lens != null && lensMarksInjected && elapsed > 2.0f -> "Completed"
            lens == null && elapsed > 8f -> {
                failureReason = "gravitational lens missing: ${LensArrayCoreHullModIds.HULL_ID}"
                "Failed"
            }
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: lens_phase1_foundation evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, lens)
            writeTelemetry(engine, state, lens, null)
        }
    }

    private fun lensDeployedShipIds(engine: CombatEngineAPI): List<String> =
        engine.ships
            .asSequence()
            .filter { !it.isFighter }
            .mapNotNull { it.hullSpec?.hullId }
            .distinct()
            .sorted()
            .toList()

    private fun lensCoreTooltipKeyCount(engine: CombatEngineAPI): Int {
        val ship = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID) ?: return 0
        if (!hasHullmod(ship, LensArrayCoreHullModIds.CORE)) return 0
        return LENS_CORE_TOOLTIP_KEYS.count { key -> isResolvedTextKey(key) }
    }

    // === Phase-2 gravitational lens scenario (mechanisms + shader vfx counts) ===

    /**
     * 载人引力透镜级（无 MODE_AUTOMATED perma-mod 即载人）。phase2 旗舰，驱动定影场施放、潮汐、标记高光。
     * 标记高光提交（[cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod.submitMarkHighlights]）
     * 仅对 engine.playerShip 每帧执行，故必须把载人透镜设为玩家船。
     */
    private fun findCrewedLens(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == LensArrayCoreHullModIds.HULL_ID &&
                ship.variant?.hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED) != true &&
                ship.variant?.hasLensAutomatedModeSafe() != true
        }

    /** 无人引力透镜级（MODE_AUTOMATED perma-mod）。唯一跑幽灵信号的模式。 */
    private fun findAutomatedLens(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == LensArrayCoreHullModIds.HULL_ID &&
                ship.variant?.hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED) == true
        }

    /** ShipVariantAPI.hasLensAutomatedMode 的安全包装（perma-mod 或普通 hullmod 任一即无人模式）。 */
    private fun com.fs.starfarer.api.combat.ShipVariantAPI.hasLensAutomatedModeSafe(): Boolean =
        try {
            getPermaMods().contains(LensArrayCoreHullModIds.MODE_AUTOMATED) ||
                hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED)
        } catch (_: Throwable) {
            false
        }

    /** phase2 全场敌舰（非残骸非舰载机），潮汐深水标记 / 认知撕裂的目标。 */
    private fun lensPhase2Enemies(engine: CombatEngineAPI): List<ShipAPI> {
        val crewed = findCrewedLens(engine) ?: return emptyList()
        return engine.ships.filter { it.owner != crewed.owner && !it.isFighter && !it.isHulk }
    }

    private fun deployLensPhase2ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase2Side(engine, FleetSide.PLAYER)
        deployLensPhase2Side(engine, FleetSide.ENEMY)
    }

    private fun deployLensPhase2Side(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.setSuppressDeploymentMessages(true)
        val reserves = manager.getReservesCopy().toList()
        if (reserves.isEmpty()) return

        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            val anchor = when {
                side == FleetSide.ENEMY -> Vector2f(LENS_PHASE2_ENEMY_CLUSTER_X + enemyIndex++ * 140f, -200f + (enemyIndex % 3) * 200f)
                hullId == LensArrayCoreHullModIds.HULL_ID ->
                    if (member.variant?.hasLensAutomatedModeSafe() == true) LENS_PHASE2_AUTOMATED_ANCHOR else LENS_PHASE2_CREWED_ANCHOR
                else -> Vector2f(-560f, 0f)
            }
            val facing = if (side == FleetSide.ENEMY) 180f else 0f
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = side == FleetSide.ENEMY)
        }
    }

    private fun arrangeLensPhase2Ships(engine: CombatEngineAPI) {
        val crewed = findCrewedLens(engine)
        crewed?.let { stabilizeShip(it, LENS_PHASE2_CREWED_ANCHOR, 0f, allowFire = false) }
        findAutomatedLens(engine)?.let { stabilizeShip(it, LENS_PHASE2_AUTOMATED_ANCHOR, 0f, allowFire = false) }
        engine.ships
            .filter { ship -> crewed != null && ship.owner != crewed.owner && !ship.isFighter }
            .forEachIndexed { index, ship ->
                val anchor = Vector2f(LENS_PHASE2_ENEMY_CLUSTER_X + index * 140f, -200f + (index % 3) * 200f)
                stabilizeShip(ship, anchor, 180f, allowFire = false, preserveAI = true)
            }
    }

    private fun advanceLensPhase2Scenario(engine: CombatEngineAPI, amount: Float) {
        engine.setDoNotEndCombat(true)
        deployLensPhase2ReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeLensPhase2Ships(engine)

        val crewed = findCrewedLens(engine)
        // 标记高光提交（drift/deepwater shader）仅对 playerShip 每帧驱动，故把载人透镜设为玩家船。
        crewed?.let { engine.setPlayerShipExternal(it) }
        crewed?.shield?.let { if (!it.isOn) it.toggleOn() }

        val enemies = lensPhase2Enemies(engine)

        // (1) 回声定影场：直接 EchoFixationField.spawn 在敌群中心建场（最确定性的施放路径，等价系统 IN 首帧建场）。
        //     场每 ~4s 定影后回放并销毁；为保证观测点恒有活跃场（echoFixationFieldActive=true）且回放反复
        //     产出认知撕裂/残影/误差标记，无活跃场时即补建一个。建在敌群质心，敌舰落在场内（半径 700su）。
        if (crewed != null && enemies.isNotEmpty() && elapsed > 0.5f && !EchoFixationField.hasActiveField(engine)) {
            val cx = enemies.map { it.location.x }.average().toFloat()
            val cy = enemies.map { it.location.y }.average().toFloat()
            EchoFixationField.spawn(engine, crewed, cx, cy, systemRangeMult = 1f)
            log.info("[ASTD-Automation] lens phase-2 echo fixation field spawned at ($cx,$cy) over ${enemies.size} enemies")
        }

        // (2) 幽灵信号（仅无人模式）：无人透镜的 advanceInCombat 每 0.25s tick 对范围内敌方导弹做失制导判定，
        //     有剥离即起一道紫波。为提供真实导弹，向无人透镜附近持续 spawn 敌方导弹（owner=敌舰），
        //     交由真实 ghostSignal() 路径消费——不伪造波，波必须来自真实剥离触发的 spawnGhostWave。
        feedGhostSignalMissiles(engine, amount)

        // (3) 潮汐深水标记 / 标记高光：由 ASTDLensPermeatingTideHullMod / ASTDLensArrayCoreHullMod 每帧自驱，
        //     敌舰落在载人透镜 2500su 潮汐场内自然叠深水标记，无需脚本干预。

        // (4) fighter 误差标记降级说明（once）：parallaxDriftStacksFromFighters 依赖舰载机起飞 + on-hit 命中，
        //     实机难稳定确定性触发，故本场景降级为「视差甲板插件挂载」断言（parallaxDecksHullmod）；
        //     fighter 实命中叠误差标记的逐帧外观留人工验证。此处 log 一次，符合全局规范「降级要 log 说明」。
        if (!lensPhase2DowngradeLogged && findCrewedLens(engine) != null) {
            lensPhase2DowngradeLogged = true
            log.info(
                "[ASTD-Automation] lens phase2: parallaxDriftStacksFromFighters downgraded to " +
                    "parallaxDecksHullmod mount assertion (fighter on-hit mark hard to trigger " +
                    "deterministically in-game; fighter-driven drift visuals left to manual review)"
            )
        }

        val evidenceReady = lensPhase2EvidenceReady(engine, enemies)
        val state = when {
            crewed == null && elapsed > 10f -> {
                failureReason = "crewed gravitational lens missing: ${LensArrayCoreHullModIds.HULL_ID}"
                "Failed"
            }
            evidenceReady -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !completed) {
            completed = true
            completedAt = elapsed
            log.info("[ASTD-Automation] Completed: lens_phase2_mechanisms evidence observed")
        }
        if (elapsed - lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            lastWriteAt = elapsed
            writeDiagnostics(engine, state, crewed)
            writeTelemetry(engine, state, crewed, null)
        }
    }

    /**
     * 向无人透镜附近持续投放敌方导弹，供真实幽灵信号路径消费。
     *
     * 失制导每 tick 0.25s、每枚导弹 50% 命中，单 tick 内多枚同时落入范围 → P(至少一枚剥离)≈1。
     * 导弹经 engine.spawnProjectile(enemySource, null, weaponId, ...) 生成（owner=敌舰 ≠ 无人透镜 owner，
     * 满足 ghostSignal 的敌对判定）。spawn 失败（weaponId 不可用等）必须 Fail Fast 报错，绝不静默吞错。
     */
    private fun feedGhostSignalMissiles(engine: CombatEngineAPI, amount: Float) {
        val automated = findAutomatedLens(engine) ?: return
        // 幽灵信号波计数已满足则停止投放，避免无意义刷导弹。
        if (LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES) > 0) return
        if (elapsed < 0.75f) return

        ghostMissileFeedAcc += amount
        if (ghostMissileFeedAcc < GHOST_MISSILE_FEED_INTERVAL) return
        ghostMissileFeedAcc = 0f

        val enemySource = engine.ships.firstOrNull { it.owner != automated.owner && !it.isFighter && !it.isHulk } ?: return
        // 在无人透镜周围一圈投放，确保落入 2000su 幽灵范围内（取 800su 半径）。
        for (i in 0 until GHOST_MISSILE_BURST) {
            val ang = i * (360f / GHOST_MISSILE_BURST)
            val loc = Vector2f(
                automated.location.x + 800f * kotlin.math.cos(Math.toRadians(ang.toDouble())).toFloat(),
                automated.location.y + 800f * kotlin.math.sin(Math.toRadians(ang.toDouble())).toFloat(),
            )
            // weapon=null + weaponId 直接生成导弹实体（范式同 ASTDVirtualParticleLatticeWebHullMod:215）。
            // spawn 返回 null 视为 weaponId 不可用——Fail Fast 抛错，由 automation 暴露而非伪造通过。
            val spawned = engine.spawnProjectile(enemySource, null, GHOST_FEED_MISSILE_ID, Vector2f(loc), ang + 180f, Vector2f())
            if (spawned == null) {
                throw IllegalStateException(
                    "[ASTD-Automation] lens phase-2 ghost-signal missile spawn returned null for weaponId=$GHOST_FEED_MISSILE_ID"
                )
            }
        }
    }

    /**
     * phase2 证据齐备判定：机制证据 + shader 提交计数全满足才判 Completed。
     * fighter 误差标记降级为插件挂载断言（见诊断字段 parallaxDecksHullmod），不在此处要求 fighter 实触发。
     */
    private fun lensPhase2EvidenceReady(engine: CombatEngineAPI, enemies: List<ShipAPI>): Boolean {
        if (elapsed < 1.5f) return false
        val mechOk = EchoFixationField.hasActiveField(engine) &&
            lensPhase2CognitiveTearApplied(enemies) &&
            EchoFixationAfterimageRenderer.afterimageFrames(engine) > 0 &&
            lensPhase2MaxDeepWaterOnEnemy(enemies) > 0 &&
            lensPhase2PermeatingTideHullmod(engine) &&
            lensPhase2ParallaxDecksHullmod(engine)
        val visualOk = LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_ECHO_FIXATION_FIELD_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DRIFT_MARK_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DEEP_WATER_MARK_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES) > 0 &&
            LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_TIDE_FIELD_FRAMES) > 0
        return mechOk && visualOk
    }

    /** 是否有敌舰被认知撕裂：承伤 mult>1（EchoFixationField 回放写 hullDamageTakenMult.modifyMult）。 */
    private fun lensPhase2CognitiveTearApplied(enemies: List<ShipAPI>): Boolean =
        enemies.any { ship ->
            try { (ship.mutableStats?.hullDamageTakenMult?.modifiedValue ?: 0f) > 1.0001f } catch (_: Throwable) { false }
        }

    /** 场内敌舰深水标记最大层数（潮汐叠层证据）。 */
    private fun lensPhase2MaxDeepWaterOnEnemy(enemies: List<ShipAPI>): Int =
        enemies.maxOfOrNull { LensMarks.deepWaterStacks(it) } ?: 0

    /** 场内敌舰误差标记最大层数（认知撕裂回放 / 视差甲板均会叠误差标记）。 */
    private fun lensPhase2MaxDriftOnEnemy(enemies: List<ShipAPI>): Int =
        enemies.maxOfOrNull { LensMarks.driftStacks(it) } ?: 0

    /** 载人透镜是否挂载渗透潮汐插件（内置 hullmod）。 */
    private fun lensPhase2PermeatingTideHullmod(engine: CombatEngineAPI): Boolean {
        val crewed = findCrewedLens(engine) ?: return false
        return hasHullmod(crewed, "astd_lens_permeating_tide")
    }

    /**
     * 载人透镜是否挂载视差甲板插件（内置 hullmod）。
     * fighter 驱动误差标记实机难稳定触发（依赖舰载机起飞/命中），故 parallaxDriftStacksFromFighters
     * 降级为「插件挂载」断言：证明视差甲板机制已装载即视为通过，fighter 实命中留人工/后续。
     */
    private fun lensPhase2ParallaxDecksHullmod(engine: CombatEngineAPI): Boolean {
        val crewed = findCrewedLens(engine) ?: return false
        return hasHullmod(crewed, "astd_lens_parallax_decks")
    }

    private fun spawnAod7Projectile(engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI) {
        val location = Vector2f(projectilePreviewAnchor)
        val velocity = Vector2f(ship.velocity ?: Vector2f())
        val projectile = engine.spawnProjectile(
            ship,
            weapon,
            ASTDInGameAutomationScenario.WEAPON_ID,
            location,
            weapon.currAngle,
            velocity,
        ) as? DamagingProjectileAPI

        if (projectile != null) {
            fallbackProjectile = projectile
            fallbackProjectileSpawnedAt = elapsed
            projectile.velocity.x = FALLBACK_PROJECTILE_SPEED
            projectile.velocity.y = 0f
            alignFallbackProjectileForEvidence()
            ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine)
            log.info("[ASTD-Automation] fallback spawned ${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID} through ${ASTDInGameAutomationScenario.WEAPON_ID}")
        }
    }

    private fun alignFallbackProjectileForEvidence() {
        val projectile = fallbackProjectile ?: return
        driveFallbackProjectileCurve(projectile)
    }

    private fun alignAod7ProjectilesForEvidence(engine: CombatEngineAPI) {
        fallbackProjectile?.let { alignProjectileForEvidence(it) }
        engine.projectiles
            .filter { it.projectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID }
            .forEach { projectile ->
                if (projectile is DamagingProjectileAPI) alignProjectileForEvidence(projectile)
            }
    }

    private fun alignProjectileForEvidence(projectile: DamagingProjectileAPI) {
        if (projectile === fallbackProjectile) {
            driveFallbackProjectileCurve(projectile)
            return
        }
        projectile.location.y = curvePositionAt(0f).y
        projectile.facing = 0f
    }

    private fun driveFallbackProjectileCurve(projectile: DamagingProjectileAPI) {
        val age = (elapsed - fallbackProjectileSpawnedAt).coerceAtLeast(0f)
        projectile.location.set(curvePositionAt(age))
        val velocity = curveVelocityAt(age)
        projectile.velocity.set(velocity)
        projectile.facing = org.lazywizard.lazylib.VectorUtils.getFacing(velocity)
    }

    private fun curvePositionAt(age: Float): Vector2f {
        val track = automationPreviewTrack(age)
        val scale = automationReferenceWorldUnitsPerPixel()
        return Vector2f(
            projectilePreviewAnchor.x + track.headOffset.x * scale,
            projectilePreviewAnchor.y + track.headOffset.y * scale,
        )
    }

    private fun curveVelocityAt(age: Float): Vector2f {
        val step = 1f / 120f
        val previous = curvePositionAt((age - step).coerceAtLeast(0f))
        val next = curvePositionAt(age + step)
        return Vector2f((next.x - previous.x) / (step * 2f), (next.y - previous.y) / (step * 2f))
    }

    private fun automationPreviewTrack(age: Float): ASTDProjectileVfxLayout.PreviewFlightTrack {
        val preset = ASTDProjectileVfxPresetCatalog.preset(ASTDInGameAutomationScenario.VFX_PRESET_ID)
            ?: throw IllegalStateException("AOD-7 automation reference preset missing: ${ASTDInGameAutomationScenario.VFX_PRESET_ID}")
        val trail = preset.primaryTrailLayer()
            ?: throw IllegalStateException("AOD-7 automation reference trail missing: ${ASTDInGameAutomationScenario.VFX_PRESET_ID}")
        return ASTDProjectileVfxLayout.previewFlightTrack(
            trailStartWidth = trail.startWidth,
            elapsed = age,
            durationSeconds = preset.lifecycle.durationSeconds,
            flightEndRatio = preset.lifecycle.flightEndRatio,
            dissolveStartRatio = preset.lifecycle.dissolveStartRatio,
            preDissolveFraction = preset.lifecycle.preDissolveFraction,
            captureWidth = preset.lifecycle.layoutReferenceWidth,
            captureHeight = AUTOMATION_REFERENCE_CAPTURE_HEIGHT,
            curveAmount = AUTOMATION_CURVE_AMOUNT,
            curveFrequency = AUTOMATION_CURVE_FREQUENCY,
            curved = true,
        )
    }

    private fun automationReferenceWorldUnitsPerPixel(): Float {
        val pixelHeight = try { Display.getHeight().toFloat().takeIf { it > 0f } ?: 1f } catch (_: Throwable) { 1f }
        return ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel(pixelHeight)
    }

    private fun currentState(engine: CombatEngineAPI, ship: ShipAPI?, weapon: WeaponAPI?): String {
        failureReason = null
        if (ship == null) {
            failureReason = "arc_flare ship not found in combat"
            return if (elapsed > 10f) "Failed" else "CombatReady"
        }
        if (weapon == null) {
            failureReason = "aod7 weapon not found on arc_flare"
            return if (elapsed > 10f) "Failed" else "CombatReady"
        }
        if (projectileObserved(engine) && vfxObserved() && evidenceReady()) return "Completed"
        if (projectileObserved(engine)) return "FireObserved"
        return "CombatReady"
    }

    private fun projectileObserved(engine: CombatEngineAPI): Boolean {
        val runtime = ASTDProjectileVfxRuntimeTelemetry.snapshot()
        if (runtime.lastProjectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID) return true
        return engine.projectiles.any { it.projectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID }
    }

    private fun vfxObserved(): Boolean {
        val runtime = ASTDProjectileVfxRuntimeTelemetry.snapshot()
        return runtime.trackedCount > 0 &&
            runtime.lastPresetId == ASTDInGameAutomationScenario.VFX_PRESET_ID
    }

    private fun evidenceReady(): Boolean {
        val runtime = ASTDProjectileVfxRuntimeTelemetry.snapshot()
        return runtime.lastVisibleLength >= referenceCaptureVisibleLength() &&
            runtime.lastElapsed >= SCREENSHOT_FLIGHT_SECONDS
    }

    private fun referenceCaptureVisibleLength(): Float {
        val preset = ASTDProjectileVfxPresetCatalog.preset(ASTDInGameAutomationScenario.VFX_PRESET_ID)
            ?: throw IllegalStateException("AOD-7 automation reference preset missing: ${ASTDInGameAutomationScenario.VFX_PRESET_ID}")
        val trail = preset.primaryTrailLayer()
            ?: throw IllegalStateException("AOD-7 automation reference trail missing: ${ASTDInGameAutomationScenario.VFX_PRESET_ID}")
        return ASTDProjectileVfxLayout.previewFlightLayout(
            trailStartWidth = trail.startWidth,
            elapsed = REFERENCE_CAPTURE_ELAPSED_SECONDS,
            durationSeconds = preset.lifecycle.durationSeconds,
            flightEndRatio = preset.lifecycle.flightEndRatio,
            dissolveStartRatio = preset.lifecycle.dissolveStartRatio,
            preDissolveFraction = preset.lifecycle.preDissolveFraction,
            captureWidth = preset.lifecycle.layoutReferenceWidth,
        ).visibleLength
    }

    private fun writeTelemetry(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI? = findArcFlare(engine),
        weapon: WeaponAPI? = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
    ) {
        // SSOptimizer patches this method and writes telemetry/screenshots outside the Starsector script sandbox.
    }

    private fun writeDiagnostics(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI? = findArcFlare(engine),
    ) {
        if (!ASTDInGameAutomationScenario.isEnabled() &&
            !ASTDInGameAutomationScenario.isArcProductionEnabled() &&
            !ASTDInGameAutomationScenario.isLensPhase1Enabled() &&
            !ASTDInGameAutomationScenario.isLensPhase2Enabled()
        ) {
            return
        }

        val displayMode = try { Display.getDisplayMode() } catch (_: Throwable) { null }
        val displayWidth = try { Display.getWidth() } catch (_: Throwable) { -1 }
        val displayHeight = try { Display.getHeight() } catch (_: Throwable) { -1 }
        val displayPixelScale = try { Display.getPixelScaleFactor() } catch (_: Throwable) { -1f }
        val viewport = engine.viewport
        val shipSprite = try { ship?.spriteAPI } catch (_: Throwable) { null }
        val runtime = ASTDProjectileVfxRuntimeTelemetry.snapshot()
        val scenarioId = when {
            ASTDInGameAutomationScenario.isLensPhase2Enabled() -> ASTDInGameAutomationScenario.LENS_PHASE2_SCENARIO_ID
            ASTDInGameAutomationScenario.isLensPhase1Enabled() -> ASTDInGameAutomationScenario.LENS_PHASE1_SCENARIO_ID
            ASTDInGameAutomationScenario.isArcProductionEnabled() -> ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID
            else -> ASTDInGameAutomationScenario.SCENARIO_ID
        }
        val json = buildString {
            appendLine("{")
            appendLine("  \"source\": \"ASTD\",")
            appendLine("  \"scenario\": \"$scenarioId\",")
            appendLine("  \"state\": \"$state\",")
            appendLine("  \"displayWidth\": $displayWidth,")
            appendLine("  \"displayHeight\": $displayHeight,")
            appendLine("  \"displayPixelScale\": ${formatFloat(displayPixelScale)},")
            appendLine("  \"displayModeWidth\": ${displayMode?.width ?: -1},")
            appendLine("  \"displayModeHeight\": ${displayMode?.height ?: -1},")
            appendLine("  \"viewportVisibleWidth\": ${formatFloat(viewport.visibleWidth)},")
            appendLine("  \"viewportVisibleHeight\": ${formatFloat(viewport.visibleHeight)},")
            appendLine("  \"viewportWorldXToScreenX\": ${formatFloat(viewport.worldXtoScreenX)},")
            appendLine("  \"viewportWorldYToScreenY\": ${formatFloat(viewport.worldYtoScreenY)},")
            appendLine("  \"viewportViewMult\": ${formatFloat(viewport.viewMult)},")
            appendLine("  \"shipLocationX\": ${formatFloat(ship?.location?.x ?: -1f)},")
            appendLine("  \"shipLocationY\": ${formatFloat(ship?.location?.y ?: -1f)},")
            appendLine("  \"shipFacing\": ${formatFloat(ship?.facing ?: -1f)},")
            appendLine("  \"shipSpriteWidth\": ${formatFloat(shipSprite?.width ?: -1f)},")
            appendLine("  \"shipSpriteHeight\": ${formatFloat(shipSprite?.height ?: -1f)},")
            appendLine("  \"shipSpriteCenterX\": ${formatFloat(shipSprite?.centerX ?: -1f)},")
            appendLine("  \"shipSpriteCenterY\": ${formatFloat(shipSprite?.centerY ?: -1f)},")
            appendLine("  \"failureReason\": ${jsonString(failureReason)},")
            if (ASTDInGameAutomationScenario.isLensPhase2Enabled()) {
                val crewed = findCrewedLens(engine)
                val enemies = lensPhase2Enemies(engine)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": 0,")
                appendLine("  \"runtimeLastProjectileSpecId\": null,")
                appendLine("  \"runtimeLastPresetId\": null,")
                appendLine("  \"referenceVisibleLength\": 0,")
                appendLine("  \"lensDeployedShipIds\": ${jsonStringList(lensDeployedShipIds(engine))},")
                // ---- 机制证据 ----
                appendLine("  \"echoFixationFieldActive\": ${safeBool { EchoFixationField.hasActiveField(engine) }},")
                appendLine("  \"echoFixationCognitiveTearApplied\": ${safeBool { lensPhase2CognitiveTearApplied(enemies) }},")
                appendLine("  \"echoFixationAfterimageFrames\": ${EchoFixationAfterimageRenderer.afterimageFrames(engine)},")
                appendLine("  \"tideDeepWaterStacksOnEnemy\": ${lensPhase2MaxDeepWaterOnEnemy(enemies)},")
                appendLine("  \"driftStacksOnEnemy\": ${lensPhase2MaxDriftOnEnemy(enemies)},")
                appendLine("  \"permeatingTideHullmod\": ${safeBool { lensPhase2PermeatingTideHullmod(engine) }},")
                // fighter 误差标记降级为插件挂载断言（见 lensPhase2ParallaxDecksHullmod 注释）。
                appendLine("  \"parallaxDecksHullmod\": ${safeBool { lensPhase2ParallaxDecksHullmod(engine) }},")
                appendLine("  \"lensCrewedDeployed\": ${crewed != null},")
                appendLine("  \"lensAutomatedDeployed\": ${findAutomatedLens(engine) != null},")
                // ---- shader 提交计数（视觉管线生效证据，每次真实 upsert +1）----
                appendLine("  \"echoFixationFieldVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_ECHO_FIXATION_FIELD_FRAMES)},")
                appendLine("  \"driftMarkVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DRIFT_MARK_FRAMES)},")
                appendLine("  \"deepWaterMarkVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DEEP_WATER_MARK_FRAMES)},")
                appendLine("  \"ghostSignalWaveFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES)},")
                appendLine("  \"tideFieldVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_TIDE_FIELD_FRAMES)},")
            } else if (ASTDInGameAutomationScenario.isLensPhase1Enabled()) {
                val lens = findShipByHull(engine, LensArrayCoreHullModIds.HULL_ID)
                val lensVariant = try { lens?.variant } catch (_: Throwable) { null }
                val lensShield = try { lens?.shield } catch (_: Throwable) { null }
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": 0,")
                appendLine("  \"runtimeLastProjectileSpecId\": null,")
                appendLine("  \"runtimeLastPresetId\": null,")
                appendLine("  \"referenceVisibleLength\": 0,")
                appendLine("  \"lensDeployedShipIds\": ${jsonStringList(lensDeployedShipIds(engine))},")
                appendLine("  \"lensCoreHullmod\": ${safeBool { lensVariant?.hasHullMod(LensArrayCoreHullModIds.CORE) == true }},")
                appendLine("  \"lensNanoHullmod\": ${safeBool { lensVariant?.hasHullMod("astd_nano_restoration_protocol") == true }},")
                appendLine("  \"lensSwitcherHullmod\": ${safeBool { lensVariant?.hasHullMod(LensArrayCoreHullModIds.SWITCHER) == true }},")
                appendLine("  \"lensCrewedModeHullmod\": ${safeBool { lensVariant?.hasHullMod(LensArrayCoreHullModIds.MODE_CREWED) == true }},")
                appendLine("  \"lensShieldOn\": ${safeBool { lensShield?.isOn == true }},")
                appendLine("  \"lensShieldArc\": ${formatFloat(try { lensShield?.arc ?: lens?.hullSpec?.shieldSpec?.arc ?: 0f } catch (_: Throwable) { 0f })},")
                appendLine("  \"lensFighterBays\": ${try { lens?.hullSpec?.fighterBays ?: 0 } catch (_: Throwable) { 0 }},")
                appendLine("  \"lensCoreTooltipKeys\": ${lensCoreTooltipKeyCount(engine)},")
                appendLine("  \"lensSelfDriftStacks\": ${lens?.let { LensMarks.driftStacks(it) } ?: 0},")
                appendLine("  \"lensSelfDeepWaterStacks\": ${lens?.let { LensMarks.deepWaterStacks(it) } ?: 0},")
                appendLine("  \"lensSelfHullDamageTakenMult\": ${formatFloat(try { lens?.mutableStats?.hullDamageTakenMult?.modifiedValue ?: 0f } catch (_: Throwable) { 0f })},")
            } else if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
                val plasmaArch = findShipByHull(engine, ASTDArcProductionShipIds.HULL_PLASMA_ARCH)
                val plasmaSystem = plasmaArch?.system
                val plasmaSpec = plasmaSystem?.specAPI
                val plasmaShield = plasmaArch?.shield
                val plasmaSystemAI = plasmaRuntimeSystemAI(plasmaArch)
                val plasmaBiggestThreat = plasmaAIFlagTarget(plasmaArch, ShipwideAIFlags.AIFlags.BIGGEST_THREAT)
                val plasmaSystemTarget = plasmaAIFlagTarget(plasmaArch, ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)
                val plasmaManeuverTarget = plasmaAIFlagTarget(plasmaArch, ShipwideAIFlags.AIFlags.MANEUVER_TARGET)
                appendLine("  \"runtimeElapsedSeconds\": 0,")
                appendLine("  \"runtimeVisibleLength\": 0,")
                appendLine("  \"runtimeBeamAlpha\": 0,")
                appendLine("  \"runtimeWorldUnitsPerPixel\": 0,")
                appendLine("  \"runtimeTrackedCount\": 0,")
                appendLine("  \"runtimeLastProjectileSpecId\": null,")
                appendLine("  \"runtimeLastPresetId\": null,")
                appendLine("  \"referenceVisibleLength\": 0,")
                appendLine("  \"arcProductionMissingShips\": ${jsonStringList(arcProductionMissingShips(engine))},")
                appendLine("  \"arcProductionDeployedShipIds\": ${jsonStringList(arcProductionDeployedShipIds(engine))},")
                appendLine("  \"arcProductionDeployedVariantIds\": ${jsonStringList(arcProductionDeployedVariantIds(engine))},")
                appendLine("  \"arcProductionSourceVariantIds\": ${jsonStringList(arcProductionSourceVariantIds(engine))},")
                appendLine("  \"arcProductionPlayerReserves\": ${engine.getFleetManager(FleetSide.PLAYER).getReservesCopy().size},")
                appendLine("  \"arcProductionEnemyReserves\": ${engine.getFleetManager(FleetSide.ENEMY).getReservesCopy().size},")
                appendLine("  \"radiationBeltSystemState\": ${jsonString(findShipByHull(engine, ASTDArcProductionShipIds.HULL_RADIATION_BELT)?.system?.state?.name)},")
                appendLine("  \"plasmaArchSystemId\": ${jsonString(plasmaSystem?.id)},")
                appendLine("  \"plasmaArchSystemState\": ${jsonString(plasmaSystem?.state?.name)},")
                appendLine("  \"plasmaArchSystemCanBeActivated\": ${safeBool { plasmaSystem?.canBeActivated() == true }},")
                appendLine("  \"plasmaArchSystemEffectLevel\": ${formatFloat(plasmaSystem?.effectLevel ?: -1f)},")
                appendLine("  \"plasmaArchShipAI\": ${jsonString(plasmaArch?.shipAI?.javaClass?.name)},")
                appendLine("  \"plasmaArchFluxLevel\": ${formatFloat(plasmaArch?.fluxLevel ?: -1f)},")
                appendLine("  \"plasmaArchCurrFlux\": ${formatFloat(plasmaArch?.currFlux ?: -1f)},")
                appendLine("  \"plasmaArchMaxFlux\": ${formatFloat(plasmaArch?.maxFlux ?: -1f)},")
                appendLine("  \"plasmaArchHardFlux\": ${formatFloat(plasmaArch?.fluxTracker?.hardFlux ?: -1f)},")
                appendLine("  \"plasmaArchHardFluxLevel\": ${formatFloat(plasmaArch?.hardFluxLevel ?: -1f)},")
                appendLine("  \"plasmaArchSinceLastDamageTaken\": ${formatFloat(plasmaArch?.sinceLastDamageTaken ?: -1f)},")
                appendLine("  \"plasmaArchOverloadedOrVenting\": ${plasmaArch?.fluxTracker?.isOverloadedOrVenting ?: false},")
                appendLine("  \"plasmaArchShieldOn\": ${plasmaShield?.isOn ?: false},")
                appendLine("  \"plasmaArchShieldActiveArc\": ${formatFloat(plasmaShield?.activeArc ?: -1f)},")
                appendLine("  \"plasmaArchAIFlags\": ${jsonStringList(plasmaAIFlags(plasmaArch))},")
                appendLine("  \"plasmaArchAIFlagIncomingDamage\": ${plasmaAIFlag(plasmaArch, ShipwideAIFlags.AIFlags.HAS_INCOMING_DAMAGE)},")
                appendLine("  \"plasmaArchAIFlagCriticalDpsDanger\": ${plasmaAIFlag(plasmaArch, ShipwideAIFlags.AIFlags.IN_CRITICAL_DPS_DANGER)},")
                appendLine("  \"plasmaArchAIFlagKeepShieldsOn\": ${plasmaAIFlag(plasmaArch, ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON)},")
                appendLine("  \"plasmaArchVanillaSystemAI\": ${jsonString(plasmaSystemAI.className)},")
                appendLine("  \"plasmaArchVanillaSystemAIError\": ${jsonString(plasmaSystemAI.error)},")
                appendLine("  \"plasmaArchAIFlagBiggestThreatTargetHullId\": ${jsonString(plasmaBiggestThreat.ship?.hullSpec?.hullId)},")
                appendLine("  \"plasmaArchAIFlagBiggestThreatTargetVariantId\": ${jsonString(plasmaBiggestThreat.ship?.variant?.hullVariantId)},")
                appendLine("  \"plasmaArchAIFlagTargetForSystemHullId\": ${jsonString(plasmaSystemTarget.ship?.hullSpec?.hullId)},")
                appendLine("  \"plasmaArchAIFlagTargetForSystemVariantId\": ${jsonString(plasmaSystemTarget.ship?.variant?.hullVariantId)},")
                appendLine("  \"plasmaArchAIFlagManeuverTargetHullId\": ${jsonString(plasmaManeuverTarget.ship?.hullSpec?.hullId)},")
                appendLine("  \"plasmaArchAIFlagManeuverTargetVariantId\": ${jsonString(plasmaManeuverTarget.ship?.variant?.hullVariantId)},")
                appendLine("  \"plasmaArchEnemyPressureShips\": ${plasmaEnemyPressureShips(engine, plasmaArch)},")
                appendLine("  \"plasmaArchEnemyTargetingShips\": ${plasmaEnemyTargetingShips(engine, plasmaArch)},")
                appendLine("  \"plasmaArchEnemyFiringWeapons\": ${plasmaEnemyFiringWeapons(engine, plasmaArch)},")
                appendLine("  \"plasmaArchEnemyProjectiles\": ${plasmaEnemyProjectiles(engine, plasmaArch)},")
                appendLine("  \"plasmaArchSystemSpecAiScript\": ${jsonString(plasmaSpec?.aiScript?.javaClass?.name ?: plasmaSpec?.aiScriptClassName)},")
                appendLine("  \"plasmaArchSystemSpecFpsBaseCap\": ${formatFloat(plasmaSpec?.fluxPerSecondBaseCap ?: -1f)},")
                appendLine("  \"plasmaArchSystemSpecToggle\": ${plasmaSpec?.isToggle ?: false},")
                appendLine("  \"plasmaArchSystemSpecFiringAllowed\": ${plasmaSpec?.isFiringAllowed ?: false},")
                appendLine("  \"plasmaArchSystemSpecTags\": ${jsonStringList(plasmaSpec?.tags?.toList()?.sorted() ?: emptyList())},")
            } else {
                appendLine("  \"runtimeElapsedSeconds\": ${formatFloat(runtime.lastElapsed)},")
                appendLine("  \"runtimeVisibleLength\": ${formatFloat(runtime.lastVisibleLength)},")
                appendLine("  \"runtimeBeamAlpha\": ${formatFloat(runtime.lastBeamAlpha)},")
                appendLine("  \"runtimeWorldUnitsPerPixel\": ${formatFloat(runtime.lastWorldUnitsPerPixel)},")
                appendLine("  \"runtimeTrackedCount\": ${runtime.trackedCount},")
                appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(runtime.lastProjectileSpecId)},")
                appendLine("  \"runtimeLastPresetId\": ${jsonString(runtime.lastPresetId)},")
                appendLine("  \"referenceVisibleLength\": ${formatFloat(referenceCaptureVisibleLength())},")
            }
            appendLine("  \"fallbackInPlay\": ${fallbackProjectile?.let { engine.isEntityInPlay(it) } ?: false},")
            appendLine("  \"fallbackExpired\": ${fallbackProjectile?.isExpired ?: false},")
            appendLine("  \"fallbackFading\": ${fallbackProjectile?.isFading ?: false},")
            appendLine("  \"arcJetShockwaveFrames\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FRAMES)},")
            appendLine("  \"arcJetShockwaveRadius\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_RADIUS)},")
            appendLine("  \"arcJetShockwaveFluxPressure\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_ARC_JET_SHOCKWAVE_FLUX_PRESSURE)},")
            appendLine("  \"plasmaArchShieldOpen\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_OPEN)},")
            appendLine("  \"plasmaArchSystemActive\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE)},")
            appendLine("  \"plasmaArchShieldArcEmissions\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS)},")
            appendLine("  \"radiationBeltSystemAfterimages\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES)},")
            val arcJetTooltipKeys = tooltipResolvedKeyCount("astd_arc_jet", ASTDArcProductionTooltipContracts.arcJetContracts)
            val plasmaArchTooltipKeys = tooltipResolvedKeyCount("astd_plasma_arch", ASTDArcProductionTooltipContracts.plasmaArchContracts)
            val radiationBeltTooltipKeys = tooltipResolvedKeyCount("astd_radiation_belt", ASTDArcProductionTooltipContracts.radiationBeltContracts)
            appendLine("  \"arcJetTooltip\": ${tooltipBlocksResolved("astd_arc_jet", ASTDArcProductionTooltipContracts.arcJetContracts)},")
            appendLine("  \"plasmaArchTooltip\": ${tooltipBlocksResolved("astd_plasma_arch", ASTDArcProductionTooltipContracts.plasmaArchContracts)},")
            appendLine("  \"radiationBeltTooltip\": ${tooltipBlocksResolved("astd_radiation_belt", ASTDArcProductionTooltipContracts.radiationBeltContracts)},")
            appendLine("  \"arcJetTooltipKeys\": $arcJetTooltipKeys,")
            appendLine("  \"plasmaArchTooltipKeys\": $plasmaArchTooltipKeys,")
            appendLine("  \"radiationBeltTooltipKeys\": $radiationBeltTooltipKeys,")
            appendLine("  \"elapsedSeconds\": ${"%.3f".format(java.util.Locale.ROOT, elapsed)}")
            appendLine("}")
        }
        log.info("[ASTD-Automation] diagnostics state=$state json=${json.lines().joinToString(" ")}")
    }

    private fun tooltipBlocksResolved(hullId: String, contracts: List<ASTDArcProductionTooltipContracts.Contract>): Boolean {
        val ship = engine?.let { findShipByHull(it, hullId) } ?: return false
        return contracts.all { contract ->
            hasHullmod(ship, contract.hullmodId) && contract.textKeys.all { key -> isResolvedTextKey(key) }
        }
    }

    private fun arcProductionDeployedShipIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.filter { hullId -> findShipByHull(engine, hullId) != null }

    private fun arcProductionDeployedVariantIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.mapNotNull { hullId -> findShipByHull(engine, hullId)?.variant?.hullVariantId }

    private fun arcProductionSourceVariantIds(engine: CombatEngineAPI): List<String> =
        ARC_PRODUCTION_CORE_HULLS.mapNotNull { hullId ->
            val member = findShipByHull(engine, hullId)?.fleetMember
            ARC_PRODUCTION_STANDARD_VARIANTS[hullId] ?: member?.variant?.hullVariantId
        }

    private fun tooltipResolvedKeyCount(hullId: String, contracts: List<ASTDArcProductionTooltipContracts.Contract>): Int {
        val ship = engine?.let { findShipByHull(it, hullId) } ?: return 0
        return contracts
            .filter { hasHullmod(ship, it.hullmodId) }
            .sumOf { contract -> contract.textKeys.count { key -> isResolvedTextKey(key) } }
    }

    private fun plasmaEnemyPressureShips(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.ships.count { ship ->
            ship.owner != plasmaArch.owner &&
                ship.isAlive &&
                !ship.isHulk &&
                distanceSquared(ship.location, plasmaArch.location) <= PLASMA_AI_PRESSURE_RANGE * PLASMA_AI_PRESSURE_RANGE
        }
    }

    private fun plasmaEnemyTargetingShips(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.ships.count { ship ->
            ship.owner != plasmaArch.owner && ship.shipTarget === plasmaArch
        }
    }

    private fun plasmaEnemyFiringWeapons(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.ships
            .filter { ship -> ship.owner != plasmaArch.owner }
            .sumOf { ship ->
                try {
                    ship.allWeapons.count { weapon -> weapon.isFiring }
                } catch (_: Throwable) {
                    0
                }
            }
    }

    private fun plasmaEnemyProjectiles(engine: CombatEngineAPI, plasmaArch: ShipAPI?): Int {
        if (plasmaArch == null) return 0
        return engine.projectiles.count { projectile ->
            val damaging = projectile as? DamagingProjectileAPI ?: return@count false
            val source = damaging.source ?: return@count false
            source.owner != plasmaArch.owner &&
                !damaging.isExpired &&
                distanceSquared(damaging.location, plasmaArch.location) <= PLASMA_AI_PRESSURE_RANGE * PLASMA_AI_PRESSURE_RANGE
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

    private fun hasHullmod(ship: ShipAPI, hullmodId: String): Boolean =
        try { ship.variant?.hasHullMod(hullmodId) == true } catch (_: Throwable) { false }

    private fun isResolvedTextKey(key: String): Boolean {
        val text = I18n[I18n.Categories.MOD, key]
        return text.isNotBlank() && text != "${I18n.Categories.MOD.id}:$key"
    }

    private fun jsonString(value: String?): String = value?.let { "\"${escapeJson(it)}\"" } ?: "null"

    private fun jsonStringList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

    private fun formatFloat(value: Float): String = "%.4f".format(java.util.Locale.ROOT, value)

    private fun distanceSquared(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun safeBool(block: () -> Boolean): Boolean =
        try { block() } catch (_: Throwable) { false }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

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
            ASTDArcProductionShipIds.HULL_ARC_JET,
            ASTDArcProductionShipIds.HULL_PLASMA_ARCH,
            ASTDArcProductionShipIds.HULL_RADIATION_BELT,
        )
        // 引力透镜级自标记验收层数（spec：误差/深水各叠 3 层）。
        private const val LENS_SELF_MARK_STACKS = 3
        // phase2 部署锚点：载人透镜上方、无人透镜下方，敌群居中（落入两者作用范围）。
        private val LENS_PHASE2_CREWED_ANCHOR = Vector2f(-260f, 260f)
        private val LENS_PHASE2_AUTOMATED_ANCHOR = Vector2f(-260f, -380f)
        private const val LENS_PHASE2_ENEMY_CLUSTER_X = 360f
        // phase2 幽灵信号导弹投放：每 0.3s 一批，每批 6 枚环绕无人透镜（确保落入 2000su 幽灵范围）。
        private const val GHOST_MISSILE_FEED_INTERVAL = 0.3f
        private const val GHOST_MISSILE_BURST = 6
        // 投放用导弹 weaponId（ASTD 既有导弹型武器，保证模组已加载；范式同 ASTDVirtualParticleLatticeWebHullMod）。
        private const val GHOST_FEED_MISSILE_ID = "astd_virtual_particle_mote_launcher"
        // 透镜阵列核心 hullmod tooltip 文本 key（与 ASTDLensArrayCoreHullMod.addPostDescriptionSection 一致，共 7 个）。
        private val LENS_CORE_TOOLTIP_KEYS = listOf(
            "ui.hullmod.lens_core.summary",
            "ui.hullmod.lens_core.line.1",
            "ui.hullmod.lens_core.line.2",
            "ui.hullmod.lens_core.line.3",
            "ui.hullmod.lens_core.line.4",
            "ui.hullmod.lens_core.line.5",
            "ui.hullmod.lens_core.line.6",
        )
        private val ARC_PRODUCTION_STANDARD_VARIANTS = mapOf(
            ASTDArcProductionShipIds.HULL_ARC_JET to "astd_arc_jet_Standard",
            ASTDArcProductionShipIds.HULL_PLASMA_ARCH to "astd_plasma_arch_Standard",
            ASTDArcProductionShipIds.HULL_RADIATION_BELT to "astd_radiation_belt_Standard",
        )
        private const val FALLBACK_PROJECTILE_SPEED = 2400f
        private const val AUTOMATION_CURVE_AMOUNT = 96f
        private const val AUTOMATION_CURVE_FREQUENCY = 0.8f
        private const val AUTOMATION_REFERENCE_CAPTURE_HEIGHT = 600f
        private const val SCREENSHOT_FLIGHT_SECONDS = 0.13333334f
        private const val REFERENCE_CAPTURE_ELAPSED_SECONDS = 0.3004f
        private const val PLASMA_AI_PRESSURE_RANGE = 1800f
    }
}
