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
        assertTrue(source.contains("spawnEmpArcVisual"), "plasma shield edge arcs should use original EMP arc visuals")
        assertTrue(source.contains("EmpArcEntityAPI.EmpArcParams"), "plasma shield edge arcs should configure EMP arc fade behavior")
        assertTrue(source.contains("setSingleFlickerMode"), "plasma shield edge arcs should flicker once instead of persisting as trail segments")
        assertFalse(source.contains("fun emitSegmentedArc"), "plasma shield arcs should not use custom segmented trail rendering")
        assertFalse(source.contains("addHitParticle"), "do not add original particle fallback for link visuals")
        assertFalse(source.contains("addSmoothParticle"), "do not add original particle fallback for link visuals")
        assertFalse(source.contains("addNebulaParticle"), "node pulse visuals should not fall back to original nebula particles")
    }

    @Test
    fun `plasma shield vfx uses shield edge arcs with required cadence colors and width`() {
        val vfx = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt"),
        )
        assertTrue(vfx.contains("PLASMA_ARC_WIDTH = 18f"), "plasma shield arc width should be doubled")
        assertTrue(vfx.contains("shield.activeArc * 0.1675f"), "shield arcs should be 33% shorter than the previous quarter-shield span")
        assertTrue(
            vfx.contains("MathUtils.getRandomNumberInRange(0.85f, 1f)"),
            "plasma shield arc endpoints should vary up to 15% inward from the shield edge",
        )
        assertTrue(vfx.contains("PLASMA_ARC_SEGMENTS = 3"), "shield arcs should be split into edge-following segments instead of one chord")
        assertTrue(vfx.contains("edgeBiasedShieldPoint"), "shield arc segments should bias intermediate points toward the shield edge")
        assertTrue(vfx.contains("MagicLensFlare.createSharpFlare"), "shield arc endpoints should get small MagicLib lens flare anchors")
        assertTrue(vfx.contains("movementDurOverride = 0f"), "plasma shield arcs should not drift after spawning")
        assertTrue(vfx.contains("movementDurMin = 0f"), "plasma shield arcs should not use internal movement duration")
        assertTrue(vfx.contains("movementDurMax = 0f"), "plasma shield arcs should not use internal movement duration")
        assertTrue(vfx.contains("arc.setWarping(0f)"), "plasma shield arcs should not warp their path after spawning")
        assertFalse(vfx.contains("arc.setWarping(if"), "plasma shield arcs must not conditionally enable warping")
        assertTrue(vfx.contains("PLASMA_SHIELD_BLUE_RING"), "passive shield ring color should be blue")
        assertTrue(vfx.contains("PLASMA_SHIELD_PURPLE_RING"), "active shield ring color should shift to purple")

        val hullmod = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDPlasmaArmorShieldHullMod.kt"),
        )
        assertTrue(
            hullmod.contains("MathUtils.getRandomNumberInRange(0.5f, 1f)"),
            "passive plasma shield edge arcs should spawn twice as fast as the previous 1 to 2 second cadence",
        )
        assertTrue(
            hullmod.contains("MathUtils.getRandomNumberInRange(0.25f, 0.5f)"),
            "boosted plasma shield edge arcs should keep the 2x cadence over the faster passive interval",
        )
        assertTrue(
            hullmod.contains("maintainShieldVisualsEvenWhenPaused"),
            "plasma shield colors must be maintained before paused-frame early return",
        )

        val system = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDPlasmaArmorShieldBoostSystemStats.kt"),
        )
        assertTrue(system.contains("ASTDArcProductionVfx.applyPlasmaShieldVisuals"), "system should apply purple shield colors while active")
        assertTrue(system.contains("override fun unapply"), "system should have an explicit cleanup path")
        assertTrue(system.contains("ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, 0f)"), "system cleanup should restore blue shield colors before the shield closes")
        assertFalse(system.contains("PULSE_TIMER_KEY"), "system should not spawn a separate high-frequency shield arc timer")
        assertFalse(system.contains("emitPlasmaShieldArc"), "shield arc cadence should be owned by the plasma shield hullmod")
    }

    @Test
    fun `plasma arch recoil accumulator arcs use doubled visual widths`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDIonizedRecoilAccumulatorHullMod.kt"),
        )

        assertTrue(source.contains("ARC_THICKNESS = 18f"), "targeted recoil arcs should double the prior 9px width")
        assertTrue(source.contains("ARC_VISUAL_THICKNESS = 12f"), "untargeted recoil arcs should double the prior 6px width")
        assertTrue(source.contains("setCoreWidthOverride(ARC_CORE_WIDTH)"), "targeted recoil arcs should keep a proportional core width")
        assertFalse(source.contains(", null, 9f,"), "targeted recoil arcs must not keep the old narrow width")
        assertFalse(source.contains("ship, 6f,"), "untargeted recoil arcs must not keep the old narrow width")
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
            "ASTDPlasmaArmorShieldBoostSystemStats.kt" to "applyPlasmaShieldVisuals",
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
        assertTrue(ionized.contains("HARD_FLUX_CONVERT_FRACTION = 0.02f"), "ionized recoil should convert 2% of current hard flux")
        assertTrue(ionized.contains("val conversionMult = 1f + boostLevel"), "boosted plasma shield should double ionized recoil conversion")
        assertTrue(ionized.contains("convertHardFlux(conversionMult)"), "ionized recoil conversion should scale during the system boost")
        assertTrue(ionized.contains("effectiveRecoilRange()"), "ionized recoil range should be affected by energy projectile weapon range")
        assertTrue(ionized.contains("playSound(\"system_emp_emitter_impact\""), "ionized recoil trigger should play the vanilla EMP emitter impact sound")
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

        val tacticalNetwork = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcSharedTacticalNetworkHullMod.kt"),
        )
        val selfRangeBlock = tacticalNetwork
            .substringAfter("override fun applyEffectsBeforeShipCreation")
            .substringBefore("override fun advanceInCombat")
        assertTrue(
            selfRangeBlock.contains("stats.ballisticWeaponRangeBonus.modifyPercent(modId, SELF_WEAPON_RANGE_PERCENT)"),
            "arc jet self range penalty should stack additively with advanced targeting core",
        )
        assertTrue(
            selfRangeBlock.contains("stats.energyWeaponRangeBonus.modifyPercent(modId, SELF_WEAPON_RANGE_PERCENT)"),
            "arc jet self range penalty should affect energy and beam weapons through the vanilla energy range stat",
        )
        assertFalse(
            selfRangeBlock.contains("beamWeaponRangeBonus"),
            "arc jet self range penalty must not apply an extra beam-specific penalty on top of energy range",
        )
        assertFalse(
            selfRangeBlock.contains("modifyMult"),
            "arc jet self range penalty must not multiplicatively shrink the advanced core bonus",
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
    fun `distributed pursuit network applies bonuses to connected allies`() {
        val pursuit = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDDistributedPursuitNetworkHullMod.kt"),
        )

        assertTrue(
            pursuit.contains("applyNetworkStats(ship, target, linkStrength(target))"),
            "distributed pursuit network should apply each connection bonus to the connected allied target",
        )
        assertTrue(
            pursuit.contains("val stats = target.mutableStats"),
            "distributed pursuit network bonuses must modify the connected target's stats",
        )
        assertTrue(
            pursuit.contains("clearNetworkStats(source, target)"),
            "distributed pursuit network must clear stale target modifiers when links change",
        )
        assertFalse(
            pursuit.contains("applyNetworkStats(ship, linkStrength)"),
            "distributed pursuit network must not aggregate connected allies into a self-only source buff",
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
    fun `limit temporal thruster stat boost is limited to active window`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDLimitTemporalThrusterSystemStats.kt"),
        )

        assertTrue(
            source.contains("val level = if (state == ShipSystemStatsScript.State.ACTIVE) 1f else 0f"),
            "limit temporal thruster design says the instant stat boost lasts for the 2s active window only",
        )
        assertFalse(
            source.contains("val level = if (state == ShipSystemStatsScript.State.IDLE) 0f else 1f"),
            "limit temporal thruster must not extend full stat boosts across charge-up/down frames",
        )
    }

    @Test
    fun `limit temporal thruster uses temporal shell style jitter and doubled arc flare afterimage cadence`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDLimitTemporalThrusterSystemStats.kt"),
        )

        assertTrue(source.contains("TIME_MULT = 3f"), "radiation belt system should provide 200% extra time flow")
        assertTrue(source.contains("AFTERIMAGE_INTERVAL = 0.06f"), "radiation belt afterimage cadence should be twice as fast as the prior 0.12s cadence")
        assertTrue(source.contains("ship.setJitterUnder(id, TEMPORAL_JITTER_UNDER, level, 25, 0f, 7f)"), "system should use temporal shell style under-jitter")
        assertTrue(source.contains("ship.setJitter(id, TEMPORAL_JITTER, 0.30f * level, 3, 0f, 0f)"), "system should use temporal shell style hull jitter")
        assertTrue(source.contains("ship.setJitterShields(false)"), "temporal thruster cleanup should clear shield jitter routing")
        assertTrue(source.contains("ArcFlareAfterimageManager.spawn"), "temporal thruster should reuse the arc flare afterimage renderer")
        assertTrue(source.contains("ArcFlareAfterimageManager.Snapshot"), "temporal thruster should snapshot the hull sprite like arc flare")
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
        assertTrue(
            system.contains("if (ship != null && engine != null && !engine.isPaused) {\n            renderTemporalStreak(ship, id, state)"),
            "temporal thruster VFX should run through the system script before the active-only stat gate",
        )
        assertFalse(
            plugin.contains("ensureRadiationBeltSystemVfx"),
            "ARC automation must not synthesize radiation belt system VFX evidence outside the real system path",
        )
        assertTrue(plugin.contains("radiationBeltSystemState"), "ARC diagnostics should expose radiation belt system state when VFX evidence is missing")
        assertTrue(plugin.contains("radiationBeltSystemAfterimages"), "automation diagnostics should publish system VFX evidence")
        assertTrue(verifier.contains("radiationBeltSystemAfterimages"), "verifier should require system VFX evidence, not only pursuit network links")
    }

    @Test
    fun `arc production verifier matches current tooltip contracts and standard variant ids`() {
        val plugin = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt"))
        val verifier = Files.readString(Path.of("tools/verify_ingame_vfx_automation.py"))

        assertTrue(plugin.contains("arcProductionSourceVariantIds"), "ARC automation should report the source Standard variant ids, not only mission clone ids")
        assertTrue(plugin.contains("member?.variant?.hullVariantId"), "source variant id reporting should come from deployed fleet members")
        assertTrue(plugin.contains("ARC_PRODUCTION_STANDARD_VARIANTS"), "mission clone ids should be normalized through explicit source variant ids")
        assertTrue(plugin.contains("astd_arc_jet_Standard"), "ARC production source variant ids must include arc jet standard variant")
        assertTrue(plugin.contains("astd_plasma_arch_Standard"), "ARC production source variant ids must include plasma arch standard variant")
        assertTrue(plugin.contains("astd_radiation_belt_Standard"), "ARC production source variant ids must include radiation belt standard variant")
        assertTrue(verifier.contains("\"radiationBeltTooltipKeys\": 26"), "verifier key minimum should match the current radiation belt renderer contract")
    }
}
