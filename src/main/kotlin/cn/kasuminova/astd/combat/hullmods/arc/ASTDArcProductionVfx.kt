package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.magiclib.util.MagicLensFlare
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

internal object ASTDArcProductionVfx {
    const val TELEMETRY_ARC_JET_LINKED_SHIPS = "arcJetLinkedShips"
    const val TELEMETRY_ARC_JET_ACTIVE_SYSTEM_LINKS = "arcJetActiveSystemLinks"
    const val TELEMETRY_PLASMA_ARCH_SHIELD_OPEN = "plasmaArchShieldOpen"
    const val TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE = "plasmaArchSystemActive"
    const val TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS = "plasmaArchShieldArcEmissions"
    const val TELEMETRY_RADIATION_BELT_PURSUIT_LINKS = "radiationBeltPursuitLinks"
    const val TELEMETRY_RADIATION_BELT_SYSTEM_AFTERIMAGES = "radiationBeltSystemAfterimages"

    private const val TELEMETRY_PREFIX = "astd_arc_production_vfx:"
    private const val VFX_FAILURE_WARN_KEY = "astd_arc_production_vfx_failure_warned"
    private const val BEAM_CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val BEAM_FRINGE_SPRITE = "graphics/fx/beamfringeb.png"
    private const val PLASMA_ARC_WIDTH = 18f
    private const val PLASMA_ARC_SEGMENTS = 3

    private val log = Global.getLogger(ASTDArcProductionVfx::class.java)

    private val arcBlue = Color(74, 166, 255, 190)
    private val arcCore = Color(222, 248, 255, 235)
    private val plasmaBlue = Color(96, 206, 255, 210)
    private val plasmaCore = Color(245, 255, 255, 245)
    private val radiationBlue = Color(80, 145, 255, 185)
    private val radiationCore = Color(212, 240, 255, 230)
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

    fun emitArcJetPassiveLink(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, intensity: Float) {
        emitLink(
            engine = engine,
            source = source,
            target = target,
            width = 8f,
            duration = 0.28f,
            color = arcBlue,
            core = arcCore,
            alpha = 0.38f * intensity.coerceIn(0f, 1f),
        )
        incrementCounter(engine, TELEMETRY_ARC_JET_LINKED_SHIPS)
    }

    fun emitArcJetActiveFluxLink(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, intensity: Float) {
        emitLink(
            engine = engine,
            source = source,
            target = target,
            width = 14f,
            duration = 0.34f,
            color = Color(112, 198, 255, 220),
            core = Color(248, 255, 255, 250),
            alpha = 0.58f * intensity.coerceIn(0f, 1f),
        )
        emitNodePulse(engine, target.location, 46f, Color(120, 205, 255, 155))
        incrementCounter(engine, TELEMETRY_ARC_JET_ACTIVE_SYSTEM_LINKS)
    }

    fun emitRadiationPursuitPing(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, sameNetwork: Boolean) {
        emitLink(
            engine = engine,
            source = source,
            target = target,
            width = if (sameNetwork) 10f else 7f,
            duration = 0.18f,
            color = radiationBlue,
            core = radiationCore,
            alpha = if (sameNetwork) 0.62f else 0.44f,
        )
        emitNodePulse(engine, source.location, if (sameNetwork) 36f else 28f, Color(92, 152, 255, 130))
        incrementCounter(engine, TELEMETRY_RADIATION_BELT_PURSUIT_LINKS)
    }

    fun emitPlasmaShieldArc(engine: CombatEngineAPI, ship: ShipAPI, boosted: Boolean) {
        val shield = ship.shield ?: return
        if (!shield.isOn) return
        val center = shield.location ?: ship.location
        val radius = shield.radius.coerceAtLeast(ship.collisionRadius)
        val arcSpan = shield.activeArc * 0.1675f
        val halfArc = shield.activeArc * 0.5f
        val margin = arcSpan * 0.5f
        val mid = shield.facing + MathUtils.getRandomNumberInRange(-halfArc + margin, halfArc - margin)
        val angleA = mid - arcSpan * 0.5f
        val angleB = mid + arcSpan * 0.5f
        val points = (0..PLASMA_ARC_SEGMENTS).map { idx ->
            val t = idx / PLASMA_ARC_SEGMENTS.toFloat()
            val angle = angleA + (angleB - angleA) * t
            val radiusFraction = if (idx == 0 || idx == PLASMA_ARC_SEGMENTS) {
                MathUtils.getRandomNumberInRange(0.85f, 1f)
            } else {
                MathUtils.getRandomNumberInRange(0.93f, 1f)
            }
            edgeBiasedShieldPoint(center, radius, angle, radiusFraction)
        }

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
            for (idx in 0 until PLASMA_ARC_SEGMENTS) {
                spawnShieldArcSegment(engine, ship, points[idx], points[idx + 1], boosted, params)
            }
            emitShieldArcEndpointFlare(engine, ship, points.first(), boosted)
            emitShieldArcEndpointFlare(engine, ship, points.last(), boosted)
        } catch (_: Throwable) {
            handleBoxUtilFailure(engine, "plasma shield arc")
        }
        incrementCounter(engine, TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS)
    }

    fun applyPlasmaShieldVisuals(ship: ShipAPI, boostLevel: Float) {
        val shield = ship.shield ?: return
        if (!shield.isOn) return
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

    private fun spawnShieldArcSegment(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        from: Vector2f,
        to: Vector2f,
        boosted: Boolean,
        params: EmpArcEntityAPI.EmpArcParams,
    ) {
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
