package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentSpec
import cn.kasuminova.astd.renderer.projectile.component.toTrailEntitySpec

internal fun ASTDProjectileVfxPreset.primaryTrailLayer(): ASTDTrailLayerSpec? =
    components.filterIsInstance<ASTDProjectileVfxComponentSpec.Trail>().firstOrNull()?.layer

internal fun ASTDProjectileVfxPreset.trailEntitySpecsForTests(): List<ASTDTrailEntitySpec> {
    val ribbonsByTrail = components
        .filterIsInstance<ASTDProjectileVfxComponentSpec.Ribbon>()
        .flatMap { component -> component.ribbons.map { component.trailId to it } }
        .groupBy({ it.first }, { it.second })
    return components.filterIsInstance<ASTDProjectileVfxComponentSpec.Trail>().map { trail ->
        trail.toTrailEntitySpec(ribbonsByTrail[trail.id].orEmpty())
    }
}

internal fun ASTDProjectileVfxPreset.headLayerSpecsForTests(): List<ASTDProjectileVfxHeadLayerSpec> =
    components.filterIsInstance<ASTDProjectileVfxComponentSpec.Head>().flatMap { it.layers }

internal fun ASTDProjectileVfxPreset.glowLayerSpecsForTests(): List<ASTDProjectileVfxGlowLayerSpec> =
    components.filterIsInstance<ASTDProjectileVfxComponentSpec.Glow>().flatMap { it.layers }

internal fun ASTDProjectileVfxPreset.mistLayerSpecsForTests(): List<ASTDProjectileVfxMistLayerSpec> =
    components.filterIsInstance<ASTDProjectileVfxComponentSpec.Mist>().flatMap { it.layers }

internal fun ASTDProjectileVfxPreset.sideWispLayerSpecsForTests(): List<ASTDProjectileVfxSideWispLayerSpec> =
    components.filterIsInstance<ASTDProjectileVfxComponentSpec.SideWisp>().flatMap { it.layers }

internal fun ASTDProjectileVfxPreset.ribbonDecorationSpecsForTests(): List<ASTDTrailRibbonDecorationSpec> =
    components.filterIsInstance<ASTDProjectileVfxComponentSpec.Ribbon>().flatMap { it.ribbons }

internal val ASTDProjectileVfxPreset.trailEntities: List<ASTDTrailEntitySpec>
    get() = trailEntitySpecsForTests()

internal val ASTDProjectileVfxPreset.headLayers: List<ASTDProjectileVfxHeadLayerSpec>
    get() = headLayerSpecsForTests()

internal val ASTDProjectileVfxPreset.glowLayers: List<ASTDProjectileVfxGlowLayerSpec>
    get() = glowLayerSpecsForTests()

internal val ASTDProjectileVfxPreset.mistLayers: List<ASTDProjectileVfxMistLayerSpec>
    get() = mistLayerSpecsForTests()

internal val ASTDProjectileVfxPreset.sideWispLayers: List<ASTDProjectileVfxSideWispLayerSpec>
    get() = sideWispLayerSpecsForTests()

internal val ASTDProjectileVfxPreset.ribbonDecorations: List<ASTDTrailRibbonDecorationSpec>
    get() = ribbonDecorationSpecsForTests()
