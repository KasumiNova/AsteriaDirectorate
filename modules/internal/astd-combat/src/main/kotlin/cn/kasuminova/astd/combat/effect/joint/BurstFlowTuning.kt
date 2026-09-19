package cn.kasuminova.astd.combat.effect.joint

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 落叶飞花（飞星 (ARC) 舰船系统）的机制数值声明（设计案 20-joint.md §战术系统-坠星）。
 *
 * 动机：瞬时爆发（不随时间线性增长/减弱，恒定口径）的时流/加减速/备弹恢复/辐能耗散/
 * 冲刺动量五合一系统（激活期间转向锁死，无转向加成数值），三锚点数值集中在此声明，
 * stats 脚本每次 apply 实时解析（LunaLib 设置变更即时生效）。
 *
 * 数值口径：设计案给定的 v1/v2/v5 三锚点恰为线性步进（时流 +50%/档，加减速/备弹/辐能耗散/
 * 动量逐档恰重合三锚点插值），登记 LINEAR 无超线性收益（爆发窗口固定 1s，不存在叠乘放大）。
 * 玩家来源（owner == 0）固定 v2（砺刃档），对照 ChargeNeedleTuning 既有口径。
 */
object BurstFlowTuning {

    /** 额外时流速度加成（v1 +200% / v2 +250% / v5 +400%；最终 timeMult = 1 + 本值）。 */
    val TIME_MULT_BONUS = ScalingEntry(2.0f, 2.5f, 4.0f)

    /** 加减速加成（v1 +100% / v2 +150% / v5 +250%；最终乘区 = 1 + 本值；激活期间转向锁死，不作用于转向）。 */
    val SPEED_MANEUVER_BONUS = ScalingEntry(1.0f, 1.5f, 2.5f)

    /** 非导弹武器备弹恢复加成（v1 +100% / v2 +200% / v5 +500%；最终乘区 = 1 + 本值）。 */
    val AMMO_REGEN_BONUS = ScalingEntry(1.0f, 2.0f, 5.0f)

    /** 额外辐能耗散速率加成（v1 +50% / v2 +100% / v5 +250%；最终乘区 = 1 + 本值）。 */
    val FLUX_DISSIPATION_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f)

    /** 冲刺动量相对最大航速的比例（v1 150% / v2 200% / v5 300%；直接作为倍率，无基准 1）。 */
    val MOMENTUM_BONUS = ScalingEntry(1.5f, 2.0f, 3.0f)

    /** 一次 apply 所需的全部机制数值（难度解析结果；最终乘区口径，直接可用）。 */
    data class Values(
        /** 舰船时间流速乘区（含基准 1）。 */
        val timeMult: Float,
        /** 加减速乘区（含基准 1；激活期间转向锁死，不再挂转向乘区）。 */
        val speedManeuverMult: Float,
        /** 非导弹武器备弹恢复乘区（含基准 1）。 */
        val ammoRegenMult: Float,
        /** 辐能耗散乘区（含基准 1）。 */
        val fluxDissipationMult: Float,
        /** 冲刺动量相对最大航速的倍率。 */
        val momentumMult: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        timeMult = 1f + pick(tuning, isPlayer, TIME_MULT_BONUS),
        speedManeuverMult = 1f + pick(tuning, isPlayer, SPEED_MANEUVER_BONUS),
        ammoRegenMult = 1f + pick(tuning, isPlayer, AMMO_REGEN_BONUS),
        fluxDissipationMult = 1f + pick(tuning, isPlayer, FLUX_DISSIPATION_BONUS),
        momentumMult = pick(tuning, isPlayer, MOMENTUM_BONUS),
    )

    private fun pick(tuning: DifficultyTuning, isPlayer: Boolean, entry: ScalingEntry): Float =
        if (isPlayer) entry.v2 else tuning.value(entry)
}
