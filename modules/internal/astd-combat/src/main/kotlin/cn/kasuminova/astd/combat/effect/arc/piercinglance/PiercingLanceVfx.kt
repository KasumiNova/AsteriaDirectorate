package cn.kasuminova.astd.combat.effect.arc.piercinglance

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.impl.render.BeamSprites
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseEnvelope
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.DistortionEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object PiercingLanceVfx {
    private val log = Global.getLogger(PiercingLanceVfx::class.java)

    private const val PILLAR_LENGTH_RATIO = 0.6f

    private const val PILLAR_FULL_SECONDS = 0.05f
    private const val PILLAR_FADE_OUT_SECONDS = 0.2f

    private const val PILLAR_HEAD_WIDTH = 30f
    private const val PILLAR_TAIL_WIDTH = 9f

    private const val FLASH_SIZE_RATIO = 0.30f
    private const val FLASH_SIZE_MIN = 60f

    private val FLASH_CORE_COLOR = Color(225, 242, 255)

    private val FLASH_FRINGE_COLOR = Color(140, 205, 255)

    private val PILLAR_CORE_COLOR = Color(215, 240, 255)

    private val PILLAR_FRINGE_COLOR = Color(120, 195, 255)

    private val CONE_CORE_COLOR = Color(185, 220, 255)

    private val CONE_FRINGE_COLOR = Color(95, 160, 255)

    private val ZERO_VEL = Vector2f(0f, 0f)

    private val PILLAR_POOL_KEY = TrailLeaseKey(
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        coreSpritePath = BeamSprites.CORE_PATH,
        fringeSpritePath = BeamSprites.FRINGE_PATH,
        mixPower = 3.0f,
        capacity = 16,
    )

    fun spawnImpact(engine: CombatEngineAPI, spec: ConeImpactSpec) {
        val facingDeg = Math.toDegrees(atan2(spec.direction.y.toDouble(), spec.direction.x.toDouble())).toFloat()
        // TODO: 临时方向探针，定位完成后移除
        log.info(
            "[pl-dir-probe] origin=(%.1f, %.1f) dir=(%.3f, %.3f) facingDeg=%.2f".format(
                spec.origin.x, spec.origin.y, spec.direction.x, spec.direction.y, facingDeg,
            ),
        )
        spawnVertexFlash(engine, spec)
        spawnNebulaBurst(engine, Vector2f(spec.origin), Random.Default)
        spawnPillar(engine, spec, facingDeg)
        ConeImpactVfx.spawn(
            engine,
            ConeImpactVfxSpec(
                origin = Vector2f(spec.origin),
                facingDeg = facingDeg,
                halfAngleDeg = spec.halfAngleDeg,
                length = spec.range,
                coreColor = CONE_CORE_COLOR,
                fringeColor = CONE_FRINGE_COLOR,
            ),
        )
        bumpTelemetry(engine, TELEMETRY_CONE_VFX)
    }

    fun spawnNebulaBurst(engine: CombatEngineAPI, origin: Vector2f, random: Random) {
        for (seed in nebulaBurstSeeds(random)) {
            val vel = Vector2f(
                (cos(seed.angleRad.toDouble()) * seed.speed).toFloat(),
                (sin(seed.angleRad.toDouble()) * seed.speed).toFloat(),
            )
            engine.addNebulaParticle(
                Vector2f(origin), vel, seed.size, 1.9f, 0.25f, 0.55f, seed.durationSeconds, NEBULA_COLOR,
            )
        }
        bumpTelemetry(engine, TELEMETRY_NEBULA_BURST)
    }

    fun spawnMuzzleDistortion(engine: CombatEngineAPI, muzzle: Vector2f) {
        BoxUtilCombatVfx.ensureReady(engine)
        val d = DistortionEntity().apply {
            setGlobalTimer(0.15f, 0.35f, 0.5f)
            setInnerFull(0.30f, 0.30f)
            innerHardness = 0.70f
            ringHardness = 0.55f
            setSizeIn(MUZZLE_DISTORTION_RADIUS * 0.40f, MUZZLE_DISTORTION_RADIUS * 0.40f)
            setSizeFull(MUZZLE_DISTORTION_RADIUS * 0.70f, MUZZLE_DISTORTION_RADIUS * 0.70f)
            setSizeOut(MUZZLE_DISTORTION_RADIUS, MUZZLE_DISTORTION_RADIUS)
            powerIn = 0.20f
            powerFull = 0.34f
            powerOut = 0f
            setLocation(Vector2f(muzzle))
        }
        BoxUtilCombatVfx.addEntity(engine, d)
        bumpTelemetry(engine, TELEMETRY_MUZZLE_DISTORTION)
    }

    private fun spawnVertexFlash(engine: CombatEngineAPI, spec: ConeImpactSpec) {
        val flashSize = (spec.range * FLASH_SIZE_RATIO).coerceAtLeast(FLASH_SIZE_MIN)
        engine.addHitParticle(spec.origin, ZERO_VEL, flashSize, 1.2f, 0.12f, FLASH_CORE_COLOR)
        engine.addSmoothParticle(spec.origin, ZERO_VEL, flashSize * 1.6f, 0.9f, 0.22f, FLASH_FRINGE_COLOR)

        BoxUtilCombatVfx.ensureReady(engine)
        val distortion = DistortionEntity().apply {
            setGlobalTimer(0.03f, 0.05f, 0.18f)
            setInnerFull(0.30f, 0.30f)
            innerHardness = 0.75f
            ringHardness = 0.50f
            setSizeIn(16f, 16f)
            setSizeFull(52f, 52f)
            setSizeOut(96f, 96f)
            powerIn = 0f
            powerFull = 0.34f
            powerOut = 0f
            setLocation(Vector2f(spec.origin))
        }
        BoxUtilCombatVfx.addEntity(engine, distortion)
        bumpTelemetry(engine, TELEMETRY_IMPACT_FLASH)
    }

    private fun spawnPillar(engine: CombatEngineAPI, spec: ConeImpactSpec, facingDeg: Float) {
        val lease = PooledCombatVfx.checkoutTrail(
            engine, PILLAR_POOL_KEY,
            TrailLeaseSpec(
                location = Vector2f(spec.origin),
                facingDeg = facingDeg,
                nodes = listOf(Vector2f(spec.range * PILLAR_LENGTH_RATIO, 0f), Vector2f(0f, 0f)),
                startWidth = PILLAR_TAIL_WIDTH,
                endWidth = PILLAR_HEAD_WIDTH,
                coreColor = PILLAR_CORE_COLOR,
                fringeColor = PILLAR_FRINGE_COLOR,
                startAlpha = 0.25f,
                endAlpha = 0.95f,
                startEmissiveAlpha = 0.8f,
                endEmissiveAlpha = 2.2f,
                fillStartAlpha = 0f,
                fillStartFactor = 0.25f,
                fillEndAlpha = 0f,
                fillEndFactor = 0.9f,
                envelope = TrailLeaseEnvelope(0f, PILLAR_FULL_SECONDS, PILLAR_FADE_OUT_SECONDS),
            ),
        )
        if (lease == null) {
            log.warn("贯星之矛大光柱池化检出失败（池不可用或池满），本次跳过光柱")
            return
        }
        bumpTelemetry(engine, TELEMETRY_PILLAR)
    }

    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    const val TELEMETRY_IMPACT_FLASH = "astd_piercing_lance_impact_flash"
    const val TELEMETRY_PILLAR = "astd_piercing_lance_pillar"
    const val TELEMETRY_CONE_VFX = "astd_piercing_lance_cone_vfx"
    const val TELEMETRY_NEBULA_BURST = "astd_piercing_lance_nebula_burst"
    const val TELEMETRY_MUZZLE_DISTORTION = "astd_piercing_lance_muzzle_distortion"

    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0
}

data class NebulaSeed(val angleRad: Float, val speed: Float, val size: Float, val durationSeconds: Float)

fun nebulaBurstSeeds(random: Random): List<NebulaSeed> {
    val count = NEBULA_BURST_COUNT_MIN + random.nextInt(NEBULA_BURST_COUNT_MAX - NEBULA_BURST_COUNT_MIN + 1)
    return (0 until count).map { i ->
        val base = (i.toFloat() / count) * (2f * Math.PI.toFloat())
        NebulaSeed(
            angleRad = base + (random.nextFloat() - 0.5f) * 0.6f,
            speed = NEBULA_SPEED_MIN + random.nextFloat() * (NEBULA_SPEED_MAX - NEBULA_SPEED_MIN),
            size = NEBULA_SIZE_MIN + random.nextFloat() * (NEBULA_SIZE_MAX - NEBULA_SIZE_MIN),
            durationSeconds = NEBULA_DURATION_AVG + (random.nextFloat() - 0.5f) * 1.0f,
        )
    }
}

private const val NEBULA_BURST_COUNT_MIN = 10
private const val NEBULA_BURST_COUNT_MAX = 15
private const val NEBULA_SPEED_MIN = 25f
private const val NEBULA_SPEED_MAX = 85f
private const val NEBULA_SIZE_MIN = 18f
private const val NEBULA_SIZE_MAX = 52f
private const val NEBULA_DURATION_AVG = 2.0f

private val NEBULA_COLOR = Color(110, 180, 255, 130)

private const val MUZZLE_DISTORTION_RADIUS = 199f
