package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ASTDTrailEntitySpec(
    val layerId: String,
    val nodes: List<Vector2f>,
    val layerSpec: ASTDTrailLayerSpec,
    val ribbonDecorations: List<ASTDTrailRibbonDecorationSpec> = emptyList(),
    val id: String = layerId,
    val layers: List<ASTDTrailLayerSpec> = listOf(layerSpec),
    val orientationMode: ASTDProjectileVfxOrientationMode = ASTDProjectileVfxOrientationMode.ProjectileVelocity,
    val anchorMode: ASTDProjectileVfxAnchorMode = ASTDProjectileVfxAnchorMode.HeadLocked,
)

data class ASTDTrailLayerSpec(
    val width: Float,
    val color: ASTDColor,
    val combatLayer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
    val additive: Boolean = true,
    val length: Float = 420f,
    val diffuseSpritePath: String = "graphics/fx/beamcoreb.png",
    val emissiveSpritePath: String = "graphics/fx/beamfringeb.png",
    val startColor: ASTDColor = color,
    val endColor: ASTDColor = color,
    val startEmissive: ASTDColor = color,
    val endEmissive: ASTDColor = color,
    val startWidth: Float = width,
    val endWidth: Float = width,
    val texturePixels: Float = 1f,
    val textureSpeed: Float = 4f,
    val uvOffset: Float = 0f,
    val fillStartAlpha: Float = 1f,
    val fillEndAlpha: Float = 1f,
    val fillStartFactor: Float = 1f,
    val fillEndFactor: Float = 1f,
    val jitterPower: Float = 0f,
    val flick: Boolean = false,
    val syncFlick: Boolean = true,
    val stripLineMode: Boolean = true,
    val flowWhenPaused: Boolean = false,
    val flickWhenPaused: Boolean = false,
    val flickMixValue: Float = 1f,
    val flickerSyncCode: Int? = null,
    val blendMode: String = if (additive) "additive" else "normal",
)

data class ASTDTrailRibbonDecorationSpec(
    val frequency: Float,
    val amplitude: Float,
    val id: String = "",
    val enabled: Boolean = true,
    val renderMode: String = "byLength",
    val startOffset: Float = 0f,
    val endOffset: Float = 0f,
    val thickness: Float = 1f,
    val alphaScale: Float = 1f,
    val lengthScale: Float = 1f,
    val nodeCountScale: Float = 1f,
    val waveSpeed: Float = 1f,
    val waveType: String = "sine",
    val noiseScale: Float = 1f,
    val blur: Float = 0f,
    val startColor: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
    val endColor: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
    val color: ASTDColor = startColor,
    val colorGradient: ASTDTrailDecorationColorGradientSpec = ASTDTrailDecorationColorGradientSpec(),
)

data class ASTDTrailDecorationColorGradientSpec(
    val enabled: Boolean = false,
    val stops: List<ASTDTrailDecorationColorStopSpec> = emptyList(),
)

data class ASTDTrailDecorationColorStopSpec(
    val offset: Float,
    val color: ASTDColor,
)

sealed interface ASTDTrailEntityBuildResult {
    data class Success(val entity: TrailEntity) : ASTDTrailEntityBuildResult
    data class Failure(val layerId: String, val state: Int) : ASTDTrailEntityBuildResult
}

data class ASTDTrailEntityHandle(
    val entity: TrailEntity,
    val spec: ASTDTrailEntitySpec,
)

object ASTDProjectileVfxTrailEntities {
    private val log = Global.getLogger(ASTDProjectileVfxTrailEntities::class.java)

    fun buildSpecs(
        layers: List<ASTDProjectileVfxLayer>,
        trailEntities: List<ASTDTrailEntitySpec>,
        historyNodes: List<ASTDProjectileHistoryNode>,
    ): List<ASTDTrailEntitySpec> {
        if (historyNodes.size < 2) return emptyList()

        val dynamicSpecs = layers.map { layer ->
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

        val exportSpecs = trailEntities.flatMap { spec ->
            val baseLayer = spec.layers.firstOrNull() ?: spec.layerSpec
            val baseSpec = spec.copy(nodes = localBeamNodes(baseLayer, spec.layerId))
            listOf(baseSpec) + buildRibbonDecorationSpecs(baseSpec)
        }.filter { it.nodes.size >= 2 }

        return dynamicSpecs + exportSpecs
    }

    fun buildSpecs(
        layers: List<ASTDProjectileVfxLayer>,
        historyNodes: List<ASTDProjectileHistoryNode>,
    ): List<ASTDTrailEntitySpec> {
        return buildSpecs(layers, emptyList(), historyNodes)
    }

    fun addTrailEntity(engine: CombatEngineAPI, spec: ASTDTrailEntitySpec): ASTDTrailEntityBuildResult {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = TrailEntity()
        spec.nodes.forEach { entity.addNode(Vector2f(it)) }
        entity.submitNodes()
        val layerSpec = spec.layers.firstOrNull() ?: spec.layerSpec
        entity.setLayer(layerSpec.combatLayer)
        if (layerSpec.additive || layerSpec.blendMode.equals("additive", ignoreCase = true)) entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, 3600f, 0f)
        val headWidth = ASTDProjectileVfxLayout.widthBase(layerSpec)
        val tailWidth = (layerSpec.endWidth * 0.075f).coerceAtLeast(0.3f)
        entity.setStartWidth(tailWidth)
        entity.setEndWidth(headWidth)
        entity.setStartColor(layerSpec.endColor.red, layerSpec.endColor.green, layerSpec.endColor.blue, layerSpec.endColor.alpha)
        entity.setEndColor(layerSpec.startColor.red, layerSpec.startColor.green, layerSpec.startColor.blue, layerSpec.startColor.alpha)
        entity.setStartEmissive(layerSpec.endEmissive.red, layerSpec.endEmissive.green, layerSpec.endEmissive.blue, layerSpec.endEmissive.alpha)
        entity.setEndEmissive(layerSpec.startEmissive.red, layerSpec.startEmissive.green, layerSpec.startEmissive.blue, layerSpec.startEmissive.alpha)
        entity.setTexturePixels(layerSpec.texturePixels)
        entity.setTextureSpeed(layerSpec.textureSpeed)
        entity.setUVOffset(layerSpec.uvOffset)
        entity.setFillStartAlpha(layerSpec.fillStartAlpha)
        entity.setFillEndAlpha(layerSpec.fillEndAlpha)
        entity.setFillStartFactor(layerSpec.fillStartFactor)
        entity.setFillEndFactor(layerSpec.fillEndFactor)
        entity.setJitterPower(layerSpec.jitterPower)
        entity.setFlick(layerSpec.flick)
        entity.setSyncFlick(layerSpec.syncFlick)
        entity.setStripLineMode(layerSpec.stripLineMode)
        entity.setFlowWhenPaused(layerSpec.flowWhenPaused)
        entity.setFlickWhenPaused(layerSpec.flickWhenPaused)
        entity.setFlickMixValue(layerSpec.flickMixValue)
        layerSpec.flickerSyncCode?.let { entity.setFlickerSyncCode(it) }
        entity.materialData.setColor(layerSpec.color.toAwtColor())
        entity.materialData.setEmissiveColor(layerSpec.startEmissive.toAwtColor())
        entity.materialData.setDiffuse(Global.getSettings().getSprite(layerSpec.diffuseSpritePath))
        entity.materialData.setEmissive(Global.getSettings().getSprite(layerSpec.emissiveSpritePath))
        entity.materialData.setAlphaToEmissive(0f)
        entity.materialData.setColorToEmissive(0f)
        entity.materialData.setGlowPower(1f)

        val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
        if (state != 0) {
            entity.delete()
            log.warn("ASTD projectile VFX legacy addTrailEntity failed state=$state layer=${spec.layerId} id=${spec.id}")
            return ASTDTrailEntityBuildResult.Failure(spec.layerId, state)
        }

        return ASTDTrailEntityBuildResult.Success(entity)
    }

    fun buildExportHandles(
        engine: CombatEngineAPI,
        trailEntities: List<ASTDTrailEntitySpec>,
    ): List<ASTDTrailEntityHandle> {
        return trailEntities.flatMap { spec ->
            val baseLayer = spec.layers.firstOrNull() ?: spec.layerSpec
            val baseSpec = spec.copy(nodes = localBeamNodes(baseLayer, spec.layerId))
            listOf(baseSpec) + buildRibbonDecorationSpecs(baseSpec)
        }.mapNotNull { spec ->
            when (val result = addTrailEntity(engine, spec)) {
                is ASTDTrailEntityBuildResult.Success -> ASTDTrailEntityHandle(result.entity, spec)
                is ASTDTrailEntityBuildResult.Failure -> null
            }
        }
    }

    fun updateExportHandle(handle: ASTDTrailEntityHandle, location: Vector2f, facing: Float) {
        if (handle.entity.hasDelete()) return
        handle.entity.setStateVanilla(location, facing)
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
            ribbonDecorations = listOfNotNull(ribbonDecoration),
        )
    }

    private fun buildRibbonDecorationSpecs(spec: ASTDTrailEntitySpec): List<ASTDTrailEntitySpec> {
        val baseLayer = spec.layers.firstOrNull() ?: spec.layerSpec
        return spec.ribbonDecorations.filter { it.enabled }.map { decoration ->
            val decorationLayer = decorationLayerSpec(baseLayer, decoration)
            val decorationId = decoration.id.ifBlank { "ribbon" }
            ASTDTrailEntitySpec(
                layerId = "${spec.layerId}.$decorationId",
                id = "${spec.id}.$decorationId",
                nodes = localBeamNodes(decorationLayer, decorationId),
                layerSpec = decorationLayer,
                ribbonDecorations = emptyList(),
                layers = listOf(decorationLayer),
            )
        }
    }

    private fun decorationLayerSpec(baseLayer: ASTDTrailLayerSpec, decoration: ASTDTrailRibbonDecorationSpec): ASTDTrailLayerSpec {
        val startColor = decoration.startColor.scaledAlpha(decoration.alphaScale)
        val endColor = decoration.endColor.scaledAlpha(decoration.alphaScale)
        return baseLayer.copy(
            width = baseLayer.width * decoration.thickness,
            color = decoration.color.scaledAlpha(decoration.alphaScale),
            startColor = startColor,
            endColor = endColor,
            startEmissive = startColor,
            endEmissive = endColor,
            startWidth = baseLayer.startWidth * decoration.thickness,
            endWidth = baseLayer.endWidth * decoration.thickness,
            fillStartAlpha = baseLayer.fillStartAlpha * decoration.alphaScale,
            fillEndAlpha = baseLayer.fillEndAlpha * decoration.alphaScale,
            jitterPower = baseLayer.jitterPower + decoration.blur * 0.02f,
        )
    }

    private fun localBeamNodes(layer: ASTDTrailLayerSpec, id: String): List<Vector2f> {
        val yOffset = if (id.contains("ribbon", ignoreCase = true)) 10f else 0f
        return ASTDProjectileVfxLayout.trailLocalNodes(layer.length, yOffset)
    }

    private fun deterministicNoise(index: Int, scale: Float): Float {
        val x = index * scale.coerceAtLeast(0.001f) * 12.9898f
        return (sin(x) * cos(x * 0.41f) + sin(x * 0.37f) * 0.5f).coerceIn(-1f, 1f)
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
