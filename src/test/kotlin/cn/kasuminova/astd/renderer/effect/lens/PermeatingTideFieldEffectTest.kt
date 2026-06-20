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

/**
 * [PermeatingTideFieldEffect] shader effect spec / frame 测试（渗透潮汐场，spec §5）。
 *
 * 覆盖：稳定 id、tideLevel（涨落）uniform、紫罗兰主色（非青）、renderRadius 覆盖 2500su 场、
 * BelowParticles/WorldQuad/Additive 契约、frame 纯函数（涨落驱动 alpha + clamp）、keyed upsert。
 */
class PermeatingTideFieldEffectTest {

    @Test
    fun `effect spec has stable id and declares the tide level uniform`() {
        val spec = PermeatingTideFieldEffect.effectSpec
        assertEquals("astd_permeating_tide_field", spec.id.value)

        val keys = spec.uniformSchema.definitions.map { it.key }.toSet()
        assertTrue("tideLevel" in keys, "uniform schema must declare 'tideLevel' (涨落驱动)")
    }

    @Test
    fun `render radius covers the 2500su tide field`() {
        assertTrue(
            PermeatingTideFieldEffect.effectSpec.renderRadius >= 2500f,
            "renderRadius must cover the ~2500su tide field",
        )
    }

    @Test
    fun `effect spec uses below particles world quad contract`() {
        val spec = PermeatingTideFieldEffect.effectSpec
        assertEquals(ShaderEffectLayer.BelowParticles, spec.layer)
        assertEquals(ShaderBlendMode.Additive, spec.material.blendMode)
        assertTrue(spec.geometry is ShaderGeometrySpec.WorldQuad)
    }

    @Test
    fun `frame uses violet primary hue (never cyan)`() {
        val frame = PermeatingTideFieldEffect.frame(tideLevel = 0.5f)
        // 色彩指令：主色紫罗兰 hue ≈ 0.76，saturation ≈ 0.55~0.7。绝不青色 hue 0.4~0.6。
        assertEquals(0.76f, frame.hue, 0.001f)
        assertTrue(frame.saturation in 0.55f..0.7f)
        assertTrue(frame.hue !in 0.4f..0.6f, "hue must not be cyan")
    }

    @Test
    fun `frame tide level drives non-zero alpha and clamps range`() {
        val high = PermeatingTideFieldEffect.frame(tideLevel = 1f)
        val belowZero = PermeatingTideFieldEffect.frame(tideLevel = -1f)
        val aboveOne = PermeatingTideFieldEffect.frame(tideLevel = 2f)

        assertTrue(high.alphaMult > 0f)
        assertEquals(0f, belowZero.tideLevel, 0.0001f)
        assertEquals(1f, aboveOne.tideLevel, 0.0001f)
    }

    @Test
    fun `frame quad half extent covers the field radius`() {
        val frame = PermeatingTideFieldEffect.frame(tideLevel = 0.8f)
        assertTrue(frame.quadHalfExtentWorld >= 2500f)
        assertEquals(frame.quadHalfExtentWorld, frame.outerRadiusWorld, 0.001f)
    }

    @Test
    fun `submit frame queues keyed world quad shader effect for the tide field`() {
        val runtime = CombatShaderRuntime.ensure(FakeHost())
        val frame = PermeatingTideFieldEffect.frame(tideLevel = 0.7f)

        val handle = PermeatingTideFieldEffect.submitFrame(
            sink = runtime.sink,
            instanceId = "tide-42",
            center = Vector2f(7f, 8f),
            frame = frame,
        )

        val snapshot = runtime.snapshotsForTests(ShaderEffectLayer.BelowParticles).single()
        assertEquals(handle, snapshot.handle)
        assertEquals("tide-42", snapshot.handle?.instanceId)
        assertSame(PermeatingTideFieldEffect.effectSpec.program, snapshot.spec.program)
        val geometry = snapshot.spec.geometry as ShaderGeometrySpec.WorldQuad
        assertEquals(frame.quadHalfExtentWorld, geometry.halfExtentWorld, 0.001f)
        assertEquals(7f, snapshot.center.x, 0.001f)
        assertEquals(8f, snapshot.center.y, 0.001f)
    }

    private class FakeHost : ShaderRuntimeHost {
        override val customData: MutableMap<String, Any?> = HashMap()
        override val isPaused: Boolean = false

        override fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin) = Unit
    }
}
