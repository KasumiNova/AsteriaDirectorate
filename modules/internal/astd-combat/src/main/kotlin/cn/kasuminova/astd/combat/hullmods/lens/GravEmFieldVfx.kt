package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.base.SimpleParticleControlData
import org.boxutil.units.standard.entity.FlareEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal object GravEmFieldVfx {

    private val log = Global.getLogger(GravEmFieldVfx::class.java)

    private class FlarePool(
        val entity: FlareEntity,
        val controller: SimpleParticleControlData,
    )

    internal class CenterFlare internal constructor(
        private val entity: FlareEntity,
        private val baseSize: Float,
    ) {
        val expired: Boolean get() = entity.hasDelete()

        fun update(ship: ShipAPI, phaseBlend: Float, fieldBlend: Float) {
            if (entity.hasDelete()) return
            entity.setLocation(Vector2f(ship.location))
            entity.setCoreColor(lerpColor(CENTER_CORE, CENTER_CORE_PHASE, phaseBlend, fieldBlend))
            entity.setFringeColor(lerpColor(CENTER_FRINGE, CENTER_FRINGE_PHASE, phaseBlend, fieldBlend))
            val scale = CENTER_MIN_SCALE_FRACTION + (1f - CENTER_MIN_SCALE_FRACTION) * fieldBlend
            entity.setSize(baseSize * scale, baseSize * scale)
        }

        fun dispose() {
            BoxUtilCombatVfx.removeEntity(entity)
        }
    }

    fun disposeWavePools(engine: CombatEngineAPI) {
        disposePool(engine, GLOW_POOL_KEY)
        disposePool(engine, PILLAR_POOL_KEY)
    }

    private fun disposePool(engine: CombatEngineAPI, key: String) {
        val pool = engine.customData[key] as? FlarePool ?: return
        BoxUtilCombatVfx.removeEntity(pool.entity)
        engine.customData.remove(key)
    }

    fun spawnWave(engine: CombatEngineAPI, ship: ShipAPI, phaseBlend: Float, chargeGather: Float = 0f) {
        val glowPool = poolOf(engine, GLOW_POOL_KEY, smooth = true) ?: return
        val pillarPool = poolOf(engine, PILLAR_POOL_KEY, smooth = false) ?: return
        val core = lerpColor(FIELD_CORE, PHASE_CORE, phaseBlend)
        val fringe = lerpColor(FIELD_FRINGE, PHASE_FRINGE, phaseBlend)
        val shipVel = Vector2f(ship.velocity)
        val gather = chargeGather.coerceIn(0f, 1f)
        val speedMult = 1f + CHARGE_GATHER_SPEED_SPAN * gather

        val count = MathUtils.getRandomNumberInRange(
            GravEmFieldTuning.WAVE_COUNT_MIN, GravEmFieldTuning.WAVE_COUNT_MAX,
        )
        repeat(count) {
            val from = hullBoundaryPoint(ship)
            val baseAngle = if (gather > 0f) {
                Misc.getAngleInDegrees(from, ship.location)
            } else {
                Misc.getAngleInDegrees(ship.location, from)
            }
            val outAngle = BoxUtilCombatVfx.normalizeFacingDeg(
                baseAngle + MathUtils.getRandomNumberInRange(-30f, 30f),
            )
            val rad = Math.toRadians(outAngle.toDouble())
            val speed = MathUtils.getRandomNumberInRange(WAVE_SPEED_MIN, WAVE_SPEED_MAX) * speedMult
            val velocity = Vector2f(
                shipVel.x + (cos(rad) * speed).toFloat(),
                shipVel.y + (sin(rad) * speed).toFloat(),
            )
            val fadeIn: Float
            val full: Float
            val fadeOut: Float
            if (gather > 0f) {
                val flightSeconds = Misc.getDistance(from, ship.location) / speed
                fadeIn = minOf(WAVE_FADE_IN, flightSeconds * CHARGE_GATHER_FADE_IN_FRACTION)
                full = 0f
                fadeOut = (flightSeconds - fadeIn).coerceAtLeast(CHARGE_GATHER_MIN_FADE_OUT_SECONDS)
            } else {
                fadeIn = WAVE_FADE_IN
                full = WAVE_FULL
                fadeOut = WAVE_FADE_OUT
            }
            val scale = MathUtils.getRandomNumberInRange(WAVE_SCALE_MIN, WAVE_SCALE_MAX)
            glowPool.controller.addParticle(
                from, 0f, 0f, velocity, Vector2f(scale, scale), ZERO,
                core, fringe, fadeIn, full, fadeOut,
            )
            pillarPool.controller.addParticle(
                from, outAngle, 0f, velocity, Vector2f(scale, scale), ZERO,
                core, fringe, fadeIn, full, fadeOut,
            )
        }
    }

    fun maintainCenterFlare(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        current: CenterFlare?,
        phaseBlend: Float,
        fieldBlend: Float,
    ): CenterFlare? {
        if (engine.customData[CENTER_DISABLED_KEY] == DISABLED) return null
        if ((current == null || current.expired) && fieldBlend <= 0f) return null
        val flare = if (current == null || current.expired) createCenterFlare(engine, ship) else current
        flare?.update(ship, phaseBlend, fieldBlend)
        return flare
    }

    private fun createCenterFlare(engine: CombatEngineAPI, ship: ShipAPI): CenterFlare? {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            warnOnce(engine, CENTER_DISABLED_KEY, "[ASTD] 引力电子干扰力场中心光斑建实体失败（${t.javaClass.simpleName}），力场中心光斑视觉本场缺席", t)
            return null
        }
        entity.setLayer(CombatEngineLayers.BELOW_SHIPS_LAYER)
        entity.setAdditiveBlend()
        entity.setSmooth()
        entity.isFlick = false
        entity.glowPower = 0.1f
        val radius = ship.collisionRadius * CENTER_FLARE_RADIUS_MULT
        entity.setSize(radius * 2f, radius * 2f)
        entity.setCoreColor(CENTER_CORE)
        entity.setFringeColor(CENTER_FRINGE)
        entity.setGlobalTimer(0f, RESIDENT_FULL_SECONDS, 0f)
        entity.setStateVanilla(Vector2f(ship.location), 0f)
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            warnOnce(engine, CENTER_DISABLED_KEY, "[ASTD] 引力电子干扰力场中心光斑注册失败（addEntity 返回 $state，ship=${ship.id}），力场中心光斑视觉本场缺席")
            entity.delete()
            return null
        }
        return CenterFlare(entity, radius * 2f)
    }

    private fun poolOf(engine: CombatEngineAPI, key: String, smooth: Boolean): FlarePool? {
        if (engine.customData[key] == DISABLED) return null
        val existing = engine.customData[key] as? FlarePool
        if (existing != null && !existing.entity.hasDelete()) return existing
        val created = createPool(engine, key, smooth) ?: return null
        engine.customData[key] = created
        return created
    }

    private fun createPool(engine: CombatEngineAPI, key: String, smooth: Boolean): FlarePool? {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            warnOnce(engine, key, "[ASTD] 引力电子干扰力场波形光斑池建实体失败（${t.javaClass.simpleName}，smooth=$smooth），本池视觉本场缺席", t)
            return null
        }
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        entity.isFlick = false
        entity.glowPower = 0.1f
        if (smooth) {
            entity.setSmooth()
            entity.setSize(WAVE_GLOW_SIZE, WAVE_GLOW_SIZE)
        } else {
            entity.setSmoothDisc()
            entity.setSize(WAVE_PILLAR_LENGTH, WAVE_PILLAR_WIDTH)
        }
        entity.autoAspect()
        entity.setStateVanilla(ZERO, 0f)
        entity.setGlobalTimer(0f, RESIDENT_FULL_SECONDS, 0f)

        val controller = SimpleParticleControlData(WAVE_POOL_CAPACITY, WAVE_MAX_DUR, -5120f, false)
        entity.setControlData(controller)
        entity.setAutoSubmitEntityData(false)
        entity.setAutoSubmitModelMatrix(false)
        entity.submitEntityData()
        entity.submitModelMatrix()

        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            warnOnce(engine, key, "[ASTD] 引力电子干扰力场波形光斑池注册失败（addEntity 返回 $state，smooth=$smooth），本池视觉本场缺席")
            entity.delete()
            return null
        }
        return FlarePool(entity, controller)
    }

    private fun warnOnce(engine: CombatEngineAPI, key: String, message: String, t: Throwable? = null) {
        if (engine.customData[key] == DISABLED) return
        engine.customData[key] = DISABLED
        if (t == null) log.warn(message) else log.warn(message, t)
    }

    private fun lerpColor(a: Color, b: Color, t: Float, alphaMult: Float = 1f): Color {
        val u = t.coerceIn(0f, 1f)
        fun channel(x: Int, y: Int) = (x + (y - x) * u).roundToInt().coerceIn(0, 255)
        val alpha = (channel(a.alpha, b.alpha) * alphaMult).roundToInt().coerceIn(0, 255)
        return Color(channel(a.red, b.red), channel(a.green, b.green), channel(a.blue, b.blue), alpha)
    }

    private fun hullBoundaryPoint(ship: ShipAPI): Vector2f {
        val bounds = ship.exactBounds
        if (bounds != null) {
            bounds.update(ship.location, ship.facing)
            val segments = bounds.segments
            if (segments.isNotEmpty()) {
                val seg = segments[MathUtils.getRandomNumberInRange(0, segments.size - 1)]
                val t = MathUtils.getRandomNumberInRange(0f, 1f)
                return Vector2f(
                    seg.p1.x + (seg.p2.x - seg.p1.x) * t,
                    seg.p1.y + (seg.p2.y - seg.p1.y) * t,
                )
            }
        }
        val angle = MathUtils.getRandomNumberInRange(0f, 360f)
        val radius = ship.collisionRadius * 1.5f
        val rad = Math.toRadians(angle.toDouble())
        return Vector2f(
            ship.location.x + (cos(rad) * radius).toFloat(),
            ship.location.y + (sin(rad) * radius).toFloat(),
        )
    }

    private const val GLOW_POOL_KEY = "astd_grav_em_field_glow_pool"
    private const val PILLAR_POOL_KEY = "astd_grav_em_field_pillar_pool"

    private const val CENTER_DISABLED_KEY = "astd_grav_em_field_center_disabled"

    private const val DISABLED = "disabled"

    private const val WAVE_POOL_CAPACITY = 256

    private const val WAVE_MAX_DUR = 2f

    private const val RESIDENT_FULL_SECONDS = 1e7f

    private const val CHARGE_GATHER_SPEED_SPAN = 3f

    private const val CHARGE_GATHER_FADE_IN_FRACTION = 0.3f

    private const val CHARGE_GATHER_MIN_FADE_OUT_SECONDS = 0.05f

    const val PHASE_BLEND_SECONDS = 0.4f

    const val FIELD_BLEND_SECONDS = 0.5f

    private const val CENTER_MIN_SCALE_FRACTION = 0.2f

    private const val WAVE_GLOW_SIZE = 30f
    private const val WAVE_PILLAR_LENGTH = 200f
    private const val WAVE_PILLAR_WIDTH = 18f
    private const val WAVE_SPEED_MIN = 200f
    private const val WAVE_SPEED_MAX = 400f
    private const val WAVE_SCALE_MIN = 0.75f
    private const val WAVE_SCALE_MAX = 1.25f
    private const val WAVE_FADE_IN = 0.75f
    private const val WAVE_FULL = 1f
    private const val WAVE_FADE_OUT = 0.75f

    private const val CENTER_FLARE_RADIUS_MULT = 10f
    private const val CENTER_ALPHA = 26

    private val FIELD_CORE = Color(232, 205, 255, 80)
    private val FIELD_FRINGE = Color(186, 120, 255, 60)

    private val PHASE_CORE = Color(255, 170, 160, 80)
    private val PHASE_FRINGE = Color(215, 45, 60, 60)

    private val CENTER_CORE = Color(232, 205, 255, CENTER_ALPHA)
    private val CENTER_FRINGE = Color(186, 120, 255, CENTER_ALPHA)
    private val CENTER_CORE_PHASE = Color(255, 170, 160, CENTER_ALPHA)
    private val CENTER_FRINGE_PHASE = Color(215, 45, 60, CENTER_ALPHA)

    private val ZERO = Vector2f(0f, 0f)
}
