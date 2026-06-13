package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.SegmentEntity
import org.boxutil.util.RenderingUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.magiclib.util.MagicLensFlare
import org.lwjgl.util.vector.Vector2f
import org.lwjgl.util.vector.Vector4f
import java.awt.Color
import kotlin.math.roundToInt

internal object ASTDArcProductionVfx {
    const val TELEMETRY_ARC_JET_ACTIVE_SYSTEM_LINKS = "arcJetActiveSystemLinks"
    const val TELEMETRY_ARC_JET_ACTIVE_SYSTEM_BEAM_FRAMES = "arcJetActiveSystemBeamFrames"
    const val TELEMETRY_ARC_JET_ACTIVE_SYSTEM_FLUX_PRESSURE = "arcJetActiveSystemFluxPressure"
    const val TELEMETRY_PLASMA_ARCH_SHIELD_OPEN = "plasmaArchShieldOpen"
    const val TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE = "plasmaArchSystemActive"
    const val TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS = "plasmaArchShieldArcEmissions"
    const val TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES = "radiationBeltSystemAfterimages"

    private const val TELEMETRY_PREFIX = "astd_arc_production_vfx:"
    private const val VFX_FAILURE_WARN_KEY = "astd_arc_production_vfx_failure_warned"
    private const val BEAM_CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val BEAM_FRINGE_SPRITE = "graphics/fx/beamfringeb.png"
    private const val PLASMA_ARC_WIDTH = 18f
    private const val ACTIVE_BEAM_FULL = 0.07f
    private const val ACTIVE_BEAM_FADE_OUT = 0.11f
    private const val ACTIVE_BEAM_BASE_WIDTH = 7f
    private const val ARC_FLUX_STAR_ROTATION_DEGREES_PER_SECOND = 30f
    private const val STAR_ALPHA = 0.24f

    private val log = Global.getLogger(ASTDArcProductionVfx::class.java)

    private val arcCore = Color(222, 248, 255, 235)
    private val ARC_FLUX_BLUE = Color(54, 155, 255, 230)
    private val ARC_FLUX_PURPLE = Color(174, 72, 255, 245)
    private val ARC_FLUX_CORE = Color(238, 252, 255, 250)
    private val plasmaBlue = Color(96, 206, 255, 210)
    private val plasmaCore = Color(245, 255, 255, 245)
    private val PLASMA_SHIELD_BLUE_RING = Color(28, 104, 230, 235)
    private val PLASMA_SHIELD_BLUE_INNER = Color(34, 132, 255, 72)
    private val PLASMA_SHIELD_PURPLE_RING = Color(122, 54, 230, 240)
    private val PLASMA_SHIELD_PURPLE_INNER = Color(148, 74, 255, 82)

    fun setCounter(engine: CombatEngineAPI, key: String, value: Int) {
        engine.customData["$TELEMETRY_PREFIX$key"] = value
    }

    fun incrementCounter(engine: CombatEngineAPI, key: String, amount: Int = 1) {
        val current = engine.customData["$TELEMETRY_PREFIX$key"] as? Int ?: 0
        engine.customData["$TELEMETRY_PREFIX$key"] = current + amount
    }

    fun counter(engine: CombatEngineAPI, key: String): Int =
        engine.customData["$TELEMETRY_PREFIX$key"] as? Int ?: 0

    fun renderArcJetSharedFluxBeam(
        engine: CombatEngineAPI,
        source: ShipAPI,
        target: ShipAPI,
        level: Float,
        pressureRatio: Float,
    ) {
        val from = fluxBeamAnchor(source, target.location, fromSource = true)
        val to = fluxBeamAnchor(target, source.location, fromSource = false)
        val length = MathUtils.getDistance(from, to)
        if (length <= 1f) return

        val clampedLevel = level.coerceIn(0f, 1f)
        val pressure = pressureRatio.coerceIn(0f, 1f)
        val color = Misc.interpolateColor(ARC_FLUX_BLUE, ARC_FLUX_PURPLE, pressure)
        val width = ACTIVE_BEAM_BASE_WIDTH * hullSizeBeamWidthScale(target.hullSize)
        val alpha = (0.44f + 0.52f * pressure) * clampedLevel
        val facing = VectorUtils.getAngle(from, to)

        renderFluxBeamLayer(engine, from, facing, length, width * 2.30f, colorWithAlpha(color, 92), colorWithAlpha(color, 120), alpha * 0.32f, 0.72f)
        renderFluxBeamLayer(engine, from, facing, length, width * 1.20f, colorWithAlpha(color, 170), colorWithAlpha(ARC_FLUX_CORE, 210), alpha * 0.58f, 1.00f)
        renderFluxBeamLayer(engine, from, facing, length, width * 0.42f, colorWithAlpha(ARC_FLUX_CORE, 235), colorWithAlpha(color, 235), alpha * 0.88f, 1.20f)
        emitFluxPathFlares(engine, source, target, from, to, color, pressure, clampedLevel)
        emitFluxTravelBeam(engine, from, to, width, color, pressure, clampedLevel)
        emitArcJetFluxStar(engine, source, pressure, clampedLevel)

        incrementCounter(engine, TELEMETRY_ARC_JET_ACTIVE_SYSTEM_LINKS)
        incrementCounter(engine, TELEMETRY_ARC_JET_ACTIVE_SYSTEM_BEAM_FRAMES)
        setCounter(engine, TELEMETRY_ARC_JET_ACTIVE_SYSTEM_FLUX_PRESSURE, (pressure * 1000f).roundToInt().coerceAtLeast(1))
    }

    fun emitPlasmaShieldArc(engine: CombatEngineAPI, ship: ShipAPI, boosted: Boolean, preferredAngle: Float? = null) {
        val shield = ship.shield ?: return
        if (!shield.isOn) return
        val center = shield.location ?: ship.location
        val radius = shield.radius.coerceAtLeast(ship.collisionRadius)
        val arcSpan = shield.activeArc * 0.1675f
        val halfArc = shield.activeArc * 0.5f
        val margin = arcSpan * 0.5f
        val preferredOffset = preferredAngle
            ?.let { MathUtils.getShortestRotation(shield.facing, it) }
            ?.coerceIn(-halfArc + margin, halfArc - margin)
        val randomOffset = MathUtils.getRandomNumberInRange(-halfArc + margin, halfArc - margin)
        val mid = shield.facing + (preferredOffset ?: randomOffset) + MathUtils.getRandomNumberInRange(-arcSpan * 0.14f, arcSpan * 0.14f)
        val angleA = mid - arcSpan * 0.5f
        val angleB = mid + arcSpan * 0.5f
        val from = edgeBiasedShieldPoint(center, radius, angleA, MathUtils.getRandomNumberInRange(0.85f, 1f))
        val to = edgeBiasedShieldPoint(center, radius, angleB, MathUtils.getRandomNumberInRange(0.85f, 1f))

        val params = EmpArcEntityAPI.EmpArcParams().apply {
            segmentLengthMult = if (boosted) 5.4f else 6.4f
            zigZagReductionFactor = if (boosted) 0.08f else 0.11f
            fadeOutDist = radius * 0.24f
            minFadeOutMult = 8f
            flickerRateMult = if (boosted) 0.32f else 0.42f
            movementDurOverride = 0f
            movementDurMin = 0f
            movementDurMax = 0f
            brightSpotFullFraction = 0.36f
            brightSpotFadeFraction = 0.62f
            nonBrightSpotMinBrightness = if (boosted) 0.35f else 0.24f
            glowSizeMult = if (boosted) 1.35f else 1.05f
            glowAlphaMult = if (boosted) 0.86f else 0.62f
            glowColorOverride = if (boosted) PLASMA_SHIELD_PURPLE_RING else PLASMA_SHIELD_BLUE_RING
        }
        try {
            val arc = engine.spawnEmpArcVisual(
                from,
                ship,
                to,
                ship,
                PLASMA_ARC_WIDTH,
                if (boosted) PLASMA_SHIELD_PURPLE_RING else plasmaBlue,
                plasmaCore,
                params,
            )
            arc.setSingleFlickerMode(true)
            arc.setFadedOutAtStart(true)
            arc.setRenderGlowAtStart(false)
            arc.setRenderGlowAtEnd(false)
            arc.setCoreWidthOverride(PLASMA_ARC_WIDTH * if (boosted) 0.46f else 0.36f)
            arc.setWarping(0f)
            emitShieldArcEndpointFlare(engine, ship, from, boosted)
            emitShieldArcEndpointFlare(engine, ship, to, boosted)
        } catch (_: Throwable) {
            handleBoxUtilFailure(engine, "plasma shield arc")
        }
        incrementCounter(engine, TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS)
    }

    fun applyPlasmaShieldVisuals(ship: ShipAPI, boostLevel: Float) {
        val shield = ship.shield ?: return
        val level = boostLevel.coerceIn(0f, 1f)
        val ring = Misc.interpolateColor(PLASMA_SHIELD_BLUE_RING, PLASMA_SHIELD_PURPLE_RING, level)
        val inner = Misc.interpolateColor(PLASMA_SHIELD_BLUE_INNER, PLASMA_SHIELD_PURPLE_INNER, level)
        shield.setRingColor(ring)
        shield.setInnerColor(inner)
        shield.applyShieldEffects(
            ring,
            inner,
            1.12f + 0.22f * level,
            0.08f + 0.24f * level,
            0.08f + 0.18f * level,
        )
    }

    fun markPlasmaShieldVisualGrace(engine: CombatEngineAPI, ship: ShipAPI, seconds: Float) {
        ship.setCustomData("astd_plasma_shield_visual_grace", engine.getTotalElapsedTime(false) + seconds.coerceAtLeast(0f))
    }

    fun emitPlasmaShieldHit(engine: CombatEngineAPI, ship: ShipAPI, point: Vector2f, intensity: Float) {
        val boosted = intensity.coerceIn(0f, 1f)
        emitNodePulse(engine, point, 52f + 52f * boosted, Color(120, 220, 255, 138))
        emitNodePulse(engine, ship.location, 30f + 24f * boosted, Color(86, 180, 255, 82))
    }

    fun emitTemporalThrusterAfterimage(engine: CombatEngineAPI, ship: ShipAPI, intensity: Float) {
        val tail = MathUtils.getPointOnCircumference(Vector2f(ship.location), -ship.collisionRadius * 0.55f, ship.facing)
        emitNodePulse(engine, tail, 34f + 34f * intensity.coerceIn(0f, 1f), Color(120, 200, 255, 120))
        incrementCounter(engine, TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES)
    }

    private fun emitLink(
        engine: CombatEngineAPI,
        source: ShipAPI,
        target: ShipAPI,
        width: Float,
        duration: Float,
        color: Color,
        core: Color,
        alpha: Float,
    ) {
        val from = Vector2f(source.location)
        val to = Vector2f(target.location)
        val length = MathUtils.getDistance(from, to)
        if (length <= 1f) return
        val facing = org.lazywizard.lazylib.VectorUtils.getAngle(from, to)
        val settings = Global.getSettings()
        BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = from,
            facing = facing,
            length = length,
            baseWidth = width,
            tipWidth = width * 0.35f,
            coreColor = color,
            fringeColor = core,
            coreSprite = settings.getSprite(BEAM_CORE_SPRITE),
            fringeSprite = settings.getSprite(BEAM_FRINGE_SPRITE),
            layer = CombatEngineLayers.BELOW_SHIPS_LAYER,
            full = duration.coerceAtLeast(0.05f),
            baseAlphaMul = alpha.coerceIn(0f, 1f),
            tipAlphaMul = (alpha * 0.22f).coerceIn(0f, 1f),
            baseEmissiveAlphaMul = (alpha * 0.92f).coerceIn(0f, 1f),
            tipEmissiveAlphaMul = (alpha * 0.45f).coerceIn(0f, 1f),
            mixPower = 1f,
        )?.setGlobalTimer(0.02f, duration.coerceAtLeast(0.05f), 0.20f)
            ?: handleBoxUtilFailure(engine, "link")
    }

    private fun renderFluxBeamLayer(
        engine: CombatEngineAPI,
        from: Vector2f,
        facing: Float,
        length: Float,
        width: Float,
        color: Color,
        core: Color,
        alpha: Float,
        mixPower: Float,
    ) {
        val settings = Global.getSettings()
        BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = Vector2f(from),
            facing = facing,
            length = length,
            baseWidth = width,
            tipWidth = width,
            coreColor = color,
            fringeColor = core,
            coreSprite = settings.getSprite(BEAM_CORE_SPRITE),
            fringeSprite = settings.getSprite(BEAM_FRINGE_SPRITE),
            layer = CombatEngineLayers.BELOW_SHIPS_LAYER,
            full = ACTIVE_BEAM_FULL,
            baseAlphaMul = alpha.coerceIn(0f, 1f),
            tipAlphaMul = alpha.coerceIn(0f, 1f),
            baseEmissiveAlphaMul = (alpha * 1.15f).coerceIn(0f, 1f),
            tipEmissiveAlphaMul = (alpha * 1.15f).coerceIn(0f, 1f),
            mixPower = mixPower,
        )?.setGlobalTimer(0.025f, ACTIVE_BEAM_FULL, ACTIVE_BEAM_FADE_OUT)
            ?: handleBoxUtilFailure(engine, "arc jet shared flux beam")
    }

    private fun emitFluxPathFlares(
        engine: CombatEngineAPI,
        source: ShipAPI,
        target: ShipAPI,
        from: Vector2f,
        to: Vector2f,
        color: Color,
        pressureRatio: Float,
        level: Float,
    ) {
        val key = "astd_arc_flux_path_flare:${System.identityHashCode(source)}:${System.identityHashCode(target)}"
        val now = engine.getTotalElapsedTime(false)
        val next = engine.customData[key] as? Float ?: 0f
        if (now < next) return
        engine.customData[key] = now + MathUtils.getRandomNumberInRange(0.10f, 0.18f) / (0.75f + pressureRatio)

        val length = MathUtils.getDistance(from, to)
        if (length <= 1f) return
        val facingToSource = VectorUtils.getAngle(to, from)
        val dir = MathUtils.getPointOnCircumference(null, 1f, facingToSource)
        val base = pointAlong(from, to, MathUtils.getRandomNumberInRange(0.12f, 0.88f))
        val side = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(-6f, 6f), facingToSource + 90f)
        base.translate(side.x, side.y)

        RenderingUtil.addCombatFlareField(
            base,
            7 + (pressureRatio * 7f).roundToInt(),
            facingToSource,
            26f,
            Vector2f(0f, 18f),
            Vector2f(-8f, 8f),
            Vector4f(5f, 10f, 12f, 26f),
            colorWithAlpha(color, (85f + 95f * level).roundToInt()),
            null,
            colorWithAlpha(ARC_FLUX_CORE, (120f + 100f * level).roundToInt()),
            null,
            false,
            false,
            0.08f,
            0.16f,
            0.34f,
            0.05f,
            true,
            CombatEngineLayers.BELOW_SHIPS_LAYER,
        ).let { pair ->
            if (pair.two != BoxEnum.STATE_SUCCESS) {
                try { pair.one.delete() } catch (_: Throwable) {}
                handleBoxUtilFailure(engine, "arc jet shared flux flare field")
            }
        }

        MagicLensFlare.createSharpFlare(
            engine,
            source,
            base,
            2.5f + 2.5f * pressureRatio,
            28f + 42f * pressureRatio,
            facingToSource,
            colorWithAlpha(color, 105),
            colorWithAlpha(ARC_FLUX_CORE, 160),
        )
        val nebulaOk = RenderingUtil.VanillaFX.addNebulaParticle(
            false,
            Vector2f(base),
            Vector2f(dir.x * MathUtils.getRandomNumberInRange(35f, 82f), dir.y * MathUtils.getRandomNumberInRange(35f, 82f)),
            MathUtils.getRandomNumberInRange(18f, 34f),
            1.35f,
            0.12f,
            0.28f,
            MathUtils.getRandomNumberInRange(0.48f, 0.78f),
            colorWithAlpha(color, (36f + 46f * pressureRatio).roundToInt()),
        )
        if (!nebulaOk) handleBoxUtilFailure(engine, "arc jet shared flux nebula particle")
    }

    private fun emitFluxTravelBeam(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        width: Float,
        color: Color,
        pressureRatio: Float,
        level: Float,
    ) {
        if (MathUtils.getRandomNumberInRange(0f, 1f) > 0.28f + pressureRatio * 0.30f) return
        val length = MathUtils.getDistance(from, to)
        if (length <= 120f) return
        val segmentLength = MathUtils.getRandomNumberInRange(length * 0.10f, length * 0.24f).coerceAtMost(length * 0.42f)
        val startT = MathUtils.getRandomNumberInRange(0.08f, 0.74f)
        val start = pointAlong(from, to, startT)
        val facing = VectorUtils.getAngle(from, to)
        renderFluxBeamLayer(
            engine = engine,
            from = start,
            facing = facing,
            length = segmentLength,
            width = width * MathUtils.getRandomNumberInRange(0.34f, 0.58f),
            color = darken(color, 0.80f),
            core = darken(ARC_FLUX_CORE, 0.80f),
            alpha = (0.30f + 0.30f * pressureRatio) * level,
            mixPower = 0.95f,
        )
    }

    private fun emitArcJetFluxStar(engine: CombatEngineAPI, ship: ShipAPI, pressureRatio: Float, level: Float) {
        val key = "astd_arc_flux_star:${System.identityHashCode(ship)}"
        val now = engine.getTotalElapsedTime(false)
        val next = engine.customData[key] as? Float ?: 0f
        if (now < next) return
        engine.customData[key] = now + 0.05f

        val rot = now * ARC_FLUX_STAR_ROTATION_DEGREES_PER_SECOND
        val radius = ship.collisionRadius.coerceAtLeast(80f)
        val starColor = Misc.interpolateColor(ARC_FLUX_BLUE, ARC_FLUX_PURPLE, pressureRatio * 0.70f)
        val alpha = STAR_ALPHA * level
        for (idx in 0 until 4) {
            renderFluxStarRay(engine, ship, rot + idx * 90f, radius, starColor, alpha)
        }
        renderFluxStarRing(engine, ship, rot, starColor, alpha)
    }

    private fun renderFluxStarRay(engine: CombatEngineAPI, ship: ShipAPI, facing: Float, radius: Float, color: Color, alpha: Float) {
        val length = radius * 2.85f
        val start = Vector2f(-length * 0.5f, 0f)
        addSegmentLine(
            engine = engine,
            location = Vector2f(ship.location),
            facing = facing,
            length = length,
            offset = start,
            width = radius * 0.036f,
            color = colorWithAlpha(color, (145f * alpha).roundToInt()),
            emissive = colorWithAlpha(ARC_FLUX_CORE, (210f * alpha).roundToInt()),
            visual = "arc jet shared flux star ray",
        )
        addSegmentLine(
            engine = engine,
            location = Vector2f(ship.location),
            facing = facing,
            length = length * 0.56f,
            offset = Vector2f(-length * 0.28f, 0f),
            width = radius * 0.014f,
            color = colorWithAlpha(ARC_FLUX_CORE, (250f * alpha).roundToInt()),
            emissive = colorWithAlpha(ARC_FLUX_CORE, (340f * alpha).roundToInt()),
            visual = "arc jet shared flux star core ray",
        )
    }

    private fun renderFluxStarRing(engine: CombatEngineAPI, ship: ShipAPI, rotation: Float, color: Color, alpha: Float) {
        val radius = ship.collisionRadius.coerceAtLeast(80f) * 1.10f
        val ring = SegmentEntity()
        ring.initCircle(null, radius, colorWithAlpha(color, (255f * alpha).roundToInt()), colorWithAlpha(ARC_FLUX_CORE, (330f * alpha).roundToInt()), radius * 0.018f)
        ring.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
        ring.setAdditiveBlend()
        ring.setGlobalTimer(0.025f, ACTIVE_BEAM_FULL, ACTIVE_BEAM_FADE_OUT)
        ring.setStateVanilla(Vector2f(ship.location), rotation)
        val state = try { BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SEGMENT, ring) } catch (_: Throwable) { -1 }
        if (state != BoxEnum.STATE_SUCCESS.toInt()) {
            try { ring.delete() } catch (_: Throwable) {}
            handleBoxUtilFailure(engine, "arc jet shared flux star ring")
        }
    }

    private fun addSegmentLine(
        engine: CombatEngineAPI,
        location: Vector2f,
        facing: Float,
        length: Float,
        offset: Vector2f,
        width: Float,
        color: Color,
        emissive: Color,
        visual: String,
    ) {
        val line = SegmentEntity()
        line.initLine(offset, length, color, emissive, width)
        line.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
        line.setAdditiveBlend()
        line.setGlobalTimer(0.025f, ACTIVE_BEAM_FULL, ACTIVE_BEAM_FADE_OUT)
        line.setStateVanilla(location, facing)
        val state = try { BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SEGMENT, line) } catch (_: Throwable) { -1 }
        if (state != BoxEnum.STATE_SUCCESS.toInt()) {
            try { line.delete() } catch (_: Throwable) {}
            handleBoxUtilFailure(engine, visual)
        }
    }

    private fun hullSizeBeamWidthScale(hullSize: ShipAPI.HullSize): Float = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> 0.50f
        ShipAPI.HullSize.DESTROYER -> 0.75f
        ShipAPI.HullSize.CRUISER -> 1.00f
        ShipAPI.HullSize.CAPITAL_SHIP -> 1.25f
        else -> 0.75f
    }

    private fun fluxBeamAnchor(ship: ShipAPI, other: Vector2f, fromSource: Boolean): Vector2f {
        val center = Vector2f(ship.location)
        val angle = VectorUtils.getAngle(center, other)
        val radius = ship.collisionRadius.coerceAtLeast(28f)
        val offset = radius * if (fromSource) 1.18f else 1.02f
        return MathUtils.getPointOnCircumference(center, offset, angle)
    }

    private fun pointAlong(from: Vector2f, to: Vector2f, t: Float): Vector2f =
        Vector2f(from.x + (to.x - from.x) * t.coerceIn(0f, 1f), from.y + (to.y - from.y) * t.coerceIn(0f, 1f))

    private fun colorWithAlpha(color: Color, alpha: Int): Color =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun darken(color: Color, mult: Float): Color =
        Color((color.red * mult).roundToInt().coerceIn(0, 255), (color.green * mult).roundToInt().coerceIn(0, 255), (color.blue * mult).roundToInt().coerceIn(0, 255), color.alpha)

    private fun emitNodePulse(engine: CombatEngineAPI, location: Vector2f, radius: Float, color: Color) {
        val settings = Global.getSettings()
        val alpha = (color.alpha / 255f).coerceIn(0f, 1f)
        val length = radius.coerceAtLeast(8f)
        val baseWidth = (radius * 0.14f).coerceAtLeast(3f)
        val tipWidth = (baseWidth * 0.18f).coerceAtLeast(0.8f)
        val rays = 6
        for (index in 0 until rays) {
            val facing = index * (360f / rays) + MathUtils.getRandomNumberInRange(-8f, 8f)
            BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = Vector2f(location),
                facing = facing,
                length = length * MathUtils.getRandomNumberInRange(0.62f, 1.0f),
                baseWidth = baseWidth,
                tipWidth = tipWidth,
                coreColor = color,
                fringeColor = arcCore,
                coreSprite = settings.getSprite(BEAM_CORE_SPRITE),
                fringeSprite = settings.getSprite(BEAM_FRINGE_SPRITE),
                layer = CombatEngineLayers.BELOW_SHIPS_LAYER,
                full = 0.18f + radius * 0.0012f,
                baseAlphaMul = (alpha * 0.32f).coerceIn(0f, 1f),
                tipAlphaMul = (alpha * 0.02f).coerceIn(0f, 1f),
                baseEmissiveAlphaMul = (alpha * 0.82f).coerceIn(0f, 1f),
                tipEmissiveAlphaMul = (alpha * 0.12f).coerceIn(0f, 1f),
                mixPower = 1f,
            )?.setGlobalTimer(0.02f, 0.06f + radius * 0.0008f, 0.22f)
                ?: handleBoxUtilFailure(engine, "node pulse")
        }
    }

    private fun edgeBiasedShieldPoint(center: Vector2f, radius: Float, angle: Float, radiusFraction: Float): Vector2f {
        return MathUtils.getPointOnCircumference(Vector2f(center), radius * radiusFraction.coerceIn(0f, 1f), angle)
    }

    private fun emitShieldArcEndpointFlare(engine: CombatEngineAPI, ship: ShipAPI, location: Vector2f, boosted: Boolean) {
        val fringe = if (boosted) PLASMA_SHIELD_PURPLE_RING else plasmaBlue
        MagicLensFlare.createSharpFlare(
            engine,
            ship,
            location,
            PLASMA_ARC_WIDTH * 0.18f,
            PLASMA_ARC_WIDTH * 0.72f,
            MathUtils.getRandomNumberInRange(0f, 360f),
            fringe,
            plasmaCore,
        )
    }

    private fun handleBoxUtilFailure(engine: CombatEngineAPI, visual: String) {
        val message = "BoxUtil $visual entity creation failed for ARC production VFX"
        if (ASTDInGameAutomationScenario.isArcProductionEnabled()) {
            throw IllegalStateException(message)
        }
        if (engine.customData[VFX_FAILURE_WARN_KEY] != true) {
            engine.customData[VFX_FAILURE_WARN_KEY] = true
            log.warn(message)
        }
    }
}
