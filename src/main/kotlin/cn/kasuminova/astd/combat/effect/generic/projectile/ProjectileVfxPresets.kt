package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.boxutil.BoxUtilProjectileTrails
import cn.kasuminova.astd.renderer.effect.projectile.beam.PathEllipseOglShockRingEmitterProjectileVisual
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.effect.system.ArcFlareOverdriveVisualState

import cn.kasuminova.astd.combat.effect.lens.signature.singularity.SingularityDetonationFx
import cn.kasuminova.astd.combat.effect.lens.signature.singularity.SingularityAccretionDiskVisual
import cn.kasuminova.astd.combat.effect.lens.signature.singularity.SingularityRetargetPulseVisual
import cn.kasuminova.astd.combat.effect.lens.signature.singularity.SingularityShotDownDetonationVisual
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 一组“按 projectileSpecId 绑定”的代码 VFX 预设实现。
 *
 * 说明：这里主要实现“弹体表现”（tracer/cone/粒子/轻量 spawn 视觉），
 * 不包含设计稿里更复杂的“二段飞行/延迟撕裂/软控封顶”等机制逻辑。
 */
internal object ProjectileVfxPresets {

    internal object Aod7Shot {

        private const val BODY_SCALE = 4.5f

        private val options = ProjectileTracerManager.Options(
            // AOD-7 的尾迹需要“弹体消失后仍残留一小段时间”，因此不在 projectile.isFading 时立刻淡出，
            // 改为在弹体真正移除后再用更长淡出。
            fadeOutOnProjectileFadingSeconds = 0f,
            fadeOutOnProjectileRemovedSeconds = 0.70f,
            pendingTimeoutSeconds = 0.75f,
            // 弹道弹体命中后 wasRemoved 立即可靠，缩短容错窗口避免拖尾在命中点“停滞”。
            removedGraceSeconds = 0.05f,
        )

        // 需求更新：保留“长光束 + 线圈环”的结构，但整体颜色使用先前的金橙色系。
        private val coreColor = Color(220, 240, 255, 235)
        private val fringeColor = Color(130, 195, 255, 214)
        private val ionAccentColor = Color(152, 224, 255, 126)

        // 尾迹主色：更偏金白/橙白，便于远距离判读。
        private val tailCoreColor = Color(210, 232, 255, 205)
        private val tailFringeColor = Color(110, 178, 255, 165)

        // 外层辉光：更宽、更淡（蓝色），用于模拟“光晕”。
        private val tailBloomCoreColor = Color(185, 218, 255, 95)
        private val tailBloomFringeColor = Color(130, 205, 255, 78)

        private fun getOverdriveLevel(projectile: DamagingProjectileAPI): Float {
            return try {
                val ship = projectile.weapon?.ship ?: return 0f
                ArcFlareOverdriveVisualState.getLevel(ship)
            } catch (_: Throwable) { 0f }
        }

        /**
         * AOD-7：只使用 1 个 BoxUtil TrailEntity 来渲染“弹体曳光”。
         *
         * 需求：
         * - 拖尾随飞行逐步拉长（ramp）
         * - 尾部有“虚化淡化”（fillStartAlpha/factor）
         * - 不使用 cone（避免两侧锥形/曳光弹观感）
         */
        private fun createSingleTrailStyle(projectile: DamagingProjectileAPI): BoxUtilProjectileTrails.BeamAndConeStyle {
            val spec = projectile.projectileSpec
            val baseLen = (spec?.length ?: 24f).coerceAtLeast(1f) * BODY_SCALE
            val baseW = (spec?.width ?: 8f).coerceAtLeast(1f) * BODY_SCALE
            val overdriveLevel = getOverdriveLevel(projectile)

            val tailLen = (baseLen * 5.46f).coerceIn(364f, 1225f)

            // 需求更新：拖尾整体宽度缩小 25%。
            val widthMul = 0.75f

            // 尾部需要更窄（更像“能量束逐渐收束/消散”），且整体再缩小 25%。
            // 这里让尾端明显小于头部，配合 fillStart 做“虚化淡化”。
            val headW = (baseW * 0.72f * widthMul).coerceIn(14f, 48f)
            val tailW = (headW * 0.56f).coerceIn(8f, 32f)

            val startDist = (baseLen * 0.90f).coerceIn(26f, 110f)
            val rampDist = (tailLen * 0.50f).coerceIn(220f, 900f)
            val minLen = (baseLen * 0.06f).coerceIn(2f, 10f)

            // 弹头尖端：用极短的前向 cone 解决“平头截断”的观感。
            // 注意：这不是曳光拖尾（tracer），而是“弹头尖端补强”。
            val noseLen = (baseLen * 0.55f).coerceIn(22f, 120f)
            val noseTipW = (headW * 0.12f).coerceIn(1.3f, 6.5f)
            // 关键：cone 根部宽度必须与 tracer 头宽一致，否则会出现一圈“截断台阶”。
            val noseRootW = headW

            val activeTailCore = ArcFlareOverdriveVisualState.lerpColor(tailCoreColor, ArcFlareOverdriveVisualState.hotCore, overdriveLevel, tailCoreColor.alpha)
            val activeTailFringe = ArcFlareOverdriveVisualState.lerpColor(tailFringeColor, ArcFlareOverdriveVisualState.hotFringe, overdriveLevel, tailFringeColor.alpha)
            val particleColorMin = ArcFlareOverdriveVisualState.lerpColor(tailFringeColor, ArcFlareOverdriveVisualState.hotFringe, overdriveLevel, 38)
            val particleColorMax = ArcFlareOverdriveVisualState.lerpColor(tailCoreColor, ArcFlareOverdriveVisualState.hotCore, overdriveLevel, 72)

            return BoxUtilProjectileTrails.BeamAndConeStyle(
                coreColor = activeTailCore,
                fringeColor = activeTailFringe,

                joinWidth = headW,

                tracerEnabled = true,
                tracerLength = tailLen,
                tracerTailWidth = tailW,
                tracerHeadWidth = headW,

                // 尾端很淡，头端保持高对比（尾端变窄后，略抬一点 alpha 防止过度消失）
                tracerTailAlphaMul = 0.030f,
                tracerHeadAlphaMul = 0.92f,
                tracerTailEmissiveAlphaMul = 0.22f,
                tracerHeadEmissiveAlphaMul = 3.80f,
                tracerMixPower = 1.85f,

                // 随飞行逐步拉出
                tracerMinLength = minLen,
                tracerRampStartDistance = startDist,
                tracerRampDistance = rampDist,
                tracerRampEpsilon = 1.25f,

                // 尾部虚化淡化（factor=0 对应尾端）
                tracerFillStartAlpha = 0f,
                tracerFillStartFactor = 0.80f,
                tracerFillEndAlpha = 1f,
                tracerFillEndFactor = 1f,

                // 弹体移除后尾迹逐渐缩短，避免“整条线硬性留到最后”
                tracerShrinkOnFade = true,

                // 不要 cone：避免两侧锥形贴图/曳光弹观感
                // 启用一个“很短很细”的弹头 cone，用来做尖端；长度/宽度都控制在很小范围，避免形成明显锥形曳光。
                coneEnabled = true,
                coneLength = noseLen,
                coneTipWidth = noseTipW,
                coneRootWidth = noseRootW,
                coneTipAlphaMul = 0.22f,
                // 根部与 tracer 头部保持接近的 alpha，减少接缝处“突然变暗/变亮”的观感。
                coneRootAlphaMul = 0.90f,
                coneTipEmissiveAlphaMul = 2.10f,
                coneRootEmissiveAlphaMul = 3.40f,
                coneMixPower = 3.6f,

                // 只柔化最尖端的一小段，避免“尖端完全透明”导致仍然像平头。
                coneFillStartAlpha = 0.35f,
                coneFillStartFactor = 0.88f,
                coneFillEndAlpha = 1f,
                coneFillEndFactor = 1f,

                particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                    enabled = true,
                    emitWhileFading = false,
                    particlesPerSecond = 22f,
                    inheritVelocityMul = 0.04f,
                    colorMin = particleColorMin,
                    colorMax = particleColorMax,
                    sizeMin = 7f,
                    sizeMax = 16f,
                    brightnessMin = 0.32f,
                    brightnessMax = 0.75f,
                    durationMin = 0.10f,
                    durationMax = 0.26f,
                    spawnJitterRadius = (headW * 0.30f).coerceIn(3f, 12f),
                    behindDistance = (headW * 0.50f).coerceIn(5f, 16f),
                    speedMin = 40f,
                    speedMax = 100f,
                    spreadArc = 30f,
                ),
            )
        }

        private fun createBloomTrailStyle(projectile: DamagingProjectileAPI): BoxUtilProjectileTrails.BeamAndConeStyle {
            val spec = projectile.projectileSpec
            val baseLen = (spec?.length ?: 24f).coerceAtLeast(1f) * BODY_SCALE
            val baseW = (spec?.width ?: 8f).coerceAtLeast(1f) * BODY_SCALE
            val overdriveLevel = getOverdriveLevel(projectile)

            val tailLen = (baseLen * 4.48f).coerceIn(294f, 896f)
            val headW = (baseW * 0.92f).coerceIn(20f, 64f)
            val tailW = (headW * 1.18f).coerceIn(16f, 72f)

            val activeBloomCore = ArcFlareOverdriveVisualState.lerpColor(tailBloomCoreColor, ArcFlareOverdriveVisualState.hotCore, overdriveLevel * 0.55f, tailBloomCoreColor.alpha)
            val activeBloomFringe = ArcFlareOverdriveVisualState.lerpColor(tailBloomFringeColor, ArcFlareOverdriveVisualState.hotFringe, overdriveLevel * 0.40f, tailBloomFringeColor.alpha)
            return BoxUtilProjectileTrails.BeamAndConeStyle(
                coreColor = activeBloomCore,
                fringeColor = activeBloomFringe,
                joinWidth = headW,
                tracerEnabled = true,
                tracerLength = tailLen,
                tracerTailWidth = tailW,
                tracerHeadWidth = headW,
                tracerTailAlphaMul = 0.020f,
                tracerHeadAlphaMul = 0.34f,
                tracerTailEmissiveAlphaMul = 0.16f,
                tracerHeadEmissiveAlphaMul = 1.35f,
                tracerMixPower = 1.35f,
                tracerMinLength = (baseLen * 0.05f).coerceIn(2f, 10f),
                tracerRampStartDistance = (baseLen * 0.75f).coerceIn(18f, 72f),
                tracerRampDistance = (tailLen * 0.48f).coerceIn(160f, 620f),
                tracerRampEpsilon = 1.1f,
                tracerFillStartAlpha = 0f,
                tracerFillStartFactor = 0.74f,
                tracerFillEndAlpha = 0.85f,
                tracerFillEndFactor = 1f,
                tracerShrinkOnFade = true,
                coneEnabled = false,
                particles = BoxUtilProjectileTrails.ParticleSprayStyle(enabled = false),
            )
        }

        private val singleTrailFactory = BoxUtilProjectileTrails.beamAndConeFactory { p -> createSingleTrailStyle(p) }
        private val bloomTrailFactory = BoxUtilProjectileTrails.beamAndConeFactory { p -> createBloomTrailStyle(p) }

        private val factory = ProjectileVisualFactory { engine, projectile ->
            val spec = projectile.projectileSpec
            val baseLen = (spec?.length ?: 24f).coerceAtLeast(1f) * BODY_SCALE
            val baseW = (spec?.width ?: 8f).coerceAtLeast(1f) * BODY_SCALE

            val startDist = (baseLen * 0.90f).coerceIn(26f, 110f)

            val overdriveLevel = getOverdriveLevel(projectile)
            val activeCoreColor = ArcFlareOverdriveVisualState.lerpColor(coreColor, ArcFlareOverdriveVisualState.hotCore, overdriveLevel, coreColor.alpha)
            val activeFringeColor = ArcFlareOverdriveVisualState.lerpColor(fringeColor, ArcFlareOverdriveVisualState.hotFringe, overdriveLevel, fringeColor.alpha)
            val activeWarmHazeColor = ArcFlareOverdriveVisualState.lerpColor(Color(175, 215, 255), ArcFlareOverdriveVisualState.hotCore, overdriveLevel * 0.55f, 96)
            val activeCoilRingColor = ArcFlareOverdriveVisualState.lerpColor(Color(140, 200, 255), ArcFlareOverdriveVisualState.hotCore, overdriveLevel, 115)

            // 需求更新：现在主躯体主要由“单一 TrailEntity”承担，粒子 glow 需要收敛，否则会把弹头“糊平”。
            val coreGlow = ParticleCoreGlowProjectileVisual(
                engine = engine,
                color = Color(activeCoreColor.red, activeCoreColor.green, activeCoreColor.blue, 165),
                particlesPerSecond = 92f,
                jitterRadius = (0.85f * BODY_SCALE).coerceIn(2f, 7f),
                sizeMin = (4.0f * BODY_SCALE).coerceIn(10f, 24f),
                sizeMax = (6.5f * BODY_SCALE).coerceIn(16f, 34f),
                brightnessMin = 2.05f,
                brightnessMax = 3.10f,
                durationMin = 0.05f,
                durationMax = 0.10f,
                inheritVelocityMul = 0.10f,
            )

            val fringeGlow = ParticleCoreGlowProjectileVisual(
                engine = engine,
                color = Color(activeFringeColor.red, activeFringeColor.green, activeFringeColor.blue, 118),
                particlesPerSecond = 58f,
                jitterRadius = (1.6f * BODY_SCALE).coerceIn(4f, 12f),
                sizeMin = (5.5f * BODY_SCALE).coerceIn(14f, 32f),
                sizeMax = (8.5f * BODY_SCALE).coerceIn(18f, 44f),
                brightnessMin = 1.05f,
                brightnessMax = 1.65f,
                durationMin = 0.07f,
                durationMax = 0.14f,
                inheritVelocityMul = 0.06f,
            )

            val warmHazeGlow = ParticleCoreGlowProjectileVisual(
                engine = engine,
                color = Color(activeWarmHazeColor.red, activeWarmHazeColor.green, activeWarmHazeColor.blue, activeWarmHazeColor.alpha),
                particlesPerSecond = 38f,
                jitterRadius = (2.4f * BODY_SCALE).coerceIn(5f, 15f),
                sizeMin = (8.0f * BODY_SCALE).coerceIn(18f, 42f),
                sizeMax = (11.5f * BODY_SCALE).coerceIn(24f, 54f),
                brightnessMin = 0.90f,
                brightnessMax = 1.45f,
                durationMin = 0.08f,
                durationMax = 0.18f,
                inheritVelocityMul = 0.03f,
            )

            val ionAccentGlow = ParticleCoreGlowProjectileVisual(
                engine = engine,
                color = ionAccentColor,
                particlesPerSecond = 30f,
                jitterRadius = (2.0f * BODY_SCALE).coerceIn(4f, 13f),
                sizeMin = (6.2f * BODY_SCALE).coerceIn(14f, 34f),
                sizeMax = (9.0f * BODY_SCALE).coerceIn(20f, 42f),
                brightnessMin = 0.95f,
                brightnessMax = 1.55f,
                durationMin = 0.06f,
                durationMax = 0.15f,
                inheritVelocityMul = 0.05f,
            )

            // 弹头“针尖”高亮：用少量高亮粒子在弹体前方补一个尖端，修正“平头/块状”观感。
            val noseNeedle = MissileNoseNeedleProjectileVisual(
                engine = engine,
                color = Color(activeCoreColor.red, activeCoreColor.green, activeCoreColor.blue, 170),
                particlesPerSecond = 95f,
                aheadDistance = (baseLen * 0.48f).coerceIn(22f, 90f),
                sizeMin = (baseW * 0.22f).coerceIn(6f, 14f),
                sizeMax = (baseW * 0.34f).coerceIn(9f, 20f),
                brightnessMin = 2.8f,
                brightnessMax = 4.0f,
                durationMin = 0.035f,
                durationMax = 0.070f,
                inheritVelocityMul = 0.10f,
            )

            val coilRings = PathEllipseOglShockRingEmitterProjectileVisual(
                engine = engine,
                spacingDistance = (baseW * 4.2f).coerceIn(92f, 200f),
                offsetsBehind = floatArrayOf((baseLen * 0.55f).coerceIn(24f, 180f)),
                startDistance = startDist,
                // 需求更新：椭圆环初始大小：在原始基础上先缩小到 75%，再继续减少 30% => 0.75 * 0.70 = 0.525
                aSideHalf = ((baseW * 1.75f).coerceIn(32f, 110f) * 0.525f),
                bAlongHalf = ((baseW * 0.70f).coerceIn(14f, 58f) * 0.525f),
                duration = 0.42f,
                color = activeCoilRingColor,
                lineWidthPx = 1.35f,
                segments = 80,
                expandSpeed = 20f,
                tangentialSpeed = 0f,
            )

            CompositeProjectileVisual(
                visuals = listOf(
                    bloomTrailFactory.create(engine, projectile),
                    // 唯一的 TrailEntity 曳光
                    singleTrailFactory.create(engine, projectile),
                    noseNeedle,
                    warmHazeGlow,
                    ionAccentGlow,
                    fringeGlow,
                    coreGlow,
                    coilRings,
                ),
            )
        }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            val spec = projectile.projectileSpec
            val baseW = (spec?.width ?: 8f).coerceAtLeast(1f) * BODY_SCALE
            val spawnOverdriveLevel = getOverdriveLevel(projectile)
            val spawnRingColor = ArcFlareOverdriveVisualState.lerpColor(Color(180, 220, 255), ArcFlareOverdriveVisualState.hotCore, spawnOverdriveLevel, 90)
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = projectile.location,
                baseVel = projectile.velocity,
                radius = (baseW * 0.55f).coerceIn(18f, 44f),
                particleCount = 18,
                size = 11f,
                brightness = 1.45f,
                duration = 0.14f,
                color = spawnRingColor,
            )
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    /**
     * TSM-2 / TSM-Ω：飞行尾迹参数共用，仅保留颜色差异（便于统一调参）。
     */
    private val tsmMissileOptions = ProjectileTracerManager.Options(
        // `.proj` fadeTime=0.2；略加余量
        fadeOutOnProjectileFadingSeconds = 0.24f,
        fadeOutOnProjectileRemovedSeconds = 0.10f,
        pendingTimeoutSeconds = 0.75f,
    )

    private fun createTsmMissileTrailStyle(
        coreColor: Color,
        fringeColor: Color,
        particleColorMin: Color,
        particleColorMax: Color,
    ): BoxUtilProjectileTrails.BeamAndConeStyle {
        return BoxUtilProjectileTrails.BeamAndConeStyle(
            coreColor = coreColor,
            fringeColor = fringeColor,
            // 需求：曳光宽度翻倍
            joinWidth = 20.0f,

            tracerEnabled = true,
            // 需求：曳光长度降低 40%（220 -> 132）
            tracerLength = 132f,
            tracerTailWidth = 4.4f,
            tracerHeadWidth = 20.0f,
            tracerTailAlphaMul = 0.18f,
            tracerTailEmissiveAlphaMul = 0.85f,
            tracerHeadAlphaMul = 0.95f,
            tracerHeadEmissiveAlphaMul = 1.9f,
            tracerMixPower = 2.4f,

            // 弹头：启用前锥形，解决“弹头看起来过钝”
            coneEnabled = true,
            coneLength = 30f,
            coneTipWidth = 0.45f,
            // 根部略窄于 tracer 头宽，让“尖端”更明显
            coneRootWidth = 12.0f,
            coneTipAlphaMul = 0.18f,
            coneRootAlphaMul = 0.75f,
            coneTipEmissiveAlphaMul = 1.25f,
            coneRootEmissiveAlphaMul = 2.0f,
            coneMixPower = 3.6f,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 55f,
                inheritVelocityMul = 0.06f,
                colorMin = particleColorMin,
                colorMax = particleColorMax,
                sizeMin = 10f,
                sizeMax = 18f,
                brightnessMin = 1.3f,
                brightnessMax = 2.2f,
                durationMin = 0.25f,
                durationMax = 0.45f,
                spawnJitterRadius = 6f,
                behindDistance = 12f,
                speedMin = 70f,
                speedMax = 170f,
                spreadArc = 22f,
            ),
        )
    }

    private fun createTsmMissileCompositeFactory(
        style: BoxUtilProjectileTrails.BeamAndConeStyle,
        bodyCoreColor: Color,
        bodyFringeColor: Color,
    ): ProjectileVisualFactory {
        val trailFactory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        return ProjectileVisualFactory { engine, projectile ->
            // 需求：去贴图渲染弹体。隐藏 missile sprite，本体由尾迹+粒子核承担。
            val hide = HideMissileSpriteProjectileVisual()

            // 需求：弹体尺寸 x2（用更大的“粒子核”实现，避免依赖贴图）
            val core = ParticleCoreGlowProjectileVisual(
                engine = engine,
                color = bodyCoreColor,
                particlesPerSecond = 120f,
                jitterRadius = 2.0f,
                // 头部不要发团：缩小粒子核尺寸，让前锥/针尖负责“尖”
                sizeMin = 16f,
                sizeMax = 26f,
                brightnessMin = 1.6f,
                brightnessMax = 2.4f,
                durationMin = 0.06f,
                durationMax = 0.11f,
                inheritVelocityMul = 0.10f,
            )

            val fringe = ParticleCoreGlowProjectileVisual(
                engine = engine,
                color = bodyFringeColor,
                particlesPerSecond = 70f,
                jitterRadius = 4.0f,
                sizeMin = 22f,
                sizeMax = 34f,
                brightnessMin = 0.95f,
                brightnessMax = 1.45f,
                durationMin = 0.08f,
                durationMax = 0.14f,
                inheritVelocityMul = 0.06f,
            )

            val needle = MissileNoseNeedleProjectileVisual(
                engine = engine,
                color = bodyCoreColor,
                particlesPerSecond = 140f,
                aheadDistance = 20f,
                sizeMin = 5f,
                sizeMax = 10f,
                brightnessMin = 2.2f,
                brightnessMax = 3.3f,
                durationMin = 0.04f,
                durationMax = 0.08f,
                inheritVelocityMul = 0.12f,
            )

            CompositeProjectileVisual(
                visuals = listOf(
                    trailFactory.create(engine, projectile),
                    hide,
                    core,
                    fringe,
                    needle,
                ),
            )
        }
    }

    private fun spawnTsmMissileIgnitionRing(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, color: Color) {
        // 一个很轻的“冲刺焰点火”闪光：不用依赖 weapon/ship。
        ProjectileVfxUtil.spawnRing(
            engine = engine,
            center = projectile.location,
            baseVel = projectile.velocity,
            radius = 18f,
            particleCount = 18,
            size = 10f,
            brightness = 1.6f,
            duration = 0.14f,
            color = color,
        )
    }

    internal object Spc3Shot {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 白蓝“钉刺列”
            coreColor = Color(235, 252, 255, 235),
            fringeColor = Color(140, 210, 255, 220),
            joinWidth = 9.0f,

            tracerEnabled = true,
            tracerLength = 145f,
            tracerTailWidth = 1.4f,
            tracerHeadWidth = 9.0f,
            tracerTailAlphaMul = 0.25f,
            tracerHeadAlphaMul = 0.95f,
            tracerTailEmissiveAlphaMul = 1.0f,
            tracerHeadEmissiveAlphaMul = 2.15f,
            tracerMixPower = 3.2f,

            // 更像“硬弹体”而不是火焰锥
            coneEnabled = true,
            coneLength = 18f,
            coneTipWidth = 1.0f,
            coneRootWidth = 9.0f,
            coneTipAlphaMul = 0.18f,
            coneRootAlphaMul = 0.90f,
            coneTipEmissiveAlphaMul = 0.85f,
            coneRootEmissiveAlphaMul = 1.9f,
            coneMixPower = 4.0f,
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    /**
     * 恒星喷射：系统期间额外发射的“能量弹”。
     *
     * 需求：
     * - 使用本模组 BoxUtil 拖尾风格（无需贴图弹体）。
     * - 大小随机 100~200：通过 projectile.customData 传入（纯视觉，不改物理 hitbox）。
     */
    internal object StellarJetBolt {

        const val VFX_SIZE_KEY: String = "astd_stellar_jet_bolt_vfx_size"

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.10f,
            pendingTimeoutSeconds = 0.75f,
        )

        private fun rand01(): Float = Math.random().toFloat()

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { p ->
            val sizeFromCustom = (p.customData[VFX_SIZE_KEY] as? Float)
            // 需求调整：按 DRV-11 的量级（joinWidth≈10）生成。
            val headW = (sizeFromCustom ?: lerp(8.5f, 12.5f, rand01())).coerceIn(6f, 16f)
            val scale = (headW / 10f).coerceIn(0.6f, 1.6f)

            val tailLen = (240f * scale).coerceIn(160f, 360f)
            val tailW = 0f
            val coneLen = (14f * scale).coerceIn(10f, 22f)

            BoxUtilProjectileTrails.BeamAndConeStyle(
                coreColor = Color(255, 250, 235, 220),
                fringeColor = Color(120, 200, 255, 170),

                joinWidth = headW,

                tracerEnabled = true,
                tracerLength = tailLen,
                tracerTailWidth = tailW,
                tracerHeadWidth = headW,
                // 参考 DRV-11：高亮但不糊成一坨
                tracerTailAlphaMul = 0.18f * 1.30f,
                tracerHeadAlphaMul = 0.95f * 1.30f,
                tracerTailEmissiveAlphaMul = 0.65f * 1.30f,
                tracerHeadEmissiveAlphaMul = 2.20f * 1.30f,
                tracerMixPower = 2.8f,

                coneEnabled = true,
                coneLength = coneLen,
                coneTipWidth = 1.0f,
                coneRootWidth = headW,
                coneTipAlphaMul = 0.32f * 1.30f,
                coneRootAlphaMul = 0.90f * 1.30f,
                coneTipEmissiveAlphaMul = 1.05f * 1.30f,
                coneRootEmissiveAlphaMul = 1.90f * 1.30f,
                coneMixPower = 3.2f,

                // 10 发/秒：这里不额外喷粒子，避免噪音与性能压力。
                particles = BoxUtilProjectileTrails.ParticleSprayStyle(enabled = false),
            )
        }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Drv9Slug {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 动能弹：偏冷灰蓝，整体更克制
            coreColor = Color(210, 235, 255, 185),
            fringeColor = Color(110, 170, 220, 185),
            joinWidth = 8.0f,

            tracerEnabled = true,
            tracerLength = 165f,
            tracerTailWidth = 1.2f,
            tracerHeadWidth = 8.0f,
            tracerTailAlphaMul = 0.12f,
            tracerHeadAlphaMul = 0.75f,
            tracerTailEmissiveAlphaMul = 0.55f,
            tracerHeadEmissiveAlphaMul = 1.2f,
            tracerMixPower = 2.8f,

            coneEnabled = true,
            coneLength = 20f,
            coneTipWidth = 1.0f,
            coneRootWidth = 8.0f,
            coneTipAlphaMul = 0.10f,
            coneRootAlphaMul = 0.65f,
            coneTipEmissiveAlphaMul = 0.45f,
            coneRootEmissiveAlphaMul = 1.1f,
            coneMixPower = 3.3f,
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object DrvOmegaSlug {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.24f,
            fadeOutOnProjectileRemovedSeconds = 0.10f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 相对论：更亮、更白、更“干净”
            coreColor = Color(250, 252, 255, 245),
            fringeColor = Color(140, 235, 255, 230),
            joinWidth = 12.0f,

            tracerEnabled = true,
            tracerLength = 300f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.12f,
            tracerHeadAlphaMul = 0.90f,
            tracerTailEmissiveAlphaMul = 1.0f,
            tracerHeadEmissiveAlphaMul = 2.5f,
            tracerMixPower = 2.2f,

            coneEnabled = true,
            coneLength = 22f,
            coneTipWidth = 1.0f,
            coneRootWidth = 12f,
            coneTipAlphaMul = 0.18f,
            coneRootAlphaMul = 0.95f,
            coneTipEmissiveAlphaMul = 1.2f,
            coneRootEmissiveAlphaMul = 2.3f,
            coneMixPower = 3.0f,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 30f,
                inheritVelocityMul = 0.04f,
                colorMin = Color(140, 230, 255, 25),
                colorMax = Color(255, 255, 255, 75),
                sizeMin = 6f,
                sizeMax = 12f,
                brightnessMin = 1.1f,
                brightnessMax = 2.0f,
                durationMin = 0.14f,
                durationMax = 0.26f,
                spawnJitterRadius = 4f,
                behindDistance = 14f,
                speedMin = 30f,
                speedMax = 110f,
                spreadArc = 18f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Slt4Burst {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 过驱抑制：偏青蓝、压制感更“厚”
            coreColor = Color(205, 245, 255, 205),
            fringeColor = Color(90, 200, 255, 205),
            joinWidth = 13.0f,

            tracerEnabled = true,
            tracerLength = 155f,
            tracerTailWidth = 2.2f,
            tracerHeadWidth = 13.0f,
            tracerTailAlphaMul = 0.22f,
            tracerHeadAlphaMul = 0.85f,
            tracerTailEmissiveAlphaMul = 0.65f,
            tracerHeadEmissiveAlphaMul = 1.6f,
            tracerMixPower = 2.4f,

            coneEnabled = true,
            coneLength = 22f,
            coneTipWidth = 1.0f,
            coneRootWidth = 13f,
            coneTipAlphaMul = 0.18f,
            coneRootAlphaMul = 0.85f,
            coneTipEmissiveAlphaMul = 0.75f,
            coneRootEmissiveAlphaMul = 1.6f,
            coneMixPower = 3.2f,
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object SltOmegaStream {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // Ω：更饱和更亮
            coreColor = Color(235, 255, 255, 235),
            fringeColor = Color(110, 255, 230, 220),
            joinWidth = 14.0f,

            tracerEnabled = true,
            tracerLength = 240f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.22f,
            tracerHeadAlphaMul = 0.90f,
            tracerTailEmissiveAlphaMul = 0.95f,
            tracerHeadEmissiveAlphaMul = 2.2f,
            tracerMixPower = 2.25f,

            coneEnabled = true,
            coneLength = 24f,
            coneTipWidth = 1.0f,
            coneRootWidth = 14f,
            coneTipAlphaMul = 0.22f,
            coneRootAlphaMul = 0.90f,
            coneTipEmissiveAlphaMul = 0.90f,
            coneRootEmissiveAlphaMul = 2.1f,
            coneMixPower = 3.1f,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 42f,
                inheritVelocityMul = 0.05f,
                colorMin = Color(120, 255, 220, 35),
                colorMax = Color(240, 255, 255, 90),
                sizeMin = 6f,
                sizeMax = 12f,
                brightnessMin = 1.0f,
                brightnessMax = 1.8f,
                durationMin = 0.16f,
                durationMax = 0.30f,
                spawnJitterRadius = 6f,
                behindDistance = 10f,
                speedMin = 30f,
                speedMax = 120f,
                spreadArc = 30f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Vpd6Pulse {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.18f,
            fadeOutOnProjectileRemovedSeconds = 0.06f,
            pendingTimeoutSeconds = 0.50f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 点防：更“尖”、更短、更亮
            coreColor = Color(235, 255, 255, 220),
            fringeColor = Color(140, 210, 255, 200),
            joinWidth = 7.5f,

            tracerEnabled = true,
            tracerLength = 80f,
            tracerTailWidth = 1.0f,
            tracerHeadWidth = 7.5f,
            tracerTailAlphaMul = 0.22f,
            tracerHeadAlphaMul = 0.95f,
            tracerTailEmissiveAlphaMul = 1.3f,
            tracerHeadEmissiveAlphaMul = 2.6f,
            tracerMixPower = 3.0f,

            coneEnabled = true,
            coneLength = 14f,
            coneTipWidth = 1.0f,
            coneRootWidth = 7.5f,
            coneTipAlphaMul = 0.18f,
            coneRootAlphaMul = 0.95f,
            coneTipEmissiveAlphaMul = 1.2f,
            coneRootEmissiveAlphaMul = 2.4f,
            coneMixPower = 3.6f,
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object VpdOmegaArc {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.20f,
            fadeOutOnProjectileRemovedSeconds = 0.06f,
            pendingTimeoutSeconds = 0.60f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // Ω：更像“电弧段”
            coreColor = Color(255, 255, 255, 230),
            fringeColor = Color(140, 255, 220, 215),
            joinWidth = 10.0f,

            tracerEnabled = true,
            tracerLength = 105f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.20f,
            tracerHeadAlphaMul = 0.95f,
            tracerTailEmissiveAlphaMul = 1.2f,
            tracerHeadEmissiveAlphaMul = 2.8f,
            tracerMixPower = 2.8f,

            coneEnabled = false,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 55f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(120, 255, 220, 35),
                colorMax = Color(255, 255, 255, 85),
                sizeMin = 4f,
                sizeMax = 9f,
                brightnessMin = 1.0f,
                brightnessMax = 1.8f,
                durationMin = 0.12f,
                durationMax = 0.22f,
                spawnJitterRadius = 10f,
                behindDistance = 2f,
                speedMin = 0f,
                speedMax = 80f,
                spreadArc = 160f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Fdp4Charge {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 紫红“熔核”
            coreColor = Color(255, 130, 190, 210),
            fringeColor = Color(95, 25, 80, 235),
            joinWidth = 12.0f,

            tracerEnabled = true,
            tracerLength = 120f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.22f,
            tracerHeadAlphaMul = 0.95f,
            tracerTailEmissiveAlphaMul = 0.85f,
            tracerHeadEmissiveAlphaMul = 2.2f,
            tracerMixPower = 2.8f,

            coneEnabled = true,
            coneLength = 20f,
            coneTipWidth = 1.0f,
            coneRootWidth = 12f,
            coneTipAlphaMul = 0.22f,
            coneRootAlphaMul = 0.95f,
            coneTipEmissiveAlphaMul = 1.0f,
            coneRootEmissiveAlphaMul = 2.0f,
            coneMixPower = 3.4f,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 48f,
                inheritVelocityMul = 0.04f,
                colorMin = Color(160, 60, 140, 45),
                colorMax = Color(255, 170, 230, 110),
                sizeMin = 8f,
                sizeMax = 16f,
                brightnessMin = 1.0f,
                brightnessMax = 1.8f,
                durationMin = 0.18f,
                durationMax = 0.34f,
                spawnJitterRadius = 10f,
                behindDistance = 8f,
                speedMin = 20f,
                speedMax = 95f,
                spreadArc = 65f,
            ),
        )

        private val trailFactory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        private val distortionStyle = BoxUtilProjectileDistortion.Style(
            // 注意：DistortionEntity 的尺寸是“半尺寸”。这里做得很克制，只是“热扭曲”底噪。
            sizeInHalf = 22f,
            sizeFullHalf = 16f,
            sizeOutHalf = 10f,
            powerIn = 0.16f,
            powerFull = 0.10f,
            powerOut = 0f,
            innerFullRatio = 0.38f,
            innerHardness = 0.78f,
            ringHardness = 0.55f,
            fadeInSeconds = 0.05f,
        )

        private val factory = ProjectileVisualFactory { engine, projectile ->
            val trail = trailFactory.create(engine, projectile)
            val distortion = BoxUtilProjectileDistortion.create(engine, projectile, distortionStyle)
            val glow = ParticleCoreGlowProjectileVisual(
                engine = engine,
                color = Color(255, 170, 230, 75),
                particlesPerSecond = 32f,
                jitterRadius = 5f,
                sizeMin = 10f,
                sizeMax = 16f,
                brightnessMin = 1.1f,
                brightnessMax = 1.9f,
                durationMin = 0.12f,
                durationMax = 0.22f,
                inheritVelocityMul = 0.06f,
            )

            CompositeProjectileVisual(
                listOf(
                    trail,
                    distortion,
                    glow,
                )
            )
        }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            // 轻量“裂解环”提示：真正的延迟裂解机制后续再做
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = projectile.location,
                baseVel = projectile.velocity,
                radius = 24f,
                particleCount = 24,
                size = 12f,
                brightness = 1.3f,
                duration = 0.16f,
                color = Color(210, 110, 255, 80),
            )

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Jmb2Beam {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.20f,
            fadeOutOnProjectileRemovedSeconds = 0.06f,
            pendingTimeoutSeconds = 0.60f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 淡紫噪声颗粒
            coreColor = Color(210, 190, 255, 200),
            fringeColor = Color(80, 40, 120, 220),
            joinWidth = 10.0f,

            tracerEnabled = true,
            tracerLength = 95f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.18f,
            tracerHeadAlphaMul = 0.85f,
            tracerTailEmissiveAlphaMul = 0.75f,
            tracerHeadEmissiveAlphaMul = 1.7f,
            tracerMixPower = 3.0f,

            coneEnabled = false,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 70f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(160, 130, 255, 35),
                colorMax = Color(235, 220, 255, 95),
                sizeMin = 5f,
                sizeMax = 10f,
                brightnessMin = 0.9f,
                brightnessMax = 1.5f,
                durationMin = 0.14f,
                durationMax = 0.26f,
                spawnJitterRadius = 14f,
                behindDistance = 2f,
                speedMin = 0f,
                speedMax = 60f,
                spreadArc = 180f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Jmb9Beam {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.20f,
            fadeOutOnProjectileRemovedSeconds = 0.06f,
            pendingTimeoutSeconds = 0.60f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 更偏灰白的“锁定干扰”
            coreColor = Color(235, 235, 255, 185),
            fringeColor = Color(90, 70, 120, 220),
            joinWidth = 10.0f,

            tracerEnabled = true,
            tracerLength = 105f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.16f,
            tracerHeadAlphaMul = 0.85f,
            tracerTailEmissiveAlphaMul = 0.70f,
            tracerHeadEmissiveAlphaMul = 1.7f,
            tracerMixPower = 3.1f,

            coneEnabled = false,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 55f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(210, 210, 255, 25),
                colorMax = Color(235, 220, 255, 70),
                sizeMin = 5f,
                sizeMax = 10f,
                brightnessMin = 0.9f,
                brightnessMax = 1.4f,
                durationMin = 0.14f,
                durationMax = 0.26f,
                spawnJitterRadius = 12f,
                behindDistance = 2f,
                speedMin = 0f,
                speedMax = 50f,
                spreadArc = 180f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            // 简化的“锁定圈”提示
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = projectile.location,
                baseVel = projectile.velocity,
                radius = 20f,
                particleCount = 16,
                size = 9f,
                brightness = 1.15f,
                duration = 0.14f,
                color = Color(220, 220, 255, 70),
            )

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object JmbOmegaBeam {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.20f,
            fadeOutOnProjectileRemovedSeconds = 0.06f,
            pendingTimeoutSeconds = 0.60f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // Ω：更亮、更多“扫描圈”味
            coreColor = Color(245, 245, 255, 220),
            fringeColor = Color(120, 170, 255, 215),
            joinWidth = 12.0f,

            tracerEnabled = true,
            tracerLength = 120f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.18f,
            tracerHeadAlphaMul = 0.90f,
            tracerTailEmissiveAlphaMul = 1.0f,
            tracerHeadEmissiveAlphaMul = 2.2f,
            tracerMixPower = 2.7f,

            coneEnabled = false,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 75f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(180, 220, 255, 30),
                colorMax = Color(245, 245, 255, 85),
                sizeMin = 5f,
                sizeMax = 11f,
                brightnessMin = 1.0f,
                brightnessMax = 1.7f,
                durationMin = 0.14f,
                durationMax = 0.26f,
                spawnJitterRadius = 16f,
                behindDistance = 2f,
                speedMin = 0f,
                speedMax = 65f,
                spreadArc = 180f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = projectile.location,
                baseVel = projectile.velocity,
                radius = 24f,
                particleCount = 22,
                size = 10f,
                brightness = 1.25f,
                duration = 0.16f,
                color = Color(200, 235, 255, 80),
            )

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object FtbOmegaBeam {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.20f,
            fadeOutOnProjectileRemovedSeconds = 0.06f,
            pendingTimeoutSeconds = 0.60f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 辐能转移：偏绿青
            coreColor = Color(200, 255, 235, 220),
            fringeColor = Color(80, 255, 190, 210),
            joinWidth = 12.0f,

            tracerEnabled = true,
            tracerLength = 140f,
            tracerTailWidth = 0f,
            tracerTailAlphaMul = 0.22f,
            tracerHeadAlphaMul = 0.90f,
            tracerTailEmissiveAlphaMul = 1.0f,
            tracerHeadEmissiveAlphaMul = 2.2f,
            tracerMixPower = 2.4f,

            coneEnabled = true,
            coneLength = 18f,
            coneTipWidth = 1.0f,
            coneRootWidth = 12f,
            coneTipAlphaMul = 0.20f,
            coneRootAlphaMul = 0.90f,
            coneTipEmissiveAlphaMul = 1.0f,
            coneRootEmissiveAlphaMul = 2.0f,
            coneMixPower = 3.2f,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 38f,
                inheritVelocityMul = 0.04f,
                colorMin = Color(80, 255, 190, 30),
                colorMax = Color(220, 255, 245, 90),
                sizeMin = 6f,
                sizeMax = 12f,
                brightnessMin = 1.0f,
                brightnessMax = 1.8f,
                durationMin = 0.14f,
                durationMax = 0.28f,
                spawnJitterRadius = 8f,
                behindDistance = 8f,
                speedMin = 20f,
                speedMax = 95f,
                spreadArc = 40f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Mnl3Mine {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            // 暗紫网格感
            coreColor = Color(120, 120, 255, 120),
            fringeColor = Color(40, 20, 90, 210),
            joinWidth = 14.0f,

            tracerEnabled = false,
            coneEnabled = false,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 30f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(90, 80, 255, 25),
                colorMax = Color(160, 150, 255, 75),
                sizeMin = 9f,
                sizeMax = 16f,
                brightnessMin = 0.8f,
                brightnessMax = 1.3f,
                durationMin = 0.30f,
                durationMax = 0.55f,
                spawnJitterRadius = 16f,
                behindDistance = 1f,
                speedMin = 0f,
                speedMax = 25f,
                spreadArc = 180f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            // “线框球”提示的第一步：先给一个可读的展开圈
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = projectile.location,
                baseVel = projectile.velocity,
                radius = 30f,
                particleCount = 20,
                size = 12f,
                brightness = 1.1f,
                duration = 0.20f,
                color = Color(120, 120, 255, 70),
            )

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object MnlOmegaGrid {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            coreColor = Color(160, 255, 255, 140),
            fringeColor = Color(40, 200, 160, 210),
            joinWidth = 16.0f,

            tracerEnabled = false,
            coneEnabled = false,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 42f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(80, 255, 190, 25),
                colorMax = Color(210, 255, 245, 85),
                sizeMin = 10f,
                sizeMax = 18f,
                brightnessMin = 0.9f,
                brightnessMax = 1.5f,
                durationMin = 0.30f,
                durationMax = 0.58f,
                spawnJitterRadius = 18f,
                behindDistance = 1f,
                speedMin = 0f,
                speedMax = 28f,
                spreadArc = 180f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = projectile.location,
                baseVel = projectile.velocity,
                radius = 34f,
                particleCount = 24,
                size = 14f,
                brightness = 1.2f,
                duration = 0.22f,
                color = Color(120, 255, 220, 80),
            )

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object TsmOmegaMissile {

        private val options = tsmMissileOptions

        private val style = createTsmMissileTrailStyle(
            // 紫色系（与 TSM-Ω 爆炸/引擎色一致）
            // 需求：曳光颜色更贴近引擎尾焰（engineColor=[180,120,255]）
            coreColor = Color(215, 165, 255, 245),
            fringeColor = Color(165, 95, 255, 235),
            particleColorMin = Color(145, 85, 225, 70),
            particleColorMax = Color(225, 165, 255, 165),
        )

        private val factory = createTsmMissileCompositeFactory(
            style = style,
            bodyCoreColor = Color(210, 155, 255, 215),
            bodyFringeColor = Color(160, 90, 255, 125),
        )

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            spawnTsmMissileIgnitionRing(engine, projectile, Color(225, 180, 255, 140))

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Tsm2Missile {

        private val options = tsmMissileOptions

        private val style = createTsmMissileTrailStyle(
            // 冷色离子（巡航/终端统一用“白蓝”，二段差异先留给后续机制实现）
            // 需求：缩小曳光与引擎尾焰色差（engineColor=[120,200,255]）
            coreColor = Color(195, 235, 255, 255),
            fringeColor = Color(110, 200, 255, 255),
            particleColorMin = Color(80, 160, 220, 70),
            particleColorMax = Color(200, 245, 255, 170),
        )

        private val factory = createTsmMissileCompositeFactory(
            style = style,
            bodyCoreColor = Color(205, 245, 255, 220),
            bodyFringeColor = Color(105, 190, 255, 120),
        )

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            spawnTsmMissileIgnitionRing(engine, projectile, Color(215, 245, 255, 120))

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Gsp12Rift {

        private val options = ProjectileTracerManager.Options(
            // 命中/被移除时：尽快坍缩（命中反馈更干脆）。
            fadeOutOnProjectileFadingSeconds = 0.24f,
            fadeOutOnProjectileRemovedSeconds = 0.10f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val trailStyle = BoxUtilProjectileTrails.BeamAndConeStyle(
            coreColor = Color(235, 150, 255, 140),
            fringeColor = Color(110, 35, 190, 190),
            // 需求：调宽拖尾大小（+75%）
            joinWidth = 24.5f,

            // 拖尾：同色曳光（不使用锥形弹头）。
            tracerEnabled = true,
            // 需求：拖尾调长 300%（按 +300% => 4x）
            tracerLength = 480f,
            // 需求：调宽拖尾大小（+75%）
            tracerTailWidth = 5.25f,
            tracerHeadWidth = 24.5f,
            tracerTailAlphaMul = 0.10f,
            tracerHeadAlphaMul = 0.45f,
            tracerTailEmissiveAlphaMul = 0.55f,
            tracerHeadEmissiveAlphaMul = 1.40f,
            tracerMixPower = 2.6f,
            // 让曳光“逐步拉出”，避免第一帧就出现整条固定长度线。
            tracerMinLength = 72f,
            tracerRampStartDistance = 0f,
            tracerRampDistance = 368f,

            // 参考 AOD：弹体移除后尾迹逐渐缩短，避免“整条线硬性留到最后”
            tracerShrinkOnFade = true,

            coneEnabled = false,

            // 伴随粒子拖尾
            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 55f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(160, 110, 255, 45),
                colorMax = Color(235, 150, 255, 110),
                sizeMin = 6f,
                sizeMax = 12f,
                brightnessMin = 0.9f,
                brightnessMax = 1.5f,
                // 需求：粒子拖尾调长 300%（按 +300% => 4x）
                durationMin = 1.00f,
                durationMax = 2.00f,
                spawnJitterRadius = 10f,
                behindDistance = 64f,
                speedMin = 25f,
                speedMax = 90f,
                spreadArc = 50f,
            ),
        )

        private val trailFactory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> trailStyle }

        private val factory = ProjectileVisualFactory { engine, projectile ->
            // 反馈：扭曲会盖住发光；这里暂时移除跟随扭曲，改为“发光弹体 + 辐射光”。
            // 发光弹体：稳定存在，并在飞行期间随机脉动变大/变小。
            val glowBody = PulsingGlowProjectileVisual(
                engine = engine,
                // 核心白核过亮会“炸屏”，这里把 alpha/亮度与尺寸整体压下来。
                color = Color(220, 125, 255, 165),
                particlesPerSecond = 120f,
                // 原先 2x 放大在叠加 bloom 后观感过激进：下调但仍保持“更大于普通能量弹”。
                // 需求：弹体本体大小 +50%
                baseSizeMin = 93f,
                baseSizeMax = 144f,
                brightnessMin = 1.9f,
                brightnessMax = 3.4f,
                durationMin = 0.06f,
                durationMax = 0.11f,
                inheritVelocityMul = 0.04f,
                pulseMinScale = 0.72f,
                pulseMaxScale = 1.38f,
                pulseRetargetMinSeconds = 0.06f,
                pulseRetargetMaxSeconds = 0.16f,
                pulseLerpSpeed = 9.5f,
                jitterRadius = 1.2f,
            )

            // 拖尾：同色曳光 + 粒子尾焰
            val trail = trailFactory.create(engine, projectile)?.let {
                // 需求：弹体消失后拖尾不应立即消失（参考 AOD-7 的策略：忽略 FADING，仅在 REMOVED 后更长淡出）
                FadeDurationOverrideProjectileVisual(
                    delegate = it,
                    ignoreProjectileFading = true,
                    fadeOutOnProjectileRemovedSeconds = 0.65f,
                )
            }

            // 反馈/新方向：将“辐射光刺”替换为固定大小的十字光（类似透镜星芒）。
            // 默认不随弹体旋转，整体观感更稳定、更像 UI/镜头光晕。
            val crossFlare = OglCrossFlareProjectileVisual(
                engine = engine,
                // 需求：十字光亮度 -50%（同时下调颜色 alpha + flicker 强度）
                coreColor = Color(235, 150, 255, 92),
                fringeColor = Color(110, 35, 190, 98),
                // 需求：十字光特效减小（-33%）
                longLength = 99f,
                shortLength = 99f,
                baseWidth = 14.6f,
                tipWidth = 1.06f,
                baseAngleDeg = 0f,
                followVelocityFacing = false,
                // 需求：十字光旋转速度 +100%
                rotationDegPerSecond = -40f,
                // 需求：亮度随时间随机变亮/变暗
                // 需求：十字光亮度 -50%
                flickerMinMul = 0.40f,
                flickerMaxMul = 0.59f,
                flickerRetargetMinSeconds = 0.12f,
                flickerRetargetMaxSeconds = 0.28f,
                flickerLerpSpeed = 4.2f,
            )
            val coreGlow = ParticleCoreGlowProjectileVisual(
                engine = engine,
                // 核心：保留，但更集中、更短寿命，避免“散发状一团糊”
                color = Color(205, 105, 255, 200),
                particlesPerSecond = 44f,
                jitterRadius = 7.2f,
                sizeMin = 20f,
                sizeMax = 36f,
                brightnessMin = 2.25f,
                brightnessMax = 4.25f,
                durationMin = 0.06f,
                durationMax = 0.12f,
                inheritVelocityMul = 0.05f,
            )

            // 优化：移除粒子环，仅保留“弹体本体 + 拖尾 + 十字光 + 核心粒子”。
            CompositeProjectileVisual(listOf(glowBody, trail, crossFlare, coreGlow))
        }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Sgl8Swarm {
        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            // 统一观感：蜂群弹体也走“新星”黑洞外观，避免回退到长曳光。
            SingularityNovaMissile.onSpawn(engine, projectile)
        }
    }

    /**
     * 奇点投射器（中）：新星
        * - BoxUtil：黑洞飞行外观（SpriteEntity）+ 跟随扭曲（DistortionEntity）
     * - 被击落时：触发短促扭曲+光刺自爆视觉（见 [SingularityShotDownDetonationVisual]）
     */
    internal object SingularityNovaMissile {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.24f,
            fadeOutOnProjectileRemovedSeconds = 0.10f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val distortionStyle = BoxUtilProjectileDistortion.Style(
            // 让扭曲覆盖“黑核+厚环”整体；更像黑洞透镜而不是弹头抖动。
            sizeInHalf = 26f,
            sizeFullHalf = 64f,
            sizeOutHalf = 18f,
            powerIn = 0.26f,
            powerFull = 0.42f,
            powerOut = 0.07f,
            innerFullRatio = 0.20f,
            innerHardness = 0.80f,
            ringHardness = 0.60f,
            fadeInSeconds = 0.06f,
            fullSeconds = 9999f,
        )

        private val outerDistortionStyle = BoxUtilProjectileDistortion.Style(
            // 外圈“柔性透镜”：更大、更软，叠在内核扭曲外面，增强黑洞感。
            sizeInHalf = 52f,
            sizeFullHalf = 120f,
            sizeOutHalf = 44f,
            powerIn = 0.05f,
            powerFull = 0.12f,
            powerOut = 0.03f,
            innerFullRatio = 0.76f,
            innerHardness = 0.42f,
            ringHardness = 0.28f,
            fadeInSeconds = 0.06f,
            fullSeconds = 9999f,
        )

        private val factory = ProjectileVisualFactory { engine, projectile ->
            // 关键：如果 BoxUtil 还未 ready，accretion/distortion 会返回 null。
            // 此时若仍返回一个非 null 的 Composite（哪怕只有 HideMissileSprite），TracerManager 将不会进入 pending 重试，
            // 结果就是“导弹被隐藏但没有任何 VFX”。
            // 因此：关键视觉未创建成功时直接返回 null，让 TracerManager 在 pending 窗口内重试。
            BoxUtilCombatVfx.ensureReady(engine)

            val accretion = SingularityAccretionDiskVisual.create(engine, projectile, SingularityDetonationFx.Variant.NOVA) ?: return@ProjectileVisualFactory null
            val outerDistortion = BoxUtilProjectileDistortion.create(engine, projectile, outerDistortionStyle) ?: return@ProjectileVisualFactory null
            val distortion = BoxUtilProjectileDistortion.create(engine, projectile, distortionStyle) ?: return@ProjectileVisualFactory null

            CompositeProjectileVisual(
                visuals = listOf(
                    HideMissileSpriteProjectileVisual(),
                    accretion,
                    // 先外圈、后内核：外圈更柔，内核更硬。
                    outerDistortion,
                    distortion,
                    SingularityRetargetPulseVisual(engine, SingularityDetonationFx.Variant.NOVA),
                    SingularityShotDownDetonationVisual(engine, SingularityDetonationFx.Variant.NOVA),
                ),
            )
        }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    /** 奇点投射器（大）：事件视界（更大、更亮）。 */
    internal object SingularityEventHorizonMissile {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.26f,
            fadeOutOnProjectileRemovedSeconds = 0.12f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val distortionStyle = BoxUtilProjectileDistortion.Style(
            // 更大、更亮：事件视界的扭曲覆盖范围更广。
            sizeInHalf = 40f,
            sizeFullHalf = 100f,
            sizeOutHalf = 28f,
            powerIn = 0.34f,
            powerFull = 0.55f,
            powerOut = 0.09f,
            innerFullRatio = 0.18f,
            innerHardness = 0.86f,
            ringHardness = 0.62f,
            fadeInSeconds = 0.06f,
            fullSeconds = 9999f,
        )

        private val outerDistortionStyle = BoxUtilProjectileDistortion.Style(
            // 事件视界的外圈透镜范围更广，但强度更克制，避免“整屏抖”。
            sizeInHalf = 86f,
            sizeFullHalf = 190f,
            sizeOutHalf = 74f,
            powerIn = 0.06f,
            powerFull = 0.14f,
            powerOut = 0.04f,
            innerFullRatio = 0.74f,
            innerHardness = 0.40f,
            ringHardness = 0.26f,
            fadeInSeconds = 0.06f,
            fullSeconds = 9999f,
        )

        private val factory = ProjectileVisualFactory { engine, projectile ->
            BoxUtilCombatVfx.ensureReady(engine)

            val accretion = SingularityAccretionDiskVisual.create(engine, projectile, SingularityDetonationFx.Variant.EVENT_HORIZON) ?: return@ProjectileVisualFactory null
            val outerDistortion = BoxUtilProjectileDistortion.create(engine, projectile, outerDistortionStyle) ?: return@ProjectileVisualFactory null
            val distortion = BoxUtilProjectileDistortion.create(engine, projectile, distortionStyle) ?: return@ProjectileVisualFactory null

            CompositeProjectileVisual(
                visuals = listOf(
                    HideMissileSpriteProjectileVisual(),
                    accretion,
                    outerDistortion,
                    distortion,
                    SingularityRetargetPulseVisual(engine, SingularityDetonationFx.Variant.EVENT_HORIZON),
                    SingularityShotDownDetonationVisual(engine, SingularityDetonationFx.Variant.EVENT_HORIZON),
                ),
            )
        }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }

    internal object Mnl2Mine {

        private val options = ProjectileTracerManager.Options(
            fadeOutOnProjectileFadingSeconds = 0.22f,
            fadeOutOnProjectileRemovedSeconds = 0.08f,
            pendingTimeoutSeconds = 0.75f,
        )

        private val style = BoxUtilProjectileTrails.BeamAndConeStyle(
            coreColor = Color(170, 120, 255, 140),
            fringeColor = Color(50, 20, 90, 210),
            joinWidth = 14.0f,

            // 网雷：不画“弹道线”，更像一个不稳定相位体
            tracerEnabled = false,
            coneEnabled = false,

            particles = BoxUtilProjectileTrails.ParticleSprayStyle(
                enabled = true,
                emitWhileFading = false,
                debugForceVisible = false,
                particlesPerSecond = 26f,
                inheritVelocityMul = 0.02f,
                colorMin = Color(120, 70, 255, 40),
                colorMax = Color(230, 210, 255, 110),
                sizeMin = 10f,
                sizeMax = 18f,
                brightnessMin = 0.8f,
                brightnessMax = 1.4f,
                durationMin = 0.35f,
                durationMax = 0.60f,
                spawnJitterRadius = 18f,
                behindDistance = 1f,
                speedMin = 0f,
                speedMax = 25f,
                spreadArc = 180f,
            ),
        )

        private val factory = BoxUtilProjectileTrails.beamAndConeFactory { _ -> style }

        fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
            // 相位捕获环（提示“封路范围”的第一步；后续可换成真正的范围可读 UI）
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = projectile.location,
                baseVel = projectile.velocity,
                radius = 34f,
                particleCount = 22,
                size = 14f,
                brightness = 1.2f,
                duration = 0.22f,
                color = Color(160, 110, 255, 80),
            )

            ProjectileTracerManager.track(
                engine = engine,
                projectile = projectile,
                options = options,
                factory = factory,
            )
        }
    }
}

internal object ProjectileVfxUtil {

    fun spawnRing(
        engine: CombatEngineAPI,
        center: Vector2f,
        baseVel: Vector2f?,
        radius: Float,
        particleCount: Int,
        size: Float,
        brightness: Float,
        duration: Float,
        color: Color,
    ) {
        val n = particleCount.coerceIn(6, 64)
        val vel = baseVel?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
        for (i in 0 until n) {
            val ang = (i * (360f / n.toFloat())) + MathUtils.getRandomNumberInRange(-2.5f, 2.5f)
            val loc = MathUtils.getPointOnCircumference(center, radius, ang)
            engine.addSmoothParticle(loc, vel, size, brightness, duration, color)
        }
    }
}
