package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.impl.render.ShaderCompatibility
import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import org.lwjgl.opengl.GL20

class ShaderProgramCache {
    private val programs = LinkedHashMap<String, Int>()

    fun programFor(spec: ShaderProgramSpec): Int {
        return programs.getOrPut(spec.id) {
            require(spec.vertexSource == ShaderCompatibility.VERTEX_SHADER_SOURCE) {
                "Custom vertex shaders are not supported by the GLES-compatible renderer: id=${spec.id}"
            }
            ShaderCompatibility.compileProgram(spec.id, spec.fragmentSource)
        }
    }

    fun deleteAll() {
        for (program in programs.values) {
            GL20.glDeleteProgram(program)
        }
        programs.clear()
    }
}
