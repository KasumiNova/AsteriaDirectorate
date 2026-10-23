package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import org.lwjgl.util.vector.Vector2f

interface TrailShaderEffect {
    val effectSpec: ShaderEffectSpec

    fun upsert(sink: ShaderSink, emitter: TrailEmitter): ShaderHandle?
}

interface TrailEmitter {
    val id: String

    val isAlive: Boolean

    val points: List<TrailPoint>
}

data class TrailPoint(
    val location: Vector2f,
    val width: Float,
    val ageSeconds: Float,
    val alpha: Float,
) {
    init {
        require(width.isFinite() && width >= 0f) { "Trail point width must be non-negative and finite" }
        require(ageSeconds.isFinite() && ageSeconds >= 0f) { "Trail point ageSeconds must be non-negative and finite" }
        require(alpha.isFinite() && alpha in 0f..1f) { "Trail point alpha must be in 0..1" }
    }
}
