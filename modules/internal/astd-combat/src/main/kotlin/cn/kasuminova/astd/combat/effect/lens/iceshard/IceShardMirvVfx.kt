package cn.kasuminova.astd.combat.effect.lens.iceshard

import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.random.Random

object IceShardMirvVfx {

    private val ICE_COLOR = Color(170, 225, 255, 120)

    private val ATTACH_NEBULA_COLOR = Color(150, 205, 255, 110)

    private val ZERO_VEL = Vector2f(0f, 0f)

    fun spawnSplitBurst(engine: CombatEngineAPI, origin: Vector2f, random: Random) {
        engine.spawnExplosion(origin, ZERO_VEL, ICE_COLOR, SPLIT_FLASH_SIZE, SPLIT_FLASH_DURATION)
        repeat(SPLIT_NEBULA_COUNT) {
            val angle = random.nextFloat() * 360f
            val rad = Math.toRadians(angle.toDouble())
            val speed = SPLIT_NEBULA_SPEED_MIN +
                    random.nextFloat() * (SPLIT_NEBULA_SPEED_MAX - SPLIT_NEBULA_SPEED_MIN)
            engine.addNebulaParticle(
                Vector2f(origin),
                Vector2f((Math.cos(rad) * speed).toFloat(), (Math.sin(rad) * speed).toFloat()),
                SPLIT_NEBULA_SIZE, 1.9f, 0.25f, 0.55f,
                SPLIT_NEBULA_DURATION + (random.nextFloat() - 0.5f) * 0.4f, ICE_COLOR,
            )
        }
        bumpTelemetry(engine, TELEMETRY_SPLIT_VFX)
    }

    fun spawnAttachNebula(engine: CombatEngineAPI, point: Vector2f, random: Random) {
        repeat(ATTACH_NEBULA_COUNT) {
            val size = ATTACH_NEBULA_SIZE * (0.75f + random.nextFloat() * 0.5f)
            val angle = random.nextFloat() * 360f
            val rad = Math.toRadians(angle.toDouble())
            val speed = random.nextFloat() * ATTACH_NEBULA_SPEED_MAX
            engine.addNebulaParticle(
                Vector2f(point),
                Vector2f((Math.cos(rad) * speed).toFloat(), (Math.sin(rad) * speed).toFloat()),
                size, 1.9f, 0.25f, 0.55f,
                ATTACH_NEBULA_DURATION + (random.nextFloat() - 0.5f) * 0.4f, ATTACH_NEBULA_COLOR,
            )
        }
        bumpTelemetry(engine, TELEMETRY_ATTACH_NEBULA)
    }

    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    const val TELEMETRY_SPLIT_VFX = "astd_ice_shard_mirv_split_vfx"
    const val TELEMETRY_ATTACH_NEBULA = "astd_ice_shard_attach_nebula"

    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private const val SPLIT_FLASH_SIZE = 120f
    private const val SPLIT_FLASH_DURATION = 0.25f

    private const val SPLIT_NEBULA_COUNT = 6
    private const val SPLIT_NEBULA_SIZE = 40f
    private const val SPLIT_NEBULA_SPEED_MIN = 30f
    private const val SPLIT_NEBULA_SPEED_MAX = 90f
    private const val SPLIT_NEBULA_DURATION = 1.5f

    private const val ATTACH_NEBULA_COUNT = 3
    private const val ATTACH_NEBULA_SIZE = 50f
    private const val ATTACH_NEBULA_SPEED_MAX = 20f
    private const val ATTACH_NEBULA_DURATION = 2.0f
}
