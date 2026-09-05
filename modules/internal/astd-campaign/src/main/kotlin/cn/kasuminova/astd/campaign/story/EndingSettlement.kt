package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import org.apache.log4j.Logger
import java.awt.Color

/**
 * 归档签署后的终局收尾（第五章 13 文档：归档回执 → 势力变强生效 → 合同 → 「执行官」签发 → 最终回执）。
 *
 * - 归档回执/合同由赏金管理脚本在 recordArchiveChoice 内直接打印，本类承接其后两段：
 *   势力变强结算（[ArchiveBoost] + [EndingRuntimeScript]）与「执行官」签发；
 * - 「执行官」采用**终端托管**形态：核心登记在分局终端（[EndingState]），不进入玩家货舱——
 *   由此绝对满足特殊核心「不可丢弃、不可出售」的统一规则（85 文档 §3），无需依赖物品锁定机制；
 * - 特化二选一由玩家经 [issueExecutiveCore] 显式选择（终端 UI 调用），未经选择绝不自动签发。
 */
object EndingSettlement {

    private val log: Logger = Global.getLogger(EndingSettlement::class.java)
    private val RECEIPT_COLOR = Color(200, 170, 120)

    /** 「执行官」可选特化（供终端 UI 展示的名称与说明已本地化的选项）。 */
    class ExecutiveCoreOption(
        /** 特化类型 id：[EndingKeys.CORE_TYPE_COMBAT] / [EndingKeys.CORE_TYPE_ADMIN]。 */
        val type: String,
        /** 选项显示名（本地化）。 */
        val name: String,
        /** 选项效果说明（本地化）。 */
        val description: String,
    )

    /**
     * 归档三选受理成功后调用：激活势力变强结算（立即档立即激活、延迟档排期），
     * 打印最终回执，并提示「执行官」待选特化。核心的实际签发等待玩家选择，见 [issueExecutiveCore]。
     */
    fun onArchiveSigned(sector: SectorAPI, state: BountyState) {
        val ending = EndingState.getOrCreate()
        val plan = ArchiveBoost.computePlan(state.archiveChoice, state.archiveTradeFactionId)
        if (plan == null) {
            log.error(
                "[EndingSettlement] 归档选择非法（choice=${state.archiveChoice}, " +
                    "trade=${state.archiveTradeFactionId}），势力变强结算未排期；终局回执照常输出。"
            )
        } else {
            ending.archiveImmediateApplied = plan.immediate.isNotEmpty()
            if (plan.delayed.isNotEmpty()) {
                val delayDays = EndingKeys.CYCLE_DAYS * plan.delayedCycles
                ending.archiveDelayedDueTimestamp =
                    sector.clock.timestamp + sector.clock.convertToSeconds(delayDays).toLong()
                HudMessages.campaign(
                    I18n.t(EndingKeys.I18N_CATEGORY, "archive.boost.scheduled", "days" to delayDays.toInt()),
                    RECEIPT_COLOR,
                )
            }
            if (plan.immediate.isNotEmpty()) {
                HudMessages.campaign(immediateNoticeKey(state, plan, sector), RECEIPT_COLOR)
            }
            log.info(
                "[EndingSettlement] 归档后果已结算排期：choice=${state.archiveChoice}, " +
                    "immediate=${plan.immediate}, delayed=${plan.delayed}（${plan.delayedCycles} 周期）。"
            )
        }

        EndingRuntimeScript.ensureAdded(sector)
        HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "archive.final_receipt"], RECEIPT_COLOR)
        HudMessages.campaign(I18n[EndingKeys.I18N_CATEGORY, "core.awaiting_choice"], RECEIPT_COLOR)
    }

    /** 立即档的 HUD 提示文本（公开/交易分支措辞不同；封存无立即档）。 */
    private fun immediateNoticeKey(state: BountyState, plan: ArchiveBoost.Plan, sector: SectorAPI): String {
        val tradeTarget = state.archiveTradeFactionId
        if (tradeTarget != null && plan.immediate.containsKey(tradeTarget)) {
            val factionName = sector.getFaction(tradeTarget)?.displayName ?: tradeTarget
            return I18n.t(EndingKeys.I18N_CATEGORY, "archive.boost.traded", "faction" to factionName)
        }
        return I18n[EndingKeys.I18N_CATEGORY, "archive.boost.public"]
    }

    // --- 「执行官」签发 API（终端 UI 接线面） ------------------------------------

    /** 可选特化列表（固定两项；显示文本随本地化表解析）。 */
    fun availableCoreChoices(): List<ExecutiveCoreOption> = listOf(
        ExecutiveCoreOption(
            EndingKeys.CORE_TYPE_COMBAT,
            I18n[EndingKeys.I18N_CATEGORY, "core.option.combat.name"],
            I18n[EndingKeys.I18N_CATEGORY, "core.option.combat.desc"],
        ),
        ExecutiveCoreOption(
            EndingKeys.CORE_TYPE_ADMIN,
            I18n[EndingKeys.I18N_CATEGORY, "core.option.admin.name"],
            I18n[EndingKeys.I18N_CATEGORY, "core.option.admin.desc"],
        ),
    )

    /** 是否可签发「执行官」（归档已完成且尚未选定特化）。 */
    fun canIssueExecutiveCore(): Boolean {
        val state = BountyState.getOrCreate()
        return state.infiniteContractor && EndingState.getOrCreate().executiveCoreType.isEmpty()
    }

    /** 已签发的特化类型（"" = 未签发）。 */
    fun issuedCoreType(): String = EndingState.getOrCreate().executiveCoreType

    /**
     * 签发「执行官」并选定特化（终端 UI 的玩家选择入口；不可更改、不可重复）。
     *
     * 签发后核心登记为终端托管（[EndingState.executiveCoreType]），被动立即生效；
     * 同步置位 [BountyState.executiveCoreIssued] 与 [EndingKeys.MEM_EXECUTIVE_CORE_TYPE]。
     *
     * @return 是否签发成功（非法类型/未满足前置/已签发时拒绝并记警告日志）
     */
    fun issueExecutiveCore(type: String): Boolean {
        val sector = Global.getSector()
        if (sector == null) {
            log.warn("[EndingSettlement] 签发「执行官」失败：星区不可用（type=$type）。")
            return false
        }
        val state = BountyState.getOrCreate()
        val ending = EndingState.getOrCreate()
        if (!state.infiniteContractor) {
            log.warn("[EndingSettlement] 归档流程未完成时调用签发（type=$type），已拒绝。")
            return false
        }
        if (ending.executiveCoreType.isNotEmpty()) {
            log.warn("[EndingSettlement] 「执行官」已签发（${ending.executiveCoreType}），不予退换（请求 type=$type）。")
            return false
        }
        if (type != EndingKeys.CORE_TYPE_COMBAT && type != EndingKeys.CORE_TYPE_ADMIN) {
            log.warn("[EndingSettlement] 未知特化类型：$type，已拒绝。")
            return false
        }

        ending.executiveCoreType = type
        state.executiveCoreIssued = true
        sector.memoryWithoutUpdate.set(EndingKeys.MEM_EXECUTIVE_CORE_TYPE, type)
        EndingRuntimeScript.ensureAdded(sector)

        HudMessages.campaign(I18n[BountyKeys.I18N_CATEGORY, "archive.executive_issued"], RECEIPT_COLOR)
        HudMessages.campaign(I18n[EndingKeys.I18N_CATEGORY, "core.issued.$type"], RECEIPT_COLOR)
        log.info("[EndingSettlement] 「执行官」已签发：特化=$type（终端托管，不入货舱）。")
        return true
    }

    /**
     * 旧签名保留（兼容 StoryRuntimeScript 的存量调用）：**不再自动签发**。
     * 未经玩家选择本方法不做任何事；已选择时确保生效脚本在跑（幂等）。
     * 新代码应使用 [issueExecutiveCore] 完成签发。
     */
    fun issueExecutiveCore(sector: SectorAPI, state: BountyState) {
        if (!state.infiniteContractor) return
        if (EndingState.getOrCreate().executiveCoreType.isEmpty()) return
        EndingRuntimeScript.ensureAdded(sector)
    }

    // --- 战斗特化：指挥舰选择 API ------------------------------------------------

    /**
     * 指定战斗特化的指挥舰（默认玩家旗舰；目标离队/灭失时自动回落旗舰）。
     *
     * @return 是否受理成功（未签发战斗特化或目标不在玩家舰队时拒绝并记警告日志）
     */
    fun assignCommandShip(member: FleetMemberAPI): Boolean {
        val ending = EndingState.getOrCreate()
        if (ending.executiveCoreType != EndingKeys.CORE_TYPE_COMBAT) {
            log.warn("[EndingSettlement] 非战斗特化时调用指挥舰指定（当前=${ending.executiveCoreType}），已拒绝。")
            return false
        }
        val fleet = Global.getSector()?.playerFleet
        if (fleet == null || member.isFighterWing ||
            fleet.fleetData.membersListCopy.none { it.id == member.id }
        ) {
            log.warn("[EndingSettlement] 指挥舰指定失败：目标不在玩家舰队（member=${member.id}）。")
            return false
        }
        ending.commandShipId = member.id
        log.info("[EndingSettlement] 「执行官」指挥舰已指定：${member.shipName}（${member.id}）。")
        return true
    }

    /** 战斗特化当前实际生效的指挥舰（未签发战斗特化或无玩家舰队时为 null）。 */
    fun commandShip(): FleetMemberAPI? {
        val ending = EndingState.getOrCreate()
        if (ending.executiveCoreType != EndingKeys.CORE_TYPE_COMBAT) return null
        val fleet = Global.getSector()?.playerFleet ?: return null
        return EndingRuntimeScript.resolveCommandShip(fleet, ending.commandShipId)
    }
}
