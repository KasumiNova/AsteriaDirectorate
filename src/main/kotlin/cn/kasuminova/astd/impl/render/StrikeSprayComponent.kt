package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.roundToInt

/**
 * 冲击刺束喷散组件（计划 00-锥面冲击特效重做计划 §10.9 v4.1）：一簇沿喷散轴放射的细长
 * TrailEntity 光针——「多条锥状体」主力读感。吞并原 `ImpactStrikeFx.spawnImpactSpray` 职能，
 * 渲染本体与 v2.2 主路径同款（TrailEntity 双节点 beamcoreb/beamfringeb + fill 羽化 +
 * START/END 宽色渐变 + 针尖补光粒子），**参数逐值平移 v2.2 不改一个数字**。
 *
 * v4.1 相对旧实现的结构性变更（架构层，零观感改动）：
 * - **驱动模型**：EveryFrameCombatPlugin 推 loc 的两层驱动弃用（旧「概率侧飞」根因——插件推
 *   loc + setStateVanilla 与 BoxUtil 自管理状态互撞）。本组件 advanceSelf 显式积分
 *   `pos += vel×dt` 并 `setStateVanilla(pos, facing)`，vel ∥ facing 由构造保证（同一 ang
 *   派生 dir 与 vel，数学上无侧向分量），单测以侧向分量 < 1e-3 做回归断言；
 * - **兜底链退役**：旧三级退化（TrailEntity→SpriteEntity→vanilla 粒子）整体删除，只留
 *   TrailEntity 主路径；贴图/addEntity 失败记 WARN（组件级去重）缺席视觉，参数积分照常；
 * - **intensityMult 折叠**：两个真实调用方（锥面/aod7）恒传 1f，vis 派生（sizeScale、
 *   速度/内缩/烟雾系数）全部按 1 化简；随之恒不触发的高倍率尺寸封顶一并省略。
 *
 * 错峰渐现为 v2.2 三段分批逐字移植：针数 ≥ [RAMP_MIN_RAYS] 且 introRamp > 0 时按二次曲线
 * 权重 1/9 : 3/9 : 5/9 分三段，段间隔 (introRamp/3).coerceIn(0.010, 0.060)，段尺寸
 * 0.35+0.65×smoothstep((step+1)/3)（第一段立即激活）。针寿命由实体 globalTimer 自管理
 * （满亮+淡出自灭）；树 detach 时整批 delete。
 */
class StrikeSprayComponent(
    id: String,
    private val spec: StrikeSprayVfx.StrikeSpraySpec,
) : RenderEntityImpl(id) {

    private val log = Global.getLogger(StrikeSprayComponent::class.java)

    /** 喷散针表（构造期定死参数；internal 供单测断言数量域/位移方向/错峰分段/过期停驱）。 */
    internal val needles = ArrayList<Needle>()

    private var sprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var spritesAttempted = false
    private var addEntityWarned = false
    private var driveWarned = false

    init {
        buildNeedles()
    }

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        // 零延迟段立即激活（v2.2「第一段立即生成」），t=0 即有可读针簇。
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
            // 显式积分（vel ∥ facing 构造保证，无侧向分量——侧飞修复本体）。
            needle.pos.x += needle.vel.x * dt
            needle.pos.y += needle.vel.y * dt
            val entity = needle.entity ?: continue
            if (entity.hasDelete() || entity.isGlobalTimerOver) continue
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
            needle.entity?.delete()
            needle.entity = null
        }
    }

    /**
     * 构造全部喷散针参数（纯数据，不触 BoxUtil——单测环境可完整验证参数域与积分方向）。
     * 全部数值自 v2.2 `ImpactStrikeFx` 逐字平移（vis=1 折叠）：朝向（facing ± arc/2 随机）、
     * 视觉长 rand×scale×1.20、宽 rand×scale×0.70、尖端 0.045×/基部 0.70× 两段收细、
     * 基部内缩 0.18×、寿命（满亮+淡出各自随机）、速度 rand（不等比例飞行）。
     */
    private fun buildNeedles() {
        val style = spec.style
        // 针数来源：exactRays 非空优先（v4.4 数量动态化，调用方按张角推导好总数）；
        // 否则 v2.2 固定域：rays = (baseRaysMin + rand(0, extra)) × vis(=1)，clamp [1, 80]。
        val rays = (style.exactRays
            ?: (style.baseRaysMin + MathUtils.getRandomNumberInRange(0, style.baseRaysExtra))).coerceIn(1, RAY_COUNT_MAX)
        val baseScale = style.impactScale.coerceIn(IMPACT_SCALE_OUTER_MIN, IMPACT_SCALE_OUTER_MAX)

        // 错峰三段（v2.2 逐字）：段配额按二次曲线权重、段间隔、段尺寸系数。
        val ramp = style.introRampSeconds > 0f && rays >= RAMP_MIN_RAYS
        val cohorts = if (ramp) rampCohorts(rays) else IntArray(rays)
        val interval = (style.introRampSeconds / RAMP_STEP_COUNT.toFloat()).coerceIn(RAMP_INTERVAL_MIN, RAMP_INTERVAL_MAX)

        for (i in 0 until rays) {
            val cohort = cohorts[i]
            val delay = if (ramp) interval * cohort.toFloat() else 0f
            val scale = (baseScale * rampStepScale(cohort, ramp)).coerceIn(IMPACT_SCALE_MIN, IMPACT_SCALE_MAX)

            val ang = spec.facingDeg + MathUtils.getRandomNumberInRange(-spec.arcDeg * 0.5f, spec.arcDeg * 0.5f)
            // v2.2 塑形逐字：长度 +20%、单条宽度 -30%（vis=1 后 sizeScale=1 折叠）。
            val visualLength = MathUtils.getRandomNumberInRange(style.lengthMin, style.lengthMax) * scale * EXPLICIT_LEN_MUL
            val width = MathUtils.getRandomNumberInRange(style.widthMin, style.widthMax) * scale * EXPLICIT_WIDTH_MUL
            // 针形：尖端极细、基部收敛（v2.2：基部上限 12×sizeScale=12）。
            val tipWidth = (width * TIP_WIDTH_MUL).coerceIn(TIP_WIDTH_MIN, TIP_WIDTH_MAX)
            val baseWidth = (width * BASE_WIDTH_MUL).coerceIn(BASE_WIDTH_MIN, BASE_WIDTH_MAX)

            val full = MathUtils.getRandomNumberInRange(style.fullMin, style.fullMax)
            val fadeOut = MathUtils.getRandomNumberInRange(style.fadeOutMin, style.fadeOutMax)
            val life = (full + fadeOut).coerceAtLeast(MIN_LIFE_SECONDS)

            // 基部内缩：把钝端帽藏进爆点/烟雾里（v2.2：×(0.90+0.10×vis)=×1 折叠）；
            // vel 与 facing 同 ang 派生（侧飞修复构造保证；v2.2 速度系数 0.85+0.15×vis=1 折叠）。
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

    /** 激活一根针：建 TrailEntity + 针尖补光粒子（贴图/addEntity 失败记 WARN 缺席视觉，参数积分照常）。 */
    private fun activateNeedle(engine: CombatEngineAPI, needle: Needle) {
        needle.activated = true
        val pair = loadSprites() ?: return
        val entity = BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
            engine = engine,
            location = needle.pos,
            facing = needle.facing,
            length = needle.visualLength,
            tailWidth = needle.tipWidth,
            headWidth = needle.baseWidth,
            coreColor = spec.coreColor,
            fringeColor = spec.fringeColor,
            coreSprite = pair.first,
            fringeSprite = pair.second,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = needle.full,
            // 尖端更淡、基部更亮，强化「针尖」观感（v2.2 针参数逐字）。
            tailAlphaMul = TRAIL_TAIL_ALPHA_MUL,
            headAlphaMul = TRAIL_HEAD_ALPHA_MUL,
            tailEmissiveAlphaMul = TRAIL_TAIL_EMISSIVE_MUL,
            headEmissiveAlphaMul = TRAIL_HEAD_EMISSIVE_MUL,
            mixPower = TRAIL_MIX_POWER,
        )
        if (entity == null) {
            if (!addEntityWarned) {
                addEntityWarned = true
                log.warn("刺束针 TrailEntity 注册失败（id=$id），本组件针视觉缺席（参数积分仍推进，组件级去重）")
            }
            return
        }
        // createBeamVisual 默认 fadeOut=0：手动补淡出 + 端点羽化（v2.2 针参数逐字）。
        entity.setGlobalTimer(0f, needle.full, needle.fadeOut)
        entity.setFillStartAlpha(0f)
        entity.setFillStartFactor(FILL_START_FACTOR)
        entity.setFillEndAlpha(0f)
        entity.setFillEndFactor(FILL_END_FACTOR)
        needle.entity = entity
        // 针尖补光（v2.2 主路径同款：尖端小亮点强化「尖」，随针同速 1/4 漂移）。
        engine.addSmoothParticle(
            needle.tipGlowPos,
            needle.tipGlowVel,
            needle.tipGlowSize,
            TIP_GLOW_BRIGHTNESS,
            needle.tipGlowDuration,
            spec.coreColor,
        )
    }

    /** 贴图对（core/fringe）懒加载：失败记 WARN 一次，本组件针视觉缺席。 */
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

    /** 一根喷散针：世界系位置/速度（vel ∥ facing）、寿命、针形尺寸、激活错峰、针尖补光参数与后端实体句柄。 */
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
        var entity: TrailEntity? = null,
    )

    companion object {
        // ---- 针数与内部塑形（v2.2 `ImpactStrikeFx` 逐字平移；vis=1 已折叠）----

        /** 针数硬上限（旧 clamp 80）。 */
        const val RAY_COUNT_MAX = 80

        /** 长度内部放大（旧「长度 +20%」）。 */
        const val EXPLICIT_LEN_MUL = 1.20f

        /** 宽度内部收细（旧「单条宽度 -30%」）。 */
        const val EXPLICIT_WIDTH_MUL = 0.70f

        /** impactScale 钳制域（外层 baseScale / 内层含段系数两步，v2.2 逐字）。 */
        const val IMPACT_SCALE_OUTER_MIN = 0.25f
        const val IMPACT_SCALE_OUTER_MAX = 2.5f
        const val IMPACT_SCALE_MIN = 0.15f
        const val IMPACT_SCALE_MAX = 2.5f

        /** 针形塑形：尖端极细、基部收敛（旧针参数逐字）。 */
        const val TIP_WIDTH_MUL = 0.045f
        const val TIP_WIDTH_MIN = 0.40f
        const val TIP_WIDTH_MAX = 1.9f
        const val BASE_WIDTH_MUL = 0.70f
        const val BASE_WIDTH_MIN = 2.2f
        const val BASE_WIDTH_MAX = 12f

        /** 基部内缩 = 视觉长 × 本值，clamp [6, 42]：把钝端帽藏进爆点。 */
        const val INSET_MUL = 0.18f
        const val INSET_MIN = 6f
        const val INSET_MAX = 42f

        /** 针寿命下限（旧 coerceAtLeast(0.05)，仅针尖补光时长派生用）。 */
        const val MIN_LIFE_SECONDS = 0.05f

        // ---- 拖尾观感（v2.2 主路径针参数逐字）----

        const val TRAIL_TAIL_ALPHA_MUL = 0.10f
        const val TRAIL_HEAD_ALPHA_MUL = 0.95f
        const val TRAIL_TAIL_EMISSIVE_MUL = 0.55f
        const val TRAIL_HEAD_EMISSIVE_MUL = 2.05f
        const val TRAIL_MIX_POWER = 3.0f

        /** 端点羽化（fill 因子：沿 U 向两端渐隐，治钝头钝尾）。 */
        const val FILL_START_FACTOR = 0.62f
        const val FILL_END_FACTOR = 0.92f

        // ---- 针尖补光（v2.2 主路径同款：尖端小亮点）----

        /** 补光位置 = 生成点沿向 0.98×视觉长；漂移速度 = 针速 ×0.25。 */
        const val TIP_GLOW_POS_MUL = 0.98f
        const val TIP_GLOW_VEL_MUL = 0.25f

        /** 补光尺寸 = 尖宽 ×3.5 clamp [4, 18]（v2.2：18×vis=18 折叠）；亮度 2.2。 */
        const val TIP_GLOW_SIZE_MUL = 3.5f
        const val TIP_GLOW_SIZE_MIN = 4f
        const val TIP_GLOW_SIZE_MAX = 18f
        const val TIP_GLOW_BRIGHTNESS = 2.2f

        /** 补光时长 = 针寿命 ×0.25 clamp [0.06, 0.18]。 */
        const val TIP_GLOW_DUR_MUL = 0.25f
        const val TIP_GLOW_DUR_MIN = 0.06f
        const val TIP_GLOW_DUR_MAX = 0.18f

        // ---- 错峰渐现（v2.2 三段分批逐字）----

        /** 针数达到本值且 introRamp > 0 时启用错峰渐现（旧 doRamp 阈值）。 */
        const val RAMP_MIN_RAYS = 12

        /** 分批段数（旧 stepCount=3：二次曲线权重 1/9 : 3/9 : 5/9，后段更多）。 */
        const val RAMP_STEP_COUNT = 3

        /** 段间隔 = introRamp/段数 clamp 本域（旧 interval 逐字）。 */
        const val RAMP_INTERVAL_MIN = 0.010f
        const val RAMP_INTERVAL_MAX = 0.060f

        /** 段尺寸系数 = 基数 + 跨度 × smoothstep((step+1)/段数)（旧 stepScale 逐字）。 */
        const val RAMP_SCALE_BASE = 0.35f
        const val RAMP_SCALE_SPAN = 0.65f

        /**
         * 错峰段配额（纯函数，v2.2 逐字）：二次曲线权重（后段更多）归一化后按段取整，
         * 非末段 clamp [1, 剩余 − 后续段数] 保证每段至少一根、末段拿余量。
         */
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

        /** 段尺寸系数（纯函数，v2.2 stepScale 逐字）：0.35 + 0.65 × smoothstep((step+1)/段数)。 */
        internal fun rampStepScale(step: Int, ramp: Boolean): Float {
            if (!ramp) return 1f
            val x = (step + 1).toFloat() / RAMP_STEP_COUNT.toFloat()
            return RAMP_SCALE_BASE + RAMP_SCALE_SPAN * smoothstep01(x)
        }

        /** 错峰尺寸渐变的 smoothstep（纯函数）。 */
        internal fun smoothstep01(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
