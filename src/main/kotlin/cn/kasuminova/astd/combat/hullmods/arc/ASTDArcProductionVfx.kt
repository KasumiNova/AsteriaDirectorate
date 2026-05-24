package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sin

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

    private val log = Global.getLogger(ASTDArcProductionVfx::class.java)

    private val arcBlue = Color(74, 166, 255, 190)
    private val arcCore = Color(222, 248, 255, 235)
    private val plasmaBlue = Color(96, 206, 255, 210)
    private val plasmaCore = Color(245, 255, 255, 245)
    private val radiationBlue = Color(80, 145, 255, 185)
    private val radiationCore = Color(212, 240, 255, 230)

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
        val angleA = shield.facing + MathUtils.getRandomNumberInRange(-shield.activeArc * 0.5f, shield.activeArc * 0.5f)
        val angleB = angleA + MathUtils.getRandomNumberInRange(if (boosted) 18f else 10f, if (boosted) 42f else 28f)
        val from = MathUtils.getPointOnCircumference(Vector2f(center), radius, angleA)
        val to = MathUtils.getPointOnCircumference(Vector2f(center), radius, angleB)
        val thickness = if (boosted) 9.5f else 6.5f
        emitSegmentedArc(engine, from, to, thickness, boosted)
        incrementCounter(engine, TELEMETRY_PLASMA_ARCH_SHIELD_ARC_EMISSIONS)
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

    private fun emitSegmentedArc(
        engine: CombatEngineAPI,
        from: Vector2f,
        to: Vector2f,
        thickness: Float,
        boosted: Boolean,
    ) {
        val direction = Vector2f.sub(to, from, null)
        val totalLength = direction.length()
        if (totalLength <= 2f) return
        direction.normalise()
        val normal = Vector2f(-direction.y, direction.x)
        val segments = if (boosted) 7 else 5
        val points = ArrayList<Vector2f>(segments + 1)
        val amplitude = if (boosted) 36f else 22f
        for (index in 0..segments) {
            val t = index.toFloat() / segments.toFloat()
            val base = Vector2f(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t,
            )
            if (index != 0 && index != segments) {
                val wave = sin(t * Math.PI * 2.0 + MathUtils.getRandomNumberInRange(-0.45f, 0.45f)).toFloat()
                val jitter = MathUtils.getRandomNumberInRange(-amplitude, amplitude) * 0.55f
                base.x += normal.x * (wave * amplitude + jitter)
                base.y += normal.y * (wave * amplitude + jitter)
            }
            points += base
        }

        val settings = Global.getSettings()
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            val length = MathUtils.getDistance(a, b)
            if (length <= 1f) continue
            val facing = org.lazywizard.lazylib.VectorUtils.getAngle(a, b)
            BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = Vector2f(a),
                facing = facing,
                length = length,
                baseWidth = thickness * (if (boosted) 1.08f else 0.92f),
                tipWidth = thickness * 0.42f,
                coreColor = plasmaBlue,
                fringeColor = plasmaCore,
                coreSprite = settings.getSprite(BEAM_CORE_SPRITE),
                fringeSprite = settings.getSprite(BEAM_FRINGE_SPRITE),
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                full = if (boosted) 0.20f else 0.16f,
                baseAlphaMul = if (boosted) 0.58f else 0.42f,
                tipAlphaMul = if (boosted) 0.10f else 0.07f,
                baseEmissiveAlphaMul = if (boosted) 0.96f else 0.78f,
                tipEmissiveAlphaMul = if (boosted) 0.25f else 0.16f,
                mixPower = 1f,
            )?.setGlobalTimer(0.015f, if (boosted) 0.08f else 0.06f, if (boosted) 0.22f else 0.18f)
                ?: handleBoxUtilFailure(engine, "plasma shield arc")
        }
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
