package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力电子干扰力场（决明级 ZW-001 内置 Hullmod，hullmod id：astd_grav_ew_field）
 * （purple/10-unique.md §1）的机制数值声明与纯函数。
 *
 * 接入原版电子战（ElectronicWarfareScript）的两条杠杆：
 *
 * 1. **最大效果提升**：原版每侧最大效果 = [VANILLA_MAX_EFFECT]（写死常量 BASE_MAXIMUM=10）
 *    + 该侧舰队指挥官 dynamic stat [EW_MAX_STAT]（取各指挥官最大值）。本力场对本侧全部指挥官的
 *    [EW_MAX_STAT] 写 flat 增量（+50%/+100%/+250% × 10 = +5/+10/+25），使最大效果达 15%/20%/35%。
 * 2. **附加五项减益**：原版效果仅为敌方射程削减。本力场复算原版公式得出本侧实际造成的
 *    EW 效果（[penaltyDealt]），按 实际效果/最大效果 的比例（[debuffScale]）对全部敌舰施加
 *    最高 [DEBUFF_FULL] 的减益（EMP 抗性/护盾效率/航速机动降低、系统冷却/充能时间提升）。
 *
 * 三锚点查值集中在此声明，供 hullmod 每帧实时解析（LunaLib 设置变更即时生效），
 * 并由单元测试直接驱动。玩家来源（owner == 0）按我方档位取值（默认砺刃 v2）。
 */
object GravEwFieldTuning {

    /** Hullmod 注册 id（csv/注册接线由装配侧完成）。 */
    const val HULLMOD_ID = "astd_grav_ew_field"

    /** 原版电子战最大效果基准（%，ElectronicWarfareScript.BASE_MAXIMUM 写死值，仅作换算基准）。 */
    const val VANILLA_MAX_EFFECT = 10f

    /** 原版阵营最大效果的指挥官 dynamic stat key（最大效果 = 10 + 各指挥官该值的最大值）。 */
    const val EW_MAX_STAT = "electronic_warfare_max"

    /** 原版每舰 ECM 贡献的 dynamic stat key（结算复算用）。 */
    const val ECM_FLAT_STAT = "electronic_warfare_flat"

    /** 原版每座传感阵列目标提供的 ECM（%，ElectronicWarfareScript.PER_JAMMER，结算复算用）。 */
    const val SENSOR_ARRAY_ECM = 5f

    /** 原版传感阵列目标类型 id（结算复算用）。 */
    const val SENSOR_ARRAY_TYPE = "sensor_array"

    /** 力场全部 stat 修饰句柄的统一前缀（按源舰 id 区分多力场源；跨战斗残留安全网按此前缀识别）。 */
    const val MOD_ID_PREFIX = "astd_grav_ew_field:"

    /** 我方电子战最大效果强度提升倍率（v1 +50% / v2 +100% / v5 +250%，三锚点 LINEAR）。 */
    val MAX_EFFECT_BOOST = ScalingEntry(0.5f, 1.0f, 2.5f)

    /** 五项附加减益的满额幅度（比例；按实际 EW 效果与最大效果的比例缩放，满值时达到该幅度）。 */
    const val DEBUFF_FULL = 0.10f

    /** 一次结算所需的全部机制数值（难度解析结果；最终口径，直接可用）。 */
    data class Values(
        /** 指挥官 [EW_MAX_STAT] 的 flat 增量（%；[VANILLA_MAX_EFFECT] + 该值 = 实际最大效果）。 */
        val maxEffectBonus: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）映射，其余阵营按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        maxEffectBonus = VANILLA_MAX_EFFECT * tuning.valueFor(MAX_EFFECT_BOOST, isPlayer),
    )

    /**
     * 复算原版电子战对本侧敌对方实际造成的射程削减（%，与 ElectronicWarfareScript 同公式）：
     * 原版 getTotalAndMaximum 以 int[] 返回（总额/上限先 int 截断），效果 =
     * min(本侧总额, 本侧上限)，双方均有 ECM 时按 本侧/(双方和) 折扣，最后 Math.round 半进位。
     */
    fun penaltyDealt(ownTotal: Float, ownMax: Float, enemyTotal: Float): Int {
        val total = ownTotal.toInt()
        val max = ownMax.toInt()
        val enemy = enemyTotal.toInt()
        var penalty = minOf(total, max).toFloat()
        if (enemy > 0 && penalty > 0f) penalty *= total.toFloat() / (enemy + total)
        return Math.round(penalty)
    }

    /** 附加减益缩放系数：实际效果 / 最大效果（0..1）；最大效果 ≤ 0（无指挥官）时归零。 */
    fun debuffScale(penalty: Int, max: Float): Float =
        if (max <= 0f) 0f else (penalty / max).coerceIn(0f, 1f)

    /** 从 stat 修饰来源集合中筛出本力场的句柄（跨战斗残留安全网按 [MOD_ID_PREFIX] 识别）。 */
    fun fieldModSources(sources: Collection<String>): List<String> =
        sources.filter { it.startsWith(MOD_ID_PREFIX) }
}
