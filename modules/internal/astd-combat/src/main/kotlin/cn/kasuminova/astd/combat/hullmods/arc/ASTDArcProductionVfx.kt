package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.effect.system.Xc102ShockwaveRingEffect
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import org.magiclib.util.MagicLensFlare
import java.awt.Color
import kotlin.math.roundToInt

object ASTDArcProductionVfx {
    const val TELEMETRY_XC_102_SHOCKWAVE_FRAMES = "xc102ShockwaveFrames"
    const val TELEMETRY_XC_102_SHOCKWAVE_RADIUS = "xc102ShockwaveRadius"
    const val TELEMETRY_XC_102_SHOCKWAVE_FLUX_PRESSURE = "xc102ShockwaveFluxPressure"
    const val TELEMETRY_XC_101_SHIELD_OPEN = "xc101ShieldOpen"
    const val TELEMETRY_XC_101_SYSTEM_ACTIVE = "xc101SystemActive"
    const val TELEMETRY_XC_101_SHIELD_ARC_EMISSIONS = "xc101ShieldArcEmissions"
    const val TELEMETRY_XC_103_SYSTEM_AFTERIMAGES = "xc103SystemAfterimages"

    private const val TELEMETRY_PREFIX = "astd_arc_production_vfx:"
    private const val VFX_FAILURE_WARN_KEY = "astd_arc_production_vfx_failure_warned"
    private const val BEAM_CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val BEAM_FRINGE_SPRITE = "graphics/fx/beamfringeb.png"
    private const val PLASMA_ARC_WIDTH = 18f

    private val log = Global.getLogger(ASTDArcProductionVfx::class.java)

    private val arcCore = Color(222, 248, 255, 235)
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

    fun renderXc102ShockwaveRing(
        engine: CombatEngineAPI,
        source: ShipAPI,
        level: Float,
        pressureRatio: Float,
    ) {
        val pressure = pressureRatio.coerceIn(0f, 1f)
        val frame = Xc102ShockwaveRingEffect.render(engine, source, level, pressure) ?: return
        incrementCounter(engine, TELEMETRY_XC_102_SHOCKWAVE_FRAMES)
        setCounter(engine, TELEMETRY_XC_102_SHOCKWAVE_RADIUS, frame.outerRadiusWorld.roundToInt().coerceAtLeast(1))
        setCounter(engine, TELEMETRY_XC_102_SHOCKWAVE_FLUX_PRESSURE, (pressure * 1000f).roundToInt().coerceAtLeast(1))
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
            arc.coreWidthOverride = PLASMA_ARC_WIDTH * if (boosted) 0.46f else 0.36f
            arc.setWarping(0f)
            emitShieldArcEndpointFlare(engine, ship, from, boosted)
            emitShieldArcEndpointFlare(engine, ship, to, boosted)
        } catch (_: Throwable) {
            handleBoxUtilFailure(engine, "plasma shield arc")
        }
        incrementCounter(engine, TELEMETRY_XC_101_SHIELD_ARC_EMISSIONS)
    }

    fun applyPlasmaShieldVisuals(ship: ShipAPI, boostLevel: Float) {
        val shield = ship.shield ?: return
        val level = boostLevel.coerceIn(0f, 1f)
        val ring = Misc.interpolateColor(PLASMA_SHIELD_BLUE_RING, PLASMA_SHIELD_PURPLE_RING, level)
        val inner = Misc.interpolateColor(PLASMA_SHIELD_BLUE_INNER, PLASMA_SHIELD_PURPLE_INNER, level)
        shield.ringColor = ring
        shield.innerColor = inner
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
