package cn.kasuminova.astd.renderer.shader.runtime

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

/**
 * Centralized OpenGL state save/restore for shader VFX rendering.
 */
object ShaderStateGuard {
    fun withSavedState(block: () -> Unit) {
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_TEXTURE_BIT)
        try {
            block()
        } finally {
            GL20.glUseProgram(previousProgram)
            GL11.glPopAttrib()
        }
    }
}
