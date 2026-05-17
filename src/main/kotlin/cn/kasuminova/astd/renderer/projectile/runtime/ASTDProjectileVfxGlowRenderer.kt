package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxGlowLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDColor
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f

object ASTDProjectileVfxGlowRenderer {
    data class Parameters(
        val id: String,
        val widthScale: Float,
        val lineWidth: Float,
        val alpha: Float,
        val blur: Float,
        val yOffset: Float,
        val nodes: List<Vector2f>,
        val startColor: ASTDColor,
        val endColor: ASTDColor,
    )

    fun parametersForTests(
        trail: ASTDTrailEntitySpec,
        layers: List<ASTDProjectileVfxGlowLayerSpec>,
        context: ASTDProjectileVfxRenderContext,
    ): List<Parameters> {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        return layers.filter { it.enabled }.map { layer ->
            val colors = colors(baseLayer, layer)
            Parameters(
                id = layer.id,
                widthScale = layer.widthScale,
                lineWidth = ASTDProjectileVfxLayout.glowLineWidth(widthBase, layer),
                alpha = layer.alphaScale * context.beamAlpha,
                blur = layer.blur,
                yOffset = layer.yOffset,
                nodes = ASTDProjectileVfxLayout.glowLocalNodes(context.visibleLength, layer),
                startColor = colors.first,
                endColor = colors.second,
            )
        }
    }

    fun layerSpec(baseLayer: ASTDTrailLayerSpec, glow: ASTDProjectileVfxGlowLayerSpec, context: ASTDProjectileVfxRenderContext): ASTDTrailLayerSpec {
        val (tail, head) = colors(baseLayer, glow)
        val width = ASTDProjectileVfxLayout.glowLineWidth(ASTDProjectileVfxLayout.widthBase(baseLayer), glow)
        return baseLayer.copy(
            width = width,
            startWidth = width,
            endWidth = width,
            startColor = head,
            endColor = tail,
            startEmissive = head,
            endEmissive = tail,
            jitterPower = baseLayer.jitterPower + glow.blur * 0.02f,
        )
    }

    fun colors(baseLayer: ASTDTrailLayerSpec, glow: ASTDProjectileVfxGlowLayerSpec): Pair<ASTDColor, ASTDColor> {
        if (glow.gradientStops.isNotEmpty()) {
            return sampleColorStops(glow.gradientStops, 0f, baseLayer.endColor) to sampleColorStops(glow.gradientStops, 1f, baseLayer.startColor)
        }
        val darkTail = mix(baseLayer.endColor, baseLayer.endEmissive, 0.52f)
        val hotCore = mix(baseLayer.startColor, baseLayer.startEmissive, 0.44f)
        val tail = mix(darkTail, hotCore, glow.colorMixTail)
        val head = if (glow.colorMixHead >= 1f) ASTDColor(1f, 1f, 1f, 1f) else mix(baseLayer.startColor, baseLayer.startEmissive, glow.colorMixHead)
        return tail to head
    }

    private fun sampleColorStops(stops: List<cn.kasuminova.astd.renderer.projectile.ASTDColorStopSpec>, offset: Float, fallback: ASTDColor): ASTDColor {
        if (stops.isEmpty()) return fallback
        val sorted = stops.sortedBy { it.offset }
        if (offset <= sorted.first().offset) return sorted.first().color
        for (index in 0 until sorted.lastIndex) {
            val left = sorted[index]
            val right = sorted[index + 1]
            if (offset >= left.offset && offset <= right.offset) {
                val ratio = ((offset - left.offset) / (right.offset - left.offset).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                return mix(left.color, right.color, ratio)
            }
        }
        return sorted.last().color
    }

    private fun mix(a: ASTDColor, b: ASTDColor, t: Float): ASTDColor {
        val ratio = t.coerceIn(0f, 1f)
        return ASTDColor(
            a.red + (b.red - a.red) * ratio,
            a.green + (b.green - a.green) * ratio,
            a.blue + (b.blue - a.blue) * ratio,
            a.alpha + (b.alpha - a.alpha) * ratio,
        )
    }
}

class ASTDProjectileVfxGlowRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxGlowLayerSpec>,
) : ASTDProjectileVfxRenderLayer {
    private data class Handle(val spec: ASTDProjectileVfxGlowLayerSpec, val entity: TrailEntity)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        layers.filter { it.enabled }.forEach { glow ->
            val spec = trail.copy(
                layerId = glow.id,
                id = glow.id,
                layerSpec = ASTDProjectileVfxGlowRenderer.layerSpec(baseLayer, glow, context),
                layers = listOf(ASTDProjectileVfxGlowRenderer.layerSpec(baseLayer, glow, context)),
                ribbonDecorations = emptyList(),
            )
            val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(ASTDProjectileVfxLayout.widthBase(baseLayer), glow)
            ASTDProjectileVfxTrailRenderer.createEntity(
                engine,
                spec,
                context,
                yOffset = glow.yOffset,
                alphaScale = glow.alphaScale,
                headWidthOverride = lineWidth,
                tailWidthOverride = lineWidth,
            )?.let { entity ->
                handles += Handle(glow, entity)
            }
        }
        return handles.size == layers.count { it.enabled }
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        handles.forEach { handle ->
            if (!handle.entity.hasDelete()) {
                val layer = ASTDProjectileVfxGlowRenderer.layerSpec(baseLayer, handle.spec, context)
                handle.entity.setNodes(ASTDProjectileVfxLayout.mutableGlowLocalNodes(context.visibleLength, handle.spec))
                handle.entity.setNodeRefreshIndex(0)
                handle.entity.setNodeRefreshAllFromCurrentIndex()
                handle.entity.submitNodes()
                val lineWidth = ASTDProjectileVfxLayout.glowLineWidth(ASTDProjectileVfxLayout.widthBase(baseLayer), handle.spec)
                ASTDProjectileVfxTrailRenderer.applyLayer(
                    handle.entity,
                    layer,
                    context.beamAlpha * fade.alpha() * handle.spec.alphaScale,
                    headWidthOverride = lineWidth,
                    tailWidthOverride = lineWidth,
                )
                handle.entity.setStateVanilla(context.location, context.renderFacing)
            }
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handles.forEach { it.entity.delete() }
        handles.clear()
    }
}
