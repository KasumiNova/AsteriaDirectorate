package cn.kasuminova.astd.ui.render

import cn.kasuminova.astd.impl.render.ShaderClip
import cn.kasuminova.astd.impl.render.ShaderCompatibility
import cn.kasuminova.astd.impl.render.ShaderGlState
import cn.kasuminova.astd.impl.render.ShaderQuadRenderer
import com.fs.starfarer.api.Global
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

/**
 * OpenGL 模板缓冲工具：在指定矩形区域内裁剪渲染内容。
 *
 * 使用模式：
 * ```kotlin
 * ASTDStencilRenderer.withStencilMask(x, y, width, height) {
 *     // 此块内的 GL 绘制调用都会被裁剪到上述矩形范围
 * }
 * ```
 */
object ASTDStencilRenderer {
    private const val FRAGMENT_SHADER_SOURCE = """
        uniform vec4 u_color;
        void main() {
          gl_FragColor = u_color;
        }
    """

    private val quadRenderer = ShaderQuadRenderer()
    private var programId = 0

    /**
     * 在 (x, y) 为左下角、宽 [w]、高 [h] 的矩形内启用模板裁剪，
     * 执行 [block] 中的所有渲染调用，然后恢复 GL 状态。
     */
    fun withStencilMask(x: Float, y: Float, w: Float, h: Float, block: () -> Unit) {
        ShaderGlState.withSavedState {
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
            GL11.glEnable(GL11.GL_STENCIL_TEST)
            GL11.glColorMask(false, false, false, false)
            GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF)
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
            drawSolidRect(x, y, w, h, 1f, 1f, 1f, 1f)

            GL11.glColorMask(true, true, true, true)
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF)
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
            block()
        }
    }

    fun drawRect(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float) {
        ShaderGlState.withSavedState {
            drawSolidRect(x, y, w, h, r, g, b, a)
        }
    }

    private fun drawSolidRect(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float) {
        if (programId == 0) {
            programId = ShaderCompatibility.compileProgram("astd-solid-quad", FRAGMENT_SHADER_SOURCE)
        }
        GL20.glUseProgram(programId)
        ShaderCompatibility.uploadClip(
            programId,
            ShaderClip.ui(Global.getSettings().screenWidth, Global.getSettings().screenHeight),
        )
        GL20.glUniform4f(GL20.glGetUniformLocation(programId, "u_color"), r, g, b, a)
        quadRenderer.draw(x, y, w, h)
    }
}
