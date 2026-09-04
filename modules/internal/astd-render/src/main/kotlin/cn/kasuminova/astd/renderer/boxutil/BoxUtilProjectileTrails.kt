package cn.kasuminova.astd.renderer.boxutil

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVisual
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVisualFactory
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileTracerManager

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

/**
 * BoxUtil 的“弹体曳光 + 可选弹头锥形”视觉实现，以及其样式参数。
 *
 * 这层只负责“如何创建/如何跟随/如何淡出”；生命周期由 [ProjectileTracerManager] 驱动。
 */
object BoxUtilProjectileTrails {

    private val log = Global.getLogger(BoxUtilProjectileTrails::class.java)

    /**
    * 飞行阶段附加喷散参数。当前 BoxUtil 路径保持唯一渲染路径，粒子喷散默认关闭。
     */
    data class ParticleSprayStyle(
        val enabled: Boolean = false,

        /** 是否在 beginFadeOut() 之后仍继续喷粒子（通常用于验证/更强的尾焰观感）。 */
        val emitWhileFading: Boolean = false,

        /** 调试用：强制用高亮高不透明粒子发射，便于确认“确实在喷/坐标正确/粒子系统没被关”。 */
        val debugForceVisible: Boolean = false,

        /** 每秒生成粒子数（允许小数）。 */
        val particlesPerSecond: Float = 18f,

        /**
         * 粒子继承弹体速度的比例（0=不继承，1=完全继承）。
         *
         * 注意：弹体速度通常非常大，如果继承过高，粒子会“跟着弹体一起向前飞”，
         * 很容易与同色曳光叠在一起导致肉眼不可见。
         */
        val inheritVelocityMul: Float = 0.05f,

        /** 随机颜色范围（每个通道分别在 min..max 之间取值）。 */
        val colorMin: Color = Color(120, 180, 255, 40),
        val colorMax: Color = Color(185, 235, 255, 110),

        /** 粒子大小范围（像素）。 */
        val sizeMin: Float = 3f,
        val sizeMax: Float = 6f,

        /** 亮度（1=正常；>1 更亮），用于 smoothParticle。 */
        val brightnessMin: Float = 0.7f,
        val brightnessMax: Float = 1.2f,

        /** 粒子存活时间范围（秒）。 */
        val durationMin: Float = 0.14f,
        val durationMax: Float = 0.24f,

        /** 生成点抖动半径（像素）。 */
        val spawnJitterRadius: Float = 7f,

        /** 从弹体后方偏移生成（像素）；<=0 则使用 joinWidth 的近似值。 */
        val behindDistance: Float = 8f,

        /** 附加速度（相对弹体方向）的随机范围。 */
        val speedMin: Float = 10f,
        val speedMax: Float = 55f,

        /** 散射角（度），围绕“弹体后方方向”。 */
        val spreadArc: Float = 28f,
    )

    /**
     * 一套可复用的样式参数。建议不同武器/弹体创建不同 style，传给 factory 即可。
     */
    data class BeamAndConeStyle(
        // 资源
        val coreSpritePath: String = "graphics/fx/beamcoreb.png",
        val fringeSpritePath: String = "graphics/fx/beamfringeb.png",

        // 颜色（材质颜色/发光色）
        val coreColor: Color = Color(220, 245, 255, 255),
        val fringeColor: Color = Color(120, 200, 255, 255),

        // 图层/存活
        val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        val full: Float = 9999f,

        // 连接处（弹体位置）参数：主曳光“头宽” = 锥形“根部宽”，减少断层
        val joinWidth: Float = 11.0f,

        // 主曳光（从弹体位置向后）
        val tracerEnabled: Boolean = true,
        val tracerLength: Float = 180f,
        val tracerTailWidth: Float = 2.5f,
        val tracerHeadWidth: Float = joinWidth,
        val tracerTailAlphaMul: Float = 0.187f,
        val tracerHeadAlphaMul: Float = 1.0625f,
        val tracerTailEmissiveAlphaMul: Float = 0.6375f,
        val tracerHeadEmissiveAlphaMul: Float = 2.04f,
        val tracerMixPower: Float = 2.6f,

        /**
         * 让 tracer “随飞行逐步拉出”，避免开火第一帧就画出整条固定长度线。
         *
         * - traveled < [tracerRampStartDistance]：长度保持为 [tracerMinLength]
         * - traveled >= start：长度在 [tracerRampDistance] 内线性增长到 [tracerLength]
         *
         * 默认：minLength=tracerLength 且 rampDistance=0，即保持原行为（不启用渐显）。
         */
        val tracerMinLength: Float = tracerLength,
        val tracerRampStartDistance: Float = 0f,
        val tracerRampDistance: Float = 0f,
        /** 节点更新的最小变化阈值（世界单位）。 */
        val tracerRampEpsilon: Float = 0.5f,
        /** 弹体消失后是否让拖尾逐渐缩短（而非仅透明度淡出）。 */
        val tracerShrinkOnFade: Boolean = false,

        /**
         * tracer 的 fill（可选）：用于柔化尾端/头端的“截断边缘”，制造更像虚化的淡化。
         *
         * 约定：
         * - node[0] 对应 factor=0（tracer 末端/尾部；本实现中 facing+180，因此 node[0] 在弹体后方）
         * - node[last] 对应 factor=1（tracer 头部/弹体处）
         *
         * 默认用 NaN 表示“不设置”，保持 BoxUtil 默认行为。
         */
        val tracerFillStartAlpha: Float = Float.NaN,
        val tracerFillStartFactor: Float = Float.NaN,
        val tracerFillEndAlpha: Float = Float.NaN,
        val tracerFillEndFactor: Float = Float.NaN,

        /**
         * 可选深色尾端：让拖尾沿长度方向逐渐沉入深色烟尘，而不是只把同一种颜色改透明。
         *
         * 使用注意：
         * - `null` 表示继续使用 [coreColor]；适合已有强风格弹体。
         * - 推荐使用低亮度、带 alpha 的同色系深色，例如紫色弹体使用 `Color(38, 4, 58, 125)`。
         * - 尾端 alpha 仍会乘以 [tracerTailAlphaMul]，深色过黑时会在普通背景中显得像“断线”。
         */
        val tracerTailCoreColor: Color? = null,
        /**
         * 可选深色尾端发光色。通常比 [tracerTailCoreColor] 稍亮一点，避免尾部完全失去能量感。
         */
        val tracerTailFringeColor: Color? = null,

        /**
         * 拖尾纹理滚动速度。非 0 时会让 beam 贴图沿长度方向流动，适合模拟烟雾/能量脉动。
         * 推荐范围：`-260..260`。绝对值太大时会出现闪烁感。
         */
        val tracerTextureSpeed: Float = 0f,
        /**
         * 拖尾贴图重复长度。推荐与拖尾长度同量级，短值更碎、更像噪声，长值更平滑。
         */
        val tracerTexturePixels: Float = 0f,
        /**
         * 拖尾 UV 初始偏移。使用负值表示随机偏移，避免多发弹体纹理同步滚动。
         */
        val tracerUVOffset: Float = -1f,
        /**
         * BoxUtil TrailEntity 的内建 jitter 强度。少量 jitter 可打破“纯渐变锥体”的硬边。
         * 推荐范围：`0.01..0.08`；持续高射速弹体建议使用 `<=0.04`。
         */
        val tracerJitterPower: Float = 0f,
        /**
         * 是否启用 flick。通用弹体默认关闭，只有脉冲类弹体建议开启。
         */
        val tracerFlick: Boolean = false,

        /**
         * “超射程继续飞一小段”的视觉补偿：当弹体被移除且【最后一帧处于 isFading】时，
         * 让 tracer/headCone 用“最后速度”继续向前推进一小段时间。
         *
         * - 默认 0：不启用。
         * - 只在 removed 时触发；并要求最后一帧 isFading=true，用于避免命中后还继续飞。
         */
        val ghostTravelOnRemovedSeconds: Float = 0f,
        // 弹头锥形（在弹体前方）
        val coneEnabled: Boolean = true,
        val coneLength: Float = 14f,
        /** 锥形“尖端”宽度（前方） */
        val coneTipWidth: Float = 1.0f,
        /** 锥形“根部”宽度（弹体处），默认与 joinWidth 一致 */
        val coneRootWidth: Float = joinWidth,
        val coneTipAlphaMul: Float = 0.32f,
        val coneRootAlphaMul: Float = tracerHeadAlphaMul,
        val coneTipEmissiveAlphaMul: Float = 1.05f,
        val coneRootEmissiveAlphaMul: Float = tracerHeadEmissiveAlphaMul,
        val coneMixPower: Float = 3.0f,

        // 锥形 fill：只柔化尖端，根部不淡出以避免交界断层
        val coneFillStartAlpha: Float = 0f,
        val coneFillStartFactor: Float = 0.72f,
        val coneFillEndAlpha: Float = 1f,
        val coneFillEndFactor: Float = 1f,

        /** 飞行粒子散发（可选）。 */
        val particles: ParticleSprayStyle = ParticleSprayStyle(enabled = false),
    )

    /**
     * 便捷工厂：给 [ProjectileTracerManager] 用。
     *
     * @param styleProvider 允许根据弹体属性（例如 collisionRadius/weapon/速度）动态生成样式，实现“泛用”。
     */
    fun beamAndConeFactory(styleProvider: (DamagingProjectileAPI) -> BeamAndConeStyle): ProjectileVisualFactory {
        return ProjectileVisualFactory { engine, projectile ->
            val style = styleProvider(projectile)
            BeamAndConeVisual.create(engine, projectile, style)
        }
    }

    private class BeamAndConeVisual(
        private val engine: CombatEngineAPI,
        private val style: BeamAndConeStyle,
        private val tracer: TrailEntity?,
        private val headCone: TrailEntity?,
    ) : ProjectileVisual {

        private var fadeStarted = false
        private var tracerFadeReason: ProjectileTracerManager.FadeReason? = null
        private var headConeFadeReason: ProjectileTracerManager.FadeReason? = null
        private var particleAcc = 0f

        // tracer 渐显：按“实际飞行距离”累积，支持弹速变化/炮口初速差。
        private var traveled = 0f
        private var tracerLen = Float.NaN

        // 淡出缩短：弹体消失后拖尾逐渐变短
        private var fadeStartLength = Float.NaN
        private var fadeOutDuration = 0f
        private var fadeElapsed = 0f

        // 记录“最后一帧”的状态，用于 removed 后的 ghostTravel
        private var initializedLast = false
        private var lastLoc = Vector2f(0f, 0f)
        private var lastVel = Vector2f(0f, 0f)
        private var lastFacing = 0f
        private var lastWasFading = false

        // removed 后短暂“继续飞行”的视觉位置
        private var ghostActive = false
        private var ghostTimer = 0f
        private var ghostLoc = Vector2f(0f, 0f)
        private var ghostVel = Vector2f(0f, 0f)
        private var ghostFacing = 0f

        private fun speed(projectile: DamagingProjectileAPI): Float {
            val v = projectile.velocity ?: return 0f
            return sqrt(v.x * v.x + v.y * v.y)
        }

        private fun computeTracerDesiredLength(): Float {
            val maxLen = style.tracerLength.coerceAtLeast(0.01f)
            val rampDist = style.tracerRampDistance
            val startDist = style.tracerRampStartDistance
            if (rampDist <= 0.01f) return maxLen

            val minLen = style.tracerMinLength.coerceIn(0.0f, maxLen)
            val d = (traveled - startDist).coerceAtLeast(0f)
            val t = (d / rampDist).coerceIn(0f, 1f)
            return minLen + (maxLen - minLen) * t
        }

        private fun updateTracerGeometryIfNeeded(desiredLength: Float) {
            val tr = tracer ?: return
            if (tr.hasDelete()) return

            val eps = style.tracerRampEpsilon.coerceAtLeast(0.01f)
            if (tracerLen.isFinite() && kotlin.math.abs(desiredLength - tracerLen) < eps) return
            tracerLen = desiredLength

            try {
                val nodes = tr.nodes
                if (nodes == null || nodes.size < 2) return

                // RenderingUtil.createBeamVisual(): node[0] = (length, 0), node[1] = (0, 0)
                nodes[0].x = desiredLength
                nodes[0].y = 0f
                nodes[1].x = 0f
                nodes[1].y = 0f

                tr.setNodeRefreshIndex(0)
                tr.setNodeRefreshSize(2)
                tr.submitNodes()
            } catch (_: Throwable) {
                // 忽略：节点更新失败时仍保留原长度，避免硬崩。
            }
        }

        private fun computeFacing(projectile: DamagingProjectileAPI): Float {
            // 对 ballistic projectile 来说，projectile.facing 可能在生成后被引擎修正/不等于真实飞行方向，
            // 用速度向量角度更稳定；速度过小则回退到 projectile.facing。
            val v = projectile.velocity
            return if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) {
                VectorUtils.getFacing(v)
            } else {
                projectile.facing
            }
        }

        override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
            // 记录最后一帧状态（用于 removed 后的 ghostTravel/定格）。
            // 注意：弹体刚被移除后访问其字段可能抛异常，因此必须 try/catch。
            try {
                if (!initializedLast) {
                    initializedLast = true
                }
                lastLoc = Vector2f(projectile.location)
                lastVel = projectile.velocity?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
                lastFacing = computeFacing(projectile)
                lastWasFading = projectile.isFading
            } catch (_: Throwable) {
                // ignore
            }

            // removed 后短暂“继续飞行”的视觉补偿（只影响渲染位置，不影响实际弹体）。
            if (ghostActive && amount > 0f) {
                ghostTimer -= amount
                ghostLoc.x += ghostVel.x * amount
                ghostLoc.y += ghostVel.y * amount
                if (ghostTimer <= 0f) {
                    ghostActive = false
                }
            }

            if (amount > 0f && style.tracerEnabled && style.tracerRampDistance > 0.01f && !fadeStarted) {
                traveled += speed(projectile) * amount
                updateTracerGeometryIfNeeded(computeTracerDesiredLength())
            }

            // 淡出期间：拖尾逐渐缩短（缩短速度与透明度淡出同步）
            if (fadeStarted && amount > 0f && style.tracerEnabled && style.tracerShrinkOnFade) {
                fadeElapsed += amount
                val shrinkProgress = (fadeElapsed / fadeOutDuration.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                // 使用 ease-out 曲线：开始慢、结束快，更自然
                val eased = 1f - (1f - shrinkProgress) * (1f - shrinkProgress)
                val targetLen = fadeStartLength * (1f - eased * 0.92f)  // 最终缩短到 8% 长度
                updateTracerGeometryIfNeeded(targetLen.coerceAtLeast(2f))
            }

            val facing = if (ghostActive) {
                ghostFacing
            } else {
                try {
                    computeFacing(projectile)
                } catch (_: Throwable) {
                    lastFacing
                }
            }
            val loc = if (ghostActive) {
                ghostLoc
            } else {
                try {
                    projectile.location
                } catch (_: Throwable) {
                    lastLoc
                }
            }
            // 主曳光：从弹体位置向后拉一条。
            tracer?.let {
                if (!it.hasDelete()) {
                    it.setStateVanilla(loc, facing + 180f)
                }
            }

            // 纯代码“弹头半锥形”。
            headCone?.let {
                if (!it.hasDelete()) {
                    it.setStateVanilla(loc, facing)
                }
            }

            // 粒子散发：当前 BoxUtil 路径保持唯一渲染路径。
            if (amount > 0f && style.particles.enabled) {
                val ps = style.particles
                if (projectile.isFading && !ps.emitWhileFading) {
                    // 进入淡出时清空累积，避免“淡出首帧补喷”。
                    particleAcc = 0f
                } else if (!fadeStarted || ps.emitWhileFading) {
                    emitParticles(projectile, facing, amount)
                }
            }
        }

        private fun emitParticles(projectile: DamagingProjectileAPI, facing: Float, amount: Float) = Unit

        override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
            // tracer/headCone：跟随管理器给的 fadeOutSeconds（以贴合弹体原生淡化/移除节奏）。
            if (!fadeStarted) {
                fadeStarted = true
                // 记录淡出开始时的长度，用于缩短动画
                fadeStartLength = if (tracerLen.isFinite()) tracerLen else style.tracerLength
                fadeOutDuration = fadeOutSeconds.coerceAtLeast(0.01f)
                fadeElapsed = 0f
            }

            // removed 后：如果最后一帧处于 isFading，认为是“超射程移除”，启用短暂 ghostTravel。
            if (
                reason == ProjectileTracerManager.FadeReason.PROJECTILE_REMOVED &&
                !ghostActive &&
                style.ghostTravelOnRemovedSeconds > 0.01f &&
                lastWasFading
            ) {
                ghostActive = true
                ghostTimer = style.ghostTravelOnRemovedSeconds
                ghostLoc = Vector2f(lastLoc)
                ghostVel = Vector2f(lastVel)
                ghostFacing = lastFacing
            }

            // 进入淡出时不再生成粒子：清空累积器，避免 residual spawn。
            if (!style.particles.emitWhileFading) {
                particleAcc = 0f
            }

            val t = fadeOutSeconds.coerceAtLeast(0.01f)
            tracer?.let { tr ->
                if (!tr.hasDelete()) {
                    val prev = tracerFadeReason
                    // 关键：不要在 FADING -> REMOVED 时重置 globalTimer。
                    // BoxUtil 的 setGlobalTimer(...) 可能会把 alpha 从当前淡化进度“拉回满值”再重新淡出，造成末帧闪烁。
                    if (prev == null) {
                        tr.setGlobalTimer(0f, 0f, t)
                        tracerFadeReason = reason
                    }
                }
            }
            headCone?.let { hc ->
                if (!hc.hasDelete()) {
                    val prev = headConeFadeReason
                    // 同上：避免重置 timer 导致“最后一帧突然变亮”。
                    if (prev == null) {
                        hc.setGlobalTimer(0f, 0f, t)
                        headConeFadeReason = reason
                    }
                }
            }
        }

        override fun isFadeOutOver(): Boolean {
            if (!fadeStarted) return false

            val tracerOver = tracer?.let { it.hasDelete() || it.isGlobalTimerOver } ?: true
            val headOver = headCone?.let { it.hasDelete() || it.isGlobalTimerOver } ?: true
            return tracerOver && headOver
        }

        override fun delete() {
            tracer?.delete()
            headCone?.delete()
        }

        companion object {
            fun create(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, style: BeamAndConeStyle): BeamAndConeVisual? {
                val core = Global.getSettings().getSprite(style.coreSpritePath)
                val fringe = Global.getSettings().getSprite(style.fringeSpritePath)

                val v = projectile.velocity
                val facing = if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) VectorUtils.getFacing(v) else projectile.facing

                val tracer = if (!style.tracerEnabled) {
                    null
                } else {
                    val maxLen = style.tracerLength
                    val minLen = style.tracerMinLength.coerceIn(0.0f, maxLen)
                    val initLen = if (style.tracerRampDistance > 0.01f) minLen else maxLen
                    BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
                        engine = engine,
                        location = projectile.location,
                        facing = facing + 180f,
                        length = initLen,
                        tailWidth = style.tracerTailWidth,
                        headWidth = style.tracerHeadWidth,
                        coreColor = style.coreColor,
                        fringeColor = style.fringeColor,
                        coreSprite = core,
                        fringeSprite = fringe,
                        layer = style.layer,
                        full = style.full,
                        tailAlphaMul = style.tracerTailAlphaMul,
                        headAlphaMul = style.tracerHeadAlphaMul,
                        tailEmissiveAlphaMul = style.tracerTailEmissiveAlphaMul,
                        headEmissiveAlphaMul = style.tracerHeadEmissiveAlphaMul,
                        mixPower = style.tracerMixPower,
                    )?.apply {
                        // 可选：tracer 的尾端/头端柔化
                        if (style.tracerFillStartAlpha.isFinite()) setFillStartAlpha(style.tracerFillStartAlpha)
                        if (style.tracerFillStartFactor.isFinite()) setFillStartFactor(style.tracerFillStartFactor)
                        if (style.tracerFillEndAlpha.isFinite()) setFillEndAlpha(style.tracerFillEndAlpha)
                        if (style.tracerFillEndFactor.isFinite()) setFillEndFactor(style.tracerFillEndFactor)
                        applyTracerDetailParams(this, style)
                    }
                }

                if (style.tracerEnabled && tracer == null) {
                    log.warn("[ASTD] BoxUtil trail creation failed; projectile VFX entity was deleted")
                    return null
                }

                val headCone = if (!style.coneEnabled) {
                    null
                } else {
                    BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
                        engine = engine,
                        location = projectile.location,
                        facing = facing,
                        // node[0] 是 trail 的末端（+length 方向），因此“尖端(前方)细、根部(弹体处)粗”需要：tailWidth=tip, headWidth=root
                        length = style.coneLength,
                        tailWidth = style.coneTipWidth,
                        headWidth = style.coneRootWidth,
                        coreColor = style.coreColor,
                        fringeColor = style.fringeColor,
                        coreSprite = core,
                        fringeSprite = fringe,
                        layer = style.layer,
                        full = style.full,
                        tailAlphaMul = style.coneTipAlphaMul,
                        headAlphaMul = style.coneRootAlphaMul,
                        tailEmissiveAlphaMul = style.coneTipEmissiveAlphaMul,
                        headEmissiveAlphaMul = style.coneRootEmissiveAlphaMul,
                        mixPower = style.coneMixPower,
                    )?.apply {
                        setFillStartAlpha(style.coneFillStartAlpha)
                        setFillStartFactor(style.coneFillStartFactor)
                        setFillEndAlpha(style.coneFillEndAlpha)
                        setFillEndFactor(style.coneFillEndFactor)
                    }
                }
                return BeamAndConeVisual(
                    engine = engine,
                    style = style,
                    tracer = tracer,
                    headCone = headCone,
                ).also { v ->
                    // 渐显启用时：创建后立刻把长度锁到“初始值”，避免首帧出现满长度。
                    if (style.tracerEnabled && style.tracerRampDistance > 0.01f && tracer != null) {
                        try {
                            v.updateTracerGeometryIfNeeded(style.tracerMinLength.coerceIn(0.0f, style.tracerLength.coerceAtLeast(0.01f)))
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            private fun applyTracerDetailParams(entity: TrailEntity, style: BeamAndConeStyle) {
                try {
                    val tailCore = style.tracerTailCoreColor
                    val tailFringe = style.tracerTailFringeColor
                    if (tailCore != null) {
                        entity.materialData.setColor(style.coreColor)
                        val mul = colorMul(tailCore, style.coreColor)
                        entity.setStartColor(mul.red, mul.green, mul.blue, style.tracerTailAlphaMul)
                    }
                    if (tailFringe != null) {
                        entity.materialData.setEmissiveColor(style.fringeColor)
                        val mul = colorMul(tailFringe, style.fringeColor)
                        entity.setStartEmissive(mul.red, mul.green, mul.blue, style.tracerTailEmissiveAlphaMul)
                    }

                    if (style.tracerTexturePixels > 0.01f) {
                        entity.setTexturePixels(style.tracerTexturePixels)
                    }
                    if (kotlin.math.abs(style.tracerTextureSpeed) > 0.01f) {
                        entity.setTextureSpeed(style.tracerTextureSpeed)
                        entity.setFlowWhenPaused(false)
                    }
                    if (style.tracerUVOffset >= 0f) {
                        entity.setUVOffset(style.tracerUVOffset)
                    } else if (style.tracerTexturePixels > 0.01f || kotlin.math.abs(style.tracerTextureSpeed) > 0.01f) {
                        entity.setUVOffset((Math.random().toFloat() * 2f) - 1f)
                    }
                    if (style.tracerJitterPower > 0.0001f) {
                        entity.setJitterPower(style.tracerJitterPower)
                    }
                    entity.setFlick(style.tracerFlick)
                    entity.setSyncFlick(false)
                } catch (_: Throwable) {
                    // 细节参数失败时保留基础 TrailEntity，避免单个装饰项影响弹体可见性。
                }
            }

            private fun colorMul(color: Color, base: Color): ColorMul {
                return ColorMul(
                    color.red.toFloat() / base.red.coerceAtLeast(1).toFloat(),
                    color.green.toFloat() / base.green.coerceAtLeast(1).toFloat(),
                    color.blue.toFloat() / base.blue.coerceAtLeast(1).toFloat(),
                )
            }

            private data class ColorMul(val red: Float, val green: Float, val blue: Float)

        }
    }
}
