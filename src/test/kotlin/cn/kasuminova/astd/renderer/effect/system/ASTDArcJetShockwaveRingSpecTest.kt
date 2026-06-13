package cn.kasuminova.astd.renderer.effect.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDArcJetShockwaveRingSpecTest {

    @Test
    fun `outer radius follows arc jet reference image scale`() {
        val frame = ASTDArcJetShockwaveRingSpec.frame(
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
        val params = ASTDArcJetShockwaveRingSpec.REFERENCE_PARAMETERS

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
        val inactive = ASTDArcJetShockwaveRingSpec.frame(
            collisionRadius = 270f,
            effectLevel = 0f,
            pressureRatio = 1f,
        )
        val calm = ASTDArcJetShockwaveRingSpec.frame(
            collisionRadius = 270f,
            effectLevel = 1f,
            pressureRatio = 0f,
        )
        val pressured = ASTDArcJetShockwaveRingSpec.frame(
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
    fun `stale frame timeout retires stopped system submissions`() {
        assertFalse(ASTDArcJetShockwaveRingSpec.shouldRetire(0.05f))
        assertTrue(ASTDArcJetShockwaveRingSpec.shouldRetire(ASTDArcJetShockwaveRingSpec.STALE_AFTER_SECONDS + 0.001f))
    }
}
