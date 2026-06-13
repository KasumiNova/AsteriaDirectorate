package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionTooltipContracts
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
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

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        ASTDProjectileVfxRuntimePlugin.ensureInstalled(engine)
        if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
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
        if (!ASTDInGameAutomationScenario.isEnabled() && !ASTDInGameAutomationScenario.isArcProductionEnabled()) return

        val displayMode = try { Display.getDisplayMode() } catch (_: Throwable) { null }
        val displayWidth = try { Display.getWidth() } catch (_: Throwable) { -1 }
        val displayHeight = try { Display.getHeight() } catch (_: Throwable) { -1 }
        val displayPixelScale = try { Display.getPixelScaleFactor() } catch (_: Throwable) { -1f }
        val viewport = engine.viewport
        val shipSprite = try { ship?.spriteAPI } catch (_: Throwable) { null }
        val runtime = ASTDProjectileVfxRuntimeTelemetry.snapshot()
        val scenarioId = if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID
        } else {
            ASTDInGameAutomationScenario.SCENARIO_ID
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
            if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
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
