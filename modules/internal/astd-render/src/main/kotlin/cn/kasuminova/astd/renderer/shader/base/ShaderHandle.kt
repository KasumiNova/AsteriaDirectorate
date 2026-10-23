package cn.kasuminova.astd.renderer.shader.base

data class ShaderHandle(
    val effectId: ShaderEffectKey,
    val instanceId: String,
) {
    init {
        require(instanceId.isNotBlank()) { "Shader handle instanceId must not be blank: effectId=${effectId.value}" }
    }
}
