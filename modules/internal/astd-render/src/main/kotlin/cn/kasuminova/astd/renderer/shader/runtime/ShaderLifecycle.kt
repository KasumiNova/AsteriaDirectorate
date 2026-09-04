package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec

/**
 * Lifecycle policy helpers for shader effects.
 *
 * This class keeps the timeout rules explicit and testable instead of hiding
 * them inside the layered rendering plugin.
 */
object ShaderLifecycle {
    fun isOneShotExpired(spec: ShaderEffectSpec, ageSeconds: Float): Boolean {
        require(ageSeconds.isFinite()) { "Shader one-shot age must be finite: effectId=${spec.id.value}" }
        return ageSeconds >= spec.lifetimeSeconds
    }

    fun isKeyedStale(spec: ShaderEffectSpec, ageSinceSubmitSeconds: Float): Boolean {
        require(ageSinceSubmitSeconds.isFinite()) { "Shader keyed age must be finite: effectId=${spec.id.value}" }
        return ageSinceSubmitSeconds > spec.staleAfterSeconds
    }
}
