package cn.kasuminova.astd.renderer.shader.base

enum class ShaderEffectLayer {
    BelowParticles,
    AboveParticles,
    AboveShips,
    ScreenSpace,
}

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
