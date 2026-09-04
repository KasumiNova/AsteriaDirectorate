package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

/**
 * OpenGL program cache keyed by shader program id.
 *
 * Compilation and linking fail fast with program id and shader stage so bad
 * shader specs are diagnosable during automation and development.
 */
class ShaderProgramCache {
    private val programs = LinkedHashMap<String, Int>()

    fun programFor(spec: ShaderProgramSpec): Int {
        return programs.getOrPut(spec.id) { compileProgram(spec) }
    }

    fun deleteAll() {
        for (program in programs.values) {
            GL20.glDeleteProgram(program)
        }
        programs.clear()
    }

    private fun compileProgram(spec: ShaderProgramSpec): Int {
        val vertexShader = compileShader(spec, GL20.GL_VERTEX_SHADER, "vertex", spec.vertexSource)
        val fragmentShader = compileShader(spec, GL20.GL_FRAGMENT_SHADER, "fragment", spec.fragmentSource)
        val program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertexShader)
        GL20.glAttachShader(program, fragmentShader)
        GL20.glLinkProgram(program)
        GL20.glDeleteShader(vertexShader)
        GL20.glDeleteShader(fragmentShader)
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            val message = GL20.glGetProgramInfoLog(program, 4096)
            GL20.glDeleteProgram(program)
            throw IllegalStateException("Failed to link shader program ${spec.id}: $message")
        }
        return program
    }

    private fun compileShader(spec: ShaderProgramSpec, type: Int, stage: String, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val message = GL20.glGetShaderInfoLog(shader, 4096)
            GL20.glDeleteShader(shader)
            throw IllegalStateException("Failed to compile $stage shader for program ${spec.id}: $message")
        }
        return shader
    }
}
