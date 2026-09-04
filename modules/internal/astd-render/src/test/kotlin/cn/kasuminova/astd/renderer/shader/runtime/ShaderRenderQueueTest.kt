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

class ShaderRenderQueueTest {
    @Test
    fun `queue returns deterministic layer snapshots sorted by render order program material geometry and effect id`() {
        val queue = ShaderRenderQueue()
        val later = submission("z_effect", "b_program", ShaderBlendMode.Additive, renderOrder = 2)
        val alpha = submission("b_effect", "b_program", ShaderBlendMode.Alpha, renderOrder = 1)
        val additive = submission("a_effect", "b_program", ShaderBlendMode.Additive, renderOrder = 1)
        val firstProgram = submission("c_effect", "a_program", ShaderBlendMode.Additive, renderOrder = 1)

        queue.emit(later)
        queue.emit(alpha)
        queue.emit(additive)
        queue.emit(firstProgram)

        assertEquals(
            listOf("c_effect", "a_effect", "b_effect", "z_effect"),
            queue.snapshotsForLayer(ShaderEffectLayer.AboveParticles).map { it.spec.id.value },
        )
    }

    @Test
    fun `queue snapshots copy submitted vector data`() {
        val queue = ShaderRenderQueue()
        val center = Vector2f(12f, 34f)

        queue.emit(submission("copy_effect", center = center))
        center.set(90f, 80f)

        val snapshot = queue.snapshotsForLayer(ShaderEffectLayer.AboveParticles).single()
        assertEquals(12f, snapshot.center.x)
        assertEquals(34f, snapshot.center.y)
    }

    private fun submission(
        effectId: String,
        programId: String = "${effectId}_program",
        blendMode: ShaderBlendMode = ShaderBlendMode.Additive,
        renderOrder: Int = 0,
        center: Vector2f = Vector2f(0f, 0f),
    ) = ShaderSubmission.emit(
        spec = spec(effectId, programId, blendMode),
        center = center,
        facing = 0f,
        uniforms = ShaderUniformSet(ShaderUniformSchema(emptyList()), emptyMap()),
        renderOrder = renderOrder,
        submittedAt = 0f,
    )

    private fun spec(effectId: String, programId: String, blendMode: ShaderBlendMode) = ShaderEffectSpec(
        id = ShaderEffectKey(effectId),
        program = ShaderProgramSpec(
            id = programId,
            vertexSource = "void main() { gl_Position = vec4(0.0); }",
            fragmentSource = "void main() { }",
        ),
        geometry = ShaderGeometrySpec.WorldQuad(halfExtentWorld = 120f),
        material = ShaderMaterialSpec(blendMode),
        uniformSchema = ShaderUniformSchema(emptyList()),
        layer = ShaderEffectLayer.AboveParticles,
        lifetimeSeconds = 1f,
        staleAfterSeconds = 0.25f,
        renderRadius = 240f,
    )
}
