package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n.Categories
import cn.kasuminova.astd.internal.i18n.I18nUi
import com.fs.starfarer.api.campaign.econ.Industry
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.econ.MarketImmigrationModifier
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.impl.campaign.ids.Industries
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc

object StoryConditionEffects {

    data class WanxingAdmin(
        val accessibility: Float,
        val income: Float,
        val stability: Float,
        val fleetSize: Float,
    )

    data class StarfallEngineering(
        val accessibility: Float,
        val heavyIndustryOutput: Float,
        val fleetSize: Float,
        val groundDefenses: Float,
        val maxIndustries: Float,
    )

    data class EventHorizonPower(
        val maxIndustries: Float,
        val upkeepReduction: Float,
        val hazardReduction: Float,
    )

    data class AsterResearch(
        val accessibility: Float,
        val fleetSize: Float,
        val immigrationWeight: Float,
    )

    fun wanxingAdmin(tuning: DifficultyTuning): WanxingAdmin = WanxingAdmin(
        accessibility = tuning.value(ScalingEntry(0.10f, 0.20f, 0.50f)),
        income = tuning.value(ScalingEntry(0.05f, 0.0875f, 0.20f)),
        stability = tuning.value(ScalingEntry(1f, 1.75f, 4f)),
        fleetSize = tuning.value(ScalingEntry(0.25f, 0.4375f, 1.00f)),
    )

    fun starfallEngineering(tuning: DifficultyTuning): StarfallEngineering = StarfallEngineering(
        accessibility = tuning.value(ScalingEntry(0.05f, 0.10f, 0.25f)),
        heavyIndustryOutput = tuning.value(ScalingEntry(2f, 3f, 6f)),
        fleetSize = tuning.value(ScalingEntry(0.50f, 0.875f, 2.00f)),
        groundDefenses = tuning.value(ScalingEntry(2.00f, 3.50f, 8.00f)),
        maxIndustries = tuning.value(ScalingEntry(1f, 1.5f, 3f)),
    )

    fun eventHorizonPower(tuning: DifficultyTuning): EventHorizonPower = EventHorizonPower(
        maxIndustries = tuning.value(ScalingEntry(2f, 2.5f, 4f)),
        upkeepReduction = tuning.value(ScalingEntry(0.15f, 0.30f, 0.75f)),
        hazardReduction = tuning.value(ScalingEntry(0.10f, 0.20f, 0.50f)),
    )

    fun asterResearch(tuning: DifficultyTuning): AsterResearch = AsterResearch(
        accessibility = tuning.value(ScalingEntry(0.10f, 0.20f, 0.50f)),
        fleetSize = tuning.value(ScalingEntry(0.25f, 0.4375f, 1.00f)),
        immigrationWeight = tuning.value(ScalingEntry(5f, 8.75f, 20f)),
    )

    fun formatPercent(value: Float): String = "%+.0f%%".format(value * 100f)

    fun formatFlat(value: Float): String =
        if (value == value.toLong().toFloat()) "%+d".format(value.toLong()) else "%+.1f".format(value)
}

abstract class BaseStoryCondition : BaseMarketConditionPlugin() {

    protected abstract fun effectLines(tuning: DifficultyTuning): List<Pair<String, List<Pair<String, Any?>>>>

    protected open val showEffectsByDefault: Boolean = false

    override fun createTooltip(tooltip: TooltipMakerAPI, expanded: Boolean) {
        super.createTooltip(tooltip, expanded)
        if (!expanded && !showEffectsByDefault) return
        val pad = 5f
        for ((key, vars) in effectLines(DifficultyTuningImpl)) {
            I18nUi.addPara(tooltip, Categories.MOD, key, pad, Misc.getTextColor(), *vars.toTypedArray())
        }
    }

    override fun isTooltipExpandable(): Boolean = !showEffectsByDefault
}

class WanxingAdminRuinsCondition : BaseStoryCondition() {

    override val showEffectsByDefault: Boolean = true

    override fun effectLines(tuning: DifficultyTuning): List<Pair<String, List<Pair<String, Any?>>>> {
        val e = StoryConditionEffects.wanxingAdmin(tuning)
        return listOf(
            "world.condition.effects.accessibility" to listOf("v" to StoryConditionEffects.formatPercent(e.accessibility)),
            "world.condition.effects.income" to listOf("v" to StoryConditionEffects.formatPercent(e.income)),
            "world.condition.effects.stability" to listOf("v" to StoryConditionEffects.formatFlat(e.stability)),
            "world.condition.effects.fleet_size" to listOf("v" to StoryConditionEffects.formatPercent(e.fleetSize)),
        )
    }

    override fun apply(id: String) {
        super.apply(id)
        val e = StoryConditionEffects.wanxingAdmin(DifficultyTuningImpl)
        val name = condition.name
        market.accessibilityMod.modifyFlat(id, e.accessibility, name)
        market.incomeMult.modifyMult(id, 1f + e.income, name)
        market.stability.modifyFlat(id, e.stability, name)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).modifyMult(id, 1f + e.fleetSize, name)
    }

    override fun unapply(id: String) {
        super.unapply(id)
        market.accessibilityMod.unmodifyFlat(id)
        market.incomeMult.unmodifyMult(id)
        market.stability.unmodifyFlat(id)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(id)
    }
}

class StarfallEngineeringRuinsCondition : BaseStoryCondition() {

    override fun effectLines(tuning: DifficultyTuning): List<Pair<String, List<Pair<String, Any?>>>> {
        val e = StoryConditionEffects.starfallEngineering(tuning)
        return listOf(
            "world.condition.effects.accessibility" to listOf("v" to StoryConditionEffects.formatPercent(e.accessibility)),
            "world.condition.effects.heavy_industry" to listOf("v" to StoryConditionEffects.formatFlat(e.heavyIndustryOutput)),
            "world.condition.effects.fleet_size" to listOf("v" to StoryConditionEffects.formatPercent(e.fleetSize)),
            "world.condition.effects.ground_defenses" to listOf("v" to StoryConditionEffects.formatPercent(e.groundDefenses)),
            "world.condition.effects.max_industries" to listOf("v" to StoryConditionEffects.formatFlat(e.maxIndustries)),
        )
    }

    override fun apply(id: String) {
        super.apply(id)
        val e = StoryConditionEffects.starfallEngineering(DifficultyTuningImpl)
        val name = condition.name
        market.accessibilityMod.modifyFlat(id, e.accessibility, name)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).modifyMult(id, 1f + e.fleetSize, name)
        market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD).modifyMult(id, 1f + e.groundDefenses, name)
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).modifyFlat(id, e.maxIndustries, name)

        val heavyIndustry = market.getIndustry(Industries.HEAVYINDUSTRY)
            ?: market.getIndustry(Industries.ORBITALWORKS)
        if (heavyIndustry != null && heavyIndustry.isFunctional) {
            val qty = e.heavyIndustryOutput.toInt()
            heavyIndustry.supply("${id}_supplies", Commodities.SUPPLIES, qty, name)
            heavyIndustry.supply("${id}_machinery", Commodities.HEAVY_MACHINERY, qty, name)
            heavyIndustry.supply("${id}_ships", Commodities.SHIPS, qty, name)
        }
    }

    override fun unapply(id: String) {
        super.unapply(id)
        market.accessibilityMod.unmodifyFlat(id)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(id)
        market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(id)
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).unmodifyFlat(id)
        unmodifyHeavyIndustry(id, market.getIndustry(Industries.HEAVYINDUSTRY))
        unmodifyHeavyIndustry(id, market.getIndustry(Industries.ORBITALWORKS))
    }

    private fun unmodifyHeavyIndustry(id: String, industry: Industry?) {
        if (industry == null) return
        industry.getSupply(Commodities.SUPPLIES)?.quantity?.unmodifyFlat("${id}_supplies")
        industry.getSupply(Commodities.HEAVY_MACHINERY)?.quantity?.unmodifyFlat("${id}_machinery")
        industry.getSupply(Commodities.SHIPS)?.quantity?.unmodifyFlat("${id}_ships")
    }
}

class EventHorizonPowerCondition : BaseStoryCondition() {

    override fun effectLines(tuning: DifficultyTuning): List<Pair<String, List<Pair<String, Any?>>>> {
        val e = StoryConditionEffects.eventHorizonPower(tuning)
        return listOf(
            "world.condition.effects.max_industries" to listOf("v" to StoryConditionEffects.formatFlat(e.maxIndustries)),
            "world.condition.effects.upkeep" to listOf("v" to StoryConditionEffects.formatPercent(-e.upkeepReduction)),
            "world.condition.effects.hazard" to listOf("v" to StoryConditionEffects.formatPercent(-e.hazardReduction)),
            "world.condition.effects.event_horizon_shield" to listOf(
                "range" to StoryWorldIds.EVENT_HORIZON_IMMUNITY_RADIUS.toInt(),
            ),
        )
    }

    override fun apply(id: String) {
        super.apply(id)
        val e = StoryConditionEffects.eventHorizonPower(DifficultyTuningImpl)
        val name = condition.name
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).modifyFlat(id, e.maxIndustries, name)
        market.upkeepMult.modifyMult(id, 1f - e.upkeepReduction, name)
        market.hazard.modifyMult(id, 1f - e.hazardReduction, name)
    }

    override fun unapply(id: String) {
        super.unapply(id)
        market.stats.dynamic.getMod(Stats.MAX_INDUSTRIES).unmodifyFlat(id)
        market.upkeepMult.unmodifyMult(id)
        market.hazard.unmodifyMult(id)
    }
}

class AsterResearchRuinsCondition : BaseStoryCondition(), MarketImmigrationModifier {

    override fun effectLines(tuning: DifficultyTuning): List<Pair<String, List<Pair<String, Any?>>>> {
        val e = StoryConditionEffects.asterResearch(tuning)
        return listOf(
            "world.condition.effects.accessibility" to listOf("v" to StoryConditionEffects.formatPercent(e.accessibility)),
            "world.condition.effects.fleet_size" to listOf("v" to StoryConditionEffects.formatPercent(e.fleetSize)),
            "world.condition.effects.immigration" to listOf("v" to StoryConditionEffects.formatFlat(e.immigrationWeight)),
        )
    }

    override fun apply(id: String) {
        super.apply(id)
        val e = StoryConditionEffects.asterResearch(DifficultyTuningImpl)
        val name = condition.name
        market.accessibilityMod.modifyFlat(id, e.accessibility, name)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).modifyMult(id, 1f + e.fleetSize, name)
        market.addImmigrationModifier(this)
    }

    override fun unapply(id: String) {
        super.unapply(id)
        market.accessibilityMod.unmodifyFlat(id)
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(id)
        market.removeImmigrationModifier(this)
    }

    override fun modifyIncoming(market: MarketAPI, incoming: PopulationComposition) {
        val e = StoryConditionEffects.asterResearch(DifficultyTuningImpl)
        incoming.add(market.factionId, e.immigrationWeight)
    }
}
