package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lwjgl.util.vector.Vector2f

/**
 * 裂隙折跃机制数值（blue/10-unique.md XC-002 节舰船系统）的契约测试：
 * 闭合爆点序列的间距/数量/方向、折跃方向的飞行向量优先与朝向回退、
 * 裂隙接触判定的半宽边界与线段外投影。
 */
class RiftShiftTuningTest {

    @Test
    fun `闭合爆点序列 800su 路径 8 个爆点沿向均布`() {
        val points = RiftShiftTuning.closureBlastPoints(Vector2f(0f, 0f), Vector2f(800f, 0f))
        assertEquals(8, points.size, "800su 路径每 100su 一爆点")
        points.forEachIndexed { index, point ->
            assertEquals(100f * (index + 1), point.x, 1e-4f)
            assertEquals(0f, point.y, 1e-4f)
        }
        // 斜向路径：爆点落在 from→to 连线上
        val diag = RiftShiftTuning.closureBlastPoints(Vector2f(0f, 0f), Vector2f(600f, 800f))
        assertEquals(10, diag.size, "1000su 斜线路径 10 个爆点")
        val first = diag.first()
        assertEquals(0.6f, first.x / 100f, 1e-4f, "首爆点方向与路径一致（3-4-5 方向）")
        assertEquals(0.8f, first.y / 100f, 1e-4f)
    }

    @Test
    fun `闭合爆点序列 短于间距的路径为空`() {
        assertTrue(RiftShiftTuning.closureBlastPoints(Vector2f(0f, 0f), Vector2f(99f, 0f)).isEmpty())
        assertEquals(1, RiftShiftTuning.closureBlastPoints(Vector2f(0f, 0f), Vector2f(100f, 0f)).size)
    }

    @Test
    fun `折跃方向 飞行向量优先 近零速度回退朝向`() {
        val moving = RiftShiftTuning.shiftDirection(Vector2f(30f, 40f), 0f)
        assertEquals(0.6f, moving.x, 1e-6f)
        assertEquals(0.8f, moving.y, 1e-6f)

        val fallback = RiftShiftTuning.shiftDirection(Vector2f(0f, 0f), 90f)
        assertEquals(0f, fallback.x, 1e-6f, "朝向 90° 回退为 +Y")
        assertEquals(1f, fallback.y, 1e-6f)

        val nearZero = RiftShiftTuning.shiftDirection(Vector2f(0.5f, 0f), 180f)
        assertEquals(-1f, nearZero.x, 1e-6f, "速度 ≤1su/s 视为静止")
        assertEquals(0f, nearZero.y, 1e-6f)
    }

    @Test
    fun `裂隙接触判定 半宽加碰撞半径为界`() {
        val from = Vector2f(0f, 0f)
        val to = Vector2f(800f, 0f)
        val radius = 60f
        // 界内：垂距恰好 半宽 40 + 半径 60 = 100
        assertTrue(RiftShiftTuning.contactsRift(Vector2f(400f, 100f), radius, from, to))
        // 界外：垂距 100.1
        assertFalse(RiftShiftTuning.contactsRift(Vector2f(400f, 100.1f), radius, from, to))
        // 投影在线段外：端点外 100su 处（到端点距离 100，恰在界上）
        assertTrue(RiftShiftTuning.contactsRift(Vector2f(-100f, 0f), radius, from, to))
        assertFalse(RiftShiftTuning.contactsRift(Vector2f(-100.1f, 0f), radius, from, to))
    }

    @Test
    fun `折跃缓动曲线 端点恒等 中点过半 单调不减 域外钳制`() {
        assertEquals(0f, RiftShiftTuning.easeProgress(0f), 1e-6f, "起点 0→0")
        assertEquals(1f, RiftShiftTuning.easeProgress(1f), 1e-6f, "终点 1→1")
        assertEquals(0.5f, RiftShiftTuning.easeProgress(0.5f), 1e-6f, "中点 0.5→0.5")
        // smoothstep 加速段慢于线性、减速段快于线性（起步/到达速度为零）
        assertTrue(RiftShiftTuning.easeProgress(0.25f) < 0.25f, "前半程慢于线性（加速段）")
        assertTrue(RiftShiftTuning.easeProgress(0.75f) > 0.75f, "后半程快于线性（减速段）")
        // 单调不减（密集采样）
        var prev = -1f
        for (i in 0..100) {
            val v = RiftShiftTuning.easeProgress(i / 100f)
            assertTrue(v >= prev, "单调不减：t=${i / 100f}")
            prev = v
        }
        // 域外钳制
        assertEquals(0f, RiftShiftTuning.easeProgress(-0.3f), 1e-6f)
        assertEquals(1f, RiftShiftTuning.easeProgress(1.3f), 1e-6f)
    }
}
