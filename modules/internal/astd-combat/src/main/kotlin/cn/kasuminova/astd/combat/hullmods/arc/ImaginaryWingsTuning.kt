package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 虚数之翼（XC-002 星翼内置船插，规格 blue/10-unique.md XC-002 节）的机制数值声明与纯函数。
 *
 * 动机：系统激活后的 3s 衰减速度窗口与「伤害随航速比例缩放」的映射集中在一处声明；
 * 窗口加成衰减与伤害倍率均为纯函数，供 HullMod 每帧调用并由单元测试直接驱动。
 *
 * 难度缩放走 D13 双轨三锚点（[ScalingEntry]，isPlayer = owner==0 见 DifficultyTuning.valueFor）：
 * 速度峰值加成 50/100/250（%）、满速增伤 25/50/125（%）、超上限增伤 1/2/5（%/每超 1%）。
 * 窗口时长 3s 与静止减伤系数（满速增伤的一半）为设计案锁死项。
 */
object ImaginaryWingsTuning {

    /** 系统激活后的速度加成窗口时长（秒）。 */
    const val WINDOW_SECONDS = 3f

    /** 窗口峰值最大航速/机动性加成（%，三锚点 50/100/250）。 */
    val SPEED_BOOST_PERCENT = ScalingEntry(50f, 100f, 250f)

    /** 满速（100% 航速比）武器伤害加成（%，三锚点 25/50/125）。 */
    val FULL_SPEED_DAMAGE_PERCENT = ScalingEntry(25f, 50f, 125f)

    /** 航速超出上限后每超 1% 的额外伤害加成（%，三锚点 1/2/5）。 */
    val OVER_CAP_DAMAGE_PER_PERCENT = ScalingEntry(1f, 2f, 5f)

    /** 窗口内速度加成（纯函数，%）：峰值 × (1 − t/3s) 线性衰减；窗口外恒 0。 */
    fun speedBonusPercent(elapsedSeconds: Float, peakPercent: Float): Float =
        if (elapsedSeconds < 0f || elapsedSeconds >= WINDOW_SECONDS) {
            0f
        } else {
            peakPercent * (1f - elapsedSeconds / WINDOW_SECONDS)
        }

    /**
     * 武器伤害倍率（纯函数）：[ratio] = 当前航速 / 当前最大航速（含窗口加成后的上限）。
     * - ratio ≤ 1：1 + fullBonus × (1.5×ratio − 0.5)——0% 航速 −fullBonus/2、100% 航速 +fullBonus；
     * - ratio > 1：1 + fullBonus + (ratio−1) × overCapPerPercent（每超 1% 上限再加 overCapPerPercent%）。
     *
     * [fullSpeedBonus] 为分数（0.5 = +50%）；[overCapPerPercent] 为百分数读数（2 = 每超 1% 加 2%）。
     */
    fun damageMult(ratio: Float, fullSpeedBonus: Float, overCapPerPercent: Float): Float {
        val r = ratio.coerceAtLeast(0f)
        if (r <= 1f) return 1f + fullSpeedBonus * (1.5f * r - 0.5f)
        // (r−1)×100 = 超出百分比读数；每 1% 加 overCapPerPercent% → 乘算后两项 100 互消
        return 1f + fullSpeedBonus + (r - 1f) * overCapPerPercent
    }
}
