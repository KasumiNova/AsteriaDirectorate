package cn.kasuminova.astd.renderer.shader.runtime

import org.lwjgl.util.vector.Vector2f

/**
 * Render-time viewport data available for shader host uniforms.
 */
data class ViewportContext(
    val center: Vector2f,
    val visibleWidth: Float,
    val visibleHeight: Float,
    val viewMult: Float,
) {
    init {
        require(visibleWidth.isFinite() && visibleWidth > 0f) { "Viewport visibleWidth must be positive and finite" }
        require(visibleHeight.isFinite() && visibleHeight > 0f) { "Viewport visibleHeight must be positive and finite" }
        require(viewMult.isFinite() && viewMult > 0f) { "Viewport viewMult must be positive and finite" }
    }
}
