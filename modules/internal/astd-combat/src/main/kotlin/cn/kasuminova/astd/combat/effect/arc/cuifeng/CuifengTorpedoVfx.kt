package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.api.render.BloomFlareVfx
import cn.kasuminova.astd.renderer.effect.explosion.BloomFlareVfxImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object CuifengTorpedoVfx {

    private val bloomFlare: BloomFlareVfx = BloomFlareVfxImpl

    private const val CROSS_FLARE_DURATION = 0.5f

    private const val FLARE_SIZE_START = 200f
    private const val FLARE_SIZE_END = 600f
    private const val FLARE_HEIGHT = FLARE_SIZE_START / 8f

    private val FLARE_CORE_COLOR = ASTDColor(0xFFE1F2FF)

    private val FLARE_FRINGE_COLOR = ASTDColor(0xFF8CCDFF)

    private val NEBULA_COLOR = java.awt.Color(140, 200, 255, 130)

    private val FLASH_COLOR = java.awt.Color(160, 210, 255, 90)

    private val ZERO_VEL = Vector2f(0f, 0f)

    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float) =
        spawnImpact(engine, point, hitAngleDeg, Random.Default)

    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float, random: Random) {
        spawnCrossFlare(engine, point, hitAngleDeg)
        spawnNebulaBurst(engine, point, random)
    }

    private fun spawnCrossFlare(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float) {
        val spawned = bloomFlare.spawn(
            engine, point, CROSS_FLARE_DURATION,
            listOf(
                crossFlareSpec(hitAngleDeg),
                crossFlareSpec(hitAngleDeg + 90f),
            ),
        )
        if (spawned > 0) bumpTelemetry(engine, TELEMETRY_CROSS_FLARE)
    }

    private fun crossFlareSpec(facingDeg: Float) = BloomFlareSpec(
        style = BoxFlareStyle.SMOOTH_DISC,
        sizeStart = FLARE_SIZE_START,
        sizeEnd = FLARE_SIZE_END,
        heightStart = FLARE_HEIGHT,
        heightEnd = FLARE_HEIGHT,
        facingDeg = facingDeg,
        coreColor = FLARE_CORE_COLOR,
        fringeColor = FLARE_FRINGE_COLOR,
        glowPower = 0.1f,
        noisePower = 0.05f,
    )

    private fun spawnNebulaBurst(engine: CombatEngineAPI, point: Vector2f, random: Random) {
        repeat(NEBULA_COUNT) {
            val angle = random.nextFloat() * 360f
            val speed = NEBULA_SPEED_MIN + random.nextFloat() * (NEBULA_SPEED_MAX - NEBULA_SPEED_MIN)
            val size = NEBULA_SIZE_MIN + random.nextFloat() * (NEBULA_SIZE_MAX - NEBULA_SIZE_MIN)
            val vel = Vector2f(
                (cos(Math.toRadians(angle.toDouble())) * speed).toFloat(),
                (sin(Math.toRadians(angle.toDouble())) * speed).toFloat(),
            )
            engine.addNebulaParticle(
                Vector2f(point), vel, size, 1.9f, 0.25f, 0.55f,
                NEBULA_DURATION_AVG + (random.nextFloat() - 0.5f) * 0.6f, NEBULA_COLOR,
            )
        }
        engine.spawnExplosion(point, ZERO_VEL, NEBULA_COLOR, 100f, 1f)
        bumpTelemetry(engine, TELEMETRY_NEBULA_BURST)
    }

    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    const val TELEMETRY_CROSS_FLARE = "astd_cuifeng_cross_flare"
    const val TELEMETRY_NEBULA_BURST = "astd_cuifeng_nebula_burst"

    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private const val FLASH_SIZE = 80f

    private const val NEBULA_COUNT = 12
    private const val NEBULA_SIZE_MIN = 75f
    private const val NEBULA_SIZE_MAX = 150f
    private const val NEBULA_SPEED_MIN = 20f
    private const val NEBULA_SPEED_MAX = 40f
    private const val NEBULA_DURATION_AVG = 1.0f
}
