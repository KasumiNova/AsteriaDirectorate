package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import org.lwjgl.util.vector.Vector2f

/**
 * Immutable world-space point captured by a shader submission.
 *
 * Starsector and LWJGL vectors are mutable, so runtime queues copy submitted
 * positions into this value object before storing them.
 */
data class ShaderWorldPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "Shader world point must use finite coordinates: x=$x, y=$y" }
    }

    fun toVector2f(): Vector2f = Vector2f(x, y)

    companion object {
        fun from(point: Vector2f): ShaderWorldPoint = ShaderWorldPoint(point.x, point.y)
    }
}

/**
 * Complete render snapshot submitted to the shader runtime.
 *
 * The snapshot is detached from mutable caller state. One-shot submissions
 * have no [handle]; keyed submissions carry a [ShaderHandle] so callers can
 * update or remove the same live effect instance.
 */
data class ShaderSubmission(
    val spec: ShaderEffectSpec,
    val handle: ShaderHandle?,
    val center: ShaderWorldPoint,
    val facing: Float,
    val uniforms: ShaderUniformSet,
    val renderOrder: Int,
    val submittedAt: Float,
    val startedAt: Float,
) {
    init {
        require(facing.isFinite()) { "Shader submission facing must be finite: effectId=${spec.id.value}" }
        require(submittedAt.isFinite() && submittedAt >= 0f) {
            "Shader submission submittedAt must be a non-negative finite value: effectId=${spec.id.value}"
        }
        require(startedAt.isFinite() && startedAt >= 0f) {
            "Shader submission startedAt must be a non-negative finite value: effectId=${spec.id.value}"
        }
    }

    val ageSeconds: Float get() = (submittedAt - startedAt).coerceAtLeast(0f)

    fun asOneShotSubmittedAt(timeSeconds: Float): ShaderSubmission = copy(submittedAt = timeSeconds, startedAt = timeSeconds)

    companion object {
        fun emit(
            spec: ShaderEffectSpec,
            center: Vector2f,
            facing: Float,
            uniforms: ShaderUniformSet,
            renderOrder: Int,
            submittedAt: Float,
        ): ShaderSubmission = ShaderSubmission(
            spec = spec,
            handle = null,
            center = ShaderWorldPoint.from(center),
            facing = facing,
            uniforms = uniforms,
            renderOrder = renderOrder,
            submittedAt = submittedAt,
            startedAt = submittedAt,
        )

        fun keyed(
            spec: ShaderEffectSpec,
            instanceId: String,
            center: Vector2f,
            facing: Float,
            uniforms: ShaderUniformSet,
            renderOrder: Int,
            submittedAt: Float,
            startedAt: Float,
        ): ShaderSubmission = ShaderSubmission(
            spec = spec,
            handle = ShaderHandle(spec.id, instanceId),
            center = ShaderWorldPoint.from(center),
            facing = facing,
            uniforms = uniforms,
            renderOrder = renderOrder,
            submittedAt = submittedAt,
            startedAt = startedAt,
        )
    }
}
