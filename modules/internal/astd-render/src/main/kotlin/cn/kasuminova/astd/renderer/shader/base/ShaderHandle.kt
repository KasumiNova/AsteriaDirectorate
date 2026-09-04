package cn.kasuminova.astd.renderer.shader.base

/**
 * Public handle for a runtime shader effect instance.
 *
 * [effectId] links the handle back to the registered effect contract, and
 * [instanceId] lets the runtime distinguish multiple live instances of the
 * same effect.
 */
data class ShaderHandle(
    val effectId: ShaderEffectKey,
    val instanceId: String,
) {
    init {
        require(instanceId.isNotBlank()) { "Shader handle instanceId must not be blank: effectId=${effectId.value}" }
    }
}
