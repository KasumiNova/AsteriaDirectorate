package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.econ.MarketImmigrationModifier
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition
import com.fs.starfarer.api.ui.TooltipMakerAPI

/**
 * 紫菀科研部遗址（轨道生活空间站「拾光」，docs/story/07 紫菀遗址星系）。
 *
 * 效果随难度系数缩放（k=1 下限 / k=5 上限）：
 * 流通性 +10%~+50%、舰队规模 +25%~+100%、人口增长 +5~+20。
 *
 * 人口增长经原版 `MarketImmigrationModifier` 机制实现（与 Habitable / LuddicMajority
 * 同一机制）：apply 时登记为瞬时移民修正，在迁入权重上挂平值加成。
 */
class StoryConditionAsterResearchRuins : StoryConditionBase(), MarketImmigrationModifier {

    override fun apply(id: String) {
        market.accessibilityMod.modifyFlat(id, scaled(ACCESS), conditionName)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT)
            .modifyMult(id, 1f + scaled(FLEET_SIZE), conditionName)
        market.addTransientImmigrationModifier(this)
    }

    override fun unapply(id: String) {
        market.removeTransientImmigrationModifier(this)
        market.accessibilityMod.unmodifyFlat(id)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(id)
    }

    override fun modifyIncoming(market: MarketAPI, incoming: PopulationComposition) {
        val growth = scaledInt(GROWTH)
        if (growth > 0) {
            incoming.weight.modifyFlat(modId, growth.toFloat(), condition.name)
        }
    }

    override fun createTooltipAfterDescription(tooltip: TooltipMakerAPI, expanded: Boolean) {
        addEffectLine(tooltip, "condition.common.access", "val" to formatPercent(scaled(ACCESS)))
        addEffectLine(tooltip, "condition.common.fleet_size", "val" to formatPercent(scaled(FLEET_SIZE)))
        addEffectLine(
            tooltip,
            "condition.aster_research_ruins.growth",
            "val" to formatSignedInt(scaledInt(GROWTH)),
        )
    }

    companion object {
        /** 流通性平值（0.1 = +10%）。 */
        val ACCESS = ScalingEntry(0.10f, 0.20f, 0.50f)

        /** 舰队规模乘区增量（0.25 = +25%）。 */
        val FLEET_SIZE = ScalingEntry(0.25f, 0.50f, 1.00f)

        /** 人口增长平值（迁入权重，整数语义）。 */
        val GROWTH = ScalingEntry(5f, 10f, 20f)
    }
}
