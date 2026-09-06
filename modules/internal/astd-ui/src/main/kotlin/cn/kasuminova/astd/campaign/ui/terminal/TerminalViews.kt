package cn.kasuminova.astd.campaign.ui.terminal

/**
 * 分局终端全屏 UI 的数据模型与后端契约（doc `docs/design/ui/00-模组总UI设计.md`）。
 *
 * 分层：
 * - [TerminalSnapshot] 族：campaign 侧一次性装配的**输入快照**（只含可序列化纯数据，
 *   不含任何原版 UI/游戏对象引用），供 [TerminalDataMapper] 做纯逻辑映射；
 * - 视图族（[TerminalOrderView] 等）：映射产物，UI 装配层消费；
 * - [BranchTerminalBackend]：UI 事件回注 campaign 层的动作接口（由 campaign 侧实现）。
 *
 * 本文件不允许出现 Global/UI 控件依赖，保证 [TerminalDataMapper] 可脱离游戏环境单测。
 */

/** 终端三个 tab（工单终端 / 档案室 / 承包商账户）。 */
enum class TerminalTab { ORDERS, ARCHIVES, ACCOUNT }

/**
 * 工单生命周期原始态（快照输入）：
 * - [POSTABLE] gating 已满足、未挂出（待接取）；
 * - [POSTED] 已挂出、目标在世（执行中）；
 * - [DESTROYED] 目标已击毁、待交付核销；
 * - [SETTLED] 已核销。
 */
enum class OrderLifecycle { POSTABLE, POSTED, DESTROYED, SETTLED }

/**
 * 工单在终端列表中的展示状态章（设计稿三态：待接取橙 / 执行中青 / 已核销灰）。
 *
 * [AWAITING_SETTLEMENT]（已击毁待交付）在列表章上归入「执行中」观感（青色），
 * 但底部操作切换为「交付核销」——委托在核销前对分局而言始终处于未结执行态。
 */
enum class OrderStatus { AVAILABLE, ACTIVE, AWAITING_SETTLEMENT, SETTLED }

/** 底部主操作（按选中工单状态切换）。 */
enum class TerminalAction { ACCEPT, TRACK, SETTLE, NONE }

/** 流水行类型（单票报酬 / 结清奖金）。 */
enum class LedgerKind { ORDER, GROUP_BONUS }

// ─── 第五章「归档」结局视图（docs/story/13；campaign 侧状态机见 EndingProgression） ───

/**
 * 归档三选（终端侧口径；campaign 侧状态机为 EndingProgression.Choice，装配层按 name 映射）。
 */
enum class ArchivalChoice { PUBLISH, SEAL, TRADE }

/** 「执行官」特化方向（同上，映射 EndingProgression.ExecutorSpec）。 */
enum class ExecutorSpec { COMBAT, ADMIN }

/** 交易候选势力快照（报价函：报价在 campaign 侧按种子确定性生成，此处直接展示）。 */
data class TradeCandidateSnapshot(val factionId: String, val factionName: String, val quote: Int)

/** 指挥舰候选快照（玩家舰队舰只；旗舰默认置顶）。 */
data class ShipCandidateSnapshot(val memberId: String, val shipName: String, val flagship: Boolean)

/** 行政任命候选市场快照（玩家殖民地）。 */
data class MarketCandidateSnapshot(val marketId: String, val marketName: String)

/**
 * 结局事务快照（档案处置签署 / 执行官签发与任命的终端数据源）。
 *
 * @property archivalPending 四章末清算 100% 后挂起（档案处置申请待签署）
 * @property archivalChoice 已签署的三选结果（null=未签署）
 * @property tradeFactionId 交易选对象势力 id（仅 TRADE）
 * @property tradeCandidates 交易候选势力（含锁定报价；签署前展示报价函）
 * @property executorIssued 「执行官」是否已签发（签发后特化不可更改）
 * @property executorSpec 已签发特化（null=未签发）
 * @property hasExecutorItem 玩家货舱是否持有签发物品（对话入口 gating 由 campaign 侧自行判定）
 * @property commandShipName 战斗特化：当前指挥舰名（null=未指定）
 * @property adminMarketName 行政特化：当前任命市场名（null=未任命）
 * @property ships / markets 任命候选列表（仅在对应待办阶段需要展示）
 * @property archivesReadOnly 档案室是否已转入「已归档」只读（公开/交易选）
 */
data class EndingSnapshot(
    val archivalPending: Boolean = false,
    val archivalChoice: ArchivalChoice? = null,
    val tradeFactionId: String? = null,
    val tradeCandidates: List<TradeCandidateSnapshot> = emptyList(),
    val executorIssued: Boolean = false,
    val executorSpec: ExecutorSpec? = null,
    val hasExecutorItem: Boolean = false,
    val commandShipName: String? = null,
    val adminMarketName: String? = null,
    val ships: List<ShipCandidateSnapshot> = emptyList(),
    val markets: List<MarketCandidateSnapshot> = emptyList(),
    val archivesReadOnly: Boolean = false,
)

/**
 * 叙事回执行（签署演出回执链的文本行；key 为 i18n 键，终端以 `asteria_directorate` 表解析）。
 *
 * @property dim 以弱化色打印（备注/附言行）
 */
data class NarrativeLine(val key: String, val vars: Map<String, String> = emptyMap(), val dim: Boolean = false)

/**
 * 叙事回执页（无工单明细的纯文书回执：标题 + 逐行打印 + 可选金额滚动）。
 *
 * @property amount 金额滚动目标值（0=无金额行）
 * @property amountKey 金额行前缀的 i18n 键（amount>0 时必填）
 */
data class NarrativeReceiptView(
    val titleKey: String,
    val lines: List<NarrativeLine>,
    val amount: Int = 0,
    val amountKey: String = "",
)

/** 叙事回执链动作结果（签署/签发；pages 按序弹出，关闭当前页弹出下一页）。 */
data class NarrativeOutcome(val pages: List<NarrativeReceiptView>)

/**
 * 单张工单的快照（campaign 侧从主线注册表 + 持久化状态装配）。
 *
 * @property key 工单 key（动作回传用）
 * @property serial 文书编号（如 YJ-c206-1102／核销-17）
 * @property i18nId 工单文案短 id（bounty 表 `main.<i18nId>.*`）
 * @property summary 列表行委托摘要（campaign 侧按 i18n 键 `main.<i18nId>.summary` 解析装配；
 *   UI 层直接展示，不解析文书正文）
 * @property groupId 结清组 id（批次分组键，组显示名取 strings `story.account.group.<groupId>`）
 * @property chapter 所属章节（0=序章）
 * @property threatTier 危险等级（1~6；罗马数字徽记由 UI 层按 `ui.terminal.tier.<n>` 解析）
 * @property dangerOmitted 危险等级栏留白（「等级从缺」，四章 ZQ）
 * @property clauseCount 追加条款条数（i18n 键 `main.<i18nId>.clause.1..N`）
 * @property stageIndex / stageCount 多阶段合并单当前阶段（ZW 四阶段 / ZQ 三阶段；单阶段恒 0/1）
 * @property nodeDrivenStages 前 N 阶段由引力节点拔除驱动（仅 ZW=3）
 * @property lifecycle 生命周期原始态
 * @property quotedReward 接取时锁定的整单报价（未报价为 null——待接取单常态）
 * @property requiredItemId / hasRequiredItem 核销交割物要求与当前持有状态
 * @property affixIds 固定词缀表（无限赏金：生成时抽取锁定；详情卡「追加条款」按 `affix.<id>.clause` 解析）
 * @property descVars 文书正文 i18n 命名变量（无限赏金：serial/faction/fp 等；主线工单为空表）
 * @property chapterCleared 本单核销时结清的章节（仅「触发章末」的那一单非空；重打回执的章末确认行数据源）
 */
data class OrderSnapshot(
    val key: String,
    val serial: String,
    val i18nId: String,
    val summary: String,
    val groupId: String,
    val chapter: Int,
    val threatTier: Int,
    val dangerOmitted: Boolean,
    val clauseCount: Int,
    val stageIndex: Int,
    val stageCount: Int,
    val nodeDrivenStages: Int,
    val lifecycle: OrderLifecycle,
    val quotedReward: Int?,
    val requiredItemId: String?,
    val hasRequiredItem: Boolean,
    val affixIds: List<String> = emptyList(),
    val descVars: Map<String, String> = emptyMap(),
    val chapterCleared: Int? = null,
)

/** 档案快照（解锁判定已在 campaign 侧完成）。 */
data class ArchiveSnapshot(val id: String, val layer: Int, val unlocked: Boolean)

/** 账户流水快照行（title：工单编号或结清组 id）。 */
data class LedgerSnapshot(val title: String, val amount: Int, val kind: LedgerKind)

/**
 * 终端全量快照：一次 UI 刷新所需的全部数据。
 *
 * @property orders 可见工单（gating 未满足的工单由 campaign 侧直接不放入本表，即「gating 隐藏」）
 * @property groupTotals 各结清组工单总数（含隐藏单，用于组头「结清 x/y」分母）
 * @property chapter 当前开放章节（清算进度顶栏显隐判定用）
 * @property currentDate 战役时钟日期串（回执/档案调阅时间）
 * @property liquidationProgress 清算序列进度（%）
 * @property contractorLevel 承包商等级（0=未注册）
 * @property settledCount 已核销主线工单数
 * @property ledger 履约流水（已核销单票 + 结清奖金）
 * @property archives 全部档案（含未解锁存目条目）
 * @property ending 结局事务快照（第五章；未进入结局流程时为空默认值）
 */
data class TerminalSnapshot(
    val orders: List<OrderSnapshot>,
    val groupTotals: Map<String, Int>,
    val chapter: Int,
    val currentDate: String,
    val liquidationProgress: Float,
    val contractorLevel: Int,
    val settledCount: Int,
    val ledger: List<LedgerSnapshot>,
    val archives: List<ArchiveSnapshot>,
    val ending: EndingSnapshot = EndingSnapshot(),
)

/** 工单终端行视图。 */
data class TerminalOrderView(
    val key: String,
    val serial: String,
    val i18nId: String,
    /** 列表行委托摘要（快照装配时按 i18n 键解析，UI 直接展示）。 */
    val summary: String,
    val groupId: String,
    val threatTier: Int,
    val dangerOmitted: Boolean,
    val clauseCount: Int,
    val stageIndex: Int,
    val stageCount: Int,
    val nodeDrivenStages: Int,
    val status: OrderStatus,
    val reward: Int?,
    val requiredItemId: String?,
    val hasRequiredItem: Boolean,
    /** 固定词缀表（无限赏金；详情卡「追加条款」按 `affix.<id>.clause` 解析，取代 clauseCount 序号条款）。 */
    val affixIds: List<String> = emptyList(),
    /** 文书正文 i18n 命名变量（无限赏金 desc；主线为空表）。 */
    val descVars: Map<String, String> = emptyMap(),
    /** 本单核销时结清的章节（仅触发章末的那一单非空；重打回执与原结算回执口径一致）。 */
    val chapterCleared: Int? = null,
) {
    /** 多阶段合并单（ZW/ZQ）对外单卡呈现，内部阶段由此暴露。 */
    val multiStage: Boolean get() = stageCount > 1
}

/** 批次分组视图（组头显示批次名 + 结清进度 settled/total）。 */
data class OrderBatchView(
    val groupId: String,
    val settled: Int,
    val total: Int,
    val orders: List<TerminalOrderView>,
)

/** 档案条目视图（unlocked=false 即灰色存目「依保密条令不予展示」）。 */
data class ArchiveEntryView(val id: String, val layer: Int, val unlocked: Boolean, val indexInLayer: Int)

/** 档案层分组视图。 */
data class ArchiveLayerView(val layer: Int, val unlocked: Int, val total: Int, val entries: List<ArchiveEntryView>)

/** 账户页视图。 */
data class TerminalAccountView(
    val contractorLevel: Int,
    val chapter: Int,
    val currentDate: String,
    val liquidationProgress: Float,
    val settledCount: Int,
    val ledger: List<TerminalLedgerLine>,
    /** 本周期贷方合计（星币）。 */
    val totalPayout: Int,
)

/** 账户流水行视图。 */
data class TerminalLedgerLine(val title: String, val amount: Int, val kind: LedgerKind)

/** 一次刷新的全量视图数据（当前 tab + 三 tab 各自内容 + 顶栏显隐）。 */
data class TerminalViewData(
    val tab: TerminalTab,
    val batches: List<OrderBatchView>,
    val archiveLayers: List<ArchiveLayerView>,
    val account: TerminalAccountView,
    /** 清算序列进度顶栏行是否显示（第三章起）。 */
    val showLiquidationTopbar: Boolean,
    val selectedOrder: TerminalOrderView?,
    val selectedArchive: ArchiveEntryView?,
    /** 结局事务视图（账户 tab 顶部事务卡的数据源；未进入结局流程时 stage=NONE）。 */
    val ending: EndingView,
)

/**
 * 结局事务阶段（账户 tab 顶部事务卡的呈现状态机）。
 *
 * 推进：NONE →（四章末清算 100%）→ AWAITING_SIGN →（签署）→ AWAITING_EXECUTOR_SPEC
 * →（签发）→ AWAITING_COMMAND_SHIP / AWAITING_ADMIN_MARKET →（任命）→ COMPLETE。
 */
enum class EndingStage { NONE, AWAITING_SIGN, AWAITING_EXECUTOR_SPEC, AWAITING_COMMAND_SHIP, AWAITING_ADMIN_MARKET, COMPLETE }

/**
 * 结局事务视图（[EndingSnapshot] + 阶段推导 + 终端选中态）。
 *
 * @property selectedDoc 签署界面当前选中的文书（null=未选；签署按钮可用性的数据源）
 * @property selectedTradeFaction 交易选当前选中的对象势力 id（仅文书=TRADE 时有效）
 */
data class EndingView(
    val stage: EndingStage,
    val snapshot: EndingSnapshot,
    val selectedDoc: ArchivalChoice?,
    val selectedTradeFaction: String?,
)

/**
 * 核销结算结果（回执打印动效的数据源；campaign 侧由 MainlineProgression.SettleResult 映射）。
 *
 * @property payout 单票报酬（整单锁定报价）
 * @property groupBonusId / groupBonusAmount 本次触发的结清组奖金（无则 null/0）
 * @property chapterCleared 本次结清的章节（触发章末钩子/glitch 判定用）
 * @property liquidationProgress 结算后的清算序列进度（%）
 * @property rejectReason 失败原因（内部标识，记日志用）
 */
data class SettleOutcome(
    val success: Boolean,
    val orderKey: String,
    val payout: Int = 0,
    val groupBonusId: String? = null,
    val groupBonusAmount: Int = 0,
    val chapterCleared: Int? = null,
    val liquidationProgress: Float = 0f,
    val rejectReason: String? = null,
)

/** 回执打印视图（明细单逐行打印 + 金额数字滚动到位的数据）。 */
data class ReceiptView(
    val orderKey: String,
    val serial: String,
    val i18nId: String,
    val payout: Int,
    val groupBonusId: String?,
    val groupBonusAmount: Int,
    val chapterCleared: Int?,
    val liquidationProgress: Float,
    val date: String,
)

/** 闪现 glitch 变体（剧情节点驱动；一章末「现役？」/ 三章末半行工单卡死）。 */
enum class GlitchSpec {
    /** 一章末：选中工单状态章瞬替为「目标状态：现役？」。 */
    TARGET_STATUS,

    /** 三章末：文书打印队列混入半行卡死工单（打印至半截后自愈抹除）。 */
    HALF_LINE,
}

/**
 * 分局终端的数据/动作后端（campaign 侧实现注入；UI 事件经此回流游戏侧副作用）。
 */
interface BranchTerminalBackend {
    /** 装配当前全量快照（每次 UI 刷新调用）。 */
    fun snapshot(): TerminalSnapshot

    /** 接取工单（立即挂出并激活）。 */
    fun postOrder(key: String): Boolean

    /** 追踪目标：将执行中工单的目标舰队标记为重要（Misc.makeImportant）。 */
    fun trackOrder(key: String): Boolean

    /** 交付核销：内含交割物校验与消耗，成功时发放报酬并触发章末钩子。 */
    fun settleOrder(key: String): SettleOutcome

    /**
     * 归档签署（第五章三选）：落账三选效果 → 势力强化/关系写入/交易报酬发放 →
     * 《无限期承包合同》生效（无限赏金槽位初始化）。
     *
     * @param tradeFactionId 交易选的对象势力（仅 choice=TRADE 必传；候选见快照 tradeCandidates）
     * @return 演出回执链（清算 100%+反转回执 → 归档回执 → 无限期承包合同）；null=签署被拒（已签署/条件不满足）
     */
    fun signArchival(choice: ArchivalChoice, tradeFactionId: String?): NarrativeOutcome?

    /**
     * 「执行官」签发（归档签署完成后二选一，选定不可更改）：物品入玩家货舱。
     *
     * @return 演出回执链（签发回执 → 最终回执）；null=签发被拒（未签署归档/已签发）
     */
    fun issueExecutor(spec: ExecutorSpec): NarrativeOutcome?

    /** 指定指挥舰（战斗特化；舰只离场/损毁后可重新指定）。 */
    fun assignCommandShip(memberId: String): Boolean

    /** 任命市场管理官（行政特化；重复任命更换市场，旧市场由 campaign 侧清理）。 */
    fun appointAdmin(marketId: String): Boolean
}
