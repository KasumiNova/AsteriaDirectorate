package cn.kasuminova.astd.renderer.shader.base

/**
 * Stable identifier for a shader effect contract.
 *
 * The key is the public lookup value used by callers and catalogs. It fails
 * fast on blank values so invalid registrations are caught at construction.
 */
@JvmInline
value class ShaderEffectKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Shader effect id must not be blank" }
    }

    override fun toString(): String = value
}
