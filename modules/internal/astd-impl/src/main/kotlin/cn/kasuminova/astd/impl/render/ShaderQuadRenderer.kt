package cn.kasuminova.astd.impl.render

import org.lwjgl.BufferUtils
import org.lwjgl.PointerBuffer
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import java.nio.ByteOrder

class ShaderQuadRenderer {
    private val vertices = BufferUtils.createFloatBuffer(VERTEX_FLOAT_COUNT)
    private val attributeState = BufferUtils.createIntBuffer(16)
    private var vboId = 0

    fun draw(x: Float, y: Float, width: Float, height: Float) {
        require(width.isFinite() && height.isFinite()) { "Quad extent must be finite" }
        if (vboId == 0) {
            vboId = GL15.glGenBuffers()
            check(vboId != 0) { "Failed to create shader quad VBO" }
        }

        val previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        val previousPosition = readAttribute(ShaderCompatibility.POSITION_ATTRIBUTE)
        val previousUv = readAttribute(ShaderCompatibility.UV_ATTRIBUTE)
        try {
            vertices.clear()
            vertices.put(quadVertices(x, y, width, height))
            vertices.flip()
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW)

            GL20.glVertexAttribPointer(ShaderCompatibility.POSITION_ATTRIBUTE, 2, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L)
            GL20.glVertexAttribPointer(ShaderCompatibility.UV_ATTRIBUTE, 2, GL11.GL_FLOAT, false, VERTEX_STRIDE, 8L)
            GL20.glEnableVertexAttribArray(ShaderCompatibility.POSITION_ATTRIBUTE)
            GL20.glEnableVertexAttribArray(ShaderCompatibility.UV_ATTRIBUTE)
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        } finally {
            try {
                previousPosition.restore()
            } finally {
                try {
                    previousUv.restore()
                } finally {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer)
                }
            }
        }
    }

    private fun readAttribute(index: Int): AttributeState {
        val pointer = BufferUtils.createByteBuffer(16).order(ByteOrder.nativeOrder())
        GL20.glGetVertexAttribPointer(index, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER, pointer)
        pointer.clear()
        val offset = if (PointerBuffer.getPointerSize() == 8) pointer.long else pointer.int.toUInt().toLong()
        return AttributeState(
            index = index,
            enabled = attributeValue(index, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == GL11.GL_TRUE,
            size = attributeValue(index, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE),
            type = attributeValue(index, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE),
            normalized = attributeValue(index, GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED) == GL11.GL_TRUE,
            stride = attributeValue(index, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE),
            buffer = attributeValue(index, GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING),
            offset = offset,
        )
    }

    private fun attributeValue(index: Int, parameter: Int): Int {
        attributeState.clear()
        GL20.glGetVertexAttrib(index, parameter, attributeState)
        return attributeState.get(0)
    }

    fun cleanup() {
        if (vboId != 0) {
            GL15.glDeleteBuffers(vboId)
            vboId = 0
        }
    }

    companion object {
        private const val QUAD_VERTEX_COUNT = 4
        private const val VERTEX_FLOAT_COUNT = QUAD_VERTEX_COUNT * 4
        private const val VERTEX_STRIDE = 4 * Float.SIZE_BYTES

        fun quadVertices(x: Float, y: Float, width: Float, height: Float): FloatArray = floatArrayOf(
            x, y, 0f, 0f,
            x + width, y, 1f, 0f,
            x, y + height, 0f, 1f,
            x + width, y + height, 1f, 1f,
        )
    }
}

private data class AttributeState(
    val index: Int,
    val enabled: Boolean,
    val size: Int,
    val type: Int,
    val normalized: Boolean,
    val stride: Int,
    val buffer: Int,
    val offset: Long,
) {
    fun restore() {
        if (buffer != 0) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer)
            GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset)
        }
        if (enabled) GL20.glEnableVertexAttribArray(index) else GL20.glDisableVertexAttribArray(index)
    }
}
