package cn.kasuminova.astd.renderer.projectile

data class ASTDProjectileVfxPreset(
    val id: String,
    val layers: List<ASTDProjectileVfxLayer>,
    val samplingPolicy: ASTDProjectileVfxSamplingPolicy,
    val fadePolicy: ASTDProjectileVfxFadePolicy,
)

data class ASTDColor(val red: Float, val green: Float, val blue: Float, val alpha: Float)

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
