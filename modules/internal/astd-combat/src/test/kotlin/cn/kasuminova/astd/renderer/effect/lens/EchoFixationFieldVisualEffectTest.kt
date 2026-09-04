package cn.kasuminova.astd.renderer.effect.lens

import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import cn.kasuminova.astd.renderer.shader.runtime.ShaderRuntimeHost
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EchoFixationFieldVisualEffectTest {

    @Test
    fun `effect spec has stable id and required uniforms`() {
        val spec = EchoFixationFieldVisualEffect.effectSpec

        assertEquals("astd_echo_fixation_field", spec.id.value)

        val keys = spec.uniformSchema.definitions.map { it.key }.toSet()
        assertTrue("progress" in keys, "uniform schema must declare 'progress'")
    }

    @Test
    fun `render radius covers field radius`() {
        assertTrue(
            EchoFixationFieldVisualEffect.effectSpec.renderRadius >= 700f,
            "renderRadius must cover the 700su field radius (with feather margin)",
        )
    }

    @Test
    fun `effect spec uses below particles world quad additive contract`() {
        val spec = EchoFixationFieldVisualEffect.effectSpec

        assertEquals(ShaderEffectLayer.BelowParticles, spec.layer)
        assertEquals(ShaderBlendMode.Additive, spec.material.blendMode)
        assertTrue(spec.geometry is ShaderGeometrySpec.WorldQuad)
    }

    @Test
    fun `frame derives quad half extent from field radius and feathers it`() {
        val frame = EchoFixationFieldVisualEffect.frame(
            fieldRadius = 700f,
            progress = 0.5f,
        )

        assertEquals(700f, frame.fieldRadiusWorld, 0.001f)
        // Quad must fully cover the ring plus feather margin.
        assertTrue(frame.quadHalfExtentWorld >= 700f)
        assertEquals(frame.quadHalfExtentWorld, frame.outerRadiusWorld, 0.001f)
    }

    @Test
    fun `frame progress drives a non-zero pulsing alpha and clamps range`() {
        val mid = EchoFixationFieldVisualEffect.frame(fieldRadius = 700f, progress = 0.5f)
        val belowZero = EchoFixationFieldVisualEffect.frame(fieldRadius = 700f, progress = -1f)
        val aboveOne = EchoFixationFieldVisualEffect.frame(fieldRadius = 700f, progress = 2f)

        assertTrue(mid.alphaMult > 0f)
        assertEquals(0f, belowZero.progress, 0.0001f)
        assertEquals(1f, aboveOne.progress, 0.0001f)
    }

    @Test
    fun `frame uses violet primary hue`() {
        val frame = EchoFixationFieldVisualEffect.frame(fieldRadius = 700f, progress = 0.5f)
        // Violet primary per color directive: hue ~= 0.76, saturation in [0.6, 0.75].
        assertEquals(0.76f, frame.hue, 0.001f)
        assertTrue(frame.saturation in 0.6f..0.75f)
    }

    @Test
    fun `submit frame queues keyed world quad shader effect for the field`() {
        val runtime = CombatShaderRuntime.ensure(FakeHost())
        val frame = EchoFixationFieldVisualEffect.frame(fieldRadius = 700f, progress = 0.5f)

        val handle = EchoFixationFieldVisualEffect.submitFrame(
            sink = runtime.sink,
            instanceId = "echo-field-7",
            center = Vector2f(12f, 34f),
            frame = frame,
        )

        val snapshot = runtime.snapshotsForTests(ShaderEffectLayer.BelowParticles).single()
        assertEquals(handle, snapshot.handle)
        assertEquals("echo-field-7", snapshot.handle?.instanceId)
        assertSame(EchoFixationFieldVisualEffect.effectSpec.program, snapshot.spec.program)
        val geometry = snapshot.spec.geometry as ShaderGeometrySpec.WorldQuad
        assertEquals(frame.quadHalfExtentWorld, geometry.halfExtentWorld, 0.001f)
        assertEquals(12f, snapshot.center.x, 0.001f)
        assertEquals(34f, snapshot.center.y, 0.001f)
    }

    private class FakeHost : ShaderRuntimeHost {
        override val customData: MutableMap<String, Any?> = HashMap()
        override val isPaused: Boolean = false

        override fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin) = Unit
    }
}
