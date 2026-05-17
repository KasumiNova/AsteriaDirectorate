package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.projectile.ASTDColor
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxAnchorMode
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxOrientationMode
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailLayerSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

object ASTDProjectileVfxTrailRenderer {
    data class Parameters(
        val startWidth: Float,
        val endWidth: Float,
        val anchorMode: ASTDProjectileVfxAnchorMode,
        val orientationMode: ASTDProjectileVfxOrientationMode,
        val boxUtilFacing: Float,
        val alpha: Float,
    )

    fun localNodes(spec: ASTDTrailEntitySpec, yOffset: Float = 0f): List<Vector2f> {
        val layer = spec.layers.firstOrNull() ?: spec.layerSpec
        return ASTDProjectileVfxLayout.trailLocalNodes(layer.length, yOffset)
    }

    fun parametersForTests(spec: ASTDTrailEntitySpec, context: ASTDProjectileVfxRenderContext): Parameters {
        val layer = spec.layers.firstOrNull() ?: spec.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(layer)
        return Parameters(
            startWidth = widthBase,
            endWidth = (layer.endWidth * 0.075f).coerceAtLeast(0.3f),
            anchorMode = spec.anchorMode,
            orientationMode = spec.orientationMode,
            boxUtilFacing = context.renderFacing,
            alpha = context.beamAlpha,
        )
    }

    internal fun createEntity(
        engine: CombatEngineAPI,
        spec: ASTDTrailEntitySpec,
        context: ASTDProjectileVfxRenderContext,
        yOffset: Float = 0f,
        alphaScale: Float = 1f,
        headWidthOverride: Float? = null,
        tailWidthOverride: Float? = null,
    ): TrailEntity? {
        BoxUtilCombatVfx.ensureReady(engine)
        val layer = spec.layers.firstOrNull() ?: spec.layerSpec
        val entity = TrailEntity()
        ASTDProjectileVfxLayout.trailLocalNodes(context.visibleLength, yOffset).forEach { entity.addNode(Vector2f(it)) }
        entity.submitNodes()
        applyLayer(entity, layer, context.beamAlpha * alphaScale, headWidthOverride, tailWidthOverride)
        val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, entity)
        if (state != 0) {
            entity.delete()
            Global.getLogger(ASTDProjectileVfxTrailRenderer::class.java).warn(
                "ASTD projectile VFX addEntity failed state=$state layer=${spec.layerId} preset=${context.presetId} projectile=${context.projectileSpecId}",
            )
            return null
        }
        entity.setStateVanilla(context.location, context.renderFacing)
        return entity
    }

    internal fun applyLayer(
        entity: TrailEntity,
        layer: ASTDTrailLayerSpec,
        alpha: Float,
        headWidthOverride: Float? = null,
        tailWidthOverride: Float? = null,
    ) {
        val headWidth = headWidthOverride ?: ASTDProjectileVfxLayout.widthBase(layer)
        val tailWidth = tailWidthOverride ?: (layer.endWidth * 0.075f).coerceAtLeast(0.3f)
        entity.setLayer(layer.combatLayer)
        if (layer.additive || layer.blendMode.equals("additive", ignoreCase = true)) entity.setAdditiveBlend()
        entity.setGlobalTimer(0f, 3600f, 0f)
        entity.setStartWidth(tailWidth)
        entity.setEndWidth(headWidth)
        entity.setStartColor(layer.endColor.red, layer.endColor.green, layer.endColor.blue, layer.endColor.alpha * alpha)
        entity.setEndColor(layer.startColor.red, layer.startColor.green, layer.startColor.blue, layer.startColor.alpha * alpha)
        entity.setStartEmissive(layer.endEmissive.red, layer.endEmissive.green, layer.endEmissive.blue, layer.endEmissive.alpha * alpha)
        entity.setEndEmissive(layer.startEmissive.red, layer.startEmissive.green, layer.startEmissive.blue, layer.startEmissive.alpha * alpha)
        entity.setTexturePixels(layer.texturePixels)
        entity.setTextureSpeed(layer.textureSpeed)
        entity.setUVOffset(layer.uvOffset)
        entity.setFillStartAlpha(layer.fillStartAlpha * alpha)
        entity.setFillEndAlpha(layer.fillEndAlpha * alpha)
        entity.setFillStartFactor(layer.fillStartFactor)
        entity.setFillEndFactor(layer.fillEndFactor)
        entity.setJitterPower(layer.jitterPower)
        entity.setFlick(layer.flick)
        entity.setSyncFlick(layer.syncFlick)
        entity.setStripLineMode(layer.stripLineMode)
        entity.setFlowWhenPaused(layer.flowWhenPaused)
        entity.setFlickWhenPaused(layer.flickWhenPaused)
        entity.setFlickMixValue(layer.flickMixValue)
        layer.flickerSyncCode?.let { entity.setFlickerSyncCode(it) }
        entity.materialData.setColor(layer.color.toAwtColor(alpha))
        entity.materialData.setEmissiveColor(layer.startEmissive.toAwtColor(alpha))
        entity.materialData.setDiffuse(Global.getSettings().getSprite(layer.diffuseSpritePath))
        entity.materialData.setEmissive(Global.getSettings().getSprite(layer.emissiveSpritePath))
        entity.materialData.setAlphaToEmissive(0f)
        entity.materialData.setColorToEmissive(0f)
        entity.materialData.setGlowPower(1f)
    }
}

class ASTDProjectileVfxTrailRenderLayer(
    private val specs: List<ASTDTrailEntitySpec>,
) : ASTDProjectileVfxRenderLayer {
    private val handles = ArrayList<TrailEntity>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        specs.forEach { spec ->
            ASTDProjectileVfxTrailRenderer.createEntity(engine, spec, context)?.let { handles += it }
        }
        return handles.size == specs.size
    }

    override fun advance(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext, amount: Float) {
        fade.advance(amount)
        if (handles.isEmpty()) create(engine, context)
        handles.forEachIndexed { index, entity ->
            if (!entity.hasDelete()) {
                val spec = specs.getOrNull(index) ?: return@forEachIndexed
                val layer = spec.layers.firstOrNull() ?: spec.layerSpec
                entity.setNodes(ASTDProjectileVfxLayout.mutableTrailLocalNodes(context.visibleLength))
                entity.setNodeRefreshIndex(0)
                entity.setNodeRefreshAllFromCurrentIndex()
                entity.submitNodes()
                ASTDProjectileVfxTrailRenderer.applyLayer(entity, layer, context.beamAlpha * fade.alpha())
                entity.setStateVanilla(context.location, context.renderFacing)
            }
        }
        if (fade.complete()) delete()
    }

    override fun beginFadeOut(reason: ASTDProjectileVfxFadeReason, seconds: Float) {
        fade.begin(seconds)
    }

    override fun delete() {
        handles.forEach { it.delete() }
        handles.clear()
    }
}

internal fun ASTDColor.toAwtColor(alphaScale: Float = 1f): Color = Color(
    red.coerceIn(0f, 1f),
    green.coerceIn(0f, 1f),
    blue.coerceIn(0f, 1f),
    (alpha * alphaScale).coerceIn(0f, 1f),
)

internal fun rotateLocal(point: Vector2f, degrees: Float, origin: Vector2f): Vector2f {
    val radians = Math.toRadians(degrees.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    return Vector2f(origin.x + point.x * c - point.y * s, origin.y + point.x * s + point.y * c)
}
