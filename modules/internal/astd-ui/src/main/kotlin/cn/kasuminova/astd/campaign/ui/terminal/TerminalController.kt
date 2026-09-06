package cn.kasuminova.astd.campaign.ui.terminal

/**
 * 分局终端 UI 状态控制器（纯逻辑，不触碰原版 UI 控件）。
 *
 * 持有当前 tab / 选中项，接收 UI 事件（切 tab、选中、主操作、重打回执），
 * 经 [BranchTerminalBackend] 回注 campaign 动作，并向装配层返回待执行的
 * 特效指令序列（[TerminalEffect]，含延迟调度）。控件树操作由装配层翻译执行，
 * 因此事件分发可脱离游戏环境单测。
 */
class TerminalController(
    private val backend: BranchTerminalBackend,
    initialTab: TerminalTab = TerminalTab.ORDERS,
) {

    var tab: TerminalTab = initialTab
        private set

    /** 当前选中工单 key（null=自动回落到列表首单）。 */
    var selectedOrderKey: String? = null
        private set

    /** 当前选中档案 id。 */
    var selectedArchiveId: String? = null
        private set

    /** 签署界面当前选中的文书（null=未选；签署后清空）。 */
    var archivalDoc: ArchivalChoice? = null
        private set

    /** 交易选当前选中的对象势力 id（仅文书=TRADE 时有效）。 */
    var selectedTradeFactionId: String? = null
        private set

    /** 当前视图数据（实时取快照映射）。 */
    fun view(): TerminalViewData =
        TerminalDataMapper.map(
            backend.snapshot(), tab, selectedOrderKey, selectedArchiveId,
            endingDoc = archivalDoc, endingTradeFaction = selectedTradeFactionId,
        )

    /** 切换 tab：刷新列表 + 详情逐行打印 + tab 音。 */
    fun selectTab(tab: TerminalTab): List<TerminalEffect> {
        if (tab == this.tab) return emptyList()
        this.tab = tab
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.TAB),
            TerminalEffect.RefreshTopbar(),
            TerminalEffect.RebuildList(),
            TerminalEffect.ReprintDetail(),
        )
    }

    /** 选中工单行：详情文书重开（逐行打印；行点击音由复选按钮自带音效承担）。 */
    fun selectOrder(key: String): List<TerminalEffect> {
        if (key == selectedOrderKey) return emptyList()
        selectedOrderKey = key
        return listOf(
            TerminalEffect.RebuildList(),
            TerminalEffect.ReprintDetail(),
        )
    }

    /** 选中档案行（锁定存目不可选，由映射层回落；此处仅记录已解锁条目）。 */
    fun selectArchive(id: String): List<TerminalEffect> {
        if (id == selectedArchiveId) return emptyList()
        selectedArchiveId = id
        return listOf(
            TerminalEffect.RebuildList(),
            TerminalEffect.ReprintDetail(),
        )
    }

    /**
     * 底部主操作（按选中工单状态分发）：
     * - 接取：盖章「已受理」→ 刷新；
     * - 追踪：标记目标重要 + 提示音；
     * - 交付核销：盖章「已核销」→ 回执打印（明细单 + 金额滚动）→ 章末 glitch（若触发）。
     */
    fun pressPrimary(): List<TerminalEffect> {
        val view = view()
        val order = view.selectedOrder ?: return emptyList()
        return when (TerminalDataMapper.primaryActionOf(order)) {
            TerminalAction.ACCEPT -> {
                if (!backend.postOrder(order.key)) {
                    listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
                } else {
                    listOf(
                        TerminalEffect.PlaySound(TerminalSound.STAMP),
                        TerminalEffect.StampSlam(StampKind.ACCEPTED),
                        TerminalEffect.RebuildList(),
                        TerminalEffect.ReprintDetail(),
                        TerminalEffect.RefreshTopbar(),
                    ).withDelays(stampSettle = true)
                }
            }
            TerminalAction.TRACK -> {
                if (!backend.trackOrder(order.key)) {
                    listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
                } else {
                    listOf(TerminalEffect.PlaySound(TerminalSound.TRACK))
                }
            }
            TerminalAction.SETTLE -> {
                val outcome = backend.settleOrder(order.key)
                if (!outcome.success) {
                    listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
                } else {
                    buildSettleEffects(order, outcome)
                }
            }
            TerminalAction.NONE -> emptyList()
        }
    }

    /** 已核销单「重打回执」：以锁定报价回放回执（结清奖金按账户流水实发记录补全，章末确认行按核销时记录回放）。 */
    fun pressReplayReceipt(): List<TerminalEffect> {
        val view = view()
        val order = view.selectedOrder ?: return emptyList()
        if (order.status != OrderStatus.SETTLED) return emptyList()
        val snapshot = backend.snapshot()
        val bonus = snapshot.ledger.firstOrNull {
            it.kind == LedgerKind.GROUP_BONUS && it.title == order.groupId
        }
        val receipt = ReceiptView(
            orderKey = order.key,
            serial = order.serial,
            i18nId = order.i18nId,
            payout = order.reward ?: 0,
            groupBonusId = bonus?.title,
            groupBonusAmount = bonus?.amount ?: 0,
            chapterCleared = order.chapterCleared,
            liquidationProgress = snapshot.liquidationProgress,
            date = snapshot.currentDate,
        )
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.RECEIPT_OPEN),
            TerminalEffect.ShowReceipt(receipt),
        )
    }

    // ─── 第五章「归档」结局事务（账户 tab 事务卡事件） ───

    /** 选中待签署文书（切换选中即重印事务卡正文；离开 TRADE 时清空候选势力选中态）。 */
    fun selectArchivalDoc(choice: ArchivalChoice): List<TerminalEffect> {
        if (choice == archivalDoc) return emptyList()
        archivalDoc = choice
        if (choice != ArchivalChoice.TRADE) selectedTradeFactionId = null
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.SELECT),
            TerminalEffect.ReprintDetail(),
        )
    }

    /** 选中交易候选势力（报价函行点击；重印事务卡）。 */
    fun selectTradeFaction(factionId: String): List<TerminalEffect> {
        if (factionId == selectedTradeFactionId) return emptyList()
        selectedTradeFactionId = factionId
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.SELECT),
            TerminalEffect.ReprintDetail(),
        )
    }

    /**
     * 确认签署：盖章 → 事务卡重印 → 演出回执链（清算 100%+反转回执 → 归档回执 → 无限期承包合同）。
     * 签署被拒（未选文书 / 交易选未选对象 / campaign 侧守卫拒绝）→ 拒音。
     */
    fun confirmSign(): List<TerminalEffect> {
        val choice = archivalDoc ?: return listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
        val tradeFaction = if (choice == ArchivalChoice.TRADE) {
            selectedTradeFactionId ?: return listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
        } else {
            null
        }
        val outcome = backend.signArchival(choice, tradeFaction)
            ?: return listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
        archivalDoc = null
        selectedTradeFactionId = null
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.STAMP),
            TerminalEffect.StampSlam(StampKind.SETTLED),
            TerminalEffect.RebuildList(),
            TerminalEffect.ReprintDetail(),
            TerminalEffect.RefreshTopbar(),
            TerminalEffect.PlaySound(TerminalSound.RECEIPT_OPEN),
            TerminalEffect.ShowNarrative(outcome.pages),
        ).withDelays(stampSettle = true, receipt = true)
    }

    /**
     * 「执行官」签发（特化二选一，选定不可更改）：盖章 → 演出回执链（签发回执 → 最终回执）。
     */
    fun chooseExecutorSpec(spec: ExecutorSpec): List<TerminalEffect> {
        val outcome = backend.issueExecutor(spec)
            ?: return listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.STAMP),
            TerminalEffect.StampSlam(StampKind.SETTLED),
            TerminalEffect.ReprintDetail(),
            TerminalEffect.PlaySound(TerminalSound.RECEIPT_OPEN),
            TerminalEffect.ShowNarrative(outcome.pages),
        ).withDelays(stampSettle = true, receipt = true)
    }

    /** 指定指挥舰（战斗特化任命）：盖章 → 事务卡重印。 */
    fun assignShip(memberId: String): List<TerminalEffect> {
        if (!backend.assignCommandShip(memberId)) {
            return listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
        }
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.STAMP),
            TerminalEffect.ReprintDetail(),
        )
    }

    /** 任命市场管理官（行政特化任命）：盖章 → 事务卡重印。 */
    fun appointMarket(marketId: String): List<TerminalEffect> {
        if (!backend.appointAdmin(marketId)) {
            return listOf(TerminalEffect.PlaySound(TerminalSound.REJECTED))
        }
        return listOf(
            TerminalEffect.PlaySound(TerminalSound.STAMP),
            TerminalEffect.ReprintDetail(),
        )
    }

    private fun buildSettleEffects(order: TerminalOrderView, outcome: SettleOutcome): List<TerminalEffect> {
        val receipt = ReceiptView(
            orderKey = order.key,
            serial = order.serial,
            i18nId = order.i18nId,
            payout = outcome.payout,
            groupBonusId = outcome.groupBonusId,
            groupBonusAmount = outcome.groupBonusAmount,
            chapterCleared = outcome.chapterCleared,
            liquidationProgress = outcome.liquidationProgress,
            date = backend.snapshot().currentDate,
        )
        val glitch = TerminalDataMapper.glitchForChapter(outcome.chapterCleared)
        val effects = mutableListOf(
            TerminalEffect.PlaySound(TerminalSound.STAMP),
            TerminalEffect.StampSlam(StampKind.SETTLED),
            TerminalEffect.RebuildList(),
            TerminalEffect.ReprintDetail(),
            TerminalEffect.RefreshTopbar(),
            TerminalEffect.PlaySound(TerminalSound.RECEIPT_OPEN),
            // 三章末 glitch：回执打印队列混入半行卡死工单（随回执打印注入）
            TerminalEffect.ShowReceipt(receipt, glitchSpec = if (glitch == GlitchSpec.HALF_LINE) glitch else null),
        )
        // 章末闪现（剧情钩子：一章末「现役？」/ 三章末半行工单卡死），自愈不解释
        glitch?.let { spec ->
            effects += TerminalEffect.PlaySound(TerminalSound.GLITCH)
            // TARGET_STATUS 的瞬替目标：核销后快照中本批（兜底全表）的一条已核销灰章行，
            // 装配层在列表 +0.7s 重建完成后按 key 瞬替该行状态章（doc 05：已结清列表里某一份）
            effects += TerminalEffect.GlitchFx(
                spec,
                targetOrderKey = if (spec == GlitchSpec.TARGET_STATUS) settledRowKey(order.groupId) else null,
            )
        }
        return effects.withDelays(stampSettle = true, receipt = true)
    }

    /** 一章末 glitch 瞬替目标行：核销完成后本批的首条已核销单（本批无则全表首条已核销单）。 */
    private fun settledRowKey(groupId: String): String? {
        val batches = TerminalDataMapper.mapOrders(backend.snapshot())
        val batch = batches.firstOrNull { it.groupId == groupId }
        return batch?.orders?.firstOrNull { it.status == OrderStatus.SETTLED }?.key
            ?: batches.flatMap { it.orders }.firstOrNull { it.status == OrderStatus.SETTLED }?.key
    }

    /**
     * 为动效序列分配延迟：盖章系（音/章/刷新）即时；回执在章面落稳后弹入；
     * glitch 在回执弹入瞬间闪现（视觉上「系统出了一次无人解释的错」）。
     */
    private fun List<TerminalEffect>.withDelays(stampSettle: Boolean = false, receipt: Boolean = false): List<TerminalEffect> {
        var stampSeen = false
        return map { effect ->
            val delay = when (effect) {
                is TerminalEffect.ShowReceipt -> if (receipt) StampTimeline.SETTLE + 0.1f else 0f
                is TerminalEffect.ShowNarrative -> if (receipt) StampTimeline.SETTLE + 0.1f else 0f
                is TerminalEffect.PlaySound -> when (effect.sound) {
                    TerminalSound.RECEIPT_OPEN -> if (receipt) StampTimeline.SETTLE + 0.1f else 0f
                    TerminalSound.GLITCH -> if (receipt) StampTimeline.SETTLE + 0.3f else 0f
                    else -> 0f
                }
                is TerminalEffect.GlitchFx -> if (receipt) StampTimeline.SETTLE + 0.3f else 0f
                is TerminalEffect.RebuildList, is TerminalEffect.ReprintDetail, is TerminalEffect.RefreshTopbar ->
                    if (stampSeen && stampSettle) StampTimeline.SETTLE else 0f
                is TerminalEffect.StampSlam -> {
                    stampSeen = true
                    0f
                }
            }
            effect.delayed(delay)
        }
    }
}

/** 盖章章面文种。 */
enum class StampKind { ACCEPTED, SETTLED }

/** 终端音效 id（原版 sounds.json；打印音/数字滚动/噪点均取原版条目）。 */
enum class TerminalSound(val soundId: String, val pitch: Float, val volume: Float) {
    /** tab 切换。 */
    TAB("ui_button_pressed", 1f, 0.8f),

    /** 列表选中。 */
    SELECT("ui_button_pressed", 1.4f, 0.5f),

    /** 逐行打印（行首）。 */
    PRINT_LINE("ui_typer_type", 1f, 0.35f),

    /** 盖章低频重击。 */
    STAMP("ui_cargo_machinery_drop", 0.6f, 1f),

    /** 操作被拒。 */
    REJECTED("ui_button_disabled_pressed", 1f, 0.8f),

    /** 追踪目标标记。 */
    TRACK("ui_create_waypoint", 1.2f, 0.8f),

    /** 回执单弹入。 */
    RECEIPT_OPEN("ui_typer_buzz", 1f, 0.6f),

    /** 金额数字滚动。 */
    RECEIPT_ROLL("ui_number_scrolling", 1f, 0.6f),

    /** 闪现噪点。 */
    GLITCH("ui_noise_static", 1f, 0.5f),

    /** 开机扫描线点亮。 */
    BOOT_SWEEP("ui_sensor_burst_on", 1.3f, 0.4f),

    /** 徽记淡入。 */
    BOOT_EMBLEM("ui_discovered_entity", 1.4f, 0.4f),

    /** 关闭终端。 */
    CLOSE("ui_go_dark_off", 1f, 0.6f),
}

/**
 * 装配层待执行的特效指令（[delaySeconds] 由控制器调度，装配层到时执行）。
 */
sealed class TerminalEffect {
    /** 距当前帧的延迟（s）。 */
    abstract val delaySeconds: Float

    /**
     * 该特效待执行期间是否屏蔽终端关闭（Esc/关闭按钮暂吞）。
     * 「盖章 → 回执弹出」窗口内关闭会截断回执叙事链，回执系特效标记为 true。
     */
    open val blocksClose: Boolean get() = false

    /** 返回带延迟的副本。 */
    abstract fun delayed(delay: Float): TerminalEffect

    /** 播放 UI 音效。 */
    data class PlaySound(val sound: TerminalSound, override val delaySeconds: Float = 0f) : TerminalEffect() {
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    /** 重建列表栏（状态章/批次进度可能变化）。 */
    data class RebuildList(override val delaySeconds: Float = 0f) : TerminalEffect() {
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    /** 详情正文重新逐行打印（切 tab / 开文书）。 */
    data class ReprintDetail(override val delaySeconds: Float = 0f) : TerminalEffect() {
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    /** 刷新顶栏（清算进度读数）。 */
    data class RefreshTopbar(override val delaySeconds: Float = 0f) : TerminalEffect() {
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    /** 盖章动效（章面砸落 + 震屏 + 墨渍扩散）。 */
    data class StampSlam(val kind: StampKind, override val delaySeconds: Float = 0f) : TerminalEffect() {
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    /** 回执打印（明细单弹入逐行打印 + 金额滚动；[glitchSpec]=HALF_LINE 时打印队列混入卡死行）。 */
    data class ShowReceipt(
        val receipt: ReceiptView,
        val glitchSpec: GlitchSpec? = null,
        override val delaySeconds: Float = 0f,
    ) : TerminalEffect() {
        override val blocksClose: Boolean get() = true
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    /** 叙事回执链（签署/签发演出；pages 按序弹出，关闭当前页弹出下一页）。 */
    data class ShowNarrative(
        val pages: List<NarrativeReceiptView>,
        override val delaySeconds: Float = 0f,
    ) : TerminalEffect() {
        override val blocksClose: Boolean get() = true
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    /**
     * 闪现 glitch（噪点 + 撕裂 + 文字瞬替，<0.5s 自愈）。
     *
     * [targetOrderKey]：TARGET_STATUS 瞬替的目标行工单 key（控制器在核销后快照中选定的
     * 已核销行；装配层按 key 找行状态章标签，找不到则放弃文字瞬替仅保留噪点/撕裂）。
     */
    data class GlitchFx(
        val spec: GlitchSpec,
        val targetOrderKey: String? = null,
        override val delaySeconds: Float = 0f,
    ) : TerminalEffect() {
        override fun delayed(delay: Float): TerminalEffect = copy(delaySeconds = delay)
    }

    companion object {
        /** 统一置零延迟（一次性立即执行）。 */
        fun immediate(effects: List<TerminalEffect>): List<TerminalEffect> = effects.map { it.delayed(0f) }
    }
}
