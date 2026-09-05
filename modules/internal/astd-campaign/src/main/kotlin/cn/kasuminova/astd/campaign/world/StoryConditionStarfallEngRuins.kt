package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.campaign.econ.Industry
import com.fs.starfarer.api.impl.campaign.ids.Industries
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI

/**
 * 星坠工程部遗址（锻原，docs/story/07 星坠遗址星系）。
 *
 * 效果随难度系数缩放（k=1 下限 / k=5 上限）：
 * 流通性 +5%~+25%、重工业产量 +2~+6、舰队规模 +50%~+200%、
 * 地面防御 +200%~+800%、最大工业设施数量 +1~+3。
 *
 * 重工业产量经原版 `Industry.getSupplyBonusFromOther()` 钩子实现（与 SolarArray 同一机制）；
 * 市场当前没有重工业/轨道设施时加成休眠，输出一条 INFO 日志而非静默跳过。
 */
class StoryConditionStarfallEngRuins : StoryConditionBase() {

    private var appliedId: String = ""
    private var appliedIndustry: Industry? = null

    override fun apply(id: String) {
        market.accessibilityMod.modifyFlat(id, scaled(ACCESS), conditionName)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT)
            .modifyMult(id, 1f + scaled(FLEET_SIZE), conditionName)
        market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD)
            .modifyMult(id, 1f + scaled(GROUND_DEFENSE), conditionName)
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES)
            .modifyFlat(id, scaledInt(MAX_INDUSTRIES).toFloat(), conditionName)
        appliedId = id
        refreshHeavyIndustryProduction(logWhenMissing = true)
    }

    override fun advance(amount: Float) {
        super.advance(amount)
        refreshHeavyIndustryProduction(logWhenMissing = false)
    }

    override fun unapply(id: String) {
        market.accessibilityMod.unmodifyFlat(id)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(id)
        market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(id)
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).unmodifyFlat(id)
        appliedIndustry?.supplyBonusFromOther?.unmodifyFlat(id)
        if (heavyIndustry() !== appliedIndustry) {
            heavyIndustry()?.supplyBonusFromOther?.unmodifyFlat(id)
        }
        appliedIndustry = null
        appliedId = ""
    }

    override fun createTooltipAfterDescription(tooltip: TooltipMakerAPI, expanded: Boolean) {
        addEffectLine(tooltip, "condition.common.access", "val" to formatPercent(scaled(ACCESS)))
        addEffectLine(
            tooltip,
            "condition.starfall_eng_ruins.heavy_production",
            "val" to formatSignedInt(scaledInt(HEAVY_PRODUCTION)),
        )
        addEffectLine(tooltip, "condition.common.fleet_size", "val" to formatPercent(scaled(FLEET_SIZE)))
        addEffectLine(
            tooltip,
            "condition.starfall_eng_ruins.ground_defense",
            "val" to formatPercent(scaled(GROUND_DEFENSE)),
        )
        addEffectLine(
            tooltip,
            "condition.common.max_industries",
            "val" to formatSignedInt(scaledInt(MAX_INDUSTRIES)),
        )
    }

    private fun refreshHeavyIndustryProduction(logWhenMissing: Boolean) {
        val industry = heavyIndustry()
        if (industry === appliedIndustry) return

        appliedIndustry?.supplyBonusFromOther?.unmodifyFlat(appliedId)
        appliedIndustry = industry
        if (industry == null) {
            if (logWhenMissing) {
                AstdLog.logger.info(
                    "[ASTD] 星坠工程部遗址：市场 ${market.id} 当前无重工业设施，产量加成等待设施建成。",
                )
            }
            return
        }
        industry.supplyBonusFromOther.modifyFlat(
            appliedId,
            scaledInt(HEAVY_PRODUCTION).toFloat(),
            conditionName,
        )
    }

    private fun heavyIndustry(): Industry? =
        market.getIndustry(Industries.HEAVYINDUSTRY) ?: market.getIndustry(Industries.ORBITALWORKS)

    companion object {
        /** 流通性平值（0.05 = +5%）。 */
        val ACCESS = ScalingEntry(0.05f, 0.10f, 0.25f)

        /** 重工业产量平值（单位：商品单位，整数语义）。 */
        val HEAVY_PRODUCTION = ScalingEntry(2f, 3f, 6f)

        /** 舰队规模乘区增量（0.5 = +50%）。 */
        val FLEET_SIZE = ScalingEntry(0.50f, 1.00f, 2.00f)

        /** 地面防御乘区增量（2.0 = +200%）。 */
        val GROUND_DEFENSE = ScalingEntry(2.00f, 4.00f, 8.00f)

        /** 最大工业设施数量平值（整数语义）。 */
        val MAX_INDUSTRIES = ScalingEntry(1f, 2f, 3f)
    }
}
