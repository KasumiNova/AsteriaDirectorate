package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyCampaignManager
import cn.kasuminova.astd.campaign.bounty.BountyDef
import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.bounty.BountyRewards
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.InfiniteBounties
import cn.kasuminova.astd.campaign.bounty.MagicBountyBridge
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.ui.ArchiveEntry
import cn.kasuminova.astd.campaign.ui.DirectorateTerminalDataSource
import cn.kasuminova.astd.campaign.ui.DirectorateTerminalKeys
import cn.kasuminova.astd.campaign.ui.EndingOption
import cn.kasuminova.astd.campaign.ui.LedgerEntry
import cn.kasuminova.astd.campaign.ui.TerminalSnapshot
import cn.kasuminova.astd.campaign.ui.WorkOrder
import cn.kasuminova.astd.campaign.ui.WorkOrderBatch
import cn.kasuminova.astd.campaign.ui.WorkOrderStatus
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator
import org.magiclib.bounty.MagicBountyLoader
import java.util.Random

/**
 * 分局终端的真实数据源：以主线赏金系统（[MainBounties] / [BountyState] / MagicBounty 运行时）
 * 为事实来源，替代默认的 memory/persistentData 键读取实现。
 *
 * - 工单：批次 = [MainBounties.groups]；状态经 [StoryTerminalMapping] 由结清/接取/门槛推导；
 * - 档案室：[StoryArchives] 按章节结清进度逐层逐份解锁；
 * - 账户：承包商编号/等级/注册日期 + [BountyState.ledgerEntries] 履约流水；
 * - 终局：档案处置申请受理后（[BountyState.archivalPending]）出示三选结局，
 *   选择经 [BountyCampaignManager.recordArchiveChoice] 落盘并结算（[EndingSettlement]）。
 */
class BountyTerminalDataSource : DirectorateTerminalDataSource {

    private companion object {
        val log: Logger = Global.getLogger(BountyTerminalDataSource::class.java)

        const val CAT: String = BountyKeys.I18N_CATEGORY

        const val ENDING_PUBLIC: String = "archive.public"
        const val ENDING_SEALED: String = "archive.sealed"

        /** 交易选结局选项 id 前缀：完整 id = 前缀 + 对象势力 id。 */
        const val ENDING_TRADE_PREFIX: String = "archive.traded."
        /** 执行官特化选项 id 前缀：完整 id = 前缀 + combat/admin。 */
        const val CORE_PREFIX: String = "core."
    }

    override fun snapshot(): TerminalSnapshot {
        val sector = Global.getSector()
        val state = BountyState.getOrCreate()
        if (state.infiniteContractor) {
            try {
                InfiniteBounties.ensureAvailable()
            } catch (t: Throwable) {
                log.warn("[BountyTerminalDataSource] 刷新无限赏金工单失败：${t.message}", t)
            }
        }
        val coord = coordinator()
        val activeAccepted = coord?.activeBounties
            ?.filterValues { it.stage == ActiveBounty.Stage.Accepted }
            ?.keys
            ?: emptySet()
        val gating: (String) -> Boolean = { mk -> sector?.memoryWithoutUpdate?.getBoolean(mk) == true }

        val mainBatches = MainBounties.groups.mapNotNull { group ->
            val groupKeys = MainBounties.groupMembers[group.id] ?: emptyList()
            val orders = if (group.id == "c2_zw") {
                mergedAsterOrder(groupKeys, state, activeAccepted, gating)?.let(::listOf).orEmpty()
            } else {
                groupKeys.mapNotNull { key ->
                    val def = MainBounties.defsByKey[key] ?: return@mapNotNull null
                    val status = when {
                        key in state.defeatedBountyKeys -> WorkOrderStatus.READY_TO_SETTLE
                        else -> StoryTerminalMapping.visibleStatus(def, state.succeededBountyKeys, activeAccepted, gating)
                    } ?: return@mapNotNull null
                    toWorkOrder(def, status)
                }
            }
            if (orders.isEmpty()) null else WorkOrderBatch(
                id = group.id,
                chapterTitle = I18n[CAT, "chapter.${chapterOf(group.id)}.title"],
                title = I18n[CAT, "group.${group.id}.title"],
                orders = orders,
            )
        }
        val infiniteOrders = if (state.infiniteContractor) {
            InfiniteBounties.definitions().map { def ->
                val status = infiniteStatus(def, coord)
                toInfiniteWorkOrder(def, status)
            }
        } else {
            emptyList()
        }
        val batches = if (infiniteOrders.isEmpty()) {
            mainBatches
        } else {
            mainBatches + WorkOrderBatch(
                id = "infinite",
                chapterTitle = I18n[InfiniteBounties.I18N_CATEGORY, "infinite.batch.chapter"],
                title = I18n[InfiniteBounties.I18N_CATEGORY, "infinite.batch.title"],
                orders = infiniteOrders,
            )
        }
        val ledger = state.ledgerEntries.map { LedgerEntry(it.code, it.date, it.amount, it.note) } +
            InfiniteBounties.bills().map { LedgerEntry(it.code, it.date, it.amount, it.note) }

        return TerminalSnapshot(
            contractorId = state.contractorId.ifEmpty { I18n[I18n.Categories.MOD, "ui.terminal.account.unregistered"] },
            contractorLevel = state.contractorLevel,
            registerCycle = state.registerCycle.ifEmpty { "-" },
            batches = batches,
            archives = archives(state),
            ledger = ledger,
            liquidationProgress = state.liquidationProgress.takeIf { it > 0f },
            endings = endings(sector, state),
        )
    }

    override fun acceptWorkOrder(orderId: String): Boolean {
        val sector = Global.getSector() ?: return false
        val actualOrderId = if (orderId == "c2_zw") {
            nextInternalOrderId(orderId) ?: run {
                log.info("[BountyTerminalDataSource] 紫菀合并工单没有可执行阶段")
                return false
            }
        } else orderId
        val infiniteDef = InfiniteBounties.definition(actualOrderId)
        val def = MainBounties.defsByKey[actualOrderId] ?: infiniteDef ?: run {
            log.warn("[BountyTerminalDataSource] 接取失败：未知工单 '$orderId'")
            return false
        }
        val state = BountyState.getOrCreate()
        val gating: (String) -> Boolean = { mk -> sector.memoryWithoutUpdate.getBoolean(mk) }
        val status = if (infiniteDef != null) {
            infiniteStatus(infiniteDef, coordinator())
        } else {
            StoryTerminalMapping.visibleStatus(def, state.succeededBountyKeys, acceptedKeys(), gating)
        }
        if (status != WorkOrderStatus.AVAILABLE) {
            log.info("[BountyTerminalDataSource] 工单 '$orderId' 当前状态不可接取（$status）")
            return false
        }

        val coord = coordinator() ?: return false
        val spec = MagicBountyLoader.getBountyData(actualOrderId) ?: run {
            log.warn("[BountyTerminalDataSource] 接取失败：MagicBounty 注册器缺少 '$actualOrderId'，补注册后重试")
            if (infiniteDef != null) {
                InfiniteBounties.ensureAvailable()
                MagicBountyLoader.getBountyData(actualOrderId)
            } else {
                reRegisterSpec(actualOrderId)
            }
        } ?: return false
        var active = coord.getActiveBounty(actualOrderId)
        if (active == null) {
            active = coord.createActiveBounty(actualOrderId, spec)
            if (active == null) {
                log.warn("[BountyTerminalDataSource] 接取失败：目标舰队创建未果（'$actualOrderId'），详见 MagicLib 日志")
                return false
            }
        }
        if (active.stage != ActiveBounty.Stage.NotAccepted) {
            log.info("[BountyTerminalDataSource] 工单 '$orderId' 已处于 ${active.stage}，不可重复接取")
            return false
        }

        val source = sector.getEntityById(StoryWorldIds.MAIN_STATION_BRANCH) ?: sector.playerFleet
        if (infiniteDef == null) {
            state.quotedRewards[actualOrderId] = BountyRewards.computeTicketPayout(
                def.rewardMin, def.rewardMax, DifficultyTuningImpl.fixedScale,
                Random(actualOrderId.hashCode().toLong()),
            )
        }
        active.acceptBounty(
            source,
            0f,
            spec.job_reputation_reward,
            spec.job_forFaction,
        )
        moveToStoryTarget(actualOrderId, active.fleet)
        log.info("[BountyTerminalDataSource] 工单 '$orderId'（内部 $actualOrderId）已经终端接取。")
        return true
    }

    override fun trackWorkOrder(orderId: String): Boolean {
        val actualOrderId = acceptedInternalOrderId(orderId) ?: orderId
        val fleet = coordinator()?.getActiveBounty(actualOrderId)?.fleet
        if (fleet == null) {
            log.warn("[BountyTerminalDataSource] 追踪失败：工单 '$orderId' 没有活动舰队")
            return false
        }
        Misc.makeImportant(fleet, "astd_terminal_tracking")
        Global.getSector()?.memoryWithoutUpdate?.set(DirectorateTerminalKeys.SETTLEMENT_FOCUS, actualOrderId, 0f)
        return true
    }

    override fun requestSettlement(orderId: String): Boolean {
        val actualOrderId = acceptedInternalOrderId(orderId) ?: orderId
        if (InfiniteBounties.isInfiniteKey(actualOrderId)) {
            if (actualOrderId !in InfiniteBounties.pendingDeliveries()) {
                log.info("[BountyTerminalDataSource] 无限工单 '$orderId' 尚未进入可交付状态，无法登记核销")
                return false
            }
            return BountyCampaignManager.settleBounty(actualOrderId)
        }

        val state = BountyState.getOrCreate()
        if (actualOrderId !in state.defeatedBountyKeys) {
            log.info("[BountyTerminalDataSource] 工单 '$orderId' 尚未进入可交付状态，无法登记核销")
            return false
        }
        if (StorySites.requiresAsset(actualOrderId) && !StoryCargo.getOrCreate().hasAsset(actualOrderId)) {
            log.info("[BountyTerminalDataSource] 工单 '$orderId' 的托管资产尚未回收，无法核销")
            return false
        }
        val accepted = BountyCampaignManager.settleBounty(actualOrderId)
        if (accepted && StorySites.requiresAsset(actualOrderId)) {
            StoryCargo.getOrCreate().handIn(actualOrderId)
        }
        return accepted
    }

    override fun chooseEnding(endingId: String): Boolean {
        if (endingId.startsWith(CORE_PREFIX)) {
            val type = endingId.removePrefix(CORE_PREFIX)
            return EndingSettlement.issueExecutiveCore(type)
        }
        val (choice, faction) = when {
            endingId == ENDING_PUBLIC -> BountyCampaignManager.ARCHIVE_PUBLIC to null
            endingId == ENDING_SEALED -> BountyCampaignManager.ARCHIVE_SEALED to null
            endingId.startsWith(ENDING_TRADE_PREFIX) ->
                BountyCampaignManager.ARCHIVE_TRADED to endingId.removePrefix(ENDING_TRADE_PREFIX)
            else -> {
                log.warn("[BountyTerminalDataSource] 未知结局选项：'$endingId'")
                return false
            }
        }
        return BountyCampaignManager.recordArchiveChoice(choice, faction)
    }

    // --- 内部映射 ---------------------------------------------------------------

    private fun mergedAsterOrder(
        keys: List<String>,
        state: BountyState,
        activeAccepted: Set<String>,
        gating: (String) -> Boolean,
    ): WorkOrder? {
        val defs = keys.mapNotNull(MainBounties.defsByKey::get)
        if (defs.isEmpty()) return null
        val first = defs.first()
        val allSucceeded = defs.all { it.key in state.succeededBountyKeys }
        val anyAccepted = defs.any { it.key in activeAccepted }
        val available = !allSucceeded && !anyAccepted && first.requiredMemKeys.all(gating)
        val ready = defs.any { it.key in state.defeatedBountyKeys }
        val status = when {
            allSucceeded -> WorkOrderStatus.SETTLED
            ready -> WorkOrderStatus.READY_TO_SETTLE
            anyAccepted -> WorkOrderStatus.ACTIVE
            available -> WorkOrderStatus.AVAILABLE
            else -> return null
        }
        val mergedReward = BountyRewards.computeTicketPayout(
            defs.minOf { it.rewardMin }, defs.maxOf { it.rewardMax },
            DifficultyTuningImpl.fixedScale, Random(first.key.hashCode().toLong()),
        )
        return WorkOrder(
            id = "c2_zw",
            code = first.code,
            title = I18n[CAT, "main.${first.key}.short_name"],
            threatTier = first.dangerLevel,
            targetSummary = I18n[CAT, "main.${first.key}.fleet_name"],
            status = status,
            commission = I18n[CAT, "main.${first.key}.commission"],
            clauses = (1..4).map { I18n[CAT, "main.${first.key}.clause.$it"] },
            remark = I18n[CAT, "main.${first.key}.remark"],
            issueDate = I18n[CAT, "main.${first.key}.issue_date"],
            reward = mergedReward.toLong(),
        )
    }

    private fun toWorkOrder(def: BountyDef, status: WorkOrderStatus): WorkOrder {
        val state = BountyState.getOrCreate()
        val ks = DifficultyTuningImpl.fixedScale
        return WorkOrder(
            id = def.key,
            code = def.code,
            title = I18n[CAT, "main.${def.key}.short_name"],
            threatTier = if (def.dangerAbsent) 0 else def.dangerLevel,
            targetSummary = I18n[CAT, "main.${def.key}.fleet_name"],
            status = status,
            commission = I18n[CAT, "main.${def.key}.commission"],
            clauses = (1..def.clauseCount).map { I18n[CAT, "main.${def.key}.clause.$it"] },
            remark = I18n[CAT, "main.${def.key}.remark"],
            issueDate = I18n[CAT, "main.${def.key}.issue_date"],
            reward = (state.quotedRewards[def.key] ?: BountyRewards.computeTicketPayout(
                def.rewardMin, def.rewardMax, ks, Random(def.key.hashCode().toLong()),
            )).toLong(),
        )
    }

    private fun infiniteStatus(def: BountyDef, coord: MagicBountyCoordinator?): WorkOrderStatus {
        if (def.key in InfiniteBounties.pendingDeliveries()) return WorkOrderStatus.READY_TO_SETTLE
        return when (coord?.getActiveBounty(def.key)?.stage) {
            ActiveBounty.Stage.Accepted -> WorkOrderStatus.ACTIVE
            ActiveBounty.Stage.Succeeded -> WorkOrderStatus.READY_TO_SETTLE
            ActiveBounty.Stage.NotAccepted, null -> WorkOrderStatus.AVAILABLE
            else -> WorkOrderStatus.AVAILABLE
        }
    }

    private fun toInfiniteWorkOrder(def: BountyDef, status: WorkOrderStatus): WorkOrder = WorkOrder(
        id = def.key,
        code = def.code,
        title = InfiniteBounties.displayName(def.key) ?: def.code,
        threatTier = def.dangerLevel,
        targetSummary = InfiniteBounties.fleetName(def.key) ?: def.code,
        status = status,
        commission = InfiniteBounties.description(def.key) ?: def.code,
        remark = InfiniteBounties.receiptText(def.key) ?: "",
        issueDate = def.code,
        reward = InfiniteBounties.quotedReward(def.key)?.toLong() ?: 0L,
    )

    private fun archives(state: BountyState): List<ArchiveEntry> =
        StoryArchives.defs
            .filter { StoryArchives.isLayerOpen(it.layer, state.completedChapters) }
            .map { def ->
                ArchiveEntry(
                    id = def.id,
                    layer = def.layer,
                    title = I18n[CAT, "archive.${def.id}.title"],
                    body = (1..def.paragraphs).map { I18n[CAT, "archive.${def.id}.body.$it"] },
                    unlocked = StoryArchives.isUnlocked(
                        def.id, state.succeededBountyKeys, state.clearedGroupIds, state.completedChapters,
                    ),
                )
            }

    private fun endings(sector: SectorAPI?, state: BountyState): List<EndingOption> {
        if (EndingSettlement.canIssueExecutiveCore()) {
            return EndingSettlement.availableCoreChoices().map { core ->
                EndingOption(CORE_PREFIX + core.type, core.name, core.description, true)
            }
        }
        if (!state.archivalPending || state.archiveChoice.isNotEmpty()) return emptyList()
        val out = ArrayList<EndingOption>(2 + BountyCampaignManager.TRADEABLE_FACTIONS.size)
        out += EndingOption(
            ENDING_PUBLIC, I18n[CAT, "ending.archive.public.title"], I18n[CAT, "ending.archive.public.desc"], true,
        )
        out += EndingOption(
            ENDING_SEALED, I18n[CAT, "ending.archive.sealed.title"], I18n[CAT, "ending.archive.sealed.desc"], true,
        )
        for (fid in BountyCampaignManager.TRADEABLE_FACTIONS) {
            val factionName = sector?.getFaction(fid)?.displayName ?: fid
            out += EndingOption(
                ENDING_TRADE_PREFIX + fid,
                I18n[CAT, "ending.archive.traded.title"],
                I18n.t(CAT, "ending.archive.traded.desc", "faction" to factionName),
                true,
            )
        }
        return out
    }

    private fun nextInternalOrderId(orderId: String): String? {
        if (orderId != "c2_zw") return null
        val state = BountyState.getOrCreate()
        val keys = MainBounties.groupMembers[orderId].orEmpty()
        return keys.firstOrNull { it in state.defeatedBountyKeys }
            ?: keys.firstOrNull { it !in state.succeededBountyKeys && it !in acceptedKeys() }
    }

    private fun acceptedInternalOrderId(orderId: String): String? {
        if (orderId != "c2_zw") return null
        val state = BountyState.getOrCreate()
        return MainBounties.groupMembers[orderId].orEmpty().firstOrNull {
            it in acceptedKeys() || it in state.defeatedBountyKeys
        }
    }

    private fun moveToStoryTarget(orderId: String, fleet: CampaignFleetAPI) {
        val targetId = when {
            orderId.startsWith("astd_main_c2_xc_") -> StoryWorldIds.STARFALL_STATION_MAIN
            orderId == "astd_main_c2_zw_s1" -> StoryWorldIds.ASTER_GRAVITY_NODE_1
            orderId == "astd_main_c2_zw_s2" -> StoryWorldIds.ASTER_GRAVITY_NODE_2
            orderId == "astd_main_c2_zw_s3" -> StoryWorldIds.ASTER_GRAVITY_NODE_3
            orderId == "astd_main_c2_zw_s4" -> StoryWorldIds.ASTER_STATION_MAIN
            else -> StoryWorldIds.MAIN_STATION_BRANCH
        }
        val target = Global.getSector()?.getEntityById(targetId) ?: run {
            log.warn("[BountyTerminalDataSource] 工单 '$orderId' 目标实体不存在，保留 MagicBounty 原始落点")
            return
        }
        val location = target.containingLocation ?: return
        fleet.containingLocation?.removeEntity(fleet)
        location.addEntity(fleet)
        fleet.setLocation(target.location.x, target.location.y)
        fleet.clearAssignments()
        fleet.addAssignment(com.fs.starfarer.api.campaign.FleetAssignment.DEFEND_LOCATION, target, 1_000_000f)
    }

    private fun chapterOf(groupId: String): Int =
        MainBounties.groupMembers[groupId]?.firstOrNull()
            ?.let { MainBounties.defsByKey[it]?.chapter }
            ?: 0

    private fun acceptedKeys(): Set<String> = coordinator()?.activeBounties
        ?.filterValues { it.stage == ActiveBounty.Stage.Accepted }
        ?.keys
        ?: emptySet()

    /** MagicBounty 协调器（加载时序内可能尚未就绪；失败记日志返回 null）。 */
    private fun coordinator(): MagicBountyCoordinator? = try {
        MagicBountyCoordinator.getInstance()
    } catch (t: Throwable) {
        log.warn("[BountyTerminalDataSource] MagicBountyCoordinator 不可用：${t.message}")
        null
    }

    /** 补注册单个主线赏金并返回 spec（注册器数据在加载器重载后可能丢失）。 */
    private fun reRegisterSpec(orderId: String): org.magiclib.bounty.MagicBountySpec? {
        val def = MainBounties.defsByKey[orderId] ?: return null
        MagicBountyBridge.registerMainBounty(def, overwrite = true)
        return MagicBountyLoader.getBountyData(orderId)
    }
}
