package cn.kasuminova.astd.impl.render

import kotlin.test.Test
import kotlin.test.assertEquals

class ASTDProjectileVfxMathTest {
    @Test
    fun `matches scalar helper vectors`() {
        assertEquals(0.5f, ASTDProjectileVfxMath.smoothstep(0.2f, 0.8f, 0.5f), 0.0001f)
        assertEquals(0.4353125f, ASTDProjectileVfxMath.hermite01(0.35f, 1.2f, 0.3f), 0.0001f)
        assertEquals(0.19f, ASTDProjectileVfxMath.beamAlpha(0.5f), 0.0001f)
        assertEquals(7f, ASTDProjectileVfxMath.lerp(4f, 10f, 0.5f), 0.0001f)
        assertEquals(2f, ASTDProjectileVfxMath.clamp(2f, 0f, 5f), 0.0001f)
        assertEquals(5f, ASTDProjectileVfxMath.clamp(8f, 0f, 5f), 0.0001f)
    }
}
