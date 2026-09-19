package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 战机引力联结器（Fighter Gravity Link）的机制数值声明与纯函数
 * （purple/20-production.md §1，2026-09 重构）。
 *
 * 动机：飞蓬级（ZW-102）舰船系统——以原版召回装置为基线的增强：持续期间强化机群
 * （时流 + 减伤），结束时进入 1s 召回窗口（快照机群相位渐隐、窗口末 land 回收并快速
 * 重新出击）；代价为持续软辐能产出与结束时的软→硬辐能转化。三锚点查值与辐能折算
 * 集中在此声明，供系统脚本调用并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档）。
 */
object FighterGravLinkTuning {

    /** 战机时间流速加成（v1 +100% / v2 +150% / v5 +200%，三锚点 LINEAR）。 */
    val TIME_MULT_BONUS = ScalingEntry(1.0f, 1.5f, 2.0f)

    /** 战机受到所有伤害减免（v1 25% / v2 50% / v5 75%，三锚点 LINEAR）。 */
    val DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.25f, 0.5f, 0.75f)

    /** 激活期间软辐能产出速率：每秒产出舰船**基础**最大辐能的该比例（固定 7%，不随难度变化）。 */
    const val SOFT_FLUX_RATIO_OF_BASE_CAP = 0.07f

    /**
     * ACTIVE 持续时间上限（秒）。系统为 toggle 型（CSV active 为空 + toggle=true，可提前
     * 手动关闭），引擎不再自动结束 ACTIVE，故由系统脚本按该上限补发 useSystem() 收口
     * （fire 路径进 OUT；不能用 deactivate()——其直接跳 COOLDOWN、跳过 OUT 召回窗口）。
     */
    const val MAX_ACTIVE_SECONDS = 15f

    /** 机群全灭提前终止的宽限期（秒）：ACTIVE 开始该时长后才允许「无在外战机」提前结束。 */
    const val NO_FIGHTER_CANCEL_GRACE_SECONDS = 1f

    /** 一次激活所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 战机时流乘区（= 1 + 加成）。 */
        val timeMult: Float,
        /** 战机承伤乘区（= 1 − 减免）。 */
        val damageTakenMult: Float,
    )

    /**
     * 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。
     * 每帧调用（不缓存），保证 LunaLib 设置变更即时生效。
     */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        timeMult = 1f + if (isPlayer) TIME_MULT_BONUS.v2 else tuning.value(TIME_MULT_BONUS),
        damageTakenMult = 1f - if (isPlayer) DAMAGE_TAKEN_REDUCTION.v2 else tuning.value(DAMAGE_TAKEN_REDUCTION),
    )

    /** 软辐能产出速率（纯函数）：基础最大辐能 × [SOFT_FLUX_RATIO_OF_BASE_CAP]（su/s）。 */
    fun softFluxPerSecond(baseMaxFlux: Float): Float = baseMaxFlux * SOFT_FLUX_RATIO_OF_BASE_CAP

    /**
     * 当前软辐能量（纯函数）：当前辐能 − 硬辐能，下限 0（硬辐能恒 ≤ 当前辐能，
     * 0 下限仅防御浮点抖动）。
     */
    fun softFluxNow(currFlux: Float, hardFlux: Float): Float = (currFlux - hardFlux).coerceAtLeast(0f)
}
