package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import kotlin.math.roundToInt

/**
 * 维度折叠甲板（Dimensional Folding Deck）的机制数值声明与纯函数（purple/20-production.md §1，2026-09 重构）。
 *
 * 动机：飞蓬级（ZW-102）内置船插——以 +50% 战机 LPC 装配点为代价，按难度系数扩大每个
 * 飞行甲板的联队战机数量。三锚点查值与联队规模折算集中在此声明，供 HullMod 调用并由
 * 单元测试直接驱动。
 *
 * 联队规模实现路径（原版源码调研结论）：引擎不存在「单舰联队规模」的 stat/dynamic 钩子
 * （`FighterWingSpec.numFighters` 为全局共享 spec，不可按舰修改），唯一按甲板生效的扩容
 * 通道是 `FighterLaunchBayAPI` 的 extraDeployments 体系（`extraDeploymentLimit` 直接参与
 * 联队成员上限判定）。`extraDuration` 设为与原生战机一致的 1e7（≈ 永久），使额外编制
 * 与原生战机行为完全一致（不会因整备倒计时归零而强制返航）。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档）。
 */
object FoldingDeckTuning {

    /** 战机 LPC 装配点乘区（固定 +50%，不随难度系数变化，设计案原文）。 */
    const val OP_COST_MULT = 1.5f

    /** 每甲板联队战机数量加成（v1 +50% / v2 +150% / v5 +250%，三锚点 LINEAR）。 */
    val WING_SIZE_BONUS = ScalingEntry(0.5f, 1.5f, 2.5f)

    /** 额外编制战机的整备倒计时（与原生战机默认值 1e7 一致，≈ 永久在场）。 */
    const val EXTRA_FIGHTER_DURATION = 1e7f

    /**
     * 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。
     * 每次 advance 调用（不缓存），保证 LunaLib 设置变更即时生效。
     */
    fun resolveWingSizeMult(tuning: DifficultyTuning, isPlayer: Boolean): Float =
        if (isPlayer) WING_SIZE_BONUS.v2 else tuning.value(WING_SIZE_BONUS)

    /**
     * 联队规模上限折算（纯函数）：基础编制 × (1 + 加成) 四舍五入；加成失效（≤ 0）时
     * 恒返回基础编制，下限为基础编制本身（永不缩编）。
     */
    fun wingSizeLimit(baseNum: Int, bonusMult: Float): Int =
        (baseNum * (1f + bonusMult)).roundToInt().coerceAtLeast(baseNum)
}
