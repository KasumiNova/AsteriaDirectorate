package cn.kasuminova.astd.renderer.projectile.component

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxLifecycleSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxBodyRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxDebug
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxGlowRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxHeadRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMistRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRibbonRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxSideWispRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxTrailRenderLayer
import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxRenderLayer

object ASTDProjectileVfxComponentRegistry {
    fun layersFor(
        components: List<ASTDProjectileVfxComponentSpec>,
        lifecycle: ASTDProjectileVfxLifecycleSpec,
    ): List<ASTDProjectileVfxRenderLayer> {
        val context = ASTDProjectileVfxComponentContext(components)
        val visibility = ASTDProjectileVfxDebug.visibility()
        return components.filter { it.enabled }.flatMap { component ->
            when (component) {
                is ASTDProjectileVfxComponentSpec.Trail -> {
                    if (visibility.trail && !context.hasBodyForTrail(component.id)) {
                        listOf(ASTDProjectileVfxTrailRenderLayer(listOf(component.toTrailEntitySpec())))
                    } else {
                        emptyList()
                    }
                }
                is ASTDProjectileVfxComponentSpec.Mist -> {
                    if (visibility.mist && component.layers.any { it.enabled }) {
                        listOf(ASTDProjectileVfxMistRenderLayer(context.trail(component.trailId).toTrailEntitySpec(), component.layers))
                    } else {
                        emptyList()
                    }
                }
                is ASTDProjectileVfxComponentSpec.Glow -> {
                    if (visibility.glow && component.layers.any { it.enabled }) {
                        listOf(ASTDProjectileVfxGlowRenderLayer(context.trail(component.trailId).toTrailEntitySpec(), component.layers))
                    } else {
                        emptyList()
                    }
                }
                is ASTDProjectileVfxComponentSpec.Body -> listOf(
                    ASTDProjectileVfxBodyRenderLayer(context.trail(component.trailId).toTrailEntitySpec()),
                )
                is ASTDProjectileVfxComponentSpec.SideWisp -> {
                    if (visibility.sideWisps && component.layers.any { it.enabled }) {
                        listOf(ASTDProjectileVfxSideWispRenderLayer(context.trail(component.trailId).toTrailEntitySpec(), component.layers))
                    } else {
                        emptyList()
                    }
                }
                is ASTDProjectileVfxComponentSpec.Head -> {
                    if (visibility.head && component.layers.any { it.enabled }) {
                        listOf(
                            ASTDProjectileVfxHeadRenderLayer(
                                context.trail(component.trailId).toTrailEntitySpec(),
                                component.layers,
                                lifecycle.projectileHeadSizeScale,
                            ),
                        )
                    } else {
                        emptyList()
                    }
                }
                is ASTDProjectileVfxComponentSpec.Ribbon -> {
                    if (visibility.ribbon && component.ribbons.any { it.enabled }) {
                        listOf(ASTDProjectileVfxRibbonRenderLayer(context.trail(component.trailId).toTrailEntitySpec(), component.ribbons))
                    } else {
                        emptyList()
                    }
                }
                is ASTDProjectileVfxComponentSpec.Extra -> throw IllegalArgumentException(
                    "Projectile VFX component extra type is not registered: id=${component.id} type=${component.type}",
                )
            }
        }
    }
}

internal fun ASTDProjectileVfxComponentSpec.Trail.toTrailEntitySpec(
    ribbonDecorations: List<cn.kasuminova.astd.renderer.projectile.ASTDTrailRibbonDecorationSpec> = emptyList(),
): ASTDTrailEntitySpec = ASTDTrailEntitySpec(
    layerId = id,
    id = id,
    nodes = emptyList(),
    layerSpec = layer,
    layers = listOf(layer),
    ribbonDecorations = ribbonDecorations,
    orientationMode = orientationMode,
    anchorMode = anchorMode,
)
