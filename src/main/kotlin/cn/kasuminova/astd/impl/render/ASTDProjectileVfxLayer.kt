package cn.kasuminova.astd.impl.render

sealed interface ASTDProjectileVfxLayer {
    val id: String
    val width: Float
    val length: ASTDProjectileVfxLengthPolicy
    val color: ASTDColor

    data class Trail(
        override val id: String,
        override val width: Float,
        override val length: ASTDProjectileVfxLengthPolicy,
        override val color: ASTDColor,
    ) : ASTDProjectileVfxLayer

    data class Glow(
        override val id: String,
        override val width: Float,
        override val length: ASTDProjectileVfxLengthPolicy,
        override val color: ASTDColor,
    ) : ASTDProjectileVfxLayer

    data class Ribbon(
        override val id: String,
        override val width: Float,
        override val length: ASTDProjectileVfxLengthPolicy,
        override val color: ASTDColor,
        val frequency: Float,
        val amplitude: Float,
    ) : ASTDProjectileVfxLayer

    data class HeadTrail(
        override val id: String,
        override val width: Float,
        override val length: ASTDProjectileVfxLengthPolicy,
        override val color: ASTDColor,
    ) : ASTDProjectileVfxLayer
}
