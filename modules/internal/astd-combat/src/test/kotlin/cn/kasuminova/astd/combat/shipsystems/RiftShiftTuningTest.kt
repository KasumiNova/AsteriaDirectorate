package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.lwjgl.util.vector.Vector2f

/**
 * 裂隙折跃机制数值（blue/10-unique.md XC-002 节舰船系统）的契约测试：
 * 伤害点位布点序列的间距/数量/方向、折跃方向的飞行向量优先与朝向回退、
 * 变距折跃长度的下限/上限钳制、动态拉开时长的距离线性映射、
 * 路径推进点端点口径、点位接触判定的最近点选取与界外剔除、折跃缓动曲线。
 */
class RiftShiftTuningTest {

    @Test
    fun `伤害点位序列 沿路径按间距均布`() {
        val spacing = RiftShiftTuning.ANCHOR_SPACING
        val points = RiftShiftTuning.anchorPoints(Vector2f(0f, 0f), Vector2f(800f, 0f))
        assertEquals(8, points.size, "800su 路径按间距布点 8 个")
        points.forEachIndexed { index, point ->
            assertEquals(spacing * (index + 1), point.x, 1e-4f)
            assertEquals(0f, point.y, 1e-4f)
        }
        // 斜向路径（3-4-5 方向，全长 1000su）：布点落在 from→to 连线上
        val diag = RiftShiftTuning.anchorPoints(Vector2f(0f, 0f), Vector2f(600f, 800f))
        assertEquals(10, diag.size, "1000su 斜线路径按间距布点 10 个")
        val first = diag.first()
        assertEquals(0.6f, first.x / spacing, 1e-4f, "首个点位方向与路径一致")
        assertEquals(0.8f, first.y / spacing, 1e-4f)
        // 末点不超过路径全长（850su 路径只布 8 个，余量不足一个间距）
        val rest = RiftShiftTuning.anchorPoints(Vector2f(0f, 0f), Vector2f(850f, 0f))
        assertEquals(8, rest.size)
        assertEquals(800f, rest.last().x, 1e-4f)
    }

    @Test
    fun `伤害点位序列 短于间距的路径为空`() {
        val spacing = RiftShiftTuning.ANCHOR_SPACING
        assertTrue(
            RiftShiftTuning.anchorPoints(Vector2f(0f, 0f), Vector2f(spacing - 1f, 0f)).isEmpty(),
        )
        assertEquals(
            1,
            RiftShiftTuning.anchorPoints(Vector2f(0f, 0f), Vector2f(spacing, 0f)).size,
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
    fun `动态拉开时长 按折跃距离占比线性映射`() {
        val max = RiftShiftTuning.SHIFT_DISTANCE
        val full = RiftShiftTuning.shiftDurationSeconds(max, max)
        assertEquals(RiftShiftTuning.SHIFT_DURATION_MAX, full, 1e-6f, "满距拉开时长为名义最大值")
        val shortest = RiftShiftTuning.shiftDurationSeconds(max, max * RiftShiftTuning.MIN_SHIFT_FRACTION)
        assertEquals(
            RiftShiftTuning.SHIFT_DURATION_MAX * RiftShiftTuning.MIN_SHIFT_FRACTION,
            shortest, 1e-6f,
            "最短折跃（25% 占比）拉开时长为名义最大值的 25%",
        )
        val half = RiftShiftTuning.shiftDurationSeconds(max, max * 0.5f)
        assertEquals(RiftShiftTuning.SHIFT_DURATION_MAX * 0.5f, half, 1e-6f, "半程距离线性映射半程时长")
        assertEquals(
            RiftShiftTuning.SHIFT_DURATION_MAX,
            RiftShiftTuning.shiftDurationSeconds(max, max * 2f), 1e-6f,
            "占比超出 100% 钳到名义最大值",
        )
        assertEquals(
            shortest,
            RiftShiftTuning.shiftDurationSeconds(max, max * 0.1f), 1e-6f,
            "占比低于 25% 钳到下限时长",
        )
    }

    @Test
    fun `路径推进点 端点恒等 中点在半程`() {
        val from = Vector2f(0f, 0f)
        val to = Vector2f(800f, 0f)
        val start = RiftShiftTuning.pathPointAt(from, to, 0f)
        assertEquals(from.x, start.x, 1e-4f, "进度 0 在路径起点")
        assertEquals(from.y, start.y, 1e-4f)
        val end = RiftShiftTuning.pathPointAt(from, to, 1f)
        assertEquals(to.x, end.x, 1e-4f, "进度 1 在路径终点")
        assertEquals(to.y, end.y, 1e-4f)
        val mid = RiftShiftTuning.pathPointAt(from, to, 0.5f)
        assertEquals(400f, mid.x, 1e-4f, "缓动曲线中点恒等，半程推进在路径中点")
        assertEquals(0f, mid.y, 1e-4f)
    }

    @Test
    fun `点位接触判定 界内取最近点位 交叠区单点结算 界外剔除`() {
        val points = RiftShiftTuning.anchorPoints(Vector2f(0f, 0f), Vector2f(800f, 0f))
        val range = RiftShiftTuning.CONTACT_RANGE
        // 正对点位：最近点位即正对点
        val headOn = RiftShiftTuning.nearestAnchorInRange(Vector2f(400f, 50f), points)
        assertEquals(400f, headOn!!.x, 1e-4f)
        assertEquals(0f, headOn.y, 1e-4f)
        // 两点交叠区（距 (100,0) 40su、距 (200,0) 60su）：取最近点 (100,0)
        val overlap = RiftShiftTuning.nearestAnchorInRange(Vector2f(140f, 0f), points)
        assertEquals(100f, overlap!!.x, 1e-4f, "交叠区只按最近点位结算")
        // 界上恰为接触范围
        val onEdge = RiftShiftTuning.nearestAnchorInRange(Vector2f(400f, range), points)
        assertEquals(400f, onEdge!!.x, 1e-4f)
        // 界外：垂距超出接触范围
        assertNull(RiftShiftTuning.nearestAnchorInRange(Vector2f(400f, range + 0.1f), points))
        // 界外：路径端外（起点后方无点位覆盖）
        assertNull(RiftShiftTuning.nearestAnchorInRange(Vector2f(-range - 0.1f, 0f), points))
        // 空点位序列
        assertNull(RiftShiftTuning.nearestAnchorInRange(Vector2f(0f, 0f), emptyList()))
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
