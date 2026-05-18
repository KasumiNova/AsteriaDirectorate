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
    fun `automation combat plugin writes required evidence fields`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))

        listOf(
            "scenario",
            "state",
            "shipId",
            "weaponId",
            "projectileSpecId",
            "projectileObserved",
            "vfxObserved",
            "captureCenterX",
            "captureCenterY",
            "subjectAnchorX",
            "subjectAnchorY",
            "projectilePreviewX",
            "projectilePreviewY",
            "viewportVisibleWidth",
            "viewportVisibleHeight",
            "previewReferencePath",
            "screenshotAttemptPath",
        ).forEach { field ->
            assertTrue(source.contains("\\\"$field\\\""), "missing telemetry field: $field")
        }
    }

    @Test
    fun `automation diagnostics are written outside SSOptimizer telemetry hook`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val diagnosticsBody = source.substringAfter("private fun writeDiagnostics(").substringBefore("private fun writeScreenshotAttempt")

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

        assertTrue(source.contains("captureCenter = Vector2f(0f, 0f)"), "camera should center on the automation subject")
        assertTrue(source.contains("playerAnchor = Vector2f(-360f, 0f)"), "ship should be visible while staying clear of the projectile ROI")
        assertTrue(source.contains("projectilePreviewAnchor = Vector2f(300f, 0f)"), "projectile VFX preview should sit clear of ship sprite overlap")
        assertTrue(source.contains("enemyAnchor = Vector2f(900f, 0f)"), "enemy target should stay beyond the preview projectile")
        assertTrue(source.contains("visibleHeight = 600f"), "parity capture should keep projectile scale close to the preview reference")
        assertTrue(lockCameraBody.contains("viewport.set("), "camera should set an aspect-correct world viewport")
        assertTrue(lockCameraBody.contains("displayAspect"), "camera should use the active display aspect ratio")
        assertFalse(lockCameraBody.contains("setViewMult("), "square setViewMult capture stretches into 16:9 screenshots")
    }

    @Test
    fun `automation fallback projectile is pinned in screenshot preview window`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))

        assertTrue(source.contains("private var fallbackProjectile"), "fallback projectile should be retained for screenshot staging")
        assertTrue(source.contains("pinFallbackProjectileForEvidence"), "fallback projectile should be stabilized for visual evidence")
        assertTrue(source.contains("pinAod7ProjectilesForEvidence"), "all observed AOD-7 projectiles should be stabilized for visual evidence")
        assertTrue(source.contains("projectile.location.set(projectilePreviewAnchor)"), "projectile should stay at the preview anchor while screenshots are captured")
        assertTrue(source.contains("projectile.velocity.set(0f, 0f)"), "projectile should not race out of the capture window")
        assertTrue(source.contains("projectile.facing = 0f"), "projectile VFX should face along the visible firing axis")
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
        assertTrue(script.contains("ship template scale ratio"), "verification should guard against non-uniform ship flattening")
        assertTrue(script.contains("EXPECTED_ROTATED_SHIP_ASPECT"), "verification should compare ship shape to the rotated source sprite")
    }
}
