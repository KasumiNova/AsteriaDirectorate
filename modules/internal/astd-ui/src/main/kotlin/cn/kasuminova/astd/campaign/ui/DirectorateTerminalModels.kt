package cn.kasuminova.astd.campaign.ui

/**
 * 模组总 UI（分局终端）的纯数据模型。
 *
 * 设计约束：UI 层不依赖 campaign/bounty 的具体实现类，所有状态经
 * [DirectorateTerminalDataSource] 快照进入；字符串均为数据源侧完成本地化后的显示文本。
 */

/** 终端页签。 */
enum class TerminalTab { WORK_ORDERS, ARCHIVES, ACCOUNT }

/** 工单状态章。 */
enum class WorkOrderStatus {
    /** 待接取。 */
    AVAILABLE,

    /** 执行中（已接取，目标未消灭）。UI 操作：追踪目标。 */
    ACTIVE,

    /** 可交付（目标已消灭，铅封回收物在手）。UI 操作：交付核销。 */
    READY_TO_SETTLE,

    /** 已核销（批次结清进度只统计本状态）。 */
    SETTLED,
}

/**
 * 危险等级「从缺」约定值：目标超出等级表（第四章 ZQ 工单），
 * 列表与文书一律显示「从缺」，不得向最高级（V/VI）钳制。
 */
const val THREAT_TIER_UNRATED: Int = 0

/** 从缺/契约外等级的徽记文本（ASCII 之外的破折号原版字体可渲染）。 */
const val THREAT_TIER_UNRATED_BADGE: String = "——"

/**
 * 一份工单（赏金文书）。
 *
 * @param code 文书编号骑缝，如 XW-c206-0447／核销-03
 * @param threatTier 危险等级：1..6 以罗马数字徽记呈现；[THREAT_TIER_UNRATED]（0）表示「等级从缺」
 * @param commission 委托事项正文
 * @param clauses 条款列表
 * @param remark 备注栏
 * @param issueDate 签发日期（纪年文本）
 * @param reward 星币报酬
 */
data class WorkOrder(
    val id: String,
    val code: String,
    val title: String,
    val threatTier: Int,
    val targetSummary: String,
    val status: WorkOrderStatus,
    val commission: String,
    val clauses: List<String> = emptyList(),
    val remark: String = "",
    val issueDate: String = "",
    val reward: Long = 0L,
)

/**
 * 一批工单（章节内按批次发放，整批结清后挂出下一批）。
 */
data class WorkOrderBatch(
    val id: String,
    /** 所属章节标题（章节状态展示）。 */
    val chapterTitle: String,
    /** 批次名。 */
    val title: String,
    val orders: List<WorkOrder>,
) {
    /** 已核销单数（批次结清进度分子）。 */
    val settledCount: Int
        get() = orders.count { it.status == WorkOrderStatus.SETTLED }

    val totalCount: Int
        get() = orders.size

    /** 批次是否已整批结清。 */
    val isCleared: Boolean
        get() = orders.isNotEmpty() && settledCount == totalCount
}

/**
 * 一份档案（档案室按“层”分组，阅读解锁制）。
 *
 * @param unlocked false 时仅显示灰色存目条目（依保密条令不予展示），正文不开放
 */
data class ArchiveEntry(
    val id: String,
    val layer: Int,
    val title: String,
    val body: List<String>,
    val unlocked: Boolean,
)

/** 履约流水账一行。 */
data class LedgerEntry(
    val orderCode: String,
    val date: String,
    val amount: Long,
    val note: String,
)

/** 结局选项（终局清算选择，条件满足才可受理）。 */
data class EndingOption(
    val id: String,
    val title: String,
    val description: String,
    val available: Boolean,
)

/**
 * 终端一次打开/刷新时的完整快照。
 *
 * @param liquidationProgress 清算序列进度（百分比，如 97.3f 表示 97.3%），数值由剧情脚本按章节推进；
 *   `null` 表示尚未开放显示（第二章末之前），UI 完全不渲染该行——
 *   清算数字不得由工单完成数/总数伪造，二者仅作为批次结清进度展示。
 */
data class TerminalSnapshot(
    val contractorId: String,
    val contractorLevel: Int,
    val registerCycle: String,
    val batches: List<WorkOrderBatch> = emptyList(),
    val archives: List<ArchiveEntry> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val liquidationProgress: Float? = null,
    val endings: List<EndingOption> = emptyList(),
) {
    /** 档案按层分组（层号升序）。 */
    val archiveLayers: Map<Int, List<ArchiveEntry>>
        get() = archives.groupBy { it.layer }.toSortedMap()

    /** 全部工单（跨批次平铺）。 */
    val allOrders: List<WorkOrder>
        get() = batches.flatMap { it.orders }

    fun findOrder(orderId: String): WorkOrder? = allOrders.firstOrNull { it.id == orderId }

    fun findArchive(archiveId: String): ArchiveEntry? = archives.firstOrNull { it.id == archiveId }
}

/**
 * 危险等级罗马数字徽记（1→I … 6→VI；ASCII 拼写保证原版字体可渲染）。
 *
 * [THREAT_TIER_UNRATED]（0）及一切契约外取值一律呈现 [THREAT_TIER_UNRATED_BADGE]：
 * 第三章把等级上限撑到六级后，第四章目标连六级也容不下——从缺是等级体系能给出的最高评价，
 * 绝不钳制回 V/VI。
 */
fun threatTierBadge(tier: Int): String = when (tier) {
    1 -> "I"
    2 -> "II"
    3 -> "III"
    4 -> "IV"
    5 -> "V"
    6 -> "VI"
    else -> THREAT_TIER_UNRATED_BADGE
}
