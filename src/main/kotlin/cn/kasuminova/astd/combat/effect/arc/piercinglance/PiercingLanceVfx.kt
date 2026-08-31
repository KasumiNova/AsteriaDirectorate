package cn.kasuminova.astd.combat.effect.arc.piercinglance

import cn.kasuminova.astd.api.combat.ConeImpactSpec
import cn.kasuminova.astd.impl.render.BeamSprites
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 贯星之矛命中特效触发层（规格 09 §3.2）：顶点大闪光 + 大光柱 + 锥状冲击锥面。
 *
 * 动机：单发 2500 重击与锥状冲击两层机制各需同帧玩家可见反馈（机制可视化铁律）：
 * - 顶点：大号 hitParticle 核心闪 + smoothParticle 光晕 + BoxUtil DistortionEntity（样板
 *   Aod7OnFireEffect.spawnDistortion），冷蓝白；
 * - 大光柱：沿命中矢量的短寿命 BoxUtil 渐变拖尾光柱（emissive 增益并入 bloom 管线，
 *   提案参数：长 ≈ 锥长 × 0.6、存续 0.25s，目检调宽度/时长）；
 * - 锥状冲击锥面：调共享锥面组件 [ConeImpactVfx]（正电子首发落地），锥角/锥长随结算 spec，
 *   冷蓝白调色（正电子为蓝色调缩小版）。
 */
object PiercingLanceVfx {
    private val log = Global.getLogger(PiercingLanceVfx::class.java)

    /** 大光柱长度占锥长比例（提案值，目检面）。 */
    private const val PILLAR_LENGTH_RATIO = 0.6f

    /** 大光柱存续（秒，提案值，目检面）：full 段 + 收尾淡出。 */
    private const val PILLAR_FULL_SECONDS = 0.05f
    private const val PILLAR_FADE_OUT_SECONDS = 0.2f

    /** 大光柱基宽/梢宽（su）：比重矛弹体带宽更粗的重击读感，收窄防糊屏（目检面）。 */
    private const val PILLAR_HEAD_WIDTH = 30f
    private const val PILLAR_TAIL_WIDTH = 9f

    /** 顶点闪光尺寸下限/占锥长比例（锥长 375 → 闪光 ≈110）。 */
    private const val FLASH_SIZE_RATIO = 0.30f
    private const val FLASH_SIZE_MIN = 60f

    /** 顶点大闪光核心色（冷蓝白近白）。 */
    private val FLASH_CORE_COLOR = Color(225, 242, 255)

    /** 顶点光晕/扭曲环色（冷蓝白）。 */
    private val FLASH_FRINGE_COLOR = Color(140, 205, 255)

    /** 大光柱核心色（冷蓝白高能量）。 */
    private val PILLAR_CORE_COLOR = Color(215, 240, 255)

    /** 大光柱辉光色（冷蓝白）。 */
    private val PILLAR_FRINGE_COLOR = Color(120, 195, 255)

    /** 锥状冲击锥面核心色（冷蓝白；正电子蓝色调缩小版的对照组）。 */
    private val CONE_CORE_COLOR = Color(185, 220, 255)

    /** 锥状冲击锥面辉光色。 */
    private val CONE_FRINGE_COLOR = Color(95, 160, 255)

    /** 静止速度矢量（顶点闪光/扭曲用，避免逐次分配）。 */
    private val ZERO_VEL = Vector2f(0f, 0f)

    /** (core, fringe) 束体贴图对（惰性加载一次；加载失败时光柱降级、锥面组件内部已自备降级）。 */
    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null

    /**
     * 触发一次命中特效三层：顶点大闪光（+ 扭曲）→ 大光柱 → 锥状冲击锥面（同帧）。
     * 锥内无额外目标时照常调用（机制存在性反馈，规格 §2.5）。
     */
    fun spawnImpact(engine: CombatEngineAPI, spec: ConeImpactSpec) {
        val facingDeg = Math.toDegrees(atan2(spec.direction.y.toDouble(), spec.direction.x.toDouble())).toFloat()
        // TODO(2026-07-30 锥面朝向排查探针，定位后移除)：记录命中点/方向矢量/朝向角，与舞台锚点比对。
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

    /** 命中星云爆发（规格 09 §3.2 追加）：10~15 个大小/速度各异的蓝色星云向四周消散，平均存续约 2s。 */
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

    /** 发射点扭曲（规格 09 §3.1 追加）：从内向外扩张的中等规模扭曲环，持续 1s，冷蓝白调（无跳变感）。 */
    fun spawnMuzzleDistortion(engine: CombatEngineAPI, muzzle: Vector2f) {
        BoxUtilCombatVfx.ensureReady(engine)
        val d = DistortionEntity()
        // 内 → 外：fadIn/full/fadeOut 三段尺寸递增（0.15s 长到 40% → 0.35s 到 70% → 0.5s 到全径后消散）
        d.setGlobalTimer(0.15f, 0.35f, 0.5f)
        d.setInnerFull(0.30f, 0.30f)
        d.setInnerHardness(0.70f)
        d.setRingHardness(0.55f)
        d.setSizeIn(MUZZLE_DISTORTION_RADIUS * 0.40f, MUZZLE_DISTORTION_RADIUS * 0.40f)
        d.setSizeFull(MUZZLE_DISTORTION_RADIUS * 0.70f, MUZZLE_DISTORTION_RADIUS * 0.70f)
        d.setSizeOut(MUZZLE_DISTORTION_RADIUS, MUZZLE_DISTORTION_RADIUS)
        d.setPowerIn(0.20f)
        d.setPowerFull(0.34f)
        d.setPowerOut(0f)
        d.setLocation(Vector2f(muzzle))
        CombatRenderingManager.addEntity(d)
        bumpTelemetry(engine, TELEMETRY_MUZZLE_DISTORTION)
    }

    /** 顶点大闪光：核心闪 + 光晕 + BoxUtil 扭曲环（冷蓝白，尺寸随锥长缩放）。 */
    private fun spawnVertexFlash(engine: CombatEngineAPI, spec: ConeImpactSpec) {
        val flashSize = (spec.range * FLASH_SIZE_RATIO).coerceAtLeast(FLASH_SIZE_MIN)
        engine.addHitParticle(spec.origin, ZERO_VEL, flashSize, 1.2f, 0.12f, FLASH_CORE_COLOR)
        engine.addSmoothParticle(spec.origin, ZERO_VEL, flashSize * 1.6f, 0.9f, 0.22f, FLASH_FRINGE_COLOR)

        BoxUtilCombatVfx.ensureReady(engine)
        val distortion = DistortionEntity()
        distortion.setGlobalTimer(0.03f, 0.05f, 0.18f)
        distortion.setInnerFull(0.30f, 0.30f)
        distortion.setInnerHardness(0.75f)
        distortion.setRingHardness(0.50f)
        distortion.setSizeIn(16f, 16f)
        distortion.setSizeFull(52f, 52f)
        distortion.setSizeOut(96f, 96f)
        distortion.setPowerIn(0f)
        distortion.setPowerFull(0.34f)
        distortion.setPowerOut(0f)
        distortion.setLocation(Vector2f(spec.origin))
        CombatRenderingManager.addEntity(distortion)
        bumpTelemetry(engine, TELEMETRY_IMPACT_FLASH)
    }

    /**
     * 大光柱：沿命中矢量的短寿命 BoxUtil 渐变拖尾（emissive 增益并入 bloom 管线）。
     * 全局定时器自管理生命周期（full 0.05s + 淡出 0.2s），无需 RenderEntity 树逐帧推进。
     */
    private fun spawnPillar(engine: CombatEngineAPI, spec: ConeImpactSpec, facingDeg: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val spritePair = sprites ?: BeamSprites.load()?.also { sprites = it } ?: run {
            log.warn("贯星之矛大光柱贴图加载失败，本次跳过光柱（锥面特效不受影响）")
            return
        }
        val entity = BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
            engine = engine,
            location = Vector2f(spec.origin),
            facing = facingDeg,
            length = spec.range * PILLAR_LENGTH_RATIO,
            tailWidth = PILLAR_TAIL_WIDTH,
            headWidth = PILLAR_HEAD_WIDTH,
            coreColor = PILLAR_CORE_COLOR,
            fringeColor = PILLAR_FRINGE_COLOR,
            coreSprite = spritePair.first,
            fringeSprite = spritePair.second,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = PILLAR_FULL_SECONDS,
            tailAlphaMul = 0.25f,
            headAlphaMul = 0.95f,
            tailEmissiveAlphaMul = 0.8f,
            headEmissiveAlphaMul = 2.2f,
            mixPower = 3.0f,
        ) ?: run {
            log.warn("贯星之矛大光柱建实体失败（BoxUtil addEntity 未就绪），本次跳过光柱")
            return
        }
        entity.setFillStartAlpha(0f)
        entity.setFillStartFactor(0.25f)
        entity.setFillEndAlpha(0f)
        entity.setFillEndFactor(0.9f)
        entity.setGlobalTimer(0f, PILLAR_FULL_SECONDS, PILLAR_FADE_OUT_SECONDS)
        bumpTelemetry(engine, TELEMETRY_PILLAR)
    }

    /** dev 自动化烟测证据计数（对齐 ConeStrike 遥测先例）：engine.customData 整数自增。 */
    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    // ---- dev 自动化烟测遥测键（engine.customData）----
    const val TELEMETRY_IMPACT_FLASH = "astd_piercing_lance_impact_flash"
    const val TELEMETRY_PILLAR = "astd_piercing_lance_pillar"
    const val TELEMETRY_CONE_VFX = "astd_piercing_lance_cone_vfx"
    const val TELEMETRY_NEBULA_BURST = "astd_piercing_lance_nebula_burst"
    const val TELEMETRY_MUZZLE_DISTORTION = "astd_piercing_lance_muzzle_distortion"

    /** 读整数遥测计数（无记录为 0）。 */
    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0
}

/** 命中星云单颗参数（纯数据，可测）：方向角（弧度）/ 初速（su/s）/ 尺寸（su）/ 存续（秒）。 */
data class NebulaSeed(val angleRad: Float, val speed: Float, val size: Float, val durationSeconds: Float)

/**
 * 命中星云爆发计划（纯函数，可测）：[NEBULA_BURST_COUNT_MIN]~[NEBULA_BURST_COUNT_MAX] 颗，
 * 方向均匀铺满圆周 + 抖动，速度 [NEBULA_SPEED_MIN]~[NEBULA_SPEED_MAX]、尺寸 [NEBULA_SIZE_MIN]~[NEBULA_SIZE_MAX]
 * 各自随机，存续以 [NEBULA_DURATION_AVG] 为中心 ±0.5s。
 */
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

/** 命中星云色（亮蓝，alpha 由 addNebulaParticle 亮度参数调制）。 */
private val NEBULA_COLOR = Color(110, 180, 255, 130)

/** 发射点扭曲全径（su）：约 0.7 × 等离子拱船宽（284）。 */
private const val MUZZLE_DISTORTION_RADIUS = 199f
