package cn.kasuminova.astd.renderer.shader.runtime

import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectKey
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.base.ShaderMaterialSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSchema
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CombatShaderRuntimeTest {
    @Test
    fun `ensure installs one runtime plugin per host`() {
        val host = FakeShaderRuntimeHost()

        val first = CombatShaderRuntime.ensure(host)
        val second = CombatShaderRuntime.ensure(host)

        assertSame(first, second)
        assertEquals(1, host.addedPlugins.size)
    }

    @Test
    fun `runtime sink emits updates removes and cleanup clears active state`() {
        val runtime = CombatShaderRuntime.ensure(FakeShaderRuntimeHost())
        val spec = spec()
        val uniforms = ShaderUniformSet(spec.uniformSchema, emptyMap())

        runtime.sink.emit(spec, Vector2f(1f, 2f), 0f, uniforms)
        val handle = runtime.sink.upsert(spec, "ship-1", Vector2f(3f, 4f), 90f, uniforms)

        assertEquals(2, runtime.snapshotsForTests(ShaderEffectLayer.AboveParticles).size)

        runtime.sink.remove(handle)
        assertEquals(1, runtime.snapshotsForTests(ShaderEffectLayer.AboveParticles).size)

        runtime.cleanup()
        assertTrue(runtime.isExpired)
        assertTrue(runtime.snapshotsForTests(ShaderEffectLayer.AboveParticles).isEmpty())
    }

    @Test
    fun `runtime advance ignores paused host and advances active host`() {
        val host = FakeShaderRuntimeHost()
        val runtime = CombatShaderRuntime.ensure(host)
        val spec = spec(lifetimeSeconds = 0.2f)
        runtime.sink.emit(spec, Vector2f(1f, 2f), 0f, ShaderUniformSet(spec.uniformSchema, emptyMap()))

        host.isPaused = true
        runtime.advance(0.3f)
        assertFalse(runtime.snapshotsForTests(ShaderEffectLayer.AboveParticles).isEmpty())

        host.isPaused = false
        runtime.advance(0.3f)
        assertTrue(runtime.snapshotsForTests(ShaderEffectLayer.AboveParticles).isEmpty())
    }

    @Test
    fun `installed layered plugin advances runtime queue`() {
        val host = FakeShaderRuntimeHost()
        val runtime = CombatShaderRuntime.ensure(host)
        val spec = spec(lifetimeSeconds = 0.2f)
        runtime.sink.emit(spec, Vector2f(1f, 2f), 0f, ShaderUniformSet(spec.uniformSchema, emptyMap()))

        host.addedPlugins.single().advance(0.3f)

        assertTrue(runtime.snapshotsForTests(ShaderEffectLayer.AboveParticles).isEmpty())
    }

    private fun spec(lifetimeSeconds: Float = 1f) = ShaderEffectSpec(
        id = ShaderEffectKey("runtime_effect"),
        program = ShaderProgramSpec(
            id = "runtime_program",
            vertexSource = "void main() { gl_Position = vec4(0.0); }",
            fragmentSource = "void main() { }",
        ),
        geometry = ShaderGeometrySpec.WorldQuad(halfExtentWorld = 120f),
        material = ShaderMaterialSpec(ShaderBlendMode.Additive),
        uniformSchema = ShaderUniformSchema(emptyList()),
        layer = ShaderEffectLayer.AboveParticles,
        lifetimeSeconds = lifetimeSeconds,
        staleAfterSeconds = 0.25f,
        renderRadius = 240f,
    )
}
