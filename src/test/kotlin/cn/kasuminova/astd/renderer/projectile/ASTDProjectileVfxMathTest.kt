package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.runtime.ASTDProjectileVfxMath
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxMathTest {
    @Test
    fun `matches scalar helper vectors`() {
        assertEquals(0.5f, ASTDProjectileVfxMath.smoothstep(0.2f, 0.8f, 0.5f), 0.0001f)
        assertEquals(0.4353125f, ASTDProjectileVfxMath.hermite01(0.35f, 1.2f, 0.3f), 0.0001f)
        assertEquals(0.7555694f, ASTDProjectileVfxMath.shaderNoise(1.25f, 2.5f), 0.0001f)
        assertEquals(0.4510680f, ASTDProjectileVfxMath.layeredNoise(0.42f, 3.7f), 0.0001f)
    }

    @Test
    fun `matches sampling and lifecycle vectors`() {
        val sample = ASTDProjectileVfxMath.sampleHistoryAt(listOf(Vector2f(0f, 0f), Vector2f(10f, 0f), Vector2f(20f, 10f)), 7.5f, 5f)
        assertEquals(15f, sample.x, 0.0001f)
        assertEquals(5f, sample.y, 0.0001f)
        assertEquals(0.5f, ASTDProjectileVfxMath.dissolve(1.0f, 1.25f, 0.6f), 0.0001f)
        assertEquals(0.19f, ASTDProjectileVfxMath.beamAlpha(0.5f), 0.0001f)
        assertEquals(226.8f, ASTDProjectileVfxMath.visibleLength(420f, 0.5f), 0.0001f)
    }

    @Test
    fun `matches ribbon wave vectors`() {
        assertEquals(-0.4573935f, ASTDProjectileVfxMath.ribbonWave("sine", 120f, 0.42f, 1.1f, 1f, 1.35f, 4f, 17, 0.48f), 0.0001f)
        assertEquals(-0.1519367f, ASTDProjectileVfxMath.ribbonWave("noise", 120f, 0.42f, 1.1f, 1f, 1.35f, 4f, 17, 0.48f), 0.0001f)
        assertEquals(-0.6449725f, ASTDProjectileVfxMath.ribbonWave("zigzag", 120f, 0.42f, 1.1f, 1f, 1.35f, 4f, 17, 0.48f), 0.0001f)
    }
}
