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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Task 9 幽灵信号波 shader effect 规格测试。
 *
 * 断言项（与任务验收一一对应）：
 * - effect id 稳定为 "astd_ghost_signal_wave"。
 * - uniform schema 含 progress（CPU 驱动的波扩张进度，render 端不依赖 u_time 也能扩张）。
 * - 颜色为 LENS 紫（hue ≈ 0.76），明确不落在青色区间 [0.4,0.6]。
 * - renderRadius 为「每枚导弹小脉冲」尺寸（Task 9 改造后：波在导弹位置起一道小扰动脉冲，
 *   不再以本舰为心起一道 ~2000su 大波），故 renderRadius 应为小值（<= 300f 且 > 0）。
 * - geometry WorldQuad、material Additive、layer AboveParticles（电子战脉冲在粒子层上方）。
 * - frame() 为纯函数：progress 单调驱动波半径与 alpha 包络；波前半径受 PULSE_RANGE 约束（小脉冲）。
 * - submitFrame() keyed upsert 落到 AboveParticles snapshot。
 */
class GhostSignalWaveEffectTest {

    @Test
    fun `effect spec uses ghost signal world quad contract`() {
        val spec = GhostSignalWaveEffect.effectSpec

        assertEquals("astd_ghost_signal_wave", spec.id.value)
        assertEquals(ShaderEffectLayer.AboveParticles, spec.layer)
        assertEquals(ShaderBlendMode.Additive, spec.material.blendMode)
        assertTrue(spec.geometry is ShaderGeometrySpec.WorldQuad)
    }

    @Test
    fun `uniform schema exposes progress driver`() {
        val keys = GhostSignalWaveEffect.effectSpec.uniformSchema.definitions.map { it.key }
        assertTrue("progress" in keys, "ghost signal wave must expose a progress uniform: $keys")
    }

    @Test
    fun `primary color is lens violet and never cyan`() {
        val hue = GhostSignalWaveEffect.PRIMARY_HUE
        assertEquals(0.76f, hue, 0.001f)
        assertTrue(hue < 0.4f || hue > 0.6f, "ghost signal hue must not sit in the cyan band [0.4,0.6]: $hue")
    }

    @Test
    fun `render radius is a small per-missile pulse`() {
        val r = GhostSignalWaveEffect.effectSpec.renderRadius
        // Task 9：每枚导弹小脉冲（~120su + 羽化），renderRadius 必须为小值（远小于旧 2000su 大波）。
        assertTrue(r > 0f, "renderRadius must be positive: $r")
        assertTrue(r <= 300f, "renderRadius must be a small per-missile pulse (<= 300su), not a 2000su ship wave: $r")
    }

    @Test
    fun `frame is pure and progress drives radius and alpha envelope`() {
        val range = GhostSignalWaveEffect.PULSE_RANGE
        val early = GhostSignalWaveEffect.frame(range = range, progress = 0.1f)
        val mid = GhostSignalWaveEffect.frame(range = range, progress = 0.5f)
        val late = GhostSignalWaveEffect.frame(range = range, progress = 0.95f)

        // 纯函数：相同输入相同输出。
        assertEquals(mid, GhostSignalWaveEffect.frame(range = range, progress = 0.5f))

        // 波前半径随 progress 单调外扩。
        assertTrue(early.waveRadiusWorld < mid.waveRadiusWorld)
        assertTrue(mid.waveRadiusWorld < late.waveRadiusWorld)

        // 小脉冲：波前半径全程受 PULSE_RANGE 约束（锁定 Task 9 缩小后的尺寸）。
        assertTrue(
            late.waveRadiusWorld <= GhostSignalWaveEffect.PULSE_RANGE,
            "wave radius must stay bounded by the small PULSE_RANGE: ${late.waveRadiusWorld}",
        )

        // alpha 包络：起手淡入、临近完成淡出（late < mid），且均 > 0。
        assertTrue(early.alphaMult > 0f)
        assertTrue(mid.alphaMult > 0f)
        assertTrue(late.alphaMult < mid.alphaMult)
    }

    @Test
    fun `submit frame queues keyed wave on above particles layer`() {
        val runtime = CombatShaderRuntime.ensure(FakeHost())
        val frame = GhostSignalWaveEffect.frame(range = GhostSignalWaveEffect.PULSE_RANGE, progress = 0.4f)

        val handle = GhostSignalWaveEffect.submitFrame(
            sink = runtime.sink,
            instanceId = "ghost-pulse-7",
            center = Vector2f(50f, -25f),
            frame = frame,
        )
        assertNotNull(handle)

        val snapshot = runtime.snapshotsForTests(ShaderEffectLayer.AboveParticles).single()
        assertEquals(handle, snapshot.handle)
        assertEquals("ghost-pulse-7", snapshot.handle?.instanceId)
        assertSame(GhostSignalWaveEffect.effectSpec.program, snapshot.spec.program)
        assertEquals(50f, snapshot.center.x, 0.001f)
        assertEquals(-25f, snapshot.center.y, 0.001f)

        val progress = snapshot.uniforms.values["progress"]
        assertNotNull(progress)
    }

    @Test
    fun `stale frame timeout retires stopped waves`() {
        assertFalse(GhostSignalWaveEffect.shouldRetire(0.05f))
        assertTrue(GhostSignalWaveEffect.shouldRetire(GhostSignalWaveEffect.STALE_AFTER_SECONDS + 0.001f))
    }

    private class FakeHost : ShaderRuntimeHost {
        override val customData: MutableMap<String, Any?> = HashMap()
        override val isPaused: Boolean = false

        override fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin) = Unit
    }
}
