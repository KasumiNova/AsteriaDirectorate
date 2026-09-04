package cn.kasuminova.astd.renderer.shader.base

/**
 * Source contract for a shader program used by a VFX effect.
 *
 * The [id] names the program within renderer diagnostics and caches. The
 * [vertexSource] and [fragmentSource] fields carry the GLSL source that a
 * runtime compiler will consume.
 */
data class ShaderProgramSpec(
    val id: String,
    val vertexSource: String,
    val fragmentSource: String,
) {
    init {
        require(id.isNotBlank()) { "Shader program id must not be blank" }
        require(vertexSource.isNotBlank()) { "Shader program vertex source must not be blank: id=$id" }
        require(fragmentSource.isNotBlank()) { "Shader program fragment source must not be blank: id=$id" }
    }
}
