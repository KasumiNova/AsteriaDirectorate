package cn.kasuminova.astd.ui.render

import org.lwjgl.opengl.GL11

/**
 * GL11 模板缓冲工具：在指定矩形区域内裁剪渲染内容。
 *
 * 使用模式：
 * ```kotlin
 * ASTDStencilRenderer.withStencilMask(x, y, width, height) {
 *     // 这里的所有 GL 绘制调用都会被裁剪到上述矩形范围内
 * }
 * ```
 */
object ASTDStencilRenderer {

    /**
     * 在 (x, y) 为左下角、宽 [w]、高 [h] 的矩形内启用模板裁剪，
     * 执行 [block] 中的所有渲染调用，然后恢复 GL 状态。
     */
    inline fun withStencilMask(x: Float, y: Float, w: Float, h: Float, block: () -> Unit) {
        // 1. 清除模板缓冲
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)

        // 2. 写入模板：在矩形区域写 1
        GL11.glEnable(GL11.GL_STENCIL_TEST)
        GL11.glColorMask(false, false, false, false)
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF)
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()

        // 3. 切换为"仅在模板=1的区域渲染"
        GL11.glColorMask(true, true, true, true)
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF)
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)

        // 4. 执行裁剪后的渲染
        block()

        // 5. 恢复
        GL11.glDisable(GL11.GL_STENCIL_TEST)
    }

    /**
     * 绘制一个纯色半透明矩形（用于暗色遮罩层）。
     */
    fun drawRect(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float) {
        GL11.glColor4f(r, g, b, a)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(x, y)
        GL11.glVertex2f(x + w, y)
        GL11.glVertex2f(x + w, y + h)
        GL11.glVertex2f(x, y + h)
        GL11.glEnd()
    }
}
