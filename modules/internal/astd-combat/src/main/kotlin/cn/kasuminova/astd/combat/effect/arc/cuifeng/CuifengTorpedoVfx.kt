package cn.kasuminova.astd.combat.effect.arc.cuifeng

import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BloomFlareSpec
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.api.render.BloomFlareVfx
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.effect.explosion.BloomFlareVfxImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.DistortionEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object CuifengTorpedoVfx {

    private val log = Global.getLogger(CuifengTorpedoVfx::class.java)

    private val bloomFlare: BloomFlareVfx = BloomFlareVfxImpl

    private const val CROSS_FLARE_DURATION = 0.5f

    private const val FLARE_SIZE_START = 200f
    private const val FLARE_SIZE_END = 600f
    private const val FLARE_HEIGHT = FLARE_SIZE_START / 8f

    private val FLARE_CORE_COLOR = ASTDColor(0xFFE1F2FF)

    private val FLARE_FRINGE_COLOR = ASTDColor(0xFF8CCDFF)

    private val NEBULA_COLOR = java.awt.Color(140, 200, 255, 130)

    private val ZERO_VEL = Vector2f(0f, 0f)

    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float) =
        spawnImpact(engine, point, hitAngleDeg, Random.Default)

    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, hitAngleDeg: Float, random: Random) {
        spawnCrossFlare(engine, point, hitAngleDeg)
        spawnPillarFlare(engine, point, random)
        spawnShockwaves(engine, point)
        spawnShardBurst(engine, point, random)
        spawnDistortion(engine, point)
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

    // 随机朝向大光柱：透明度为十字辉星一半、长宽均为其三倍，快速闪烁。
    private fun spawnPillarFlare(engine: CombatEngineAPI, point: Vector2f, random: Random) {
        val spec = BloomFlareSpec(
            style = BoxFlareStyle.SMOOTH_DISC,
            sizeStart = FLARE_SIZE_START * PILLAR_SIZE_MULT,
            sizeEnd = FLARE_SIZE_END * PILLAR_SIZE_MULT,
            heightStart = FLARE_HEIGHT * PILLAR_SIZE_MULT,
            heightEnd = FLARE_HEIGHT * PILLAR_SIZE_MULT,
            facingDeg = random.nextFloat() * 360f,
            coreColor = FLARE_CORE_COLOR.scaledAlpha(PILLAR_ALPHA),
            fringeColor = FLARE_FRINGE_COLOR.scaledAlpha(PILLAR_ALPHA),
            glowPower = 0.1f,
            noisePower = 0.05f,
            flicker = true,
        )
        val spawned = bloomFlare.spawn(engine, point, CROSS_FLARE_DURATION, listOf(spec))
        if (spawned > 0) bumpTelemetry(engine, TELEMETRY_PILLAR_FLARE)
    }

    // 双冲击波：astd_shockwave 快速扩散，astd_shockwave_ring 慢速扩散，统一蓝色。
    private fun spawnShockwaves(engine: CombatEngineAPI, point: Vector2f) {
        val fast = PooledCombatVfx.spawnSprite(
            engine, SHOCKWAVE_POOL,
            point.x, point.y,
            scaleX = SHOCK_FAST_SCALE_START, scaleY = SHOCK_FAST_SCALE_START,
            scaleRateX = SHOCK_FAST_SCALE_RATE, scaleRateY = SHOCK_FAST_SCALE_RATE,
            color = SHOCKWAVE_COLOR, emissiveColor = SHOCKWAVE_EMISSIVE,
            fadeIn = 0.03f, full = 0.06f, fadeOut = 0.40f,
        )
        val ring = PooledCombatVfx.spawnSprite(
            engine, SHOCKWAVE_RING_POOL,
            point.x, point.y,
            scaleX = SHOCK_RING_SCALE_START, scaleY = SHOCK_RING_SCALE_START,
            scaleRateX = SHOCK_RING_SCALE_RATE, scaleRateY = SHOCK_RING_SCALE_RATE,
            color = SHOCKWAVE_COLOR, emissiveColor = SHOCKWAVE_EMISSIVE,
            fadeIn = 0.05f, full = 0.25f, fadeOut = 0.80f,
        )
        if (fast && ring) bumpTelemetry(engine, TELEMETRY_SHOCKWAVES)
    }

    // 大量三角碎片，随机朝向/随机速度扩散。
    private fun spawnShardBurst(engine: CombatEngineAPI, point: Vector2f, random: Random) {
        var spawned = 0
        repeat(SHARD_COUNT) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = SHARD_SPEED_MIN + random.nextFloat() * (SHARD_SPEED_MAX - SHARD_SPEED_MIN)
            val side = SHARD_SIDE_MIN + random.nextFloat() * (SHARD_SIDE_MAX - SHARD_SIDE_MIN)
            val skew = SHARD_SKEW_LO + random.nextFloat() * (SHARD_SKEW_HI - SHARD_SKEW_LO)
            val brighten = random.nextFloat() < SHARD_CORE_RATIO
            val base = if (brighten) SHARD_CORE_COLOR else SHARD_FRINGE_COLOR
            val alpha = (SHARD_ALPHA_LO + random.nextFloat() * (SHARD_ALPHA_HI - SHARD_ALPHA_LO)).toInt()
            val spinMag = SHARD_SPIN_MIN + random.nextFloat() * (SHARD_SPIN_MAX - SHARD_SPIN_MIN)
            val spin = if (random.nextBoolean()) -spinMag else spinMag
            val accepted = PooledCombatVfx.spawnSprite(
                engine, SHARD_POOL,
                point.x, point.y,
                velX = (cos(angle.toDouble()) * speed).toFloat(),
                velY = (sin(angle.toDouble()) * speed).toFloat(),
                facingDeg = random.nextFloat() * 360f,
                turnRateDeg = spin,
                // Instance2Data 的 scale 是半尺寸（边长的一半）。
                scaleX = side * 0.5f,
                scaleY = side * 0.5f * skew,
                color = Color(base.red, base.green, base.blue, alpha),
                emissiveColor = Color(base.red, base.green, base.blue, (alpha * SHARD_EMISSIVE_ALPHA_MUL).toInt()),
                fadeIn = 0.02f,
                full = SHARD_FULL_LO + random.nextFloat() * (SHARD_FULL_HI - SHARD_FULL_LO),
                fadeOut = 0.32f,
            )
            if (accepted) spawned++
        }
        if (spawned > 0) bumpTelemetry(engine, TELEMETRY_SHARD_BURST)
        if (spawned < SHARD_COUNT) {
            log.warn("[ASTD] 摧锋命中碎片灌入粒子池部分缺席（$spawned/$SHARD_COUNT）")
        }
    }

    // 高强度扭曲实体。
    private fun spawnDistortion(engine: CombatEngineAPI, point: Vector2f) {
        BoxUtilCombatVfx.ensureReady(engine)
        val distortion = DistortionEntity()
        distortion.setGlobalTimer(0.10f, 0.30f, 0.50f)
        distortion.setSizeIn(40f, 40f)
        distortion.setSizeFull(120f, 120f)
        distortion.setSizeOut(320f, 320f)
        distortion.setInnerIn(0.25f, 0.25f)
        distortion.setInnerFull(0.25f, 0.25f)
        distortion.setInnerOut(0.60f, 0.60f)
        distortion.innerHardness = 0.85f
        distortion.ringHardness = 0.65f
        distortion.powerIn = 0.90f
        distortion.powerFull = 0.60f
        distortion.powerOut = 0f
        distortion.setLocation(Vector2f(point))
        val addState = BoxUtilCombatVfx.addEntity(engine, distortion)
        if (addState != 0) {
            log.warn("[ASTD] 摧锋命中扭曲实体注册失败（addEntity 返回 $addState），本次扭曲视觉缺席")
            distortion.delete()
            return
        }
        bumpTelemetry(engine, TELEMETRY_DISTORTION)
    }

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
    const val TELEMETRY_PILLAR_FLARE = "astd_cuifeng_pillar_flare"
    const val TELEMETRY_SHOCKWAVES = "astd_cuifeng_shockwaves"
    const val TELEMETRY_SHARD_BURST = "astd_cuifeng_shard_burst"
    const val TELEMETRY_DISTORTION = "astd_cuifeng_distortion"
    const val TELEMETRY_NEBULA_BURST = "astd_cuifeng_nebula_burst"

    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private const val PILLAR_SIZE_MULT = 3f
    private const val PILLAR_ALPHA = 0.5f

    private val SHOCKWAVE_COLOR = Color(150, 205, 255, 150)
    private val SHOCKWAVE_EMISSIVE = Color(170, 215, 255, 130)

    private val SHOCKWAVE_POOL = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shockwave.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )
    private val SHOCKWAVE_RING_POOL = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shockwave_ring.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )
    private val SHARD_POOL = PooledCombatVfx.SpritePoolKey(
        spritePath = "graphics/fx/astd_shard_tri.png",
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    )

    // scale 为半尺寸；扩散速度 = 半尺寸每秒增量。
    private const val SHOCK_FAST_SCALE_START = 20f
    private const val SHOCK_FAST_SCALE_RATE = 600f
    private const val SHOCK_RING_SCALE_START = 15f
    private const val SHOCK_RING_SCALE_RATE = 200f

    private const val SHARD_COUNT = 80
    private const val SHARD_SPEED_MIN = 120f
    private const val SHARD_SPEED_MAX = 360f
    private const val SHARD_SIDE_MIN = 6f
    private const val SHARD_SIDE_MAX = 15f
    private const val SHARD_SKEW_LO = 0.7f
    private const val SHARD_SKEW_HI = 1.3f
    private const val SHARD_CORE_RATIO = 0.25f
    private const val SHARD_ALPHA_LO = 140
    private const val SHARD_ALPHA_HI = 200
    private const val SHARD_EMISSIVE_ALPHA_MUL = 0.4f
    private const val SHARD_SPIN_MIN = 180f
    private const val SHARD_SPIN_MAX = 540f
    private const val SHARD_FULL_LO = 0.18f
    private const val SHARD_FULL_HI = 0.32f
    private val SHARD_CORE_COLOR = Color(0xE1, 0xF2, 0xFF)
    private val SHARD_FRINGE_COLOR = Color(0x8C, 0xCD, 0xFF)

    private const val NEBULA_COUNT = 4
    private const val NEBULA_SIZE_MIN = 75f
    private const val NEBULA_SIZE_MAX = 150f
    private const val NEBULA_SPEED_MIN = 20f
    private const val NEBULA_SPEED_MAX = 40f
    private const val NEBULA_DURATION_AVG = 1.0f
}
