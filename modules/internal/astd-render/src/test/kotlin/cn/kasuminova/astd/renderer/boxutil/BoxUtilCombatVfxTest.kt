package cn.kasuminova.astd.renderer.boxutil

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// BoxUtil 对负角度会错误计算半角正弦，输入实体变换前必须归一化到 [0, 360)。
class BoxUtilCombatVfxTest {
    @Test
    fun `normalizeFacingDeg maps any input into 0 to 360`() {
        assertEquals(270f, BoxUtilCombatVfx.normalizeFacingDeg(-90f), 1e-4f)
        assertEquals(180f, BoxUtilCombatVfx.normalizeFacingDeg(-180f), 1e-4f)
        assertEquals(359.5f, BoxUtilCombatVfx.normalizeFacingDeg(-0.5f), 1e-4f)
        assertEquals(90f, BoxUtilCombatVfx.normalizeFacingDeg(450f), 1e-4f)
        assertEquals(0f, BoxUtilCombatVfx.normalizeFacingDeg(720f), 1e-4f)
        assertEquals(0f, BoxUtilCombatVfx.normalizeFacingDeg(0f), 1e-4f)
        assertEquals(0f, BoxUtilCombatVfx.normalizeFacingDeg(360f), 1e-4f)
        assertEquals(270f, BoxUtilCombatVfx.normalizeFacingDeg(270f), 1e-4f)
    }

    @Test
    fun `normalizeFacingDeg preserves world direction exactly`() {
        var deg = -720f
        while (deg <= 720f) {
            val normalized = BoxUtilCombatVfx.normalizeFacingDeg(deg)
            assertTrue(normalized >= 0f && normalized < 360f, "归一化结果必须在 [0,360)：$normalized（输入 $deg）")
            val rad = Math.toRadians(deg.toDouble())
            val normRad = Math.toRadians(normalized.toDouble())
            assertEquals(cos(rad).toFloat(), cos(normRad).toFloat(), 1e-5f, "方向 x 分量必须不变（输入 $deg）")
            assertEquals(sin(rad).toFloat(), sin(normRad).toFloat(), 1e-5f, "方向 y 分量必须不变（输入 $deg）")
            deg += 7.5f
        }
    }
}
