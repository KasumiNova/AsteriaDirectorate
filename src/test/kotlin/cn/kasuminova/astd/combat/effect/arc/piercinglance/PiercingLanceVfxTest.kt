package cn.kasuminova.astd.combat.effect.arc.piercinglance

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiercingLanceVfxTest {

    @Test
    fun `星云爆发计划为 10 至 15 颗且参数各自随机`() {
        val seeds = nebulaBurstSeeds(Random(42))

        assertTrue(seeds.size in 10..15, "星云数量须在 10~15，实际 ${seeds.size}")
        for (seed in seeds) {
            assertTrue(seed.speed in 25f..85f, "速度越界: ${seed.speed}")
            assertTrue(seed.size in 18f..52f, "尺寸越界: ${seed.size}")
            assertTrue(seed.durationSeconds in 1.5f..2.5f, "存续越界（平均 2s ± 0.5）: ${seed.durationSeconds}")
        }
        // 大小速度均不等（随机序列下出现全同参数的概率可忽略）
        assertTrue(seeds.map { it.speed }.toSet().size > 1, "速度应有差异")
        assertTrue(seeds.map { it.size }.toSet().size > 1, "尺寸应有差异")
        // 方向铺满四周：均匀基角 ± 0.3 rad 抖动，任意两颗夹角不超过 2π/count + 0.6
        val maxGap = 2f * Math.PI.toFloat() / seeds.size + 0.6f + 1e-3f
        val sorted = seeds.map { it.angleRad }.sorted()
        for (i in sorted.indices) {
            val next = sorted[(i + 1) % sorted.size] + if (i == sorted.lastIndex) 2f * Math.PI.toFloat() else 0f
            assertTrue(next - sorted[i] <= maxGap, "方向分布出现过大空洞: ${next - sorted[i]} > $maxGap")
        }
    }

    @Test
    fun `星云爆发计划存续均值约 2 秒`() {
        // 大样本均值回归：随机流锁定种子后多次采样总均值应落在 2s ± 0.1
        val random = Random(7)
        val durations = (1..40).flatMap { nebulaBurstSeeds(random) }.map { it.durationSeconds }
        assertEquals(2.0f, durations.average().toFloat(), 0.1f, "存续均值应约 2s")
    }
}
