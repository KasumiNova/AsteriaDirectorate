package cn.kasuminova.astd.renderer.projectile.runtime

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxMistLayerSpec
import cn.kasuminova.astd.renderer.projectile.ASTDTrailEntitySpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

object ASTDProjectileVfxMistRenderer {
    data class MistSample(val position: Vector2f, val rx: Float, val ry: Float, val alpha: Float)

    fun samplesForTests(layer: ASTDProjectileVfxMistLayerSpec, context: ASTDProjectileVfxRenderContext, length: Float, widthBase: Float): List<MistSample> {
        return (0 until layer.blobCount).map { index ->
            val seed = index * 13.71f
            val t = (index + ASTDProjectileVfxMath.shaderNoise(seed, context.elapsed * 0.17f)) / layer.blobCount.coerceAtLeast(1)
            val envelope = sin(PI.toFloat() * t.coerceIn(0f, 1f)).coerceAtLeast(0f)
            val noise = ASTDProjectileVfxMath.layeredNoise(t * layer.noiseScale - context.elapsed * layer.driftSpeed, seed * 0.017f)
            val x = -length * layer.lengthScale * t
            val y = (ASTDProjectileVfxMath.shaderNoise(seed, 8.4f) - 0.5f) * widthBase * 5.4f * layer.widthScale * envelope
            val rx = widthBase * lerp(layer.rxRange.min, layer.rxRange.max, noise) * (0.3f + envelope)
            val ry = widthBase * lerp(layer.ryRange.min, layer.ryRange.max, ASTDProjectileVfxMath.shaderNoise(seed, 12.2f)) * (0.4f + envelope * 0.7f)
            val alpha = context.beamAlpha * lerp(layer.alphaRange.min, layer.alphaRange.max, noise) * envelope
            MistSample(Vector2f(x, y), rx, ry, alpha)
        }
    }

    private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t
}

class ASTDProjectileVfxMistRenderLayer(
    private val trail: ASTDTrailEntitySpec,
    private val layers: List<ASTDProjectileVfxMistLayerSpec>,
) : ASTDProjectileVfxRenderLayer {
    private data class Handle(val layer: ASTDProjectileVfxMistLayerSpec, val entity: SpriteEntity, val instances: MutableList<Instance2Data>)

    private val handles = ArrayList<Handle>()
    private val fade = ASTDProjectileVfxLayerFadeState()

    override fun create(engine: CombatEngineAPI?, context: ASTDProjectileVfxRenderContext): Boolean {
        if (engine == null) return false
        if (handles.isNotEmpty()) return true
        BoxUtilCombatVfx.ensureReady(engine)
        layers.filter { it.enabled }.forEach { layer ->
            val entity = SpriteEntity()
            entity.setLayer((trail.layers.firstOrNull() ?: trail.layerSpec).combatLayer)
            entity.setAdditiveBlend()
            entity.setBaseSizePerTiles(1f, 1f)
            entity.materialData.setColor(Color(0, 0, 0, 255))
            entity.materialData.setEmissiveColor(layer.colorEnd.toAwtColor(1f))
            entity.materialData.setEmissiveState(0f, 0f, 1f)
            entity.materialData.setAdditionEmissive(true)
            entity.materialData.setIgnoreIllumination(true)
            entity.setStateVanilla(context.location, context.renderFacing)
            val instances = MutableList(layer.blobCount.coerceAtLeast(0)) { Instance2Data() }
            applySamples(layer, context, entity, instances)
            @Suppress("UNCHECKED_CAST")
            entity.setInstanceData(instances as MutableList<InstanceDataAPI>, 0f, 3600f, 0f)
            entity.setInstanceDataRefreshIndex(0)
            entity.setInstanceDataRefreshAllFromCurrentIndex()
            entity.submitInstance()
            entity.setRenderingCount(instances.size)
            entity.setAlwaysRefreshInstanceData(true)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SPRITE, entity)
            if (state == 0) {
                handles += Handle(layer, entity, instances)
            } else {
                entity.delete()
                delete()
                throw IllegalStateException(
                    "ASTD projectile VFX mist BoxUtil SpriteEntity addEntity failed: " +
                        "state=$state layer=${layer.id} preset=${context.presetId} projectile=${context.projectileSpecId}",
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
                handle.entity.setStateVanilla(context.location, context.renderFacing)
                applySamples(handle.layer, context.copy(beamAlpha = context.beamAlpha * fade.alpha()), handle.entity, handle.instances)
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

    private fun applySamples(layer: ASTDProjectileVfxMistLayerSpec, context: ASTDProjectileVfxRenderContext, entity: SpriteEntity, instances: MutableList<Instance2Data>) {
        val baseLayer = trail.layers.firstOrNull() ?: trail.layerSpec
        val widthBase = ASTDProjectileVfxLayout.widthBase(baseLayer)
        val samples = ASTDProjectileVfxMistRenderer.samplesForTests(layer, context, context.visibleLength, widthBase)
        val scale = context.worldUnitsPerPixel.coerceAtLeast(0.0001f)
        samples.forEachIndexed { index, sample ->
            val data = instances[index]
            data.setLocation(ASTDProjectileVfxLayout.scalePoint(sample.position, scale))
            data.setScale(max(0.1f, sample.rx * scale), max(0.1f, sample.ry * scale))
            data.setFacing(0f)
            data.setColor(layer.colorStart.red, layer.colorStart.green, layer.colorStart.blue, 0f)
            data.setEmissiveColor(layer.colorEnd.red, layer.colorEnd.green, layer.colorEnd.blue, sample.alpha)
            data.setTimer(0f, 3600f, 0f)
        }
        entity.setInstanceDataRefreshIndex(0)
        entity.setInstanceDataRefreshAllFromCurrentIndex()
        entity.submitInstance()
        entity.setRenderingCount(samples.size)
    }
}
