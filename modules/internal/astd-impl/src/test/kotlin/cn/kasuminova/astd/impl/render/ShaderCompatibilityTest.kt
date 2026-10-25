package cn.kasuminova.astd.impl.render

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderCompatibilityTest {
    @Test
    fun `detects GLES from the runtime shading language version`() {
        assertTrue(ShaderCompatibility.isGles("OpenGL ES GLSL ES 3.20"))
        assertTrue(ShaderCompatibility.isGles("OpenGL ES GLSL ES 1.00"))
        assertFalse(ShaderCompatibility.isGles("4.60 NVIDIA"))
    }

    @Test
    fun `adds version and precision only for GLES sources`() {
        val fragment = "void main() { gl_FragColor = vec4(1.0); }"
        val desktopVersion = "4.60 NVIDIA"
        val glesVersion = "OpenGL ES GLSL ES 3.20"

        assertEquals(ShaderCompatibility.VERTEX_SHADER_SOURCE, ShaderCompatibility.vertexSource(desktopVersion))
        assertEquals(fragment, ShaderCompatibility.fragmentSource(fragment, desktopVersion))
        assertEquals("#version 100\n${ShaderCompatibility.VERTEX_SHADER_SOURCE}", ShaderCompatibility.vertexSource(glesVersion))
        assertEquals(
            "#version 100\nprecision highp float;\nprecision highp int;\n$fragment",
            ShaderCompatibility.fragmentSource(fragment, glesVersion),
        )
    }

    @Test
    fun `world clip preserves camera center and visible extent`() {
        val clip = ShaderClip.world(120f, -40f, 800f, 600f)

        assertEquals(120f, clip.centerX)
        assertEquals(-40f, clip.centerY)
        assertEquals(2f / 800f, clip.scaleX)
        assertEquals(2f / 600f, clip.scaleY)
        assertEquals(0f, clip.offsetX)
        assertEquals(0f, clip.offsetY)
    }

    @Test
    fun `ui clip maps logical screen coordinates into clip space`() {
        val clip = ShaderClip.ui(1920f, 1080f)

        assertEquals(0f, clip.centerX)
        assertEquals(0f, clip.centerY)
        assertEquals(2f / 1920f, clip.scaleX)
        assertEquals(2f / 1080f, clip.scaleY)
        assertEquals(-1f, clip.offsetX)
        assertEquals(-1f, clip.offsetY)
    }

    @Test
    fun `quad vertices use triangle strip order with interleaved positions and UVs`() {
        assertContentEquals(
            floatArrayOf(
                10f, 20f, 0f, 0f,
                40f, 20f, 1f, 0f,
                10f, 70f, 0f, 1f,
                40f, 70f, 1f, 1f,
            ),
            ShaderQuadRenderer.quadVertices(10f, 20f, 30f, 50f),
        )
    }
}
