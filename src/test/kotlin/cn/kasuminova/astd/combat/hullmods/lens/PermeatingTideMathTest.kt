package cn.kasuminova.astd.combat.hullmods.lens

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PermeatingTideMath] 纯函数测试（渗透潮汐，spec §5 / `purple/10-unique.md` §1 插件③）。
 *
 * 覆盖：
 * - 叠深水标记的间隔按距离线性插值（≤1000su 最快 2.5s、≥2500su 不叠、中点插值）。
 * - 难度系数缩放（m=1 基线 vs m=2 间隔 ×0.5，即最快 1.25s/层）。
 * - 「不叠」sentinel（出场不叠返回 POSITIVE_INFINITY）。
 * - 场内判定 / 退潮（过载）判定。
 *
 * 纯函数先写失败 → 实现 → 通过；hullmod 集成层（advanceInCombat per-target 计时 / 提交潮汐场）不单测。
 */
class PermeatingTideMathTest {

    private fun approx(expected: Float, actual: Float, eps: Float = 1e-3f) {
        assertTrue(abs(expected - actual) <= eps, "expected ~$expected but was $actual")
    }

    // ---- 距离插值（基线难度 m=1）----

    @Test
    fun `interval is fastest at or inside the near distance`() {
        // ≤1000su → 最快间隔 baseNear = 2.5s（m=1）。
        approx(2.5f, PermeatingTideMath.markIntervalForDistance(0f, difficultyFactor = 1f))
        approx(2.5f, PermeatingTideMath.markIntervalForDistance(500f, difficultyFactor = 1f))
        approx(2.5f, PermeatingTideMath.markIntervalForDistance(1000f, difficultyFactor = 1f))
    }

    @Test
    fun `interval is slowest at the far ramp distance`() {
        // 恰在最慢叠加范围 2000su → baseFar = 5s（m=1）。
        approx(5f, PermeatingTideMath.markIntervalForDistance(2000f, difficultyFactor = 1f))
    }

    @Test
    fun `interval interpolates linearly at the midpoint of the ramp`() {
        // 1500su 为 [1000,2000] 中点 → (2.5+5)/2 = 3.75s（m=1）。
        approx(3.75f, PermeatingTideMath.markIntervalForDistance(1500f, difficultyFactor = 1f))
    }

    // ---- 不叠（出 2500su 场半径）----

    @Test
    fun `interval is infinite (no stacking) beyond the field radius`() {
        assertTrue(PermeatingTideMath.markIntervalForDistance(2500.1f, difficultyFactor = 1f).isInfinite())
        assertTrue(PermeatingTideMath.markIntervalForDistance(9999f, difficultyFactor = 1f).isInfinite())
    }

    @Test
    fun `interval is finite between the slow ramp end and the field edge`() {
        // 2000su~2500su 区间仍在场内、仍叠（保持最慢间隔），出 2500su 才不叠。
        val atRampEnd = PermeatingTideMath.markIntervalForDistance(2000f, difficultyFactor = 1f)
        val nearFieldEdge = PermeatingTideMath.markIntervalForDistance(2400f, difficultyFactor = 1f)
        assertTrue(atRampEnd.isFinite())
        assertTrue(nearFieldEdge.isFinite())
        approx(5f, nearFieldEdge)
        approx(5f, PermeatingTideMath.markIntervalForDistance(2500f, difficultyFactor = 1f))
    }

    // ---- 难度缩放 ----

    @Test
    fun `difficulty factor halves interval at m equals 2`() {
        // m=2 → interval = base / m。最快 2.5/2 = 1.25s（spec：最快约 1.25s/层）。
        approx(1.25f, PermeatingTideMath.markIntervalForDistance(1000f, difficultyFactor = 2f))
        // 最慢 5/2 = 2.5s。
        approx(2.5f, PermeatingTideMath.markIntervalForDistance(2000f, difficultyFactor = 2f))
    }

    @Test
    fun `difficulty factor is clamped to the one-to-two range`() {
        // m<1 视作 1（不增益本舰），m>2 视作 2（上限）。
        approx(2.5f, PermeatingTideMath.markIntervalForDistance(1000f, difficultyFactor = 0.5f))
        approx(1.25f, PermeatingTideMath.markIntervalForDistance(1000f, difficultyFactor = 5f))
    }

    @Test
    fun `difficulty does not make a non-stacking distance start stacking`() {
        // 出场距离即便 m=2 也不叠（infinity / 2 仍为 infinity）。
        assertTrue(PermeatingTideMath.markIntervalForDistance(3000f, difficultyFactor = 2f).isInfinite())
    }

    // ---- 场内判定 ----

    @Test
    fun `in tide field includes the exact field edge`() {
        assertTrue(PermeatingTideMath.isInTideField(0f))
        assertTrue(PermeatingTideMath.isInTideField(2500f))
        assertFalse(PermeatingTideMath.isInTideField(2500.1f))
    }

    // ---- 退潮（过载）判定 ----

    @Test
    fun `ebb triggers exactly when overloaded`() {
        assertTrue(PermeatingTideMath.shouldEbb(isOverloaded = true))
        assertFalse(PermeatingTideMath.shouldEbb(isOverloaded = false))
    }

    // ---- 常量自检 ----

    @Test
    fun `constants match spec anchors`() {
        assertEquals(2500f, PermeatingTideMath.FIELD_RADIUS)
        assertEquals(1000f, PermeatingTideMath.NEAR_DISTANCE)
        assertEquals(2000f, PermeatingTideMath.FAR_RAMP_DISTANCE)
        assertEquals(2.5f, PermeatingTideMath.BASE_INTERVAL_NEAR)
        assertEquals(5f, PermeatingTideMath.BASE_INTERVAL_FAR)
    }
}
