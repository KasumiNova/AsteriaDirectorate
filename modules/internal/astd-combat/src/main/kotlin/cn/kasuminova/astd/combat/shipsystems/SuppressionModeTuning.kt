package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 压制模式（列星级 XC-103 舰船系统，hullmod id / 系统 id：astd_suppression_mode，
 * 规格 blue/20-production.md §驱逐舰-舰船系统）的机制数值声明与纯函数。
 *
 * 动机：压制模式四项难度缩放（航速机动削减 / 武器辐能减免 / 射程加成 / 护盾减伤共用
 * v1/v2/v5 锚点）与硬辐能产出曲线集中在此声明，供 stats 脚本每次 apply 实时解析
 * （LunaLib 设置变更即时生效），并由单元测试直接驱动。
 *
 * 数值口径：设计案给定的 v1/v2/v5 三锚点恰为线性步进（每档 +10%/+30%），登记 LINEAR
 * 无超线性收益（固定 8s 窗口的一次性乘区，不存在叠乘放大）。
 * 射速加成（恒定 +30%）与硬辐能产出曲线（2%→6%）不在设计案难度缩放点内，恒常数。
 *
 * 玩家来源（owner == 0）按我方档位取值（默认砺刃 v2，见 DifficultyTuning.valueFor）。
 */
object SuppressionModeTuning {

    /** 最大航速与机动性削减比例（v1 40% / v2 50% / v5 80%；最终乘区 = 1 - 本值）。 */
    val SPEED_MANEUVER_REDUCTION = ScalingEntry(0.40f, 0.50f, 0.80f)

    /** 武器辐能产出减免比例（v1 40% / v2 50% / v5 80%；最终乘区 = 1 - 本值）。 */
    val WEAPON_FLUX_REDUCTION = ScalingEntry(0.40f, 0.50f, 0.80f)

    /** 武器射程加成比例（v1 20% / v2 30% / v5 60%；写入射程乘区的百分值）。 */
    val RANGE_BONUS = ScalingEntry(0.20f, 0.30f, 0.60f)

    /** 护盾受到伤害减免比例（v1 20% / v2 30% / v5 60%；最终乘区 = 1 - 本值）。 */
    val SHIELD_DAMAGE_REDUCTION = ScalingEntry(0.20f, 0.30f, 0.60f)

    /** 武器射速加成（恒定 30%，不在设计案难度缩放点内；弹道/能量/导弹三通道同一乘区）。 */
    const val ROF_BONUS = 0.30f

    /** 硬辐能产出起始比例：激活第 0 秒每秒产出基础最大辐能的 2%。 */
    const val HARD_FLUX_FRACTION_START = 0.02f

    /** 硬辐能产出最高比例：第 [HARD_FLUX_RAMP_SECONDS] 秒起每秒产出基础最大辐能的 6%。 */
    const val HARD_FLUX_FRACTION_MAX = 0.06f

    /** 硬辐能产出爬坡时长（秒）：从起始比例线性爬升至最高比例。 */
    const val HARD_FLUX_RAMP_SECONDS = 4f

    /** 一次 apply 所需的全部机制数值（难度解析结果；满额口径，渐入渐出由 effectLevel 缩放）。 */
    data class Values(
        /** 最大航速/加减速/转向乘区（含基准 1）。 */
        val speedManeuverMult: Float,
        /** 武器辐能产出乘区（含基准 1；弹道/能量/导弹三通道同值）。 */
        val weaponFluxMult: Float,
        /** 武器射程加成百分值（0.3 = +30%；弹道/能量/光束/导弹同值）。 */
        val rangeBonusPercent: Float,
        /** 护盾承伤乘区（含基准 1）。 */
        val shieldDamageTakenMult: Float,
        /** 武器射速加成百分值（0.3 = +30%；恒定，不随难度缩放）。 */
        val rofBonusPercent: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）映射，其余阵营按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        speedManeuverMult = 1f - tuning.valueFor(SPEED_MANEUVER_REDUCTION, isPlayer),
        weaponFluxMult = 1f - tuning.valueFor(WEAPON_FLUX_REDUCTION, isPlayer),
        rangeBonusPercent = tuning.valueFor(RANGE_BONUS, isPlayer),
        shieldDamageTakenMult = 1f - tuning.valueFor(SHIELD_DAMAGE_REDUCTION, isPlayer),
        rofBonusPercent = ROF_BONUS,
    )

    /**
     * 硬辐能产出曲线（纯函数）：激活第 0 秒每秒产出基础最大辐能的 [HARD_FLUX_FRACTION_START]，
     * 线性爬坡，第 [HARD_FLUX_RAMP_SECONDS] 秒达到 [HARD_FLUX_FRACTION_MAX] 并保持。
     *
     * @param activeSeconds 本次激活已累计的系统开启时长（秒；负值按 0 计）
     * @return 当前每秒产出比例（相对舰船基础最大辐能）
     */
    fun hardFluxFractionPerSecond(activeSeconds: Float): Float {
        val progress = (activeSeconds.coerceAtLeast(0f) / HARD_FLUX_RAMP_SECONDS).coerceAtMost(1f)
        return HARD_FLUX_FRACTION_START + (HARD_FLUX_FRACTION_MAX - HARD_FLUX_FRACTION_START) * progress
    }
}
