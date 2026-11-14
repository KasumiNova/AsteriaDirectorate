package cn.kasuminova.astd.renderer.shader.base

enum class ShaderBlendMode {
    Additive,
    Alpha,
}

enum class ShaderTextureWrap {
    Repeat,
    Clamp,
}

/**
 * 片元着色器在纹理单元 0 上采样的贴图，采样器 uniform 固定为 `u_texture`。
 * [path] 必须在应用加载阶段 loadTexture 预加载；Repeat 环绕要求贴图边长为 2 的幂。
 */
data class ShaderTextureSpec(
    val path: String,
    val wrapU: ShaderTextureWrap = ShaderTextureWrap.Repeat,
    val wrapV: ShaderTextureWrap = ShaderTextureWrap.Clamp,
) {
    init {
        require(path.isNotBlank()) { "Shader texture path must not be blank" }
    }
}

data class ShaderMaterialSpec(
    val blendMode: ShaderBlendMode,
    val texture: ShaderTextureSpec? = null,
)
