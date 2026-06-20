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
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Spec for the drift / deep-water mark highlight shader effects (LENS theme).
 *
 * 动机（Task 8 / 颜色指令）：被误差（drift）/深水（deep water）标记的敌舰需在周身显示
 * 高光环，强度按标记层数。误差用 LENS 主色紫罗兰，深水用红色辅色——两类必须可区分，
 * 且深水绝不使用青色 hue。本测试锁定颜色、schema、纯函数 frame、几何/材质/层、keyed upsert。
 */
class MarkVisualEffectTest {

    @Test
    fun `drift effect id matches catalog key`() {
        assertEquals("astd_drift_mark_highlight", DriftMarkVisualEffect.effectSpec.id.value)
    }

    @Test
    fun `deep water effect id matches catalog key`() {
        assertEquals("astd_deep_water_mark_highlight", DeepWaterMarkVisualEffect.effectSpec.id.value)
    }

    @Test
    fun `both schemas declare markLevel uniform`() {
        assertTrue(DriftMarkVisualEffect.effectSpec.uniformSchema.definitions.map { it.key }.contains("markLevel"))
        assertTrue(DeepWaterMarkVisualEffect.effectSpec.uniformSchema.definitions.map { it.key }.contains("markLevel"))
    }

    @Test
    fun `drift highlight uses violet primary hue`() {
        // LENS 主色紫罗兰：hue ≈ 0.76（约 275°）。
        assertEquals(0.76f, DriftMarkVisualEffect.PRIMARY_HUE, 0.02f)
        assertTrue(DriftMarkVisualEffect.PRIMARY_SATURATION in 0.65f..0.72f)
    }

    @Test
    fun `deep water highlight uses red accent hue and never cyan`() {
        // 深水红色辅色：hue ≈ 0.0（红）。绝不落在青色 hue 0.4~0.6 区间。
        assertEquals(0.0f, DeepWaterMarkVisualEffect.PRIMARY_HUE, 0.02f)
        assertTrue(DeepWaterMarkVisualEffect.PRIMARY_SATURATION in 0.7f..0.85f)
        assertFalse(DeepWaterMarkVisualEffect.PRIMARY_HUE in 0.4f..0.6f)
    }

    @Test
    fun `drift and deep water hues differ for clear contrast`() {
        assertNotEquals(DriftMarkVisualEffect.PRIMARY_HUE, DeepWaterMarkVisualEffect.PRIMARY_HUE)
    }

    @Test
    fun `frame normalizes mark level by max stacks`() {
        val frame = DriftMarkVisualEffect.frame(collisionRadius = 120f, markStacks = 5, maxStacks = 10)
        assertEquals(0.5f, frame.markLevel, 0.0001f)
    }

    @Test
    fun `frame mark level clamps stacks into unit range`() {
        val over = DriftMarkVisualEffect.frame(collisionRadius = 120f, markStacks = 99, maxStacks = 10)
        val under = DriftMarkVisualEffect.frame(collisionRadius = 120f, markStacks = -3, maxStacks = 10)
        assertEquals(1f, over.markLevel, 0.0001f)
        assertEquals(0f, under.markLevel, 0.0001f)
    }

    @Test
    fun `frame alpha grows with mark stacks`() {
        val low = DriftMarkVisualEffect.frame(collisionRadius = 120f, markStacks = 1, maxStacks = 10)
        val high = DriftMarkVisualEffect.frame(collisionRadius = 120f, markStacks = 10, maxStacks = 10)
        assertTrue(high.alphaMult > low.alphaMult)
        assertTrue(low.alphaMult > 0f)
    }

    @Test
    fun `frame quad half extent scales with collision radius`() {
        val small = DriftMarkVisualEffect.frame(collisionRadius = 80f, markStacks = 5, maxStacks = 10)
        val big = DriftMarkVisualEffect.frame(collisionRadius = 320f, markStacks = 5, maxStacks = 10)
        assertTrue(big.quadHalfExtentWorld > small.quadHalfExtentWorld)
        assertEquals(big.quadHalfExtentWorld, big.outerRadiusWorld, 0.0001f)
    }

    @Test
    fun `deep water frame carries red defaults`() {
        val frame = DeepWaterMarkVisualEffect.frame(collisionRadius = 120f, markStacks = 4, maxStacks = 10)
        assertEquals(DeepWaterMarkVisualEffect.PRIMARY_HUE, frame.hue, 0.0001f)
        assertEquals(DeepWaterMarkVisualEffect.PRIMARY_SATURATION, frame.saturation, 0.0001f)
        assertEquals(0.4f, frame.markLevel, 0.0001f)
    }

    @Test
    fun `specs use additive world quad above ships`() {
        for (spec in listOf(DriftMarkVisualEffect.effectSpec, DeepWaterMarkVisualEffect.effectSpec)) {
            assertTrue(spec.geometry is ShaderGeometrySpec.WorldQuad)
            assertEquals(ShaderBlendMode.Additive, spec.material.blendMode)
            assertEquals(ShaderEffectLayer.AboveShips, spec.layer)
        }
    }

    @Test
    fun `program ids are unique across the two effects`() {
        assertNotEquals(
            DriftMarkVisualEffect.effectSpec.program.id,
            DeepWaterMarkVisualEffect.effectSpec.program.id,
        )
    }

    @Test
    fun `submit frame queues keyed world quad above ships`() {
        val runtime = CombatShaderRuntime.ensure(FakeHost())
        val frame = DriftMarkVisualEffect.frame(collisionRadius = 120f, markStacks = 6, maxStacks = 10)

        val handle = DriftMarkVisualEffect.submitFrame(
            sink = runtime.sink,
            instanceId = "drift-1",
            center = Vector2f(7f, 9f),
            frame = frame,
        )

        val snapshot = runtime.snapshotsForTests(ShaderEffectLayer.AboveShips).single()
        assertEquals(handle, snapshot.handle)
        assertEquals("drift-1", snapshot.handle?.instanceId)
        assertSame(DriftMarkVisualEffect.effectSpec.program, snapshot.spec.program)
        val geometry = snapshot.spec.geometry as ShaderGeometrySpec.WorldQuad
        assertEquals(frame.quadHalfExtentWorld, geometry.halfExtentWorld, 0.001f)
        assertEquals(7f, snapshot.center.x, 0.001f)
        assertEquals(9f, snapshot.center.y, 0.001f)
    }

    @Test
    fun `deep water submit frame queues keyed world quad above ships`() {
        val runtime = CombatShaderRuntime.ensure(FakeHost())
        val frame = DeepWaterMarkVisualEffect.frame(collisionRadius = 140f, markStacks = 3, maxStacks = 10)

        val handle = DeepWaterMarkVisualEffect.submitFrame(
            sink = runtime.sink,
            instanceId = "deepwater-1",
            center = Vector2f(21f, 5f),
            frame = frame,
        )

        val snapshot = runtime.snapshotsForTests(ShaderEffectLayer.AboveShips).single()
        assertEquals(handle, snapshot.handle)
        assertEquals("deepwater-1", snapshot.handle?.instanceId)
        assertSame(DeepWaterMarkVisualEffect.effectSpec.program, snapshot.spec.program)
        val geometry = snapshot.spec.geometry as ShaderGeometrySpec.WorldQuad
        assertEquals(frame.quadHalfExtentWorld, geometry.halfExtentWorld, 0.001f)
        assertEquals(21f, snapshot.center.x, 0.001f)
        assertEquals(5f, snapshot.center.y, 0.001f)
    }

    @Test
    fun `stale frame timeout retires stopped submissions`() {
        assertFalse(DriftMarkVisualEffect.shouldRetire(0.02f))
        assertTrue(DriftMarkVisualEffect.shouldRetire(DriftMarkVisualEffect.STALE_AFTER_SECONDS + 0.001f))
    }

    private class FakeHost : ShaderRuntimeHost {
        override val customData: MutableMap<String, Any?> = HashMap()
        override val isPaused: Boolean = false

        override fun addLayeredRenderingPlugin(plugin: BaseCombatLayeredRenderingPlugin) = Unit
    }
}
