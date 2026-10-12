package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 虚数之翼机制数值（blue/10-unique.md XC-002 节内置 Hullmod，2026-10 裁定）的契约测试：
 * - 3s 速度窗口的线性衰减与窗口外归零；
 * - 静止/满速通道改为备弹恢复与非导弹武器射速（砺刃档：静止 −25% / 满速 +50%）与线性插值；
 * - 静止惩罚派生（min(0, 满速/2 − 50)：迟暮 −37.5%、砺刃 −25%、破晓起无惩罚）；
 * - 超速段只产出武器伤害（每超 1% 基础最大航速 +overCap%），非超速段伤害恒 1。
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
    fun `静止惩罚派生 三锚点档位读数`() {
        assertEquals(-37.5f, ImaginaryWingsTuning.restBoostPercent(25f), 1e-6f, "迟暮（满速+25%）静止 -37.5%")
        assertEquals(-25f, ImaginaryWingsTuning.restBoostPercent(50f), 1e-6f, "砺刃（满速+50%）静止 -25%")
        assertEquals(0f, ImaginaryWingsTuning.restBoostPercent(125f), 1e-6f, "破晓（满速+125%）静止无惩罚")
    }

    @Test
    fun `备弹射速倍率 砺刃档设计锚点与线性插值`() {
        // 砺刃档读数：满速加成 50% → 静止 -25%、满速 +50%
        assertEquals(0.75f, ImaginaryWingsTuning.sustainedMult(0f, 50f), 1e-6f, "0% 航速 -25%")
        assertEquals(1.5f, ImaginaryWingsTuning.sustainedMult(1f, 50f), 1e-6f, "100% 航速 +50%")
        assertEquals(1.125f, ImaginaryWingsTuning.sustainedMult(0.5f, 50f), 1e-6f, "50% 航速线性中点 +12.5%")
        // 超速段不继续提升备弹/射速
        assertEquals(1.5f, ImaginaryWingsTuning.sustainedMult(1.5f, 50f), 1e-6f, "超速段恒为满速值")
        // 负比例（异常输入）按 0% 航速处理
        assertEquals(0.75f, ImaginaryWingsTuning.sustainedMult(-0.3f, 50f), 1e-6f)
    }

    @Test
    fun `超速伤害 非超速段恒一 超出部分按每百分之一加计`() {
        // 砺刃档读数：每超 1% 基础最大航速 +1%
        assertEquals(1f, ImaginaryWingsTuning.overCapDamageMult(0f, 1f), "静止无伤害调整")
        assertEquals(1f, ImaginaryWingsTuning.overCapDamageMult(1f, 1f), "满速（未超）无伤害加成")
        assertEquals(1.5f, ImaginaryWingsTuning.overCapDamageMult(1.5f, 1f), 1e-6f, "超出 50% → +50%")
        assertEquals(2.25f, ImaginaryWingsTuning.overCapDamageMult(1.5f, 2.5f), 1e-6f, "破晓档超出 50% → +125%")
    }
}
