package cn.kasuminova.astd.impl.render

import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

object ASTDProjectileVfxMistRenderer {
    data class MistSample(val position: Vector2f, val rx: Float, val ry: Float, val alpha: Float)

    fun samplesForTests(layer: ASTDProjectileVfxMistLayerSpec, context: ASTDProjectileVfxRenderContext, length: Float, widthBase: Float): List<MistSample> {
        val centerline = if (context.historyNodes.size >= 3 && !ASTDProjectileVfxCenterline.isEffectivelyStraight(context)) {
            ASTDProjectileVfxCenterline.build(context)
        } else {
            emptyList()
        }
        return (0 until layer.blobCount).map { index ->
            val seed = index * 13.71f
            val t = (index + ASTDProjectileVfxMath.shaderNoise(seed, context.elapsed * 0.17f)) / layer.blobCount.coerceAtLeast(1)
            val envelope = sin(PI.toFloat() * t.coerceIn(0f, 1f)).coerceAtLeast(0f)
            val noise = ASTDProjectileVfxMath.layeredNoise(t * layer.noiseScale - context.elapsed * layer.driftSpeed, seed * 0.017f)
            val lateralOffset = (ASTDProjectileVfxMath.shaderNoise(seed, 8.4f) - 0.5f) * widthBase * 5.4f * layer.widthScale * envelope
            val position = if (centerline.isNotEmpty()) {
                val base = ASTDProjectileVfxCenterline.sampleByRatio(centerline, t * layer.lengthScale)
                val normal = ASTDProjectileVfxCenterline.normalAt(centerline, t)
                Vector2f(base.position.x + normal.x * lateralOffset, base.position.y + normal.y * lateralOffset)
            } else {
                Vector2f(-length * layer.lengthScale * t, lateralOffset)
            }
            val rx = widthBase * lerp(layer.rxRange.min, layer.rxRange.max, noise) * (0.3f + envelope)
            val ry = widthBase * lerp(layer.ryRange.min, layer.ryRange.max, ASTDProjectileVfxMath.shaderNoise(seed, 12.2f)) * (0.4f + envelope * 0.7f)
            val alpha = context.beamAlpha * lerp(layer.alphaRange.min, layer.alphaRange.max, noise) * envelope
            MistSample(position, rx, ry, alpha)
        }
    }

    private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t
}
