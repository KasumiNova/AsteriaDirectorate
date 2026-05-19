package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxSideWispLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import kotlin.math.max

object ASTDProjectileVfxSideWispRenderer {
    fun localPathsForTests(layer: ASTDProjectileVfxSideWispLayerSpec, length: Float, widthBase: Float): List<List<Vector2f>> {
        return ASTDProjectileVfxLayout.sideWispLocalPaths(layer, length, widthBase)
    }

    fun worldPathForTests(layer: ASTDProjectileVfxSideWispLayerSpec, context: ASTDProjectileVfxRenderContext, length: Float, widthBase: Float): List<List<Vector2f>> {
        return localPathsForTests(layer, length, widthBase).map { path ->
            path.map { rotateLocal(it, context.renderFacing, context.location) }
        }
    }

    fun alpha(layer: ASTDProjectileVfxSideWispLayerSpec, context: ASTDProjectileVfxRenderContext): Float {
        return layer.alphaScale * context.beamAlpha
    }

    fun lineWidthForTests(trail: ASTDTrailEntitySpec, layer: ASTDProjectileVfxSideWispLayerSpec): Float {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        return max(0.65f, ASTDProjectileVfxLayout.widthBase(baseLayer) * layer.widthScale)
    }
}

class ASTDProjectileVfxSideWispRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxSideWispLayerSpec>,
) : ASTDProjectileVfxRenderLayer {
    private data class Handle(val layer: ASTDProjectileVfxSideWispLayerSpec, val pathIndex: Int, val entity: TrailEntity, val baseSpec: ASTDTrailLayerSpec)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        BoxUtilCombatVfx.ensureReady(engine)
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        layers.filter { it.enabled }.forEach { layer ->
            val layerSpec = baseLayer.copy(
                width = max(0.65f, widthBase * layer.widthScale),
                startWidth = max(0.65f, widthBase * layer.widthScale),
                endWidth = max(0.35f, widthBase * layer.widthScale * 0.55f),
                startColor = layer.color,
                endColor = layer.color.copy(alpha = 0f),
                startEmissive = layer.color,
                endEmissive = layer.color.copy(alpha = 0f),
                fillStartAlpha = baseLayer.fillStartAlpha * layer.alphaScale,
                fillEndAlpha = 0f,
                jitterPower = baseLayer.jitterPower + layer.blur * 0.02f,
            )
            ASTDProjectileVfxSideWispRenderer.localPathsForTests(layer, context.visibleLength, widthBase).forEachIndexed { index, path ->
                val entity = TrailEntity()
                ASTDProjectileVfxLayout.scalePoints(path, context.worldUnitsPerPixel).forEach { entity.addNode(Vector2f(it)) }
                entity.submitNodes()
                ASTDProjectileVfxTrailRenderer.applyLayer(
                    entity,
                    layerSpec,
                    context.beamAlpha * layer.alphaScale,
                    headWidthOverride = layerSpec.startWidth,
                    tailWidthOverride = layerSpec.endWidth,
                    worldUnitsPerPixel = context.worldUnitsPerPixel,
                )
                val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
                if (state == 0) {
                    entity.setStateVanilla(context.location, context.renderFacing)
                    handles += Handle(layer, index, entity, layerSpec)
                } else {
                    entity.delete()
                    delete()
                    throw IllegalStateException(
                        "ASTD projectile VFX side wisp BoxUtil TrailEntity addEntity failed: " +
                            "state=$state layer=${layer.id} preset=${context.presetId} projectile=${context.projectileSpecId}",
                    )
                }
            }
        }
        return handles.isNotEmpty() || layers.none { it.enabled }
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        handles.forEach { handle ->
            if (!handle.entity.hasDelete()) {
                val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
                val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
                val path = ASTDProjectileVfxSideWispRenderer.localPathsForTests(handle.layer, context.visibleLength, widthBase).getOrNull(handle.pathIndex)
                if (path != null) {
                    handle.entity.setNodes(ASTDProjectileVfxLayout.mutableScaledNodeList(path, context.worldUnitsPerPixel))
                    handle.entity.setNodeRefreshIndex(0)
                    handle.entity.setNodeRefreshAllFromCurrentIndex()
                    handle.entity.submitNodes()
                }
                ASTDProjectileVfxTrailRenderer.applyLayer(
                    handle.entity,
                    handle.baseSpec,
                    context.beamAlpha * fade.alpha() * handle.layer.alphaScale,
                    headWidthOverride = handle.baseSpec.startWidth,
                    tailWidthOverride = handle.baseSpec.endWidth,
                    worldUnitsPerPixel = context.worldUnitsPerPixel,
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
