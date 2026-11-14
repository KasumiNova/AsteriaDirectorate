package cn.kasuminova.astd.combat.effect.joint.stardust

import kotlin.test.Test
import kotlin.test.assertEquals

class StardustMoteTargetLimitTest {

    @Test
    fun `单目标上限为存活光尘数量的六成并向下取整`() {
        assertEquals(4, StardustMoteTuning.moteTargetLimit(7))
        assertEquals(4, StardustMoteTuning.moteTargetLimit(8))
        assertEquals(5, StardustMoteTuning.moteTargetLimit(9))
        assertEquals(6, StardustMoteTuning.moteTargetLimit(10))
        assertEquals(6, StardustMoteTuning.moteTargetLimit(11))
        assertEquals(9, StardustMoteTuning.moteTargetLimit(15))
        assertEquals(12, StardustMoteTuning.moteTargetLimit(20))
    }

    @Test
    fun `存活光尘不足下限时按四枚处理`() {
        assertEquals(4, StardustMoteTuning.moteTargetLimit(1))
        assertEquals(4, StardustMoteTuning.moteTargetLimit(4))
        assertEquals(4, StardustMoteTuning.moteTargetLimit(6))
    }
}
