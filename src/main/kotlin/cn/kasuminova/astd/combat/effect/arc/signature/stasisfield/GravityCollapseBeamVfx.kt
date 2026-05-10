package cn.kasuminova.astd.combat.effect.arc.signature.stasisfield

import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamEllipseRingRenderer
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamSpriteRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.TaperedBeamTrailsVfx

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.util.IntervalUtil
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow

/**
 * 停滞场系统“投射束”视觉：
 * - 从舰首（近似炮口）连到停滞场中心；
 * - 红色主束（BoxUtil tapered trail）+ “沿束前进的环/螺旋细节”（SpriteEntity/BoxUtil bloom）。
 *
 * 设计点：
 * - System 侧没有 BeamAPI/WeaponAPI，因此这里做的是纯视觉束；
 * - 主束用短寿命 trail 周期刷新，避免需要可变长度的持久实体更新；
 * - 细节环采用 BeamEllipseOglRingStream（按距离采样），观感更“连续”。
 */
/**
 * 引力坍缩炮（Gravity Collapse）自绘束体 VFX：
 * - BoxUtil tapered trail 作为主束（core/glow + 镜像）；
 * - SpriteEntity 环作为“沿束细节”；
 * - 炮口音爆圈 + 红色短锥喷射作为爆发读感。
 *
 * 该类不关心武器具体数值；不同尺寸/变体通过 [scale] 做整体尺寸感缩放。
 */
internal class GravityCollapseBeamVfx(
    /** 视觉缩放：主要影响束宽、环尺寸、炮口光锥等“体积感”。 */
    private val scale: Float = 1f,
    /** 仅影响“主束宽度感”（不缩放环等附件），用于不同尺寸武器进一步压窄束宽。 */
    private val beamWidthMul: Float = 1f,
) {

    companion object {
        private const val BRIGHTNESS_MUL = 0.65f

        // 需求：提高光束中亮色（白芯）部分亮度（再 +25%）
        private const val WHITE_CORE_MUL = 1.09375f

        private val CORE_COLOR = Color(255, 45, 45, (235f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
        private val GLOW_COLOR = Color(255, 25, 25, (190f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
        private val HOT_COLOR = Color(255, 70, 70, (220f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))

        // 考虑：主光束白芯（配合红色外辉与光圈）。
        private val BEAM_CORE_WHITE = Color(255, 255, 255, (225f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))
        private val BEAM_FRINGE_PINK = Color(255, 130, 130, (200f * BRIGHTNESS_MUL).toInt().coerceIn(0, 255))

        // 主束刷新：越短越“丝滑”，但也更吃性能；这里取中间值。
        private const val TRAIL_REFRESH = 0.030f

        // 每次刷新的 trail 生命周期（淡入/停留/淡出）
        private const val BEAM_FADE_IN = 0.02f
        private const val BEAM_FULL = 0.05f
        private const val BEAM_FADE_OUT = 0.10f

        // 需求：充能完毕后束体不要“瞬间到终点”，而是“快速到终点”
        // 仅影响渲染，不影响命中/结算。
        private const val BEAM_GROW_TIME = 0.08f

        // 主/子环：永久态（整条束上持续存在），并沿束方向缓慢前进。
        private const val RING_SPACING = 150f
        private const val RING_TRAVEL_SPEED = 260f

        // 光圈：初始尺寸 -35%
        private const val RING_BASE_SIZE_MUL = 0.65f

        // FlareEntity 版光圈容易被主束“亮度压住”，这里把半径整体抬大一点。
        private const val RING_VISIBILITY_SIZE_MUL = 1.75f

        // 普通光圈：缩放从大到小（200% -> 100%）
        private const val RING_START_SCALE = 2.0f

        // 炮口光圈：额外放大到 300%~400%（乘在 RING_START_SCALE 之上）
        private const val MUZZLE_EXTRA_MIN = 1.50f
        private const val MUZZLE_EXTRA_MAX = 2.00f

        // 炮口光圈：散发速率（每秒）与寿命
        // 更像“爆发态”：更密、寿命更短、分布更集中
        private const val MUZZLE_EMIT_RATE = 8.5f
        private const val MUZZLE_EMIT_LIFE = 0.35f
        private const val MUZZLE_SPREAD_DIST = 55f

        // 炮口光锥爆发（短束喷射，参考 StellarJetBeamVfx）
        // 需求：炮口光锥散射角调大 2x
        private const val MUZZLE_CONE_ARC_DEG = 92f
        private const val MUZZLE_CONE_LEN_MIN = 160f
        private const val MUZZLE_CONE_LEN_MAX = 280f
        // 需求：降低光锥密度（-25%）
        private const val MUZZLE_CONE_COUNT_MIN = 5
        private const val MUZZLE_CONE_COUNT_MAX = 10
        // 需求：降低动画频率、提高存活时间（约 0.5s）
        private const val MUZZLE_CONE_FULL = 0.08f
        private const val MUZZLE_CONE_FADE_OUT = 0.42f

        // 需求：爆发光锥大小调小 40%
        private const val MUZZLE_CONE_GEOM_MUL = 0.60f

        // 子环：尺寸约小 30%，两套不同间距
        private const val SUB_RING_SCALE = 0.70f

        // 子光圈：相对主光圈的 offset（su）
        private const val SUB_RING_DISTANCE_OFFSET = -20f

        // 末端宽度：80%（轻微 taper）
        private const val TIP_WIDTH_MUL = 0.80f

        // 额外“螺旋粒子”
        private const val HELIX_RATE = 85f // particles/sec
        private const val HELIX_MAX_PER_FRAME = 10

        // 沿束 nebula 散发（参考 StellarJetBeamVfx 的密度/分布策略）
        // 需求：
        // - 提高 nebula 亮度（+50%） => alpha 基准 * 1.5
        // - 降低 nebula 尺寸（-33%） => size * 0.67
        // - 增加 nebula 数量（+100%） => rate * 2
        private const val AMBIENT_PARTICLES_PER_50SU_PER_SEC = 2.0f
        private const val AMBIENT_NEBULA_RATE_MUL = (1f / 3f)
        private const val AMBIENT_NEBULA_COUNT_MUL = 2.0f
        private const val AMBIENT_NEBULA_MAX_PER_FRAME = 18

        private const val AMBIENT_NEBULA_LATERAL_MUL = 1.30f
        private const val AMBIENT_NEBULA_SIDE_SPEED_MUL = 0.55f
        private const val AMBIENT_NEBULA_COLOR_ALPHA = 143
        private val AMBIENT_NEBULA_BASE_COLOR = Color(255, 55, 55)

        private const val AMBIENT_NEBULA_END_SIZE_MUL_MIN = 1.35f
        private const val AMBIENT_NEBULA_END_SIZE_MUL_MAX = 2.10f

        private const val AMBIENT_NEBULA_IN_FRAC = 0.12f
        private const val AMBIENT_NEBULA_FULL_FRAC = 0.22f
        private const val AMBIENT_NEBULA_OUT_FRAC = 0.66f

        private const val AMBIENT_NEBULA_SPEED_MIN = 6f
        private const val AMBIENT_NEBULA_SPEED_MAX = 42f
        private const val AMBIENT_NEBULA_SIZE_MIN = 12f
        private const val AMBIENT_NEBULA_SIZE_MAX = 35f
        private const val AMBIENT_NEBULA_DUR_MIN = 0.55f
        private const val AMBIENT_NEBULA_DUR_MAX = 1.35f
        private const val AMBIENT_NEBULA_OPACITY_MIN = 0.25f
        private const val AMBIENT_NEBULA_OPACITY_MAX = 0.55f

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }

    private val log = Global.getLogger(GravityCollapseBeamVfx::class.java)
    private val KEY_RING_UPSERT_ERR_ONCE = "astd_stasis_ring_upsert_err_once"

    private val trailInterval = IntervalUtil(TRAIL_REFRESH, TRAIL_REFRESH)

    // 炮口光锥：发射期间持续出现
    // 需求：出现频率提高 50%（0.50s -> 0.33s）
    private val muzzleConeInterval = IntervalUtil(0.333f, 0.333f)

    // 起手时间：用于避免“开火第一帧就刷后续光锥”，确保“闪烁式 burst 只在开火出现”。
    private var startedAt: Float? = null

    private var helixAcc = 0f

    // 沿束 nebula 散发累积器（用 acc 控频，避免每帧固定数量导致“束长变化时突然抖动”）
    private var ambientNebulaAcc = 0f
    private var ambientNebulaIndex = 0

    private var ringKeyBase: String? = null

    private data class GrowingCone(
        val entity: TrailEntity,
        val facing: Float,
        val createdAt: Float,
        val growDuration: Float,
        val targetLength: Float,
        val targetBaseWidth: Float,
        val targetTipWidth: Float,
        val baseAlpha: Float,
        val tipAlpha: Float,
        val baseEmissive: Float,
        val tipEmissive: Float,
    )

    private val growingCones: MutableList<GrowingCone> = ArrayList()

    private data class PersistentBeam(
        val coreMain: TrailEntity?,
        val coreMirror: TrailEntity?,
        val glowMain: TrailEntity?,
        val glowMirror: TrailEntity?,
    )

    private var persistentBeam: PersistentBeam? = null

    fun reset(engine: CombatEngineAPI? = null) {
        trailInterval.forceIntervalElapsed()
        muzzleConeInterval.forceIntervalElapsed()
        startedAt = null
        helixAcc = 0f
        ambientNebulaAcc = 0f
        ambientNebulaIndex = 0

        val k = ringKeyBase
        if (engine != null && k != null) {
            // 兼容：旧版/新版环渲染器都尽量清掉，避免战斗中热重载后叠加。
            try {
                AttachedBeamEllipseRingRenderer.remove(engine, k)
            } catch (_: Throwable) {
            }
            try {
                AttachedBeamEllipseRingRenderer.remove(engine, "$k:main")
            } catch (_: Throwable) {
            }
            try {
                AttachedBeamEllipseRingRenderer.remove(engine, "$k:sub130")
            } catch (_: Throwable) {
            }

            try {
                AttachedBeamSpriteRingRenderer.remove(engine, k)
            } catch (_: Throwable) {
            }
            try {
                AttachedBeamSpriteRingRenderer.remove(engine, "$k:main")
            } catch (_: Throwable) {
            }
            try {
                AttachedBeamSpriteRingRenderer.remove(engine, "$k:sub130")
            } catch (_: Throwable) {
            }
            try {
                AttachedBeamSpriteRingRenderer.remove(engine, "$k:muzzle")
            } catch (_: Throwable) {
            }
        }

        // 删除持久束，避免角度变化残留
        val p = persistentBeam
        if (p != null) {
            listOf(p.coreMain, p.coreMirror, p.glowMain, p.glowMirror).forEach {
                try {
                    it?.delete()
                } catch (_: Throwable) {
                }
            }
        }
        persistentBeam = null

        // 删除“几何渐长”的炮口光锥
        if (growingCones.isNotEmpty()) {
            for (c in growingCones) {
                try {
                    c.entity.delete()
                } catch (_: Throwable) {
                }
            }
            growingCones.clear()
        }
    }

    fun onStart(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, level: Float) {
        val t = level.coerceIn(0f, 1f)
        val s = scale.coerceIn(0.35f, 2.25f)

        startedAt = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            null
        }

        // 起手闪光（截图里的“更亮的炮口/束根部”读感）
        try {
            engine.spawnExplosion(from, Vector2f(0f, 0f), CORE_COLOR, lerp(140f, 240f, t) * BRIGHTNESS_MUL * s, 0.22f)
        } catch (_: Throwable) {
        }
        try {
            engine.addSmoothParticle(from, Vector2f(0f, 0f), lerp(220f, 380f, t) * BRIGHTNESS_MUL * s, 1.25f * BRIGHTNESS_MUL, 0.28f, GLOW_COLOR)
        } catch (_: Throwable) {
        }

        // 小范围火花沿束方向喷出一点，避免“突然出现一条直线”
        val line = BeamLineUtil.fromPoints(from, to) ?: return
        repeat(14) {
            val ang = line.facing + MathUtils.getRandomNumberInRange(-10f, 10f)
            val speed = MathUtils.getRandomNumberInRange(220f, 760f) * (0.65f + 0.65f * t)
            val vel = MathUtils.getPointOnCircumference(Vector2f(0f, 0f), speed, ang)
            val size = MathUtils.getRandomNumberInRange(18f, 44f) * s
            val dur = MathUtils.getRandomNumberInRange(0.10f, 0.22f)
            val c = if (Math.random() < 0.35) HOT_COLOR else CORE_COLOR
            try {
                engine.addSmoothParticle(from, vel, size * BRIGHTNESS_MUL, 1.15f * BRIGHTNESS_MUL, dur, c)
            } catch (_: Throwable) {
            }
        }

        // 炮口“光锥”爆发：短束喷射（红色），强化点火/爆发读感。
        spawnMuzzleConeBurst(engine = engine, center = from, facing = line.facing, level = t)
    }

    fun advance(engine: CombatEngineAPI, amount: Float, from: Vector2f, to: Vector2f, effectLevel: Float, fadeMul: Float = 1f) {
        if (amount <= 0f) return

        val level = effectLevel.coerceIn(0f, 1f)
        val fade = fadeMul.coerceIn(0f, 1f)
        val s = scale.coerceIn(0.35f, 2.25f)
        val wMul = beamWidthMul.coerceIn(0.35f, 1.25f)
        // 光圈跟随 fadeMul 平滑淡出（平方曲线：初期衰减缓，尾段快速归零）。
        val ringFade = (fade * fade).coerceIn(0f, 1f)

        val lineFull = BeamLineUtil.fromPoints(from, to) ?: return

        val now = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }

        // 渲染端“快速延伸”：前 BEAM_GROW_TIME 秒内把束长按比例拉长。
        val sa = startedAt
        val reach = if (sa == null || now <= 0f) {
            1f
        } else {
            ((now - sa) / BEAM_GROW_TIME).coerceIn(0f, 1f)
        }
        val line = if (reach >= 0.999f) {
            lineFull
        } else {
            val newTo = Vector2f(
                lineFull.from.x + lineFull.dirUnit.x * lineFull.length * reach,
                lineFull.from.y + lineFull.dirUnit.y * lineFull.length * reach,
            )
            BeamLineUtil.fromPoints(lineFull.from, newTo) ?: lineFull
        }
        val ringEndFade = max(72f * s, min(line.length * 0.18f, 180f * s))
        val ringSpawnFade = 14f * s
        val muzzleOffset = min(70f * s, max(18f * s, line.length * 0.12f))
        val ringMaxInstances = if (line.length > 2200f) 128 else 112

        // 更新“几何渐长”的炮口光锥（需要在任何其它 VFX 之前更新，确保位置/朝向正确）
        advanceGrowingCones(engine, now, line.from)

        // 主束：持久实体，每帧更新（避免转向时残留拖影）
        // 放在环之前：若 BoxUtil 在同层/同批次里按插入顺序绘制，可减少“环被主束盖住”的概率。
        val coreW = lerp(18f, 34f, level) * 0.50f * s * wMul
        val glowW = coreW * 2.15f
        upsertPersistentBeam(engine, line, level, coreW, glowW, fade)

        // 炮口“光锥”后续喷射：
        // - 闪烁式 burst 只在 onStart 触发；
        // - 后续喷射整体缩小（75%），并用更长的 fade-in 让它“长出来”而不是闪出；
        // - 开火初期留一个很短的空窗，避免第一帧就叠加后续喷射。
        if (fade > 0.01f && level > 0.01f) {
            val allow = sa == null || now - sa >= 0.20f
            if (allow) {
                muzzleConeInterval.advance(amount)
                if (muzzleConeInterval.intervalElapsed()) {
                    spawnMuzzleConeSpray(engine = engine, center = line.from, facing = line.facing, level = level, fade = fade)
                }
            }
        }

        // ====== 普通光圈：永久态 ======
        // ringFade = fade² 控制淡出 alpha；fade 由调用方（END_FADE_TIME 阶段）逐帧传入，
        // 到 0 时外部调用 reset() 彻底清理。
        val baseKey = ringKeyBase ?: run {
            val k = "astd_stasis_collapse_nozzle_rings:" + System.identityHashCode(this)
            ringKeyBase = k
            k
        }
        if (ringFade > 0.001f) {
        try {
            AttachedBeamSpriteRingRenderer.upsert(
                engine = engine,
                key = "$baseKey:main",
                line = line,
                spec = AttachedBeamSpriteRingRenderer.Spec(
                    mode = AttachedBeamSpriteRingRenderer.Mode.PERMANENT,
                    spacing = RING_SPACING * s,
                    travelSpeed = RING_TRAVEL_SPEED,
                    // a(侧向) > b(沿向)：更像“垂直于束的圈”
                    aSideHalf = 44f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    bAlongHalf = 20f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    color = Color(255, 45, 45, (110f * BRIGHTNESS_MUL * ringFade).toInt().coerceIn(0, 255)),
                    // 大 -> 小（200% -> 100%）
                    headScale = RING_START_SCALE,
                    tailScale = 1.0f,
                    endFadeDistance = ringEndFade,
                    spawnFadeInDistance = ringSpawnFade,
                    // 普通光圈不做“炮口额外放大”，避免与炮口散发光圈叠加过亮
                    muzzleExtraScaleMin = 1.0f,
                    muzzleExtraScaleMax = 1.0f,
                    glowPower = 1.35f,
                    layer = CombatEngineLayers.ABOVE_PARTICLES,
                    maxInstances = ringMaxInstances,
                )
            )
        } catch (t: Throwable) {
            if (engine.customData[KEY_RING_UPSERT_ERR_ONCE] != true) {
                engine.customData[KEY_RING_UPSERT_ERR_ONCE] = true
                log.warn("AttachedBeamSpriteRingRenderer.upsert(main) failed; rings will be invisible.", t)
            }
        }

        // 子环：更小、更密一点，作为“细节层”
        val subA = 44f * RING_BASE_SIZE_MUL * SUB_RING_SCALE * RING_VISIBILITY_SIZE_MUL * s
        val subB = 20f * RING_BASE_SIZE_MUL * SUB_RING_SCALE * RING_VISIBILITY_SIZE_MUL * s
        try {
            AttachedBeamSpriteRingRenderer.upsert(
                engine = engine,
                key = "$baseKey:sub130",
                line = line,
                spec = AttachedBeamSpriteRingRenderer.Spec(
                    mode = AttachedBeamSpriteRingRenderer.Mode.PERMANENT,
                    spacing = RING_SPACING * s,
                    travelSpeed = RING_TRAVEL_SPEED,
                    aSideHalf = subA,
                    bAlongHalf = subB,
                    distanceOffset = SUB_RING_DISTANCE_OFFSET * s,
                    color = Color(255, 45, 45, (88f * BRIGHTNESS_MUL * ringFade).toInt().coerceIn(0, 255)),
                    headScale = RING_START_SCALE,
                    tailScale = 1.0f,
                    endFadeDistance = ringEndFade,
                    spawnFadeInDistance = ringSpawnFade,
                    muzzleExtraScaleMin = 1.0f,
                    muzzleExtraScaleMax = 1.0f,
                    glowPower = 1.25f,
                    layer = CombatEngineLayers.ABOVE_PARTICLES,
                    maxInstances = ringMaxInstances,
                )
            )
        } catch (t: Throwable) {
            if (engine.customData[KEY_RING_UPSERT_ERR_ONCE] != true) {
                engine.customData[KEY_RING_UPSERT_ERR_ONCE] = true
                log.warn("AttachedBeamSpriteRingRenderer.upsert(sub) failed; rings will be invisible.", t)
            }
        }

        // ====== 炮口光圈：散发态（不向前推进） ======
        // 只影响发射端读感：音爆式“单圈快速变大”，同一时间只存在一个。
        try {
            AttachedBeamSpriteRingRenderer.upsert(
                engine = engine,
                key = "$baseKey:muzzle",
                line = line,
                spec = AttachedBeamSpriteRingRenderer.Spec(
                    mode = AttachedBeamSpriteRingRenderer.Mode.MUZZLE_PULSE,
                    // spacing 在 MUZZLE_EMIT 下主要用于自动参数推导；这里仍给一个合理值
                    spacing = RING_SPACING * s,
                    travelSpeed = 0f,
                    aSideHalf = 44f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    bAlongHalf = 20f * RING_BASE_SIZE_MUL * RING_VISIBILITY_SIZE_MUL * s,
                    // 需求：大光圈渲染 offset 调整（100su -> 70su）
                    distanceOffset = muzzleOffset,
                    color = Color(255, 45, 45, (110f * BRIGHTNESS_MUL * ringFade).toInt().coerceIn(0, 255)),
                    headScale = RING_START_SCALE,
                    tailScale = RING_START_SCALE,
                    endFadeDistance = max(56f * s, line.length * 0.10f),
                    spawnFadeInDistance = 10f * s,
                    // 这里的“从小到大”由 pulseStart/endScale 控制；muzzleExtra 只做轻微随机，避免每次完全一样。
                    muzzleExtraScaleMin = 0.95f,
                    muzzleExtraScaleMax = 1.10f,
                    // 单圈音爆：更短寿命 + 快速扩张
                    // 需求：0.5s 一次（单圈模式下频率≈寿命）
                    pulseLifetime = 0.50f,
                    pulseStartScale = 0.28f,
                    pulseEndScale = 2.55f,
                    pulseScaleExponent = 0.33f,
                    muzzleSpreadDistance = 0f,
                    glowPower = 1.70f,
                    layer = CombatEngineLayers.ABOVE_PARTICLES,
                    maxInstances = 1,
                )
            )
        } catch (_: Throwable) {
        }
        } // if (ringFade > 0.001f)
        // 沿束 nebula 散发：让光束更“有体积/有尘埃”，并在束长变化时保持连续。
        // 在淡出阶段也保留，但会随 fade 自动降密度与透明度。
        emitAmbientBeamNebula(
            engine = engine,
            amount = amount,
            line = line,
            level = level,
            fade = fade,
            coreWidth = coreW,
            glowWidth = glowW,
        )

        // 细小光束装饰：仍用 interval 刷新即可
        trailInterval.advance(amount)
        if (trailInterval.intervalElapsed()) {
            if (fade > 0.999f) {
                spawnDecorativeMicroBeams(engine, now, line, level)
            }
        }

        // “螺旋细节粒子”：让截图里那种绕束的红色弧线更明显
        if (fade > 0.999f) {
            helixAcc += (HELIX_RATE * (0.55f + 0.75f * level)) * amount
        }
        val helixCount = helixAcc.toInt().coerceIn(0, HELIX_MAX_PER_FRAME)
        if (helixCount > 0) {
            helixAcc -= helixCount

            val amp = lerp(12f, 28f, level)
            repeat(helixCount) {
                val u = Math.random().toFloat().coerceIn(0f, 1f)

                val phase = (now * 10.5f) + u * (2f * PI).toFloat() * 3.0f
                val wobble = sin(phase.toDouble()).toFloat() * amp

                val base = Vector2f(
                    line.from.x + line.dirUnit.x * line.length * u,
                    line.from.y + line.dirUnit.y * line.length * u,
                )
                val loc = Vector2f(
                    base.x + line.perpUnit.x * wobble,
                    base.y + line.perpUnit.y * wobble,
                )

                // 速度：沿束方向 + 少量侧向，形成“旋进”动感
                val along = MathUtils.getRandomNumberInRange(140f, 360f) * (0.75f + 0.55f * level)
                val side = cos(phase.toDouble()).toFloat() * MathUtils.getRandomNumberInRange(45f, 110f)
                val vel = Vector2f(
                    line.dirUnit.x * along + line.perpUnit.x * side,
                    line.dirUnit.y * along + line.perpUnit.y * side,
                )

                val size = lerp(10f, 18f, level) * MathUtils.getRandomNumberInRange(0.75f, 1.25f)
                val dur = MathUtils.getRandomNumberInRange(0.18f, 0.40f)
                val bright = MathUtils.getRandomNumberInRange(0.85f, 1.35f) * BRIGHTNESS_MUL
                val c = if (Math.random() < 0.45) CORE_COLOR else GLOW_COLOR
                try {
                    engine.addSmoothParticle(loc, vel, size, bright, dur, c)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun spawnMuzzleConeBurst(engine: CombatEngineAPI, center: Vector2f, facing: Float, level: Float) {
        val t = level.coerceIn(0f, 1f)
        val s = scale.coerceIn(0.35f, 2.25f)

        val coreSprite = try {
            Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
        } catch (_: Throwable) {
            null
        } ?: return

        val fringeSprite = try {
            Global.getSettings().getSprite("graphics/fx/beamfringeb.png")
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (_: Throwable) {
        }

        val count = (lerp(MUZZLE_CONE_COUNT_MIN.toFloat(), MUZZLE_CONE_COUNT_MAX.toFloat(), t)).toInt()
            .coerceIn(MUZZLE_CONE_COUNT_MIN, MUZZLE_CONE_COUNT_MAX)
        val halfArc = MUZZLE_CONE_ARC_DEG * 0.5f

        // 需求：角度更随机，但至少保证两侧平均出现（每次 burst 至少各一条）。
        val signs = ArrayList<Float>(count)
        if (count >= 2) {
            signs.add(1f)
            signs.add(-1f)
        }
        while (signs.size < count) {
            signs.add(if (Math.random() < 0.5) 1f else -1f)
        }
        signs.shuffle()

        repeat(count) {
            val sign = signs[it]
            val mag = MathUtils.getRandomNumberInRange(0f, halfArc)
            val ang = facing + sign * mag + MathUtils.getRandomNumberInRange(-4.0f, 4.0f)
            val len = lerp(MUZZLE_CONE_LEN_MIN, MUZZLE_CONE_LEN_MAX, Math.random().toFloat()) * (0.85f + 0.35f * t) * s * MUZZLE_CONE_GEOM_MUL
            // 需求：降低光锥发射部宽度（-40%）
            val baseW = lerp(70f, 125f, t) * 0.90f * 0.60f * s * MUZZLE_CONE_GEOM_MUL
            val tipW = (baseW * 0.08f).coerceAtLeast(1.2f * s * MUZZLE_CONE_GEOM_MUL)

            val e = try {
                BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                    engine = engine,
                    location = center,
                    facing = ang,
                    length = len,
                    baseWidth = baseW,
                    tipWidth = tipW,
                    coreColor = HOT_COLOR,
                    fringeColor = CORE_COLOR,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    full = MUZZLE_CONE_FULL,
                    baseAlphaMul = 0.22f * BRIGHTNESS_MUL,
                    tipAlphaMul = 0.04f * BRIGHTNESS_MUL,
                    baseEmissiveAlphaMul = 2.45f * BRIGHTNESS_MUL,
                    tipEmissiveAlphaMul = 0.55f * BRIGHTNESS_MUL,
                    mixPower = 3.25f,
                )
            } catch (_: Throwable) {
                null
            }

            if (e != null) {
                try {
                    e.setGlobalTimer(0f, MUZZLE_CONE_FULL, MUZZLE_CONE_FADE_OUT)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun spawnMuzzleConeSpray(engine: CombatEngineAPI, center: Vector2f, facing: Float, level: Float, fade: Float) {
        val t = (level * fade).coerceIn(0f, 1f)
        if (t <= 0.001f) return

        val s = scale.coerceIn(0.35f, 2.25f)

        val coreSprite = try {
            Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
        } catch (_: Throwable) {
            null
        } ?: return

        val fringeSprite = try {
            Global.getSettings().getSprite("graphics/fx/beamfringeb.png")
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (_: Throwable) {
        }

        // 需求：降低光锥密度（-25%）
        // 这里保持每次喷射固定 2 条，确保左右各一条（不会出现只喷一侧的情况）。
        val count = 2
        val halfArc = MUZZLE_CONE_ARC_DEG * 0.5f

        // 需求：角度更随机，但至少保证两侧平均出现（每次 spray 至少各一条）。
        val signs = ArrayList<Float>(count)
        if (count >= 2) {
            signs.add(1f)
            signs.add(-1f)
        }
        while (signs.size < count) {
            signs.add(if (Math.random() < 0.5) 1f else -1f)
        }
        signs.shuffle()

        val createdAt = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }

        repeat(count) {
            val sign = signs[it]
            val mag = MathUtils.getRandomNumberInRange(0f, halfArc)
            val ang = facing + sign * mag + MathUtils.getRandomNumberInRange(-4.0f, 4.0f)
            // 需求：后续爆发光锥大小为 75%
            val sizeMul = 0.75f
            val len = lerp(120f, 210f, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.85f + 0.35f * t) * s * sizeMul * MUZZLE_CONE_GEOM_MUL
            // 需求：降低光锥发射部宽度（-40%）
            val baseW = lerp(55f, 95f, t) * 0.60f * s * sizeMul * MUZZLE_CONE_GEOM_MUL
            val tipW = (baseW * 0.10f).coerceAtLeast(1.2f * s * MUZZLE_CONE_GEOM_MUL)

            val e = try {
                BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                    engine = engine,
                    location = center,
                    facing = ang,
                    // 初始长度/宽度很小；后续在 advanceGrowingCones 中做“几何渐长”。
                    length = 1f,
                    baseWidth = 1f,
                    tipWidth = 1f,
                    coreColor = HOT_COLOR,
                    fringeColor = CORE_COLOR,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    full = 9999f,
                    baseAlphaMul = 0.12f * BRIGHTNESS_MUL,
                    tipAlphaMul = 0.03f * BRIGHTNESS_MUL,
                    baseEmissiveAlphaMul = 1.75f * BRIGHTNESS_MUL,
                    tipEmissiveAlphaMul = 0.45f * BRIGHTNESS_MUL,
                    mixPower = 3.10f,
                )
            } catch (_: Throwable) {
                null
            }

            if (e != null) {
                try {
                    // 总可见时间约 0.55s；alpha 将由我们在 grow 阶段拉起。
                    e.setGlobalTimer(0f, 0.22f, 0.33f)
                } catch (_: Throwable) {
                }

                // 注册为“几何渐长”实体：长度/宽度随时间插值增长。
                // growDuration 取 0.22s，与原本的“缓慢出现”节奏一致。
                growingCones.add(
                    GrowingCone(
                        entity = e,
                        facing = ang,
                        createdAt = createdAt,
                        growDuration = 0.22f,
                        targetLength = len,
                        targetBaseWidth = baseW,
                        targetTipWidth = tipW,
                        baseAlpha = 0.12f * BRIGHTNESS_MUL,
                        tipAlpha = 0.03f * BRIGHTNESS_MUL,
                        baseEmissive = 1.75f * BRIGHTNESS_MUL,
                        tipEmissive = 0.45f * BRIGHTNESS_MUL,
                    )
                )
            }
        }
    }

    private fun advanceGrowingCones(engine: CombatEngineAPI, now: Float, muzzle: Vector2f) {
        if (growingCones.isEmpty()) return

        val it = growingCones.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val e = c.entity

            val t0 = ((now - c.createdAt) / c.growDuration).coerceIn(0f, 1f)
            // smoothstep：头尾更柔和
            val t = (t0 * t0 * (3f - 2f * t0)).coerceIn(0f, 1f)

            val curLen = (1f + (c.targetLength - 1f) * t).coerceAtLeast(1f)
            val curBaseW = (1f + (c.targetBaseWidth - 1f) * t).coerceAtLeast(1f)
            val curTipW = (1f + (c.targetTipWidth - 1f) * t).coerceAtLeast(1f)

            // alpha/emissive 也随 grow 拉起，避免“长度变了但亮度一开始就满”
            val aBase = (c.baseAlpha * t).coerceIn(0f, 1f)
            val aTip = (c.tipAlpha * t).coerceIn(0f, 1f)
            val eBase = (c.baseEmissive * t).coerceIn(0f, 10f)
            val eTip = (c.tipEmissive * t).coerceIn(0f, 10f)

            try {
                // 更新节点（本地空间）：0..length
                val nodes = e.nodes
                if (nodes == null || nodes.size < 2) {
                    e.resetNodes()
                    e.addNode(Vector2f(0f, 0f))
                    e.addNode(Vector2f(curLen, 0f))
                    e.submitNodes()
                } else {
                    nodes[0].x = 0f
                    nodes[0].y = 0f
                    nodes[1].x = curLen
                    nodes[1].y = 0f
                    e.setNodeRefreshIndex(0)
                    e.setNodeRefreshAllFromCurrentIndex()
                    e.submitNodes()
                }

                e.setStartWidth(curBaseW)
                e.setEndWidth(curTipW)

                // 这里使用“分别控制 base/tip 的 alpha/emissive”
                // 若底层实现只读 start/end，则相当于近似，但也足够好看。
                e.setStartColorAlpha(aBase)
                e.setEndColorAlpha(aTip)
                e.setStartEmissiveAlpha(eBase)
                e.setEndEmissiveAlpha(eTip)

                // 跟随炮口位置（舰体移动时不脱节），角度保持创建时的随机角。
                e.setStateVanilla(muzzle, c.facing)
            } catch (_: Throwable) {
                // 若 entity 已失效/被删除，直接移除，避免持续异常
                it.remove()
            }

            // grow 完成后不移除：交给 globalTimer 自然淡出
        }
    }

    private fun spawnDecorativeMicroBeams(engine: CombatEngineAPI, now: Float, line: BeamLineUtil.BeamLine, level: Float) {
        val len = line.length
        if (len < 80f) return

        val s = scale.coerceIn(0.35f, 2.25f)
        val wMul = beamWidthMul.coerceIn(0.35f, 1.25f)

        val segLen = (len * lerp(0.18f, 0.30f, level)).coerceIn(55f, 220f)
        val amp = lerp(4f, 10f, level) * s
        val thinCoreW = lerp(2.2f, 3.6f, level) * s * wMul
        val thinGlowW = thinCoreW * 1.9f

        // 3 段沿束方向循环滚动；位置随时间平滑移动（非随机跳动）
        for (i in 0 until 3) {
            val u0 = ((now * 0.78f) + i * 0.33f) % 1f
            val startDist = (u0 * (len - segLen)).coerceIn(0f, (len - segLen).coerceAtLeast(0f))

            val baseFrom = Vector2f(
                line.from.x + line.dirUnit.x * startDist,
                line.from.y + line.dirUnit.y * startDist,
            )
            val baseTo = Vector2f(
                baseFrom.x + line.dirUnit.x * segLen,
                baseFrom.y + line.dirUnit.y * segLen,
            )

            // 两侧对称细束（围绕主束）
            val phase = (now * 6.0f) + i * 1.7f
            val off = (sin(phase.toDouble()).toFloat() * amp).coerceIn(-amp, amp)
            for (sign in floatArrayOf(-1f, 1f)) {
                val dx = line.perpUnit.x * off * sign
                val dy = line.perpUnit.y * off * sign

                val f = Vector2f(baseFrom.x + dx, baseFrom.y + dy)
                val t = Vector2f(baseTo.x + dx, baseTo.y + dy)

                TaperedBeamTrailsVfx.spawn(
                    engine = engine,
                    from = f,
                    to = t,
                    coreBaseWidth = thinCoreW,
                    coreTipWidth = thinCoreW * TIP_WIDTH_MUL,
                    glowBaseWidth = thinGlowW,
                    glowTipWidth = thinGlowW * TIP_WIDTH_MUL,
                    params = TaperedBeamTrailsVfx.BeamParams(
                        fadeIn = 0.01f,
                        full = 0.03f,
                        fadeOut = 0.06f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                        core = TaperedBeamTrailsVfx.LayerParams(
                            coreColor = HOT_COLOR,
                            fringeColor = CORE_COLOR,
                            baseAlphaMul = 0.08f * BRIGHTNESS_MUL,
                            tipAlphaMul = 0.08f * BRIGHTNESS_MUL,
                            baseEmissiveAlphaMul = 1.35f * BRIGHTNESS_MUL,
                            tipEmissiveAlphaMul = 1.35f * BRIGHTNESS_MUL,
                            mixPower = 3.1f,
                            mirroredUMul = 0f,
                        ),
                        glow = TaperedBeamTrailsVfx.LayerParams(
                            coreColor = CORE_COLOR,
                            fringeColor = GLOW_COLOR,
                            baseAlphaMul = 0.03f * BRIGHTNESS_MUL,
                            tipAlphaMul = 0.03f * BRIGHTNESS_MUL,
                            baseEmissiveAlphaMul = 0.85f * BRIGHTNESS_MUL,
                            tipEmissiveAlphaMul = 0.85f * BRIGHTNESS_MUL,
                            mixPower = 3.4f,
                            mirroredUMul = 0f,
                        ),
                    )
                )
            }
        }
    }

    private fun upsertPersistentBeam(engine: CombatEngineAPI, line: BeamLineUtil.BeamLine, level: Float, coreW: Float, glowW: Float, fade: Float) {
        val coreSprite = try {
            com.fs.starfarer.api.Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
        } catch (_: Throwable) {
            return
        }
        val fringeSprite = try {
            com.fs.starfarer.api.Global.getSettings().getSprite("graphics/fx/beamfringeb.png")
        } catch (_: Throwable) {
            return
        }

        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (_: Throwable) {
        }

        val beamLayer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER

        fun createLayer(
            baseW: Float,
            tipW: Float,
            core: Color,
            fringe: Color,
            baseAlpha: Float,
            tipAlpha: Float,
            baseEm: Float,
            tipEm: Float,
            mix: Float,
            mirrored: Boolean,
        ): TrailEntity? {
            val e = try {
                if (!mirrored) {
                    BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                        engine = engine,
                        location = line.from,
                        facing = line.facing,
                        length = line.length,
                        baseWidth = baseW,
                        tipWidth = tipW,
                        coreColor = core,
                        fringeColor = fringe,
                        coreSprite = coreSprite,
                        fringeSprite = fringeSprite,
                        layer = beamLayer,
                        full = 9999f,
                        baseAlphaMul = baseAlpha,
                        tipAlphaMul = tipAlpha,
                        baseEmissiveAlphaMul = baseEm,
                        tipEmissiveAlphaMul = tipEm,
                        mixPower = mix,
                    )
                } else {
                    BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
                        engine = engine,
                        location = line.from,
                        facing = line.facing,
                        length = line.length,
                        baseWidth = baseW,
                        tipWidth = tipW,
                        coreColor = core,
                        fringeColor = fringe,
                        coreSprite = coreSprite,
                        fringeSprite = fringeSprite,
                        layer = beamLayer,
                        full = 9999f,
                        baseAlphaMul = baseAlpha,
                        tipAlphaMul = tipAlpha,
                        baseEmissiveAlphaMul = baseEm,
                        tipEmissiveAlphaMul = tipEm,
                        mixPower = mix,
                    )
                }
            } catch (_: Throwable) {
                null
            }
            return e
        }

        val alphaMul = fade.coerceIn(0f, 1f)
        val widthMul = alphaMul

        val baseAlphaCore = 0.62f * BRIGHTNESS_MUL * alphaMul
        val baseEmCore = 2.85f * BRIGHTNESS_MUL * WHITE_CORE_MUL * alphaMul
        val baseAlphaGlow = 0.18f * BRIGHTNESS_MUL * alphaMul
        val baseEmGlow = 1.95f * BRIGHTNESS_MUL * alphaMul

        val baseWCore = coreW * widthMul
        val tipWCore = coreW * TIP_WIDTH_MUL * widthMul
        val baseWGlow = glowW * widthMul
        val tipWGlow = glowW * TIP_WIDTH_MUL * widthMul

        val p = persistentBeam ?: run {
            val coreMain = createLayer(
                baseWCore,
                tipWCore,
                BEAM_CORE_WHITE,
                BEAM_FRINGE_PINK,
                baseAlphaCore,
                baseAlphaCore,
                baseEmCore,
                baseEmCore,
                3.25f,
                mirrored = false
            )
            val coreMirror = createLayer(
                baseWCore,
                tipWCore,
                BEAM_CORE_WHITE,
                BEAM_FRINGE_PINK,
                baseAlphaCore * 0.62f,
                baseAlphaCore * 0.62f,
                baseEmCore * 0.62f,
                baseEmCore * 0.62f,
                3.25f,
                mirrored = true
            )
            val glowMain =
                createLayer(baseWGlow, tipWGlow, GLOW_COLOR, HOT_COLOR, baseAlphaGlow, baseAlphaGlow, baseEmGlow, baseEmGlow, 3.60f, mirrored = false)
            val glowMirror = createLayer(
                baseWGlow,
                tipWGlow,
                GLOW_COLOR,
                HOT_COLOR,
                baseAlphaGlow * 0.62f,
                baseAlphaGlow * 0.62f,
                baseEmGlow * 0.62f,
                baseEmGlow * 0.62f,
                3.60f,
                mirrored = true
            )
            PersistentBeam(coreMain, coreMirror, glowMain, glowMirror).also { persistentBeam = it }
        }

        fun updateBeamEntity(
            e: TrailEntity?,
            mirrored: Boolean,
            baseW: Float,
            tipW: Float,
            alpha: Float,
            emissive: Float,
        ) {
            if (e == null) return

            // 更新节点（本地空间）：0..length
            val nodes = try {
                e.nodes
            } catch (_: Throwable) {
                null
            }
            if (nodes == null || nodes.size < 2) {
                try {
                    e.resetNodes()
                } catch (_: Throwable) {
                }
                try {
                    if (!mirrored) {
                        e.addNode(Vector2f(0f, 0f))
                        e.addNode(Vector2f(line.length, 0f))
                    } else {
                        e.addNode(Vector2f(line.length, 0f))
                        e.addNode(Vector2f(0f, 0f))
                    }
                    e.submitNodes()
                } catch (_: Throwable) {
                }
            } else {
                try {
                    if (!mirrored) {
                        nodes[0].x = 0f
                        nodes[0].y = 0f
                        nodes[1].x = line.length
                        nodes[1].y = 0f
                    } else {
                        nodes[0].x = line.length
                        nodes[0].y = 0f
                        nodes[1].x = 0f
                        nodes[1].y = 0f
                    }
                    e.setNodeRefreshIndex(0)
                    e.setNodeRefreshAllFromCurrentIndex()
                    e.submitNodes()
                } catch (_: Throwable) {
                }
            }

            // 更新外观：宽度、alpha/emissive 等允许轻微随强度变化
            try {
                // 让消失阶段呈现“变细 + 淡出”
                // mirrored：node0=tip / node1=base，因此宽度也需要反转。
                if (!mirrored) {
                    e.setStartWidth(baseW)
                    e.setEndWidth(tipW)
                } else {
                    e.setStartWidth(tipW)
                    e.setEndWidth(baseW)
                }

                e.setStartColorAlpha(alpha.coerceIn(0f, 1f))
                e.setEndColorAlpha(alpha.coerceIn(0f, 1f))
                e.setStartEmissiveAlpha(emissive.coerceIn(0f, 10f))
                e.setEndEmissiveAlpha(emissive.coerceIn(0f, 10f))

                e.setStateVanilla(line.from, line.facing)
            } catch (_: Throwable) {
            }
        }

        updateBeamEntity(p.coreMain, mirrored = false, baseW = baseWCore, tipW = tipWCore, alpha = baseAlphaCore, emissive = baseEmCore)
        updateBeamEntity(
            p.coreMirror,
            mirrored = true,
            baseW = baseWCore,
            tipW = tipWCore,
            alpha = baseAlphaCore * 0.62f,
            emissive = baseEmCore * 0.62f
        )
        updateBeamEntity(p.glowMain, mirrored = false, baseW = baseWGlow, tipW = tipWGlow, alpha = baseAlphaGlow, emissive = baseEmGlow)
        updateBeamEntity(
            p.glowMirror,
            mirrored = true,
            baseW = baseWGlow,
            tipW = tipWGlow,
            alpha = baseAlphaGlow * 0.62f,
            emissive = baseEmGlow * 0.62f
        )
    }

    private fun emitAmbientBeamNebula(
        engine: CombatEngineAPI,
        amount: Float,
        line: BeamLineUtil.BeamLine,
        level: Float,
        fade: Float,
        coreWidth: Float,
        glowWidth: Float,
    ) {
        val s = level.coerceIn(0f, 1f)
        val f = fade.coerceIn(0f, 1f)
        if (f <= 0.001f) return

        val length = line.length.coerceAtLeast(0f)
        if (length < 80f) return

        // 复用 StellarJetBeamVfx 的“密度随束长线性增长”的策略，并按需求把数量翻倍。
        val particleRatePerSec = (length / 50f) * AMBIENT_PARTICLES_PER_50SU_PER_SEC * (0.65f + 0.85f * s)
        val ratePerSec = (particleRatePerSec * AMBIENT_NEBULA_RATE_MUL * AMBIENT_NEBULA_COUNT_MUL * (0.35f + 0.65f * f)).coerceAtLeast(0f)

        ambientNebulaAcc += ratePerSec * amount
        val count = ambientNebulaAcc.toInt().coerceAtMost(AMBIENT_NEBULA_MAX_PER_FRAME)
        if (count > 0) ambientNebulaAcc -= count
        if (count <= 0) return

        // 侧向散发：与 StellarJet 相同，主要向两侧扩散，不沿束方向给速度。
        val dir = line.dirUnit
        val perp = line.perpUnit
        val baseSpread = ((glowWidth * 0.5f * AMBIENT_NEBULA_LATERAL_MUL) + (coreWidth * 0.25f)).coerceAtLeast(10f)

        for (i in 0 until count) {
            // 略偏向束前半段：让观感更像“沿束散发/蒸发”
            val t = if (Math.random() < 0.65) MathUtils.getRandomNumberInRange(0f, 1f).pow(0.55f) else MathUtils.getRandomNumberInRange(0f, 1f)
            val along = length * t
            val lateral = (MathUtils.getRandomNumberInRange(0f, 1f) - 0.5f) * 2f * baseSpread * (0.75f + 0.45f * s)
            val at = Vector2f(
                line.from.x + dir.x * along + perp.x * lateral,
                line.from.y + dir.y * along + perp.y * lateral,
            )

            // 两侧交替，减少“只在一边”的随机偏差
            val idx = ambientNebulaIndex++
            val sign = if ((idx and 1) == 0) 1f else -1f
            val speed = lerp(AMBIENT_NEBULA_SPEED_MIN, AMBIENT_NEBULA_SPEED_MAX, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.65f + 0.45f * s)
            val vel = Vector2f(
                perp.x * speed * AMBIENT_NEBULA_SIDE_SPEED_MUL * sign,
                perp.y * speed * AMBIENT_NEBULA_SIDE_SPEED_MUL * sign,
            )

            val size = lerp(AMBIENT_NEBULA_SIZE_MIN, AMBIENT_NEBULA_SIZE_MAX, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.85f + 0.35f * s)
            val dur = lerp(AMBIENT_NEBULA_DUR_MIN, AMBIENT_NEBULA_DUR_MAX, MathUtils.getRandomNumberInRange(0f, 1f))
            val opacity = lerp(AMBIENT_NEBULA_OPACITY_MIN, AMBIENT_NEBULA_OPACITY_MAX, MathUtils.getRandomNumberInRange(0f, 1f)) * (0.85f + 0.25f * s) * f

            val endSizeMult = lerp(AMBIENT_NEBULA_END_SIZE_MUL_MIN, AMBIENT_NEBULA_END_SIZE_MUL_MAX, MathUtils.getRandomNumberInRange(0f, 1f))
            val inDur = (dur * AMBIENT_NEBULA_IN_FRAC).coerceAtLeast(0.01f)
            val fullDur = (dur * AMBIENT_NEBULA_FULL_FRAC).coerceAtLeast(0.01f)
            val outDur = (dur * AMBIENT_NEBULA_OUT_FRAC).coerceAtLeast(0.01f)

            val base = AMBIENT_NEBULA_BASE_COLOR
            val alpha = (AMBIENT_NEBULA_COLOR_ALPHA.toFloat() * opacity).toInt().coerceIn(0, 255)
            val c = Color(base.red, base.green, base.blue, alpha)

            try {
                engine.addNebulaSmokeParticle(at, vel, size, endSizeMult, inDur, fullDur, outDur, c)
            } catch (_: Throwable) {
            }
        }
    }
}
