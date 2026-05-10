package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.boxutil.BoxUtilProjectileTrails

import cn.kasuminova.astd.combat.effect.generic.projectile.CodeProjectileRenderer.onSpawn
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.loading.ProjectileSpawnType
import java.awt.Color
import kotlin.math.max

/**
 * 默认“代码弹体渲染器”：用于把绝大多数 projectile 的显示从贴图 bulletSprite 迁移到纯代码（BoxUtil TrailEntity）。
 *
 * 用法：在弹体生成时调用 [onSpawn] 即可（通常由 [ProjectileSpecOnFireDispatcher] 分发）。
 */
internal object CodeProjectileRenderer {

    /**
     * 通用弹体“烟雾消散线”总开关。
     *
     * 用途：快速 A/B 测试旧版纯锥形曳光与新版烟雾线视觉。
     * 关闭后仍保留基础 BoxUtil tracer/cone，本体可见性不受影响。
     */
    private const val SMOKY_SIDE_TRAILS_ENABLED = true

    /** 两条淡色侧线开关。用于图 1/2/3 风格。 */
    private const val SMOKY_SIDE_LINES_ENABLED = true

    /** 大烟雾消散带开关。用于图 4 风格；高射速小弹体可关闭以降低画面密度。 */
    private const val SMOKY_RIBBON_ENABLED = true

    /** 装饰闪丝开关。会增加少量短线，帮助打破“纯渐变锥体”的硬感。 */
    private const val SMOKY_DECOR_ENABLED = true

    private val options = ProjectileTracerManager.Options(
        fadeOutOnProjectileFadingSeconds = 0.22f,
        fadeOutOnProjectileRemovedSeconds = 0.08f,
        pendingTimeoutSeconds = 0.75f,
    )

    private val factory = BoxUtilProjectileTrails.beamAndConeFactory { p ->
        val spec = p.projectileSpec
        val core = spec?.coreColor ?: Color(220, 245, 255, 255)
        val fringe = spec?.fringeColor ?: Color(120, 200, 255, 255)
        val darkCore = darkenForTail(core, alpha = 118)
        val darkFringe = darkenForTail(fringe, alpha = 92)

        val w = (spec?.width ?: 8f).coerceAtLeast(1f)
        val l = (spec?.length ?: 24f).coerceAtLeast(1f)
        val speed = p.moveSpeed.coerceAtLeast(0f)

        val spawnType = spec?.spawnType ?: p.spawnType

        // 让大部分弹体“看起来更像子弹/能量体”：
        // - tracerLength 与速度正相关
        // - joinWidth 与弹体 width 正相关
        val joinWidth = (w * 1.15f).coerceIn(6f, 18f)

        val tracerLengthBase = (speed * 0.25f).coerceIn(90f, 220f)
        val tracerLength = when (spawnType) {
            ProjectileSpawnType.MISSILE -> (tracerLengthBase * 0.85f).coerceIn(80f, 190f)
            else -> tracerLengthBase
        }

        val coneLength = (l * 0.60f).coerceIn(w * 1.1f, w * 2.6f)

        BoxUtilProjectileTrails.BeamAndConeStyle(
            coreColor = core,
            fringeColor = fringe,

            joinWidth = joinWidth,

            tracerEnabled = true,
            tracerLength = tracerLength,
            tracerTailWidth = (joinWidth * 0.22f).coerceAtLeast(1.2f),
            tracerHeadWidth = joinWidth,
            tracerTailAlphaMul = 0.20f,
            tracerHeadAlphaMul = 0.95f,
            tracerTailEmissiveAlphaMul = 0.80f,
            tracerHeadEmissiveAlphaMul = 1.85f,
            tracerMixPower = 2.45f,
            tracerTailCoreColor = darkCore,
            tracerTailFringeColor = darkFringe,
            tracerTextureSpeed = -130f,
            tracerTexturePixels = (tracerLength * 0.72f).coerceIn(72f, 180f),
            tracerJitterPower = 0.025f,

            coneEnabled = true,
            coneLength = coneLength,
            coneTipWidth = 1.0f,
            coneRootWidth = joinWidth,
            coneTipAlphaMul = 0.30f,
            coneRootAlphaMul = 0.95f,
            coneTipEmissiveAlphaMul = 1.05f,
            coneRootEmissiveAlphaMul = 1.85f,
            coneMixPower = 3.0f,

            coneFillStartAlpha = 0f,
            coneFillStartFactor = 0.72f,
            coneFillEndAlpha = 1f,
            coneFillEndFactor = 1f,
        )
    }

    /**
     * 新版烟雾线层工厂：与基础 tracer/cone 叠加。
     *
     * 参数说明：
     * - `sideLength/smokeLength` 跟弹速正相关，保证高速弹有足够长的残影。
     * - `sideOffset` 以弹体宽度为基准，过大看起来像三叉线，过小会被主曳光盖住。
     * - `tailCoreColor/tailFringeColor` 使用同色系深色，表现“颜色变深后消散”。
     * - `nodeCount/noiseAmplitude/noiseWavelength` 是烟雾感核心参数；调参时优先改这三项。
     */
    private val smokyFactory = ProjectileVisualFactory { engine, projectile ->
        if (!SMOKY_SIDE_TRAILS_ENABLED) return@ProjectileVisualFactory null

        val spec = projectile.projectileSpec
        val core = spec?.coreColor ?: Color(220, 245, 255, 255)
        val fringe = spec?.fringeColor ?: Color(120, 200, 255, 255)

        val w = (spec?.width ?: 8f).coerceAtLeast(1f)
        val speed = projectile.moveSpeed.coerceAtLeast(0f)
        val spawnType = spec?.spawnType ?: projectile.spawnType
        val scale = when (spawnType) {
            ProjectileSpawnType.MISSILE -> 0.82f
            else -> 1f
        }

        val sideLen = (speed * 0.31f * scale).coerceIn(120f, 300f)
        val smokeLen = (sideLen * 1.34f).coerceIn(160f, 430f)
        val sideOffset = (w * 0.86f).coerceIn(4.8f, 13.0f)
        val sideHeadW = (w * 0.34f).coerceIn(1.6f, 4.2f)
        val smokeHeadW = (w * 1.28f).coerceIn(6.5f, 22f)

        BoxUtilSmokySideTrailProjectileVisual.create(
            engine = engine,
            projectile = projectile,
            style = BoxUtilSmokySideTrailProjectileVisual.Style(
                enabled = SMOKY_SIDE_TRAILS_ENABLED,
                sideLinesEnabled = SMOKY_SIDE_LINES_ENABLED,
                smokeRibbonEnabled = SMOKY_RIBBON_ENABLED,
                decorEnabled = SMOKY_DECOR_ENABLED,
                nodeCount = 14,
                sideLength = sideLen,
                smokeLength = smokeLen,
                sideOffset = sideOffset,
                smokeOffset = 0f,
                sideHeadWidth = sideHeadW,
                sideTailWidth = (sideHeadW * 0.28f).coerceAtLeast(0.45f),
                smokeHeadWidth = smokeHeadW,
                smokeTailWidth = (smokeHeadW * 0.22f).coerceAtLeast(1.4f),
                headCoreColor = Color(core.red, core.green, core.blue, 152),
                headFringeColor = Color(fringe.red, fringe.green, fringe.blue, 128),
                tailCoreColor = darkenForTail(core, alpha = 88),
                tailFringeColor = darkenForTail(fringe, alpha = 66),
                sideHeadAlpha = 0.38f,
                sideTailAlpha = 0.050f,
                sideHeadEmissive = 0.54f,
                sideTailEmissive = 0.018f,
                smokeHeadAlpha = 0.18f,
                smokeTailAlpha = 0.040f,
                smokeHeadEmissive = 0.16f,
                smokeTailEmissive = 0.0f,
                noiseAmplitude = max(3.0f, w * 0.62f).coerceIn(3f, 8.5f),
                noiseWavelength = sideLen * 0.42f,
                noiseScrollSpeed = (speed * 0.055f).coerceIn(42f, 115f),
                textureSpeed = -135f,
                texturePixels = (sideLen * 0.62f).coerceIn(82f, 180f),
                jitterPower = 0.035f,
                decorChancePerSecond = 5.5f,
                decorLengthMin = (sideLen * 0.10f).coerceIn(20f, 38f),
                decorLengthMax = (sideLen * 0.28f).coerceIn(42f, 92f),
                decorWidth = (w * 0.16f).coerceIn(0.8f, 1.7f),
                decorLife = 0.11f,
            ),
        )
    }

    private val compositeFactory = ProjectileVisualFactory { engine, projectile ->
        CompositeProjectileVisual(
            listOf(
                factory.create(engine, projectile),
                smokyFactory.create(engine, projectile),
            ),
        )
    }

    private fun darkenForTail(color: Color, alpha: Int): Color {
        val maxChannel = max(color.red, max(color.green, color.blue)).coerceAtLeast(1)
        val preserveHue = 0.18f
        val floor = 6
        return Color(
            (floor + color.red.toFloat() / maxChannel.toFloat() * 54f * preserveHue).toInt().coerceIn(0, 255),
            (floor + color.green.toFloat() / maxChannel.toFloat() * 54f * preserveHue).toInt().coerceIn(0, 255),
            (floor + color.blue.toFloat() / maxChannel.toFloat() * 54f * preserveHue).toInt().coerceIn(0, 255),
            alpha.coerceIn(0, 255),
        )
    }

    fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        projectile.setCustomData(ProjectileVfxKeys.PROJECTILE_VFX_COMMON_FX_SKIP, true)
        ProjectileTracerManager.track(
            engine = engine,
            projectile = projectile,
            options = options,
            factory = compositeFactory,
        )
    }
}
