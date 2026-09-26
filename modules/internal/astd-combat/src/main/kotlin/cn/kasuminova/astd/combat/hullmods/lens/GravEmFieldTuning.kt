package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcAuraUtil

/**
 * 引力电磁力场（密蒙级 ZW-002 内置 Hullmod，hullmod id：astd_grav_em_field）的机制数值声明与纯函数。
 *
 * 动机：力场四项难度缩放（影响范围 / EMP 抗性降低 / 航速机动射程削减与开火辐能增加共用锚点）
 * 与距离衰减曲线集中在此声明，供 hullmod 每帧实时解析（LunaLib 设置变更即时生效），
 * 并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档），对照 GravPhaseDeckTuning 既有口径。
 */
object GravEmFieldTuning {

    /** 力场影响范围（su，v1 1250 / v2 1500 / v5 2000，三锚点 LINEAR）。 */
    val RANGE = ScalingEntry(1250f, 1500f, 2000f)

    /** EMP 抗性绝对位移降低量（v1 0.25 / v2 0.50 / v5 0.90；写 stat 时按乘区反向换算）。 */
    val EMP_RESIST_REDUCTION = ScalingEntry(0.25f, 0.50f, 0.90f)

    /** 最大航速/机动性/武器射程削减与开火辐能增加共用锚点（v1 10% / v2 20% / v5 50%）。 */
    val STAT_PENALTY = ScalingEntry(0.10f, 0.20f, 0.50f)

    /** 满效半径占比：距离 ≤ 范围 × 该比例时效果最大（v2 口径即 ≤750su 满效）。 */
    const val FULL_EFFECT_FRACTION = 0.5f

    /** 影响范围边缘的最小效力（线性衰减终点，25%）。 */
    const val EDGE_SCALE = 0.25f

    /** 力场电弧视觉节拍（秒）：每隔该时长向随机方向发射一波紫色特效电弧。 */
    const val ARC_WAVE_INTERVAL = 0.5f

    /** 每波特效电弧数量区间。 */
    const val ARC_WAVE_COUNT_MIN = 5
    const val ARC_WAVE_COUNT_MAX = 10

    /** 一次力场结算所需的全部机制数值（难度解析结果；最终乘区口径，直接可用）。 */
    data class Values(
        /** 影响范围（su）。 */
        val range: Float,
        /** EMP 抗性绝对位移降低量（满效时）。 */
        val empResistReduction: Float,
        /** 航速/机动/射程削减比例与开火辐能增加比例（满效时，两者同值）。 */
        val statPenalty: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        range = pick(tuning, isPlayer, RANGE),
        empResistReduction = pick(tuning, isPlayer, EMP_RESIST_REDUCTION),
        statPenalty = pick(tuning, isPlayer, STAT_PENALTY),
    )

    /**
     * 距离衰减效力（纯函数）：距离 ≤ [range] × [FULL_EFFECT_FRACTION] 满效 1.0，
     * 到范围边缘线性衰减到 [EDGE_SCALE]，出范围 0。曲线本体复用 [ASTDArcAuraUtil.distanceFalloff]。
     */
    fun effectScale(distance: Float, range: Float): Float =
        ASTDArcAuraUtil.distanceFalloff(distance, range * FULL_EFFECT_FRACTION, range, EDGE_SCALE)

    private fun pick(tuning: DifficultyTuning, isPlayer: Boolean, entry: ScalingEntry): Float =
        if (isPlayer) entry.v2 else tuning.value(entry)
}
