package cn.kasuminova.astd.renderer.shader.base

sealed interface ShaderGeometrySpec {
    data class WorldQuad(val halfExtentWorld: Float) : ShaderGeometrySpec {
        init {
            require(halfExtentWorld.isFinite() && halfExtentWorld > 0f) {
                "Shader world quad halfExtentWorld must be a positive finite value"
            }
        }
    }
}
