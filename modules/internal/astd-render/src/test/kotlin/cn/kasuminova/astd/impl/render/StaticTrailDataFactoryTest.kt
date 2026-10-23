package cn.kasuminova.astd.impl.render

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `三段时长比例和为 1 且对齐旧观感锚点`() {
        assertEquals(
            1f,
            StaticTrailDataFactory.FADE_IN_RATIO + StaticTrailDataFactory.FULL_RATIO + StaticTrailDataFactory.FADE_OUT_RATIO,
            1e-6f,
        )
        assertEquals(0.6f, StaticTrailDataFactory.FADE_IN_RATIO + StaticTrailDataFactory.FULL_RATIO, 1e-6f)
    }
}
