package cn.kasuminova.astd.campaign.ui

/**
 * 模组总 UI（分局终端）的数据源接口。
 *
 * UI 层与 campaign/bounty 实现解耦的唯一通道：UI 只面向本接口，
 * 具体实现（赏金系统对接）由 campaign 侧经 [DirectorateTerminalBackends.install] 注入。
 * 不存在任何默认/兜底实现——未注入时终端拒绝打开并记录错误，绝不以假数据假成功。
 *
 * 所有返回文本均为实现侧完成本地化后的显示文本。
 */
interface DirectorateTerminalDataSource {

    /** 拉取当前完整快照（每次打开终端或状态变更后调用）。 */
    fun snapshot(): TerminalSnapshot

    /**
     * 接取选中的工单（仅 [WorkOrderStatus.AVAILABLE] 状态可受理）。
     * @return 是否受理成功；false 时 UI 显示受理失败提示，不做假成功
     */
    fun acceptWorkOrder(orderId: String): Boolean

    /**
     * 追踪目标：为执行中（[WorkOrderStatus.ACTIVE]）的工单登记目标追踪意图
     * （星图标记/导航聚焦由 campaign 侧实现消费）。
     * @return 是否登记成功；false 时 UI 显示受理失败提示，不做假成功
     */
    fun trackWorkOrder(orderId: String): Boolean

    /**
     * 交付核销：为可交付（[WorkOrderStatus.READY_TO_SETTLE]）的工单登记交付/核销意图
     * （导航/标记由 campaign 侧消费 [DirectorateTerminalKeys.SETTLEMENT_FOCUS] 完成）。
     * @return 是否登记成功；false 时 UI 显示受理失败提示，不做假成功
     */
    fun requestSettlement(orderId: String): Boolean

    /**
     * 签署结局（终局清算）。UI 在调用前已向玩家出示拟制文书并取得明确确认；
     * 仅 available 的选项可被受理，签署不可反悔。
     * @return 是否受理成功
     */
    fun chooseEnding(endingId: String): Boolean
}

/**
 * 分局终端状态在 memory / persistentData 中的 key 约定。
 *
 * 写入方（campaign 侧赏金系统）与读取方的共同契约；
 * 全部为可 XStream 序列化的普通类型（String / Int / Long / Float / Boolean / List / Map），
 * 不依赖任何 bounty 类。
 */
object DirectorateTerminalKeys {

    /** String：承包商编号。 */
    const val CONTRACTOR_ID: String = "astd_terminal_contractor_id"

    /** Int：承包商等级（一级/二级…）。 */
    const val CONTRACTOR_LEVEL: String = "astd_terminal_contractor_level"

    /** String：注册日期（“本审计周期”纪年文本）。 */
    const val REGISTER_CYCLE: String = "astd_terminal_register_cycle"

    /**
     * Float：清算序列进度（百分比 0..100，由剧情脚本按章节推进，如 97.3f）。
     * 键缺失 = 第二章末之前，终端不显示清算行。
     */
    const val LIQUIDATION_PROGRESS: String = "astd_terminal_liquidation_progress"

    /** List<Map<String, Any?>>：批次列表，字段见 F_BATCH_*，工单字段见 F_ORDER_*。 */
    const val BATCHES: String = "astd_terminal_batches"

    /** List<Map<String, Any?>>：档案列表，字段见 F_ARCHIVE_*。 */
    const val ARCHIVES: String = "astd_terminal_archives"

    /** List<Map<String, Any?>>：履约流水账，字段见 F_LEDGER_*（编号复用 [F_ORDER_CODE]）。 */
    const val LEDGER: String = "astd_terminal_ledger"

    /** List<Map<String, Any?>>：结局选项，字段见 F_ENDING_*。 */
    const val ENDINGS: String = "astd_terminal_endings"

    /** String：已登记的结局选择 id（[DirectorateTerminalDataSource.chooseEnding] 写入）。 */
    const val ENDING_CHOSEN: String = "astd_terminal_ending_chosen"

    /**
     * Sector memory：核销意图焦点（工单 id）。
     * 由 [DirectorateTerminalDataSource.requestSettlement] 写入，campaign 侧导航系统消费后清除。
     */
    const val SETTLEMENT_FOCUS: String = "\$astd_terminal_settlement_focus"

    // --- 批次 map 字段 -------------------------------------------------------
    const val F_BATCH_ID: String = "id"
    const val F_BATCH_CHAPTER: String = "chapter"
    const val F_BATCH_TITLE: String = "title"
    const val F_BATCH_ORDERS: String = "orders"

    // --- 工单 map 字段 -------------------------------------------------------
    const val F_ORDER_ID: String = "id"
    const val F_ORDER_CODE: String = "code"
    const val F_ORDER_TITLE: String = "title"

    /** Int：危险等级 1..6；[THREAT_TIER_UNRATED]（0）= 等级从缺。 */
    const val F_ORDER_TIER: String = "tier"
    const val F_ORDER_TARGET: String = "target"

    /** String：[WorkOrderStatus] 的枚举名（AVAILABLE / ACTIVE / READY_TO_SETTLE / SETTLED）。 */
    const val F_ORDER_STATUS: String = "status"
    const val F_ORDER_COMMISSION: String = "commission"

    /** List<String>：条款。 */
    const val F_ORDER_CLAUSES: String = "clauses"
    const val F_ORDER_REMARK: String = "remark"
    const val F_ORDER_ISSUE_DATE: String = "issueDate"

    /** Long/Int：星币报酬。 */
    const val F_ORDER_REWARD: String = "reward"

    // --- 档案 map 字段 -------------------------------------------------------
    const val F_ARCHIVE_ID: String = "id"

    /** Int：所属层。 */
    const val F_ARCHIVE_LAYER: String = "layer"
    const val F_ARCHIVE_TITLE: String = "title"

    /** List<String>：正文段落。 */
    const val F_ARCHIVE_BODY: String = "body"

    /** Boolean：是否已解锁可读。 */
    const val F_ARCHIVE_UNLOCKED: String = "unlocked"

    // --- 流水账 map 字段 -----------------------------------------------------
    const val F_LEDGER_DATE: String = "date"

    /** Long/Int：金额（星币）。 */
    const val F_LEDGER_AMOUNT: String = "amount"
    const val F_LEDGER_NOTE: String = "note"

    // --- 结局 map 字段 -------------------------------------------------------
    const val F_ENDING_ID: String = "id"
    const val F_ENDING_TITLE: String = "title"
    const val F_ENDING_DESC: String = "description"

    /** Boolean：受理条件是否已满足。 */
    const val F_ENDING_AVAILABLE: String = "available"
}
