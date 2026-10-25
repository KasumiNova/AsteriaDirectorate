package cn.kasuminova.astd.impl.render

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20

object ShaderGlState {
    fun withSavedState(block: () -> Unit) {
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val blend = GL11.glIsEnabled(GL11.GL_BLEND)
        val cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
        val srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
        val dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
        val srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
        val dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
        val stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
        val stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC)
        val stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF)
        val stencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK)
        val stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK)
        val stencilFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL)
        val stencilDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL)
        val stencilDepthPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS)
        val colorMask = BufferUtils.createByteBuffer(16)
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, colorMask)
        try {
            block()
        } finally {
            GL20.glUseProgram(previousProgram)
            if (blend) GL11.glEnable(GL11.GL_BLEND) else GL11.glDisable(GL11.GL_BLEND)
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
            if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST) else GL11.glDisable(GL11.GL_STENCIL_TEST)
            GL11.glStencilFunc(stencilFunc, stencilRef, stencilValueMask)
            GL11.glStencilMask(stencilWriteMask)
            GL11.glStencilOp(stencilFail, stencilDepthFail, stencilDepthPass)
            GL11.glColorMask(colorMask.get(0) != 0.toByte(), colorMask.get(1) != 0.toByte(), colorMask.get(2) != 0.toByte(), colorMask.get(3) != 0.toByte())
        }
    }
}
