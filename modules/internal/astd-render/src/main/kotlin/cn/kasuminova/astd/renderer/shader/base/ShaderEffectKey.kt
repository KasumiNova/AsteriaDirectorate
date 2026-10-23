package cn.kasuminova.astd.renderer.shader.base

@JvmInline
value class ShaderEffectKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Shader effect id must not be blank" }
    }

    override fun toString(): String = value
}
