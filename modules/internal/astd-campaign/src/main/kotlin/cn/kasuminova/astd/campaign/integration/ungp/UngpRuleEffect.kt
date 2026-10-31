package cn.kasuminova.astd.campaign.integration.ungp

import ungp.api.rules.UNGP_BaseRuleEffect
import ungp.scripts.campaign.specialist.UNGP_SpecialistSettings

/**
 * ASTD × UNGP 规则效果基类。
 *
 * UNGP 专家模式的规则由 `data/campaign/UNGP_rules.csv` 声明（effectPlugin 列指向实现类），
 * 仅在 UNGP 启用时被其反射实例化，因此本包可以安全地直接引用 ungp API 而不构成硬依赖。
 * 数值统一按 GAMMA/BETA/ALPHA/OMEGA 四档查表，不再使用 legacyLevel 线性插值。
 */
abstract class UngpRuleEffect : UNGP_BaseRuleEffect() {

    protected fun difficultyValue(
        difficulty: UNGP_SpecialistSettings.Difficulty,
        gamma: Float,
        beta: Float,
        alpha: Float,
        omega: Float,
    ): Float = when (difficulty) {
        UNGP_SpecialistSettings.Difficulty.GAMMA -> gamma
        UNGP_SpecialistSettings.Difficulty.BETA -> beta
        UNGP_SpecialistSettings.Difficulty.ALPHA -> alpha
        UNGP_SpecialistSettings.Difficulty.OMEGA -> omega
    }

    /** 数值文本：整数去小数点，避免 "300.0" 这类展示噪音。 */
    protected fun formatPlain(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    /** 0.04 -> "4%" */
    protected fun formatFraction(value: Float): String = getPercentString(value * 100f)
}
