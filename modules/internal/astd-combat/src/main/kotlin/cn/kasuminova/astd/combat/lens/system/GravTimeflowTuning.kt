package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力时流干涉器（Gravity Timeflow Interference）——决明级（ZW-001）舰船系统
 * （purple/10-unique.md §1）的机制数值声明。
 *
 * 选取一名锁定的友军舰船，持续期间令其与其所属舰载机的时间流速提升，
 * 并在激活瞬间立即缩减目标舰船系统的剩余冷却时间；自身时间流速不变，
 * 持续期间目标驶出射程不会中断干涉。
 * 三锚点查值集中在此声明，供系统脚本每帧实时解析（LunaLib 设置变更即时生效）。
 *
 * 玩家来源（owner == 0）按我方档位取值（默认砺刃 v2，见 DifficultyTuning.valueFor）。
 */
object GravTimeflowTuning {

    /** 系统注册 id（csv/.system 接线由装配侧完成）。 */
    const val SYSTEM_ID = "astd_grav_timeflow_interference"

    /** 目标选择基础射程（su），受舰船 mutableStats.systemRangeBonus 加成折算。 */
    const val BASE_RANGE = 2000f

    /** 持续时间（秒，文档口径；实际生效值以 ship_systems.csv 为准）。 */
    const val ACTIVE_SECONDS = 10f

    /** 系统冷却（秒，文档口径；实际生效值以 ship_systems.csv 为准）。 */
    const val COOLDOWN_SECONDS = 30f

    /** 目标与所属战机的时间流速乘区（v1 150% / v2 200% / v5 350%，三锚点 LINEAR）。 */
    val TIME_MULT = ScalingEntry(1.5f, 2.0f, 3.5f)

    /** 激活瞬间缩减目标舰船系统剩余冷却的比例（v1 50% / v2 60% / v5 90%，三锚点 LINEAR）。 */
    val SYSTEM_COOLDOWN_REDUCTION = ScalingEntry(0.5f, 0.6f, 0.9f)

    /** 一次激活所需的全部机制数值（难度解析结果；最终口径，直接可用）。 */
    data class Values(
        /** 目标与所属战机的时流乘区（写 stat 时按 effectLevel 渐入：1 + (该值 − 1) × effectLevel）。 */
        val timeMult: Float,
        /** 激活瞬间缩减目标系统剩余冷却的比例（0-1）。 */
        val cooldownReduction: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）映射，其余阵营按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        timeMult = tuning.valueFor(TIME_MULT, isPlayer),
        cooldownReduction = tuning.valueFor(SYSTEM_COOLDOWN_REDUCTION, isPlayer),
    )
}
