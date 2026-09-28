package cn.kasuminova.astd.combat.effect.arc.starfallwing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残翼机制数值（blue/10-signature.md 坠星残翼节）的契约测试：
 * 振频适应承伤比映射的削弱/封顶/免伤口径、子射弹追踪延迟闸门（1s 惯性直飞后开追踪）、
 * 穿透扫掠用的点到线段距离与最近点（投影内/外/退化段）、碰撞箱多边形内部点判定。
 */
class StarfallWingTuningTest {

    @Test
    fun `承伤比映射 层数线性抬升承伤比且封顶 1`() {
        // base 0.7（XC-002 护盾效率口径）：5 层 → min(0.7+0.5, 1.0)/0.7 = 1/0.7
        assertEquals(1f / 0.7f, StarfallWingTuning.adaptationShieldMult(0.7f, 5f), 1e-6f)
        // 3 层恰好把 0.7 抬到 1.0（封顶边沿）
        assertEquals(1f / 0.7f, StarfallWingTuning.adaptationShieldMult(0.7f, 3f), 1e-6f)
        // 1 层未封顶：0.8/0.7
        assertEquals(0.8f / 0.7f, StarfallWingTuning.adaptationShieldMult(0.7f, 1f), 1e-6f)
        // 0 层不改动承伤比
        assertEquals(1f, StarfallWingTuning.adaptationShieldMult(0.7f, 0f), 1e-6f)
    }

    @Test
    fun `承伤比映射 免伤与等额承伤护盾恒 1`() {
        assertEquals(1f, StarfallWingTuning.adaptationShieldMult(0f, 10f), "base≤0 为免伤规格，不产生除零")
        assertEquals(1f, StarfallWingTuning.adaptationShieldMult(1f, 10f), "base≥1 已等额承伤，无削弱空间")
        assertEquals(1f, StarfallWingTuning.adaptationShieldMult(1.2f, 10f))
    }

    @Test
    fun `子射弹追踪闸门 延迟窗内不追踪 满 1s 开追踪`() {
        assertFalse(StarfallWingTuning.moteTrackingActive(0f), "射出瞬间惯性直飞")
        assertFalse(
            StarfallWingTuning.moteTrackingActive(StarfallWingTuning.MOTE_TRACK_DELAY_SECONDS - 0.01f),
            "延迟窗内不索敌不转向",
        )
        assertTrue(
            StarfallWingTuning.moteTrackingActive(StarfallWingTuning.MOTE_TRACK_DELAY_SECONDS),
            "满延迟即开追踪（闭区间下界）",
        )
        assertTrue(
            StarfallWingTuning.moteTrackingActive(StarfallWingTuning.MOTE_TRACK_DELAY_SECONDS + 1f),
            "延迟窗后持续追踪",
        )
    }

    @Test
    fun `多边形内部点判定 内部真 外部假 空段集假`() {
        // 正方形碰撞箱 [0,100]×[0,100]
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
