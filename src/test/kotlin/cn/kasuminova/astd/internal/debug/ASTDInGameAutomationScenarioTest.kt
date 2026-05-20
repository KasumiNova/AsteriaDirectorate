package cn.kasuminova.astd.internal.debug

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDInGameAutomationScenarioTest {
    @Test
    fun `automation scenario ids match ship weapon projectile and vfx config`() {
        assertTrue(Files.readString(Path.of("contents/data/hulls/astd_arc_flare.ship")).contains("\"hullId\": \"${ASTDInGameAutomationScenario.SHIP_ID}\""))
        assertTrue(Files.readString(Path.of("contents/data/hulls/astd_arc_flare.ship")).contains("\"WS MAIN\": \"${ASTDInGameAutomationScenario.WEAPON_ID}\""))
        assertTrue(Files.readString(Path.of("contents/data/weapons/astd_aod7.wpn")).contains("\"projectileSpecId\": \"${ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID}\""))
        assertTrue(Files.readString(Path.of("contents/data/weapons/proj/astd_aod7_shot.proj")).contains("ProjectileSpecOnFireDispatcher"))

        val scenarios = JSONObject(Files.readString(Path.of("contents/data/config/astd_automation_scenarios.json"))).getJSONArray("scenarios")
        val scenario = scenarios.getJSONObject(0)
        assertEquals(ASTDInGameAutomationScenario.SCENARIO_ID, scenario.getString("id"))
        assertEquals(ASTDInGameAutomationScenario.VARIANT_ID, scenario.getString("variantId"))
        assertEquals(ASTDInGameAutomationScenario.PROJECTILE_SPEC_ID, scenario.getString("projectileSpecId"))
        assertEquals(ASTDInGameAutomationScenario.VFX_PRESET_ID, scenario.getString("vfxPresetId"))
    }

    @Test
    fun `mission installs automation combat plugin`() {
        val mission = Files.readString(Path.of("contents/data/missions/arc_flare_aod7_basic/MissionDefinition.java"))

        assertTrue(mission.contains("ASTDAutomationCombatPlugin"))
        assertTrue(mission.contains("ASTDInGameAutomationScenario.VARIANT_ID"))
        assertTrue(mission.contains("api.addPlugin(new ASTDAutomationCombatPlugin())"))
    }

    @Test
    fun `automation combat plugin exposes screenshot hook and diagnostics fields`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))

        listOf(
            "viewportVisibleWidth",
            "viewportVisibleHeight",
            "runtimeElapsedSeconds",
            "runtimeVisibleLength",
            "runtimeBeamAlpha",
            "runtimeWorldUnitsPerPixel",
        ).forEach { field ->
            assertTrue(source.contains("\\\"$field\\\""), "missing diagnostics field: $field")
        }
        assertTrue(source.contains("private fun writeTelemetry("), "automation must keep the SSOptimizer telemetry hook")
        assertTrue(source.contains("writeTelemetry("), "automation should call the patched telemetry hook")
    }

    @Test
    fun `automation delegates screenshot file writes to SSOptimizer helper`() {
        val scenario = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt"))
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val verifier = Files.readString(Path.of("tools/verify_ingame_vfx_automation.py"))

        assertTrue(scenario.contains("ASTD_TELEMETRY_FILE"), "ASTD telemetry filename must not be shared with SSOptimizer")
        assertFalse(source.contains("GL11.glReadPixels"), "Starsector script sandbox blocks ASTD direct framebuffer file capture")
        assertFalse(source.contains("ImageIO.write"), "Starsector script sandbox blocks ASTD direct screenshot file writes")
        assertTrue(source.contains("SSOptimizer patches this method"), "ASTD should delegate concrete evidence writes to SSOptimizer")
        assertTrue(verifier.contains("ASTD_TELEMETRY_FILE"), "verifier should prefer ASTD-owned telemetry when the shared file is overwritten")
        assertTrue(verifier.contains("_load_preferred_telemetry"), "verifier should load ASTD-owned telemetry before evaluating screenshot evidence")
        assertTrue(verifier.contains("telemetry_path.with_name(ASTD_TELEMETRY_FILE).exists()"), "verifier should accept ASTD telemetry even when the shared telemetry file is missing")
    }

    @Test
    fun `automation diagnostics are written outside SSOptimizer telemetry hook`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val diagnosticsBody = source.substringAfter("private fun writeDiagnostics(").substringBefore("private fun jsonString")

        assertTrue(source.contains("private fun writeDiagnostics("), "diagnostics should not be embedded only in writeTelemetry")
        assertTrue(source.contains("writeDiagnostics(combatEngine, \"Completed\""), "render-time diagnostics should run after completed frame staging")
        assertTrue(source.contains("writeDiagnostics(engine, \"CombatReady\""), "init diagnostics should run even when telemetry is intercepted")
        listOf(
            "displayWidth",
            "displayHeight",
            "displayPixelScale",
            "displayModeWidth",
            "displayModeHeight",
            "viewportVisibleWidth",
            "viewportVisibleHeight",
            "viewportWorldXToScreenX",
            "viewportWorldYToScreenY",
            "shipSpriteWidth",
            "shipSpriteHeight",
        ).forEach { field ->
            assertTrue(diagnosticsBody.contains("\\\"$field\\\""), "missing diagnostics field: $field")
        }
        assertTrue(diagnosticsBody.contains("[ASTD-Automation] diagnostics state="), "script-safe diagnostics should be emitted to the game log")
        assertFalse(diagnosticsBody.contains("Files.writeString"), "script diagnostics must not use direct file IO")
    }

    @Test
    fun `automation capture keeps ship and projectile preview inside viewport`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val lockCameraBody = source.substringAfter("private fun lockCamera(").substringBefore("private fun arrangeShips(")

        assertTrue(source.contains("captureCenter = Vector2f(100f, 0f)"), "camera should keep the mature projectile trail in the verification ROI")
        assertTrue(source.contains("playerAnchor = Vector2f(-260f, 0f)"), "ship should be visible while staying clear of the projectile ROI")
        assertTrue(source.contains("projectilePreviewAnchor = Vector2f(40f, 0f)"), "projectile VFX should start clear of ship sprite overlap and fly through the ROI")
        assertTrue(source.contains("enemyAnchor = Vector2f(900f, 0f)"), "enemy target should stay beyond the preview projectile")
        assertTrue(source.contains("visibleHeight = 600f"), "parity capture should keep projectile scale close to the preview reference")
        assertTrue(lockCameraBody.contains("viewport.set("), "camera should set an aspect-correct world viewport")
        assertTrue(lockCameraBody.contains("displayAspect"), "camera should use the active display aspect ratio")
        assertFalse(lockCameraBody.contains("setViewMult("), "square setViewMult capture stretches into 16:9 screenshots")
    }

    @Test
    fun `automation fallback projectile flies before screenshot capture`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val weaponCsv = Files.readAllLines(Path.of("contents/data/weapons/weapon_data.csv"))
        val header = weaponCsv.first().split(",")
        val aod7 = weaponCsv.first { it.split(",")[1] == ASTDInGameAutomationScenario.WEAPON_ID }.split(",")
        val projSpeed = aod7[header.indexOf("proj speed")].toFloat()

        assertTrue(source.contains("private var fallbackProjectile"), "fallback projectile should be retained for screenshot staging")
        assertTrue(source.contains("fallbackProjectileSpawnedAt"), "fallback projectile should expose its flight age for capture staging")
        assertTrue(source.contains("evidenceReady"), "automation should wait for a mature trail before screenshot capture")
        assertTrue(source.contains("SCREENSHOT_FLIGHT_SECONDS"), "capture delay should be an explicit automation constant")
        assertTrue(source.contains("visualFramesWritten > 0 && elapsed - lastVisualFrameAt < 0.18f"), "first evidence frame should be captured on the completion frame")
        assertTrue(source.contains("FALLBACK_PROJECTILE_SPEED = ${projSpeed.toInt()}f"), "fallback projectile should use weapon_data proj speed instead of weapon range")
        assertTrue(source.contains("referenceCaptureVisibleLength"), "capture length should be derived from the preview reference contract")
        assertTrue(source.contains("previewFlightLayout"), "capture length should share the TS preview flight layout function")
        assertTrue(source.contains("REFERENCE_CAPTURE_ELAPSED_SECONDS = 0.28f"), "automation should capture while the AOD-7 head remains inside the parity ROI")
        assertFalse(source.contains("CAPTURE_TRAIL_MIN_LENGTH = 840f"), "capture should not wait for a hardcoded full tail cap when the reference frame is shorter")
        assertTrue(source.contains("projectile.location.y = projectilePreviewAnchor.y"), "projectile may be kept on the visual lane without resetting traveled distance")
        assertFalse(source.contains("projectile.location.set(projectilePreviewAnchor)"), "projectile should not be pinned to the preview anchor every frame")
        assertFalse(source.contains("projectile.velocity.set(0f, 0f)"), "projectile velocity should not be zeroed during visual evidence capture")
    }

    @Test
    fun `smoke automation mode requests high resolution screenshot capture`() {
        val script = Files.readString(Path.of("tools/smoke_test_game_launch.sh"))

        assertTrue(script.contains("""[[ "${'$'}MODE" == "game" || "${'$'}MODE" == "automation" ]]"""), "automation mode should enter the game path")
        assertTrue(script.contains("ASTD_SMOKE_START_RES:-2560x1440"), "automation mode should default to 2560x1440")
        assertTrue(script.contains("-Dssoptimizer.automation.enabled=true"), "automation mode should enable SSOptimizer automation")
        assertTrue(script.contains("-Dssoptimizer.automation.requireScreenshotFile=true"), "automation mode should require a concrete screenshot")
        assertTrue(script.contains("-Dssoptimizer.automation.outputDir="), "automation mode should write evidence to a known output dir")
    }

    @Test
    fun `verification script validates concrete screenshot pixels and resolution`() {
        val script = Files.readString(Path.of("tools/verify_ingame_vfx_automation.py"))

        assertTrue(script.contains("from PIL import Image"), "verification should inspect the screenshot image")
        assertTrue(script.contains("import cv2"), "verification should use template matching for ship shape")
        assertTrue(script.contains("expected 2560x1440 automation screenshot"), "verification should enforce automation capture resolution")
        assertTrue(script.contains("ship visible pixels"), "verification should require a visible Arc Flare region")
        assertTrue(script.contains("projectile VFX dynamic ROI"), "verification should require visible AOD-7 body VFX through dynamic ROI")
        assertTrue(script.contains("projectile VFX bright head/core pixels"), "verification should require visible AOD-7 filled head VFX")
        assertTrue(script.contains("_crop_projectile_roi(path, (0.36, 0.32, 0.88, 0.70))"), "verification should not use a fixed small projectile pixel window")
        assertTrue(script.contains("suppress_grid_lines=True"), "preview reference cropping should suppress editor grid lines before component matching")
        assertTrue(script.contains("_suppress_preview_grid_lines"), "verification should not compare against grid-contaminated preview components")
        assertTrue(script.contains("_suppress_screenshot_tactical_lines"), "verification should not compare against combat tactical guide line contamination")
        assertTrue(script.contains("_main_projectile_mask"), "verification should measure the projectile component instead of unrelated stars in the ROI")
        assertTrue(script.contains("_crop_projectile_roi_by_bright_core"), "preview parity should anchor dynamic crops on the projectile bright core")
        assertTrue(script.contains("--visual-compare-output"), "verification should be able to emit a side-by-side crop for visual review")
        assertTrue(script.contains("--screenshot"), "verification should allow explicit screenshot input for manual visual review without relaxing telemetry pass criteria")
        assertTrue(script.contains("_write_visual_compare"), "verification should provide visual review evidence instead of relying only on numeric pixel deltas")
        assertTrue(script.contains("def _print_result("), "verification should print screenshot details for both pass and fail cases")
        assertTrue(script.contains("ship template scale ratio"), "verification should guard against non-uniform ship flattening")
        assertTrue(script.contains("EXPECTED_ROTATED_SHIP_ASPECT"), "verification should compare ship shape to the rotated source sprite")
    }
}
