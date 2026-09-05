package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import org.apache.log4j.Logger
import java.awt.Color

/**
 * 终局运行时脚本（约 1s 一跳）：第五章归档后果与「执行官」被动的持续挂载。
 *
 * 职责：
 * - 归档延迟档到点激活（封存 2 周期 / 交易其余势力 1 周期，见 [EndingKeys.CYCLE_DAYS] 裁定）；
 * - 每 tick 幂等重挂归档增幅的市场修正（[ArchiveBoost]），覆盖市场统计重建等场景；
 * - 按已签发的「执行官」特化维护被动：
 *   - 战斗特化：对指挥舰（[EndingState.commandShipId]，默认旗舰）挂载军官基线等效修正，
 *     修正挂在 FleetMember 统计上并随其带入战斗；
 *   - 行政特化：对全部玩家殖民地挂载稳定度/可达性/地面防御被动。
 *
 * 接线：由剧情引导侧在 onGameLoad 时调用 [ensureAdded]；归档签署时
 * [EndingSettlement.onArchiveSigned] 也会确保挂载，签署即可生效。
 */
class EndingRuntimeScript : EveryFrameScript {

    companion object {
        private val log: Logger = Global.getLogger(EndingRuntimeScript::class.java)
        private val RECEIPT_COLOR = Color(200, 170, 120)

        /** 经 sector memory key 去重挂载（新档/读档同路径）。 */
        @JvmStatic
        fun ensureAdded(sector: SectorAPI) {
            val mem = sector.memoryWithoutUpdate
            if (mem.getBoolean(EndingKeys.MEMORY_ENDING_RUNTIME_ADDED)) return
            sector.addScript(EndingRuntimeScript())
            mem.set(EndingKeys.MEMORY_ENDING_RUNTIME_ADDED, true)
        }

        /**
         * 解析战斗特化的当前指挥舰：优先 [EndingState.commandShipId] 指定的在队成员，
         * 未指定或目标已离队时回落玩家旗舰；无旗舰/无成员时返回 null。
         */
        @JvmStatic
        fun resolveCommandShip(fleet: CampaignFleetAPI, preferredId: String): FleetMemberAPI? {
            val members = fleet.fleetData.membersListCopy.filter { !it.isFighterWing }
            if (preferredId.isNotEmpty()) {
                members.firstOrNull { it.id == preferredId }?.let { return it }
            }
            return fleet.flagship ?: members.firstOrNull()
        }

        /**
         * 战斗特化修正包（公开 Alpha 军官技能基线等效，数值逐项取自原版技能）：
         * 操舰（Helmsmanship）机动 +50% / 极速 +15%；火控植入（Gunnery Implants）实弹与能量射程 +15%；
         * 战斗耐力（Combat Endurance）峰值时间 +60s / CR 上限 +15%。
         */
        @JvmStatic
        fun applyCommandShipMods(member: FleetMemberAPI) {
            val stats = member.stats
            val id = EndingKeys.COMMAND_MOD_ID
            val label = I18n[EndingKeys.I18N_CATEGORY, "core.command.label"]
            stats.maxSpeed.modifyPercent(id, 15f)
            stats.acceleration.modifyPercent(id, 50f)
            stats.deceleration.modifyPercent(id, 50f)
            stats.maxTurnRate.modifyPercent(id, 50f)
            stats.turnAcceleration.modifyPercent(id, 100f)
            stats.ballisticWeaponRangeBonus.modifyPercent(id, 15f)
            stats.energyWeaponRangeBonus.modifyPercent(id, 15f)
            stats.peakCRDuration.modifyFlat(id, 60f)
            stats.maxCombatReadiness.modifyFlat(id, 0.15f, label)
        }

        /** 解除指挥舰修正（未挂载时为空操作）。 */
        @JvmStatic
        fun clearCommandShipMods(member: FleetMemberAPI) {
            val stats = member.stats
            val id = EndingKeys.COMMAND_MOD_ID
            stats.maxSpeed.unmodifyPercent(id)
            stats.acceleration.unmodifyPercent(id)
            stats.deceleration.unmodifyPercent(id)
            stats.maxTurnRate.unmodifyPercent(id)
            stats.turnAcceleration.unmodifyPercent(id)
            stats.ballisticWeaponRangeBonus.unmodifyPercent(id)
            stats.energyWeaponRangeBonus.unmodifyPercent(id)
            stats.peakCRDuration.unmodifyFlat(id)
            stats.maxCombatReadiness.unmodifyFlat(id)
        }

        /**
         * 行政特化殖民地被动（裁定：A 级行政官基线等效）：
         * 稳定度 +1、可达性 +10pp、地面防御 ×1.25。
         */
        @JvmStatic
        fun applyAdminMods(market: MarketAPI) {
            val label = I18n[EndingKeys.I18N_CATEGORY, "core.admin.label"]
            market.stability.modifyFlat(EndingKeys.ADMIN_MOD_ID, 1f, label)
            market.accessibilityMod.modifyFlat(EndingKeys.ADMIN_MOD_ID, 0.10f, label)
            market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD)
                .modifyMult(EndingKeys.ADMIN_MOD_ID, 1.25f, label)
        }

        /** 解除行政特化殖民地被动（未挂载时为空操作）。 */
        @JvmStatic
        fun clearAdminMods(market: MarketAPI) {
            market.stability.unmodifyFlat(EndingKeys.ADMIN_MOD_ID)
            market.accessibilityMod.unmodifyFlat(EndingKeys.ADMIN_MOD_ID)
            market.stats.dynamic.getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(EndingKeys.ADMIN_MOD_ID)
        }
    }

    private var timer = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < 1f) return
        timer = 0f

        val sector = Global.getSector() ?: return
        val state = BountyState.getOrCreate()
        val ending = EndingState.getOrCreate()

        applyDelayedIfDue(sector, state, ending)
        maintainMarkets(sector, state, ending)
        maintainCommandShip(sector, ending)
    }

    /** 延迟档到点激活（一次性，由 [EndingState.archiveDelayedApplied] 保证不重入）。 */
    private fun applyDelayedIfDue(sector: SectorAPI, state: BountyState, ending: EndingState) {
        if (!state.infiniteContractor || ending.archiveDelayedApplied) return
        val due = ending.archiveDelayedDueTimestamp
        if (due < 0 || sector.clock.timestamp < due) return

        val plan = ArchiveBoost.computePlan(state.archiveChoice, state.archiveTradeFactionId)
        if (plan == null) {
            log.error(
                "[EndingRuntimeScript] 延迟档到期但归档选择非法（choice=${state.archiveChoice}, " +
                    "trade=${state.archiveTradeFactionId}），本次跳过，下 tick 重试。"
            )
            return
        }
        if (plan.delayed.isEmpty()) {
            ending.archiveDelayedApplied = true
            return
        }

        ending.archiveDelayedApplied = true
        HudMessages.campaign(I18n[EndingKeys.I18N_CATEGORY, "archive.boost.delayed"], RECEIPT_COLOR)
        log.info(
            "[EndingRuntimeScript] 归档延迟档已生效（choice=${state.archiveChoice}, " +
                "factions=${plan.delayed.keys.joinToString(",")}）。"
        )
    }

    /** 归档增幅与行政被动的市场修正统一维护：先清后挂，不在作用集内的市场保证无残留。 */
    private fun maintainMarkets(sector: SectorAPI, state: BountyState, ending: EndingState) {
        val boosts = ArchiveBoost.activeBoosts(state, ending)
        val adminActive = ending.executiveCoreType == EndingKeys.CORE_TYPE_ADMIN
        for (market in sector.economy.marketsCopy) {
            val bonus = boosts[market.factionId]
            if (bonus != null && bonus > 0f) {
                ArchiveBoost.applyToMarket(market, bonus)
            } else {
                ArchiveBoost.clearFromMarket(market)
            }
            if (adminActive && market.isPlayerOwned) {
                applyAdminMods(market)
            } else {
                clearAdminMods(market)
            }
        }
    }

    /** 战斗特化：对指挥舰挂载修正、其余在队舰船保证无残留。 */
    private fun maintainCommandShip(sector: SectorAPI, ending: EndingState) {
        val fleet = sector.playerFleet ?: return
        val target = if (ending.executiveCoreType == EndingKeys.CORE_TYPE_COMBAT) {
            resolveCommandShip(fleet, ending.commandShipId)
        } else {
            null
        }
        for (member in fleet.fleetData.membersListCopy) {
            if (member.isFighterWing) continue
            if (target != null && member.id == target.id) {
                applyCommandShipMods(member)
            } else {
                clearCommandShipMods(member)
            }
        }
    }
}
