package cn.kasuminova.astd.campaign.ending

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import java.awt.Color
import org.apache.log4j.Logger

/**
 * 归档三选效果的游戏侧挂载（势力变强 stat 修正 / 关系变动 / 延迟激活调度）。
 *
 * 实现路径与 API 依据：
 * - 势力变强 = 对该势力全部市场的 stat 幂等修正：
 *   `MarketAPI.getStats().getDynamic().getMod(Stats.*).modifyMult(sourceId, 1+pct, desc)`
 *   （FLEET_QUALITY_MOD / COMBAT_FLEET_SIZE_MULT / GROUND_DEFENSES_MOD），
 *   附稳定度 `MarketAPI.getStability().modifyFlat` 与可达性 `MarketAPI.getAccessibilityMod().modifyFlat`
 *   （后两项为提案口径：稳定度 +pct×4、可达性 +pct×0.2，13 文档未定案）；
 * - 幂等口径：StatBonus/MutableStat 的同 sourceId 重复 modify 即覆盖，
 *   [EndingCampaignManager] 周期重挂 [remountAll]（state.appliedStrengthPct 为事实源），
 *   读档/新市场出现/其它模组改动后自动恢复；
 * - 关系变动 = `FactionAPI.setRelationship("player", clamp(current+delta, -1, 1))`；
 * - 延迟激活时钟 = `CampaignClockAPI.getTimestamp()`（激活时刻在签署时由
 *   [EndingProgression.sign] 按 timestamp + DELAY_DAYS×secondsPerDay 预算）。
 */
object EndingEffects {

    private const val CAT = "asteria_directorate"

    /** 市场 stat 修正的 sourceId（同 id 重复挂载即覆盖，幂等口径）。 */
    const val MOD_ID: String = "astd_archive_fragment"

    private val log: Logger = Global.getLogger(EndingEffects::class.java)

    private val RECEIPT_COLOR = Color(200, 170, 120)

    /** 稳定度修正系数（提案口径：+pct×4；13 文档未定案）。 */
    private const val STABILITY_PER_PCT: Float = 4f

    /** 可达性修正系数（提案口径：+pct×0.2；13 文档未定案）。 */
    private const val ACCESSIBILITY_PER_PCT: Float = 0.2f

    /** 不参与强度修正的势力（玩家自身与无主权中立）。 */
    private val EXCLUDED_FACTIONS = setOf("player", "neutral")

    /**
     * 受影响的全体势力 id（有市场的主要势力，玩家/中立除外）。
     * 签署计划展开与重挂共用此集合口径。
     */
    fun affectedFactionIds(sector: SectorAPI): List<String> =
        sector.economy.marketsCopy
            .map { it.factionId }
            .filter { it !in EXCLUDED_FACTIONS }
            .distinct()
            .sorted()

    /**
     * 立即生效条目的挂载（签署时调用一次；周期重挂走 [remountAll]）。
     *
     * @param effects factionId → 幅度（如 0.25 = +25%）
     */
    fun applyImmediate(sector: SectorAPI, effects: Map<String, Float>) {
        if (effects.isEmpty()) return
        val desc = I18n[CAT, "ending.strength_mod_desc"]
        for (market in sector.economy.marketsCopy) {
            val pct = effects[market.factionId] ?: continue
            mountMarket(market, pct, desc)
        }
        log.info("[ASTD] 档案碎片强度修正已挂载：${effects.keys.joinToString()}")
    }

    /** 全量幂等重挂（[EndingCampaignManager] 周期调用；事实源 = state.appliedStrengthPct）。 */
    fun remountAll(sector: SectorAPI, state: BountyState) {
        if (state.appliedStrengthPct.isEmpty()) return
        applyImmediate(sector, state.appliedStrengthPct)
    }

    /**
     * 交易选的关系变动写入（clamp 到 [-1, 1]）。
     *
     * @param relationDeltas factionId → 变动量（对象 +0.3 / 其余 -0.05，13 文档未定案，提案值）
     */
    fun applyRelations(sector: SectorAPI, relationDeltas: Map<String, Float>) {
        for ((factionId, delta) in relationDeltas) {
            val faction = sector.getFaction(factionId)
            if (faction == null) {
                log.warn("[ASTD] 关系变动跳过：势力不存在（$factionId）")
                continue
            }
            val current = faction.getRelationship("player")
            faction.setRelationship("player", (current + delta).coerceIn(-1f, 1f))
        }
    }

    /** 单市场的强度修正挂载（舰队质量/战斗舰队规模/地面防御乘区 + 稳定度/可达性平区）。 */
    private fun mountMarket(market: MarketAPI, pct: Float, desc: String) {
        val dynamic = market.stats.dynamic
        dynamic.getMod(Stats.FLEET_QUALITY_MOD).modifyMult(MOD_ID, 1f + pct, desc)
        dynamic.getMod(Stats.COMBAT_FLEET_SIZE_MULT).modifyMult(MOD_ID, 1f + pct, desc)
        dynamic.getMod(Stats.GROUND_DEFENSES_MOD).modifyMult(MOD_ID, 1f + pct, desc)
        market.stability.modifyFlat(MOD_ID, pct * STABILITY_PER_PCT, desc)
        market.accessibilityMod.modifyFlat(MOD_ID, pct * ACCESSIBILITY_PER_PCT, desc)
    }
}

/**
 * 结局周期管理脚本（~10s 节流）：
 * - 到期激活延迟生效条目（[EndingProgression.activateDelayedEffects] → 挂载 + HUD 回执）；
 * - 全量幂等重挂已生效强度修正（读档/新市场/外部改动后自动恢复）。
 *
 * 节流裁定：全部工作均为幂等重挂/到期激活，时效不敏感（延迟条目以「日」为粒度），
 * 10s 周期足够且避免每帧统计开销。
 *
 * 注册：[cn.kasuminova.astd.campaign.bounty.BountyBootstrapper.onGameLoad]（memory key 去重）。
 */
class EndingCampaignManager : EveryFrameScript {

    private companion object {
        private const val CAT = "asteria_directorate"
        private val log: Logger = Global.getLogger(EndingCampaignManager::class.java)
        private val RECEIPT_COLOR = Color(200, 170, 120)

        /** 重挂/激活周期（秒）。 */
        private const val INTERVAL: Float = 10f
    }

    private var timer = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < INTERVAL) return
        timer = 0f

        val sector = Global.getSector() ?: return
        val state = BountyState.getOrCreate()
        if (state.archivalChoice == null) return

        // 延迟条目到期激活（势力灭国条目到期作废并记日志，不残留）
        val now = sector.clock.timestamp
        val activated = EndingProgression.activateDelayedEffects(state, now) { factionId ->
            val alive = sector.getFaction(factionId) != null &&
                sector.economy.marketsCopy.any { it.factionId == factionId }
            if (!alive) {
                log.info("[ASTD] 延迟强度条目到期作废：势力已无市场或不存在（$factionId）")
            }
            alive
        }
        if (activated.isNotEmpty()) {
            EndingEffects.applyImmediate(sector, state.appliedStrengthPct)
            for (effect in activated) {
                val factionName = sector.getFaction(effect.factionId)?.displayName ?: effect.factionId
                HudMessages.campaign(
                    I18n.t(CAT, "hud.ending.delayed_activated", "faction" to factionName),
                    RECEIPT_COLOR,
                )
                log.info("[ASTD] 延迟强度条目已激活：${effect.factionId} +${effect.pct}")
            }
        }

        // 周期幂等重挂（事实源 = appliedStrengthPct）
        EndingEffects.remountAll(sector, state)
    }
}
