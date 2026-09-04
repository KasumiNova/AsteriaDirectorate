package cn.kasuminova.astd.renderer.shader.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderUniformsTest {
    @Test
    fun `schema rejects duplicate uniform keys`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ShaderUniformSchema(
                listOf(
                    ShaderUniformDefinition("time", ShaderUniformType.Float),
                    ShaderUniformDefinition("time", ShaderUniformType.Int),
                ),
            )
        }

        assertEquals("Shader uniform schema contains duplicate key: time", error.message)
    }

    @Test
    fun `schema rejects blank uniform keys`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ShaderUniformSchema(
                listOf(
                    ShaderUniformDefinition(" ", ShaderUniformType.Float),
                ),
            )
        }

        assertEquals("Shader uniform key must not be blank", error.message)
    }

    @Test
    fun `uniform set requires all required uniforms`() {
        val schema = schema()

        val error = assertFailsWith<IllegalArgumentException> {
            ShaderUniformSet(
                schema,
                mapOf("intensity" to ShaderUniformValue.FloatValue(0.75f)),
            )
        }

        assertEquals("Missing required shader uniform: tint", error.message)
    }

    @Test
    fun `uniform set rejects unknown keys`() {
        val schema = schema()

        val error = assertFailsWith<IllegalArgumentException> {
            ShaderUniformSet(
                schema,
                mapOf(
                    "intensity" to ShaderUniformValue.FloatValue(0.75f),
                    "tint" to ShaderUniformValue.Vec4(1f, 0.8f, 0.6f, 1f),
                    "unknown" to ShaderUniformValue.BooleanValue(true),
                ),
            )
        }

        assertEquals("Unknown shader uniform key: unknown", error.message)
    }

    @Test
    fun `uniform set rejects type mismatches`() {
        val schema = schema()

        val error = assertFailsWith<IllegalArgumentException> {
            ShaderUniformSet(
                schema,
                mapOf(
                    "intensity" to ShaderUniformValue.IntValue(1),
                    "tint" to ShaderUniformValue.Vec4(1f, 0.8f, 0.6f, 1f),
                ),
            )
        }

        assertEquals("Shader uniform intensity expects Float but received Int", error.message)
    }

    @Test
    fun `uniform set applies defaults only for optional uniforms`() {
        val schema = schema()
        val uniforms = ShaderUniformSet(
            schema,
            mapOf(
                "intensity" to ShaderUniformValue.FloatValue(0.75f),
                "tint" to ShaderUniformValue.Vec4(1f, 0.8f, 0.6f, 1f),
            ),
        )

        assertEquals(ShaderUniformValue.FloatValue(0.75f), uniforms["intensity"])
        assertEquals(ShaderUniformValue.Vec4(1f, 0.8f, 0.6f, 1f), uniforms["tint"])
        assertEquals(ShaderUniformValue.Vec2(0.5f, 0.25f), uniforms["offset"])
        assertNull(uniforms["enabled"])
    }

    @Test
    fun `schema exposes all supported minimum uniform types`() {
        assertTrue(
            ShaderUniformType.entries.toSet().containsAll(
                setOf(
                    ShaderUniformType.Float,
                    ShaderUniformType.Vec2,
                    ShaderUniformType.Vec3,
                    ShaderUniformType.Vec4,
                    ShaderUniformType.Int,
                    ShaderUniformType.Boolean,
                ),
            ),
        )
    }

    private fun schema() = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition("intensity", ShaderUniformType.Float),
            ShaderUniformDefinition("tint", ShaderUniformType.Vec4),
            ShaderUniformDefinition(
                key = "offset",
                type = ShaderUniformType.Vec2,
                required = false,
                defaultValue = ShaderUniformValue.Vec2(0.5f, 0.25f),
            ),
            ShaderUniformDefinition("enabled", ShaderUniformType.Boolean, required = false),
        ),
    )
}
