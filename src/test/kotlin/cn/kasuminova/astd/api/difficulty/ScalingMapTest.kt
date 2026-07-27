package cn.kasuminova.astd.api.difficulty

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 分段线性映射的端点/中点/接合验证：直接驱动 [ScalingMap.LINEAR] 全链路计算。
 */
class ScalingMapTest {

    private val map = ScalingMap.LINEAR

    @Test
    fun `三锚点端点精确命中`() {
        assertEquals(6f, map.value(1f, 6f, 13f, 18f))
        assertEquals(13f, map.value(2f, 6f, 13f, 18f))
        assertEquals(18f, map.value(5f, 6f, 13f, 18f))
    }

    @Test
    fun `低段中点插值`() {
        // k=1.5：v1→v2 的中点
        assertEquals(9.5f, map.value(1.5f, 6f, 13f, 18f), 1e-6f)
    }

    @Test
    fun `高段中点插值`() {
        // k=3.5：v2→v5 的中点
        assertEquals(15.5f, map.value(3.5f, 6f, 13f, 18f), 1e-6f)
    }

    @Test
    fun `段间接合连续`() {
        // k=2 两侧极限应收敛到 v2
        val below = map.value(1.9999f, 6f, 13f, 18f)
        val above = map.value(2.0001f, 6f, 13f, 18f)
        assertEquals(below, above, 1e-3f)
        assertEquals(13f, below, 1e-3f)
    }

    @Test
    fun `越界系数被钳制到锚点`() {
        assertEquals(6f, map.value(0.2f, 6f, 13f, 18f))
        assertEquals(18f, map.value(9f, 6f, 13f, 18f))
    }
}
