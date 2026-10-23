package cn.kasuminova.astd.renderer.shader.base

enum class ShaderBlendMode {
    Additive,
    Alpha,
}

data class ShaderMaterialSpec(
    val blendMode: ShaderBlendMode,
)
