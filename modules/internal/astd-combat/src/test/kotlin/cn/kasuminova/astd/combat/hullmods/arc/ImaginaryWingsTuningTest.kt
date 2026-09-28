package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 虚数之翼机制数值（blue/10-unique.md XC-002 节内置 Hullmod）的契约测试：
 * 3s 速度窗口的线性衰减与窗口外归零、伤害倍率映射的设计锚点
 * （砺刃档：静止 −25% / 满速 +50% / 超上限每 1% +2%）与分段连续性。
 */
class ImaginaryWingsTuningTest {

    @Test
    fun `速度窗口 峰值线性衰减 窗口外恒零`() {
        assertEquals(100f, ImaginaryWingsTuning.speedBonusPercent(0f, 100f), "激活瞬间为满峰值")
        assertEquals(50f, ImaginaryWingsTuning.speedBonusPercent(1.5f, 100f), "窗口中点衰减至一半")
        assertEquals(0f, ImaginaryWingsTuning.speedBonusPercent(3f, 100f), "窗口结束（含端点）归零")
        assertEquals(0f, ImaginaryWingsTuning.speedBonusPercent(10f, 100f), "窗口外恒零")
        assertEquals(0f, ImaginaryWingsTuning.speedBonusPercent(-0.1f, 100f), "负时长（未开窗）恒零")
    }

    @Test
    fun `伤害倍率 砺刃档设计锚点`() {
        // 砺刃档读数：满速增伤 50%（分数 0.5）、超上限每 1% +2%
        assertEquals(0.75f, ImaginaryWingsTuning.damageMult(0f, 0.5f, 2f), 1e-6f, "0% 航速 −25%")
        assertEquals(1.5f, ImaginaryWingsTuning.damageMult(1f, 0.5f, 2f), 1e-6f, "100% 航速 +50%")
        assertEquals(2.5f, ImaginaryWingsTuning.damageMult(1.5f, 0.5f, 2f), 1e-6f, "超上限 50% → +50% 基础上再 +100%")
    }

    @Test
    fun `伤害倍率 分段在满速点连续 负比例钳制`() {
        // 两段在 ratio=1 处同值（1 + fullBonus），跨段无跳变
        val below = ImaginaryWingsTuning.damageMult(1f - 1e-4f, 0.5f, 2f)
        val above = ImaginaryWingsTuning.damageMult(1f + 1e-4f, 0.5f, 2f)
        assertEquals(below, above, 1e-3f)
        // 负比例（异常输入）按 0% 航速处理
        assertEquals(
            ImaginaryWingsTuning.damageMult(0f, 0.5f, 2f),
            ImaginaryWingsTuning.damageMult(-0.3f, 0.5f, 2f),
            1e-6f,
        )
    }
}
