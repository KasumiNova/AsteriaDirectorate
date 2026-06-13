package cn.kasuminova.astd.renderer.shader.base

/**
 * Render layer requested by a shader effect.
 *
 * The layer is part of the public contract so effect authors can express
 * ordering intent without depending on a concrete renderer implementation.
 */
enum class ShaderEffectLayer {
    BelowParticles,
    AboveParticles,
    AboveShips,
    ScreenSpace,
}

/**
 * Complete public contract for one shader-backed VFX effect.
 *
 * [id] is the catalog key. [program], [geometry], [material], and
 * [uniformSchema] describe what the runtime must render. [layer] controls
 * ordering intent, while [lifetimeSeconds], [staleAfterSeconds], and
 * [renderRadius] give runtime ownership and culling bounds.
 */
data class ShaderEffectSpec(
    val id: ShaderEffectKey,
    val program: ShaderProgramSpec,
    val geometry: ShaderGeometrySpec,
    val material: ShaderMaterialSpec,
    val uniformSchema: ShaderUniformSchema,
    val layer: ShaderEffectLayer,
    val lifetimeSeconds: Float,
    val staleAfterSeconds: Float,
    val renderRadius: Float,
) {
    init {
        require(lifetimeSeconds.isFinite() && lifetimeSeconds > 0f) {
            "Shader effect lifetimeSeconds must be a positive finite value: id=${id.value}"
        }
        require(staleAfterSeconds.isFinite() && staleAfterSeconds > 0f) {
            "Shader effect staleAfterSeconds must be a positive finite value: id=${id.value}"
        }
        require(renderRadius.isFinite() && renderRadius > 0f) {
            "Shader effect renderRadius must be a positive finite value: id=${id.value}"
        }
    }
}
