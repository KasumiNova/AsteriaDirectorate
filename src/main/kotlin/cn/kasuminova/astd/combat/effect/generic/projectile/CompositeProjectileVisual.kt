package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.define.BoxEnum
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.EnumSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一些额外的 [ProjectileVisual] 实现：
 * - 组合/聚合多个 visual（同一弹体一次 track）
 * - BoxUtil DistortionEntity 的“跟随扭曲”封装
 */

internal class CompositeProjectileVisual(
    visuals: List<ProjectileVisual?>,
) : ProjectileVisual {

    private val visuals: List<ProjectileVisual> = visuals.filterNotNull()

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        for (v in visuals) {
            v.advance(projectile, amount)
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        for (v in visuals) {
            v.beginFadeOut(reason, fadeOutSeconds)
        }
    }

    override fun isFadeOutOver(): Boolean {
        return visuals.all { it.isFadeOutOver() }
    }

    override fun delete() {
        for (v in visuals) {
            v.delete()
        }
    }
}

/**
 * 包装一个 [ProjectileVisual]，以便按 [ProjectileTracerManager.FadeReason] 覆盖淡出时长。
 *
 * 用途：某些视觉（例如 BoxUtil 的拖尾）希望在弹体进入 isFading 时不立刻淡出，
 * 而是在弹体真正移除后再用更长的淡出（参考 AOD-7 的拖尾消失策略）。
 */
internal class FadeDurationOverrideProjectileVisual(
    private val delegate: ProjectileVisual,
    private val fadeOutOnProjectileFadingSeconds: Float? = null,
    private val fadeOutOnProjectileRemovedSeconds: Float? = null,
    private val ignoreProjectileFading: Boolean = false,
) : ProjectileVisual {

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        delegate.advance(projectile, amount)
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (ignoreProjectileFading && reason == ProjectileTracerManager.FadeReason.PROJECTILE_FADING) {
            return
        }

        val t = when (reason) {
            ProjectileTracerManager.FadeReason.PROJECTILE_FADING -> fadeOutOnProjectileFadingSeconds
            ProjectileTracerManager.FadeReason.PROJECTILE_REMOVED -> fadeOutOnProjectileRemovedSeconds
        } ?: fadeOutSeconds

        delegate.beginFadeOut(reason, t)
    }

    override fun isFadeOutOver(): Boolean {
        return delegate.isFadeOutOver()
    }

    override fun delete() {
        delegate.delete()
    }
}

internal object BoxUtilProjectileDistortion {

    /**
     * 扭曲样式参数。
     *
     * 注意：BoxUtil 的 [DistortionEntity] 使用的是“半尺寸”（widthHalf/heightHalf）。
     */
    data class Style(
        val sizeInHalf: Float,
        val sizeFullHalf: Float,
        val sizeOutHalf: Float,
        val powerIn: Float,
        val powerFull: Float,
        val powerOut: Float,
        val innerFullRatio: Float,
        val innerHardness: Float,
        val ringHardness: Float = 0.65f,
        val fadeInSeconds: Float = 0.06f,
        /**
         * fullSeconds 设为很大，依赖 [ProjectileVisual.beginFadeOut] 来触发真正的淡出/回收。
         */
        val fullSeconds: Float = 9999f,
    )

    fun create(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, style: Style): ProjectileVisual? {
        try {
            // BoxUtil 依赖可能在战斗开始时尚未完成 initLater/CRM 注入；这里确保一次。
            BoxUtilCombatVfx.ensureReady(engine)

            val entity = DistortionEntity()

            // 生命周期：先快速淡入，再保持；真正淡出由 beginFadeOut() 触发。
            entity.setGlobalTimer(style.fadeInSeconds, style.fullSeconds, 0f)

            // 形状：尽量做成“奇点透镜”——中心较硬，外围较柔。
            entity.setInnerFull(style.innerFullRatio, style.innerFullRatio)
            entity.setInnerHardness(style.innerHardness)
            entity.setRingHardness(style.ringHardness)

            // 三段尺寸：略“收缩”进入 full，再在淡出时进一步收缩。
            entity.setSizeIn(style.sizeInHalf, style.sizeInHalf)
            entity.setSizeFull(style.sizeFullHalf, style.sizeFullHalf)
            entity.setSizeOut(style.sizeOutHalf, style.sizeOutHalf)

            // 强度：淡入/满值/淡出。
            entity.setPowerIn(style.powerIn)
            entity.setPowerFull(style.powerFull)
            entity.setPowerOut(style.powerOut)

            // 初始位置
            entity.setLocation(projectile.location)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, entity)
            if (state != 0) {
                entity.delete()
                return null
            }

            return DistortionProjectileVisual(entity)
        } catch (_: Throwable) {
            return null
        }
    }

    private class DistortionProjectileVisual(
        private val entity: DistortionEntity,
    ) : ProjectileVisual {

        private var fadeStarted = false

        override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
            if (entity.hasDelete()) return
            entity.setLocation(projectile.location)
        }

        override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
            if (fadeStarted || entity.hasDelete()) return
            fadeStarted = true

            val t = fadeOutSeconds.coerceAtLeast(0.01f)
            // 与 BoxUtil 的渲染移除逻辑兼容：timer 结束后实体会被 delete+从队列移除。
            entity.setGlobalTimer(0f, 0f, t)
        }

        override fun isFadeOutOver(): Boolean {
            // BoxUtil 在 timer invalid 时会 delete，之后 hasDelete=true。
            return fadeStarted && entity.hasDelete()
        }

        override fun delete() {
            if (!entity.hasDelete()) entity.delete()
        }
    }
}

internal class ParticleCoreGlowProjectileVisual(
    private val engine: CombatEngineAPI,
    private val color: Color,
    private val particlesPerSecond: Float,
    private val jitterRadius: Float,
    private val sizeMin: Float,
    private val sizeMax: Float,
    private val brightnessMin: Float,
    private val brightnessMax: Float,
    private val durationMin: Float,
    private val durationMax: Float,
    private val inheritVelocityMul: Float = 0.08f,
) : ProjectileVisual {

    private var fadeStarted = false
    private var fadeOutSeconds = 0.12f
    private var fadeTimer = 0f
    private var acc = 0f

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        val rate = particlesPerSecond.coerceAtLeast(0f)
        if (rate <= 0f) return

        // 淡出阶段：只靠计时结束即可；不再额外刷粒子，避免“淡出越来越亮”。
        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        acc += rate * amount
        val n = acc.toInt()
        if (n <= 0) return
        acc -= n

        val baseVel = projectile.velocity?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
        baseVel.scale(inheritVelocityMul.coerceAtLeast(0f))

        for (i in 0 until n) {
            val loc = MathUtils.getRandomPointInCircle(projectile.location, jitterRadius)
            val size = MathUtils.getRandomNumberInRange(sizeMin, sizeMax)
            val brightness = MathUtils.getRandomNumberInRange(brightnessMin, brightnessMax)
            val duration = MathUtils.getRandomNumberInRange(durationMin, durationMax)

            engine.addSmoothParticle(loc, baseVel, size, brightness, duration, color)
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        this.fadeTimer = 0f
        // 清空累积，避免淡出首帧补喷
        acc = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 粒子是引擎托管的，无需显式 delete。
    }
}

/**
 * 发光弹体（稳定存在 + 随机脉动）：
 * - 始终跟随弹体中心；
 * - 大小在飞行期间缓慢随机变大/变小；
 * - 使用短寿命高亮粒子“每帧覆盖”，形成连续的发光球体观感。
 */
internal class PulsingGlowProjectileVisual(
    private val engine: CombatEngineAPI,
    private val color: Color,
    private val particlesPerSecond: Float = 90f,
    private val baseSizeMin: Float = 28f,
    private val baseSizeMax: Float = 44f,
    private val brightnessMin: Float = 2.4f,
    private val brightnessMax: Float = 4.6f,
    private val durationMin: Float = 0.06f,
    private val durationMax: Float = 0.11f,
    private val inheritVelocityMul: Float = 0.05f,
    private val pulseMinScale: Float = 0.75f,
    private val pulseMaxScale: Float = 1.35f,
    private val pulseRetargetMinSeconds: Float = 0.06f,
    private val pulseRetargetMaxSeconds: Float = 0.14f,
    /** 追随目标脉动倍率的速度（越大越“弹跳”）。 */
    private val pulseLerpSpeed: Float = 10.5f,
    /** 允许一点点中心抖动，让发光更“活”。设为 0 可完全贴中心。 */
    private val jitterRadius: Float = 0.8f,
) : ProjectileVisual {

    private var fadeStarted = false
    private var fadeOutSeconds = 0.14f
    private var fadeTimer = 0f
    private var acc = 0f

    private var pulseTimer = 0f
    private var pulseRetarget = MathUtils.getRandomNumberInRange(pulseRetargetMinSeconds, pulseRetargetMaxSeconds)
    private var targetScale = MathUtils.getRandomNumberInRange(pulseMinScale, pulseMaxScale)
    private var currentScale = targetScale

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        // 更新脉动倍率（缓慢随机 + 平滑跟随）
        pulseTimer += amount
        if (pulseTimer >= pulseRetarget) {
            pulseTimer = 0f
            pulseRetarget = MathUtils.getRandomNumberInRange(pulseRetargetMinSeconds, pulseRetargetMaxSeconds)
            targetScale = MathUtils.getRandomNumberInRange(pulseMinScale, pulseMaxScale)
        }
        val lerp = (pulseLerpSpeed * amount).coerceIn(0f, 1f)
        currentScale = currentScale + (targetScale - currentScale) * lerp

        val rate = particlesPerSecond.coerceAtLeast(0f)
        if (rate <= 0f) return

        acc += rate * amount
        var n = acc.toInt()
        if (n <= 0) return
        // 限制单帧最大喷发，避免低帧率时突然爆亮
        n = n.coerceIn(1, 24)
        acc -= n

        val baseVel = projectile.velocity?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
        baseVel.scale(inheritVelocityMul.coerceAtLeast(0f))

        // 叠两层：外层更大更淡，内层更小更亮，让它像“发光弹体”而不是一团烟
        for (i in 0 until n) {
            val loc = if (jitterRadius <= 0.01f) projectile.location else MathUtils.getRandomPointInCircle(projectile.location, jitterRadius)

            val sizeOuter = MathUtils.getRandomNumberInRange(baseSizeMin, baseSizeMax) * currentScale
            val durOuter = MathUtils.getRandomNumberInRange(durationMin, durationMax)
            val brightOuter = MathUtils.getRandomNumberInRange(brightnessMin, brightnessMax)

            engine.addSmoothParticle(loc, baseVel, sizeOuter, brightOuter, durOuter, color)

            val sizeInner = (sizeOuter * 0.55f).coerceAtLeast(6f)
            val durInner = (durOuter * 0.70f).coerceAtLeast(0.03f)
            val brightInner = (brightOuter * 1.35f).coerceAtLeast(1f)

            engine.addSmoothParticle(loc, baseVel, sizeInner, brightInner, durInner, color)
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
        acc = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 粒子由引擎托管，无需显式 delete。
    }
}

/**
 * “辐射状”弹体发光：不是一团雾化的 glow，而是持续闪烁的短促放射光刺（更像“辐射/剪切”）。
 *
 * 实现策略：
 * - 优先用 BoxUtil 的 taper beam（TrailEntity）做短寿命光刺；
 * - BoxUtil 不可用时回退到原版 hit 粒子（同样沿随机方向发射）。
 */
internal class RadiatingSpokesProjectileVisual(
    private val engine: CombatEngineAPI,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val spokesPerSecond: Float = 70f,
    private val lengthMin: Float = 18f,
    private val lengthMax: Float = 46f,
    private val baseWidthMin: Float = 4.5f,
    private val baseWidthMax: Float = 8.5f,
    private val tipWidthMin: Float = 0.35f,
    private val tipWidthMax: Float = 1.05f,
    private val fullMin: Float = 0.02f,
    private val fullMax: Float = 0.05f,
    private val fadeOutMin: Float = 0.08f,
    private val fadeOutMax: Float = 0.16f,
    private val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
) : ProjectileVisual {

    private var fadeStarted = false
    private var fadeOutSeconds = 0.14f
    private var fadeTimer = 0f
    private var acc = 0f

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        val rate = spokesPerSecond.coerceAtLeast(0f)
        if (rate <= 0f) return

        acc += rate * amount
        var n = acc.toInt()
        if (n <= 0) return
        // 兜底：避免低帧率时单帧爆量（会看起来又“突兀”）
        n = n.coerceIn(1, 18)
        acc -= n

        val loc = projectile.location
        for (i in 0 until n) {
            val ang = MathUtils.getRandomNumberInRange(0f, 360f)
            val length = MathUtils.getRandomNumberInRange(lengthMin, lengthMax)
            val baseWidth = MathUtils.getRandomNumberInRange(baseWidthMin, baseWidthMax)
            val tipWidth = MathUtils.getRandomNumberInRange(tipWidthMin, tipWidthMax)
            val full = MathUtils.getRandomNumberInRange(fullMin, fullMax)
            val fadeOut = MathUtils.getRandomNumberInRange(fadeOutMin, fadeOutMax)

            // 优先：BoxUtil 光刺
            try {
                val coreSprite = Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
                val fringeSprite = Global.getSettings().getSprite("graphics/fx/beamfringeb.png")
                val ent = BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
                    engine = engine,
                    location = loc,
                    facing = ang,
                    length = length,
                    tailWidth = tipWidth,
                    headWidth = baseWidth,
                    coreColor = coreColor,
                    fringeColor = fringeColor,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = layer,
                    full = full,
                    // 尖端极淡、基部极亮：更像“辐射刺”而不是短棒
                    tailAlphaMul = 0.05f,
                    headAlphaMul = 1.00f,
                    tailEmissiveAlphaMul = 0.95f,
                    headEmissiveAlphaMul = 3.15f,
                    mixPower = 3.7f,
                )
                if (ent != null) {
                    try {
                        ent.setGlobalTimer(0f, full, fadeOut)
                    } catch (_: Throwable) {
                    }
                    try {
                        ent.setFillStartAlpha(0f)
                        ent.setFillStartFactor(0.60f)
                        ent.setFillEndAlpha(0f)
                        ent.setFillEndFactor(0.92f)
                    } catch (_: Throwable) {
                    }
                    continue
                }
            } catch (_: Throwable) {
                // ignore
            }

            // 回退：原版粒子（同样做“向外辐射”）
            val dir = MathUtils.getPointOnCircumference(null, 1f, ang)
            val vel = Vector2f(dir.x * MathUtils.getRandomNumberInRange(80f, 190f), dir.y * MathUtils.getRandomNumberInRange(80f, 190f))
            engine.addHitParticle(
                loc,
                vel,
                MathUtils.getRandomNumberInRange(16f, 30f),
                MathUtils.getRandomNumberInRange(1.8f, 3.4f),
                MathUtils.getRandomNumberInRange(0.06f, 0.14f),
                coreColor,
            )
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        this.fadeTimer = 0f
        acc = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 粒子/beam 为引擎或 BoxUtil 托管，无需显式回收
    }
}

/**
 * 固定大小“十字光/透镜星芒”渲染：
 * - 与 [RadiatingSpokesProjectileVisual] 不同，这个效果不会随机抖动长度/宽度；
 * - 通过 4 根常驻短 beam（+X/-X/+Y/-Y）实现稳定的十字形态；
 * - 可选择是否随弹体速度方向旋转（默认不旋转，保持“屏幕对齐”的十字感）。
 */
internal class FixedCrossFlareProjectileVisual(
    private val engine: CombatEngineAPI,
    private val coreColor: Color,
    private val fringeColor: Color,
    /** 每根光刺的长度（单向）；十字的总跨度约为 2*longLength / 2*shortLength。 */
    private val longLength: Float = 54f,
    private val shortLength: Float = 38f,
    /** 基部（中心）宽度与尖端宽度。 */
    private val baseWidth: Float = 8.5f,
    private val tipWidth: Float = 0.55f,
    /** 十字的基础角度偏移（度）。0=“十”字；45=“X”字。 */
    private val baseAngleDeg: Float = 0f,
    /** 是否让十字跟随弹体速度方向旋转。false 则只用 [baseAngleDeg]（更像透镜星芒）。 */
    private val followVelocityFacing: Boolean = false,
    /** 十字光在飞行中缓慢旋转的角速度（度/秒）。顺时针为负值；默认 -12 表示缓慢顺时针。 */
    private val rotationDegPerSecond: Float = -12f,

    /**
     * 优先使用 BoxUtil 内置的“无方向纯色贴图”（如 BUtil_ONE）来渲染十字光刺。
     *
     * 背景：beamcore/beamfringe 这类贴图本身沿 U/V 有方向性（渐变/细节），在某些屏幕/世界方向下可能产生
     * “某一臂看起来逆向旋转”的错觉。使用纯色贴图可以从根源上去掉方向性线索。
     *
     * 失败回退：如果 BoxUtil 的 sprite sheet 不可用，会自动回退到原版 beamcoreb/beamfringeb。
     */
    private val preferFlatBoxUtilTexture: Boolean = true,
    /** BoxUtil textures sheet 里的 sprite id（默认 BUtil_ONE）。 */
    private val flatTextureId: String = "BUtil_ONE",

    /** 十字光亮度随机起伏（乘子）。 */
    private val flickerMinMul: Float = 0.78f,
    private val flickerMaxMul: Float = 1.26f,
    /** 多久重新随机一次目标亮度（秒）。 */
    private val flickerRetargetMinSeconds: Float = 0.10f,
    private val flickerRetargetMaxSeconds: Float = 0.26f,
    /** 追随目标亮度的速度（越大越“跳”）。 */
    private val flickerLerpSpeed: Float = 4.8f,
    private val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
) : ProjectileVisual {

    private var initialized = false
    private var boxOk = false
    private var rays: Array<TrailEntity?> = arrayOfNulls(4)

    // 缓存贴图，避免中途重建光刺时每次都 getSprite。
    private var cachedSprites: Pair<SpriteAPI, SpriteAPI>? = null

    // 避免 BoxUtil addEntity 短暂失败时每帧疯狂重试。
    private var recreateCooldown = 0f

    private var fadeStarted = false
    private var fadeOutSeconds = 0.16f
    private var fadeTimer = 0f
    private var deleted = false

    private var rotationDeg = 0f
    private var flickerTimer = 0f
    private var flickerRetarget = MathUtils.getRandomNumberInRange(flickerRetargetMinSeconds, flickerRetargetMaxSeconds)
    private var targetFlicker = MathUtils.getRandomNumberInRange(flickerMinMul, flickerMaxMul)
    private var currentFlicker = targetFlicker

    // 用“手动 fade”避免 BoxUtil setGlobalTimer 重置导致的末帧闪亮。
    // 注意：本 visual 的 beam 节点顺序为“中心(node0) → 尖端(node1)”，所以 start=中心/base，end=尖端/tip。
    private val baseAlphaMul = 0.78f
    private val tipAlphaMul = 0.05f
    private val baseEmissiveAlphaMul = 3.25f
    private val tipEmissiveAlphaMul = 0.95f
    private val mixPower = 3.8f

    // 顺序：长轴正向/反向，短轴正向/反向。
    private val armFacingOffsets: FloatArray = floatArrayOf(0f, 180f, 90f, 270f)

    /**
     * 统一把角度归一化到 [0,360)，避免 BoxUtil/底层计算对负角度产生的“方向/转向错觉”。
     *
     * 现象：某些情况下第一个光刺（offs[0]）会表现为“逆时针”，但手动 +360 后正常。
     * 根因通常是负角度在内部取模/插值时走了错误分支。
     */
    private fun normDeg(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    private fun loadTrailSprites(): Pair<SpriteAPI, SpriteAPI>? {
        if (preferFlatBoxUtilTexture) {
            try {
                val s = Global.getSettings().getSprite("textures", flatTextureId)
                return Pair(s, s)
            } catch (_: Throwable) {
                // fallback below
            }
        }

        return try {
            val core = Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
            val fringe = Global.getSettings().getSprite("graphics/fx/beamfringeb.png")
            Pair(core, fringe)
        } catch (_: Throwable) {
            null
        }
    }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            val t = fadeOutSeconds.coerceAtLeast(0.01f)
            val f = (1f - (fadeTimer / t)).coerceIn(0f, 1f)
            // 淡出阶段：沿用当前 flicker 值，让亮度不会“突然回正”。
            applyAlphaFactor(f, currentFlicker)
            if (!deleted && fadeTimer >= t) {
                deleted = true
                delete()
            }
            return
        }

        // 飞行中：缓慢旋转 + 亮度随机起伏
        rotationDeg += rotationDegPerSecond * amount
        // wrap 到 [-360, 360] 附近，防止数值无限累积
        if (rotationDeg > 360f || rotationDeg < -360f) rotationDeg %= 360f

        flickerTimer += amount
        if (flickerTimer >= flickerRetarget) {
            flickerTimer = 0f
            flickerRetarget = MathUtils.getRandomNumberInRange(flickerRetargetMinSeconds, flickerRetargetMaxSeconds)
            targetFlicker = MathUtils.getRandomNumberInRange(flickerMinMul, flickerMaxMul)
        }
        val lerp = (flickerLerpSpeed * amount).coerceIn(0f, 1f)
        currentFlicker = currentFlicker + (targetFlicker - currentFlicker) * lerp

        if (!initialized) {
            initialized = true
            boxOk = tryCreateRays(projectile)
        }

        if (boxOk) {
            // 现象：十字光的某几臂/全部会在飞行中突然消失。
            // 推断：TrailEntity 可能被 BoxUtil 渲染队列意外 delete/移除（例如队列清理/状态不同步）。
            // 处理：检测缺失并自动重建。
            ensureRaysAlive(projectile, amount)

            // 常驻 beam：每帧只更新 state 与强度乘子。
            applyAlphaFactor(1f, currentFlicker)
            val base = computeBaseFacing(projectile)
            // 重要：仅 setLocation/setFacingScale 在某些情况下会导致 TrailEntity 被判为 stale 并从渲染队列移除。
            // 这里在 setRayTransform 内优先走 setStateVanilla 以保持 BoxUtil 内部状态同步。
            val loc = projectile.location
            for (i in 0 until 4) {
                val r = rays[i] ?: continue
                setRayTransform(r, loc, base + armFacingOffsets[i])
            }
        } else {
            // Fallback：BoxUtil 不可用时，尽量用粒子“点阵”模拟一个十字端点闪光。
            // 注意：项目整体已有 glowBody/coreGlow，这里只补一点“十字端点”的可读性。
            spawnFallbackEndpoints(projectile)
        }

    }

    private fun setRayTransform(trail: TrailEntity, loc: Vector2f, facing: Float) {
        if (trail.hasDelete()) return
        try {
            trail.setStateVanilla(loc, normDeg(facing))
        } catch (_: Throwable) {
            // fallback：某些环境下 setStateVanilla 可能不可用/抛异常
            try {
                trail.setLocation(loc)
                trail.setFacingScale(normDeg(facing), 1f, 1f)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun computeBaseFacing(projectile: DamagingProjectileAPI): Float {
        if (!followVelocityFacing) return baseAngleDeg + rotationDeg
        val v = projectile.velocity
        val facing = if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) VectorUtils.getFacing(v) else projectile.facing
        return facing + baseAngleDeg + rotationDeg
    }

    private fun tryCreateRays(projectile: DamagingProjectileAPI): Boolean {
        val loc = projectile.location
        val sprites = loadTrailSprites() ?: return false
        cachedSprites = sprites
        val (coreSprite, fringeSprite) = sprites

        // 初始化朝向不重要（飞行中会持续更新），这里只要能创建成功。
        val base = computeBaseFacing(projectile)

        val c0Core = coreColor
        val c0Fringe = fringeColor
        val c1Core = coreColor
        val c1Fringe = fringeColor
        val c2Core = coreColor
        val c2Fringe = fringeColor
        val c3Core = coreColor
        val c3Fringe = fringeColor

        val aPos = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = loc,
            facing = normDeg(base + armFacingOffsets[0]),
            length = longLength,
            baseWidth = baseWidth,
            tipWidth = tipWidth,
            coreColor = c0Core,
            fringeColor = c0Fringe,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = layer,
            full = 9999f,
            baseAlphaMul = baseAlphaMul,
            tipAlphaMul = tipAlphaMul,
            baseEmissiveAlphaMul = baseEmissiveAlphaMul,
            tipEmissiveAlphaMul = tipEmissiveAlphaMul,
            mixPower = mixPower,
        )
        val aNeg = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = loc,
            facing = normDeg(base + armFacingOffsets[1]),
            length = longLength,
            baseWidth = baseWidth,
            tipWidth = tipWidth,
            coreColor = c1Core,
            fringeColor = c1Fringe,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = layer,
            full = 9999f,
            baseAlphaMul = baseAlphaMul,
            tipAlphaMul = tipAlphaMul,
            baseEmissiveAlphaMul = baseEmissiveAlphaMul,
            tipEmissiveAlphaMul = tipEmissiveAlphaMul,
            mixPower = mixPower,
        )
        val bPos = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = loc,
            facing = normDeg(base + armFacingOffsets[2]),
            length = shortLength,
            baseWidth = baseWidth,
            tipWidth = tipWidth,
            coreColor = c2Core,
            fringeColor = c2Fringe,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = layer,
            full = 9999f,
            baseAlphaMul = baseAlphaMul,
            tipAlphaMul = tipAlphaMul,
            baseEmissiveAlphaMul = baseEmissiveAlphaMul,
            tipEmissiveAlphaMul = tipEmissiveAlphaMul,
            mixPower = mixPower,
        )
        val bNeg = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = loc,
            facing = normDeg(base + armFacingOffsets[3]),
            length = shortLength,
            baseWidth = baseWidth,
            tipWidth = tipWidth,
            coreColor = c3Core,
            fringeColor = c3Fringe,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = layer,
            full = 9999f,
            baseAlphaMul = baseAlphaMul,
            tipAlphaMul = tipAlphaMul,
            baseEmissiveAlphaMul = baseEmissiveAlphaMul,
            tipEmissiveAlphaMul = tipEmissiveAlphaMul,
            mixPower = mixPower,
        )

        val created = arrayOf(aPos, aNeg, bPos, bNeg)
        if (created.any { it == null }) {
            created.forEach { it?.delete() }
            return false
        }

        rays = arrayOf(created[0], created[1], created[2], created[3])
        try {
            fun setup(r: TrailEntity?, texturePixels: Float) {
                r ?: return
                // 禁用 beam 的贴图“流动”，避免对向光刺出现纹理运动方向不一致的视觉错觉。
                r.setTextureSpeed(0f)
                r.setUVOffset(0f)
                // 关键：避免纹理沿长度方向高频重复导致的方向性/走样错觉。
                // distance(U) 单位是“世界像素”，texturePixels 设为接近长度时大约只重复 1 次。
                r.setTexturePixels(texturePixels.coerceAtLeast(8f))
                r.setFillStartAlpha(0f)
                r.setFillStartFactor(0.62f)
                r.setFillEndAlpha(0f)
                r.setFillEndFactor(0.92f)
            }
            // 0/1 为 long；2/3 为 short
            setup(rays[0], longLength)
            setup(rays[1], longLength)
            setup(rays[2], shortLength)
            setup(rays[3], shortLength)
        } catch (_: Throwable) {
        }

        return true
    }

    private fun ensureRaysAlive(projectile: DamagingProjectileAPI, amount: Float) {
        if (!boxOk) return

        if (recreateCooldown > 0f) {
            recreateCooldown = (recreateCooldown - amount).coerceAtLeast(0f)
        }

        var need = false
        for (r in rays) {
            if (r == null || r.hasDelete()) {
                need = true
                break
            }
        }
        if (!need) return
        if (recreateCooldown > 0f) return

        val sprites = cachedSprites ?: loadTrailSprites()?.also { cachedSprites = it }
        if (sprites == null) {
            boxOk = false
            return
        }
        val (coreSprite, fringeSprite) = sprites

        val loc = projectile.location
        val base = computeBaseFacing(projectile)

        fun setup(r: TrailEntity?, texturePixels: Float) {
            r ?: return
            try {
                r.setTextureSpeed(0f)
                r.setUVOffset(0f)
                r.setTexturePixels(texturePixels.coerceAtLeast(8f))
                r.setFillStartAlpha(0f)
                r.setFillStartFactor(0.62f)
                r.setFillEndAlpha(0f)
                r.setFillEndFactor(0.92f)
            } catch (_: Throwable) {
            }
        }

        fun recreate(index: Int): TrailEntity? {
            val len = if (index <= 1) longLength else shortLength
            val facing = normDeg(base + armFacingOffsets[index])
            val ent = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = loc,
                facing = facing,
                length = len,
                baseWidth = baseWidth,
                tipWidth = tipWidth,
                coreColor = coreColor,
                fringeColor = fringeColor,
                coreSprite = coreSprite,
                fringeSprite = fringeSprite,
                layer = layer,
                full = 9999f,
                baseAlphaMul = baseAlphaMul,
                tipAlphaMul = tipAlphaMul,
                baseEmissiveAlphaMul = baseEmissiveAlphaMul,
                tipEmissiveAlphaMul = tipEmissiveAlphaMul,
                mixPower = mixPower,
            )
            setup(ent, len)
            return ent
        }

        var okAny = false
        for (i in 0 until 4) {
            val r = rays[i]
            if (r == null || r.hasDelete()) {
                try {
                    r?.delete()
                } catch (_: Throwable) {
                }

                val ent = recreate(i)
                rays[i] = ent
                if (ent != null) okAny = true
            }
        }

        if (!okAny) {
            recreateCooldown = 0.25f
            boxOk = false
        }
    }

    private fun applyAlphaFactor(f: Float, brightnessMul: Float) {
        if (!boxOk) return
        val b = brightnessMul.coerceIn(0.0f, 10.0f)
        fun apply(e: TrailEntity?) {
            val ent = e ?: return
            if (ent.hasDelete()) return
            try {
                // start=中心/base，end=尖端/tip
                ent.setStartColor(1f, 1f, 1f, baseAlphaMul * f * b)
                ent.setEndColor(1f, 1f, 1f, tipAlphaMul * f * b)
                ent.setStartEmissive(1f, 1f, 1f, baseEmissiveAlphaMul * f * b)
                ent.setEndEmissive(1f, 1f, 1f, tipEmissiveAlphaMul * f * b)
            } catch (_: Throwable) {
            }
        }
        for (r in rays) apply(r)
    }

    private var fallbackAcc = 0f

    private fun spawnFallbackEndpoints(projectile: DamagingProjectileAPI) {
        fallbackAcc += 1f
        // 只要 BoxUtil 不可用，这个效果意义不大：用“低频端点闪光”补一点存在感即可。
        if (fallbackAcc < 2f) return
        fallbackAcc = 0f

        val base = computeBaseFacing(projectile)
        val u = MathUtils.getPointOnCircumference(null, 1f, base)
        val v = MathUtils.getPointOnCircumference(null, 1f, base + 90f)
        val center = projectile.location
        val vel = projectile.velocity?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
        vel.scale(0.06f)

        fun addAt(dx: Float, dy: Float, size: Float, brightness: Float, dur: Float) {
            engine.addSmoothParticle(Vector2f(center.x + dx, center.y + dy), vel, size, brightness, dur, coreColor)
        }

        val lp = longLength.coerceAtLeast(12f)
        val sp = shortLength.coerceAtLeast(10f)
        addAt(u.x * lp, u.y * lp, 18f, 2.2f, 0.05f)
        addAt(-u.x * lp, -u.y * lp, 18f, 2.2f, 0.05f)
        addAt(v.x * sp, v.y * sp, 16f, 2.0f, 0.05f)
        addAt(-v.x * sp, -v.y * sp, 16f, 2.0f, 0.05f)
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
        deleted = false
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && (deleted || fadeTimer >= fadeOutSeconds)
    }

    override fun delete() {
        for (i in 0 until 4) {
            val r = rays[i]
            if (r != null) {
                try {
                    if (!r.hasDelete()) r.delete()
                } catch (_: Throwable) {
                }
                rays[i] = null
            }
        }
    }
}

/**
 * 将导弹本体 sprite 彻底隐藏（alpha override = 0）。
 *
 * 目的：实现“去贴图、纯代码弹体渲染”（弹体外观由 trail/粒子等代码效果承担）。
 */
internal class HideMissileSpriteProjectileVisual : ProjectileVisual {

    private var fadeStarted = false
    private var fadeOutSeconds = 0.10f
    private var fadeTimer = 0f

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        // 只对 MissileAPI 生效；非导弹弹体直接忽略。
        val missile = projectile as? MissileAPI ?: return

        // fade 期间也继续强制隐藏，避免“淡出阶段贴图突然出现”。
        try {
            missile.setSpriteAlphaOverride(0f)
        } catch (_: Throwable) {
            // 兼容：如果 API/实现差异导致不可用，静默失败。
        }

        try {
            missile.setGlowRadius(0f)
        } catch (_: Throwable) {
        }

        if (fadeStarted && amount > 0f) {
            fadeTimer += amount
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        this.fadeTimer = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 无需显式回滚 alpha override；导弹即将被引擎移除。
    }
}

/**
 * 导弹“针尖”高亮：在弹体前方一点点位置持续喷少量亮粒子，让弹头不那么“钝/团”。
 */
internal class MissileNoseNeedleProjectileVisual(
    private val engine: CombatEngineAPI,
    private val color: Color,
    private val particlesPerSecond: Float = 120f,
    private val aheadDistance: Float = 18f,
    private val sizeMin: Float = 6f,
    private val sizeMax: Float = 11f,
    private val brightnessMin: Float = 2.0f,
    private val brightnessMax: Float = 3.1f,
    private val durationMin: Float = 0.04f,
    private val durationMax: Float = 0.08f,
    private val inheritVelocityMul: Float = 0.10f,
) : ProjectileVisual {

    private var fadeStarted = false
    private var fadeOutSeconds = 0.12f
    private var fadeTimer = 0f
    private var acc = 0f

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        val rate = particlesPerSecond.coerceAtLeast(0f)
        if (rate <= 0f) return

        acc += rate * amount
        val n = acc.toInt()
        if (n <= 0) return
        acc -= n

        val v = projectile.velocity
        val facing = if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
            VectorUtils.getFacing(v)
        } else {
            projectile.facing
        }

        val baseVel = projectile.velocity?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
        baseVel.scale(inheritVelocityMul.coerceAtLeast(0f))

        val nose = MathUtils.getPointOnCircumference(projectile.location, aheadDistance, facing)
        for (i in 0 until n) {
            val loc = MathUtils.getRandomPointInCircle(nose, 1.6f)
            val size = MathUtils.getRandomNumberInRange(sizeMin, sizeMax)
            val brightness = MathUtils.getRandomNumberInRange(brightnessMin, brightnessMax)
            val duration = MathUtils.getRandomNumberInRange(durationMin, durationMax)
            engine.addSmoothParticle(loc, baseVel, size, brightness, duration, color)
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        this.fadeTimer = 0f
        acc = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 引擎托管粒子，无需显式回收
    }
}

/**
 * 固定环形粒子发射器：在弹体周围周期性生成“等半径环”。
 *
 * 用于表现“弹体周围不断生成的固定环形特效”（参考图中绿色标注）。
 *
 * 实现说明：
 * - 每隔 [interval] 秒，在弹体位置（以及可选的若干“沿飞行方向的后方偏移点”）生成一次环。
 * - 粒子速度继承弹体速度，使环在视觉上“黏在弹体上”。
 */
internal class FixedRingEmitterProjectileVisual(
    private val engine: CombatEngineAPI,
    private val color: Color,
    private val radius: Float,
    private val particleCount: Int,
    private val size: Float,
    private val brightness: Float,
    private val duration: Float,
    interval: Float,
    /** 若为 true，则在首次 advance（或创建后首帧）立即生成一次环，避免“延迟一拍”。 */
    private val spawnImmediately: Boolean = false,
    /** 沿“弹体后方方向（facing + 180）”的偏移距离列表。 */
    private val offsetsBehind: FloatArray = floatArrayOf(0f),
) : ProjectileVisual {

    private val interval: Float = interval.coerceAtLeast(0.01f)

    private var fadeStarted = false
    private var fadeOutSeconds = 0.12f
    private var fadeTimer = 0f

    private var acc = if (spawnImmediately) interval.coerceAtLeast(0.01f) else 0f

    private fun computeFacing(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity
        return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
            VectorUtils.getFacing(v)
        } else {
            projectile.facing
        }
    }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        acc += amount
        if (acc < interval) return

        // 允许补帧：如果帧率很低，可能一次 advance 需要生成多次环。
        val facing = computeFacing(projectile)
        val baseVel = projectile.velocity

        while (acc >= interval) {
            acc -= interval

            for (d in offsetsBehind) {
                val center = if (d <= 0.01f) {
                    projectile.location
                } else {
                    MathUtils.getPointOnCircumference(projectile.location, d, facing + 180f)
                }

                ProjectileVfxUtil.spawnRing(
                    engine = engine,
                    center = center,
                    baseVel = baseVel,
                    radius = radius,
                    particleCount = particleCount,
                    size = size,
                    brightness = brightness,
                    duration = duration,
                    color = color,
                )
            }
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
        acc = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 粒子由引擎托管，无需显式回收
    }
}

/**
 * “非曳光”的长同色拖尾：用粒子带状体积感来表现长拖尾，而不是一条 beam/tracer 线。
 *
 * 生成策略：每帧按速率在弹体后方 [tailLength] 范围内随机采样点生成粒子，
 * 让尾部自然稀疏、前端更亮更密。
 */
internal class LongTailRibbonParticleProjectileVisual(
    private val engine: CombatEngineAPI,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val tailLength: Float,
    private val halfWidth: Float,
    private val particlesPerSecond: Float,
    private val sizeNear: Float,
    private val sizeFar: Float,
    private val brightnessNear: Float,
    private val brightnessFar: Float,
    private val durationNear: Float,
    private val durationFar: Float,
    private val inheritVelocityMul: Float = 0.08f,
    /** 采样范围（相对 [tailLength] 的比例）。0=从弹体处采样；1=尾端。 */
    private val sampleMinRatio: Float = 0f,
    private val sampleMaxRatio: Float = 1f,
    /** 越大则越强调“靠近弹体更亮”。 */
    private val falloffPower: Float = 1.75f,
) : ProjectileVisual {

    private var fadeStarted = false
    private var fadeOutSeconds = 0.12f
    private var fadeTimer = 0f
    private var acc = 0f

    private fun computeFacing(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity
        return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
            VectorUtils.getFacing(v)
        } else {
            projectile.facing
        }
    }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        val rate = particlesPerSecond.coerceAtLeast(0f)
        if (rate <= 0f) return

        acc += rate * amount
        val n = acc.toInt()
        if (n <= 0) return
        acc -= n

        val facing = computeFacing(projectile)
        val behindFacing = facing + 180f
        val sideFacing = facing + 90f

        val baseVel = projectile.velocity?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
        baseVel.scale(inheritVelocityMul.coerceAtLeast(0f))

        val len = tailLength.coerceAtLeast(1f)
        val hw = halfWidth.coerceAtLeast(0f)

        val minR = sampleMinRatio.coerceIn(0f, 1f)
        val maxR = sampleMaxRatio.coerceIn(minR, 1f)
        val dMin = len * minR
        val dMax = (len * maxR).coerceAtLeast(dMin + 0.01f)

        for (i in 0 until n) {
            // d=0 在弹体处，d=len 在尾端
            val d = MathUtils.getRandomNumberInRange(dMin, dMax)
            val t0 = (1f - (d / len)).coerceIn(0f, 1f)
            val t = t0.toDouble().pow(falloffPower.coerceAtLeast(0.01f).toDouble()).toFloat()

            val along = MathUtils.getPointOnCircumference(null, d, behindFacing)
            val lateral = if (hw <= 0.01f) Vector2f(0f, 0f) else {
                MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(-hw, hw), sideFacing)
            }

            val loc = Vector2f(
                projectile.location.x + along.x + lateral.x,
                projectile.location.y + along.y + lateral.y,
            )

            val size = (sizeFar + (sizeNear - sizeFar) * t).coerceAtLeast(1f)
            val brightness = (brightnessFar + (brightnessNear - brightnessFar) * t).coerceAtLeast(0.05f)
            val duration = (durationFar + (durationNear - durationFar) * t).coerceAtLeast(0.03f)
            val color = if ((i and 1) == 0) fringeColor else coreColor

            engine.addSmoothParticle(loc, baseVel, size, brightness, duration, color)
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
        acc = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 粒子由引擎托管，无需显式回收
    }
}

/**
 * “音爆扭曲环”：在弹体路径上留下椭圆形 distortion ring，不跟随弹体。
 *
 * 目标：更像“画出来的一圈”，而不是用点拼出来的环。
 */
internal class PathDistortionShockRingEmitterProjectileVisual(
    private val engine: CombatEngineAPI,
    interval: Float,
    private val offsetsBehind: FloatArray = floatArrayOf(0f),

    // 椭圆半尺寸（DistortionEntity 使用 half-size）
    private val sizeFullXHalf: Float,
    private val sizeFullYHalf: Float,

    // 三段尺寸：in -> full -> out（用于“膨胀+淡出”）
    private val sizeInFactor: Float = 0.78f,
    private val sizeOutFactor: Float = 1.18f,

    // 强度：in/full/out
    private val powerFull: Float = 0.42f,
    private val powerOut: Float = 0.08f,

    // 生命周期
    private val fadeInSeconds: Float = 0.03f,
    private val fullSeconds: Float = 0.05f,
    private val fadeOutSeconds: Float = 0.20f,

    // 环形“线感”
    private val innerFullRatio: Float = 0.68f,
    private val innerHardness: Float = 0.92f,
    private val ringHardness: Float = 0.88f,

    // fallback 粒子环（当 BoxUtil distortion 不可用时）
    private val fallbackParticleColor: Color = Color(255, 220, 140, 60),
    private val fallbackRadius: Float = 24f,
    private val fallbackParticleCount: Int = 16,
    private val fallbackSize: Float = 10f,
    private val fallbackBrightness: Float = 1.1f,
    private val fallbackDuration: Float = 0.14f,
) : ProjectileVisual {

    private val interval: Float = interval.coerceAtLeast(0.01f)

    private var fadeStarted = false
    private var fadeOut = 0.12f
    private var fadeTimer = 0f
    private var acc = 0f

    private fun computeFacing(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity
        return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
            VectorUtils.getFacing(v)
        } else {
            projectile.facing
        }
    }

    private fun trySpawnDistortionRing(center: Vector2f) {
        try {
            val e = DistortionEntity()
            e.setGlobalTimer(fadeInSeconds, fullSeconds, fadeOutSeconds)

            // 尽量做成“环”：中心区域更硬、更满；边缘 ring 更硬，视觉更像“画出来的一圈”。
            e.setInnerFull(innerFullRatio, innerFullRatio)
            e.setInnerHardness(innerHardness)
            e.setRingHardness(ringHardness)

            val xFull = sizeFullXHalf.coerceAtLeast(1f)
            val yFull = sizeFullYHalf.coerceAtLeast(1f)
            e.setSizeIn(xFull * sizeInFactor, yFull * sizeInFactor)
            e.setSizeFull(xFull, yFull)
            e.setSizeOut(xFull * sizeOutFactor, yFull * sizeOutFactor)

            e.setPowerIn(0f)
            e.setPowerFull(powerFull)
            e.setPowerOut(powerOut)

            e.setLocation(center)

            val added = CombatRenderingManager.addEntity(e)
            if (added == BoxEnum.STATE_SUCCESS) return
            e.delete()
        } catch (_: Throwable) {
            // ignore
        }

        // fallback：静止粒子环（仍然“留在路径上”，只是点状）
        ProjectileVfxUtil.spawnRing(
            engine = engine,
            center = center,
            baseVel = Vector2f(0f, 0f),
            radius = fallbackRadius,
            particleCount = fallbackParticleCount,
            size = fallbackSize,
            brightness = fallbackBrightness,
            duration = fallbackDuration,
            color = fallbackParticleColor,
        )
    }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        acc += amount
        if (acc < interval) return

        val facing = computeFacing(projectile)
        while (acc >= interval) {
            acc -= interval

            for (d in offsetsBehind) {
                val center = if (d <= 0.01f) {
                    Vector2f(projectile.location)
                } else {
                    MathUtils.getPointOnCircumference(projectile.location, d, facing + 180f)
                }
                trySpawnDistortionRing(center)
            }
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        fadeOut = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
        acc = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOut
    }

    override fun delete() {
        // 生成的 DistortionEntity 自己有 timer，会自行回收。
    }
}

/**
 * “霓虹椭圆环（粒子版）”：沿弹体路径按【距离】均匀采样生成一组椭圆环粒子。
 *
 * 目标：复刻参考图里“长直线曳光 + 一圈圈环形线圈”的观感。
 * - 使用距离采样以适配不同弹速（弹速翻倍也保持环的空间间距稳定）。
 * - 椭圆按弹体朝向旋转：长轴垂直于弹道方向，看起来像“环套在光束上”。
 */
internal class PathEllipseParticleShockRingEmitterProjectileVisual(
    private val engine: CombatEngineAPI,
    /** 每隔多少距离（世界单位）生成一圈。 */
    spacingDistance: Float,
    /** 在弹体后方的偏移（可多个）：用于让环更靠后、避免盖住弹头。 */
    private val offsetsBehind: FloatArray = floatArrayOf(0f),

    /** 起始距离门槛：弹体飞出一定距离后才开始生成，避免开火瞬间“贴炮口冒圈”。 */
    private val startDistance: Float = 0f,

    /** 椭圆半轴：a 为垂直于弹道方向（侧向）半径，b 为沿弹道方向（前后）半径。 */
    private val aSideHalf: Float,
    private val bAlongHalf: Float,

    private val particleCount: Int = 40,
    private val particleSize: Float = 7f,
    private val brightness: Float = 2.0f,
    private val duration: Float = 0.15f,
    private val color: Color = Color(255, 80, 120, 90),

    /** 让线圈环“缓慢变大消失”：给粒子一个沿半径方向的外扩速度。 */
    private val expandSpeed: Float = 0f,
    /** 轻微的切向速度（可选）：让环边缘有一点“扭动/旋”感。 */
    private val tangentialSpeed: Float = 0f,
) : ProjectileVisual {

    private val step: Float = spacingDistance.coerceAtLeast(1f)

    private var fadeStarted = false
    private var fadeOutSeconds = 0.12f
    private var fadeTimer = 0f

    private var distAcc = 0f
    private var traveled = 0f

    private fun computeFacing(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity
        return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
            VectorUtils.getFacing(v)
        } else {
            projectile.facing
        }
    }

    private fun speed(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity ?: return 0f
        val s2 = v.x * v.x + v.y * v.y
        if (s2 <= 0.0001f) return 0f
        return sqrt(s2)
    }

    private fun spawnEllipseRing(center: Vector2f, facing: Float) {
        val a = aSideHalf.coerceAtLeast(1f)
        val b = bAlongHalf.coerceAtLeast(1f)

        // facing 单位向量（沿弹道）与其法线（侧向）
        val rad = Math.toRadians(facing.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val vx = -uy
        val vy = ux

        val n = particleCount.coerceAtLeast(6)
        val phase = MathUtils.getRandomNumberInRange(0f, (2f * PI).toFloat())
        val stepAng = (2f * PI).toFloat() / n.toFloat()

        for (i in 0 until n) {
            val ang = phase + stepAng * i
            val ca = cos(ang.toDouble()).toFloat()
            val sa = sin(ang.toDouble()).toFloat()

            // 椭圆：侧向用 cos，沿向用 sin；长轴在侧向（更像“环套在光束上”）
            val ox = vx * (a * ca) + ux * (b * sa)
            val oy = vy * (a * ca) + uy * (b * sa)

            val loc = Vector2f(center.x + ox, center.y + oy)
            val vel = if (expandSpeed > 0.01f || abs(tangentialSpeed) > 0.01f) {
                val len = sqrt(ox * ox + oy * oy).coerceAtLeast(0.01f)
                val nx = ox / len
                val ny = oy / len
                // radial outward
                var vxOut = nx * expandSpeed
                var vyOut = ny * expandSpeed
                if (abs(tangentialSpeed) > 0.01f) {
                    // tangent = perpendicular of radial
                    vxOut += (-ny) * tangentialSpeed
                    vyOut += (nx) * tangentialSpeed
                }
                Vector2f(vxOut, vyOut)
            } else {
                Vector2f(0f, 0f)
            }

            engine.addSmoothParticle(loc, vel, particleSize, brightness, duration, color)
        }
    }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        val s = speed(projectile)
        if (s <= 0.01f) return

        traveled += s * amount
        if (traveled < startDistance) return

        distAcc += s * amount
        if (distAcc < step) return

        val facing = computeFacing(projectile)
        while (distAcc >= step) {
            distAcc -= step

            for (d in offsetsBehind) {
                val center = if (d <= 0.01f) {
                    Vector2f(projectile.location)
                } else {
                    MathUtils.getPointOnCircumference(projectile.location, d, facing + 180f)
                }
                spawnEllipseRing(center, facing)
            }
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 粒子由引擎托管，无需显式回收
    }
}

/**
 * “飞行中逐步拉出的拖尾”：按距离采样在弹体经过的位置生成拖尾粒子。
 *
 * 用途：替代 tracer 那种“开火第一帧就画出整条固定长度线”的表现，
 * 让拖尾随着弹体飞行逐渐出现；同时支持边缘装饰线条（会随时间漂移/扭曲并淡化）。
 */
internal class DistanceSampledNeonTrailParticleEmitterProjectileVisual(
    private val engine: CombatEngineAPI,
    /** 飞出多少距离后才开始生成拖尾，避免炮口附近的怪异闪烁。 */
    private val startDistance: Float,
    /** 是否生成核心拖尾粒子（false 时仅生成边缘装饰线条）。 */
    private val emitCore: Boolean = true,
    /** 采样间距（世界单位）：越小越连贯，但粒子数量越大。 */
    sampleSpacing: Float,

    private val coreColor: Color,
    private val edgeColor: Color,

    private val coreSize: Float,
    private val edgeSize: Float,
    private val coreBrightness: Float,
    private val edgeBrightness: Float,
    private val coreDuration: Float,
    private val edgeDuration: Float,

    /** 边缘装饰线：左右各偏移多少（世界单位）。 */
    private val edgeOffset: Float,
    /** 边缘装饰的随机抖动（世界单位）。 */
    private val edgeJitter: Float,
    /** 边缘装饰的漂移速度（单位/秒），用于模拟“逐渐扭曲”。 */
    private val edgeDriftSpeed: Float,
    /** 偶发的“毛刺/扭动”粒子概率（0-1）。 */
    private val decorChance: Float,
) : ProjectileVisual {

    private val spacing: Float = sampleSpacing.coerceAtLeast(1f)

    private var fadeStarted = false
    private var fadeOutSeconds = 0.12f
    private var fadeTimer = 0f

    private var initialized = false
    private var lastLoc = Vector2f(0f, 0f)
    private var traveled = 0f
    private var distAcc = 0f

    private fun computeFacing(projectile: DamagingProjectileAPI): Float {
        val v = projectile.velocity
        return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
            VectorUtils.getFacing(v)
        } else {
            projectile.facing
        }
    }

    private fun emitAt(pos: Vector2f, facing: Float) {
        // facing 单位向量（沿弹道）与其法线（侧向）
        val rad = Math.toRadians(facing.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val sx = -uy
        val sy = ux

        // 核心拖尾：尽量稳定，不给速度，避免尾迹“散开”。
        if (emitCore && coreSize > 0.01f && coreColor.alpha > 0) {
            engine.addSmoothParticle(pos, Vector2f(0f, 0f), coreSize, coreBrightness, coreDuration, coreColor)
        }

        // 边缘装饰：左右两条，带轻微抖动与漂移。
        val off = edgeOffset.coerceAtLeast(0f)
        if (off > 0.01f) {
            for (sign in floatArrayOf(-1f, 1f)) {
                val jitter = if (edgeJitter > 0.01f) MathUtils.getRandomNumberInRange(-edgeJitter, edgeJitter) else 0f
                val ex = (off + jitter) * sign
                val edgePos = Vector2f(pos.x + sx * ex, pos.y + sy * ex)

                // 漂移：侧向为主，少量沿向扰动，让边缘线“扭动”。
                val vSide = MathUtils.getRandomNumberInRange(-edgeDriftSpeed, edgeDriftSpeed)
                val vAlong = MathUtils.getRandomNumberInRange(-edgeDriftSpeed * 0.25f, edgeDriftSpeed * 0.25f)
                val vel = Vector2f(sx * vSide + ux * vAlong, sy * vSide + uy * vAlong)

                engine.addSmoothParticle(edgePos, vel, edgeSize, edgeBrightness, edgeDuration, edgeColor)

                // 偶发“毛刺”：更小、更短寿命、更快漂移，制造边缘随时间扭曲/淡化的细节。
                if (decorChance > 0f && Math.random().toFloat() < decorChance) {
                    val v2Side = MathUtils.getRandomNumberInRange(-edgeDriftSpeed * 1.6f, edgeDriftSpeed * 1.6f)
                    val v2Along = MathUtils.getRandomNumberInRange(-edgeDriftSpeed * 0.6f, edgeDriftSpeed * 0.6f)
                    val vel2 = Vector2f(sx * v2Side + ux * v2Along, sy * v2Side + uy * v2Along)
                    engine.addSmoothParticle(edgePos, vel2, edgeSize * 0.72f, edgeBrightness * 0.85f, edgeDuration * 0.65f, edgeColor)
                }
            }
        }
    }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount <= 0f) return

        if (fadeStarted) {
            fadeTimer += amount
            return
        }

        val cur = Vector2f(projectile.location)
        if (!initialized) {
            initialized = true
            lastLoc = cur
            return
        }

        val dx = cur.x - lastLoc.x
        val dy = cur.y - lastLoc.y
        val stepDist = sqrt(dx * dx + dy * dy)
        if (stepDist <= 0.001f) {
            lastLoc = cur
            return
        }

        traveled += stepDist
        distAcc += stepDist

        if (traveled >= startDistance) {
            val facing = computeFacing(projectile)
            // 可能一帧走很远：插值多个采样点
            while (distAcc >= spacing) {
                distAcc -= spacing
                val t = ((stepDist - distAcc) / stepDist).coerceIn(0f, 1f)
                val p = Vector2f(lastLoc.x + dx * t, lastLoc.y + dy * t)
                emitAt(p, facing)
            }
        }

        lastLoc = cur
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 粒子由引擎托管，无需显式回收
    }
}

/**
 * BoxUtil 烟雾式弹体尾迹：在通用代码弹体上叠加“左右两条消散线 + 可选大烟雾带 + 可选装饰闪丝”。
 *
 * 视觉目标：
 * - 参考 MagicTrail 的“路径两侧拉线”思路，但实体实现使用 BoxUtil [TrailEntity]。
 * - 尾迹从弹体附近的亮色逐渐过渡到尾端深色，形成“烧蚀/烟雾消散”感。
 * - 多节点 trail 每帧写入轻微噪声波形，打破纯渐变锥体的硬边。
 *
 * 使用注意：
 * - [Style.sideLinesEnabled] 控制两条淡色边线，[Style.smokeRibbonEnabled] 控制图 4 风格的大烟雾带。
 * - [Style.decorEnabled] 会增加短寿命闪丝数量；高射速小弹体建议关闭或降低 [Style.decorChancePerSecond]。
 * - [Style.nodeCount] 越高越平滑，CPU 提交节点越多；常规弹体建议 `10..18`。
 * - [Style.noiseAmplitude] 和 [Style.noiseWavelength] 决定烟雾扭动。振幅过高会像锯齿，波长过短会显得抖。
 * - [Style.textureSpeed] 提供贴图流动。绝对值过高会有闪烁，推荐 `-220..220`。
 * - 这里是纯 BoxUtil 路径。若所有 BoxUtil entity 创建失败，会返回 null，让 [ProjectileTracerManager] pending 重试。
 */
internal class BoxUtilSmokySideTrailProjectileVisual private constructor(
    private val engine: CombatEngineAPI,
    private val style: Style,
    private val sideTrails: List<TrailEntity>,
    private val smokeTrail: TrailEntity?,
    private val decorTrails: MutableList<DecorTrail>,
) : ProjectileVisual {

    data class Style(
        /** 总开关。false 时工厂直接跳过本 visual。 */
        val enabled: Boolean = true,

        /** 两条细边线开关：对应图 1/2/3 的“曳光两侧线条”。 */
        val sideLinesEnabled: Boolean = true,
        /** 大烟雾带开关：对应图 4 的“一条大烟雾消散线”。 */
        val smokeRibbonEnabled: Boolean = true,
        /** 装饰闪丝开关：在尾迹边缘偶发短线，增加能量撕裂感。 */
        val decorEnabled: Boolean = true,

        val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        val fullSeconds: Float = 9999f,

        /** 多节点数量。建议 10..18；很长的主炮弹体可提高到 24。 */
        val nodeCount: Int = 14,
        /** 侧线长度。应略长于主 tracer，才能在远端读出烟雾消散。 */
        val sideLength: Float = 220f,
        /** 大烟雾带长度。通常为 [sideLength] 的 1.15..1.8 倍。 */
        val smokeLength: Float = 300f,
        /** 侧线与弹道中心线距离。过小会被主曳光淹没，过大像分叉。 */
        val sideOffset: Float = 7f,
        /** 大烟雾带中心偏移；0 表示压在中心线，正值偏到一侧。 */
        val smokeOffset: Float = 0f,

        val sideHeadWidth: Float = 2.2f,
        val sideTailWidth: Float = 0.55f,
        val smokeHeadWidth: Float = 9f,
        val smokeTailWidth: Float = 2.2f,

        /** 弹体附近颜色。通常跟 projectile core/fringe 同色。 */
        val headCoreColor: Color = Color(220, 245, 255, 170),
        val headFringeColor: Color = Color(120, 200, 255, 140),
        /** 烟雾带的主色与边缘色。建议使用更暗、更低饱和的色值，避免看起来只是第二条亮线。 */
        val smokeCoreColor: Color = Color(32, 42, 58, 150),
        val smokeFringeColor: Color = Color(58, 72, 92, 118),
        /** 尾端深色。用于“颜色沉入暗部”，避免只靠 alpha 变淡。 */
        val tailCoreColor: Color = Color(16, 24, 36, 86),
        val tailFringeColor: Color = Color(45, 60, 95, 70),

        val sideHeadAlpha: Float = 0.45f,
        val sideTailAlpha: Float = 0.06f,
        val sideHeadEmissive: Float = 0.70f,
        val sideTailEmissive: Float = 0.02f,
        val smokeHeadAlpha: Float = 0.22f,
        val smokeTailAlpha: Float = 0.045f,
        val smokeHeadEmissive: Float = 0.22f,
        val smokeTailEmissive: Float = 0.0f,

        /** 噪声振幅：控制尾迹横向摇摆。 */
        val noiseAmplitude: Float = 5.5f,
        /** 噪声波长：越长越平滑。 */
        val noiseWavelength: Float = 92f,
        /** 噪声随时间流动速度。 */
        val noiseScrollSpeed: Float = 86f,
        /** 贴图滚动速度。 */
        val textureSpeed: Float = -140f,
        /** 贴图重复长度。 */
        val texturePixels: Float = 150f,
        /** BoxUtil Trail jitter。少量即可。 */
        val jitterPower: Float = 0.035f,

        /** 装饰闪丝每秒期望数量。 */
        val decorChancePerSecond: Float = 7f,
        val decorLengthMin: Float = 28f,
        val decorLengthMax: Float = 70f,
        val decorWidth: Float = 1.2f,
        val decorLife: Float = 0.12f,
    )

    private data class DecorTrail(
        val entity: TrailEntity,
        var age: Float,
        val life: Float,
    )

    private var fadeStarted = false
    private var fadeOutSeconds = 0.16f
    private var fadeTimer = 0f
    private var time = 0f

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount > 0f) {
            time += amount
        }

        if (fadeStarted) {
            if (amount > 0f) fadeTimer += amount
            updateDecor(amount)
            return
        }

        val facing = computeFacing(projectile)
        val loc = projectile.location

        for (i in sideTrails.indices) {
            val sign = if (i == 0) -1f else 1f
            updateTrailNodes(sideTrails[i], style.sideLength, style.sideOffset * sign, style.sideTailWidth, style.sideHeadWidth, sign, smoke = false)
            sideTrails[i].setStateVanilla(loc, facing + 180f)
        }

        smokeTrail?.let {
            updateTrailNodes(it, style.smokeLength, style.smokeOffset, style.smokeTailWidth, style.smokeHeadWidth, 0.35f, smoke = true)
            it.setStateVanilla(loc, facing + 180f)
        }

        if (amount > 0f && style.decorEnabled && style.decorChancePerSecond > 0f) {
            val expected = style.decorChancePerSecond * amount
            if (Math.random().toFloat() < expected.coerceIn(0f, 1f)) {
                spawnDecor(projectile, facing)
            }
        }
        updateDecor(amount)
    }

    private fun updateTrailNodes(
        entity: TrailEntity,
        length: Float,
        baseOffset: Float,
        tailWidth: Float,
        headWidth: Float,
        phaseSign: Float,
        smoke: Boolean,
    ) {
        if (entity.hasDelete()) return
        try {
            val nodes = entity.nodes ?: return
            val n = nodes.size.coerceAtLeast(2)
            val len = length.coerceAtLeast(1f)
            for (i in 0 until n) {
                val t = i.toFloat() / (n - 1).toFloat()
                // node[0] 位于尾端，node[last] 位于弹体附近。x 由 length -> 0。
                val x = len * (1f - t)
                val tailFactor = 1f - t
                val waveA = style.noiseAmplitude * (0.35f + tailFactor * 0.95f) * if (smoke) 2.05f else 1f
                val wave0 = sin(((x + time * style.noiseScrollSpeed) / style.noiseWavelength).toDouble()).toFloat()
                val wave1 = sin(((x * 1.73f - time * style.noiseScrollSpeed * 0.61f) / (style.noiseWavelength * 0.63f)).toDouble()).toFloat()
                val y = baseOffset + (wave0 * 0.72f + wave1 * 0.28f) * waveA * phaseSign
                nodes[i].x = x
                nodes[i].y = y
            }
            entity.setStartWidth(tailWidth)
            entity.setEndWidth(headWidth)
            entity.setNodeRefreshIndex(0)
            entity.setNodeRefreshSize(n)
            entity.submitNodes()
        } catch (_: Throwable) {
        }
    }

    private fun spawnDecor(projectile: DamagingProjectileAPI, facing: Float) {
        val len = MathUtils.getRandomNumberInRange(style.decorLengthMin, style.decorLengthMax).coerceAtLeast(4f)
        val d = MathUtils.getRandomNumberInRange(style.sideLength * 0.18f, style.sideLength * 0.92f)
        val side = if (Math.random() < 0.5) -1f else 1f
        val lateral = style.sideOffset * side + MathUtils.getRandomNumberInRange(-style.noiseAmplitude, style.noiseAmplitude)
        val center = MathUtils.getPointOnCircumference(projectile.location, d, facing + 180f)
        val sidePoint = MathUtils.getPointOnCircumference(center, lateral, facing + 90f)
        val angle = facing + 180f + MathUtils.getRandomNumberInRange(-18f, 18f)

        try {
            val e = TrailEntity()
            e.addNode(Vector2f(0f, 0f))
            e.addNode(Vector2f(len, 0f))
            e.submitNodes()
            e.setLayer(style.layer)
            e.setAdditiveBlend()
            e.setGlobalTimer(0.015f, style.decorLife.coerceAtLeast(0.02f), style.decorLife.coerceAtLeast(0.02f))
            e.setStartWidth(style.decorWidth)
            e.setEndWidth(style.decorWidth * 0.35f)
            e.setMixFactor(2.8f)
            e.setStartColor(1f, 1f, 1f, 0.22f)
            e.setEndColor(1f, 1f, 1f, 0f)
            e.setStartEmissive(1f, 1f, 1f, 0.42f)
            e.setEndEmissive(1f, 1f, 1f, 0f)
            e.setFillStartAlpha(0f)
            e.setFillStartFactor(0.12f)
            e.setFillEndAlpha(0f)
            e.setFillEndFactor(0.88f)
            e.materialData.setDiffuse(sideTrails.firstOrNull()?.materialData?.diffuse ?: smokeTrail?.materialData?.diffuse)
            e.materialData.setEmissive(sideTrails.firstOrNull()?.materialData?.emissive ?: smokeTrail?.materialData?.emissive)
            e.materialData.setColor(style.tailCoreColor)
            e.materialData.setEmissiveColor(style.tailFringeColor)
            e.setStateVanilla(sidePoint, angle)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, e)
            if (state != 0) {
                e.delete()
                return
            }
            decorTrails.add(DecorTrail(e, 0f, style.decorLife.coerceAtLeast(0.02f)))
        } catch (_: Throwable) {
        }
    }

    private fun updateDecor(amount: Float) {
        if (decorTrails.isEmpty()) return
        for (i in decorTrails.size - 1 downTo 0) {
            val d = decorTrails[i]
            if (amount > 0f) d.age += amount
            if (d.entity.hasDelete() || d.age >= d.life) {
                try {
                    if (!d.entity.hasDelete()) d.entity.delete()
                } catch (_: Throwable) {
                }
                decorTrails.removeAt(i)
            }
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
        val t = this.fadeOutSeconds
        for (e in sideTrails) {
            if (!e.hasDelete()) e.setGlobalTimer(0f, 0f, t)
        }
        smokeTrail?.let { if (!it.hasDelete()) it.setGlobalTimer(0f, 0f, t) }
    }

    override fun isFadeOutOver(): Boolean {
        if (!fadeStarted) return false
        val sidesOver = sideTrails.all { it.hasDelete() || it.isGlobalTimerOver }
        val smokeOver = smokeTrail?.let { it.hasDelete() || it.isGlobalTimerOver } ?: true
        return fadeTimer >= fadeOutSeconds && sidesOver && smokeOver && decorTrails.isEmpty()
    }

    override fun delete() {
        for (e in sideTrails) {
            if (!e.hasDelete()) e.delete()
        }
        smokeTrail?.let { if (!it.hasDelete()) it.delete() }
        for (d in decorTrails) {
            if (!d.entity.hasDelete()) d.entity.delete()
        }
        decorTrails.clear()
    }

    companion object {
        fun create(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, style: Style): BoxUtilSmokySideTrailProjectileVisual? {
            if (!style.enabled) return null
            BoxUtilCombatVfx.ensureReady(engine)

            val coreSprite = Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
            val fringeSprite = Global.getSettings().getSprite("graphics/fx/beamfringeb.png")

            fun newTrail(length: Float, tailWidth: Float, headWidth: Float, smoke: Boolean): TrailEntity? {
                return try {
                    val e = TrailEntity()
                    repeat(style.nodeCount.coerceAtLeast(2)) { e.addNode(Vector2f(0f, 0f)) }
                    e.submitNodes()
                    e.setLayer(style.layer)
                    if (!smoke) e.setAdditiveBlend() else e.setNormalBlend()
                    e.setGlobalTimer(0.035f, style.fullSeconds.coerceAtLeast(0.01f), 0f)
                    e.setStartWidth(tailWidth)
                    e.setEndWidth(headWidth)
                    e.setMixFactor(if (smoke) 2.15f else 2.8f)
                    e.setStartColor(1f, 1f, 1f, if (smoke) style.smokeTailAlpha else style.sideTailAlpha)
                    e.setEndColor(1f, 1f, 1f, if (smoke) style.smokeHeadAlpha else style.sideHeadAlpha)
                    e.setStartEmissive(1f, 1f, 1f, if (smoke) style.smokeTailEmissive else style.sideTailEmissive)
                    e.setEndEmissive(1f, 1f, 1f, if (smoke) style.smokeHeadEmissive else style.sideHeadEmissive)
                    e.setFillStartAlpha(0f)
                    e.setFillStartFactor(if (smoke) 0.30f else 0.18f)
                    e.setFillEndAlpha(if (smoke) 0.98f else 0.85f)
                    e.setFillEndFactor(if (smoke) 0.84f else 0.98f)
                    e.setTexturePixels(style.texturePixels.coerceAtLeast(16f))
                    e.setTextureSpeed(style.textureSpeed)
                    e.setFlowWhenPaused(false)
                    e.setUVOffset((Math.random().toFloat() * 2f) - 1f)
                    e.setJitterPower(style.jitterPower.coerceAtLeast(0f))
                    e.setFlick(false)
                    e.setSyncFlick(false)

                    e.materialData.setDiffuse(coreSprite)
                    e.materialData.setEmissive(fringeSprite)
                    if (smoke) {
                        e.materialData.setColor(style.smokeCoreColor)
                        e.materialData.setEmissiveColor(style.smokeFringeColor)
                    } else {
                        e.materialData.setColor(style.headCoreColor)
                        e.materialData.setEmissiveColor(style.headFringeColor)
                    }
                    val baseCore = if (smoke) style.smokeCoreColor else style.headCoreColor
                    val baseFringe = if (smoke) style.smokeFringeColor else style.headFringeColor
                    val tailCoreMul = colorMul(style.tailCoreColor, baseCore)
                    val tailFringeMul = colorMul(style.tailFringeColor, baseFringe)
                    e.setStartColor(tailCoreMul.red, tailCoreMul.green, tailCoreMul.blue, if (smoke) style.smokeTailAlpha else style.sideTailAlpha)
                    e.setStartEmissive(tailFringeMul.red, tailFringeMul.green, tailFringeMul.blue, if (smoke) style.smokeTailEmissive else style.sideTailEmissive)
                    e.materialData.setAlphaToEmissive(0f)
                    e.materialData.setColorToEmissive(0f)
                    e.materialData.setGlowPower(if (smoke) 0.28f else 1f)

                    val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, e)
                    if (state != 0) {
                        e.delete()
                        null
                    } else {
                        e
                    }
                } catch (_: Throwable) {
                    null
                }
            }

            val sideTrails = ArrayList<TrailEntity>(2)
            if (style.sideLinesEnabled) {
                repeat(2) {
                    val e = newTrail(style.sideLength, style.sideTailWidth, style.sideHeadWidth, smoke = false)
                    if (e != null) sideTrails.add(e)
                }
            }
            val smokeTrail = if (style.smokeRibbonEnabled) {
                newTrail(style.smokeLength, style.smokeTailWidth, style.smokeHeadWidth, smoke = true)
            } else {
                null
            }

            if (sideTrails.isEmpty() && smokeTrail == null) {
                return null
            }

            return BoxUtilSmokySideTrailProjectileVisual(engine, style, sideTrails, smokeTrail, ArrayList())
        }

        private fun colorMul(color: Color, base: Color): ColorMul {
            return ColorMul(
                color.red.toFloat() / base.red.coerceAtLeast(1).toFloat(),
                color.green.toFloat() / base.green.coerceAtLeast(1).toFloat(),
                color.blue.toFloat() / base.blue.coerceAtLeast(1).toFloat(),
            )
        }

        private data class ColorMul(val red: Float, val green: Float, val blue: Float)

        private fun computeFacing(projectile: DamagingProjectileAPI): Float {
            val v = projectile.velocity
            return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
                VectorUtils.getFacing(v)
            } else {
                projectile.facing
            }
        }
    }
}

// ==================== 纯 OpenGL 十字光渲染（绕过 BoxUtil TrailEntity）====================

/**
 * 使用 OpenGL 直接绘制的十字光渲染器（单例管理）。
 *
 * 设计目标：
 * - 彻底绕开 BoxUtil TrailEntity 的 UV/朝向路径，避免"东向反转"等底层渲染问题。
 * - 使用 `CombatLayeredRenderingPlugin` 在指定图层绘制纯色"锥形光刺"四边形。
 * - 每条光刺为"从中心到尖端逐渐变窄且透明"的梯形，4 条组成十字。
 */
internal object OglCrossFlareRenderer {

    private const val ENGINE_KEY = "astd_ogl_cross_flare_renderer"

    /**
     * 十字光实例数据（由 [OglCrossFlareProjectileVisual] 持有引用并每帧更新）。
     */
    class FlareInstance(
        var center: Vector2f = Vector2f(),
        var rotationDeg: Float = 0f,
        var alpha: Float = 1f,
        var brightnessMul: Float = 1f,
        val longLength: Float,
        val shortLength: Float,
        val baseWidth: Float,
        val tipWidth: Float,
        val coreColor: Color,
        val fringeColor: Color,
    ) {
        @Volatile
        var expired: Boolean = false
    }

    /**
     * 跟随弹体的“光晕白圈”实例。
     * 说明：使用纯 OpenGL 绘制一个薄环（triangle strip），避免依赖 BoxUtil flare/light。
     */
    class RingInstance(
        var center: Vector2f = Vector2f(),
        var radius: Float,
        var thickness: Float,
        var alpha: Float = 1f,
        var brightnessMul: Float = 1f,
        val color: Color,
        val segments: Int = 64,
    ) {
        @Volatile
        var expired: Boolean = false
    }

    fun register(engine: CombatEngineAPI, inst: FlareInstance) {
        val renderer = getOrCreate(engine)
        renderer.register(inst)
    }

    fun unregister(engine: CombatEngineAPI, inst: FlareInstance) {
        val renderer = engine.customData[ENGINE_KEY] as? Renderer ?: return
        renderer.unregister(inst)
    }

    fun registerRing(engine: CombatEngineAPI, inst: RingInstance) {
        val renderer = getOrCreate(engine)
        renderer.registerRing(inst)
    }

    fun unregisterRing(engine: CombatEngineAPI, inst: RingInstance) {
        val renderer = engine.customData[ENGINE_KEY] as? Renderer ?: return
        renderer.unregisterRing(inst)
    }

    private fun getOrCreate(engine: CombatEngineAPI): Renderer {
        val existing = engine.customData[ENGINE_KEY] as? Renderer
        if (existing != null && !existing.isExpired) return existing

        val r = Renderer()
        r.bindEngine(engine)
        engine.addLayeredRenderingPlugin(r)
        engine.customData[ENGINE_KEY] = r
        return r
    }

    private class Renderer : CombatLayeredRenderingPlugin {

        private var engine: CombatEngineAPI? = null
        private val flares = ArrayList<FlareInstance>(64)
        private val rings = ArrayList<RingInstance>(64)
        @Volatile
        private var expired = false

        fun bindEngine(e: CombatEngineAPI) {
            engine = e
        }

        fun register(inst: FlareInstance) {
            if (!expired) flares.add(inst)
        }

        fun unregister(inst: FlareInstance) {
            inst.expired = true
            flares.remove(inst)
        }

        fun registerRing(inst: RingInstance) {
            if (!expired) rings.add(inst)
        }

        fun unregisterRing(inst: RingInstance) {
            inst.expired = true
            rings.remove(inst)
        }

        override fun init(entity: CombatEntityAPI) {
            if (entity is CombatEngineAPI) engine = entity
        }

        override fun cleanup() {
            flares.clear()
            rings.clear()
            expired = true
            engine = null
        }

        override fun advance(amount: Float) {
            if (expired) return
            // 移除已过期的实例
            flares.removeAll { it.expired }
            rings.removeAll { it.expired }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired) return
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (flares.isEmpty() && rings.isEmpty()) return

            GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_TEXTURE_BIT
            )
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                // 保险：避免引擎/其他插件打开了剔除导致某些角度下 QUAD 被剔掉（表现为“某方向异常/缺一边”）。
                GL11.glDisable(GL11.GL_CULL_FACE)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE) // additive

                for (inst in flares) {
                    if (inst.expired) continue
                    if (inst.alpha <= 0.001f) continue
                    drawCross(inst)
                }

                for (inst in rings) {
                    if (inst.expired) continue
                    if (inst.alpha <= 0.001f) continue
                    drawRing(inst)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }

        /**
         * 绘制一个十字：4 条锥形光刺（梯形四边形）。
         *
         * 每条光刺：
         * - 基部（靠近中心）宽度=baseWidth，颜色=fringeColor，alpha=inst.alpha*brightnessMul
         * - 尖端（远离中心）宽度=tipWidth，颜色=coreColor 更亮但 alpha 趋近 0
         */
        private fun drawCross(inst: FlareInstance) {
            val cx = inst.center.x
            val cy = inst.center.y
            val alpha = inst.alpha.coerceIn(0f, 1f)
            val bm = inst.brightnessMul.coerceIn(0.1f, 10f)
            val a = (alpha * bm).coerceIn(0f, 10f)

            // 4 条光刺的朝向偏移（度）
            val offsets = floatArrayOf(0f, 180f, 90f, 270f)
            val lengths = floatArrayOf(inst.longLength, inst.longLength, inst.shortLength, inst.shortLength)

            for (i in 0..3) {
                val ang = inst.rotationDeg + offsets[i]
                val len = lengths[i]
                // bloom：用多层更宽更淡的锥形四边形叠加，模拟 BoxUtil 的“自动 bloom/泛光”。
                // 这样不依赖贴图/UV，也不会引入方向相关的翻转错觉。
                // 调整：上一版叠加偏激进，容易形成“核爆”白核。这里整体压低，并减少外层权重。
                drawSpoke(cx, cy, ang, len, inst.baseWidth * 3.0f, inst.tipWidth * 3.0f, inst.coreColor, inst.fringeColor, a * 0.10f, 0.006f)
                drawSpoke(cx, cy, ang, len, inst.baseWidth * 1.9f, inst.tipWidth * 1.9f, inst.coreColor, inst.fringeColor, a * 0.22f, 0.010f)
                drawSpoke(cx, cy, ang, len, inst.baseWidth, inst.tipWidth, inst.coreColor, inst.fringeColor, a, 0.02f)
            }
        }

        /** 绘制一个薄环（triangle strip）：外圈 alpha 高、内圈 alpha 略低，制造柔边。 */
        private fun drawRing(inst: RingInstance) {
            val cx = inst.center.x
            val cy = inst.center.y
            val alpha = inst.alpha.coerceIn(0f, 1f)
            val bm = inst.brightnessMul.coerceIn(0.1f, 10f)
            val a = (alpha * bm).coerceIn(0f, 1f)

            val r = inst.radius.coerceAtLeast(0.5f)
            val thick = inst.thickness.coerceAtLeast(0.5f)
            val seg = inst.segments.coerceIn(12, 192)

            val cr = inst.color.red / 255f
            val cg = inst.color.green / 255f
            val cb = inst.color.blue / 255f

            // 需求：白圈更“模糊/柔和”。实现：多层叠加的薄环（越外层越宽越淡）。
            fun drawRingLayer(radius: Float, thickness: Float, alphaMulOuter: Float, alphaMulInner: Float) {
                val inner = (radius - thickness * 0.5f).coerceAtLeast(0.1f)
                val outer = radius + thickness * 0.5f

                GL11.glBegin(GL11.GL_TRIANGLE_STRIP)
                for (i in 0..seg) {
                    val t = (2.0 * PI * (i.toDouble() / seg.toDouble())).toFloat()
                    val dx = cos(t.toDouble()).toFloat()
                    val dy = sin(t.toDouble()).toFloat()

                    GL11.glColor4f(cr, cg, cb, (a * alphaMulOuter * (inst.color.alpha / 255f)).coerceIn(0f, 1f))
                    GL11.glVertex2f(cx + dx * outer, cy + dy * outer)

                    GL11.glColor4f(cr, cg, cb, (a * alphaMulInner * (inst.color.alpha / 255f)).coerceIn(0f, 1f))
                    GL11.glVertex2f(cx + dx * inner, cy + dy * inner)
                }
                GL11.glEnd()
            }

            // 外扩柔边
            drawRingLayer(r, thick * 3.2f, 0.16f, 0.04f)
            drawRingLayer(r, thick * 1.9f, 0.34f, 0.12f)
            // 核心边线
            drawRingLayer(r, thick * 1.0f, 0.70f, 0.28f)
        }

        /**
         * 绘制一条锥形光刺（梯形四边形 + 渐变 alpha）。
         *
         * 顶点顺序（逆时针）：
         *   baseLeft -> tipLeft -> tipRight -> baseRight
         *
         * 基部颜色取 fringeColor（偏暗/边缘色），尖端取 coreColor（更亮但 alpha=0）。
         */
        private fun drawSpoke(
            cx: Float,
            cy: Float,
            angleDeg: Float,
            length: Float,
            baseWidth: Float,
            tipWidth: Float,
            coreColor: Color,
            fringeColor: Color,
            alphaMul: Float,
            tipAlphaFrac: Float,
        ) {
            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            // 垂直于朝向的单位向量
            val px = -dy
            val py = dx

            val bwHalf = baseWidth * 0.5f
            val twHalf = tipWidth * 0.5f

            // 基部两点（靠近中心）
            val blX = cx + px * bwHalf
            val blY = cy + py * bwHalf
            val brX = cx - px * bwHalf
            val brY = cy - py * bwHalf

            // 尖端两点
            val tlX = cx + dx * length + px * twHalf
            val tlY = cy + dy * length + py * twHalf
            val trX = cx + dx * length - px * twHalf
            val trY = cy + dy * length - py * twHalf

            // 基部颜色（使用 fringeColor，alpha 高）
            val br = fringeColor.red / 255f
            val bg = fringeColor.green / 255f
            val bb = fringeColor.blue / 255f
            val ba = (fringeColor.alpha / 255f) * alphaMul

            // 尖端颜色（使用 coreColor，更亮但 alpha 趋近 0）
            val tr = coreColor.red / 255f
            val tg = coreColor.green / 255f
            val tb = coreColor.blue / 255f
            val ta = tipAlphaFrac.coerceIn(0f, 1f) * alphaMul  // 尖端几乎透明

            GL11.glBegin(GL11.GL_QUADS)
            // 基部左
            GL11.glColor4f(br, bg, bb, ba.coerceIn(0f, 1f))
            GL11.glVertex2f(blX, blY)
            // 尖端左
            GL11.glColor4f(tr, tg, tb, ta.coerceIn(0f, 1f))
            GL11.glVertex2f(tlX, tlY)
            // 尖端右
            GL11.glColor4f(tr, tg, tb, ta.coerceIn(0f, 1f))
            GL11.glVertex2f(trX, trY)
            // 基部右
            GL11.glColor4f(br, bg, bb, ba.coerceIn(0f, 1f))
            GL11.glVertex2f(brX, brY)
            GL11.glEnd()
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> {
            return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        }

        override fun getRenderRadius(): Float = 999999f

        override fun isExpired(): Boolean = expired
    }
}

/**
 * 固定大小"十字光/透镜星芒"渲染（BoxUtil TrailEntity 版本）。
 *
 * 说明：类名历史原因保留（曾经是纯 OpenGL 版本），但当前实现会直接复用
 * [FixedCrossFlareProjectileVisual]，即：十字光只走 BoxUtil 的 trail 管线。
 */
internal class OglCrossFlareProjectileVisual(
    private val engine: CombatEngineAPI,
    private val coreColor: Color,
    private val fringeColor: Color,
    private val longLength: Float = 54f,
    private val shortLength: Float = 38f,
    private val baseWidth: Float = 8.5f,
    private val tipWidth: Float = 0.55f,
    private val baseAngleDeg: Float = 0f,
    private val followVelocityFacing: Boolean = false,
    private val rotationDegPerSecond: Float = -12f,
    private val flickerMinMul: Float = 0.78f,
    private val flickerMaxMul: Float = 1.26f,
    private val flickerRetargetMinSeconds: Float = 0.10f,
    private val flickerRetargetMaxSeconds: Float = 0.26f,
    private val flickerLerpSpeed: Float = 4.8f,
    /** 优先使用 BoxUtil 的纯色贴图以避免纹理方向性造成的错觉。 */
    private val preferFlatBoxUtilTexture: Boolean = true,
    private val flatTextureId: String = "BUtil_ONE",
    private val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
) : ProjectileVisual {

    private val impl = FixedCrossFlareProjectileVisual(
        engine = engine,
        coreColor = coreColor,
        fringeColor = fringeColor,
        longLength = longLength,
        shortLength = shortLength,
        baseWidth = baseWidth,
        tipWidth = tipWidth,
        baseAngleDeg = baseAngleDeg,
        followVelocityFacing = followVelocityFacing,
        rotationDegPerSecond = rotationDegPerSecond,
        preferFlatBoxUtilTexture = preferFlatBoxUtilTexture,
        flatTextureId = flatTextureId,
        flickerMinMul = flickerMinMul,
        flickerMaxMul = flickerMaxMul,
        flickerRetargetMinSeconds = flickerRetargetMinSeconds,
        flickerRetargetMaxSeconds = flickerRetargetMaxSeconds,
        flickerLerpSpeed = flickerLerpSpeed,
        layer = layer,
    )

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) = impl.advance(projectile, amount)

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) =
        impl.beginFadeOut(reason, fadeOutSeconds)

    override fun isFadeOutOver(): Boolean = impl.isFadeOutOver()

    override fun delete() = impl.delete()
}

/**
 * 跟随弹体的白色光晕环。
 */
internal class OglHaloRingProjectileVisual(
    private val engine: CombatEngineAPI,
    private val color: Color = Color(255, 255, 255, 140),
    private val radius: Float = 56f,
    private val thickness: Float = 3.0f,
    private val brightnessMul: Float = 1.0f,
) : ProjectileVisual {

    private var inst: OglCrossFlareRenderer.RingInstance? = null
    private var initialized = false

    private var fadeStarted = false
    private var fadeOutSeconds = 0.14f
    private var fadeTimer = 0f

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        val dt = amount.coerceAtLeast(0f)

        if (!initialized) {
            initialized = true
            val newInst = OglCrossFlareRenderer.RingInstance(
                center = Vector2f(projectile.location),
                radius = radius,
                thickness = thickness,
                alpha = 1f,
                brightnessMul = brightnessMul,
                color = color,
                segments = 72,
            )
            OglCrossFlareRenderer.registerRing(engine, newInst)
            inst = newInst
        }

        val instance = inst ?: return
        instance.center.set(projectile.location)

        if (fadeStarted) {
            if (dt > 0f) fadeTimer += dt
            val t = fadeOutSeconds.coerceAtLeast(0.01f)
            instance.alpha = (1f - (fadeTimer / t)).coerceIn(0f, 1f)
        } else {
            instance.alpha = 1f
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeTimer = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        inst?.let { OglCrossFlareRenderer.unregisterRing(engine, it) }
        inst = null
    }
}

/**
 * 使用 BoxUtil TrailEntity 绘制“曳光式锥形光锥”。
 * 设计目标：获得 BoxUtil 自带 bloom 的锥形贴图效果，替代 SpotLight/FlareEntity 的高能量叠加。
 */
