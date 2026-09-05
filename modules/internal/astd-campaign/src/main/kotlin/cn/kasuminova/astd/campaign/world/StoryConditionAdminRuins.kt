package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI

/**
 * 菀星行政部遗址（兰台，docs/story/03 序章主星系）。
 *
 * 效果随难度系数缩放（k=1 下限 / k=5 上限）：
 * 流通性 +10%~+50%、星球收入系数 +5%~+20%、稳定性 +1~+4、舰队规模 +25%~+100%。
 */
class StoryConditionAdminRuins : StoryConditionBase() {

    override fun apply(id: String) {
        market.accessibilityMod.modifyFlat(id, scaled(ACCESS), conditionName)
        market.incomeMult.modifyMult(id, 1f + scaled(INCOME), conditionName)
        market.stability.modifyFlat(id, scaledInt(STABILITY).toFloat(), conditionName)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT)
            .modifyMult(id, 1f + scaled(FLEET_SIZE), conditionName)
    }

    override fun unapply(id: String) {
        market.accessibilityMod.unmodifyFlat(id)
        market.incomeMult.unmodifyMult(id)
        market.stability.unmodify(id)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(id)
    }

    override fun createTooltipAfterDescription(tooltip: TooltipMakerAPI, expanded: Boolean) {
        addEffectLine(tooltip, "condition.common.access", "val" to formatPercent(scaled(ACCESS)))
        addEffectLine(tooltip, "condition.admin_ruins.income", "val" to formatPercent(scaled(INCOME)))
        addEffectLine(tooltip, "condition.admin_ruins.stability", "val" to formatSignedInt(scaledInt(STABILITY)))
        addEffectLine(tooltip, "condition.common.fleet_size", "val" to formatPercent(scaled(FLEET_SIZE)))
    }

    companion object {
        /** 流通性平值（StatBonus 平值，0.1 = +10%）。 */
        val ACCESS = ScalingEntry(0.10f, 0.20f, 0.50f)

        /** 收入系数乘区增量（0.05 = +5%）。 */
        val INCOME = ScalingEntry(0.05f, 0.10f, 0.20f)

        /** 稳定性平值（整数语义）。 */
        val STABILITY = ScalingEntry(1f, 2f, 4f)

        /** 舰队规模乘区增量（0.25 = +25%）。 */
        val FLEET_SIZE = ScalingEntry(0.25f, 0.50f, 1.00f)
    }
}
