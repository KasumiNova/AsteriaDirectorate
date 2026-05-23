package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentSpec
import cn.kasuminova.astd.renderer.projectile.reload.ASTDProjectileVfxHotReloadManager
import cn.kasuminova.astd.renderer.projectile.reload.ASTDProjectileVfxHotReloadSource
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ASTDProjectileVfxPresetCatalog {
    private const val DEFAULT_TRAIL_ID = "astd_default_trail"

    private val builtInPresets: Map<String, ASTDProjectileVfxPreset> = listOf(
        aod7Shot(),
        preset("spc3_shot", violet(), 6f, 135f),
        preset("drv9_slug", amber(), 10f, 190f),
        preset("drv11", amber(), 12f, 230f, glowScale = 2.6f),
        preset("drv_omega_slug", omega(), 14f, 260f, glowScale = 3.0f),
        preset("slt3_pulse", blue(), 8f, 170f, ribbon = true),
        preset("slt4_burst", blue(), 9f, 190f, ribbon = true),
        preset("slt_omega_stream", omega(), 8f, 240f, ribbon = true),
        preset("vpd6_pulse", teal(), 8f, 180f),
        preset("vpd_omega_arc", omega(), 9f, 220f, ribbon = true),
        preset("rct6", rose(), 16f, 280f, head = true),
        preset("singularity_event_horizon_missile", singularity(), 18f, 310f, glowScale = 3.4f, head = true),
        preset("tsm_omega_missile", omega(), 18f, 330f, glowScale = 3.3f, head = true),
        preset("gsp12_rift", singularity(), 18f, 280f, glowScale = 3.1f, ribbon = true),
        preset("jmb2_beam", teal(), 12f, 260f, glowScale = 2.5f),
        preset("jmb9_beam", blue(), 13f, 280f, glowScale = 2.6f),
        preset("jmb_omega_beam", omega(), 15f, 330f, glowScale = 3.0f),
        preset("singularity_nova_missile", singularity(), 20f, 340f, glowScale = 3.6f, head = true),
        preset("stellar_jet_bolt", stellar(), 10f, 240f, glowScale = 2.4f),
        preset("fdp4_charge", amber(), 14f, 250f, glowScale = 2.6f, head = true),
        preset("ftb_omega_beam", omega(), 16f, 350f, glowScale = 3.2f),
        preset("mnl2_mine", teal(), 13f, 210f, glowScale = 2.4f, head = true),
        preset("mnl3_mine", blue(), 14f, 230f, glowScale = 2.5f, head = true),
        preset("mnl_omega_grid", omega(), 15f, 260f, glowScale = 3.0f, ribbon = true, head = true),
    ).associateBy { it.id }

    private val activePresets = AtomicReference(builtInPresets)
    private val activeVersion = AtomicLong(1)

    fun preset(id: String): ASTDProjectileVfxPreset? = activePresets.get()[id]

    fun presetIds(): Set<String> = activePresets.get().keys

    fun version(): Long = activeVersion.get()

    fun reloadForDev(source: ASTDProjectileVfxHotReloadSource): Int {
        val reloaded = ASTDProjectileVfxHotReloadManager.reload(source)
        activePresets.set(reloaded.presets)
        activeVersion.set(reloaded.version)
        return reloaded.presets.size
    }

    internal fun resetForTests() {
        activePresets.set(builtInPresets)
        activeVersion.set(1)
    }

    private fun aod7Shot(): ASTDProjectileVfxPreset {
        val trailLayer = ASTDTrailLayerSpec(
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
        val ribbonDecoration = ASTDTrailRibbonDecorationSpec(
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
        val headLayers = listOf(
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
        val glowLayers = listOf(
            ASTDProjectileVfxGlowLayerSpec("astd_default_glow_0", widthScale = 5.4f, alphaScale = 0.18f, blur = 34f, yOffset = -0.36f, colorMixTail = 0.52f, colorMixHead = 0.44f),
            ASTDProjectileVfxGlowLayerSpec("astd_default_glow_1", widthScale = 3.2f, alphaScale = 0.30f, blur = 18f, yOffset = 0.22f, colorMixTail = 0.52f, colorMixHead = 0.44f),
            ASTDProjectileVfxGlowLayerSpec("astd_default_glow_2", widthScale = 1.4f, alphaScale = 0.58f, blur = 7f, yOffset = -0.08f, colorMixTail = 0.22f, colorMixHead = 1f),
            ASTDProjectileVfxGlowLayerSpec("astd_default_glow_3", widthScale = 0.62f, alphaScale = 0.82f, blur = 4f, yOffset = 0f, colorMixTail = 0.48f, colorMixHead = 1f),
        )
        val mistLayers = listOf(
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
        val sideWispLayers = listOf(
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
        return ASTDProjectileVfxPreset(
            id = "aod7_shot",
            components = listOf(
                ASTDProjectileVfxComponentSpec.Trail(
                    id = DEFAULT_TRAIL_ID,
                    layer = trailLayer,
                    orientationMode = ASTDProjectileVfxOrientationMode.ProjectileVelocity,
                    anchorMode = ASTDProjectileVfxAnchorMode.HeadLocked,
                ),
                ASTDProjectileVfxComponentSpec.Mist(
                    id = "astd_default_mist",
                    trailId = DEFAULT_TRAIL_ID,
                    layers = mistLayers,
                ),
                ASTDProjectileVfxComponentSpec.Glow(
                    id = "astd_default_glow",
                    trailId = DEFAULT_TRAIL_ID,
                    layers = glowLayers,
                ),
                ASTDProjectileVfxComponentSpec.Body(
                    id = "astd_default_body",
                    trailId = DEFAULT_TRAIL_ID,
                ),
                ASTDProjectileVfxComponentSpec.SideWisp(
                    id = "astd_default_side_wisp",
                    trailId = DEFAULT_TRAIL_ID,
                    layers = sideWispLayers,
                ),
                ASTDProjectileVfxComponentSpec.Head(
                    id = "astd_default_head",
                    trailId = DEFAULT_TRAIL_ID,
                    layers = headLayers,
                ),
                ASTDProjectileVfxComponentSpec.Ribbon(
                    id = "astd_default_ribbon",
                    trailId = DEFAULT_TRAIL_ID,
                    ribbons = listOf(ribbonDecoration),
                ),
            ),
            lifecycle = ASTDProjectileVfxLifecycleSpec(
                durationSeconds = 1.25f,
                flightEndRatio = 0.6f,
                dissolveStartRatio = 0.6f,
                preDissolveFraction = 0.82f,
                projectileHeadSizeScale = 1.5f,
                historySampleMultiplier = 3f,
                historySmoothingPasses = 3,
                ribbonWaveSoftening = 0.48f,
                layoutReferenceWidth = 1846f,
            ),
            samplingPolicy = ASTDProjectileVfxSamplingPolicy(
                historyFps = 60f,
                maxHistoryNodes = 96,
                minDistancePerNode = 2f,
                smoothingPasses = 3,
                distanceWindow = 420f,
            ),
            fadePolicy = ASTDProjectileVfxFadePolicy(
                fadeInSeconds = 0f,
                fadeOutSeconds = 0.15f,
                hitFadeOutSeconds = 0.15f,
                expireFadeOutSeconds = 0.15f,
            ),
        )
    }

    private fun preset(
        id: String,
        color: ASTDColor,
        width: Float,
        length: Float,
        glowScale: Float = 2.2f,
        ribbon: Boolean = false,
        head: Boolean = false,
    ): ASTDProjectileVfxPreset {
        val trailLayer = ASTDTrailLayerSpec(
            width = width,
            color = color,
            length = length,
            startColor = color,
            endColor = color.copy(alpha = (color.alpha * 0.12f).coerceIn(0.04f, 0.2f)),
            startEmissive = color.copy(alpha = 1f),
            endEmissive = color.copy(alpha = (color.alpha * 0.25f).coerceIn(0.08f, 0.3f)),
            startWidth = width,
            endWidth = (width * 0.16f).coerceAtLeast(1f),
            texturePixels = 96f,
            textureSpeed = 0.9f,
            fillStartAlpha = 0.84f,
            fillEndAlpha = 0.03f,
            fillStartFactor = 0.02f,
            fillEndFactor = 0.12f,
            flowWhenPaused = true,
            flickWhenPaused = true,
            flickMixValue = 0f,
        )
        val components = ArrayList<ASTDProjectileVfxComponentSpec>()
        components += ASTDProjectileVfxComponentSpec.Trail(
            id = "${id}_trail",
            layer = trailLayer,
        )
        components += ASTDProjectileVfxComponentSpec.Glow(
            id = "${id}_glow",
            trailId = "${id}_trail",
            layers = listOf(
                ASTDProjectileVfxGlowLayerSpec(
                    id = "${id}_glow_0",
                    widthScale = glowScale,
                    alphaScale = (color.alpha * 0.35f).coerceIn(0.18f, 0.55f),
                    blur = width * 1.5f,
                    yOffset = 0f,
                    colorMixTail = 0.52f,
                    colorMixHead = 1f,
                ),
            ),
        )
        components += ASTDProjectileVfxComponentSpec.Body(
            id = "${id}_body",
            trailId = "${id}_trail",
        )
        if (ribbon) {
            components += ASTDProjectileVfxComponentSpec.Ribbon(
                id = "${id}_ribbon",
                trailId = "${id}_trail",
                ribbons = listOf(
                    ASTDTrailRibbonDecorationSpec(
                        id = "${id}_ribbon_0",
                        frequency = 5.5f,
                        amplitude = width * 0.42f,
                        thickness = 0.45f,
                        alphaScale = (color.alpha * 0.68f).coerceIn(0.25f, 0.85f),
                        startColor = color,
                        endColor = color.copy(alpha = (color.alpha * 0.18f).coerceIn(0.06f, 0.24f)),
                        color = color.copy(alpha = (color.alpha * 0.68f).coerceIn(0.25f, 0.85f)),
                    ),
                ),
            )
        }
        if (head) {
            components += ASTDProjectileVfxComponentSpec.Head(
                id = "${id}_head",
                trailId = "${id}_trail",
                layers = listOf(
                    ASTDProjectileVfxHeadLayerSpec(
                        id = "${id}_head_0",
                        length = length * 0.33f,
                        width = width * 1.2f,
                        shoulderRatio = 0.5f,
                        rearRatio = 0.95f,
                        shellColorStart = color.copy(alpha = 0.08f),
                        shellColorMid = color.copy(alpha = 0.46f),
                        shellColorEnd = color.copy(alpha = 1f),
                        blur = 0.35f,
                        alphaScale = 1f,
                    ),
                ),
            )
        }
        return ASTDProjectileVfxPreset(
            id = id,
            components = components,
            samplingPolicy = ASTDProjectileVfxSamplingPolicy(
                historyFps = 60f,
                maxHistoryNodes = 96,
                minDistancePerNode = 2f,
                smoothingPasses = 1,
                distanceWindow = length,
            ),
            fadePolicy = ASTDProjectileVfxFadePolicy(
                fadeInSeconds = 0f,
                fadeOutSeconds = 0.18f,
                hitFadeOutSeconds = 0.1f,
                expireFadeOutSeconds = 0.22f,
            ),
        )
    }

    private fun cyan() = ASTDColor(0.25f, 0.82f, 1f, 0.92f)
    private fun violet() = ASTDColor(0.66f, 0.42f, 1f, 0.9f)
    private fun amber() = ASTDColor(1f, 0.62f, 0.18f, 0.95f)
    private fun omega() = ASTDColor(0.72f, 0.35f, 1f, 0.96f)
    private fun blue() = ASTDColor(0.2f, 0.55f, 1f, 0.92f)
    private fun teal() = ASTDColor(0.22f, 1f, 0.78f, 0.9f)
    private fun rose() = ASTDColor(1f, 0.34f, 0.42f, 0.94f)
    private fun singularity() = ASTDColor(0.78f, 0.92f, 1f, 0.96f)
    private fun stellar() = ASTDColor(1f, 0.92f, 0.74f, 0.92f)
}
