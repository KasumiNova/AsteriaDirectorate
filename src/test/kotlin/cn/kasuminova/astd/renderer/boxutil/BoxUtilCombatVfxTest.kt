package cn.kasuminova.astd.renderer.boxutil

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BoxUtilCombatVfx.normalizeFacingDeg] 测试（BoxUtil 负角镜像 BUG 的边界防线）：
 * BoxUtil `TrigUtil.sinFormCosF` 对负角度会取到错误符号的 sin(半角)，等价于绕 x 轴镜像——
 * 所有进入 BoxUtil 实体变换的朝向必须归一化到 [0, 360)。
 */
class BoxUtilCombatVfxTest {

    @Test
    fun `normalizeFacingDeg maps any input into 0 to 360`() {
        // atan2 直出的负角（本次锥形朝下反转的实锤输入）。
        assertEquals(270f, BoxUtilCombatVfx.normalizeFacingDeg(-90f), 1e-4f)
        assertEquals(180f, BoxUtilCombatVfx.normalizeFacingDeg(-180f), 1e-4f)
        assertEquals(359.5f, BoxUtilCombatVfx.normalizeFacingDeg(-0.5f), 1e-4f)
        // 超圈正角（facing + 180 尾随曳光等路径可达 540°）。
        assertEquals(90f, BoxUtilCombatVfx.normalizeFacingDeg(450f), 1e-4f)
        assertEquals(0f, BoxUtilCombatVfx.normalizeFacingDeg(720f), 1e-4f)
        // 边界与域内值恒等。
        assertEquals(0f, BoxUtilCombatVfx.normalizeFacingDeg(0f), 1e-4f)
        assertEquals(0f, BoxUtilCombatVfx.normalizeFacingDeg(360f), 1e-4f)
        assertEquals(270f, BoxUtilCombatVfx.normalizeFacingDeg(270f), 1e-4f)
    }

    @Test
    fun `normalizeFacingDeg preserves world direction exactly`() {
        // 归一化是纯周期映射：方向矢量（cos/sin）必须与原始角逐点一致。
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
