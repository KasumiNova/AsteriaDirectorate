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
    fun `arc production automation scenario stages redesigned ships for vfx and tooltip evidence`() {
        assertEquals(
            "arc_production_ships_vfx_tooltip",
            ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID,
        )
        assertTrue(
            Files.exists(Path.of("contents/data/missions/arc_production_ships_vfx_tooltip/MissionDefinition.java")),
            "arc production scenario must have a concrete mission for SSOptimizer launch",
        )

        val scenarios = JSONObject(Files.readString(Path.of("contents/data/config/astd_automation_scenarios.json"))).getJSONArray("scenarios")
        val scenario = (0 until scenarios.length())
            .map { scenarios.getJSONObject(it) }
            .firstOrNull { it.getString("id") == ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID }
        assertTrue(scenario != null, "missing arc production VFX/tooltip automation scenario")
        assertEquals("arc_production_ships_vfx_tooltip", scenario.getString("missionId"))

        val shipIds = scenario.getJSONArray("shipIds")
        listOf("astd_arc_jet", "astd_plasma_arch", "astd_radiation_belt").forEach { id ->
            assertTrue((0 until shipIds.length()).any { shipIds.getString(it) == id }, "scenario missing ship id: $id")
        }

        val requiredEvidence = scenario.getJSONArray("requiredEvidence")
        listOf(
            "arcJetShockwaveFrames",
            "arcJetShockwaveRadius",
            "arcJetShockwaveFluxPressure",
            "plasmaArchShieldOpen",
            "plasmaArchSystemActive",
            "plasmaArchShieldArcEmissions",
            "radiationBeltSystemAfterimages",
            "arcJetTooltip",
            "plasmaArchTooltip",
            "radiationBeltTooltip",
            "arcJetTooltipKeys",
            "plasmaArchTooltipKeys",
            "radiationBeltTooltipKeys",
        ).forEach { key ->
            assertTrue((0 until requiredEvidence.length()).any { requiredEvidence.getString(it) == key }, "scenario missing evidence key: $key")
        }
    }

    @Test
    fun `arc production automation has combat plugin branch and verifier checks`() {
        val scenario = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt"))
        val plugin = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val verifier = Files.readString(Path.of("tools/verify_ingame_vfx_automation.py"))

        assertTrue(scenario.contains("isArcProductionEnabled"), "scenario helper should expose the arc production mode")
        assertTrue(plugin.contains("advanceArcProductionScenario"), "automation plugin must branch into ARC production staging")
        assertFalse(plugin.contains("writeArcProductionTelemetry"), "SSOptimizer only patches writeTelemetry; ARC must not expose an unpatched empty hook")
        assertTrue(plugin.contains("arcProductionTelemetryShip"), "ARC automation should route screenshot evidence through the patched telemetry hook")
        listOf(
            "arcJetShockwaveFrames",
            "arcJetShockwaveRadius",
            "arcJetShockwaveFluxPressure",
            "plasmaArchShieldOpen",
            "plasmaArchSystemActive",
            "plasmaArchShieldArcEmissions",
            "radiationBeltSystemAfterimages",
            "arcJetTooltip",
            "plasmaArchTooltip",
            "radiationBeltTooltip",
            "arcJetTooltipKeys",
            "plasmaArchTooltipKeys",
            "radiationBeltTooltipKeys",
        ).forEach { key ->
            assertTrue(plugin.contains(key), "automation plugin missing evidence key: $key")
            assertTrue(verifier.contains(key), "verification script missing evidence key: $key")
        }
        assertTrue(verifier.contains("arc_production_ships_vfx_tooltip"), "verification script must accept the ARC production scenario")
    }

    @Test
    fun `arc production automation force deploys mission reserves instead of waiting at deployment screen`() {
        val plugin = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val arcAdvanceBody = plugin
            .substringAfter("private fun advanceArcProductionScenario(")
            .substringBefore("private fun arcProductionEvidenceReady(")
        val initBody = plugin
            .substringAfter("override fun init(engine: CombatEngineAPI)")
            .substringBefore("override fun advance(")

        assertTrue(plugin.contains("setPaused(false)"), "ARC automation must leave the paused deployment screen")
        assertTrue(plugin.contains("setDoNotEndCombat(true)"), "ARC automation should keep the staged VFX scene alive")
        assertTrue(plugin.contains("deployArcProductionReserveShips"), "ARC automation must force deploy mission reserve ships")
        assertTrue(plugin.contains("spawnFleetMember"), "ARC automation should deploy the real mission fleet members")
        assertTrue(plugin.contains("removeFromReserves"), "ARC automation should not repeatedly spawn reserve ships")
        assertTrue(plugin.contains("arcProductionMissingShips"), "ARC diagnostics should report missing staged hulls")
        assertTrue(plugin.contains("arcProductionDeployedShipIds"), "ARC automation diagnostics should list deployed production hull ids")
        assertTrue(plugin.contains("arcProductionDeployedVariantIds"), "ARC automation diagnostics should list deployed production variant ids")
        assertFalse(
            arcAdvanceBody.contains("arcJet ?: plasmaArch ?: radiationBelt ?: return"),
            "ARC automation must not silently return before diagnostics when ships are not deployed",
        )
        assertFalse(
            initBody.contains("deployArcProductionReserveShips(engine)"),
            "ARC automation must not spawn fleet members during init before Starsector combat renderers are initialized",
        )
        assertTrue(
            arcAdvanceBody.contains("deployArcProductionReserveShips(engine)"),
            "ARC automation should force deploy reserves during advance after combat initialization",
        )
    }

    @Test
    fun `arc production automation lets plasma arch tactical system be driven by its vanilla ai`() {
        val plugin = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val arcAdvanceBody = plugin
            .substringAfter("private fun advanceArcProductionScenario(")
            .substringBefore("private fun arcProductionEvidenceReady(")

        assertFalse(
            arcAdvanceBody.contains("plasmaArch.useSystem()"),
            "ARC automation must not force plasma_arch system activation when validating FORTRESS_SHIELD AI",
        )
        assertTrue(
            arcAdvanceBody.contains("plasmaArch?.shield?.let { if (!it.isOn) it.toggleOn() }"),
            "ARC automation should keep plasma_arch shield online so FORTRESS_SHIELD AI has valid shield context",
        )
        assertTrue(
            plugin.contains("preserveAI: Boolean = false"),
            "staged automation must make AI preservation explicit instead of globally clearing ship AI",
        )
        assertTrue(
            plugin.contains("plasmaArch?.let { stabilizeShip(it, arcProductionAnchors.getValue(\"astd_plasma_arch\"), 0f, allowFire = false, preserveAI = true) }"),
            "ARC automation must preserve plasma_arch ship AI so its vanilla system AI can run",
        )
        assertTrue(
            plugin.contains("private fun shouldPreserveArcProductionAI(side: FleetSide, hullId: String): Boolean"),
            "ARC automation must decide AI preservation before the forced reserve spawn clears ship AI",
        )
        assertTrue(
            plugin.contains("hullId == ASTDArcProductionShipIds.HULL_PLASMA_ARCH"),
            "plasma_arch AI must be preserved during forced reserve deployment, not only during later arrangement",
        )
        assertTrue(
            plugin.contains("pressurePlasmaArchForSystemAI"),
            "ARC automation must create real enemy pressure instead of waiting in a static no-fire scene",
        )
        assertTrue(
            plugin.contains("ship.setShipTarget(plasmaArch)") && plugin.contains("allowFire = true, preserveAI = true"),
            "enemy pressure ships should keep AI/fire control and target plasma_arch",
        )
        listOf(
            "plasmaArchSystemId",
            "plasmaArchSystemState",
            "plasmaArchSystemCanBeActivated",
            "plasmaArchSystemEffectLevel",
            "plasmaArchShipAI",
            "plasmaArchFluxLevel",
            "plasmaArchCurrFlux",
            "plasmaArchMaxFlux",
            "plasmaArchHardFlux",
            "plasmaArchHardFluxLevel",
            "plasmaArchSinceLastDamageTaken",
            "plasmaArchOverloadedOrVenting",
            "plasmaArchShieldOn",
            "plasmaArchShieldActiveArc",
            "plasmaArchAIFlags",
            "plasmaArchAIFlagIncomingDamage",
            "plasmaArchAIFlagCriticalDpsDanger",
            "plasmaArchAIFlagKeepShieldsOn",
            "plasmaArchVanillaSystemAI",
            "plasmaArchVanillaSystemAIError",
            "plasmaArchAIFlagBiggestThreatTargetHullId",
            "plasmaArchAIFlagBiggestThreatTargetVariantId",
            "plasmaArchAIFlagTargetForSystemHullId",
            "plasmaArchAIFlagTargetForSystemVariantId",
            "plasmaArchAIFlagManeuverTargetHullId",
            "plasmaArchAIFlagManeuverTargetVariantId",
            "plasmaArchEnemyPressureShips",
            "plasmaArchEnemyTargetingShips",
            "plasmaArchEnemyFiringWeapons",
            "plasmaArchEnemyProjectiles",
            "plasmaArchSystemSpecAiScript",
            "plasmaArchSystemSpecFpsBaseCap",
            "plasmaArchSystemSpecToggle",
            "plasmaArchSystemSpecFiringAllowed",
            "plasmaArchSystemSpecTags",
        ).forEach { field ->
            assertTrue(plugin.contains("\\\"$field\\\""), "ARC diagnostics must expose plasma_arch FORTRESS_SHIELD AI field: $field")
        }
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
            "runtimeLastProjectileSpecId",
            "runtimeLastPresetId",
            "referenceVisibleLength",
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
        assertTrue(verifier.contains("_arc_production_data_from_log"), "verifier should read ARC evidence from ASTD diagnostics when SSOptimizer telemetry is scenario-specific")
        assertTrue(verifier.contains("_merge_screenshot_evidence"), "verifier should merge SSOptimizer screenshot files into ASTD ARC diagnostics")
        assertTrue(verifier.contains("ASTD_DIAGNOSTICS_PATTERN"), "verifier should parse ASTD diagnostics JSON from starsector.log")
        assertFalse(source.contains("writeArcProductionTelemetry"), "ARC automation must use the real SSOptimizer-patched hook")
    }

    @Test
    fun `automation diagnostics are written outside SSOptimizer telemetry hook`() {
        val source = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val diagnosticsBody = source.substringAfter("private fun writeDiagnostics(").substringBefore("private fun jsonString")

        assertTrue(source.contains("private fun writeDiagnostics("), "diagnostics should not be embedded only in writeTelemetry")
        assertTrue(source.contains("writeDiagnostics(combatEngine, \"Completed\""), "render-time diagnostics should run after completed frame staging")
        assertTrue(source.contains("writeDiagnostics(engine, \"CombatReady\""), "init diagnostics should run even when telemetry is intercepted")
        assertTrue(source.contains("runtime.lastPresetId == ASTDInGameAutomationScenario.VFX_PRESET_ID"), "VFX observation should key off the runtime preset actually used")
        assertTrue(source.contains("runtime.trackedCount > 0"), "VFX observation should require a tracked runtime instead of only a projectile id string")
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
            "runtimeLastProjectileSpecId",
            "runtimeLastPresetId",
            "referenceVisibleLength",
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
        assertTrue(source.contains("REFERENCE_CAPTURE_ELAPSED_SECONDS = 0.3004f"), "automation should capture at the same time as the AOD-7 curved preview parity reference")
        assertFalse(source.contains("CAPTURE_TRAIL_MIN_LENGTH = 840f"), "capture should not wait for a hardcoded full tail cap when the reference frame is shorter")
        assertTrue(source.contains("driveFallbackProjectileCurve"), "automation should drive a real curved projectile path for curve parity screenshots")
        assertTrue(source.contains("AUTOMATION_CURVE_AMOUNT = 96f"), "automation curve should match the preview curve reference amplitude")
        assertTrue(source.contains("previewFlightTrack"), "automation curve should use the shared preview flight track instead of a private curve formula")
        assertTrue(source.contains("projectile.location.set(curvePositionAt(age))"), "fallback projectile location should follow the shared preview curve path")
        assertTrue(source.contains("automationReferenceWorldUnitsPerPixel"), "automation should use stable max-zoom reference scale")
        assertTrue(source.contains("ASTDProjectileVfxLayout.referenceWorldUnitsPerPixel"), "automation scale should share the runtime reference projection")
        assertTrue(source.contains("track.headOffset.x * scale"), "automation curve x offset must use stable reference world units")
        assertTrue(source.contains("track.headOffset.y * scale"), "automation curve y offset must use stable reference world units")
        assertFalse(source.contains("FALLBACK_PROJECTILE_SPEED * age"), "automation should not drift away from preview track timing by integrating weapon speed")
        assertFalse(source.contains("projectile.location.set(projectilePreviewAnchor)"), "projectile should not be pinned to the preview anchor every frame")
        assertFalse(source.contains("projectile.location.y = projectilePreviewAnchor.y"), "curve parity automation must not flatten projectile history to a straight visual lane")
        assertFalse(source.contains("projectile.velocity.set(0f, 0f)"), "projectile velocity should not be zeroed during visual evidence capture")
    }

    @Test
    fun `smoke automation mode requests high resolution screenshot capture`() {
        val script = Files.readString(Path.of("tools/smoke_test_game_launch.sh"))
        val gradle = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(
            script.contains("""[[ "${'$'}MODE" == "game" || "${'$'}MODE" == "automation" || "${'$'}MODE" == "campaign-acceptance" ]]"""),
            "automation and campaign acceptance modes should enter the game path",
        )
        assertTrue(script.contains("ASTD_SMOKE_START_RES:-2560x1440"), "automation mode should default to 2560x1440")
        assertTrue(script.contains("-Dssoptimizer.automation.enabled=true"), "automation mode should enable SSOptimizer automation")
        assertTrue(script.contains("ASTD_AUTOMATION_SCENARIO:-arc_flare_aod7_basic"), "automation mode should allow selecting the ARC production scenario")
        assertTrue(script.contains("-Dssoptimizer.automation.requireScreenshotFile=true"), "automation mode should require a concrete screenshot")
        assertTrue(script.contains("-Dssoptimizer.automation.outputDir="), "automation mode should write evidence to a known output dir")
        assertTrue(gradle.contains("smokeTestGame"), "Gradle should expose the full in-game smoke task")
        assertTrue(gradle.contains("\"automation\""), "smokeTestGame must use SSOptimizer automation mode")
        assertTrue(
            gradle.contains("ASTD_AUTOMATION_SCENARIO") && gradle.contains(ASTDInGameAutomationScenario.ARC_PRODUCTION_SCENARIO_ID),
            "smokeTestGame should default to ARC production automation acceptance",
        )
        assertTrue(
            gradle.contains("verify_ingame_vfx_automation.py") && gradle.contains("--log"),
            "smokeTestGame must run the automation evidence verifier after launch smoke",
        )
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
