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
import kotlin.test.assertTrue

class ShaderLifecycleTest {
    @Test
    fun `one shot submissions expire after lifetime`() {
        val queue = ShaderRenderQueue()
        queue.emit(submission(submittedAt = 0f, lifetimeSeconds = 0.5f))

        queue.advance(0.49f)
        assertEquals(1, queue.snapshotsForLayer(ShaderEffectLayer.AboveParticles).size)

        queue.advance(0.02f)
        assertTrue(queue.snapshotsForLayer(ShaderEffectLayer.AboveParticles).isEmpty())
    }

    @Test
    fun `keyed upsert replaces snapshot and stale submissions expire`() {
        val queue = ShaderRenderQueue()
        val spec = spec(staleAfterSeconds = 0.2f)
        val handle = queue.upsert(
            instanceId = "ship-1",
            submission = ShaderSubmission.keyed(
                spec = spec,
                instanceId = "ship-1",
                center = Vector2f(1f, 2f),
                facing = 0f,
                uniforms = ShaderUniformSet(spec.uniformSchema, emptyMap()),
                renderOrder = 0,
                submittedAt = 0f,
                startedAt = 0f,
            ),
        )

        queue.upsert(
            instanceId = "ship-1",
            submission = ShaderSubmission.keyed(
                spec = spec,
                instanceId = "ship-1",
                center = Vector2f(7f, 8f),
                facing = 90f,
                uniforms = ShaderUniformSet(spec.uniformSchema, emptyMap()),
                renderOrder = 1,
                submittedAt = 0.1f,
                startedAt = 0f,
            ),
        )
        val snapshot = queue.snapshotsForLayer(ShaderEffectLayer.AboveParticles).single()
        assertEquals(handle, snapshot.handle)
        assertEquals(7f, snapshot.center.x)
        assertEquals(8f, snapshot.center.y)
        assertEquals(90f, snapshot.facing)

        queue.advance(0.31f)
        assertTrue(queue.snapshotsForLayer(ShaderEffectLayer.AboveParticles).isEmpty())
    }

    @Test
    fun `remove deletes active keyed submission`() {
        val queue = ShaderRenderQueue()
        val handle = queue.upsert("ship-1", submission(instanceId = "ship-1"))

        queue.remove(handle)

        assertTrue(queue.snapshotsForLayer(ShaderEffectLayer.AboveParticles).isEmpty())
    }

    private fun submission(
        instanceId: String? = null,
        submittedAt: Float = 0f,
        lifetimeSeconds: Float = 1f,
    ): ShaderSubmission {
        val spec = spec(lifetimeSeconds = lifetimeSeconds)
        val uniforms = ShaderUniformSet(spec.uniformSchema, emptyMap())
        return if (instanceId == null) {
            ShaderSubmission.emit(spec, Vector2f(0f, 0f), 0f, uniforms, 0, submittedAt)
        } else {
            ShaderSubmission.keyed(spec, instanceId, Vector2f(0f, 0f), 0f, uniforms, 0, submittedAt, submittedAt)
        }
    }

    private fun spec(lifetimeSeconds: Float = 1f, staleAfterSeconds: Float = 0.25f) = ShaderEffectSpec(
        id = ShaderEffectKey("lifecycle_effect"),
        program = ShaderProgramSpec(
            id = "lifecycle_program",
            vertexSource = "void main() { gl_Position = vec4(0.0); }",
            fragmentSource = "void main() { }",
        ),
        geometry = ShaderGeometrySpec.WorldQuad(halfExtentWorld = 120f),
        material = ShaderMaterialSpec(ShaderBlendMode.Additive),
        uniformSchema = ShaderUniformSchema(emptyList()),
        layer = ShaderEffectLayer.AboveParticles,
        lifetimeSeconds = lifetimeSeconds,
        staleAfterSeconds = staleAfterSeconds,
        renderRadius = 240f,
    )
}
