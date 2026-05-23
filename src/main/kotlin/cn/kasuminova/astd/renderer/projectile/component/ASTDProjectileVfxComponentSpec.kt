package cn.kasuminova.astd.renderer.projectile.component

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxAnchorMode
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxGlowLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxMistLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxOrientationMode
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxSideWispLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailRibbonDecorationSpec

sealed interface ASTDProjectileVfxComponentSpec {
    val id: String
    val kind: String
    val enabled: Boolean

    data class Trail(
        override val id: String,
        val layer: ASTDTrailLayerSpec,
        val orientationMode: ASTDProjectileVfxOrientationMode = ASTDProjectileVfxOrientationMode.ProjectileVelocity,
        val anchorMode: ASTDProjectileVfxAnchorMode = ASTDProjectileVfxAnchorMode.HeadLocked,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "trail"
    }

    data class Body(
        override val id: String,
        val trailId: String,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "body"
    }

    data class Ribbon(
        override val id: String,
        val trailId: String,
        val ribbons: List<ASTDTrailRibbonDecorationSpec>,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "ribbon"
    }

    data class Head(
        override val id: String,
        val trailId: String,
        val layers: List<ASTDProjectileVfxHeadLayerSpec>,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "head"
    }

    data class Glow(
        override val id: String,
        val trailId: String,
        val layers: List<ASTDProjectileVfxGlowLayerSpec>,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "glow"
    }

    data class Mist(
        override val id: String,
        val trailId: String,
        val layers: List<ASTDProjectileVfxMistLayerSpec>,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "mist"
    }

    data class SideWisp(
        override val id: String,
        val trailId: String,
        val layers: List<ASTDProjectileVfxSideWispLayerSpec>,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "sideWisp"
    }

    data class Extra(
        override val id: String,
        val type: String,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "extra"
    }
}
