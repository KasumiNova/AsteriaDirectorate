package cn.kasuminova.astd.combat.effect.arc.starfallwing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lwjgl.util.vector.Vector2f

class StarfallWingTuningTest {

    @Test
    fun `振频蚀穿承伤比映射 层数线性劣化承伤效率且封顶 1`() {
        assertEquals(0.82f / 0.8f, StarfallWingTuning.erosionShieldMult(0.8f, 1), 1e-6f, "1 层 +2% 劣化")
        assertEquals(1f / 0.8f, StarfallWingTuning.erosionShieldMult(0.8f, 15), 1e-6f, "满层劣化至 1.0 封顶")
        assertEquals(1f / 0.8f, StarfallWingTuning.erosionShieldMult(0.8f, 20), 1e-6f, "超层数仍封顶 1.0")
        assertEquals(1f, StarfallWingTuning.erosionShieldMult(0.8f, 0), 1e-6f, "无层数不劣化")
    }

    @Test
    fun `振频蚀穿承伤比映射 免伤与等额承伤护盾恒 1`() {
        assertEquals(1f, StarfallWingTuning.erosionShieldMult(0f, 10), "base≤0 为免伤规格，不产生除零")
        assertEquals(1f, StarfallWingTuning.erosionShieldMult(1f, 10), "base≥1 已等额承伤，无削弱空间")
        assertEquals(1f, StarfallWingTuning.erosionShieldMult(1.2f, 10))
    }

    @Test
    fun `穿透 tick 伤害与 EMP 同比例面板折算`() {
        assertEquals(100f, StarfallWingTuning.pierceTickDamage(400f), 1e-6f)
        assertEquals(75f, StarfallWingTuning.pierceTickEmp(300f), 1e-6f)
    }

    @Test
    fun `多边形内部点判定 内部真 外部假 空段集假`() {
        val square = listOf(
            Vector2f(0f, 0f) to Vector2f(100f, 0f),
            Vector2f(100f, 0f) to Vector2f(100f, 100f),
            Vector2f(100f, 100f) to Vector2f(0f, 100f),
            Vector2f(0f, 100f) to Vector2f(0f, 0f),
        )
        assertTrue(
            StarfallWingTuning.pointInPolygon(Vector2f(50f, 50f), square),
            "深内部位采样点算接触（中段漏拍闸门修复口径）",
        )
        assertFalse(
            StarfallWingTuning.pointInPolygon(Vector2f(150f, 50f), square),
            "界外点不算内部接触",
        )
        assertFalse(
            StarfallWingTuning.pointInPolygon(Vector2f(-10f, -10f), square),
            "角部界外点不算内部接触",
        )
        assertFalse(
            StarfallWingTuning.pointInPolygon(Vector2f(50f, 50f), emptyList()),
            "空段集无碰撞箱语义，恒 false（调用方走碰撞圈近似）",
        )
    }

    @Test
    fun `点到线段距离 投影在线段内取垂距 投影在外取端点距`() {
        val a = Vector2f(0f, 0f)
        val b = Vector2f(100f, 0f)
        assertEquals(0f, StarfallWingTuning.distanceToSegment(Vector2f(50f, 0f), a, b), 1e-6f, "点在线段上")
        assertEquals(30f, StarfallWingTuning.distanceToSegment(Vector2f(50f, 30f), a, b), 1e-6f, "投影在线段内")
        assertEquals(50f, StarfallWingTuning.distanceToSegment(Vector2f(-40f, 30f), a, b), 1e-6f, "投影在 a 外侧")
        assertEquals(50f, StarfallWingTuning.distanceToSegment(Vector2f(140f, 30f), a, b), 1e-6f, "投影在 b 外侧")
    }

    @Test
    fun `点到线段距离 退化线段按点到点处理`() {
        val a = Vector2f(10f, 10f)
        assertEquals(
            5f,
            StarfallWingTuning.distanceToSegment(Vector2f(13f, 14f), a, Vector2f(a)),
            1e-6f,
        )
    }

    @Test
    fun `点到线段最近点 投影内取垂足 投影外钳端点 退化段返回端点`() {
        val a = Vector2f(0f, 0f)
        val b = Vector2f(100f, 0f)
        val foot = StarfallWingTuning.closestPointOnSegment(Vector2f(40f, 30f), a, b)
        assertEquals(40f, foot.x, 1e-6f, "投影在线段内取垂足")
        assertEquals(0f, foot.y, 1e-6f)
        val clampA = StarfallWingTuning.closestPointOnSegment(Vector2f(-20f, 30f), a, b)
        assertEquals(0f, clampA.x, 1e-6f, "投影在 a 外侧钳到端点 a")
        assertEquals(0f, clampA.y, 1e-6f)
        val clampB = StarfallWingTuning.closestPointOnSegment(Vector2f(140f, -10f), a, b)
        assertEquals(100f, clampB.x, 1e-6f, "投影在 b 外侧钳到端点 b")
        assertEquals(0f, clampB.y, 1e-6f)
        val degenerate = StarfallWingTuning.closestPointOnSegment(Vector2f(5f, 5f), a, Vector2f(a))
        assertEquals(0f, degenerate.x, 1e-6f, "退化线段返回端点 a")
        assertEquals(0f, degenerate.y, 1e-6f)
    }
}
