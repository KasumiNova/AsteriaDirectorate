package cn.kasuminova.astd.renderer.projectile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDProjectileVfxRuntimeTest {
    @Test
    fun `runtime state machine transitions from active to fading to removed`() {
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())

        assertEquals(ASTDProjectileVfxRuntimeState.Active, runtime.state)

        runtime.advanceForTests(locationX = 0f, locationY = 0f, facing = 0f, amount = 0.1f, projectileAlive = true)
        assertEquals(ASTDProjectileVfxRuntimeState.Active, runtime.state)
        assertTrue(runtime.historyNodesForTests().isNotEmpty())

        runtime.advanceForTests(locationX = 2f, locationY = 0f, facing = 0f, amount = 0.1f, projectileAlive = false)
        assertEquals(ASTDProjectileVfxRuntimeState.Fading, runtime.state)

        runtime.advanceForTests(locationX = 4f, locationY = 0f, facing = 0f, amount = 1f, projectileAlive = false)
        assertEquals(ASTDProjectileVfxRuntimeState.Removed, runtime.state)
    }

    @Test
    fun `removed runtime no longer samples history`() {
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())
        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.markProjectileGone()
        runtime.advanceForTests(1f, 0f, 0f, 1f, projectileAlive = false)
        val count = runtime.historyNodesForTests().size

        runtime.advanceForTests(20f, 0f, 0f, 1f, projectileAlive = true)

        assertEquals(count, runtime.historyNodesForTests().size)
    }

    @Test
    fun `runtime preserves non linear projectile history`() {
        val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())
        runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.advanceForTests(10f, 0f, 0f, 0.1f, projectileAlive = true)
        runtime.advanceForTests(10f, 10f, 90f, 0.1f, projectileAlive = true)

        val nodes = runtime.historyNodesForTests()
        assertEquals(3, nodes.size)
        assertEquals(10f, nodes[1].location.x)
        assertEquals(0f, nodes[1].location.y)
        assertEquals(10f, nodes[2].location.x)
        assertEquals(10f, nodes[2].location.y)
    }

    private fun testPreset() = ASTDProjectileVfxPreset(
        id = "test_runtime",
        layers = listOf(ASTDProjectileVfxLayer.Trail("trail", 8f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 1f))),
        samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 0, 160f),
        fadePolicy = ASTDProjectileVfxFadePolicy(0f, 0.2f, 0.1f, 0.2f),
    )
}
