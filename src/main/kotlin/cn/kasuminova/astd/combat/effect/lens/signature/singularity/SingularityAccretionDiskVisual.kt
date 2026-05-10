package cn.kasuminova.astd.combat.effect.lens.signature.singularity

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileTracerManager
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVisual
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.boxutil.units.standard.entity.FlareEntity
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * 奇点导弹“飞行外观”：黑核 + 白描边 + 厚环（深红→鲜粉 / 紫白）+ 雾状边缘。
 *
 * 设计目标：更像“黑洞柔性扭曲/吸积盘”，并移除此前偏“曳光弹”的长拖尾。
 *
 * 约束：尽量仅使用 BoxUtil（SpriteEntity）。
 */
internal class SingularityAccretionDiskVisual private constructor(
    private val engine: CombatEngineAPI,
    private val variant: SingularityDetonationFx.Variant,
    private val layers: List<Layer>,
    private val glowFlare: GlowFlare?,
    private val pinwheelCoreRays: List<PinwheelCoreRay>,
    private val seed: Float,
    private val startTime: Float,
) : ProjectileVisual {

    private val tmpScale = Vector2f(1f, 1f)

    private class GlowFlare(
        val entity: FlareEntity,
        val baseAlpha: Float,
        val baseScaleMul: Float,
        val noisePower: Float,
    )

    /**
     * EVENT_HORIZON 核心“十字白光”的替代实现：
     * - 不用纯正十字贴图拉伸，而用 4 条弧形 TrailEntity 光刺形成轻微旋扭（Pinwheel/Boomerang 观感）。
     * - 通过“统一向局部 +Y 偏移”的曲线形状，让四条射线在整体上呈现同向扭曲。
     */
    private class PinwheelCoreRay(
        val entity: TrailEntity,
        val baseAlpha: Float,
        val baseWidth: Float,
        val tipWidth: Float,
        val facingOffsetDeg: Float,
    )

    private class Layer(
        val entity: SpriteEntity,
        val baseAlpha: Float,
        val rotateSpeedDeg: Float,
        val baseScaleMul: Float,
        val scaleXMul: Float,
        val scaleYMul: Float,
        val facingOffsetDeg: Float,
        val kind: Kind,
    ) {
        enum class Kind {
            BLACK_CORE,
            WHITE_RIM,
            DECOR_RING,
            RED_RING_INNER,
            PINK_RING_OUTER,
            HAZE,
            CENTER_HIGHLIGHT,
            FLARE_H,
            FLARE_V,
            FLARE_H_CORE,
            FLARE_V_CORE,
            FLARE_H_OUTER,
            FLARE_V_OUTER,
            FLARE_D1,
            FLARE_D2,
        }
    }

    private var fadeStarted = false
    private var fadeOutSeconds = 0.18f
    private var fadeAge = 0f

    private var lastLoc: Vector2f? = null
    private var lastFacing: Float = 0f

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        val now = safeTime(engine)
        val t = (now - startTime).coerceAtLeast(0f)

        if (fadeStarted && amount > 0f) fadeAge += amount

        // 弹体离场/回收后，字段访问可能异常：用 lastLoc/lastFacing 定格。
        val loc = safeLocation(projectile) ?: lastLoc
        val facing = safeFacing(projectile) ?: lastFacing
        if (loc != null) {
            lastLoc = loc
            lastFacing = facing
        } else {
            // 完全拿不到位置时无法更新渲染；仍允许淡出计时继续推进。
            return
        }

        val fadeMul = if (!fadeStarted) 1f else {
            val x = (1f - (fadeAge / fadeOutSeconds).coerceIn(0f, 1f))
            // 更自然：三次方（比线性更“慢开始、快结束”）
            x * x * x
        }

        val baseR = estimateBaseRadius(projectile)

        // 轻微“呼吸”：柔性扭曲感（不要过于夸张，避免眩目）
        val breathe = (0.92f + 0.08f * sin((t * 6.0f + seed) * (2f * PI).toFloat())).coerceIn(0.80f, 1.10f)
        // 厚环更明显一点（也更像“吸积盘”）
        val ringBreathe = (0.88f + 0.12f * sin((t * 4.5f + seed * 0.7f) * (2f * PI).toFloat())).coerceIn(0.78f, 1.18f)

        // 环形层的“各向异性呼吸”：让吸积盘更像在被拉扯的柔性圆环（非常克制，避免变成椭圆飞盘）。
        val anis = (1f + 0.06f * sin((t * 2.15f + seed * 1.7f) * (2f * PI).toFloat())).coerceIn(0.88f, 1.12f)

        // 事件视界：中心亮区/十字星芒轻微闪烁（更像 lens flare，而不是静态贴图）。
        val highlightTwinkle = (0.90f + 0.10f * sin((t * 7.2f + seed * 2.2f) * (2f * PI).toFloat())).coerceIn(0.78f, 1.05f)
        val flareTwinkle = (0.82f + 0.18f * sin((t * 9.5f + seed * 3.1f) * (2f * PI).toFloat())).coerceIn(0.65f, 1.12f)

        // 新星：短促红光闪烁（参考图“极亮且不断短闪烁”）。
        val novaFlash = (0.70f + 0.30f * sin((t * 13.0f + seed * 4.7f) * (2f * PI).toFloat())).coerceIn(0.35f, 1.15f)

        for (layer in layers) {
            if (layer.entity.hasDelete()) continue

            val sMul = when (layer.kind) {
                Layer.Kind.BLACK_CORE,
                Layer.Kind.WHITE_RIM -> breathe

                Layer.Kind.RED_RING_INNER,
                Layer.Kind.PINK_RING_OUTER,
                Layer.Kind.HAZE,
                Layer.Kind.DECOR_RING -> ringBreathe

                else -> 1f
            }

            val baseScale = baseR * layer.baseScaleMul * sMul

            // 星芒/中心亮区旋转基准：
            // - 之前做成“镜头光晕”（不跟随 facing），但会导致与背景装饰环(DECOR_RING)的图案角度不同步。
            // - EVENT_HORIZON 需要“背景与主星芒同步”，因此改为与 DECOR_RING 使用同一套基准：seed*360 + facing + t*DECOR_ROT_SPEED。
            val facingForLayer = when (layer.kind) {
                Layer.Kind.CENTER_HIGHLIGHT,
                Layer.Kind.FLARE_H,
                Layer.Kind.FLARE_V,

                Layer.Kind.FLARE_H_CORE,
                Layer.Kind.FLARE_V_CORE,
                Layer.Kind.FLARE_H_OUTER,
                Layer.Kind.FLARE_V_OUTER -> 0f

                Layer.Kind.FLARE_D1,
                Layer.Kind.FLARE_D2 -> 0f

                else -> facing
            }

            val syncBaseRotForEventHorizonFlare =
                if (variant == SingularityDetonationFx.Variant.EVENT_HORIZON) (facing + t * EVENT_HORIZON_DECOR_ROT_SPEED_DEG) else 0f

            val flareFacing = when (layer.kind) {
                Layer.Kind.CENTER_HIGHLIGHT,
                Layer.Kind.FLARE_H,
                Layer.Kind.FLARE_V,

                Layer.Kind.FLARE_H_CORE,
                Layer.Kind.FLARE_V_CORE,
                Layer.Kind.FLARE_H_OUTER,
                Layer.Kind.FLARE_V_OUTER,

                Layer.Kind.FLARE_D1,
                Layer.Kind.FLARE_D2 -> syncBaseRotForEventHorizonFlare

                else -> facingForLayer
            }

            val rot = (t * layer.rotateSpeedDeg + seed * 360f + flareFacing + layer.facingOffsetDeg)

            val anisX = when (layer.kind) {
                Layer.Kind.RED_RING_INNER,
                Layer.Kind.PINK_RING_OUTER -> anis

                Layer.Kind.HAZE -> 1f + (anis - 1f) * 0.60f

                else -> 1f
            }
            val anisY = 1f / anisX

            // 不依赖 instance 数据（TBO/refresh 状态在不同机器/初始化时序下容易踩坑）：
            // 直接用 entity 的 modelMatrix 驱动位置/旋转/缩放，并把淡出乘进 material alpha。
            tmpScale.x = baseScale * layer.scaleXMul * anisX
            tmpScale.y = baseScale * layer.scaleYMul * anisY
            try {
                layer.entity.setStateVanilla(loc, rot, tmpScale)
            } catch (_: Throwable) {
            }

            applyLayerColor(layer, fadeMul, flareTwinkle, highlightTwinkle, novaFlash)
        }

        // 事件视界额外红光：用 FlareEntity 做更自然的“发光/散射”，避免贴图雾层看起来像红盘。
        glowFlare?.let { gf ->
            val e = gf.entity
            if (!e.hasDelete()) {
                val baseScale = baseR * gf.baseScaleMul
                val s = (0.92f + 0.08f * sin((t * 5.0f + seed * 1.9f) * (2f * PI).toFloat())).coerceIn(0.85f, 1.10f)
                // globalAlpha 对 flare 的强度影响更直观；这里提高“红光占比”但保持克制。
                val a = (gf.baseAlpha * fadeMul * (0.90f + 0.10f * highlightTwinkle)).coerceIn(0f, 1f)
                try {
                    e.setStateVanilla(loc, 0f)
                    e.setSize(baseScale * 2f * s, baseScale * 2f * s)
                    e.setGlobalAlpha(a)
                    e.setNoisePower(gf.noisePower)
                } catch (_: Throwable) {
                }
            }
        }

        // EVENT_HORIZON：核心“旋扭十字”白光（Pinwheel）。
        // 目标：避免中心呈现完美十字硬边；改为带轻微弧度/非对称的四条射线。
        if (pinwheelCoreRays.isNotEmpty() && variant == SingularityDetonationFx.Variant.EVENT_HORIZON) {
            val aCore = (0.95f * fadeMul * flareTwinkle).coerceIn(0f, 1f)
            // 重要：主星芒 SpriteEntity 的旋转包含 seed*360（见 rot 计算），如果 Pinwheel 不带 seed 偏移，
            // 就会出现“背景角度与主星芒角度不同步/像重复了一套星芒”的观感。
            // EVENT_HORIZON 需要与 DECOR_RING 同步：再加上 facing 与 DECOR 的旋转速度。
            val lensBaseRot = seed * 360f + facing + t * EVENT_HORIZON_DECOR_ROT_SPEED_DEG
            for (r in pinwheelCoreRays) {
                val e = r.entity
                if (e.hasDelete()) continue

                try {
                    // 不跟随弹体朝向旋转：更像 lens flare。
                    e.setStateVanilla(loc, lensBaseRot + r.facingOffsetDeg)
                } catch (_: Throwable) {
                }

                // 使用 TrailEntity 的 start/end 颜色 alpha 做“中心亮、末端淡”的梯度，淡出直接乘进 alpha。
                try {
                    e.setStartColor(1f, 1f, 1f, (r.baseAlpha * aCore).coerceIn(0f, 1f))
                    // 末端更快消失：Pinwheel 只负责核心扭曲，不应“延伸成第二套长十字”。
                    e.setEndColor(1f, 1f, 1f, (r.baseAlpha * aCore * 0.015f).coerceIn(0f, 1f))
                    e.setStartEmissive(1f, 1f, 1f, (r.baseAlpha * aCore * 0.95f).coerceIn(0f, 1f))
                    e.setEndEmissive(1f, 1f, 1f, 0f)
                } catch (_: Throwable) {
                }

                // 轻微呼吸：只动宽度，不动长度（避免每帧 submitNodes）。
                val wMul = (0.92f + 0.08f * flareTwinkle).coerceIn(0.85f, 1.08f)
                try {
                    e.setStartWidth(r.baseWidth * wMul)
                    e.setEndWidth(r.tipWidth)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (fadeStarted) return
        fadeStarted = true
        this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
        fadeAge = 0f
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeAge >= fadeOutSeconds
    }

    override fun delete() {
        for (l in layers) {
            try {
                if (!l.entity.hasDelete()) l.entity.delete()
            } catch (_: Throwable) {
            }
        }

        for (r in pinwheelCoreRays) {
            try {
                if (!r.entity.hasDelete()) r.entity.delete()
            } catch (_: Throwable) {
            }
        }

        try {
            glowFlare?.entity?.let { if (!it.hasDelete()) it.delete() }
        } catch (_: Throwable) {
        }
    }

    private fun applyLayerColor(layer: Layer, fadeMul: Float, flareTwinkle: Float, highlightTwinkle: Float, novaFlash: Float) {
        val m = layer.entity.materialData

        // 直接把 fadeMul 乘进 material alpha（避免依赖 instanceTimerOverride / haveValidInstanceData 门槛）。
        val alpha = (layer.baseAlpha.coerceIn(0f, 1f) * fadeMul.coerceIn(0f, 1f)).coerceIn(0f, 1f)

        // 黑核：只用 diffuse（非 additive），颜色直接写到 material color。
        if (layer.kind == Layer.Kind.BLACK_CORE) {
            // 黑核不宜太透明，否则“黑洞”不成立。
            // 注意：淡出由 instanceTimerOverride(fadeMul) 负责，这里只写“层级不透明度”。
            m.setColor(0f, 0f, 0f, (alpha * 0.98f).coerceIn(0f, 1f))
            m.setEmissiveColor(0f, 0f, 0f, 0f)
            m.setAdditionEmissive(false)
            m.setEmissiveState(0f, 0f, 0f)
            return
        }

        // 其它层：改为使用 diffuse + additive 强制可见。
        // 背景：当前问题表现为“只剩黑核 + 扭曲”，怀疑 BoxUtil emissive（material emissiveColor 等）
        // 在某些渲染/实例数据路径下没有生效。
        // diffuse 路径已被黑核验证是稳定的，因此这里用 diffuse 作为主显示，避免 emissive 路径导致整层不可见。
        // 但在 fixed instance data 路径稳定后，我们可以按需开启 emissive/glowPower 来做更“bloom”的发光。
        m.setAdditionEmissive(false)
        m.setEmissiveColor(0f, 0f, 0f, 0f)
        m.setEmissiveState(0f, 0f, 0f)
        m.setIgnoreIllumination(true)

        when (variant) {
            SingularityDetonationFx.Variant.NOVA -> {
                when (layer.kind) {
                    Layer.Kind.WHITE_RIM -> {
                        m.setColor(1f, 1f, 1f, (alpha * 0.88f * novaFlash).coerceIn(0f, 1f))
                    }

                    Layer.Kind.RED_RING_INNER -> {
                        m.setColor(1.0f, 0.12f, 0.12f, (alpha * 0.78f * novaFlash).coerceIn(0f, 1f))
                    }

                    Layer.Kind.PINK_RING_OUTER -> {
                        m.setColor(1.0f, 0.20f, 0.55f, (alpha * 0.70f * novaFlash).coerceIn(0f, 1f))
                    }

                    Layer.Kind.HAZE -> {
                        m.setColor(0.85f, 0.08f, 0.12f, (alpha * 0.35f * novaFlash).coerceIn(0f, 1f))
                    }

                    Layer.Kind.CENTER_HIGHLIGHT -> {
                        // NOVA：红色“爆闪”中心高光（贴近黑核但不盖住黑核）
                        val a = (alpha * 0.80f * highlightTwinkle * novaFlash).coerceIn(0f, 1f)
                        m.setColor(1.0f, 0.22f, 0.30f, a)
                    }

                    Layer.Kind.FLARE_H,
                    Layer.Kind.FLARE_V -> {
                        // NOVA：红色十字星芒，alpha 更克制
                        val a = (alpha * 0.48f * flareTwinkle * novaFlash).coerceIn(0f, 1f)
                        m.setColor(1.0f, 0.12f, 0.16f, a)
                    }

                    Layer.Kind.FLARE_D1,
                    Layer.Kind.FLARE_D2 -> {
                        val a = (alpha * 0.36f * flareTwinkle * novaFlash).coerceIn(0f, 1f)
                        m.setColor(1.0f, 0.14f, 0.22f, a)
                    }

                    else -> {
                        m.setColor(0f, 0f, 0f, 0f)
                    }
                }
            }

            SingularityDetonationFx.Variant.EVENT_HORIZON -> {
                when (layer.kind) {
                    Layer.Kind.WHITE_RIM -> {
                        // 需求：移除紫色背景/偏色；描边保持偏暖白。
                        // 同时收敛强度，避免叠加后形成“额外一圈”的线性观感。
                        m.setColor(1.0f, 0.98f, 0.94f, (alpha * 0.68f).coerceIn(0f, 1f))
                    }

                    Layer.Kind.DECOR_RING -> {
                        // 外圈装饰：白环（参考“引力坍缩炮”整圈装饰）。
                        // 需求：整体红色发光占比更高，但不要表现成“红色环在发光”。
                        // 因此这里环本体保持偏白，仅给非常克制的白色发光。
                        // 需求：降低外部白圈的粗细度和发光大小（-75%）
                        val a = (alpha * 0.20f).coerceIn(0f, 1f)
                        m.setColor(1.0f, 1.0f, 1.0f, a)

                        m.setAdditionEmissive(true)
                        m.setAlphaToEmissive(0f)
                        m.setColorToEmissive(0f)
                        m.setGlowPower(0.25f)
                        val ea = (a * 0.09f * (0.85f + 0.15f * highlightTwinkle)).coerceIn(0f, 1f)
                        m.setEmissiveColor(1.0f, 0.98f, 0.94f, ea)
                    }

                    Layer.Kind.RED_RING_INNER -> {
                        // 内盘：改为更“热”的红橙色，而不是紫圈。
                        // 让它更像吸积盘热边缘，并给中心红晕提供过渡。
                        m.setColor(1.00f, 0.18f, 0.10f, (alpha * 0.52f).coerceIn(0f, 1f))
                    }

                    Layer.Kind.PINK_RING_OUTER -> {
                        // 外圈：做很淡的“透镜边缘”，避免像第二个紫色实心环。
                        m.setColor(0.70f, 0.45f, 1.00f, (alpha * 0.18f).coerceIn(0f, 1f))
                    }

                    Layer.Kind.HAZE -> {
                        // 需求：移除紫色背景，改为纯红背景。
                        // 这里保留一层非常淡的红雾，帮助把“红晕”融进整体。
                        val a = (alpha * 0.24f).coerceIn(0f, 1f)
                        m.setColor(0.92f, 0.10f, 0.05f, a)

                        m.setAdditionEmissive(true)
                        m.setAlphaToEmissive(0f)
                        m.setColorToEmissive(0f)
                        m.setGlowPower(1f)
                        m.setEmissiveColor(1.0f, 0.12f, 0.06f, (a * 0.90f).coerceIn(0f, 1f))
                    }

                    Layer.Kind.CENTER_HIGHLIGHT -> {
                        // 需求：黑核周围更多红光。这里把中心高光改为偏红的“热晕”，而星芒仍由 FLARE_* 提供白光覆盖。
                        // 注意：黑核会盖住中心区域，实际看到的是红晕边缘。
                        // 需求追加：红光大小 +100%，并且整体尽量“纯红”。
                        val a = (alpha * 1.65f * highlightTwinkle).coerceIn(0f, 1f)
                        m.setColor(1.00f, 0.14f, 0.07f, a)

                        m.setAdditionEmissive(true)
                        m.setAlphaToEmissive(0f)
                        m.setColorToEmissive(0f)
                        m.setGlowPower(1f)
                        m.setEmissiveColor(1.0f, 0.10f, 0.05f, (a * 0.95f).coerceIn(0f, 1f))
                    }

                    Layer.Kind.FLARE_H,
                    Layer.Kind.FLARE_V -> {
                        // 大十字星芒：主白光覆盖层（更细更长），alpha 适中。
                        val a = (alpha * 0.58f * flareTwinkle).coerceIn(0f, 1f)
                        m.setColor(1.0f, 1.0f, 1.0f, a)
                    }

                    Layer.Kind.FLARE_H_CORE,
                    Layer.Kind.FLARE_V_CORE -> {
                        // 核心十字：更“实”的白光（中心更厚、更亮）。
                        val a = (alpha * 1.00f * flareTwinkle).coerceIn(0f, 1f)
                        m.setColor(1.0f, 1.0f, 1.0f, a)

                        m.setAdditionEmissive(true)
                        m.setAlphaToEmissive(0f)
                        m.setColorToEmissive(0f)
                        m.setGlowPower(1f)
                        m.setEmissiveColor(1.0f, 1.0f, 1.0f, (a * 0.95f).coerceIn(0f, 1f))
                    }

                    Layer.Kind.FLARE_H_OUTER,
                    Layer.Kind.FLARE_V_OUTER -> {
                        // 外延十字：更虚、更长；按需求把外部红光回滚为白光。
                        val a = (alpha * 0.72f * flareTwinkle).coerceIn(0f, 1f)
                        m.setColor(1.0f, 1.0f, 1.0f, a)

                        m.setAdditionEmissive(true)
                        m.setAlphaToEmissive(0f)
                        m.setColorToEmissive(0f)
                        m.setGlowPower(1f)
                        m.setEmissiveColor(1.0f, 1.0f, 1.0f, (a * 0.92f).coerceIn(0f, 1f))
                    }

                    Layer.Kind.FLARE_D1,
                    Layer.Kind.FLARE_D2 -> {
                        // 小十字（对角）更克制：更像两条细线而不是“第二个大十字”。
                        val a = (alpha * 0.16f * flareTwinkle).coerceIn(0f, 1f)
                        m.setColor(1.0f, 0.95f, 0.92f, a)
                    }

                    else -> {
                        m.setColor(0f, 0f, 0f, 0f)
                    }
                }
            }
        }
    }

    private fun estimateBaseRadius(projectile: DamagingProjectileAPI): Float {
        // collisionRadius 对各种弹体都相对靠谱；做一个下限避免过小不可见。
        val r = try {
            projectile.collisionRadius
        } catch (_: Throwable) {
            0f
        }

        // 这不是物理 hitbox，仅是视觉；给一点额外放大，让“球体”可读。
        val base = max(10f, r * 1.10f)

        return when (variant) {
            SingularityDetonationFx.Variant.NOVA -> base * 1.05f
            // 需求：事件视界体积约为新星的 3 倍（线性尺寸约 1.44x）；这里更偏“视觉夸张”。
            SingularityDetonationFx.Variant.EVENT_HORIZON -> base * 1.60f
        }
    }

    private fun safeLocation(projectile: DamagingProjectileAPI): Vector2f? {
        return try {
            projectile.location
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeFacing(projectile: DamagingProjectileAPI): Float? {
        return try {
            projectile.facing
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeTime(engine: CombatEngineAPI): Float {
        return try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }
    }

    companion object {

        // EVENT_HORIZON：装饰环的旋转速度。
        // 视觉约束：需要“背景角度与主星芒角度同步”，因此 flare/pinwheel 也会使用同样的基准旋转。
        private const val EVENT_HORIZON_DECOR_ROT_SPEED_DEG = -38f

        // EVENT_HORIZON：Pinwheel 核心光刺长度倍率（相对原先与 core 十字同长的实现）。
        // 目标：只扭曲“核心白光”，避免与外延大十字叠出 8 条长光线。
        private const val EVENT_HORIZON_PINWHEEL_LENGTH_MUL = 0.34f

        private const val TEX_CIRCLE_MASK = "graphics/fx/circle_mask_hires.png"
        private const val TEX_RING_THIN = "graphics/fx/astd_generated_ring.png"

        // 重要：wormhole_ring_* 系列贴图自身带明显蓝色 RGB（不是“白色 mask”），
        // 会把我们想要的红/紫色辉光在颜色相乘时“乘没”，表现为只剩一层偏蓝的光圈。
        // 改用更中性的白色环，让材质颜色真正决定颜色。
        private const val TEX_RING_THICK = "graphics/fx/shields256ringd.png"

        private const val TEX_HAZE = "graphics/fx/fog_circle2.png"
        private const val TEX_CENTER_HIGHLIGHT = "graphics/fx/star_halo.png"
        private const val TEX_LINEAR = "graphics/fx/particlealpha64linear.png"
        // 试过用 beamfringe 做星芒会出现“纹理边缘/分段感”；保留常量以便以后实验，但当前 EVENT_HORIZON 回滚使用 TEX_LINEAR。
        private const val TEX_FLARE_LINEAR_SOFT = "graphics/fx/beamfringe.png"

        // TrailEntity 更适合用 beamcore/beamfringe 一类的“横向 beam”纹理做 UV 映射。
        private const val TEX_BEAM_CORE = "graphics/fx/beamcore.png"

        @Volatile
        private var texturesPreloaded = false

        private fun ensureTexturesLoaded() {
            if (texturesPreloaded) return
            texturesPreloaded = true
            val settings = Global.getSettings()
            // 关键：BoxUtil 的 MaterialData 会把 sprite.getTextureId() 缓存到 glTex[]。
            // 如果此时 textureId 还是 0（贴图未 resident），后续即使贴图加载了也不会自动刷新，导致整层采样为透明。
            // 因此必须在 setDiffuseSprite/setEmissiveSprite 前强制 loadTexture。
            val toLoad = arrayOf(
                TEX_CIRCLE_MASK,
                TEX_RING_THIN,
                TEX_RING_THICK,
                TEX_HAZE,
                TEX_CENTER_HIGHLIGHT,
                TEX_LINEAR,
                TEX_FLARE_LINEAR_SOFT,
                TEX_BEAM_CORE,
            )
            for (path in toLoad) {
                try {
                    settings.loadTexture(path)
                } catch (_: Throwable) {
                    // 忽略：某些环境下重复 load 或路径差异会抛异常；只要后续 getSprite 能拿到有效 textureId 即可。
                }
            }
        }

        private fun getSpriteEnsured(path: String): com.fs.starfarer.api.graphics.SpriteAPI {
            ensureTexturesLoaded()
            val settings = Global.getSettings()
            // 再保险：避免 ensureTexturesLoaded 的列表漏项。
            try { settings.loadTexture(path) } catch (_: Throwable) {}
            val s = settings.getSprite(path)
            // 触发一次 textureId 访问，避免某些实现惰性初始化。
            try { s.textureId } catch (_: Throwable) {}
            return s
        }

        fun create(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, variant: SingularityDetonationFx.Variant): ProjectileVisual? {
            // BoxUtil 未 ready 时返回 null，让 TracerManager 进入 pending 重试。
            BoxUtilCombatVfx.ensureReady(engine)

            // 贴图预加载：避免 BoxUtil MaterialData 缓存到 textureId=0 导致整层永远透明。
            ensureTexturesLoaded()

            val now = try {
                engine.getTotalElapsedTime(false)
            } catch (_: Throwable) {
                0f
            }

            val seed = abs((projectile.hashCode() * 0.000233f) % 1f)

            val layers = ArrayList<Layer>(12)

            var glowFlare: GlowFlare? = null

            var pinwheelCoreRays: List<PinwheelCoreRay> = emptyList()

            fun submitFixedInstanceDataCompat(entity: org.boxutil.base.api.InstanceRenderAPI, instanceCount: Int): Byte {
                if (instanceCount < 1) return BoxEnum.STATE_FAILED_OTHER
                return try {
                    val memory = entity.instanceDataMemory
                    val needAlloc = memory == null || !memory.is_type_fixed()
                    if (needAlloc) {
                        entity.mallocInstance(InstanceType.FIXED_2D, instanceCount)
                        entity.setInstanceDataRefreshIndex(0)
                        entity.setInstanceDataRefreshOffset(0)
                        entity.setInstanceDataRefreshAllFromCurrentIndex()
                    }

                    val after = entity.instanceDataMemory
                    if (after == null || !after.is_type_fixed()) return BoxEnum.STATE_FAILED_OTHER

                    entity.submitInstance()
                    BoxEnum.STATE_SUCCESS
                } catch (_: Throwable) {
                    BoxEnum.STATE_FAILED_OTHER
                }
            }

            fun initFixedOneInstance(entity: org.boxutil.base.api.InstanceRenderAPI): Boolean {
                // 与 SpriteEntity 相同：必须有 valid instance data，否则 draw 0。
                val inst = Instance2Data().apply {
                    setLocation(0f, 0f)
                    setFacing(0f)
                    setTurnRate(0f)
                    setScale(1f, 1f)
                    setTimer(0f, 99999f, 0f)
                    setColor(255, 255, 255, 255)
                    setEmissiveColor(255, 255, 255, 255)
                    try {
                        setFixedInstanceAlpha(1f, BoxEnum.TIMER_FULL)
                    } catch (_: Throwable) {
                    }
                }

                val apiList: MutableList<InstanceDataAPI> = mutableListOf(inst)
                val stSet = try {
                    entity.setInstanceData(apiList, 0f, 99999f, 0f)
                } catch (_: Throwable) {
                    BoxEnum.STATE_FAILED_OTHER
                }
                if (stSet != BoxEnum.STATE_SUCCESS) return false

                try {
                    entity.setRenderingCount(1)
                    entity.setInstanceDataRefreshIndex(0)
                    entity.setInstanceDataRefreshSize(1)
                    entity.setInstanceTimerOverride(1f, BoxEnum.TIMER_FULL)
                } catch (_: Throwable) {
                }

                val stSubmit = submitFixedInstanceDataCompat(entity, apiList.size)
                return stSubmit == BoxEnum.STATE_SUCCESS && entity.haveValidInstanceData() && entity.getValidInstanceDataCount() >= 1
            }

            fun addLayer(
                kind: Layer.Kind,
                diffusePath: String? = null,
                diffuseSprite: com.fs.starfarer.api.graphics.SpriteAPI? = null,
                emissiveSprite: com.fs.starfarer.api.graphics.SpriteAPI? = null,
                additive: Boolean,
                baseAlpha: Float,
                rotateSpeedDeg: Float,
                baseScaleMul: Float,
                scaleXMul: Float = 1f,
                scaleYMul: Float = 1f,
                facingOffsetDeg: Float,
                layer: CombatEngineLayers,
            ): Boolean {
                val e = try {
                    SpriteEntity()
                } catch (_: Throwable) {
                    return false
                }

                try {
                    if (additive) e.setAdditiveBlend()
                } catch (_: Throwable) {
                }

                // SpriteEntity 的渲染是“实例化绘制”，即使只有 1 个 sprite，也必须有 valid instance data。
                // 否则 glDraw() 会 draw 0（完全不可见）。
                val inst = Instance2Data().apply {
                    setLocation(0f, 0f)
                    setFacing(0f)
                    setTurnRate(0f)
                    setScale(1f, 1f)
                    setTimer(0f, 99999f, 0f)
                    setColor(255, 255, 255, 255)
                    setEmissiveColor(255, 255, 255, 255)
                    // 固定实例数据：把 alpha 状态写入 rawAlpha（shader 会做 TIMER_* 的十位解码）。
                    // 这样即使 globalTimerAlpha override 失效，也不至于整层 alpha=0。
                    try {
                        setFixedInstanceAlpha(1f, BoxEnum.TIMER_FULL)
                    } catch (_: Throwable) {
                    }
                }

                val apiList: MutableList<InstanceDataAPI> = mutableListOf(inst)

                val stSet = try {
                    e.setInstanceData(apiList, 0f, 99999f, 0f)
                } catch (_: Throwable) {
                    BoxEnum.STATE_FAILED_OTHER
                }
                if (stSet != BoxEnum.STATE_SUCCESS) {
                    try { e.delete() } catch (_: Throwable) {}
                    return false
                }

                // 固定为 1 个 instance（我们用 entity 的 modelMatrix 来做实际的旋转/缩放，不依赖 per-frame submitInstanceData）。
                try {
                    e.setRenderingCount(1)
                    e.setInstanceDataRefreshIndex(0)
                    e.setInstanceDataRefreshSize(1)
                    // 统一 alpha 入口：用 instanceTimerOverride 直接喂给 shader，让 alpha=1（再由 material alpha 控制亮度/淡出）。
                    e.setInstanceTimerOverride(1f, BoxEnum.TIMER_FULL)
                } catch (_: Throwable) {
                }

                // 关键：使用 fixed instance data 路径。
                // submitInstanceData() 依赖 CRM 每帧 sysRefreshInstanceData() 生成最终矩阵，且在不同机器/配置下更脆弱；
                // fixed 路径会直接把最终矩阵/scale 写入 TBO，渲染更稳定。
                val stSubmit = submitFixedInstanceDataCompat(e, apiList.size)
                if (stSubmit != BoxEnum.STATE_SUCCESS || !e.haveValidInstanceData() || e.getValidInstanceDataCount() < 1) {
                    try { e.delete() } catch (_: Throwable) {}
                    return false
                }

                try {
                    e.setLayer(layer)
                } catch (_: Throwable) {
                }

                try {
                    val d = diffuseSprite ?: (diffusePath?.let { getSpriteEnsured(it) })
                    if (d != null) {
                        e.setDiffuseSprite(d)
                        // 关键：SpriteEntity 默认 UV 为 0..1。
                        // 但 Starsector 的很多 SpriteAPI 来自图集子区域（texX/texY/texW/texH != 0..1），
                        // 不同步 UV 会采样到“图集上的错误区域（通常是透明）”，表现为整层完全不可见。
                        // circle_mask_hires 这类独立贴图恰好不需要同步，所以黑核能看到而其它环/雾看不到。
                        try {
                            e.setUVStart(d.texX, d.texY)
                            e.setUVEnd(d.texX + d.texWidth, d.texY + d.texHeight)
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }

                try {
                    if (emissiveSprite != null) {
                        e.setEmissiveSprite(emissiveSprite)
                    } else {
                        // 默认：emissive=diffuse，方便用 emissiveColor 做发光（diffuseColor 置 0）
                        val d = diffuseSprite ?: (diffusePath?.let { getSpriteEnsured(it) })
                        if (d != null) e.setEmissiveSprite(d)
                    }
                } catch (_: Throwable) {
                }

                val st = try {
                    BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SPRITE, e)
                } catch (_: Throwable) {
                    -1
                }

                if (st != 0) {
                    try {
                        e.delete()
                    } catch (_: Throwable) {
                    }
                    return false
                }

                layers.add(
                    Layer(
                        entity = e,
                        baseAlpha = baseAlpha,
                        rotateSpeedDeg = rotateSpeedDeg,
                        baseScaleMul = baseScaleMul,
                        scaleXMul = scaleXMul,
                        scaleYMul = scaleYMul,
                        facingOffsetDeg = facingOffsetDeg,
                        kind = kind,
                    )
                )
                return true
            }

            when (variant) {
                SingularityDetonationFx.Variant.NOVA -> {
                    // 厚环（内层偏深）
                    if (!addLayer(
                            kind = Layer.Kind.RED_RING_INNER,
                            diffusePath = TEX_RING_THICK,
                            emissiveSprite = null,
                            additive = true,
                            baseAlpha = 0.95f,
                            rotateSpeedDeg = -92f,
                            baseScaleMul = 1.55f,
                            scaleXMul = 1f,
                            scaleYMul = 1f,
                            facingOffsetDeg = 0f,
                            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                        )
                    ) {
                        layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                        return null
                    }

                    // 厚环（外层偏鲜粉/亮紫）
                    if (!addLayer(
                            kind = Layer.Kind.PINK_RING_OUTER,
                            diffusePath = TEX_RING_THICK,
                            emissiveSprite = null,
                            additive = true,
                            baseAlpha = 0.80f,
                            rotateSpeedDeg = 74f,
                            baseScaleMul = 1.95f,
                            scaleXMul = 1f,
                            scaleYMul = 1f,
                            facingOffsetDeg = 18f,
                            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                        )
                    ) {
                        layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                        return null
                    }

                    // 雾状边缘：淡淡一层
                    if (!addLayer(
                            kind = Layer.Kind.HAZE,
                            diffusePath = TEX_HAZE,
                            emissiveSprite = null,
                            additive = true,
                            baseAlpha = 0.55f,
                            rotateSpeedDeg = 18f,
                            baseScaleMul = 2.30f,
                            scaleXMul = 1f,
                            scaleYMul = 1f,
                            facingOffsetDeg = seed * 360f,
                            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                        )
                    ) {
                        layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                        return null
                    }
                }

                SingularityDetonationFx.Variant.EVENT_HORIZON -> {
                    // 调整：暂时去掉红色背景（不创建 HAZE）。

                    // 使用 BoxUtil FlareEntity 做红色发光：比雾贴图更像“发光/散射”，不容易看成红盘。
                    run {
                        val f = try { FlareEntity() } catch (_: Throwable) { null }
                        if (f == null) {
                            layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                            return null
                        }

                        try {
                            f.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
                            f.setAdditiveBlend()
                            // smooth：柔和衰减，避免“硬盘边缘”
                            f.setSmooth()
                            f.setFlick(false)
                            f.setSyncFlick(false)
                            f.setGlowPower(1f)
                            // 注意：FlareEntity.setCoreColor(float...) 在 BoxUtil 里实现有误，必须用 Color/Vector4f 版本。
                            // 优化：降低中心“实心盘”感（core 更淡），增加外缘散射（fringe 更明显 + 更高噪声）。
                            f.setCoreColor(Color(255, 120, 80, 22))
                            f.setFringeColor(Color(255, 35, 20, 70))
                            f.setGlobalAlpha(0.18f)
                            f.setNoisePower(0.26f)
                            f.setFlickMixValue(0.70f)
                        } catch (_: Throwable) {
                        }

                        if (!initFixedOneInstance(f)) {
                            try { f.delete() } catch (_: Throwable) {}
                            layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                            return null
                        }

                        val st = try {
                            BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_FLARE, f)
                        } catch (_: Throwable) {
                            -1
                        }
                        if (st != 0) {
                            try { f.delete() } catch (_: Throwable) {}
                            layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                            return null
                        }

                        glowFlare = GlowFlare(
                            entity = f,
                            baseAlpha = 0.22f,
                            // 优化：降低红色光大小（-50%）
                            baseScaleMul = 2.05f,
                            noisePower = 0.18f,
                        )
                    }

                    // 外圈装饰白环：整圈，且不做各向异性拉伸，避免出现椭圆观感。
                    if (!addLayer(
                            kind = Layer.Kind.DECOR_RING,
                            diffusePath = TEX_RING_THIN,
                            emissiveSprite = null,
                            additive = true,
                            // 需求：降低外部白圈的粗细度与发光存在感（整体 -75% 左右）
                            baseAlpha = 0.24f,
                            rotateSpeedDeg = -38f,
                            baseScaleMul = 3.05f,
                            scaleXMul = 1f,
                            scaleYMul = 1f,
                            facingOffsetDeg = 0f,
                            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                        )
                    ) {
                        layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                        return null
                    }
                }
            }

            // 事件视界：中心红晕 + 白色十字星芒覆盖
            if (variant == SingularityDetonationFx.Variant.EVENT_HORIZON) {
                // 调整：暂时去掉红色背景（不创建 CENTER_HIGHLIGHT）。

                // 核心十字：按需求改为“Polar Twist/Pinwheel”风格。
                // 尝试用 4 条弧形 TrailEntity 光刺替换纯十字 SpriteEntity；失败则回退原方案。
                run {
                    val baseR = run {
                        val r = try { projectile.collisionRadius } catch (_: Throwable) { 0f }
                        max(10f, r * 1.10f) * 1.60f
                    }

                    fun createOneRay(facingOffsetDeg: Float): PinwheelCoreRay? {
                        val e = try { TrailEntity() } catch (_: Throwable) { null } ?: return null

                        // 让射线有“轻微弧度/旋扭”：在局部空间给中段/末端一个向 +Y 的偏移。
                        // 注：这里不做每帧 submitNodes（性能/稳定性），因此长度固定为创建时估计值。
                        // 重要：Pinwheel 只作为“核心白光扭曲”，不能与外延大十字一样长，否则会叠出 8 条长光线。
                        val length = baseR * 6.80f * 1.15f * EVENT_HORIZON_PINWHEEL_LENGTH_MUL
                        val midX = length * 0.55f
                        // 长度缩短后，为保证仍可读的“旋扭”，适度提高曲率比例。
                        val midY = length * 0.115f
                        val tipY = length * 0.230f
                        try {
                            e.addNode(Vector2f(0f, 0f))
                            e.addNode(Vector2f(midX, midY))
                            e.addNode(Vector2f(length, tipY))
                            e.submitNodes()
                        } catch (_: Throwable) {
                            try { e.delete() } catch (_: Throwable) {}
                            return null
                        }

                        try {
                            e.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
                            e.setAdditiveBlend()
                            e.setGlobalTimer(0f, 99999f, 0f)
                            e.setMixFactor(0.65f)
                        } catch (_: Throwable) {
                        }

                        // 核心光刺：更厚，但略收敛，避免在远端形成“第二套长十字”的轮廓。
                        val baseWidth = baseR * 6.80f * 0.042f * 0.80f
                        val tipWidth = baseWidth * 0.18f
                        try {
                            e.setStartWidth(baseWidth)
                            e.setEndWidth(tipWidth)

                            // alpha 每帧会被覆盖；这里先给一个非 0 初值，避免“第一帧全透明”。
                            e.setStartColor(1f, 1f, 1f, 0.9f)
                            e.setEndColor(1f, 1f, 1f, 0.05f)
                            e.setStartEmissive(1f, 1f, 1f, 0.9f)
                            e.setEndEmissive(1f, 1f, 1f, 0f)
                        } catch (_: Throwable) {
                        }

                        val mat = e.materialData
                        try {
                            mat.setAlphaToEmissive(0f)
                            mat.setColorToEmissive(0f)
                            mat.setGlowPower(1f)
                            mat.setColor(Color(255, 255, 255, 255))
                            mat.setEmissiveColor(Color(255, 255, 255, 255))

                            val coreSprite = getSpriteEnsured(TEX_BEAM_CORE)
                            val fringeSprite = getSpriteEnsured(TEX_FLARE_LINEAR_SOFT)
                            mat.setDiffuse(coreSprite)
                            mat.setEmissive(fringeSprite)
                        } catch (_: Throwable) {
                        }

                        try {
                            e.setStateVanilla(projectile.location, facingOffsetDeg)
                        } catch (_: Throwable) {
                        }

                        val st = try {
                            BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, e)
                        } catch (_: Throwable) {
                            -1
                        }
                        if (st != 0) {
                            try { e.delete() } catch (_: Throwable) {}
                            return null
                        }

                        return PinwheelCoreRay(
                            entity = e,
                            baseAlpha = 0.78f,
                            baseWidth = baseWidth,
                            tipWidth = tipWidth,
                            facingOffsetDeg = facingOffsetDeg,
                        )
                    }

                    val rays = ArrayList<PinwheelCoreRay>(4)
                    val offsets = floatArrayOf(0f, 90f, 180f, 270f)
                    var ok = true
                    for (deg in offsets) {
                        val ray = createOneRay(deg)
                        if (ray == null) {
                            ok = false
                            break
                        }
                        rays.add(ray)
                    }
                    if (!ok) {
                        rays.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    } else {
                        pinwheelCoreRays = rays
                    }
                }

                // 十字星芒：
                // - core：更短但更厚/更亮（中心更实）
                // - outer：更长、更虚且偏红（外部更虚并趋红）
                // 注意：用 scaleXMul 拉长，避免同时把线条“变粗”。
                if (pinwheelCoreRays.isEmpty() && !addLayer(
                        kind = Layer.Kind.FLARE_H_CORE,
                    diffusePath = TEX_LINEAR,
                    emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.95f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 6.80f,
                        scaleXMul = 1.15f,
                        // 回滚：星芒不要过细，避免出现“奇怪的细边/断续感”
                        scaleYMul = 0.042f,
                        facingOffsetDeg = 0f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (pinwheelCoreRays.isEmpty() && !addLayer(
                        kind = Layer.Kind.FLARE_V_CORE,
                    diffusePath = TEX_LINEAR,
                    emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.95f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 6.80f,
                        scaleXMul = 1.15f,
                        scaleYMul = 0.042f,
                        facingOffsetDeg = 90f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (!addLayer(
                        kind = Layer.Kind.FLARE_H_OUTER,
                    diffusePath = TEX_LINEAR,
                    emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.95f,
                        rotateSpeedDeg = 0f,
                        // 优化：降低大十字星芒大小（-35%）
                        baseScaleMul = 4.42f,
                        // 调整：外部十字星芒大小降低 25%（长度 * 0.75）
                        scaleXMul = 1.50f,
                        // 回滚：外延也略加粗，避免“像描边一样的细白线”
                        scaleYMul = 0.022f,
                        facingOffsetDeg = 0f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (!addLayer(
                        kind = Layer.Kind.FLARE_V_OUTER,
                    diffusePath = TEX_LINEAR,
                    emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.95f,
                        rotateSpeedDeg = 0f,
                        // 优化：降低大十字星芒大小（-35%）
                        baseScaleMul = 4.42f,
                        scaleXMul = 1.50f,
                        scaleYMul = 0.022f,
                        facingOffsetDeg = 90f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                // 兼容：保留老的 FLARE_H/FLARE_V（当前 EVENT_HORIZON 不再创建它们）

                if (!addLayer(
                        kind = Layer.Kind.FLARE_D1,
                    diffusePath = TEX_LINEAR,
                    emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.45f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 4.80f,
                        scaleXMul = 1.00f,
                        scaleYMul = 0.020f,
                        facingOffsetDeg = 45f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (!addLayer(
                        kind = Layer.Kind.FLARE_D2,
                    diffusePath = TEX_LINEAR,
                    emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.45f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 4.80f,
                        scaleXMul = 1.00f,
                        scaleYMul = 0.020f,
                        facingOffsetDeg = 135f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }
            } else if (variant == SingularityDetonationFx.Variant.NOVA) {
                // NOVA：补一个更“爆闪”的中心高光 + 红色星芒（与你给的小图更一致）
                if (!addLayer(
                        kind = Layer.Kind.CENTER_HIGHLIGHT,
                        diffusePath = TEX_CENTER_HIGHLIGHT,
                        emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.85f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 1.02f,
                        scaleXMul = 1f,
                        scaleYMul = 1f,
                        facingOffsetDeg = 0f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (!addLayer(
                        kind = Layer.Kind.FLARE_H,
                        diffusePath = TEX_LINEAR,
                        emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.80f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 4.90f,
                        scaleXMul = 1.00f,
                        scaleYMul = 0.070f,
                        facingOffsetDeg = 0f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (!addLayer(
                        kind = Layer.Kind.FLARE_V,
                        diffusePath = TEX_LINEAR,
                        emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.80f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 4.90f,
                        scaleXMul = 1.00f,
                        scaleYMul = 0.070f,
                        facingOffsetDeg = 90f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (!addLayer(
                        kind = Layer.Kind.FLARE_D1,
                        diffusePath = TEX_LINEAR,
                        emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.70f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 4.10f,
                        scaleXMul = 1.00f,
                        scaleYMul = 0.065f,
                        facingOffsetDeg = 45f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }

                if (!addLayer(
                        kind = Layer.Kind.FLARE_D2,
                        diffusePath = TEX_LINEAR,
                        emissiveSprite = null,
                        additive = true,
                        baseAlpha = 0.70f,
                        rotateSpeedDeg = 0f,
                        baseScaleMul = 4.10f,
                        scaleXMul = 1.00f,
                        scaleYMul = 0.065f,
                        facingOffsetDeg = 135f,
                        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    )
                ) {
                    layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                    return null
                }
            }

            // 白描边：锐利薄环（靠近最上层，保证可读）
            if (!addLayer(
                    kind = Layer.Kind.WHITE_RIM,
                    diffusePath = TEX_RING_THIN,
                    emissiveSprite = null,
                    additive = true,
                    baseAlpha = 0.92f,
                    rotateSpeedDeg = 55f,
                    baseScaleMul = when (variant) {
                        // 白描边应该比黑核略大，否则会被黑核盖住导致“描边消失”。
                        SingularityDetonationFx.Variant.NOVA -> 1.08f
                        SingularityDetonationFx.Variant.EVENT_HORIZON -> 1.12f
                    },
                    scaleXMul = 1f,
                    scaleYMul = 1f,
                    facingOffsetDeg = 0f,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                )
            ) {
                layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                return null
            }

            // 黑核：必须最后绘制，遮罩中心发光层，确保“清晰黑点”存在。
            if (!addLayer(
                    kind = Layer.Kind.BLACK_CORE,
                    diffusePath = TEX_CIRCLE_MASK,
                    emissiveSprite = null,
                    additive = false,
                    baseAlpha = 1.0f,
                    rotateSpeedDeg = 0f,
                    baseScaleMul = when (variant) {
                        // 黑核必须小于白描边/中心亮区，否则“发光层被彻底盖住”会变成只剩一个黑点。
                        SingularityDetonationFx.Variant.NOVA -> 0.96f
                        // 需求：事件视界黑核缩小 33%
                        SingularityDetonationFx.Variant.EVENT_HORIZON -> 0.67f
                    },
                    scaleXMul = 1f,
                    scaleYMul = 1f,
                    facingOffsetDeg = 0f,
                    // 黑核必须压过所有 additive flare：
                    // 某些管线里 additive pass 会在普通 alpha 之后执行，导致同层顺序不可靠；
                    // 因此把黑核放到更靠后的 combat layer，确保最终合成时黑核在最上层。
                    layer = CombatEngineLayers.JUST_BELOW_WIDGETS,
                )
            ) {
                layers.forEach { try { it.entity.delete() } catch (_: Throwable) {} }
                return null
            }

            return SingularityAccretionDiskVisual(
                engine = engine,
                variant = variant,
                layers = layers,
                glowFlare = glowFlare,
                pinwheelCoreRays = pinwheelCoreRays,
                seed = seed,
                startTime = now,
            )
        }
    }
}
