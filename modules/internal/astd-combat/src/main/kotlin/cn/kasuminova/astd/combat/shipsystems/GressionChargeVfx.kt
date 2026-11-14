package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import cn.kasuminova.astd.renderer.effect.system.ASTDVectorThrustEngineManager
import cn.kasuminova.astd.renderer.effect.system.GressionFlowWrapVfx
import cn.kasuminova.astd.renderer.effect.system.GressionImpactVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.atan2
import kotlin.math.max

object GressionChargeVfx {

    private val log = AstdLog.logger

    private val FRINGE = ASTDColor(0xFF8CCDFF)

    private val TRAIL_COLOR = FRINGE.toAwt(255)
    private val ENGINE_FLAME = Color(255, 75, 75, 255)
    private val ENGINE_CONTRAIL = Color(255, 100, 100, 100)

    private val JITTER_UNDER = FRINGE.toAwt(155)
    private val JITTER = FRINGE.toAwt(55)

    private const val TRAIL_SPACING = 50f
    private const val TRAIL_INTERVAL = 0.1f
    private const val TRAIL_ALPHA = 0.7f
    private const val TRAIL_DURATION = 1.1f
    private const val TRAIL_GROWTH = 0.5f

    private const val FLAME_EXTEND = 1f
    private const val ENGINE_SHIFT_LEVEL = 1f
    private const val ENGINE_MAX_BLEND = 1f

    private const val JITTER_UNDER_COPIES = 25
    private const val JITTER_UNDER_RANGE = 7f
    private const val JITTER_COPIES = 3
    private const val JITTER_LEVEL = 0.6f

    private val ENGINE_COLOR_KEY = Any()

    fun updateWrap(engine: CombatEngineAPI, ship: ShipAPI) {
        GressionFlowWrapVfx.ensure(engine, ship)
    }

    class TrailSampler(
        private val spacing: Float = TRAIL_SPACING,
        private val interval: Float = TRAIL_INTERVAL,
    ) {
        private var distanceSinceEmission = 0.0
        private var timeSinceEmission = 0.0

        init {
            require(spacing.isFinite() && spacing > 0f) { "Afterimage spacing must be positive and finite: $spacing" }
            require(interval.isFinite() && interval > 0f) { "Afterimage interval must be positive and finite: $interval" }
        }

        fun advance(distance: Float, amount: Float, emit: (Float) -> Unit) {
            if (amount <= 0f) return
            var cursor = 0.0
            while (cursor < 1.0) {
                val distanceFraction = if (distance > 0f) {
                    max(0.0, spacing - distanceSinceEmission) / distance
                } else {
                    Double.POSITIVE_INFINITY
                }
                val timeFraction = max(0.0, interval - timeSinceEmission) / amount
                val step = max(distanceFraction, timeFraction)
                val remaining = 1.0 - cursor
                if (step > remaining) {
                    distanceSinceEmission += distance * remaining
                    timeSinceEmission += amount * remaining
                    return
                }
                cursor += step
                emit(cursor.toFloat())
                distanceSinceEmission = 0.0
                timeSinceEmission = 0.0
            }
        }
    }

    fun trail(engine: CombatEngineAPI, ship: ShipAPI, from: Vector2f, to: Vector2f, sampler: TrailSampler) {
        sampler.advance(MathUtils.getDistance(from, to), engine.elapsedInLastFrame) { t ->
            val point = Vector2f(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
            ASTDAfterimageEffect.spawnShip(
                engine, ship, point, TRAIL_COLOR, TRAIL_ALPHA, TRAIL_DURATION, growth = TRAIL_GROWTH,
            )
        }
    }

    fun spawnImpact(
        engine: CombatEngineAPI,
        origin: Vector2f,
        direction: Vector2f,
        range: Float,
        halfAngleDeg: Float,
    ) {
        val facingDeg = Math.toDegrees(atan2(direction.y.toDouble(), direction.x.toDouble())).toFloat()
        GressionImpactVfx.spawn(engine, origin, facingDeg, range, halfAngleDeg)
    }

    fun driveEngines(engine: CombatEngineAPI, ship: ShipAPI) {
        val controller = ship.engineController
        if (controller == null) {
            log.warn("[ASTD] 贯虹突进引擎过载缺席（engineController 为空，ship=${ship.id}）")
            return
        }
        val ramp = ASTDVectorThrustEngineManager.setSystemFullFlame(engine, ship, true)
        controller.extendFlame(ENGINE_COLOR_KEY, FLAME_EXTEND * ramp, FLAME_EXTEND * ramp, FLAME_EXTEND * ramp)
        controller.fadeToOtherColor(ENGINE_COLOR_KEY, ENGINE_FLAME, ENGINE_CONTRAIL, ENGINE_SHIFT_LEVEL * ramp, ENGINE_MAX_BLEND)
    }

    fun releaseEngines(engine: CombatEngineAPI, ship: ShipAPI) {
        ASTDVectorThrustEngineManager.setSystemFullFlame(engine, ship, false)
    }

    fun jitter(ship: ShipAPI, id: String, level: Float) {
        val strength = level.coerceIn(0f, 1f)
        if (strength <= 0f) return
        ship.isJitterShields = false
        ship.setJitterUnder(id, JITTER_UNDER, strength, JITTER_UNDER_COPIES, 0f, JITTER_UNDER_RANGE)
        ship.setJitter(id, JITTER, JITTER_LEVEL * strength, JITTER_COPIES, 0f, 0f)
    }

}

private fun ASTDColor.toAwt(alpha: Int): Color = Color(
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255),
    alpha.coerceIn(0, 255),
)
