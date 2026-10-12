package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 虚数之翼（XC-002 星翼内置船插，规格 blue/10-unique.md XC-002 节）的机制数值声明与纯函数。
 *
 * 动机：系统激活后的 3s 衰减速度窗口与「航速比例 → 备弹/射速/伤害」的映射集中在一处声明；
 * 窗口加成衰减与两条倍率通道均为纯函数，供 HullMod 每帧调用并由单元测试直接驱动。
 *
 * 难度缩放走 D13 双轨三锚点（[ScalingEntry]，isPlayer = owner==0 见 DifficultyTuning.valueFor）：
 * 速度峰值加成 75/100/175（%）、满速备弹/射速加成 25/50/125（%）、超速增伤 0.5/1/2.5（%/每超 1%）。
 * 窗口时长 3s 为设计案锁死项。
 *
 * 2026-10 裁定变动：
 * - 静止/满额航速通道的作用对象由「武器伤害」改为「武器备弹恢复速度与非导弹武器射速」；
 * - 航速比的基准由「当前最大航速」改为「基础最大航速」，超速段（ratio>1）只产出武器伤害加成；
 * - 静止惩罚按 [restBoostPercent] 派生（砺刃 -25%、破晓档起无惩罚）。
 */
object ImaginaryWingsTuning {

    /** 系统激活后的速度加成窗口时长（秒）。 */
    const val WINDOW_SECONDS = 3f

    /** 窗口峰值最大航速/机动性加成（%，三锚点 75/100/175）。 */
    val SPEED_BOOST_PERCENT = ScalingEntry(75f, 100f, 175f)

    /** 满速（100% 基础航速比）武器备弹恢复速度与非导弹武器射速加成（%，三锚点 25/50/125）。 */
    val FULL_SPEED_BOOST_PERCENT = ScalingEntry(25f, 50f, 125f)

    /** 航速超出基础最大航速后每超 1% 的额外武器伤害加成（%，三锚点 0.5/1/2.5）。 */
    val OVER_CAP_DAMAGE_PER_PERCENT = ScalingEntry(0.5f, 1f, 2.5f)

    /** 窗口内速度加成（纯函数，%）：峰值 × (1 − t/3s) 线性衰减；窗口外恒 0。 */
    fun speedBonusPercent(elapsedSeconds: Float, peakPercent: Float): Float =
        if (elapsedSeconds < 0f || elapsedSeconds >= WINDOW_SECONDS) {
            0f
        } else {
            peakPercent * (1f - elapsedSeconds / WINDOW_SECONDS)
        }

    /**
     * 静止状态（0% 航速比）的备弹/射速惩罚（%）：min(0, 满速加成/2 − 50)——
     * 迟暮 -37.5%、砺刃 -25%、破晓档起（满速加成 ≥100）无惩罚。
     */
    fun restBoostPercent(fullSpeedPercent: Float): Float =
        (fullSpeedPercent / 2f - 50f).coerceAtMost(0f)

    /**
     * 备弹/射速倍率（纯函数）：[ratio] = 当前航速 / 基础最大航速。
     * ratio ∈ [0,1] 在静止值（[restBoostPercent]）与满速值（+[fullSpeedPercent]%）间线性插值；
     * ratio > 1 恒为满速值（超速段不继续提升备弹/射速）。
     */
    fun sustainedMult(ratio: Float, fullSpeedPercent: Float): Float {
        val r = ratio.coerceIn(0f, 1f)
        val rest = restBoostPercent(fullSpeedPercent)
        return 1f + (rest + (fullSpeedPercent - rest) * r) / 100f
    }

    /**
     * 超速武器伤害倍率（纯函数）：ratio ≤ 1 恒 1（非超速段不调整武器伤害）；
     * 超出部分每 1% 基础最大航速加 [overCapPerPercent]%——(r−1)×100 为超出百分比读数，
     * 乘 overCapPerPercent/100 后两项 100 互消。
     */
    fun overCapDamageMult(ratio: Float, overCapPerPercent: Float): Float {
        val r = ratio.coerceAtLeast(0f)
        if (r <= 1f) return 1f
        return 1f + (r - 1f) * overCapPerPercent
    }
}
