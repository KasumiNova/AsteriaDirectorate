package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import org.lwjgl.util.vector.Vector2f

/**
 * 引力空间折跃器（舜华级 ZW-101 内置 Hullmod）的机制数值声明与纯函数。
 *
 * 动机：折跃概率（基础值 + 伤害加计 + 上限）、光束承伤减免的三锚点查值与镜像坐标
 * 计算集中在此声明，供 hullmod 每帧实时解析（LunaLib 设置变更即时生效），
 * 并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档），对照 GravPhaseDeckTuning 既有口径。
 */
object GravSpaceFoldTuning {

    /** 折跃判定半径（su）：舰船碰撞圈外扩本值，进入范围内的敌对弹体参与判定。 */
    const val FOLD_RANGE_BONUS = 200f

    /** 伤害加计区间下限：弹体伤害 ≤ 本值时取基础概率，不加计。 */
    const val DAMAGE_NO_BONUS_BELOW = 50f

    /** 伤害加计区间上限：弹体伤害 ≥ 本值时触及概率上限。 */
    const val DAMAGE_FULL_BONUS_AT = 500f

    /** 折跃基础概率（v1 40% / v2 50% / v5 75%，三锚点 LINEAR）。 */
    val FOLD_CHANCE_BASE = ScalingEntry(0.40f, 0.50f, 0.75f)

    /** 折跃概率上限（v1 65% / v2 75% / v5 95%，三锚点 LINEAR；伤害加计触及的封顶值）。 */
    val FOLD_CHANCE_CAP = ScalingEntry(0.65f, 0.75f, 0.95f)

    /** 光束承伤减免（v1 15% / v2 25% / v5 50%；最终乘区 = 1 - 本值）。 */
    val BEAM_DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.15f, 0.25f, 0.50f)

    /** 一次判定所需的全部机制数值（难度解析结果；beamDamageTakenMult 为最终乘区口径，直接可用）。 */
    data class Values(
        /** 折跃基础概率（0~1）。 */
        val foldChanceBase: Float,
        /** 折跃概率上限（0~1）。 */
        val foldChanceCap: Float,
        /** 光束承伤乘区（1 - 减免比例）。 */
        val beamDamageTakenMult: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        foldChanceBase = pick(tuning, isPlayer, FOLD_CHANCE_BASE),
        foldChanceCap = pick(tuning, isPlayer, FOLD_CHANCE_CAP),
        beamDamageTakenMult = 1f - pick(tuning, isPlayer, BEAM_DAMAGE_TAKEN_REDUCTION),
    )

    /**
     * 折跃概率合成（纯函数）：弹体伤害 ≤ [DAMAGE_NO_BONUS_BELOW] 取基础值，
     * ≥ [DAMAGE_FULL_BONUS_AT] 触及上限，中间按伤害线性加计。
     */
    fun foldChance(base: Float, cap: Float, damage: Float): Float {
        val t = ((damage - DAMAGE_NO_BONUS_BELOW) / (DAMAGE_FULL_BONUS_AT - DAMAGE_NO_BONUS_BELOW))
            .coerceIn(0f, 1f)
        return base + (cap - base) * t
    }

    /** 镜像折跃落点（纯函数）：newPos = 2 × 舰船中心 − 弹体位置（中心对称点）。 */
    fun mirroredPosition(center: Vector2f, pos: Vector2f): Vector2f =
        Vector2f(2f * center.x - pos.x, 2f * center.y - pos.y)

    /** 折跃判定标记：已折跃（终态，不再触发任何判定与特效）。 */
    const val MARK_FOLDED = "folded"

    /** 折跃判定标记：已判定但不折跃（终态）。 */
    const val MARK_NO_FOLD = "no_fold"

    /**
     * 折跃判定状态机（纯函数）：弹体 customData 标记三态流转——
     * null（未判定）→ [MARK_FOLDED] / [MARK_NO_FOLD]，两个终态均不再触发折跃。
     * 返回 (应写入的新标记, 本次是否执行折跃)；[existingMark] 非 null 时 [roll] 不会被消费。
     */
    fun resolveFold(existingMark: String?, roll: Float, chance: Float): Pair<String, Boolean> {
        if (existingMark != null) return existingMark to false
        return if (roll < chance) MARK_FOLDED to true else MARK_NO_FOLD to false
    }

    private fun pick(tuning: DifficultyTuning, isPlayer: Boolean, entry: ScalingEntry): Float =
        if (isPlayer) entry.v2 else tuning.value(entry)
}
