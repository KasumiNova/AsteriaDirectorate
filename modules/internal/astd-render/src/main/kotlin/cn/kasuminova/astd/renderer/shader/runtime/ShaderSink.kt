package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import org.lwjgl.util.vector.Vector2f

/**
 * Developer-facing submission entry point for shader VFX.
 *
 * Domain adapters use this sink to emit one-shot effects, upsert persistent
 * keyed effects, and remove keyed instances without touching renderer internals.
 */
class ShaderSink internal constructor(private val queue: ShaderRenderQueue) {
    fun emit(
        spec: ShaderEffectSpec,
        center: Vector2f,
        facing: Float,
        uniforms: ShaderUniformSet,
        renderOrder: Int = 0,
    ) {
        queue.emit(ShaderSubmission.emit(spec, center, facing, uniforms, renderOrder, submittedAt = 0f))
    }

    fun upsert(
        spec: ShaderEffectSpec,
        instanceId: String,
        center: Vector2f,
        facing: Float,
        uniforms: ShaderUniformSet,
        renderOrder: Int = 0,
    ): ShaderHandle {
        return queue.upsert(
            instanceId,
            ShaderSubmission.keyed(spec, instanceId, center, facing, uniforms, renderOrder, submittedAt = 0f, startedAt = 0f),
        )
    }

    fun remove(handle: ShaderHandle) {
        queue.remove(handle)
    }
}
