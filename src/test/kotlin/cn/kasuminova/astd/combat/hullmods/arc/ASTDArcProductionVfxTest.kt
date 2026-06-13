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
        assertFalse(source.contains("engine.addNebulaParticle"), "node pulse visuals should not fall back to original nebula particles")
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
        assertTrue(vfx.contains("preferredAngle: Float? = null"), "shield arcs should accept a preferred combat hit direction")
        assertTrue(vfx.contains("MathUtils.getShortestRotation(shield.facing, it)"), "shield arcs should preserve left/right direction when biasing toward recent hits")
        assertFalse(vfx.contains("PLASMA_ARC_SEGMENTS"), "shield arcs should be a single original EMP arc instead of stitched segments")
        assertFalse(vfx.contains("spawnShieldArcSegment"), "shield arcs should not be stitched from multiple visual segments")
        assertFalse(vfx.contains("for (idx in 0 until PLASMA_ARC_SEGMENTS)"), "shield arcs should not spawn a chain of segment arcs")
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
        assertTrue(
            hullmod.contains("private fun visualBoostLevel(ship: ShipAPI): Float"),
            "paused-frame shield colors must derive from current ship system state when custom boost data is cleared",
        )
        assertTrue(
            hullmod.contains("system.isActive || system.isOn || system.isStateActive"),
            "paused-frame shield colors must stay boosted while the toggle system is active or held on",
        )
        assertTrue(
            hullmod.contains("ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, visualBoostLevel(ship))"),
            "paused-frame shield color maintenance should not fall back to stale zero boost data",
        )

        val system = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDPlasmaArmorShieldBoostSystemStats.kt"),
        )
        assertTrue(system.contains("ASTDArcProductionVfx.applyPlasmaShieldVisuals"), "system should apply purple shield colors while active")
        assertTrue(system.contains("override fun unapply"), "system should have an explicit cleanup path")
        assertTrue(system.contains("ASTDArcProductionVfx.markPlasmaShieldVisualGrace"), "system cleanup should keep blue shield colors through the shield closing frame")
        assertFalse(system.contains("ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, 0f)"), "system cleanup must not depend on shield.isOn during the closing frame")
        assertFalse(system.contains("PULSE_TIMER_KEY"), "system should not spawn a separate high-frequency shield arc timer")
        assertFalse(system.contains("emitPlasmaShieldArc"), "shield arc cadence should be owned by the plasma shield hullmod")
    }

    @Test
    fun `plasma arch recoil accumulator arcs use wider slower arcs with path lens flares`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDIonizedRecoilAccumulatorHullMod.kt"),
        )

        assertTrue(source.contains("ARC_THICKNESS = 27f"), "targeted recoil arcs should be 50% wider than the previous 18px width")
        assertTrue(source.contains("ARC_VISUAL_THICKNESS = 18f"), "untargeted recoil arcs should be 50% wider than the previous 12px width")
        assertTrue(source.contains("ARC_CORE_WIDTH = 9f"), "recoil arc core should scale with the wider arc")
        assertTrue(source.contains("RECOIL_LENS_FLARE_SPACING = 150f"), "recoil arc path flares should be spaced every 150su")
        assertTrue(source.contains("RECOIL_LENS_FLARE_RANDOM_OFFSET = 50f"), "recoil arc path flares should have 50su side jitter")
        assertTrue(source.contains("emitRecoilArcPathFlares(engine, from, to, ARC_FRINGE, ARC_CORE)"), "targeted recoil arcs should add random path lens flares")
        assertTrue(source.contains("MagicLensFlare.createSharpFlare"), "recoil arc path flares should use MagicLib lens flares")
        assertTrue(source.contains("flickerRateMult = 0.30f"), "recoil arc animation should last about 50% longer")
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
            "arcJetActiveSystemLinks",
            "arcJetActiveSystemBeamFrames",
            "arcJetActiveSystemFluxPressure",
            "plasmaArchShieldOpen",
            "plasmaArchSystemActive",
            "plasmaArchShieldArcEmissions",
            "radiationBeltSystemAfterimages",
        ).forEach { key ->
            assertTrue(source.contains(key), "missing VFX telemetry counter key: $key")
        }
        assertFalse(source.contains("TELEMETRY_ARC_JET_LINKED_SHIPS"), "passive tactical network should no longer expose beam-link telemetry")
        assertFalse(source.contains("TELEMETRY_RADIATION_BELT_PURSUIT_LINKS"), "distributed pursuit network should no longer expose beam-link telemetry")
    }

    @Test
    fun `production runtime call sites route acceptance vfx through helper`() {
        val callSites = mapOf(
            "ASTDArcSharedFluxNetworkSystemStats.kt" to "renderArcJetSharedFluxBeam",
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

        val tactical = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcSharedTacticalNetworkHullMod.kt"),
        )
        val pursuit = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDDistributedPursuitNetworkHullMod.kt"),
        )
        assertFalse(tactical.contains("emitPassiveConnectionVfx"), "arc shared tactical network must not render persistent connection beams")
        assertFalse(tactical.contains("emitArcJetPassiveLink"), "arc shared tactical network must not call passive link VFX")
        assertFalse(tactical.contains("CONNECT_PULSE"), "arc shared tactical network should not keep passive VFX pulse timers")
        assertFalse(pursuit.contains("renderNetworkPulse"), "distributed pursuit network must not render persistent connection beams")
        assertFalse(pursuit.contains("emitRadiationPursuitPing"), "distributed pursuit network must not call pursuit link VFX")
        assertFalse(pursuit.contains("LINK_PULSE_INTERVAL"), "distributed pursuit network should not keep passive VFX pulse timers")
    }

    @Test
    fun `arc jet shared flux system renders continuous pressure driven multilayer beams`() {
        val vfx = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt"),
        )
        val system = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDArcSharedFluxNetworkSystemStats.kt"),
        )

        assertTrue(vfx.contains("fun renderArcJetSharedFluxBeam"), "active flux network should use a dedicated continuous beam renderer")
        assertTrue(vfx.contains("ACTIVE_BEAM_BASE_WIDTH = 7f"), "active flux beam baseline width should be reduced by 50%")
        assertTrue(vfx.contains("ARC_FLUX_BLUE"), "active flux beam should use the arc production blue baseline")
        assertTrue(vfx.contains("ARC_FLUX_PURPLE"), "active flux beam should shift toward purple under high flux pressure")
        assertTrue(vfx.contains("pressureRatio"), "beam renderer should receive flux-transfer pressure")
        assertTrue(vfx.contains("Misc.interpolateColor(ARC_FLUX_BLUE, ARC_FLUX_PURPLE, pressureRatio"), "pressure should drive color shift")
        assertTrue(vfx.contains("val from = fluxBeamAnchor(source, target.location, fromSource = true)"), "active flux beams should start outside the source hull instead of from the ship center")
        assertTrue(vfx.contains("val to = fluxBeamAnchor(target, source.location, fromSource = false)"), "active flux beams should end outside the target hull instead of through the ship center")
        assertTrue(vfx.contains("private fun fluxBeamAnchor"), "active flux beams should use hull-edge anchors so BELOW_SHIPS_LAYER is visually meaningful")
        assertTrue(vfx.contains("ShipAPI.HullSize.FRIGATE -> 0.50f"), "frigate links should use half-width beams")
        assertTrue(vfx.contains("ShipAPI.HullSize.DESTROYER -> 0.75f"), "destroyer links should use 75% width beams")
        assertTrue(vfx.contains("ShipAPI.HullSize.CRUISER -> 1.00f"), "cruiser links should use baseline width beams")
        assertTrue(vfx.contains("ShipAPI.HullSize.CAPITAL_SHIP -> 1.25f"), "capital links should use wider beams")
        assertTrue(vfx.contains("tipWidth = width"), "active flux beams should keep head and tail width equal")
        assertTrue(vfx.contains("renderFluxBeamLayer"), "active flux beam should be composed from multiple beam layers")
        assertTrue(vfx.contains("emitFluxPathFlares"), "active flux beam should spawn path flare detail")
        assertTrue(vfx.contains("RenderingUtil.addCombatFlareField"), "small BoxUtil flare particles should be used on active flux links")
        assertTrue(vfx.contains("MagicLensFlare.createSharpFlare"), "active flux link detail should include MagicLib lens flares")
        assertTrue(vfx.contains("emitFluxTravelBeam"), "active flux beam should include short traveling sub-beams")
        assertTrue(vfx.contains("emitArcJetFluxStar"), "active flux system should render the source cross star")
        assertTrue(vfx.contains("ARC_FLUX_STAR_ROTATION_DEGREES_PER_SECOND = 30f"), "source cross star should rotate at 30 degrees per second")
        assertTrue(vfx.contains("STAR_ALPHA = 0.24f"), "source cross star should be visible without washing out the hull")
        assertTrue(vfx.contains("renderFluxStarRing"), "source cross star should include a rotating ring layer")
        assertTrue(vfx.contains("renderFluxStarRay"), "source cross star rays should avoid stacking full beam cores at the ship center")
        assertTrue(vfx.contains("addSegmentLine"), "source cross star should use BoxUtil segment lines instead of ordinary beam links")
        assertTrue(vfx.contains("val length = radius * 2.85f"), "source cross star segment rays should cross under the hull so the ship masks the center")
        assertTrue(vfx.contains("* 1.10f"), "source cross star ring should sit near the hull outline")
        assertTrue(vfx.contains("line.initLine(offset, length, color, emissive, width)"), "source cross star rays should be BoxUtil segment lines")
        assertTrue(vfx.contains("radius * if (fromSource) 1.18f else 1.02f"), "active flux beams should anchor at hull edges rather than crossing ship centers")
        assertFalse(vfx.contains("width = radius * 0.055f"), "source cross star must not use the previous thick centered beam rays")

        assertFalse(system.contains("ACTIVE_PULSE_INTERVAL"), "active system must not blink VFX through a pulse interval")
        assertFalse(system.contains("PULSE_KEY"), "active system must not keep pulse timers")
        assertTrue(system.contains("val transfer = transferFluxDelta"), "system should use real transfer data for VFX pressure")
        assertTrue(system.contains("updateFluxPressure"), "system should smooth transfer pressure per link")
        assertTrue(system.contains("MAX_PRESSURE_FLUX_FRACTION_PER_SECOND = 0.015f"), "pressure maximum should be 1.5% of arc jet max flux per second")
        assertTrue(system.contains("renderArcJetSharedFluxBeam(engine, ship, target"), "system should render the active link every active frame")
    }

    @Test
    fun `plasma shield boost excludes point defense weapons and ionized recoil uses total flux for proc chance`() {
        val plasmaBoost = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDPlasmaArmorShieldBoostSystemStats.kt"),
        )
        assertTrue(
            plasmaBoost.contains("ASTDArcCombatUtil.isNonPdNonMissileWeapon"),
            "plasma shield boost should exclude PD and missile weapons from weapon suppression",
        )
        assertTrue(
            plasmaBoost.contains("weapon.setForceNoFireOneFrame(true)"),
            "plasma shield boost should disable eligible weapons like fortress shield instead of reducing RoF",
        )
        assertFalse(
            plasmaBoost.contains("ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, 0f)"),
            "plasma shield boost cleanup should not restore vanilla colors during the shield closing frame",
        )
        assertFalse(plasmaBoost.contains("ASTDArcCombatUtil.applyRefireDelayMult"), "plasma shield boost should not reduce refire delay anymore")
        assertFalse(plasmaBoost.contains("ASTDArcCombatUtil.restoreRefireDelays(ship)"), "plasma shield boost should not need refire delay restoration")
        assertFalse(
            plasmaBoost.substringBefore("override fun unapply").contains("stats.ballisticRoFMult.modifyMult"),
            "plasma shield boost must not use global ballistic RoF because that affects PD weapons",
        )

        val ionized = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDIonizedRecoilAccumulatorHullMod.kt"),
        )
        assertTrue(ionized.contains("HARD_FLUX_CONVERT_FRACTION = 0.03f"), "ionized recoil should convert 3% of current hard flux")
        assertTrue(ionized.contains("SOFT_FLUX_MULT = 1f"), "ionized recoil should generate soft flux equal to converted hard flux")
        assertTrue(ionized.contains("val conversionMult = 1f + boostLevel"), "boosted plasma shield should double ionized recoil conversion")
        assertTrue(ionized.contains("convertHardFlux(conversionMult)"), "ionized recoil conversion should scale during the system boost")
        assertTrue(ionized.contains("effectiveRecoilRange()"), "ionized recoil range should be affected by energy projectile weapon range")
        assertTrue(ionized.contains("playSound(\"system_emp_emitter_impact\""), "ionized recoil trigger should play the vanilla EMP emitter impact sound")
        assertTrue(ionized.contains("private fun fluxLevel(): Float"), "ionized recoil proc chance should use total flux level")
        assertTrue(ionized.contains("val chance = procChance(param, damage, fluxLevel())"), "proc chance must not be based on hard flux or hit medium")
        assertTrue(ionized.contains("private fun procChance(param: Any?, damage: DamageAPI, fluxLevel: Float): Float"), "proc chance should combine total flux with beam and hit-strength rules")
        assertTrue(ionized.contains("val hardFluxLevel = hardFluxLevel()"), "shield pierce chance should still use hard flux level")
    }

    @Test
    fun `ionized recoil proc chance ignores damage type and hit medium but keeps beam and hit strength scaling`() {
        val ionized = Files.readString(
            Path.of("src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDIonizedRecoilAccumulatorHullMod.kt"),
        )

        assertFalse(ionized.contains("KINETIC_SHIELD_PROC_MULT"), "damage type should no longer change proc chance")
        assertFalse(ionized.contains("HIGH_EXPLOSIVE_SHIELD_PROC_MULT"), "damage type should no longer change proc chance")
        assertFalse(ionized.contains("FRAGMENTATION_SHIELD_PROC_MULT"), "damage type should no longer change proc chance")
        assertFalse(ionized.contains("KINETIC_ARMOR_PROC_MULT"), "hit medium should no longer change proc chance")
        assertFalse(ionized.contains("HIGH_EXPLOSIVE_ARMOR_PROC_MULT"), "hit medium should no longer change proc chance")
        assertFalse(ionized.contains("FRAGMENTATION_ARMOR_PROC_MULT"), "hit medium should no longer change proc chance")
        assertFalse(ionized.contains("damageTypeProcMult"), "proc chance must not call a damage type multiplier")
        assertTrue(ionized.contains("BEAM_PROC_MULT = 0.10f"), "beam damage should lose 90% proc chance")
        assertTrue(ionized.contains("HIT_STRENGTH_BASE_FLUX_FRACTION = 0.02f"), "hit strength should be based on 2% max flux")
        assertTrue(ionized.contains("MIN_HIT_STRENGTH_PROC_MULT = 0.10f"), "weak hits should reduce proc chance by at most 90%")
        assertTrue(ionized.contains("MAX_HIT_STRENGTH_PROC_MULT = 3f"), "strong hits should raise proc chance by at most 200%")
        assertTrue(ionized.contains("if (param is BeamAPI) BEAM_PROC_MULT else 1f"), "beam scaling should be applied after flux chance")
        assertTrue(ionized.contains("damage.baseDamage"), "hit strength scaling should use original base damage")
        assertFalse(ionized.contains("effectiveDamageAmount(param, damage)"), "hit strength scaling must not use current or DPS-expanded damage")
        assertTrue(ionized.contains("hitStrengthProcMult("), "proc chance should apply the hit strength multiplier")
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
            selfRangeBlock.contains("PDWeaponRangeBonus"),
            "arc jet self range penalty must not rely on additive PD stat compensation because it can leave residual range loss",
        )
        assertFalse(
            selfRangeBlock.contains("beamWeaponRangeBonus"),
            "arc jet self range penalty must not apply an extra beam-specific penalty on top of energy range",
        )
        assertFalse(
            selfRangeBlock.contains("modifyMult"),
            "arc jet self range penalty must not multiplicatively shrink the advanced core bonus",
        )
        assertTrue(
            tacticalNetwork.contains("import com.fs.starfarer.api.combat.listeners.WeaponRangeModifier"),
            "arc jet point defense exemption should use a per-weapon final range modifier",
        )
        assertTrue(
            tacticalNetwork.contains("ensurePointDefenseRangeCompensation(ship)"),
            "arc jet should attach the point defense compensation listener to ships",
        )
        assertTrue(
            tacticalNetwork.contains("PointDefenseRangeCompensationModifier : WeaponRangeModifier"),
            "arc jet should compensate only eligible point defense weapons",
        )
        assertTrue(
            tacticalNetwork.contains("SELF_POINT_DEFENSE_RANGE_COMPENSATION_MULT = 1.25f"),
            "arc jet point defense compensation should exactly cancel the self 0.8 range penalty",
        )
        assertTrue(
            tacticalNetwork.contains("ASTDArcCombatUtil.isPointDefenseWeapon(weapon)"),
            "arc jet point defense compensation should identify PD weapons through the shared combat utility",
        )
        assertTrue(
            tacticalNetwork.contains("override fun getWeaponRangeMultMod(ship: ShipAPI, weapon: WeaponAPI): Float"),
            "arc jet point defense compensation should apply to final range instead of changing base range",
        )
        assertTrue(
            tacticalNetwork.contains("isAffectedNonMissileWeapon(weapon)"),
            "arc jet point defense compensation must not boost missiles that were never penalized by the self range stat",
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
        assertFalse(verifier.contains("\"radiationBeltPursuitLinks\""), "verifier should not require removed distributed pursuit link beams")
        assertFalse(verifier.contains("\"arcJetLinkedShips\""), "verifier should not require removed passive tactical link beams")
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
        assertTrue(verifier.contains("\"plasmaArchTooltipKeys\": 44"), "verifier key minimum should match the exported plasma arch renderer contract")
        assertTrue(verifier.contains("\"radiationBeltTooltipKeys\": 26"), "verifier key minimum should match the current radiation belt renderer contract")
    }
}
