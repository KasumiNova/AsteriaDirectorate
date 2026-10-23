package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.impl.render.StrikeSprayComponent.Companion.RAMP_MIN_RAYS
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLease
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseEnvelope
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.TrailLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.roundToInt

class StrikeSprayComponent(
    id: String,
    private val spec: StrikeSprayVfx.StrikeSpraySpec,
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(StrikeSprayComponent::class.java)

    internal val needles = ArrayList<Needle>()

    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var spritesAttempted = false
    private var driveWarned = false

    init {
        buildNeedles()
    }

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        for (needle in needles) {
            if (needle.activationDelay <= 0f) activateNeedle(engine, needle)
        }
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val dt = amount.coerceAtLeast(0f)
        for (needle in needles) {
            if (!needle.activated) {
                needle.elapsed += dt
                if (needle.elapsed < needle.activationDelay) continue
                activateNeedle(engine, needle)
            }
            if (needle.expired) continue
            needle.driveAge += dt
            if (needle.driveAge >= needle.full + needle.fadeOut) {
                needle.expired = true
                continue
            }
            // vel ∥ facing 由构造保证，无侧向分量
            needle.pos.x += needle.vel.x * dt
            needle.pos.y += needle.vel.y * dt
            val entity = needle.entity ?: continue
            if (entity.hasDelete()) {
                needle.entity = null
                needle.lease = null
                continue
            }
            try {
                entity.setStateVanilla(needle.pos, needle.facing)
            } catch (t: Throwable) {
                if (!driveWarned) {
                    driveWarned = true
                    log.warn("刺束针状态推送异常（id=$id），本组件后续推送静默（组件级去重）", t)
                }
            }
        }
    }

    override fun onDetachSelf() {
        for (needle in needles) {
            // release() 为泊车归还（alpha 归零、实体留池复用），禁止走 delete
            needle.lease?.release()
            needle.lease = null
            needle.entity = null
        }
    }

    private fun buildNeedles() {
        val style = spec.style
        val rays = (style.exactRays
            ?: (style.baseRaysMin + MathUtils.getRandomNumberInRange(0, style.baseRaysExtra))).coerceIn(1, RAY_COUNT_MAX)
        val baseScale = style.impactScale.coerceIn(IMPACT_SCALE_OUTER_MIN, IMPACT_SCALE_OUTER_MAX)

        val ramp = style.introRampSeconds > 0f && rays >= RAMP_MIN_RAYS
        val cohorts = if (ramp) rampCohorts(rays) else IntArray(rays)
        val interval = (style.introRampSeconds / RAMP_STEP_COUNT.toFloat()).coerceIn(RAMP_INTERVAL_MIN, RAMP_INTERVAL_MAX)

        for (i in 0 until rays) {
            val cohort = cohorts[i]
            val delay = if (ramp) interval * cohort.toFloat() else 0f
            val scale = (baseScale * rampStepScale(cohort, ramp)).coerceIn(IMPACT_SCALE_MIN, IMPACT_SCALE_MAX)

            // 归一化到 [0,360)：facing 经随机散布可能为负（如 5°−32°），BoxUtil 变换对负角会镜像反转。
            val ang = BoxUtilCombatVfx.normalizeFacingDeg(
                spec.facingDeg + MathUtils.getRandomNumberInRange(-spec.arcDeg * 0.5f, spec.arcDeg * 0.5f),
            )
            val visualLength = MathUtils.getRandomNumberInRange(style.lengthMin, style.lengthMax) * scale * EXPLICIT_LEN_MUL
            val width = MathUtils.getRandomNumberInRange(style.widthMin, style.widthMax) * scale * EXPLICIT_WIDTH_MUL
            val tipWidth = (width * TIP_WIDTH_MUL).coerceIn(TIP_WIDTH_MIN, TIP_WIDTH_MAX)
            val baseWidth = (width * BASE_WIDTH_MUL).coerceIn(BASE_WIDTH_MIN, BASE_WIDTH_MAX)

            val full = MathUtils.getRandomNumberInRange(style.fullMin, style.fullMax)
            val fadeOut = MathUtils.getRandomNumberInRange(style.fadeOutMin, style.fadeOutMax)
            val life = (full + fadeOut).coerceAtLeast(MIN_LIFE_SECONDS)

            val dir = MathUtils.getPointOnCircumference(null, 1f, ang)
            val baseInset = (visualLength * INSET_MUL).coerceIn(INSET_MIN, INSET_MAX)
            val spawnPos = Vector2f(spec.origin.x - dir.x * baseInset, spec.origin.y - dir.y * baseInset)
            val speed = MathUtils.getRandomNumberInRange(style.speedMin, style.speedMax)
            val vel = Vector2f(dir.x * speed, dir.y * speed)

            needles += Needle(
                pos = spawnPos,
                vel = vel,
                facing = ang,
                full = full,
                fadeOut = fadeOut,
                visualLength = visualLength,
                tipWidth = tipWidth,
                baseWidth = baseWidth,
                activationDelay = delay,
                tipGlowPos = Vector2f(
                    spawnPos.x + dir.x * (visualLength * TIP_GLOW_POS_MUL),
                    spawnPos.y + dir.y * (visualLength * TIP_GLOW_POS_MUL),
                ),
                tipGlowVel = Vector2f(vel.x * TIP_GLOW_VEL_MUL, vel.y * TIP_GLOW_VEL_MUL),
                tipGlowSize = (tipWidth * TIP_GLOW_SIZE_MUL).coerceIn(TIP_GLOW_SIZE_MIN, TIP_GLOW_SIZE_MAX),
                tipGlowDuration = (life * TIP_GLOW_DUR_MUL).coerceIn(TIP_GLOW_DUR_MIN, TIP_GLOW_DUR_MAX),
            )
        }
    }

    private fun activateNeedle(engine: CombatEngineAPI, needle: Needle) {
        needle.activated = true
        loadSprites() ?: return
        val lease = PooledCombatVfx.checkoutTrail(
            engine, POOL_KEY,
            TrailLeaseSpec(
                location = needle.pos,
                facingDeg = needle.facing,
                // 节点序对齐 createBeamVisual：node[0]=+length 端为尾，START_* 作用于尾
                nodes = listOf(Vector2f(needle.visualLength, 0f), Vector2f(0f, 0f)),
                startWidth = needle.tipWidth,
                endWidth = needle.baseWidth,
                coreColor = spec.coreColor,
                fringeColor = spec.fringeColor,
                startAlpha = TRAIL_TAIL_ALPHA_MUL,
                endAlpha = TRAIL_HEAD_ALPHA_MUL,
                startEmissiveAlpha = TRAIL_TAIL_EMISSIVE_MUL,
                endEmissiveAlpha = TRAIL_HEAD_EMISSIVE_MUL,
                fillStartAlpha = 0f,
                fillStartFactor = FILL_START_FACTOR,
                fillEndAlpha = 0f,
                fillEndFactor = FILL_END_FACTOR,
                envelope = TrailLeaseEnvelope(0f, needle.full, needle.fadeOut),
            ),
        )
        if (lease == null) return
        needle.lease = lease
        needle.entity = lease.entity
        engine.addSmoothParticle(
            needle.tipGlowPos,
            needle.tipGlowVel,
            needle.tipGlowSize,
            TIP_GLOW_BRIGHTNESS,
            needle.tipGlowDuration,
            spec.coreColor,
        )
    }

    private fun loadSprites(): Pair<SpriteAPI, SpriteAPI>? {
        if (!spritesAttempted) {
            spritesAttempted = true
            sprites = BeamSprites.load()
            if (sprites == null) {
                log.warn("刺束针贴图加载失败（id=$id），本组件针视觉缺席（参数积分仍推进）")
            }
        }
        return sprites
    }

    internal class Needle(
        val pos: Vector2f,
        val vel: Vector2f,
        val facing: Float,
        val full: Float,
        val fadeOut: Float,
        val visualLength: Float,
        val tipWidth: Float,
        val baseWidth: Float,
        val activationDelay: Float,
        val tipGlowPos: Vector2f,
        val tipGlowVel: Vector2f,
        val tipGlowSize: Float,
        val tipGlowDuration: Float,
        var elapsed: Float = 0f,
        var driveAge: Float = 0f,
        var activated: Boolean = false,
        var expired: Boolean = false,
        var lease: TrailLease? = null,
        var entity: TrailEntity? = null,
    )

    companion object {
        const val RAY_COUNT_MAX = 80

        const val EXPLICIT_LEN_MUL = 1.20f

        const val EXPLICIT_WIDTH_MUL = 0.70f

        const val IMPACT_SCALE_OUTER_MIN = 0.25f
        const val IMPACT_SCALE_OUTER_MAX = 2.5f
        const val IMPACT_SCALE_MIN = 0.15f
        const val IMPACT_SCALE_MAX = 2.5f

        const val TIP_WIDTH_MUL = 0.045f
        const val TIP_WIDTH_MIN = 0.40f
        const val TIP_WIDTH_MAX = 1.9f
        const val BASE_WIDTH_MUL = 0.20f
        const val BASE_WIDTH_MIN = 2.2f
        const val BASE_WIDTH_MAX = 12f

        const val INSET_MUL = 0.18f
        const val INSET_MIN = 6f
        const val INSET_MAX = 42f

        const val MIN_LIFE_SECONDS = 0.05f

        const val TRAIL_TAIL_ALPHA_MUL = 0.10f
        const val TRAIL_HEAD_ALPHA_MUL = 0.95f
        const val TRAIL_TAIL_EMISSIVE_MUL = 0.55f
        const val TRAIL_HEAD_EMISSIVE_MUL = 2.05f
        const val TRAIL_MIX_POWER = 3.0f

        internal val POOL_KEY = TrailLeaseKey(
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            coreSpritePath = BeamSprites.CORE_PATH,
            fringeSpritePath = BeamSprites.FRINGE_PATH,
            mixPower = TRAIL_MIX_POWER,
            capacity = 384,
        )

        const val FILL_START_FACTOR = 0.62f
        const val FILL_END_FACTOR = 0.92f

        const val TIP_GLOW_POS_MUL = 0.98f
        const val TIP_GLOW_VEL_MUL = 0.25f

        const val TIP_GLOW_SIZE_MUL = 3.5f
        const val TIP_GLOW_SIZE_MIN = 4f
        const val TIP_GLOW_SIZE_MAX = 18f
        const val TIP_GLOW_BRIGHTNESS = 2.2f

        const val TIP_GLOW_DUR_MUL = 0.25f
        const val TIP_GLOW_DUR_MIN = 0.06f
        const val TIP_GLOW_DUR_MAX = 0.18f

        const val RAMP_MIN_RAYS = 12

        const val RAMP_STEP_COUNT = 3

        const val RAMP_INTERVAL_MIN = 0.010f
        const val RAMP_INTERVAL_MAX = 0.060f

        const val RAMP_SCALE_BASE = 0.35f
        const val RAMP_SCALE_SPAN = 0.65f

        internal fun rampCohorts(rays: Int): IntArray {
            val weights = FloatArray(RAMP_STEP_COUNT) { i ->
                val x0 = i.toFloat() / RAMP_STEP_COUNT.toFloat()
                val x1 = (i + 1).toFloat() / RAMP_STEP_COUNT.toFloat()
                (x1 * x1 - x0 * x0).coerceAtLeast(0f)
            }
            val sumW = weights.sum().takeIf { it > 0f } ?: 1f
            val quotas = IntArray(RAMP_STEP_COUNT)
            var remaining = rays
            for (i in 0 until RAMP_STEP_COUNT) {
                val remainingSteps = RAMP_STEP_COUNT - i
                val maxAllowed = remaining - (remainingSteps - 1)
                quotas[i] = if (i == RAMP_STEP_COUNT - 1) {
                    remaining
                } else {
                    (rays * weights[i] / sumW).roundToInt().coerceIn(1, maxAllowed)
                }
                remaining -= quotas[i]
            }
            val cohorts = IntArray(rays)
            var index = 0
            for (step in 0 until RAMP_STEP_COUNT) {
                repeat(quotas[step]) { cohorts[index++] = step }
            }
            return cohorts
        }

        internal fun rampStepScale(step: Int, ramp: Boolean): Float {
            if (!ramp) return 1f
            val x = (step + 1).toFloat() / RAMP_STEP_COUNT.toFloat()
            return RAMP_SCALE_BASE + RAMP_SCALE_SPAN * smoothstep01(x)
        }

        internal fun smoothstep01(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
