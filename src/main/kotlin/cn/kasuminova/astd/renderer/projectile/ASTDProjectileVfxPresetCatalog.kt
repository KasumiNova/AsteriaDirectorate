package cn.kasuminova.astd.renderer.projectile

object ASTDProjectileVfxPresetCatalog {
    private val presets: Map<String, ASTDProjectileVfxPreset> = listOf(
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

    fun preset(id: String): ASTDProjectileVfxPreset? = presets[id]

    fun presetIds(): Set<String> = presets.keys

    private fun aod7Shot(): ASTDProjectileVfxPreset {
        val trailLayer = ASTDTrailLayerSpec(
            width = 80f,
            color = ASTDColor(0.278431f, 0.847059f, 0.921569f, 0.92f),
            length = 420f,
            startColor = ASTDColor(0.278431f, 0.847059f, 0.921569f, 0.92f),
            endColor = ASTDColor(0.039216f, 0.164706f, 0.219608f, 0.06f),
            startEmissive = ASTDColor(1f, 0.95f, 0.98f, 1f),
            endEmissive = ASTDColor(0.039216f, 0.223529f, 0.458824f, 0.16f),
            startWidth = 80f,
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
            thickness = 0.05f,
            alphaScale = 0.28f,
            lengthScale = 1f,
            nodeCountScale = 1f,
            frequency = 1.1f,
            amplitude = 1.35f,
            waveSpeed = 1f,
            waveType = "noise",
            noiseScale = 2f,
            blur = 9f,
            startColor = ASTDColor(1f, 1f, 1f, 0.92f),
            endColor = ASTDColor(0.494118f, 0.658824f, 0.92549f, 0.06f),
            color = ASTDColor(0.756863f, 0.909804f, 0.984314f, 0.92f),
        )
        val headLayers = listOf(
            ASTDProjectileVfxHeadLayerSpec(
                id = "astd_default_head_0",
                enabled = true,
                length = 138f,
                width = 24f,
                shoulderRatio = 0.5f,
                rearRatio = 0.95f,
                shellColorStart = ASTDColor(0.039216f, 0.164706f, 0.219608f, 0.08f),
                shellColorMid = ASTDColor(0.756863f, 0.909804f, 0.984314f, 0.46f),
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
                colorStart = ASTDColor(0.039216f, 0.164706f, 0.219608f, 0.06f),
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
            layers = emptyList(),
            trailEntities = listOf(
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
            ),
            headLayers = headLayers,
            glowLayers = glowLayers,
            mistLayers = mistLayers,
            sideWispLayers = sideWispLayers,
            ribbonDecorations = listOf(ribbonDecoration),
            lifecycle = ASTDProjectileVfxLifecycleSpec(
                durationSeconds = 1.25f,
                flightEndRatio = 0.6f,
                dissolveStartRatio = 0.6f,
                preDissolveFraction = 0.82f,
                projectileHeadSizeScale = 1.5f,
                historySampleMultiplier = 3f,
                historySmoothingPasses = 3,
                ribbonWaveSoftening = 0.48f,
            ),
            samplingPolicy = ASTDProjectileVfxSamplingPolicy(
                historyFps = 60f,
                maxHistoryNodes = 96,
                minDistancePerNode = 2f,
                smoothingPasses = 1,
                distanceWindow = 360f,
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
        val layers = ArrayList<ASTDProjectileVfxLayer>()
        layers += ASTDProjectileVfxLayer.Trail(
            id = "${id}_trail",
            width = width,
            length = ASTDProjectileVfxLengthPolicy.Fixed(length),
            color = color,
        )
        layers += ASTDProjectileVfxLayer.Glow(
            id = "${id}_glow",
            width = width * glowScale,
            length = ASTDProjectileVfxLengthPolicy.Fixed(length * 0.82f),
            color = color.copy(alpha = (color.alpha * 0.55f).coerceIn(0.2f, 0.8f)),
        )
        if (ribbon) {
            layers += ASTDProjectileVfxLayer.Ribbon(
                id = "${id}_ribbon",
                width = width * 0.45f,
                length = ASTDProjectileVfxLengthPolicy.Fixed(length * 0.72f),
                color = color.copy(alpha = (color.alpha * 0.68f).coerceIn(0.25f, 0.85f)),
                frequency = 5.5f,
                amplitude = width * 0.42f,
            )
        }
        if (head) {
            layers += ASTDProjectileVfxLayer.HeadTrail(
                id = "${id}_head",
                width = width * 1.2f,
                length = ASTDProjectileVfxLengthPolicy.LifetimeWindow(0.08f),
                color = color.copy(alpha = 1f),
            )
        }
        return ASTDProjectileVfxPreset(
            id = id,
            layers = layers,
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
