package cn.kasuminova.astd.combat.effect.joint

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 视界变速（飞星 (LENS) 舰船系统）的机制数值声明（设计案 20-joint.md §战术系统-紫菀）。
 *
 * 动机：自身时流提升 + 单目标时流压制（按体型四档）+ 目标承伤方向修正（来自本舰增伤、
 * 来自他单位减伤）。三锚点数值集中在此声明，激活时一次性解析（持续 10s 窗口内不随
 * LunaLib 设置漂移，口径与既有一次性结算机制一致）。
 *
 * 数值口径：设计案给定 v1/v5 区间，v2 取区间中点（自身时流 50%~100% → 75% 基准）；
 * 全部线性步进登记 LINEAR，无超线性收益（压制乘区作用于单目标、窗口固定 10s）。
 * 「来自他单位减伤」为反向缩放（难度越高减伤越弱：v1 -80% / v5 -40%），锚点递减登记。
 * 玩家来源（owner == 0）固定 v2（砺刃档）。
 */
object VisionShiftTuning {

    /** 自身时流加成（v1 +50% / v2 +75% / v5 +100%；最终 timeMult = 1 + 本值）。 */
    val SELF_TIME_BONUS = ScalingEntry(0.50f, 0.75f, 1.0f)

    /** 目标时流压制——护卫舰档（v1 -40% / v2 -60% / v5 -80%；最终 timeMult = 1 - 本值）。 */
    val TARGET_TIME_REDUCTION_FRIGATE = ScalingEntry(0.40f, 0.60f, 0.80f)

    /** 驱逐舰档（v1 -35% / v2 -52.5% / v5 -70%）。 */
    val TARGET_TIME_REDUCTION_DESTROYER = ScalingEntry(0.35f, 0.525f, 0.70f)

    /** 巡洋舰档（v1 -30% / v2 -45% / v5 -60%）。 */
    val TARGET_TIME_REDUCTION_CRUISER = ScalingEntry(0.30f, 0.45f, 0.60f)

    /** 主力舰档（v1 -25% / v2 -37.5% / v5 -50%）。 */
    val TARGET_TIME_REDUCTION_CAPITAL = ScalingEntry(0.25f, 0.375f, 0.50f)

    /** 目标受到来自本舰的伤害加成（v1 +25% / v2 +62.5% / v5 +100%；最终乘区 = 1 + 本值）。 */
    val DAMAGE_FROM_SELF_BONUS = ScalingEntry(0.25f, 0.625f, 1.0f)

    /** 激活时解析的全部机制数值（最终乘区口径，直接可用）。 */
    data class Values(
        /** 自身时间流速乘区（含基准 1）。 */
        val selfTimeMult: Float,
        /** 目标时间流速乘区（含基准 1；按目标体型分档）。 */
        val targetTimeMult: Float,
        /** 目标受到来自本舰伤害的乘区。 */
        val damageFromSelfMult: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射；[targetHullSize] 为目标体型分档依据。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean, targetHullSize: ShipAPI.HullSize?): Values = Values(
        selfTimeMult = 1f + pick(tuning, isPlayer, SELF_TIME_BONUS),
        targetTimeMult = 1f - pick(tuning, isPlayer, reductionForSize(targetHullSize)),
        damageFromSelfMult = 1f + pick(tuning, isPlayer, DAMAGE_FROM_SELF_BONUS),
    )

    /** 体型分档（纯函数）：护卫/驱逐/巡洋/主力四档；战机/DEFAULT/null 按护卫舰档（与 AffixShared.bySize 同口径）。 */
    fun reductionForSize(hullSize: ShipAPI.HullSize?): ScalingEntry = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> TARGET_TIME_REDUCTION_CAPITAL
        ShipAPI.HullSize.CRUISER -> TARGET_TIME_REDUCTION_CRUISER
        ShipAPI.HullSize.DESTROYER -> TARGET_TIME_REDUCTION_DESTROYER
        else -> TARGET_TIME_REDUCTION_FRIGATE
    }

    private fun pick(tuning: DifficultyTuning, isPlayer: Boolean, entry: ScalingEntry): Float =
        if (isPlayer) entry.v2 else tuning.value(entry)
}
