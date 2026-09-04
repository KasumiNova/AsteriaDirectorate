package cn.kasuminova.astd.renderer.shader.domain

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import org.lwjgl.util.vector.Vector2f

/**
 * Shader VFX adapter for trail or ribbon effects.
 *
 * BoxUtil remains the preferred path for high-volume ordinary trails. This
 * contract exists for trails that specifically need shader material behavior,
 * such as procedural coloring, distortion, or custom dissolve.
 */
interface TrailShaderEffect {
    /** Static shader effect contract rendered by this trail adapter. */
    val effectSpec: ShaderEffectSpec

    /**
     * Submit or update a shader trail for [emitter].
     */
    fun upsert(sink: ShaderSink, emitter: TrailEmitter): ShaderHandle?
}

/**
 * Stable source of control points for a shader trail.
 *
 * Implementations own sampling and retention. The shader effect consumes an
 * immutable snapshot of [points] each frame instead of scanning game entities.
 */
interface TrailEmitter {
    /** Stable key used by the shader runtime for keyed trail updates. */
    val id: String

    /** Whether this emitter should continue feeding the trail. */
    val isAlive: Boolean

    /** Ordered trail control points from oldest to newest. */
    val points: List<TrailPoint>
}

/**
 * One sampled point in a shader trail.
 */
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
