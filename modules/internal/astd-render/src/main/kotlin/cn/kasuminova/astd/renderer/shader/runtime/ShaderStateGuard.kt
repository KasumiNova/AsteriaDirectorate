package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.impl.render.ShaderGlState

object ShaderStateGuard {
    fun withSavedState(block: () -> Unit) = ShaderGlState.withSavedState(block)
}
