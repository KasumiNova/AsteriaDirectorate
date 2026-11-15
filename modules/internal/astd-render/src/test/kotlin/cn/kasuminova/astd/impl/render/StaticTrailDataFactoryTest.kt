package cn.kasuminova.astd.impl.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticTrailDataFactoryTest {
    @Test
    fun `带长按弹体速度折算为总寿命`() {
        assertEquals(0.525f, StaticTrailDataFactory.totalDurationSeconds(420f, 800f), 1e-3f)
        assertEquals(0.225f, StaticTrailDataFactory.totalDurationSeconds(135f, 600f), 1e-3f)
    }

    @Test
    fun `总寿命钳位到下限与上限`() {
        assertEquals(
            StaticTrailDataFactory.MIN_TOTAL_DURATION,
            StaticTrailDataFactory.totalDurationSeconds(10f, 5000f),
        )
        assertEquals(
            StaticTrailDataFactory.MAX_TOTAL_DURATION,
            StaticTrailDataFactory.totalDurationSeconds(5000f, 100f),
        )
        assertEquals(
            StaticTrailDataFactory.MAX_TOTAL_DURATION,
            StaticTrailDataFactory.totalDurationSeconds(420f, 0f),
        )
    }

    @Test
    fun `未启用视觉缩放时缓存键与池标识保持历史格式`() {
        val sampling = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, null)
        assertEquals("tree/layer@425.0", sampling.cacheKey)
        assertEquals("tree/layer", sampling.dataId)
        assertEquals(425f, sampling.bandLength)
        assertEquals(1200f, sampling.speedSuPerSec)
        assertEquals(1f, sampling.scale)
    }

    @Test
    fun `缩放档的池标识与缓存键一致且不同档互不相同`() {
        val half = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 0.5f)
        val full = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 1.0f)
        assertTrue(half.dataId != full.dataId)
        assertEquals(half.cacheKey, half.dataId)
        assertEquals(full.cacheKey, full.dataId)
        assertTrue(half.dataId != "tree/layer")
    }

    @Test
    fun `视觉缩放量化到步长档位且同档取值共用配置`() {
        assertEquals(20, StaticTrailDataFactory.scaleBucketOf(1.0f))
        assertEquals(20, StaticTrailDataFactory.scaleBucketOf(1.02f))
        assertEquals(21, StaticTrailDataFactory.scaleBucketOf(1.05f))
        assertEquals(20, StaticTrailDataFactory.scaleBucketOf(0.999f))

        val near = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 1.02f)
        val exact = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 1.0f)
        assertEquals(exact.cacheKey, near.cacheKey)
        assertEquals(exact.scale, near.scale)
    }

    @Test
    fun `缩放档改变键值与带长速度但保持总寿命`() {
        val half = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 0.5f)
        val full = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 1.0f)
        val double = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 2.0f)

        assertTrue(half.cacheKey != full.cacheKey)
        assertTrue(full.cacheKey != double.cacheKey)
        assertEquals(212.5f, half.bandLength, 1e-4f)
        assertEquals(425f, full.bandLength, 1e-4f)
        assertEquals(850f, double.bandLength, 1e-4f)

        val expected = StaticTrailDataFactory.totalDurationSeconds(425f, 1200f)
        assertEquals(expected, StaticTrailDataFactory.totalDurationSeconds(half.bandLength, half.speedSuPerSec), 1e-4f)
        assertEquals(expected, StaticTrailDataFactory.totalDurationSeconds(double.bandLength, double.speedSuPerSec), 1e-4f)
    }

    @Test
    fun `缩放组合只由两个基础桶与缩放档决定`() {
        val a = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, 1.3f)
        val b = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 424f, 1204f, 1.3f)
        assertEquals(a.cacheKey, b.cacheKey)
        assertEquals(a.bandLength, b.bandLength, 1e-4f)
        assertEquals(a.speedSuPerSec, b.speedSuPerSec, 1e-4f)
    }

    @Test
    fun `未缩放路径同桶带长也由桶值决定`() {
        val a = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 420f, 1200f, null)
        val b = StaticTrailDataFactory.resolveTrailSampling("tree", "layer", 424f, 1200f, null)
        assertEquals(a.cacheKey, b.cacheKey)
        assertEquals(a.bandLength, b.bandLength, 1e-4f)
    }
}
