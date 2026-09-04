package cn.kasuminova.astd.renderer.effect.system

import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import cn.kasuminova.astd.renderer.shader.runtime.ShaderRuntimeHost
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArcJetShockwaveRingEffectTest {

    @Test
    fun `outer radius follows arc jet reference image scale`() {
        val frame = ArcJetShockwaveRingEffect.frame(
            collisionRadius = 270f,
            effectLevel = 1f,
            pressureRatio = 0f,
        )

        assertEquals(405f, frame.outerRadiusWorld, 0.001f)
        assertEquals(frame.outerRadiusWorld, frame.quadHalfExtentWorld, 0.001f)
        assertEquals(1.3f, frame.shaderDomainRadius, 0.001f)
    }

    @Test
    fun `reference parameters match selected shockwave ring preset`() {
        val params = ArcJetShockwaveRingEffect.REFERENCE_PARAMETERS

        assertEquals(0.30f, params.speed, 0.0001f)
        assertEquals(0.01f, params.thickness, 0.0001f)
        assertEquals(3f, params.ringCount, 0.0001f)
        assertEquals(0.50f, params.distortion, 0.0001f)
        assertEquals(1.25f, params.glow, 0.0001f)
        assertEquals(0.52f, params.hue, 0.0001f)
        assertEquals(0.60f, params.saturation, 0.0001f)
        assertEquals(1.25f, params.exposure, 0.0001f)
    }

    @Test
    fun `frame clamps level and pressure while pressure raises visibility`() {
        val inactive = ArcJetShockwaveRingEffect.frame(
            collisionRadius = 270f,
            effectLevel = 0f,
            pressureRatio = 1f,
        )
        val calm = ArcJetShockwaveRingEffect.frame(
            collisionRadius = 270f,
            effectLevel = 1f,
            pressureRatio = 0f,
        )
        val pressured = ArcJetShockwaveRingEffect.frame(
            collisionRadius = 270f,
            effectLevel = 1f,
            pressureRatio = 1f,
        )

        assertEquals(0f, inactive.alphaMult, 0.0001f)
        assertTrue(calm.alphaMult > 0f)
        assertTrue(pressured.alphaMult > calm.alphaMult)
        assertTrue(pressured.exposure > calm.exposure)
    }

    @Test
    fun `effect spec uses shader runtime world quad contract`() {
        val spec = ArcJetShockwaveRingEffect.effectSpec

        assertEquals("astd_arc_jet_shockwave_ring", spec.id.value)
        assertEquals(ShaderEffectLayer.BelowParticles, spec.layer)
        assertEquals(ShaderBlendMode.Additive, spec.material.blendMode)
        assertTrue(spec.geometry is ShaderGeometrySpec.WorldQuad)
        assertEquals(ArcJetShockwaveRingEffect.STALE_AFTER_SECONDS, spec.staleAfterSeconds)
    }

    @Test
    fun `submit frame queues keyed world quad shader effect`() {
        val runtime = CombatShaderRuntime.ensure(FakeHost())
        val frame = ArcJetShockwaveRingEffect.frame(
            collisionRadius = 270f,
            effectLevel = 1f,
            pressureRatio = 0.5f,
        )

        val handle = ArcJetShockwaveRingEffect.submitFrame(
            sink = runtime.sink,
            instanceId = "ship-1",
            center = Vector2f(12f, 34f),
            frame = frame,
        )

        val snapshot = runtime.snapshotsForTests(ShaderEffectLayer.BelowParticles).single()
        assertEquals(handle, snapshot.handle)
        assertEquals("ship-1", snapshot.handle?.instanceId)
        assertSame(ArcJetShockwaveRingEffect.effectSpec.program, snapshot.spec.program)
        val geometry = snapshot.spec.geometry as ShaderGeometrySpec.WorldQuad
        assertEquals(frame.quadHalfExtentWorld, geometry.halfExtentWorld, 0.001f)
        assertEquals(12f, snapshot.center.x, 0.001f)
        assertEquals(34f, snapshot.center.y, 0.001f)
    }

    @Test
    fun `stale frame timeout retires stopped system submissions`() {
        assertFalse(ArcJetShockwaveRingEffect.shouldRetire(0.05f))
        assertTrue(ArcJetShockwaveRingEffect.shouldRetire(ArcJetShockwaveRingEffect.STALE_AFTER_SECONDS + 0.001f))
    }

    private class FakeHost : ShaderRuntimeHost {
        override val customData: MutableMap<String, Any?> = HashMap()
        override val isPaused: Boolean = false

        override fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin) = Unit
    }
}
