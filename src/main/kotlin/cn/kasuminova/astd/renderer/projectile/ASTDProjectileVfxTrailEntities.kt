package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

data class ASTDTrailEntitySpec(
    val layerId: String,
    val nodes: List<Vector2f>,
    val layerSpec: ASTDTrailLayerSpec,
    val ribbonDecoration: ASTDTrailRibbonDecorationSpec? = null,
)

data class ASTDTrailLayerSpec(
    val width: Float,
    val color: ASTDColor,
    val combatLayer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
    val additive: Boolean = true,
)

data class ASTDTrailRibbonDecorationSpec(
    val frequency: Float,
    val amplitude: Float,
)

sealed interface ASTDTrailEntityBuildResult {
    data class Success(val entity: TrailEntity) : ASTDTrailEntityBuildResult
    data class Failure(val layerId: String, val state: Int) : ASTDTrailEntityBuildResult
}

object ASTDProjectileVfxTrailEntities {
    fun buildSpecs(
        layers: List<ASTDProjectileVfxLayer>,
        historyNodes: List<ASTDProjectileHistoryNode>,
    ): List<ASTDTrailEntitySpec> {
        if (historyNodes.size < 2) return emptyList()

        return layers.map { layer ->
            when (layer) {
                is ASTDProjectileVfxLayer.Trail -> buildSpec(layer, copyNodeLocations(historyNodes), null)
                is ASTDProjectileVfxLayer.Glow -> buildSpec(layer, copyNodeLocations(historyNodes), null)
                is ASTDProjectileVfxLayer.Ribbon -> {
                    val decoration = ASTDTrailRibbonDecorationSpec(layer.frequency, layer.amplitude)
                    buildSpec(layer, ribbonNodes(historyNodes, layer.frequency, layer.amplitude), decoration)
                }
                is ASTDProjectileVfxLayer.HeadTrail -> buildSpec(layer, headTrailNodes(historyNodes, layer.length), null)
            }
        }.filter { it.nodes.size >= 2 }
    }

    fun addTrailEntity(engine: CombatEngineAPI, spec: ASTDTrailEntitySpec): ASTDTrailEntityBuildResult {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = TrailEntity()
        spec.nodes.forEach { entity.addNode(Vector2f(it)) }
        entity.submitNodes()
        entity.setLayer(spec.layerSpec.combatLayer)
        if (spec.layerSpec.additive) entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, 3600f, 0f)
        entity.setStartWidth(spec.layerSpec.width)
        entity.setEndWidth(spec.layerSpec.width)
        entity.setStartColor(1f, 1f, 1f, spec.layerSpec.color.alpha)
        entity.setEndColor(1f, 1f, 1f, spec.layerSpec.color.alpha)
        entity.setStartEmissive(1f, 1f, 1f, spec.layerSpec.color.alpha)
        entity.setEndEmissive(1f, 1f, 1f, spec.layerSpec.color.alpha)
        entity.materialData.setColor(spec.layerSpec.color.toAwtColor())
        entity.materialData.setEmissiveColor(spec.layerSpec.color.toAwtColor())
        entity.materialData.setAlphaToEmissive(0f)
        entity.materialData.setColorToEmissive(0f)
        entity.materialData.setGlowPower(1f)

        val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
        if (state != 0) {
            entity.delete()
            return ASTDTrailEntityBuildResult.Failure(spec.layerId, state)
        }

        return ASTDTrailEntityBuildResult.Success(entity)
    }

    private fun buildSpec(
        layer: ASTDProjectileVfxLayer,
        nodes: List<Vector2f>,
        ribbonDecoration: ASTDTrailRibbonDecorationSpec?,
    ): ASTDTrailEntitySpec {
        return ASTDTrailEntitySpec(
            layerId = layer.id,
            nodes = nodes,
            layerSpec = ASTDTrailLayerSpec(width = layer.width, color = layer.color),
            ribbonDecoration = ribbonDecoration,
        )
    }

    private fun copyNodeLocations(historyNodes: List<ASTDProjectileHistoryNode>): List<Vector2f> {
        return historyNodes.map { Vector2f(it.location) }
    }

    private fun headTrailNodes(
        historyNodes: List<ASTDProjectileHistoryNode>,
        length: ASTDProjectileVfxLengthPolicy,
    ): List<Vector2f> {
        if (length !is ASTDProjectileVfxLengthPolicy.LifetimeWindow) return copyNodeLocations(historyNodes)

        val latestElapsed = historyNodes.last().elapsed
        val earliestElapsed = latestElapsed - length.seconds.coerceAtLeast(0f)
        val selected = historyNodes.filter { it.elapsed >= earliestElapsed }
        val usable = if (selected.size >= 2) selected else historyNodes.takeLast(2)
        return copyNodeLocations(usable)
    }

    private fun ribbonNodes(
        historyNodes: List<ASTDProjectileHistoryNode>,
        frequency: Float,
        amplitude: Float,
    ): List<Vector2f> {
        return historyNodes.mapIndexed { index, node ->
            val previous = historyNodes.getOrNull(index - 1)?.location
            val next = historyNodes.getOrNull(index + 1)?.location
            val tangentSourceA = previous ?: node.location
            val tangentSourceB = next ?: node.location
            val dx = tangentSourceB.x - tangentSourceA.x
            val dy = tangentSourceB.y - tangentSourceA.y
            val length = sqrt(dx * dx + dy * dy)
            val normalX = if (length > 0f) -dy / length else 0f
            val normalY = if (length > 0f) dx / length else 1f
            val phase = index.toFloat() * frequency * (PI.toFloat() / 180f)
            val offset = sin(phase) * amplitude
            Vector2f(node.location.x + normalX * offset, node.location.y + normalY * offset)
        }
    }

    private fun ASTDColor.toAwtColor(): Color {
        return Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
    }
}
