package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lwjgl.util.vector.Vector2f

/**
 * 裂隙折跃机制数值（blue/10-unique.md XC-002 节舰船系统）的契约测试：
 * 虚空锚雷布点序列的间距/数量/方向、折跃方向的飞行向量优先与朝向回退、
 * 变距折跃长度的下限/上限钳制、裂隙接触判定的固定接触范围边界、闭合收拢末端曲线。
 */
class RiftShiftTuningTest {

    @Test
    fun `虚空锚雷布点序列 沿路径按间距均布`() {
        val spacing = RiftShiftTuning.MINE_SPACING
        val points = RiftShiftTuning.mineAnchorPoints(Vector2f(0f, 0f), Vector2f(800f, 0f))
        assertEquals(8, points.size, "800su 路径按间距布点 8 枚")
        points.forEachIndexed { index, point ->
            assertEquals(spacing * (index + 1), point.x, 1e-4f)
            assertEquals(0f, point.y, 1e-4f)
        }
        // 斜向路径（3-4-5 方向，全长 1000su）：布点落在 from→to 连线上
        val diag = RiftShiftTuning.mineAnchorPoints(Vector2f(0f, 0f), Vector2f(600f, 800f))
        assertEquals(10, diag.size, "1000su 斜线路径按间距布点 10 枚")
        val first = diag.first()
        assertEquals(0.6f, first.x / spacing, 1e-4f, "首枚布点方向与路径一致")
        assertEquals(0.8f, first.y / spacing, 1e-4f)
    }

    @Test
    fun `虚空锚雷布点序列 短于间距的路径为空`() {
        val spacing = RiftShiftTuning.MINE_SPACING
        assertTrue(
            RiftShiftTuning.mineAnchorPoints(Vector2f(0f, 0f), Vector2f(spacing - 1f, 0f)).isEmpty(),
        )
        assertEquals(
            1,
            RiftShiftTuning.mineAnchorPoints(Vector2f(0f, 0f), Vector2f(spacing, 0f)).size,
        )
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
    fun `变距折跃长度 钳进下限与上限`() {
        val max = 800f
        val min = max * RiftShiftTuning.MIN_SHIFT_FRACTION
        assertEquals(min, RiftShiftTuning.resolveShiftDistance(max, 50f), 1e-4f, "目标距离低于下限钳到下限")
        assertEquals(500f, RiftShiftTuning.resolveShiftDistance(max, 500f), 1e-4f, "界内距离原样通过")
        assertEquals(max, RiftShiftTuning.resolveShiftDistance(max, 1200f), 1e-4f, "目标距离超出上限钳到上限")
        assertEquals(min, RiftShiftTuning.resolveShiftDistance(max, min), 1e-4f, "下限恰在界上")
    }

    @Test
    fun `裂隙接触判定 目标心到段距离以固定接触范围为界`() {
        val from = Vector2f(0f, 0f)
        val to = Vector2f(800f, 0f)
        val range = RiftShiftTuning.CONTACT_RANGE
        // 界内：垂距恰为接触范围
        assertTrue(RiftShiftTuning.contactsRift(Vector2f(400f, range), from, to))
        // 界外：垂距超出接触范围
        assertFalse(RiftShiftTuning.contactsRift(Vector2f(400f, range + 0.1f), from, to))
        // 投影在线段外：端点外接触范围处（到端点距离恰在界上）
        assertTrue(RiftShiftTuning.contactsRift(Vector2f(-range, 0f), from, to))
        assertFalse(RiftShiftTuning.contactsRift(Vector2f(-range - 0.1f, 0f), from, to))
    }

    @Test
    fun `闭合收拢末端 起点在成形末端 终点回起点 中点在半程`() {
        val from = Vector2f(0f, 0f)
        val to = Vector2f(800f, 0f)
        val start = RiftShiftTuning.closureTip(from, to, 0f)
        assertEquals(to.x, start.x, 1e-4f, "收拢进度 0 时末端在成形末端")
        assertEquals(to.y, start.y, 1e-4f)
        val end = RiftShiftTuning.closureTip(from, to, 1f)
        assertEquals(from.x, end.x, 1e-4f, "收拢进度 1 时末端回到起点")
        assertEquals(from.y, end.y, 1e-4f)
        val mid = RiftShiftTuning.closureTip(from, to, 0.5f)
        assertEquals(400f, mid.x, 1e-4f, "缓动曲线中点恒等，收拢半程末端在路径中点")
        assertEquals(0f, mid.y, 1e-4f)
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

    @Test
    fun `段最近点 投影界内取垂足 界外取端点 退化段取端点`() {
        val from = Vector2f(0f, 0f)
        val to = Vector2f(800f, 0f)
        val foot = RiftShiftTuning.closestPointOnSegment(Vector2f(400f, 50f), from, to)
        assertEquals(400f, foot.x, 1e-4f)
        assertEquals(0f, foot.y, 1e-4f)
        val beyond = RiftShiftTuning.closestPointOnSegment(Vector2f(-30f, 40f), from, to)
        assertEquals(0f, beyond.x, 1e-4f, "投影在线段外取最近端点")
        assertEquals(0f, beyond.y, 1e-4f)
        val degenerate = RiftShiftTuning.closestPointOnSegment(Vector2f(10f, 0f), from, from)
        assertEquals(0f, degenerate.x, 1e-4f, "退化为点的线段返回端点")
        assertEquals(0f, degenerate.y, 1e-4f)
    }
}
