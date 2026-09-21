package cn.kasuminova.astd.campaign.ui.terminal

/**
 * 分局终端数据源映射（纯逻辑层，不触碰 Global/原版 UI，可直接单测）。
 *
 * 职责：把 campaign 侧装配的 [TerminalSnapshot] 映射为终端视图——
 * 工单状态机（[OrderLifecycle] → [OrderStatus]）、批次分组与结清进度、
 * ZW/ZQ 合并单单卡呈现（一单一行一卡，内部阶段外露）、档案分层与存目、
 * 账户流水合计、清算序列进度顶栏显隐、章末 glitch 变体判定。
 */
object TerminalDataMapper {

    /** 清算序列进度顶栏显示的起始章节（定稿口径：第三章起顶栏常驻读数）。 */
    const val LIQUIDATION_TOPBAR_CHAPTER: Int = 3

    /**
     * 清算序列进度顶栏显示的末位章节（含结局后：四章结清后 currentChapter=5，
     * 第五章「归档」结局流程期间读数仍常驻；超出主线章节口径的值不显示）。
     */
    const val LIQUIDATION_TOPBAR_LAST_CHAPTER: Int = 5

    /** 生命周期 → 状态章。 */
    fun statusOf(lifecycle: OrderLifecycle): OrderStatus = when (lifecycle) {
        OrderLifecycle.POSTABLE -> OrderStatus.AVAILABLE
        OrderLifecycle.POSTED -> OrderStatus.ACTIVE
        OrderLifecycle.DESTROYED -> OrderStatus.AWAITING_SETTLEMENT
        OrderLifecycle.SETTLED -> OrderStatus.SETTLED
    }

    /** 选中工单底部主操作（已核销单无主操作；缺交割物的待核销单仍可核销——受理时按底档补发，D9 自愈）。 */
    fun primaryActionOf(order: TerminalOrderView?): TerminalAction {
        order ?: return TerminalAction.NONE
        return when (order.status) {
            OrderStatus.AVAILABLE -> TerminalAction.ACCEPT
            OrderStatus.ACTIVE -> TerminalAction.TRACK
            OrderStatus.AWAITING_SETTLEMENT -> TerminalAction.SETTLE
            OrderStatus.SETTLED -> TerminalAction.NONE
        }
    }

    /**
     * 工单列表：按批次（结清组）分组，组序 = 快照内工单首次出现顺序（即剧情注册顺序）。
     * 组头结清进度分母取 [TerminalSnapshot.groupTotals]（含 gating 隐藏单）。
     */
    fun mapOrders(snapshot: TerminalSnapshot): List<OrderBatchView> {
        val byGroup = LinkedHashMap<String, MutableList<TerminalOrderView>>()
        for (order in snapshot.orders) {
            byGroup.getOrPut(order.groupId) { mutableListOf() } += order.toView()
        }
        return byGroup.map { (groupId, orders) ->
            OrderBatchView(
                groupId = groupId,
                settled = orders.count { it.status == OrderStatus.SETTLED },
                total = snapshot.groupTotals[groupId] ?: orders.size,
                orders = orders,
            )
        }
    }

    /** 档案室：按层分组（层号升序），层内保持注册顺序，未解锁条目为存目。 */
    fun mapArchives(snapshot: TerminalSnapshot): List<ArchiveLayerView> {
        val byLayer = snapshot.archives.groupBy { it.layer }.toSortedMap()
        return byLayer.map { (layer, entries) ->
            ArchiveLayerView(
                layer = layer,
                unlocked = entries.count { it.unlocked },
                total = entries.size,
                entries = entries.mapIndexed { index, entry ->
                    ArchiveEntryView(entry.id, entry.layer, entry.unlocked, index + 1)
                },
            )
        }
    }

    /** 承包商账户：流水原样映射 + 贷方合计。 */
    fun mapAccount(snapshot: TerminalSnapshot): TerminalAccountView = TerminalAccountView(
        contractorLevel = snapshot.contractorLevel,
        chapter = snapshot.chapter,
        currentDate = snapshot.currentDate,
        liquidationProgress = snapshot.liquidationProgress,
        settledCount = snapshot.settledCount,
        ledger = snapshot.ledger.map { TerminalLedgerLine(it.title, it.amount, it.kind) },
        totalPayout = snapshot.ledger.sumOf { it.amount },
    )

    /** 清算序列进度顶栏行显隐（第三章起常驻，含结局后；口径区间见常量注释）。 */
    fun showLiquidationTopbar(snapshot: TerminalSnapshot): Boolean =
        snapshot.chapter in LIQUIDATION_TOPBAR_CHAPTER..LIQUIDATION_TOPBAR_LAST_CHAPTER

    /**
     * 章末 glitch 变体判定：一章结清 → 状态章闪现「目标状态：现役？」；
     * 三章结清 → 半行工单打印卡死；其余章节无闪现。
     */
    fun glitchForChapter(chapterCleared: Int?): GlitchSpec? = when (chapterCleared) {
        1 -> GlitchSpec.TARGET_STATUS
        3 -> GlitchSpec.HALF_LINE
        else -> null
    }

    /**
     * 结局事务阶段推导（账户 tab 事务卡的呈现状态机；仅依赖快照字段，无副作用）。
     *
     * 口径：签署前 archivalPending=false 时终端不出现事务卡（NONE）；
     * 签发后按特化进入对应任命待办，任命完成（指挥舰/市场已指定）即 COMPLETE。
     */
    fun endingStageOf(ending: EndingSnapshot): EndingStage {
        if (ending.archivalChoice == null) {
            return if (ending.archivalPending) EndingStage.AWAITING_SIGN else EndingStage.NONE
        }
        if (!ending.executorIssued) return EndingStage.AWAITING_EXECUTOR_SPEC
        return when (ending.executorSpec) {
            ExecutorSpec.COMBAT ->
                if (ending.commandShipName == null) EndingStage.AWAITING_COMMAND_SHIP else EndingStage.COMPLETE

            ExecutorSpec.ADMIN ->
                if (ending.adminMarketName == null) EndingStage.AWAITING_ADMIN_MARKET else EndingStage.COMPLETE

            null -> EndingStage.AWAITING_EXECUTOR_SPEC
        }
    }

    /** 全量映射：当前 tab、选中项（越界时回落到首个可选项）。 */
    fun map(
        snapshot: TerminalSnapshot,
        tab: TerminalTab,
        selectedOrderKey: String?,
        selectedArchiveId: String?,
        endingDoc: ArchivalChoice? = null,
        endingTradeFaction: String? = null,
    ): TerminalViewData {
        val batches = mapOrders(snapshot)
        val archiveLayers = mapArchives(snapshot)
        val account = mapAccount(snapshot)

        val allOrders = batches.flatMap { it.orders }
        val selectedOrder = allOrders.firstOrNull { it.key == selectedOrderKey } ?: allOrders.firstOrNull()
        val allArchives = archiveLayers.flatMap { it.entries }
        val selectedArchive = allArchives.firstOrNull { it.id == selectedArchiveId && it.unlocked }
            ?: allArchives.firstOrNull { it.unlocked }

        return TerminalViewData(
            tab = tab,
            batches = batches,
            archiveLayers = archiveLayers,
            account = account,
            showLiquidationTopbar = showLiquidationTopbar(snapshot),
            selectedOrder = selectedOrder,
            selectedArchive = selectedArchive,
            ending = EndingView(
                stage = endingStageOf(snapshot.ending),
                snapshot = snapshot.ending,
                selectedDoc = endingDoc,
                selectedTradeFaction = endingTradeFaction,
            ),
        )
    }

    private fun OrderSnapshot.toView(): TerminalOrderView = TerminalOrderView(
        key = key,
        serial = serial,
        i18nId = i18nId,
        summary = summary,
        groupId = groupId,
        threatTier = threatTier,
        dangerOmitted = dangerOmitted,
        clauseCount = clauseCount,
        stageIndex = stageIndex,
        stageCount = stageCount,
        nodeDrivenStages = nodeDrivenStages,
        status = statusOf(lifecycle),
        reward = quotedReward,
        requiredItemId = requiredItemId,
        hasRequiredItem = hasRequiredItem,
        affixIds = affixIds,
        descVars = descVars,
        chapterCleared = chapterCleared,
    )
}
