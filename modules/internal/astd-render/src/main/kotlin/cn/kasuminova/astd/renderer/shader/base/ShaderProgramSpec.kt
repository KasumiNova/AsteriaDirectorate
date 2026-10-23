package cn.kasuminova.astd.renderer.shader.base

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
