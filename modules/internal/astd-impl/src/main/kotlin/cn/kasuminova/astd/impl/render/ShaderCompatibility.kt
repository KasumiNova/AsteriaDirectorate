package cn.kasuminova.astd.impl.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

object ShaderCompatibility {
    const val POSITION_ATTRIBUTE = 0
    const val UV_ATTRIBUTE = 1

    const val VERTEX_SHADER_SOURCE = """
        attribute vec2 a_position;
        attribute vec2 a_uv;
        uniform vec2 u_clipCenter;
        uniform vec4 u_clipTransform;
        varying vec2 v_pos;
        varying vec2 v_uv;

        void main() {
          v_pos = a_position;
          v_uv = a_uv;
          vec2 clip = (a_position - u_clipCenter) * u_clipTransform.xy + u_clipTransform.zw;
          gl_Position = vec4(clip, 0.0, 1.0);
        }
    """

    fun isGles(shadingLanguageVersion: String): Boolean = shadingLanguageVersion.contains("GLSL ES", ignoreCase = true)

    fun vertexSource(shadingLanguageVersion: String): String =
        if (isGles(shadingLanguageVersion)) "#version 100\n$VERTEX_SHADER_SOURCE" else VERTEX_SHADER_SOURCE

    fun fragmentSource(source: String, shadingLanguageVersion: String): String =
        if (isGles(shadingLanguageVersion)) {
            "#version 100\nprecision highp float;\nprecision highp int;\n$source"
        } else {
            source
        }

    fun compileProgram(id: String, fragmentSource: String): Int {
        val shadingLanguageVersion = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)
            ?: throw IllegalStateException("Missing GLSL version while compiling $id")
        val vertexShader = compileShader(id, GL20.GL_VERTEX_SHADER, "vertex", vertexSource(shadingLanguageVersion))
        var fragmentShader = 0
        try {
            fragmentShader = compileShader(
                id,
                GL20.GL_FRAGMENT_SHADER,
                "fragment",
                fragmentSource(fragmentSource, shadingLanguageVersion),
            )
            val program = GL20.glCreateProgram()
            check(program != 0) { "Failed to create shader program $id" }
            GL20.glAttachShader(program, vertexShader)
            GL20.glAttachShader(program, fragmentShader)
            GL20.glBindAttribLocation(program, POSITION_ATTRIBUTE, "a_position")
            GL20.glBindAttribLocation(program, UV_ATTRIBUTE, "a_uv")
            GL20.glLinkProgram(program)
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                val message = GL20.glGetProgramInfoLog(program, 4096)
                GL20.glDeleteProgram(program)
                throw IllegalStateException("Failed to link shader program $id: $message")
            }
            return program
        } finally {
            GL20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader)
        }
    }

    fun uploadClip(program: Int, clip: ShaderClip) {
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_clipCenter"), clip.centerX, clip.centerY)
        GL20.glUniform4f(
            GL20.glGetUniformLocation(program, "u_clipTransform"),
            clip.scaleX,
            clip.scaleY,
            clip.offsetX,
            clip.offsetY,
        )
    }

    private fun compileShader(id: String, type: Int, stage: String, source: String): Int {
        val shader = GL20.glCreateShader(type)
        check(shader != 0) { "Failed to create $stage shader for program $id" }
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val message = GL20.glGetShaderInfoLog(shader, 4096)
            GL20.glDeleteShader(shader)
            throw IllegalStateException("Failed to compile $stage shader for program $id: $message")
        }
        return shader
    }
}

data class ShaderClip(
    val centerX: Float,
    val centerY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    companion object {
        fun world(centerX: Float, centerY: Float, visibleWidth: Float, visibleHeight: Float): ShaderClip {
            require(visibleWidth.isFinite() && visibleWidth > 0f) { "Viewport width must be positive and finite" }
            require(visibleHeight.isFinite() && visibleHeight > 0f) { "Viewport height must be positive and finite" }
            return ShaderClip(centerX, centerY, 2f / visibleWidth, 2f / visibleHeight, 0f, 0f)
        }

        fun ui(screenWidth: Float, screenHeight: Float): ShaderClip {
            require(screenWidth.isFinite() && screenWidth > 0f) { "Screen width must be positive and finite" }
            require(screenHeight.isFinite() && screenHeight > 0f) { "Screen height must be positive and finite" }
            return ShaderClip(0f, 0f, 2f / screenWidth, 2f / screenHeight, -1f, -1f)
        }
    }
}
