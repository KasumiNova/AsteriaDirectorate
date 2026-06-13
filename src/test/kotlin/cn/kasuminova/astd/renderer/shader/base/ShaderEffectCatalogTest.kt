package cn.kasuminova.astd.renderer.shader.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShaderEffectCatalogTest {
    @Test
    fun `catalog rejects duplicate effect ids`() {
        val first = effectSpec("astd_shader_pulse")
        val second = effectSpec("astd_shader_pulse")

        val error = assertFailsWith<IllegalArgumentException> {
            ShaderEffectCatalog(listOf(first, second))
        }

        assertEquals("Duplicate shader effect id: astd_shader_pulse", error.message)
    }

    @Test
    fun `catalog returns registered specs and null for unknown ids`() {
        val pulse = effectSpec("astd_shader_pulse")
        val catalog = ShaderEffectCatalog(listOf(pulse))

        assertSame(pulse, catalog.spec(ShaderEffectKey("astd_shader_pulse")))
        assertSame(pulse, catalog.spec("astd_shader_pulse"))
        assertNull(catalog.spec(ShaderEffectKey("missing")))
    }

    @Test
    fun `effect spec exposes shader pipeline contract fields`() {
        val schema = ShaderUniformSchema(
            listOf(
                ShaderUniformDefinition("intensity", ShaderUniformType.Float),
            ),
        )
        val program = ShaderProgramSpec(
            id = "pulse_program",
            vertexSource = "void main() { gl_Position = vec4(0.0); }",
            fragmentSource = "void main() { }",
        )
        val geometry = ShaderGeometrySpec.WorldQuad(halfExtentWorld = 120f)
        val material = ShaderMaterialSpec(blendMode = ShaderBlendMode.Additive)
        val spec = ShaderEffectSpec(
            id = ShaderEffectKey("astd_shader_pulse"),
            program = program,
            geometry = geometry,
            material = material,
            uniformSchema = schema,
            layer = ShaderEffectLayer.AboveParticles,
            lifetimeSeconds = 0.9f,
            staleAfterSeconds = 0.25f,
            renderRadius = 320f,
        )

        assertEquals(ShaderEffectKey("astd_shader_pulse"), spec.id)
        assertSame(program, spec.program)
        assertSame(geometry, spec.geometry)
        assertSame(material, spec.material)
        assertSame(schema, spec.uniformSchema)
        assertEquals(ShaderEffectLayer.AboveParticles, spec.layer)
        assertEquals(0.9f, spec.lifetimeSeconds)
        assertEquals(0.25f, spec.staleAfterSeconds)
        assertEquals(320f, spec.renderRadius)
    }

    @Test
    fun `material exposes required blend modes`() {
        assertTrue(ShaderBlendMode.entries.toSet().containsAll(setOf(ShaderBlendMode.Additive, ShaderBlendMode.Alpha)))
    }

    private fun effectSpec(id: String) = ShaderEffectSpec(
        id = ShaderEffectKey(id),
        program = ShaderProgramSpec(
            id = "${id}_program",
            vertexSource = "void main() { gl_Position = vec4(0.0); }",
            fragmentSource = "void main() { }",
        ),
        geometry = ShaderGeometrySpec.WorldQuad(halfExtentWorld = 96f),
        material = ShaderMaterialSpec(blendMode = ShaderBlendMode.Alpha),
        uniformSchema = ShaderUniformSchema(emptyList()),
        layer = ShaderEffectLayer.AboveParticles,
        lifetimeSeconds = 1f,
        staleAfterSeconds = 0.2f,
        renderRadius = 256f,
    )
}
