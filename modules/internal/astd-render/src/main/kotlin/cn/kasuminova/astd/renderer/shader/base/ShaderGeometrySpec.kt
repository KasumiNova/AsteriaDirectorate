package cn.kasuminova.astd.renderer.shader.base

/**
 * Geometry contract describing what mesh a shader effect renders into.
 *
 * Geometry specs are declarative only; runtime code owns buffer allocation and
 * projection details.
 */
sealed interface ShaderGeometrySpec {
    /**
     * World-space quad centered on the effect origin.
     *
     * [halfExtentWorld] is the positive half-size of the square quad in world
     * units. A runtime renderer can derive full width and height as twice this
     * value.
     */
    data class WorldQuad(val halfExtentWorld: Float) : ShaderGeometrySpec {
        init {
            require(halfExtentWorld.isFinite() && halfExtentWorld > 0f) {
                "Shader world quad halfExtentWorld must be a positive finite value"
            }
        }
    }
}
