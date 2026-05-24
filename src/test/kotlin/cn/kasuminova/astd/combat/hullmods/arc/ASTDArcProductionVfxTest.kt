package cn.kasuminova.astd.combat.hullmods.arc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDArcProductionVfxTest {

    @Test
    fun `production vfx helper uses BoxUtil for link visuals and only fails loudly in automation`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt"),
        )

        assertTrue(source.contains("BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter"), "link visuals should use BoxUtil trail entities")
        assertTrue(source.contains("ASTDInGameAutomationScenario.isArcProductionEnabled()"), "automation acceptance should keep fail-loud VFX failures")
        assertTrue(source.contains("Global.getLogger(ASTDArcProductionVfx::class.java)"), "production VFX failures should be logged")
        assertTrue(source.contains("?.setGlobalTimer("), "temporary BoxUtil trail entities must have explicit fade timers")
        assertTrue(source.contains("emitSegmentedArc"), "plasma shield arcs should be routed through BoxUtil segmented trails")
        assertFalse(source.contains("spawnEmpArcVisual"), "plasma shield arcs should not use original EMP arc visuals")
        assertFalse(source.contains("addHitParticle"), "do not add original particle fallback for link visuals")
        assertFalse(source.contains("addSmoothParticle"), "do not add original particle fallback for link visuals")
        assertFalse(source.contains("addNebulaParticle"), "node pulse visuals should not fall back to original nebula particles")
    }

    @Test
    fun `production vfx helper exposes telemetry counters for acceptance`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt"),
        )

        listOf(
            "arcJetLinkedShips",
            "arcJetActiveSystemLinks",
            "plasmaArchShieldOpen",
            "plasmaArchSystemActive",
            "plasmaArchShieldArcEmissions",
            "radiationBeltPursuitLinks",
            "radiationBeltSystemAfterimages",
        ).forEach { key ->
            assertTrue(source.contains(key), "missing VFX telemetry counter key: $key")
        }
    }

    @Test
    fun `production runtime call sites route acceptance vfx through helper`() {
        val callSites = mapOf(
            "ASTDArcSharedTacticalNetworkHullMod.kt" to "emitArcJetPassiveLink",
            "ASTDArcSharedFluxNetworkSystemStats.kt" to "emitArcJetActiveFluxLink",
            "ASTDDistributedPursuitNetworkHullMod.kt" to "emitRadiationPursuitPing",
            "ASTDPlasmaArmorShieldHullMod.kt" to "emitPlasmaShieldArc",
            "ASTDPlasmaArmorShieldBoostSystemStats.kt" to "emitPlasmaShieldArc",
            "ASTDLimitTemporalThrusterSystemStats.kt" to "emitTemporalThrusterAfterimage",
        )

        callSites.forEach { (file, helperCall) ->
            val path = if (file.contains("SystemStats")) {
                "src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/$file"
            } else {
                "src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/$file"
            }
            val source = Files.readString(Path.of(path))
            assertTrue(source.contains("ASTDArcProductionVfx.$helperCall"), "$file should call $helperCall")
        }
    }

    @Test
    fun `plasma shield boost excludes point defense weapons and ionized recoil uses total flux for proc chance`() {
        val plasmaBoost = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDPlasmaArmorShieldBoostSystemStats.kt"),
        )
        assertTrue(
            plasmaBoost.contains("ASTDArcCombatUtil.isNonPdNonMissileWeapon"),
            "plasma shield boost should exclude PD and missile weapons from the RoF penalty",
        )
        assertTrue(
            plasmaBoost.contains("ASTDArcCombatUtil.applyRefireDelayMult"),
            "plasma shield boost should apply the RoF penalty through eligible weapon refire delay",
        )
        assertTrue(
            plasmaBoost.contains("ASTDArcCombatUtil.restoreRefireDelays(ship)"),
            "plasma shield boost should restore cached weapon refire delays when ending",
        )
        val combatUtil = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcCombatUtil.kt"),
        )
        assertTrue(
            combatUtil.contains("baseRefireDelayByWeapon.getOrPut(weapon)"),
            "refire delay suppression must compute from an original cached baseline",
        )
        assertTrue(
            combatUtil.contains("fun restoreRefireDelay"),
            "refire delay suppression must expose restoration",
        )
        assertFalse(
            plasmaBoost.substringBefore("override fun unapply").contains("stats.ballisticRoFMult.modifyMult"),
            "plasma shield boost must not use global ballistic RoF because that affects PD weapons",
        )

        val ionized = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDIonizedRecoilAccumulatorHullMod.kt"),
        )
        assertTrue(ionized.contains("private fun fluxLevel(): Float"), "ionized recoil proc chance should use total flux level")
        assertTrue(ionized.contains("val chance = procChance(fluxLevel())"), "proc chance must not be based on hard flux")
        assertTrue(ionized.contains("val hardFluxLevel = hardFluxLevel()"), "shield pierce chance should still use hard flux level")
    }

    @Test
    fun `arc production range and fire control modifiers cover strict non missile wording`() {
        val fireControl = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcAdvancedFireControlHullMod.kt"),
        )
        assertTrue(
            fireControl.contains("TARGET_FULL_RAMP_WEAPON_FLUX_MULT = 0.60f"),
            "advanced fire control should reach final -40% weapon flux relative to the base ship, not only relative to its baseline penalty",
        )

        val targeting = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcAdvancedTargetingSystemHullMod.kt"),
        )
        assertTrue(
            targeting.contains("stats.beamWeaponRangeBonus.modifyPercent(id, RANGE_PERCENT)"),
            "advanced targeting says non-missile range and should include beam range",
        )

        val pursuit = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDDistributedPursuitNetworkHullMod.kt"),
        )
        assertTrue(
            pursuit.contains("stats.beamWeaponRangeBonus.modifyPercent(id, linkStrength * 100f)"),
            "distributed pursuit says weapon range and should include beam range",
        )
        assertTrue(
            pursuit.contains("stats.beamWeaponRangeBonus.unmodify(id)"),
            "distributed pursuit must clear beam range modifiers with other range stats",
        )
    }

    @Test
    fun `arc shared networks clean target modifiers and honor system range stats`() {
        val tactical = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcSharedTacticalNetworkHullMod.kt"),
        )
        assertTrue(
            tactical.contains("clearStaleTargets(engine, ship, emptySet())"),
            "passive tactical network must clear cross-ship stat modifiers before invalid source early return",
        )

        val fluxNetwork = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDArcSharedFluxNetworkSystemStats.kt"),
        )
        assertTrue(
            fluxNetwork.contains("ASTDArcCombatUtil.effectiveSystemRange(source, ASTDArcAuraUtil.ARC_JET_SYSTEM_MAX_RANGE)"),
            "active flux network target selection should honor system range bonuses",
        )
        assertTrue(
            fluxNetwork.contains("ASTDArcCombatUtil.effectiveSystemRange(ship, ASTDArcAuraUtil.ARC_JET_SYSTEM_FULL_RANGE)"),
            "active flux network falloff should scale the full-effect range with system range bonuses",
        )
    }

    @Test
    fun `arc production verifier checks screenshot pixels for scenario regions`() {
        val script = Files.readString(Path.of("tools/verify_ingame_vfx_automation.py"))

        assertTrue(script.contains("def _check_arc_production_screenshot_pixels"), "ARC production verifier needs scenario-specific screenshot content checks")
        assertTrue(script.contains("def _select_arc_production_screenshot"), "ARC production verifier should choose a clean combat frame when the primary screenshot is still on the deployment UI")
        assertTrue(script.contains("def _arc_production_deployment_overlay_score"), "ARC production verifier should reject deployment UI contaminated screenshots")
        assertTrue(script.contains("arc production deployment overlay still visible"), "ARC production verifier should fail loudly on deployment overlay contamination")
        assertTrue(script.contains("ARC_PRODUCTION_SCREENSHOT_REGIONS"), "ARC production screenshot checks should be region based")
        assertTrue(script.contains("arc production VFX bright colored pixels"), "ARC verifier should reject blank or wrong screenshots")
        assertTrue(script.contains("_select_arc_production_screenshot"), "ARC verifier should select the actual combat evidence frame before pixel checks")
        assertTrue(
            script.contains("ARC production requires a concrete screenshotPath"),
            "ARC production verifier must not allow screenshotAttemptPath-only evidence to bypass pixel checks",
        )
    }

    @Test
    fun `limit temporal thruster player time compensation is owned by the active player ship`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDLimitTemporalThrusterSystemStats.kt"),
        )

        assertTrue(source.contains("PLAYER_TIME_MULT_OWNER_KEY"), "player time multiplier cleanup should track the owning ship")
        assertTrue(source.contains("System.identityHashCode(ship)"), "player time multiplier owner should be ship-specific")
        assertFalse(
            source.contains("} else {\n                engine.timeMult.unmodify(\"" + "$" + "{id}_player\")"),
            "non-player ships must not clear the current player's time compensation",
        )
    }

    @Test
    fun `arc production tooltip evidence validates renderer block keys`() {
        val plugin = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"),
        )

        assertTrue(plugin.contains("tooltipBlocksResolved"), "automation tooltip evidence should validate renderer block contracts")
        assertTrue(plugin.contains("ASTDArcProductionTooltipContracts"), "automation should use shared ARC tooltip contracts")
        assertTrue(plugin.contains("arcJetTooltipKeys"), "diagnostics should expose resolved tooltip key counts")
        assertTrue(plugin.contains("plasmaArchTooltipKeys"), "diagnostics should expose resolved tooltip key counts")
        assertTrue(plugin.contains("radiationBeltTooltipKeys"), "diagnostics should expose resolved tooltip key counts")
        val verifier = Files.readString(Path.of("tools/verify_ingame_vfx_automation.py"))
        assertTrue(verifier.contains("ARC_PRODUCTION_TOOLTIP_KEY_MINIMUMS"), "verifier should reject missing renderer block key evidence")
    }

    @Test
    fun `radiation belt system visual has independent telemetry evidence`() {
        val vfx = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt"))
        val system = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDLimitTemporalThrusterSystemStats.kt"))
        val plugin = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val verifier = Files.readString(Path.of("tools/verify_ingame_vfx_automation.py"))

        assertTrue(vfx.contains("TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES"), "VFX helper should expose temporal thruster telemetry")
        assertTrue(
            vfx.contains("incrementCounter(engine, TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES)"),
            "temporal thruster helper should increment its own VFX telemetry",
        )
        assertTrue(system.contains("emitTemporalThrusterAfterimage"), "temporal thruster should route its VFX through the telemetry helper")
        assertTrue(plugin.contains("radiationBeltSystemAfterimages"), "automation diagnostics should publish system VFX evidence")
        assertTrue(verifier.contains("radiationBeltSystemAfterimages"), "verifier should require system VFX evidence, not only pursuit network links")
    }
}
