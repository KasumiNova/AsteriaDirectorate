package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
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

/**
 * 4 个剧情市场状况的数值规格（纯函数层，难度系数缩放三锚点就地登记，可直接单测）。
 *
 * 锚点口径：v1 = k_s=1 时的克制下限、v5 = k_s=5 时的放开上限（03/07 文档区间端点）、
 * v2 = k_s=2 设计基准 = 区间在 k_s=2 处的线性中点。全部走默认分段线性映射
 * （均为平坦加成/线性属性，无超线性收益类型）。
 *
 * 效果集合：
 * - 菀星行政部遗址（兰台）：流通 +10~50%、收入 +5~20%、稳定 +1~4、舰队规模 +25~100%；
 * - 星坠工程部遗址（锻原）：流通 +5~25%、重工业产量 +2~6、舰队规模 +50~200%、
 *   地面防御 +200~800%、最大工业设施 +1~3；
 * - 视界动力（拾光）：最大工业设施 +2~4、建筑维护费 -15~75%、危险度 -10~50%
 *   （1500su 事件视界免疫由 [GravityNodeScripts] 的生涯层脚本承担，见 07 文档：两套机制独立）；
 * - 紫菀科研部遗址（拾光）：流通 +10~50%、舰队规模 +25~100%、移民权重 +5~20。
 */
object StoryConditionEffects {

    /** 菀星行政部遗址（兰台）的效果集合。 */
    data class WanxingAdmin(
        /** 流通性加成（0.10 = +10%）。 */
        val accessibility: Float,
        /** 星球收入系数加成（0.05 = +5%）。 */
        val income: Float,
        /** 稳定性加成。 */
        val stability: Float,
        /** 舰队规模加成（0.25 = +25%）。 */
        val fleetSize: Float,
    )

    /** 星坠工程部遗址（锻原）的效果集合。 */
    data class StarfallEngineering(
        val accessibility: Float,
        /** 重工业产量加成（固定值，单位：产量点）。 */
        val heavyIndustryOutput: Float,
        val fleetSize: Float,
        /** 地面防御加成（2.0 = +200%）。 */
        val groundDefenses: Float,
        /** 最大工业设施数量加成。 */
        val maxIndustries: Float,
    )

    /** 视界动力（拾光）的效果集合。 */
    data class EventHorizonPower(
        val maxIndustries: Float,
        /** 建筑维护费减免（0.15 = -15%）。 */
        val upkeepReduction: Float,
        /** 危险度减免（0.10 = -10%）。 */
        val hazardReduction: Float,
    )

    /** 紫菀科研部遗址（拾光）的效果集合。 */
    data class AsterResearch(
        val accessibility: Float,
        val fleetSize: Float,
        /** 移民权重加成（固定值）。 */
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

    /** 百分比格式化（+30% / -25%）。 */
    fun formatPercent(value: Float): String = "%+.0f%%".format(value * 100f)

    /** 固定值格式化（+2.5 / +4）。 */
    fun formatFlat(value: Float): String =
        if (value == value.toLong().toFloat()) "%+d".format(value.toLong()) else "%+.1f".format(value)
}

/** 剧情状况基类：统一 tooltip 渲染（CSV 描述 + 效果行 i18n 高亮）。 */
abstract class BaseStoryCondition : BaseMarketConditionPlugin() {

    /** 效果行（key → 参数），由子类在当前难度系数下求值。 */
    protected abstract fun effectLines(tuning: DifficultyTuning): List<Pair<String, List<Pair<String, Any?>>>>

    override fun createTooltip(tooltip: TooltipMakerAPI, expanded: Boolean) {
        super.createTooltip(tooltip, expanded)
        if (!expanded) return
        val pad = 5f
        for ((key, vars) in effectLines(DifficultyTuningImpl)) {
            I18nUi.addPara(tooltip, Categories.MOD, key, pad, Misc.getTextColor(), *vars.toTypedArray())
        }
    }

    override fun isTooltipExpandable(): Boolean = true
}

/**
 * 菀星行政部遗址（兰台，03 文档）。
 *
 * 承载市场：兰台为 condition-only 市场（[StorySystemSpecs.MarketSpec.conditionOnly]，读档后转
 * [com.fs.starfarer.api.impl.campaign.econ.PlanetConditionMarket] 的只读代理）。生效条件：
 * - [com.fs.starfarer.api.impl.campaign.econ.PlanetConditionMarket.getStats]/[getAccessibilityMod] 等
 *   在代理上均为可变的局部对象（stats 为持久字段、accessibilityMod 每次新建），`modify*` 调用合法、无副作用；
 * - 但代理不承载工业/经济，**流通/收入/稳定修正不会被任何经济消费方读取**，舰队规模动态模仅被
 *   「该市场出兵」读取——兰台无兵可出。即本状况在兰台当前形态下**叙事效果为主**；
 * - 若未来兰台转为可殖民（condition-only 关闭），状况随 `reapplyConditions` 在结构变化时重放、
 *   上述修正即在完整市场上真实生效。
 */
class WanxingAdminRuinsCondition : BaseStoryCondition() {

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

/**
 * 星坠工程部遗址（锻原，07 文档）。
 *
 * 承载市场：锻原为 condition-only 市场（不可殖民、无 industry，读档后转 PlanetConditionMarket 只读代理）。
 * **重工业产量加成的生效边界（07 文档口径：「市场一旦拥有重工业即生效」）：**
 * - 原版 [com.fs.starfarer.api.impl.campaign.econ.Market.reapplyConditions] 随市场结构变化重放
 *   （读档 addCondition 全量应用；稳定度变化触发 `advance` 内 reapply；每经济周期全体市场 reapply），
 *   因此「市场已有重工业时状况生效、结构变化时重新应用」在原版机制下本就成立；
 * - 锻原 condition-only 无 industry，`market.getIndustry(HEAVYINDUSTRY/ORBITALWORKS)` 恒为 null，
 *   加成**永不生效**——07 文档定稿为不可殖民，此效果保持「叙事效果为主」的现状即为正确口径；
 *   若未来锻原转为可殖民市场，同 [WanxingAdminRuinsCondition] 理由，状况在重放时对真实重工业生效；
 * - 其余 stat 修正（流通/舰队规模/地面防御/设施上限）同菀星：在代理上调用合法，但
 *   流通/出兵类不被读取、设施上限仅在完整市场被殖民地 UI 消费，叙事效果为主。
 */
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

/**
 * 视界动力（拾光，07 文档）。
 *
 * 承载市场：拾光为 FULL 空间站市场（[StorySystemSpecs.MarketSpec.inEconomy]，非 condition-only），
 * 效果**真实生效**：MAX_INDUSTRIES 设施上限 / upkeepMult 维护费 / hazard 危险度均由完整市场
 * 的殖民地 UI 与 `PopulationAndInfrastructure` 消费；状况随市场结构变化（含读档）重放、稳定度变化
 * 触发 reapply，数值随时刷新。
 *
 * 注意：1500su 事件视界免疫不在此处（市场状况无法逐帧追踪舰队），
 * 由生涯层脚本 [GravityNodeScripts.EventHorizonShieldScript] 承担。
 */
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

/**
 * 紫菀科研部遗址（拾光，07 文档）。
 *
 * 承载市场：拾光为 FULL 空间站市场（见 [EventHorizonPowerCondition]），效果**真实生效**：
 * 流通由经济系统按航路/连接读取（需空间站进经济）、舰队规模由市场守军/远征读取、
 * 移民权重以 [MarketImmigrationModifier] 挂接被人口增长消费。
 */
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
