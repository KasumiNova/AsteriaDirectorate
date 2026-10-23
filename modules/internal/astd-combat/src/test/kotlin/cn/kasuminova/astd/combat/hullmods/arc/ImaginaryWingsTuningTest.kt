package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `静止惩罚派生 三锚点档位读数`() {
        assertEquals(-37.5f, ImaginaryWingsTuning.restBoostPercent(25f), 1e-6f, "迟暮（满速+25%）静止 -37.5%")
        assertEquals(-25f, ImaginaryWingsTuning.restBoostPercent(50f), 1e-6f, "砺刃（满速+50%）静止 -25%")
        assertEquals(0f, ImaginaryWingsTuning.restBoostPercent(125f), 1e-6f, "破晓（满速+125%）静止无惩罚")
    }

    @Test
    fun `备弹射速倍率 砺刃档设计锚点与线性插值`() {
        assertEquals(0.75f, ImaginaryWingsTuning.sustainedMult(0f, 50f), 1e-6f, "0% 航速 -25%")
        assertEquals(1.5f, ImaginaryWingsTuning.sustainedMult(1f, 50f), 1e-6f, "100% 航速 +50%")
        assertEquals(1.125f, ImaginaryWingsTuning.sustainedMult(0.5f, 50f), 1e-6f, "50% 航速线性中点 +12.5%")
        assertEquals(1.5f, ImaginaryWingsTuning.sustainedMult(1.5f, 50f), 1e-6f, "超速段恒为满速值")
        assertEquals(0.75f, ImaginaryWingsTuning.sustainedMult(-0.3f, 50f), 1e-6f)
    }

    @Test
    fun `超速伤害 非超速段恒一 超出部分按每百分之一加计`() {
        assertEquals(1f, ImaginaryWingsTuning.overCapDamageMult(0f, 1f), "静止无伤害调整")
        assertEquals(1f, ImaginaryWingsTuning.overCapDamageMult(1f, 1f), "满速（未超）无伤害加成")
        assertEquals(1.5f, ImaginaryWingsTuning.overCapDamageMult(1.5f, 1f), 1e-6f, "超出 50% → +50%")
        assertEquals(2.25f, ImaginaryWingsTuning.overCapDamageMult(1.5f, 2.5f), 1e-6f, "破晓档超出 50% → +125%")
    }
}
