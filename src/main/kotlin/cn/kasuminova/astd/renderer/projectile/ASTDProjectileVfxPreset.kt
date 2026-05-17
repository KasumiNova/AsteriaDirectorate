package cn.kasuminova.astd.renderer.projectile

data class ASTDProjectileVfxPreset(
    val id: String,
    val layers: List<ASTDProjectileVfxLayer>,
    val samplingPolicy: ASTDProjectileVfxSamplingPolicy,
    val fadePolicy: ASTDProjectileVfxFadePolicy,
    val trailEntities: List<ASTDTrailEntitySpec> = emptyList(),
    val headLayers: List<ASTDProjectileVfxHeadLayerSpec> = emptyList(),
    val glowLayers: List<ASTDProjectileVfxGlowLayerSpec> = emptyList(),
    val mistLayers: List<ASTDProjectileVfxMistLayerSpec> = emptyList(),
    val sideWispLayers: List<ASTDProjectileVfxSideWispLayerSpec> = emptyList(),
    val ribbonDecorations: List<ASTDTrailRibbonDecorationSpec> = emptyList(),
    val lifecycle: ASTDProjectileVfxLifecycleSpec = ASTDProjectileVfxLifecycleSpec(),
)

data class ASTDColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
    fun scaledAlpha(scale: Float): ASTDColor = copy(alpha = (alpha * scale).coerceIn(0f, 1f))
}

data class ASTDProjectileVfxSamplingPolicy(
    val historyFps: Float,
    val maxHistoryNodes: Int,
    val minDistancePerNode: Float,
    val smoothingPasses: Int,
    val distanceWindow: Float,
)

data class ASTDProjectileVfxFadePolicy(
    val fadeInSeconds: Float,
    val fadeOutSeconds: Float,
    val hitFadeOutSeconds: Float,
    val expireFadeOutSeconds: Float,
)

sealed interface ASTDProjectileVfxLengthPolicy {
    data class Fixed(val worldUnits: Float) : ASTDProjectileVfxLengthPolicy
    data class VelocityScaled(val seconds: Float) : ASTDProjectileVfxLengthPolicy
    data class ProjectileRangeRatio(val ratio: Float) : ASTDProjectileVfxLengthPolicy
    data class LifetimeWindow(val seconds: Float) : ASTDProjectileVfxLengthPolicy
}

enum class ASTDProjectileVfxOrientationMode { ProjectileVelocity, ProjectileFacing, Custom }

enum class ASTDProjectileVfxAnchorMode { HeadLocked }

data class ASTDColorStopSpec(val offset: Float, val color: ASTDColor)

data class ASTDFloatRangeSpec(val min: Float, val max: Float)

data class ASTDProjectileVfxHeadLayerSpec(
    val id: String,
    val enabled: Boolean = true,
    val length: Float,
    val width: Float,
    val shoulderRatio: Float,
    val rearRatio: Float,
    val shellColorStart: ASTDColor,
    val shellColorMid: ASTDColor,
    val shellColorEnd: ASTDColor,
    val blur: Float,
    val alphaScale: Float,
    val blendMode: String = "additive",
)

data class ASTDProjectileVfxGlowLayerSpec(
    val id: String,
    val enabled: Boolean = true,
    val widthScale: Float,
    val alphaScale: Float,
    val blur: Float,
    val yOffset: Float,
    val colorMixTail: Float,
    val colorMixHead: Float,
    val gradientStops: List<ASTDColorStopSpec> = emptyList(),
)

data class ASTDProjectileVfxMistLayerSpec(
    val id: String,
    val enabled: Boolean = true,
    val blobCount: Int,
    val lengthScale: Float,
    val widthScale: Float,
    val rxRange: ASTDFloatRangeSpec,
    val ryRange: ASTDFloatRangeSpec,
    val alphaRange: ASTDFloatRangeSpec,
    val noiseScale: Float,
    val driftSpeed: Float,
    val colorStart: ASTDColor,
    val colorEnd: ASTDColor,
)

data class ASTDProjectileVfxSideWispLayerSpec(
    val id: String,
    val enabled: Boolean = true,
    val offsets: List<Float>,
    val widthScale: Float,
    val alphaScale: Float,
    val blur: Float,
    val lengthStartRatio: Float,
    val lengthEndRatio: Float,
    val color: ASTDColor,
)

data class ASTDProjectileVfxLifecycleSpec(
    val durationSeconds: Float = 1.25f,
    val flightEndRatio: Float = 0.6f,
    val dissolveStartRatio: Float = 0.6f,
    val preDissolveFraction: Float = 0.82f,
    val projectileHeadSizeScale: Float = 1.5f,
    val historySampleMultiplier: Float = 3f,
    val historySmoothingPasses: Int = 3,
    val ribbonWaveSoftening: Float = 0.48f,
)
