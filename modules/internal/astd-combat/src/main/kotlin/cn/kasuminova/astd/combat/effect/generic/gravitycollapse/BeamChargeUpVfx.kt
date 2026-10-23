package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class BeamChargeUpVfx(
    private val coreColor: Color,
    private val glowColor: Color,
    private val scale: Float = 1f,
    private val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
) {

    private var particleAcc = 0f

    private var centerDotAcc = 0f

    fun reset() {
        particleAcc = 0f
        centerDotAcc = 0f
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun rand01(): Float = Math.random().toFloat()

    fun advance(
        engine: CombatEngineAPI,
        amount: Float,
        weapon: WeaponAPI,
        chargeLevel: Float,
    ) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val ship = weapon.ship ?: return
        if (ship.isHulk) return

        val t = chargeLevel.coerceIn(0f, 1f)
        val s = (0.20f + 0.80f * t.pow(0.65f)).coerceIn(0f, 1f)

        val sz = scale.coerceIn(0.35f, 2.25f)

        val shipVel = ship.velocity ?: Vector2f(0f, 0f)
        val velMul = (0.15f + 0.25f * s)

        val center = Vector2f(weapon.location)
        center.x += shipVel.x * velMul * amount
        center.y += shipVel.y * velMul * amount

        emitCenterDot(engine, amount, center, shipVel, s)

        val particleRate = (PARTICLE_PER_SEC * PARTICLE_RATE_MUL * (0.25f + 0.75f * s)).coerceAtLeast(0f)
        particleAcc += particleRate * amount
        val pCount = particleAcc.toInt().coerceAtMost(PARTICLE_MAX_PER_FRAME)
        if (pCount > 0) particleAcc -= pCount

        val radius = lerp(VANILLA_RADIUS_MAX, VANILLA_RADIUS_MIN, s) * sz * PARTICLE_RADIUS_MUL
        val speedBase = lerp(VANILLA_SPEED_MIN, VANILLA_SPEED_MAX, s) * PARTICLE_SPEED_MUL
        val sizeBase = lerp(VANILLA_SIZE_MIN, VANILLA_SIZE_MAX, s) * sz

        for (i in 0 until pCount) {
            val ang = rand01() * 360f
            val rad = Math.toRadians(ang.toDouble())
            val r = radius * (0.60f + 0.70f * rand01())
            val spawn = Vector2f(
                center.x + cos(rad).toFloat() * r,
                center.y + sin(rad).toFloat() * r,
            )

            val dx = center.x - spawn.x
            val dy = center.y - spawn.y
            val dist = sqrt((dx * dx + dy * dy).coerceAtLeast(0.001f))
            val ux = dx / dist
            val uy = dy / dist

            val speed = speedBase * (0.65f + 0.70f * rand01())
            val vel = Vector2f(ux * speed, uy * speed)

            val size = sizeBase * (0.70f + 0.60f * rand01())
            val dur = lerp(VANILLA_DUR_MIN, VANILLA_DUR_MAX, rand01())
            val bright = lerp(0.60f, 1.15f, rand01()) * (0.85f + 0.35f * s)

            val c = if (rand01() < 0.35f) coreColor else glowColor
            try {
                engine.addSmoothParticle(spawn, vel, size, bright, dur, c)
            } catch (_: Throwable) {
            }
        }
    }

    private fun emitCenterDot(
        engine: CombatEngineAPI,
        amount: Float,
        center: Vector2f,
        shipVel: Vector2f,
        strength: Float,
    ) {
        val s = strength.coerceIn(0f, 1f)

        val sz = scale.coerceIn(0.35f, 2.25f)

        val rate = (CENTER_DOT_PER_SEC * CENTER_DOT_RATE_MUL * (0.40f + 0.60f * s)).coerceAtLeast(0f)
        centerDotAcc += rate * amount
        val count = centerDotAcc.toInt().coerceAtMost(CENTER_DOT_MAX_PER_FRAME)
        if (count > 0) centerDotAcc -= count
        if (count <= 0) return

        for (i in 0 until count) {
            val size = lerp(CENTER_DOT_SIZE_MIN, CENTER_DOT_SIZE_MAX, s) * (0.85f + 0.35f * rand01()) * sz
            val dur = lerp(0.045f, 0.075f, rand01())
            val bright = lerp(0.55f, 1.45f, s) * (0.85f + 0.35f * rand01())
            val v = Vector2f(shipVel.x * 0.25f, shipVel.y * 0.25f)
            val c = if (rand01() < 0.40f) coreColor else glowColor
            try {
                engine.addSmoothParticle(center, v, size, bright, dur, c)
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        private const val PARTICLE_RATE_MUL = 0.60f
        private const val PARTICLE_RADIUS_MUL = 0.60f
        private const val PARTICLE_SPEED_MUL = 0.70f
        private const val CENTER_DOT_RATE_MUL = 0.60f

        private const val PARTICLE_PER_SEC = 160f
        private const val PARTICLE_MAX_PER_FRAME = 12

        private const val VANILLA_RADIUS_MAX = 420f
        private const val VANILLA_RADIUS_MIN = 140f

        private const val VANILLA_SPEED_MIN = 220f
        private const val VANILLA_SPEED_MAX = 720f

        private const val VANILLA_SIZE_MIN = 12f
        private const val VANILLA_SIZE_MAX = 30f

        private const val VANILLA_DUR_MIN = 0.16f
        private const val VANILLA_DUR_MAX = 0.34f

        private const val CENTER_DOT_PER_SEC = 40f
        private const val CENTER_DOT_MAX_PER_FRAME = 3
        private const val CENTER_DOT_SIZE_MIN = 14f
        private const val CENTER_DOT_SIZE_MAX = 46f
    }
}
