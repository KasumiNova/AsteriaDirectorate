package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxHeadLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.SegmentEntity
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

object ASTDProjectileVfxHeadRenderer {
    fun verticesForTests(layer: ASTDProjectileVfxHeadLayerSpec, visible: Float, widthBase: Float = 6f, headSizeScale: Float = 1f): List<Vector2f> {
        return ASTDProjectileVfxLayout.headVertices(layer, visible, headSizeScale, widthBase).asList()
    }

    fun alphaForTests(layer: ASTDProjectileVfxHeadLayerSpec, context: ASTDProjectileVfxRenderContext): Float {
        return layer.alphaScale * context.beamAlpha
    }

    fun colorsForTests(baseLayer: ASTDTrailLayerSpec, layer: ASTDProjectileVfxHeadLayerSpec): ASTDProjectileVfxLayout.HeadColors {
        return ASTDProjectileVfxLayout.headColors(baseLayer, layer)
    }
}

class ASTDProjectileVfxHeadRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxHeadLayerSpec>,
) : ASTDProjectileVfxRenderLayer {
    private data class Handle(val layer: ASTDProjectileVfxHeadLayerSpec, val entity: SegmentEntity)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        BoxUtilCombatVfx.ensureReady(engine)
        layers.filter { it.enabled }.forEach { layer ->
            val entity = SegmentEntity()
            applyHead(entity, layer, context.beamAlpha)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SEGMENT, entity)
            if (state == 0) {
                entity.setStateVanilla(context.location, context.renderFacing)
                handles += Handle(layer, entity)
            } else {
                entity.delete()
                Global.getLogger(ASTDProjectileVfxHeadRenderLayer::class.java).warn(
                    "ASTD projectile VFX head addEntity failed state=$state layer=${layer.id} preset=${context.presetId} projectile=${context.projectileSpecId}",
                )
            }
        }
        return handles.size == layers.count { it.enabled }
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        handles.forEach { handle ->
            if (!handle.entity.hasDelete()) {
                applyHead(handle.entity, handle.layer, context.beamAlpha * fade.alpha())
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

    private fun applyHead(entity: SegmentEntity, layer: ASTDProjectileVfxHeadLayerSpec, alpha: Float) {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val colors = ASTDProjectileVfxLayout.headColors(baseLayer, layer)
        val vertices = ASTDProjectileVfxHeadRenderer.verticesForTests(layer, alpha.coerceIn(0f, 1f), widthBase)
        val closedVertices = vertices + vertices.first()
        val nodes = closedVertices.zipWithNext().flatMap { (from, to) ->
            listOf(headNode(from, layer, colors, alpha), headNode(to, layer, colors, alpha))
        }
        entity.setNodes(nodes)
        entity.setNodeRefreshIndex(0)
        entity.setNodeRefreshAllFromCurrentIndex()
        entity.submitNodes()
        entity.setSegmentsRenderingCount(nodes.size / 2)
        entity.setLayer((trail.layers.firstOrNull() ?: trail.layerSpec).combatLayer)
        entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, 3600f, 0f)
        entity.materialData.setColor(colors.mid.toAwtColor(alpha))
        entity.materialData.setEmissiveColor(colors.emissive.toAwtColor(alpha))
        entity.materialData.setAlphaToEmissive(0f)
        entity.materialData.setColorToEmissive(0f)
        entity.materialData.setGlowPower(1f)
    }

    private fun headNode(point: Vector2f, layer: ASTDProjectileVfxHeadLayerSpec, colors: ASTDProjectileVfxLayout.HeadColors, alpha: Float): NodeData {
        val progress = ((point.x + layer.length * layer.rearRatio) / (layer.length * layer.rearRatio).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val color = when {
            progress < 0.5f -> colors.start
            progress < 0.82f -> colors.mid
            else -> colors.end
        }
        return NodeData(point).apply {
            setWidth(max(0.7f, layer.blur * 3f + 1f))
            setColor(color.red, color.green, color.blue, color.alpha * alpha * layer.alphaScale)
            setEmissiveColor(colors.emissive.red, colors.emissive.green, colors.emissive.blue, colors.emissive.alpha * alpha * layer.alphaScale)
            setMixFactor(1f)
        }
    }
}
