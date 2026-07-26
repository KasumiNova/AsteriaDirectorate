package cn.kasuminova.astd.impl.render

/**
 * 弹体 VFX 共享 spec 数据类型（几何层入参）。
 *
 * 来源：旧 `ASTDProjectileVfxPreset.kt` 中新旧管线共用的部分；preset 本体（Preset/SamplingPolicy/
 * FadePolicy/LifecycleSpec）已随旧管线删除。
 */

data class ASTDColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
    fun scaledAlpha(scale: Float): ASTDColor = copy(alpha = (alpha * scale).coerceIn(0f, 1f))
}

data class ASTDColorStopSpec(val offset: Float, val color: ASTDColor)

data class ASTDFloatRangeSpec(val min: Float, val max: Float)

sealed interface ASTDProjectileVfxLengthPolicy {
    data class Fixed(val worldUnits: Float) : ASTDProjectileVfxLengthPolicy
    data class VelocityScaled(val seconds: Float) : ASTDProjectileVfxLengthPolicy
    data class ProjectileRangeRatio(val ratio: Float) : ASTDProjectileVfxLengthPolicy
    data class LifetimeWindow(val seconds: Float) : ASTDProjectileVfxLengthPolicy
}

enum class ASTDProjectileVfxOrientationMode { ProjectileVelocity, ProjectileFacing, Custom }

enum class ASTDProjectileVfxAnchorMode { HeadLocked }

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
