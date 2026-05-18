package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimePlugin
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimeTelemetry
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.opengl.Display
import org.lwjgl.util.vector.Vector2f
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Dev-only combat automation surface for validating Arc Flare + AOD-7 runtime VFX in game.
 */
class ASTDAutomationCombatPlugin : BaseEveryFrameCombatPlugin() {
    private val captureCenter = Vector2f(0f, 0f)
    private val playerAnchor = Vector2f(-360f, 0f)
    private val projectilePreviewAnchor = Vector2f(300f, 0f)
    private val enemyAnchor = Vector2f(900f, 0f)
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

    override fun init(engine: CombatEngineAPI) {
        this.engine = engine
        ASTDProjectileVfxRuntimePlugin.ensureInstalled(engine)
        lockCamera(engine)
        arrangeShips(engine, findArcFlare(engine))
        writeScreenshotAttempt("init")
        writeDiagnostics(engine, "CombatReady")
        writeTelemetry(engine, "CombatReady")
        log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.SCENARIO_ID} combat plugin initialized")
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val combatEngine = engine ?: return
        if (combatEngine.isPaused) return

        elapsed += amount.coerceAtLeast(0f)
        val ship = findArcFlare(combatEngine)
        val weapon = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID }
        lockCamera(combatEngine)
        arrangeShips(combatEngine, ship)
        pinAod7ProjectilesForEvidence(combatEngine)
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
            writeTelemetry(combatEngine, state, ship, weapon)
        }
    }

    override fun renderInUICoords(viewport: ViewportAPI) {
        val combatEngine = engine ?: return
        if (!completed || visualFramesWritten >= 3) return
        if (elapsed - lastVisualFrameAt < 0.18f) return

        lockCamera(combatEngine)
        val ship = findArcFlare(combatEngine)
        arrangeShips(combatEngine, ship)
        pinAod7ProjectilesForEvidence(combatEngine)
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

    private fun stabilizeShip(ship: ShipAPI, location: Vector2f, facing: Float, allowFire: Boolean) {
        ship.location.set(location)
        ship.velocity.set(0f, 0f)
        ship.facing = facing
        ship.angularVelocity = 0f
        ship.shipAI = null
        ship.setShipTarget(null)
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
            pinFallbackProjectileForEvidence()
            ProjectileSpecOnFireDispatcher().onFire(projectile, weapon, engine)
            log.info("[ASTD-Automation] fallback spawned ${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID} through ${ASTDInGameAutomationScenario.WEAPON_ID}")
        }
    }

    private fun pinFallbackProjectileForEvidence() {
        val projectile = fallbackProjectile ?: return
        pinProjectileForEvidence(projectile)
    }

    private fun pinAod7ProjectilesForEvidence(engine: CombatEngineAPI) {
        fallbackProjectile?.let { pinProjectileForEvidence(it) }
        engine.projectiles
            .filter { it.projectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID }
            .forEach { projectile ->
                if (projectile is DamagingProjectileAPI) pinProjectileForEvidence(projectile)
            }
    }

    private fun pinProjectileForEvidence(projectile: DamagingProjectileAPI) {
        projectile.location.set(projectilePreviewAnchor)
        projectile.velocity.set(0f, 0f)
        projectile.facing = 0f
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
        if (projectileObserved(engine) && vfxObserved()) return "Completed"
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
        return runtime.lastProjectileSpecId == ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID &&
            runtime.lastPresetId == ASTDInGameAutomationScenario.VFX_PRESET_ID
    }

    private fun writeTelemetry(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI? = findArcFlare(engine),
        weapon: WeaponAPI? = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
    ) {
        if (!ASTDInGameAutomationScenario.isEnabled()) return

        val runtime = ASTDProjectileVfxRuntimeTelemetry.snapshot()
        val projectileObserved = projectileObserved(engine)
        val vfxObserved = vfxObserved()
        val outputDir = ASTDInGameAutomationScenario.outputDir()
        val screenshotAttemptPath = outputDir.resolve(ASTDInGameAutomationScenario.SCREENSHOT_ATTEMPT_FILE)
        val telemetryPath = outputDir.resolve(ASTDInGameAutomationScenario.TELEMETRY_FILE)
        val json = buildString {
            appendLine("{")
            appendLine("  \"source\": \"ASTD\",")
            appendLine("  \"simulationMock\": false,")
            appendLine("  \"scenario\": \"${ASTDInGameAutomationScenario.SCENARIO_ID}\",")
            appendLine("  \"state\": \"$state\",")
            appendLine("  \"combatSceneObserved\": ${ship != null},")
            appendLine("  \"shipObserved\": ${ship != null},")
            appendLine("  \"shipId\": \"${ASTDInGameAutomationScenario.SHIP_ID}\",")
            appendLine("  \"variantId\": \"${ship?.variant?.hullVariantId ?: ASTDInGameAutomationScenario.VARIANT_ID}\",")
            appendLine("  \"weaponObserved\": ${weapon != null},")
            appendLine("  \"weaponId\": \"${ASTDInGameAutomationScenario.WEAPON_ID}\",")
            appendLine("  \"projectileSpecId\": \"${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID}\",")
            appendLine("  \"projectileObserved\": $projectileObserved,")
            appendLine("  \"vfxPresetId\": \"${runtime.lastPresetId ?: ASTDInGameAutomationScenario.VFX_PRESET_ID}\",")
            appendLine("  \"vfxObserved\": $vfxObserved,")
            appendLine("  \"runtimeTrackedCount\": ${runtime.trackedCount},")
            appendLine("  \"runtimeElapsedSeconds\": ${formatFloat(runtime.lastElapsed)},")
            appendLine("  \"runtimeVisibleLength\": ${formatFloat(runtime.lastVisibleLength)},")
            appendLine("  \"runtimeBeamAlpha\": ${formatFloat(runtime.lastBeamAlpha)},")
            appendLine("  \"fireCommandIssued\": ${fireMechanism != null},")
            appendLine("  \"fireMechanism\": ${jsonString(fireMechanism)},")
            appendLine("  \"captureCenterX\": ${captureCenter.x},")
            appendLine("  \"captureCenterY\": ${captureCenter.y},")
            appendLine("  \"subjectAnchorX\": ${playerAnchor.x},")
            appendLine("  \"subjectAnchorY\": ${playerAnchor.y},")
            appendLine("  \"projectilePreviewX\": ${projectilePreviewAnchor.x},")
            appendLine("  \"projectilePreviewY\": ${projectilePreviewAnchor.y},")
            appendLine("  \"viewportVisibleWidth\": ${formatFloat(engine.viewport.visibleWidth)},")
            appendLine("  \"viewportVisibleHeight\": ${formatFloat(engine.viewport.visibleHeight)},")
            appendLine("  \"previewReferencePath\": \"tools/aod_7_effect.png\",")
            appendLine("  \"screenshotPath\": null,")
            appendLine("  \"screenshotAttemptPath\": \"${escapeJson(screenshotAttemptPath.toAbsolutePath().toString())}\",")
            appendLine("  \"elapsedSeconds\": ${"%.3f".format(java.util.Locale.ROOT, elapsed)},")
            appendLine("  \"failureReason\": ${jsonString(failureReason)}")
            appendLine("}")
        }

        try {
            Files.createDirectories(outputDir)
            Files.writeString(telemetryPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            log.info("[ASTD-Automation] telemetry state=$state projectileObserved=$projectileObserved vfxObserved=$vfxObserved path=$telemetryPath")
        } catch (t: Throwable) {
            log.warn("[ASTD-Automation] failed to write telemetry: ${t.message}", t)
        }
    }

    private fun writeDiagnostics(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI? = findArcFlare(engine),
    ) {
        if (!ASTDInGameAutomationScenario.isEnabled()) return

        val displayMode = try { Display.getDisplayMode() } catch (_: Throwable) { null }
        val displayWidth = try { Display.getWidth() } catch (_: Throwable) { -1 }
        val displayHeight = try { Display.getHeight() } catch (_: Throwable) { -1 }
        val displayPixelScale = try { Display.getPixelScaleFactor() } catch (_: Throwable) { -1f }
        val viewport = engine.viewport
        val shipSprite = try { ship?.spriteAPI } catch (_: Throwable) { null }
        val json = buildString {
            appendLine("{")
            appendLine("  \"source\": \"ASTD\",")
            appendLine("  \"scenario\": \"${ASTDInGameAutomationScenario.SCENARIO_ID}\",")
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
            appendLine("  \"elapsedSeconds\": ${"%.3f".format(java.util.Locale.ROOT, elapsed)}")
            appendLine("}")
        }
        log.info("[ASTD-Automation] diagnostics state=$state json=${json.lines().joinToString(" ")}")
    }

    private fun writeScreenshotAttempt(stage: String) {
        if (!ASTDInGameAutomationScenario.isEnabled()) return
        val outputDir = ASTDInGameAutomationScenario.outputDir()
        val path = outputDir.resolve(ASTDInGameAutomationScenario.SCREENSHOT_ATTEMPT_FILE)
        val text = "stage=$stage\nresult=attempt-recorded\nreason=ASTD combat plugin has no active OpenGL readback hook; SSOptimizer smoke script records this path for first-version evidence.\n"
        try {
            Files.createDirectories(outputDir)
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        } catch (_: Throwable) {
        }
    }

    private fun jsonString(value: String?): String = value?.let { "\"${escapeJson(it)}\"" } ?: "null"

    private fun formatFloat(value: Float): String = "%.4f".format(java.util.Locale.ROOT, value)

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
