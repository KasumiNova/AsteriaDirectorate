package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyCampaignManager
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats

/**
 * 归档三选的势力变强结算（13 文档「势力变强机制」）。
 *
 * 数值口径（提案值，13 文档定案幅度）：
 * - 公开：全体主要势力 +25%，立即；
 * - 封存：全体主要势力 +12%，延迟 2 周期（[EndingKeys.CYCLE_DAYS] 裁定 1 周期 = 30 天）；
 * - 交易：对象势力 +50% 立即，其余 +10% 延迟 1 周期。
 *
 * 「全体势力」裁定为主要在编势力集合 [BountyCampaignManager.TRADEABLE_FACTIONS]
 * （与交易候选列表同口径：星区主要势力，余晖等非在编自动智能不在其列）。
 *
 * 实现通道（裁定）：doctrine 的舰队质量参数为整型档位，无法承载 10%~50% 的连续百分比，
 * 统一走市场动态参数三通道——
 * 1. 舰队实力：巡逻/舰队规模倍率 [Stats.COMBAT_FLEET_SIZE_MULT] × (1 + 增幅)；
 * 2. 经济：市场可达性 +（增幅 × [ACCESSIBILITY_SCALE]）百分点；
 * 3. 防御：地面防御倍率 [Stats.GROUND_DEFENSES_MOD] × (1 + 增幅)。
 *
 * 修正以固定来源 id 幂等挂载（重挂前先 unmodify），重复结算/读档不叠乘。
 */
object ArchiveBoost {

    const val PUBLIC_BONUS: Float = 0.25f
    const val SEALED_BONUS: Float = 0.12f
    const val TRADED_TARGET_BONUS: Float = 0.50f
    const val TRADED_OTHERS_BONUS: Float = 0.10f

    const val SEALED_DELAY_CYCLES: Int = 2
    const val TRADED_DELAY_CYCLES: Int = 1

    /** 可达性换算：强度百分比 → 可达性百分点（+25% 强度 = +12.5pp 可达性）。 */
    const val ACCESSIBILITY_SCALE: Float = 0.5f

    /**
     * 一份归档后果计划：立即档与延迟档各自的作用势力 → 增幅，以及延迟周期数（0 = 无延迟档）。
     * 立即档与延迟档的势力集合按构造不重叠。
     */
    class Plan(
        val immediate: Map<String, Float>,
        val delayed: Map<String, Float>,
        val delayedCycles: Int,
    )

    /**
     * 按归档选择计算后果计划（纯函数，不触碰游戏状态）。
     *
     * @return 未知选择或交易选缺少合法对象时返回 null，由调用方记录错误日志
     */
    fun computePlan(choice: String, tradeFactionId: String?): Plan? {
        val majors = BountyCampaignManager.TRADEABLE_FACTIONS
        return when (choice) {
            BountyCampaignManager.ARCHIVE_PUBLIC ->
                Plan(majors.associateWith { PUBLIC_BONUS }, emptyMap(), 0)

            BountyCampaignManager.ARCHIVE_SEALED ->
                Plan(emptyMap(), majors.associateWith { SEALED_BONUS }, SEALED_DELAY_CYCLES)

            BountyCampaignManager.ARCHIVE_TRADED -> {
                if (tradeFactionId == null || tradeFactionId !in majors) return null
                Plan(
                    mapOf(tradeFactionId to TRADED_TARGET_BONUS),
                    majors.filter { it != tradeFactionId }.associateWith { TRADED_OTHERS_BONUS },
                    TRADED_DELAY_CYCLES,
                )
            }

            else -> null
        }
    }

    /**
     * 当前应当处于激活状态的增幅集合（立即档按签署激活、延迟档按到期激活）。
     * 未签署归档或选择非法时为空集。
     */
    fun activeBoosts(state: BountyState, ending: EndingState): Map<String, Float> {
        if (!state.infiniteContractor) return emptyMap()
        val plan = computePlan(state.archiveChoice, state.archiveTradeFactionId) ?: return emptyMap()
        val out = LinkedHashMap<String, Float>()
        if (ending.archiveImmediateApplied) out.putAll(plan.immediate)
        if (ending.archiveDelayedApplied) out.putAll(plan.delayed)
        return out
    }

    /** 把增幅三通道修正挂载到市场（幂等：同来源 id 覆盖旧值）。 */
    fun applyToMarket(market: MarketAPI, bonus: Float) {
        val label = I18n[EndingKeys.I18N_CATEGORY, "boost.label"]
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT)
            .modifyMult(EndingKeys.BOOST_MOD_ID, 1f + bonus, label)
        market.accessibilityMod
            .modifyFlat(EndingKeys.BOOST_MOD_ID, bonus * ACCESSIBILITY_SCALE, label)
        market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD)
            .modifyMult(EndingKeys.BOOST_MOD_ID, 1f + bonus, label)
    }

    /** 解除本系统在市场上的全部修正（未挂载时为空操作）。 */
    fun clearFromMarket(market: MarketAPI) {
        market.stats.dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(EndingKeys.BOOST_MOD_ID)
        market.accessibilityMod.unmodifyFlat(EndingKeys.BOOST_MOD_ID)
        market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(EndingKeys.BOOST_MOD_ID)
    }
}
