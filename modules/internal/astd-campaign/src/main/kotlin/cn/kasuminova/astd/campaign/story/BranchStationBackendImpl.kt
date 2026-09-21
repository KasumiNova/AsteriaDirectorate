package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.bounty.MainBountyBridge
import cn.kasuminova.astd.campaign.bounty.MainlineProgression
import cn.kasuminova.astd.campaign.dialog.story.BranchStationDialog.BranchStationBackend
import cn.kasuminova.astd.campaign.dialog.story.BranchStationDialog.BranchStationPhase
import cn.kasuminova.astd.campaign.ending.EndingEffects
import cn.kasuminova.astd.campaign.ending.EndingProgression
import cn.kasuminova.astd.campaign.ending.ExecutorCores
import cn.kasuminova.astd.campaign.ending.InfiniteBountyBridge
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator
import cn.kasuminova.astd.campaign.story.BranchTerminalData.snapshot
import cn.kasuminova.astd.campaign.ui.HudMessages
import cn.kasuminova.astd.campaign.ui.terminal.ArchivalChoice
import cn.kasuminova.astd.campaign.ui.terminal.ArchiveSnapshot
import cn.kasuminova.astd.campaign.ui.terminal.BranchTerminalBackend
import cn.kasuminova.astd.campaign.ui.terminal.EndingSnapshot
import cn.kasuminova.astd.campaign.ui.terminal.ExecutorSpec
import cn.kasuminova.astd.campaign.ui.terminal.LedgerKind
import cn.kasuminova.astd.campaign.ui.terminal.LedgerSnapshot
import cn.kasuminova.astd.campaign.ui.terminal.MarketCandidateSnapshot
import cn.kasuminova.astd.campaign.ui.terminal.NarrativeLine
import cn.kasuminova.astd.campaign.ui.terminal.NarrativeOutcome
import cn.kasuminova.astd.campaign.ui.terminal.NarrativeReceiptView
import cn.kasuminova.astd.campaign.ui.terminal.OrderLifecycle
import cn.kasuminova.astd.campaign.ui.terminal.OrderSnapshot
import cn.kasuminova.astd.campaign.ui.terminal.SettleOutcome
import cn.kasuminova.astd.campaign.ui.terminal.ShipCandidateSnapshot
import cn.kasuminova.astd.campaign.ui.terminal.TerminalSnapshot
import cn.kasuminova.astd.campaign.ui.terminal.TerminalStyle
import cn.kasuminova.astd.campaign.ui.terminal.TradeCandidateSnapshot
import cn.kasuminova.astd.campaign.world.GravityNodes
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.util.Misc
import org.apache.log4j.Logger
import org.magiclib.bounty.MagicBountyCoordinator

/**
 * 分局终端数据源装配层（阶段 4 全屏 UI 的 campaign 侧查询）。
 *
 * [snapshot] 为纯逻辑装配：引力节点拔除数 / 交割物持有判定 / 当前日期全部参数注入，
 * 不触碰 Global，可直接单测；游戏侧包装见 [BranchStationBackendImpl]。
 */
object BranchTerminalData {

    /** 功能分相：未接序章 / 已签未核销 / 已注册。 */
    fun phase(state: BountyState): BranchStationPhase = when {
        MainBounties.KEY_PROLOGUE in state.settledWorkOrders || state.contractorLevel >= 1 ->
            BranchStationPhase.OPEN

        MainBounties.KEY_PROLOGUE in state.postedWorkOrders ||
                MainBounties.KEY_PROLOGUE in state.destroyedWorkOrders ->
            BranchStationPhase.PENDING

        else -> BranchStationPhase.LOCKED
    }

    /**
     * 装配终端全量快照。
     *
     * 工单可见性（gating 隐藏）：仅装配 已核销/已击毁待核销/已挂出/gating 已满足未挂出 四态；
     * 前置未满足的工单不进入快照（终端列表与批次分母口径分离——组头分母取注册表组内总数）。
     *
     * 第五章结局装配（docs/story/13）：
     * - 无限期承包商认证后追加无限赏金工单（组 [InfiniteBountyGenerator.GROUP_ID]，3 槽常驻，
     *   词缀/文书变量锁定值透传）与核销流水；
     * - [ending] 快照驱动账户 tab 事务卡（签署/签发/任命待办）。
     *
     * @param pulledCount 引力节点拔除数（档案解锁判定注入）
     * @param hasItem 交割物/签发物品持有判定（物品 id → 玩家货舱是否持有）
     * @param currentDate 战役时钟日期串（回执/档案调阅时间）
     * @param orderSummary 列表行委托摘要解析（i18nId + 命名变量 → 摘要文本；
     *   游戏侧按 bounty 表 `main.<i18nId>.summary` 键解析，终端 UI 不解析文书正文）
     * @param kS 难度系数（交易报价函缩放）
     * @param factionName 势力显示名解析（交易候选/无限赏金目标势力展示）
     * @param shipName 舰只名解析（指挥舰已指定状态行；memberId → 舰名，不在舰队为 null）
     * @param marketName 市场名解析（行政任命状态行）
     * @param ships 指挥舰候选（玩家舰队舰只；仅在待指定阶段需要非空）
     * @param markets 行政任命候选市场（玩家殖民地；仅在待任命阶段需要非空）
     */
    fun snapshot(
        state: BountyState,
        pulledCount: Int,
        hasItem: (String) -> Boolean,
        currentDate: String,
        orderSummary: (i18nId: String, vars: Map<String, String>) -> String,
        kS: Float = 1f,
        factionName: (String) -> String = { it },
        shipName: (String) -> String? = { null },
        marketName: (String) -> String? = { null },
        ships: List<ShipCandidateSnapshot> = emptyList(),
        markets: List<MarketCandidateSnapshot> = emptyList(),
    ): TerminalSnapshot {
        val postableKeys = MainlineProgression.postableOrders(state).mapTo(HashSet()) { it.key }
        val orders = MainBounties.all.mapNotNull { def ->
            val lifecycle = when {
                def.key in state.settledWorkOrders -> OrderLifecycle.SETTLED
                def.key in state.destroyedWorkOrders -> OrderLifecycle.DESTROYED
                def.key in state.postedWorkOrders -> OrderLifecycle.POSTED
                def.key in postableKeys -> OrderLifecycle.POSTABLE
                else -> return@mapNotNull null
            }
            val requiredItem = StoryQuestItems.REQUIRED_DELIVERABLE[def.key]
            OrderSnapshot(
                key = def.key,
                serial = def.serial,
                i18nId = def.i18nId,
                summary = orderSummary(def.i18nId, emptyMap()),
                groupId = def.groupId,
                chapter = def.chapter,
                threatTier = def.threatTier,
                dangerOmitted = def.dangerOmitted,
                clauseCount = def.clauseCount,
                stageIndex = (state.workOrderStageIndex[def.key] ?: 0).coerceIn(0, def.stages.lastIndex),
                stageCount = def.stages.size,
                nodeDrivenStages = def.nodeDrivenStages,
                lifecycle = lifecycle,
                quotedReward = state.quotedRewards[MainlineProgression.quoteKey(def.key)],
                requiredItemId = requiredItem,
                hasRequiredItem = requiredItem == null || hasItem(requiredItem),
                chapterCleared = state.chapterClearingOrders.entries
                    .firstOrNull { it.value == def.key }?.key,
            )
        }.toMutableList()

        // 无限赏金工单（《无限期承包合同》常驻 3 槽；报价/词缀/文书变量取槽位锁定值）
        if (state.indefiniteContractor) {
            for (slot in state.infiniteSlots) {
                val serial = InfiniteBountyGenerator.serialOf(slot.index, slot.generation)
                val descVars = mapOf(
                    "serial" to serial,
                    "faction" to factionName(slot.targetFactionId),
                    "fp" to slot.fp.toString(),
                    "date" to currentDate,
                )
                orders += OrderSnapshot(
                    key = InfiniteBountyGenerator.keyOf(slot.index, slot.generation),
                    serial = serial,
                    i18nId = "infinite",
                    summary = orderSummary("infinite", descVars),
                    groupId = InfiniteBountyGenerator.GROUP_ID,
                    chapter = 5,
                    threatTier = slot.danger,
                    dangerOmitted = false,
                    clauseCount = 0,
                    stageIndex = 0,
                    stageCount = 1,
                    nodeDrivenStages = 0,
                    lifecycle = when (slot.lifecycle) {
                        "POSTED" -> OrderLifecycle.POSTED
                        "DESTROYED" -> OrderLifecycle.DESTROYED
                        else -> OrderLifecycle.POSTABLE
                    },
                    quotedReward = slot.quotedReward,
                    requiredItemId = null,
                    hasRequiredItem = true,
                    affixIds = slot.affixIds,
                    descVars = descVars,
                )
            }
        }

        val ledger = mutableListOf<LedgerSnapshot>()
        for (def in MainBounties.all) {
            if (def.key !in state.settledWorkOrders) continue
            val payout = state.quotedRewards[MainlineProgression.quoteKey(def.key)] ?: continue
            ledger += LedgerSnapshot(title = def.serial, amount = payout, kind = LedgerKind.ORDER)
        }
        for ((groupId, bonus) in state.grantedGroupBonuses) {
            ledger += LedgerSnapshot(title = groupId, amount = bonus, kind = LedgerKind.GROUP_BONUS)
        }
        for (record in state.infiniteSettlements) {
            ledger += LedgerSnapshot(title = record.serial, amount = record.amount, kind = LedgerKind.ORDER)
        }

        val groupTotals = MainBounties.groups.associate { it.id to MainBounties.ordersOfGroup(it.id).size }
            .toMutableMap()
        if (state.indefiniteContractor) {
            groupTotals[InfiniteBountyGenerator.GROUP_ID] = InfiniteBountyGenerator.SLOT_COUNT
        }

        return TerminalSnapshot(
            orders = orders,
            groupTotals = groupTotals,
            chapter = state.currentChapter,
            currentDate = currentDate,
            liquidationProgress = state.liquidationProgress,
            contractorLevel = state.contractorLevel,
            settledCount = state.settledWorkOrders.size,
            ledger = ledger,
            archives = StoryArchives.all.map {
                ArchiveSnapshot(it.id, it.layer, StoryArchives.isUnlocked(it, state, pulledCount))
            },
            ending = EndingSnapshot(
                archivalPending = state.archivalPending,
                archivalChoice = state.archivalChoice?.let { ArchivalChoice.valueOf(it) },
                tradeFactionId = state.tradeFactionId,
                tradeCandidates = EndingProgression.TRADE_CANDIDATES.map {
                    TradeCandidateSnapshot(it, factionName(it), EndingProgression.tradeQuote(it, kS))
                },
                executorIssued = state.executorIssued,
                executorSpec = state.executorSpec?.let { ExecutorSpec.valueOf(it) },
                hasExecutorItem = hasItem(ExecutorCores.ITEM_COMBAT) || hasItem(ExecutorCores.ITEM_ADMIN),
                commandShipName = state.executorCommandShipId?.let(shipName),
                adminMarketName = state.executorAdminMarketId?.let(marketName),
                ships = ships,
                markets = markets,
                archivesReadOnly = state.archivesReadOnly,
            ),
        )
    }
}

/**
 * 分局站后端 campaign 侧实现：入口对话分相（[BranchStationBackend]）+
 * 全屏终端数据源与动作（[BranchTerminalBackend]，事件经 holder 注入回流）。
 *
 * 动作落点：申领/核销走 [MainBountyBridge] 既有 API；追踪目标走
 * `Misc.makeImportant`（节点驱动阶段目标为未拔除的引力节点实体）。
 */
class BranchStationBackendImpl : BranchStationBackend, BranchTerminalBackend {

    private val log: Logger = Global.getLogger(BranchStationBackendImpl::class.java)

    override fun phase(): BranchStationPhase = BranchTerminalData.phase(BountyState.getOrCreate())

    override fun hasExecutorCore(): Boolean = ExecutorCores.playerHasCore()

    override fun executorSpec(): ExecutorSpec? =
        EndingProgression.specOf(BountyState.getOrCreate())?.let { ExecutorSpec.valueOf(it.name) }

    override fun snapshot(): TerminalSnapshot {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 终端快照装配失败：sector 不可用")
        }
        val playerFleet = sector?.playerFleet
        return snapshot(
            state = BountyState.getOrCreate(),
            pulledCount = GravityNodes.pulledCount(),
            hasItem = StoryQuestItems::playerHas,
            currentDate = sector?.clock?.dateString ?: "",
            orderSummary = { i18nId, vars ->
                val key = "main.$i18nId.summary"
                if (vars.isEmpty()) {
                    I18n[TerminalStyle.CAT_BOUNTY, key]
                } else {
                    I18n.t(TerminalStyle.CAT_BOUNTY, key, *vars.map { it.toPair() }.toTypedArray())
                }
            },
            kS = DifficultyTuningImpl.fixedScale,
            factionName = { fid -> sector?.getFaction(fid)?.displayName ?: fid },
            shipName = { id ->
                playerFleet?.fleetData?.membersListCopy?.firstOrNull { it.id == id }?.shipName
            },
            marketName = { id -> sector?.economy?.getMarket(id)?.name },
            ships = playerFleet?.fleetData?.membersListCopy
                ?.map { ShipCandidateSnapshot(it.id, it.shipName, it.isFlagship) }
                ?: emptyList(),
            markets = sector?.economy?.marketsCopy
                ?.filter { it.isPlayerOwned }
                ?.map { MarketCandidateSnapshot(it.id, it.name) }
                ?: emptyList(),
        )
    }

    override fun postOrder(key: String): Boolean {
        // 无限赏金路由（key 含换代序号；槽位锁定参数直接挂出）
        if (key.startsWith(InfiniteBountyGenerator.KEY_PREFIX)) {
            val state = BountyState.getOrCreate()
            val slot = InfiniteBountyBridge.slotOf(state, key)
            if (slot == null) {
                log.error("[ASTD] 终端申领失败：未知无限赏金槽位（$key）")
                return false
            }
            val coord = coordinatorOrNull(key) ?: return false
            return InfiniteBountyBridge.postSlot(state, slot, coord)
        }

        val def = MainBounties.byKey(key)
        if (def == null) {
            log.error("[ASTD] 终端申领失败：未知工单 $key")
            return false
        }
        val coord = coordinatorOrNull(key) ?: return false
        return MainBountyBridge.postWorkOrder(def, BountyState.getOrCreate(), coord)
    }

    override fun trackOrder(key: String): Boolean {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 终端追踪失败：sector 不可用（$key）")
            return false
        }

        // 无限赏金路由：目标为当前代 active 条目舰队
        if (key.startsWith(InfiniteBountyGenerator.KEY_PREFIX)) {
            val fleet = try {
                MagicBountyCoordinator.getInstance().getActiveBounty(key)?.fleet
            } catch (t: Throwable) {
                log.error("[ASTD] 终端追踪失败：MagicBountyCoordinator 不可用（$key）", t)
                return false
            }
            if (fleet == null) {
                log.warn("[ASTD] 终端追踪失败：无限赏金（$key）目标舰队不在 active 列表")
                return false
            }
            Misc.makeImportant(fleet, TRACK_REASON)
            return true
        }

        val def = MainBounties.byKey(key)
        if (def == null) {
            log.error("[ASTD] 终端追踪失败：未知工单 $key")
            return false
        }

        // 节点驱动阶段（ZW 阶段一~三）：目标为未拔除的引力节点
        val stageIndex = (BountyState.getOrCreate().workOrderStageIndex[key] ?: 0).coerceIn(0, def.stages.lastIndex)
        if (def.isNodeDrivenStage(stageIndex)) {
            var marked = false
            for (nodeId in StoryWorldIds.ASTER_NODE_IDS) {
                if (GravityNodes.isPulled(nodeId)) continue
                val node = sector.getEntityById(nodeId) ?: continue
                Misc.makeImportant(node, TRACK_REASON)
                marked = true
            }
            if (!marked) {
                log.warn("[ASTD] 终端追踪失败：无可标记的引力节点（$key）")
            }
            return marked
        }

        val fleet = try {
            MagicBountyCoordinator.getInstance().getActiveBounty(key)?.fleet
        } catch (t: Throwable) {
            log.error("[ASTD] 终端追踪失败：MagicBountyCoordinator 不可用（$key）", t)
            return false
        }
        if (fleet == null) {
            log.warn("[ASTD] 终端追踪失败：工单 ${def.serial}（$key）目标舰队不在 active 列表")
            return false
        }
        Misc.makeImportant(fleet, TRACK_REASON)
        return true
    }

    override fun settleOrder(key: String): SettleOutcome {
        val state = BountyState.getOrCreate()

        // 无限赏金路由：无交割物要求，按槽位锁定报价发放
        if (key.startsWith(InfiniteBountyGenerator.KEY_PREFIX)) {
            val slot = InfiniteBountyBridge.slotOf(state, key)
            if (slot == null) {
                log.error("[ASTD] 终端核销失败：未知无限赏金槽位（$key）")
                return SettleOutcome(false, key, rejectReason = "unknown_infinite_slot:$key")
            }
            val payout = slot.quotedReward
            if (!InfiniteBountyBridge.settle(state, slot.index)) {
                return SettleOutcome(false, key, rejectReason = "infinite_settle_rejected:$key")
            }
            return SettleOutcome(success = true, orderKey = key, payout = payout)
        }

        // 交割物校验：须随单交付的任务物品未持有时——
        // 工单已击毁待核销则自动补发一份并继续核销（D9 自愈：打捞后丢弃/未捡/被原版意外移除；
        // 物品带 no_drop 族标签，正常流程不可丢，此路径为兜底）；未击毁则拒绝受理（doc 07「只可交付」）
        val requiredItem = StoryQuestItems.REQUIRED_DELIVERABLE[key]
        if (requiredItem != null && !StoryQuestItems.playerHas(requiredItem)) {
            if (key !in state.destroyedWorkOrders) {
                log.warn("[ASTD] 终端核销被拒绝：缺少交割物 $requiredItem（$key）")
                return SettleOutcome(false, key, rejectReason = "missing_deliverable:$requiredItem")
            }
            log.warn("[ASTD] 交割物丢失自愈：工单已击毁待核销但未持有 $requiredItem（$key），按底档补发")
            StoryQuestItems.grantToPlayer(requiredItem)
            HudMessages.campaign(
                I18n.t(
                    I18n.Categories.MOD, "story.item.regrant",
                    "item" to I18n[I18n.Categories.MOD, "story.item.name.$requiredItem"],
                ),
                RECEIPT_COLOR,
            )
        }

        val result = MainBountyBridge.settleWorkOrder(key)
        if (!result.success) {
            return SettleOutcome(false, key, rejectReason = result.rejectReason)
        }

        // 核销成功后消耗交割物（失败不消耗——物品仍在玩家货舱，可重试交付）
        if (requiredItem != null && !StoryQuestItems.consumeFromPlayer(requiredItem)) {
            log.error("[ASTD] 交割物消耗失败：核销已完成但物品未移除（$requiredItem，$key）")
        }

        return SettleOutcome(
            success = true,
            orderKey = key,
            payout = result.orderPayout,
            groupBonusId = result.groupBonus?.first,
            groupBonusAmount = result.groupBonus?.second ?: 0,
            chapterCleared = result.chapterCleared,
            liquidationProgress = result.liquidationProgress,
        )
    }

    // ─── 第五章「归档」结局事务（docs/story/13） ───

    /**
     * 归档签署：三选效果落账 → 势力强化/关系写入/交易报酬发放 → 无限赏金槽位初始化 →
     * 演出回执链（清算 100%+反转回执 → 归档回执 → 《无限期承包合同》）。
     *
     * 半落账自愈：落账（[EndingProgression.sign]）先于游戏侧副作用；副作用异常只记 error，
     * 强度修正由 [cn.kasuminova.astd.campaign.ending.EndingCampaignManager] 周期重挂自愈
     * （remountAll 以 state.appliedStrengthPct 为事实源，覆盖 sign 后立即挂载的同等效果）。
     */
    override fun signArchival(choice: ArchivalChoice, tradeFactionId: String?): NarrativeOutcome? {
        val sector = Global.getSector()
        if (sector == null) {
            log.error("[ASTD] 归档签署失败：sector 不可用")
            return null
        }
        val state = BountyState.getOrCreate()
        val kS = DifficultyTuningImpl.fixedScale
        val plan = EndingProgression.planSign(
            EndingProgression.Choice.valueOf(choice.name),
            tradeFactionId,
            EndingEffects.affectedFactionIds(sector),
            kS,
        )
        if (!EndingProgression.sign(state, plan, sector.clock.timestamp, sector.clock.secondsPerDay)) {
            log.warn("[ASTD] 归档签署被拒绝（未挂起或已签署）")
            return null
        }

        // 立即生效条目挂载 + 关系变动 + 交易报酬发放。
        // 自愈口径：state 已落账（签署完成），此处任何一步异常只记 error 不中断——
        // 强度修正由 EndingCampaignManager.remountAll 每周期以 state.appliedStrengthPct 为
        // 事实源幂等重挂（与 sign 后立即挂载同等效果），最终一致；关系/报酬失败需人工介入日志。
        try {
            EndingEffects.applyImmediate(sector, state.appliedStrengthPct)
        } catch (t: Throwable) {
            log.error("[ASTD] 归档签署即时挂载异常（周期重挂将自愈）", t)
        }
        try {
            EndingEffects.applyRelations(sector, plan.relationDeltas)
        } catch (t: Throwable) {
            log.error("[ASTD] 归档签署关系写入异常：${plan.relationDeltas}", t)
        }
        if (plan.tradePayout > 0) {
            val cargo = sector.playerFleet?.cargo
            if (cargo == null) {
                log.error("[ASTD] 交易报酬发放失败：playerFleet 不可用（金额 ${plan.tradePayout}）")
            } else {
                cargo.credits.add(plan.tradePayout.toFloat())
            }
        }

        // 《无限期承包合同》：无限赏金槽位初始化（周期 tick 亦有幂等补齐兜底，此处即签即得）
        InfiniteBountyGenerator.ensureSlots(state, kS, InfiniteBountyBridge.SEED_BASE)

        val choiceId = choice.name.lowercase()
        return NarrativeOutcome(
            listOf(
                // 第 1 页：清算 100% + 反转回执（13 定稿文案）
                NarrativeReceiptView(
                    titleKey = "ending.receipt.liquidation.title",
                    lines = listOf(
                        NarrativeLine("ending.receipt.liquidation.line.1"),
                        NarrativeLine("ending.receipt.liquidation.line.2"),
                        NarrativeLine("ending.receipt.liquidation.line.3"),
                        NarrativeLine("ending.receipt.liquidation.line.4", dim = true),
                    ),
                ),
                // 第 2 页：归档回执（按三选分支；交易选附报酬金额滚动）
                NarrativeReceiptView(
                    titleKey = "ending.receipt.archival.$choiceId.title",
                    lines = listOf(
                        NarrativeLine("ending.receipt.archival.$choiceId.line.1"),
                        NarrativeLine("ending.receipt.archival.$choiceId.line.2"),
                    ),
                    amount = plan.tradePayout,
                    amountKey = if (plan.tradePayout > 0) "ending.receipt.amount.trade" else "",
                ),
                // 第 3 页：《无限期承包合同》
                NarrativeReceiptView(
                    titleKey = "ending.receipt.contract.title",
                    lines = listOf(
                        NarrativeLine("ending.receipt.contract.line.1"),
                        NarrativeLine("ending.receipt.contract.line.2", dim = true),
                    ),
                ),
            ),
        )
    }

    /**
     * 「执行官」签发：特化落账 → 物品入舱 → 演出回执链（签发回执 → 最终回执「第 205 期年检筹备中」）。
     */
    override fun issueExecutor(spec: ExecutorSpec): NarrativeOutcome? {
        val state = BountyState.getOrCreate()
        if (!EndingProgression.issueExecutor(state, EndingProgression.ExecutorSpec.valueOf(spec.name))) {
            log.warn("[ASTD] 执行官签发被拒绝（未签署归档或已签发）")
            return null
        }
        ExecutorCores.issueToPlayer(EndingProgression.ExecutorSpec.valueOf(spec.name))

        val specId = spec.name.lowercase()
        return NarrativeOutcome(
            listOf(
                NarrativeReceiptView(
                    titleKey = "ending.receipt.issuer.title",
                    lines = listOf(
                        NarrativeLine("ending.receipt.issuer.line.1"),
                        NarrativeLine("ending.receipt.issuer.spec.$specId"),
                    ),
                ),
                NarrativeReceiptView(
                    titleKey = "ending.receipt.final.title",
                    lines = listOf(
                        NarrativeLine("ending.receipt.final.line.1"),
                        NarrativeLine("ending.receipt.final.line.2", dim = true),
                    ),
                ),
            ),
        )
    }

    override fun assignCommandShip(memberId: String): Boolean {
        val state = BountyState.getOrCreate()
        // 事务口径：state 层先校验 → 游戏侧执行成功后才落账（失败有日志，UI 不误报 COMPLETE）
        if (!EndingProgression.canAssignCommandShip(state)) {
            log.warn("[ASTD] 指定指挥舰被拒绝：未签发战斗特化（$memberId）")
            return false
        }
        if (!ExecutorCores.assignCommandShip(memberId)) return false
        EndingProgression.assignCommandShip(state, memberId)
        return true
    }

    override fun appointAdmin(marketId: String): Boolean {
        val state = BountyState.getOrCreate()
        // 事务口径：state 层先校验 → 游戏侧执行成功后才落账（失败有日志，UI 不误报 COMPLETE）
        if (!EndingProgression.canAppointAdmin(state)) {
            log.warn("[ASTD] 任命行政官被拒绝：未签发行政特化（$marketId）")
            return false
        }
        if (!ExecutorCores.appointAdmin(marketId)) return false
        EndingProgression.appointAdmin(state, marketId)
        return true
    }

    private fun coordinatorOrNull(context: String): MagicBountyCoordinator? = try {
        MagicBountyCoordinator.getInstance()
    } catch (t: Throwable) {
        log.error("[ASTD] 终端操作失败：MagicBountyCoordinator 不可用（$context）", t)
        null
    }

    companion object {
        /** makeImportant 的原因标记（重要标记合并键，内部标识不展示）。 */
        const val TRACK_REASON: String = "astd_terminal_track"

        /** 交割物补发 HUD 回执色（与核销回执同色系）。 */
        private val RECEIPT_COLOR = java.awt.Color(200, 170, 120)
    }
}
