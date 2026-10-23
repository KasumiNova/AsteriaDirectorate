package cn.kasuminova.astd.renderer.beam.driver

import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.render.BeamHostImpl
import cn.kasuminova.astd.impl.render.renderEntity
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BeamVfxDriverTest {
    private fun newDriver(): BeamVfxDriverImpl {
        return BeamVfxDriverImpl(BeamHostImpl("beam@test", baseWidth = 20f), renderEntity("beam@test") {})
    }

    @Test
    fun `BeamFrame 折成 FrameState 逐字段`() {
        val driver = newDriver()
        driver.advanceForTests(
            BeamFrame(start = Vector2f(100f, 50f), facing = 30f, length = 400f, endpoint = Vector2f(500f, 50f), firing = true, strength = 0.7f),
            0.016f,
        )
        val fs = assertNotNull(driver.lastFrameForTests())
        assertEquals(100f, fs.origin.x)
        assertEquals(50f, fs.origin.y)
        assertEquals(30f, fs.facing)
        assertEquals(400f, fs.length)
        assertEquals(500f, assertNotNull(fs.endpoint).x)
        assertTrue(fs.active)
        assertEquals(0.7f, fs.intensity, 1e-4f)
        assertEquals(RenderPhase.Active, fs.phase)
        assertEquals(1f, fs.worldUnitsPerPixel)
    }

    @Test
    fun `停火 active=false 相位 FadingOut 强度截断到 1`() {
        val driver = newDriver()
        driver.advanceForTests(
            BeamFrame(start = Vector2f(0f, 0f), facing = 0f, length = 100f, endpoint = null, firing = false, strength = 1.5f),
            0.016f,
        )
        val fs = assertNotNull(driver.lastFrameForTests())
        assertFalse(fs.active)
        assertEquals(1f, fs.intensity, 1e-4f)
        assertEquals(RenderPhase.FadingOut, fs.phase)
        assertNull(fs.endpoint)
    }

    @Test
    fun `复火拉回 驱动保持 Active 且无需重建`() {
        val driver = newDriver()
        val frame = { firing: Boolean -> BeamFrame(Vector2f(0f, 0f), 0f, 100f, null, firing, 0.5f) }
        driver.advanceForTests(frame(true), 0.1f)
        driver.advanceForTests(frame(false), 0.1f)
        assertEquals(BeamVfxDriverState.Active, driver.state)
        driver.advanceForTests(frame(true), 0.1f)
        assertEquals(BeamVfxDriverState.Active, driver.state)
        assertTrue(assertNotNull(driver.lastFrameForTests()).active)
    }

    @Test
    fun `dispose 置 Removed 后不再推进`() {
        val driver = newDriver()
        driver.advanceForTests(BeamFrame(Vector2f(0f, 0f), 0f, 100f, null, true, 0.5f), 0.1f)
        val before = driver.lastFrameForTests()
        driver.dispose()
        assertEquals(BeamVfxDriverState.Removed, driver.state)
        driver.advanceForTests(BeamFrame(Vector2f(9f, 9f), 0f, 100f, null, true, 0.5f), 0.1f)
        assertSame(before, driver.lastFrameForTests())
    }
}
