package cn.kasuminova.astd.renderer.shader.base

/**
 * Blend modes supported by the shader VFX material contract.
 *
 * The names are intentionally renderer-facing and can be mapped to concrete
 * OpenGL or engine state by the runtime implementation.
 */
enum class ShaderBlendMode {
    Additive,
    Alpha,
}

/**
 * Material contract for a shader effect.
 *
 * [blendMode] is the minimum render-state choice required by all shader VFX
 * effects; future material fields should remain declarative and renderer-owned.
 */
data class ShaderMaterialSpec(
    val blendMode: ShaderBlendMode,
)
