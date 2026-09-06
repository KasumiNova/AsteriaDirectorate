package cn.kasuminova.astd.impl.render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StaticTrailDataFactory] 的带长→节点总寿命折算与三段时长比例守护。
 * StaticTrailData 构建本身依赖游戏运行时（贴图/vRAM 池），不在单测覆盖——由实机烟测目检。
 */
class StaticTrailDataFactoryTest {

    @Test
    fun `带长按弹体速度折算为总寿命`() {
        // aod7：420su 带长 / 800su/s = 0.525s
        assertEquals(0.525f, StaticTrailDataFactory.totalDurationSeconds(420f, 800f), 1e-3f)
        // spc3：135su / 600su/s = 0.225s
        assertEquals(0.225f, StaticTrailDataFactory.totalDurationSeconds(135f, 600f), 1e-3f)
    }

    @Test
    fun `总寿命钳位到下限与上限`() {
        // 极短带长 / 极快弹体 → 下限 0.15s（BoxUtil 要求三段总和 ≥ 0.1）
        assertEquals(
            StaticTrailDataFactory.MIN_TOTAL_DURATION,
            StaticTrailDataFactory.totalDurationSeconds(10f, 5000f),
        )
        // 极长带长 / 极慢弹体 → 上限 10s（vRAM 预算护栏）
        assertEquals(
            StaticTrailDataFactory.MAX_TOTAL_DURATION,
            StaticTrailDataFactory.totalDurationSeconds(5000f, 100f),
        )
        // 速度为 0 不抛异常：按最小速度折算后走上限钳位
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
        // 对齐旧 dissolveStart=0.6：淡入 5% + 满亮 55% = 60% 处开始线性消散
        assertEquals(0.6f, StaticTrailDataFactory.FADE_IN_RATIO + StaticTrailDataFactory.FULL_RATIO, 1e-6f)
    }
}
