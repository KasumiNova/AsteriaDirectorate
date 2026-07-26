package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f

/**
 * 几何数学测试共用夹具。
 *
 * 背景：旧管线删除后，原先取自 `ASTDProjectileVfxPresetCatalog.preset("aod7_shot")` 的层 spec
 * 改由本文件直接构造（数值与旧 preset 一致；新管线 aod7 DSL 亦按同一组值 1:1 书写）。
 */

/** 固定渲染上下文（沿用旧 RenderGraphTest 的测试值）。 */
internal fun testContext(elapsed: Float = 0f): ASTDProjectileVfxRenderContext = ASTDProjectileVfxRenderContext(
    location = Vector2f(10f, 20f),
    velocityFacing = 5f,
    projectileFacing = 3f,
    renderFacing = 5f,
    elapsed = elapsed,
    logicElapsed = elapsed,
    flightProgress = 0.5f,
    dissolve = 0.1f,
    visibleLength = 120f,
    beamAlpha = 0.8f,
    historyNodes = listOf(
        ASTDProjectileHistoryNode(Vector2f(0f, 0f), 0f, 0f),
        ASTDProjectileHistoryNode(Vector2f(10f, 0f), 0f, 0.1f),
    ),
    presetId = "test_preset",
    projectileSpecId = "test_projectile",
)

/** 旧 aod7_shot preset 的层 spec 与 lifecycle 标量。 */
internal object Aod7Fixture {

    private val trailLayer = ASTDTrailLayerSpec(
        width = 40f,
        color = ASTDColor(0.278431f, 0.556863f, 0.921569f, 0.92f),
        length = 420f,
        startColor = ASTDColor(0.278431f, 0.556863f, 0.921569f, 0.92f),
        endColor = ASTDColor(0.039216f, 0.141176f, 0.219608f, 0.06f),
        startEmissive = ASTDColor(0.941176f, 0.972549f, 1f, 1f),
        endEmissive = ASTDColor(0.039216f, 0.2f, 0.458824f, 0.16f),
        startWidth = 40f,
        endWidth = 4f,
        texturePixels = 96f,
        textureSpeed = 0.9f,
        uvOffset = 0f,
        fillStartAlpha = 0.84f,
        fillEndAlpha = 0.03f,
        fillStartFactor = 0.02f,
        fillEndFactor = 0.12f,
        jitterPower = 0f,
        flick = false,
        syncFlick = false,
        stripLineMode = true,
        flowWhenPaused = true,
        flickWhenPaused = true,
        flickMixValue = 0f,
        flickerSyncCode = 17,
        blendMode = "additive",
    )

    private val ribbonDecoration = ASTDTrailRibbonDecorationSpec(
        id = "astd_default_ribbon_0",
        enabled = true,
        renderMode = "byLength",
        startOffset = 0f,
        endOffset = 0f,
        thickness = 0.1f,
        alphaScale = 0.28f,
        lengthScale = 1f,
        nodeCountScale = 1f,
        frequency = 1.1f,
        amplitude = 1.35f,
        waveSpeed = 1f,
        waveType = "noise",
        noiseScale = 4f,
        blur = 9f,
        startColor = ASTDColor(0.890196f, 0.921569f, 0.933333f, 0.92f),
        endColor = ASTDColor(0.039216f, 0.109804f, 0.219608f, 0.06f),
        color = ASTDColor(1f, 1f, 1f, 0.92f),
    )

    val trailEntities: List<ASTDTrailEntitySpec> = listOf(
        ASTDTrailEntitySpec(
            layerId = "astd_default_trail",
            id = "astd_default_trail",
            nodes = emptyList(),
            layerSpec = trailLayer,
            layers = listOf(trailLayer),
            ribbonDecorations = listOf(ribbonDecoration),
            orientationMode = ASTDProjectileVfxOrientationMode.ProjectileVelocity,
            anchorMode = ASTDProjectileVfxAnchorMode.HeadLocked,
        ),
    )

    val headLayers: List<ASTDProjectileVfxHeadLayerSpec> = listOf(
        ASTDProjectileVfxHeadLayerSpec(
            id = "astd_default_head_0",
            enabled = true,
            length = 138f,
            width = 24f,
            shoulderRatio = 0.5f,
            rearRatio = 0.95f,
            shellColorStart = ASTDColor(0.22f, 0.04f, 0.18f, 0.08f),
            shellColorMid = ASTDColor(0.72f, 0.94f, 1f, 0.46f),
            shellColorEnd = ASTDColor(1f, 1f, 1f, 0.98f),
            blur = 0.35f,
            alphaScale = 1f,
        ),
    )

    val glowLayers: List<ASTDProjectileVfxGlowLayerSpec> = listOf(
        ASTDProjectileVfxGlowLayerSpec("astd_default_glow_0", widthScale = 5.4f, alphaScale = 0.18f, blur = 34f, yOffset = -0.36f, colorMixTail = 0.52f, colorMixHead = 0.44f),
        ASTDProjectileVfxGlowLayerSpec("astd_default_glow_1", widthScale = 3.2f, alphaScale = 0.30f, blur = 18f, yOffset = 0.22f, colorMixTail = 0.52f, colorMixHead = 0.44f),
        ASTDProjectileVfxGlowLayerSpec("astd_default_glow_2", widthScale = 1.4f, alphaScale = 0.58f, blur = 7f, yOffset = -0.08f, colorMixTail = 0.22f, colorMixHead = 1f),
        ASTDProjectileVfxGlowLayerSpec("astd_default_glow_3", widthScale = 0.62f, alphaScale = 0.82f, blur = 4f, yOffset = 0f, colorMixTail = 0.48f, colorMixHead = 1f),
    )

    val mistLayers: List<ASTDProjectileVfxMistLayerSpec> = listOf(
        ASTDProjectileVfxMistLayerSpec(
            id = "astd_default_mist_0",
            enabled = true,
            blobCount = 52,
            lengthScale = 1f,
            widthScale = 1f,
            rxRange = ASTDFloatRangeSpec(2.4f, 7.2f),
            ryRange = ASTDFloatRangeSpec(0.45f, 1.8f),
            alphaRange = ASTDFloatRangeSpec(0.016f, 0.075f),
            noiseScale = 5.2f,
            driftSpeed = 0.32f,
            colorStart = ASTDColor(0.22f, 0.04f, 0.18f, 0.06f),
            colorEnd = ASTDColor(1f, 0.95f, 0.98f, 1f),
        ),
    )

    val sideWispLayers: List<ASTDProjectileVfxSideWispLayerSpec> = listOf(
        ASTDProjectileVfxSideWispLayerSpec(
            id = "astd_default_side_wisp_0",
            enabled = true,
            offsets = listOf(-2.1f, -1.36f, 1.28f, 2f),
            widthScale = 0.2f,
            alphaScale = 0.24f,
            blur = 10f,
            lengthStartRatio = 0.64f,
            lengthEndRatio = 0.28f,
            color = ASTDColor(0.45f, 0.7f, 1f, 0.72f),
        ),
    )

    val ribbonDecorations: List<ASTDTrailRibbonDecorationSpec> = listOf(ribbonDecoration)

    /** 旧 aod7 preset 的 lifecycle 标量。 */
    object lifecycle {
        const val durationSeconds: Float = 1.25f
        const val flightEndRatio: Float = 0.6f
        const val dissolveStartRatio: Float = 0.6f
        const val preDissolveFraction: Float = 0.82f
        const val projectileHeadSizeScale: Float = 1.5f
        const val layoutReferenceWidth: Float = 1846f
    }
}
