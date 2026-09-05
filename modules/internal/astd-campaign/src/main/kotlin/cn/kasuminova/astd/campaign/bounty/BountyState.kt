package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global
import java.util.LinkedHashSet

/**
 * 存档持久化的赏金系统状态。
 *
 * 注意：这里用“可序列化的普通字段 + 无参构造”以尽量兼容 Starsector 的 XStream 存档。
 * 新增字段必须带默认值，旧存档反序列化时保持默认。
 */
class BountyState() {

    /**
     * 已成功结清的主线单数量（含内部阶段单）。
     */
    @JvmField
    var mainCompleted: Int = 0

    /**
     * 承包商等级：0=未注册，序章结清后 1（注册为一级），章末递升，第四章末为 5（常规顶格）。
     * 结局归档后为「无限期承包商」特殊认证，见 [infiniteContractor]。
     */
    @JvmField
    var contractorLevel: Int = 0

    /**
     * 已解锁的词缀 id。
     *
     * 旧档兼容字段：affixes.md v3（D20）后词缀数量由难度系数搭配表决定，
     * 不再按主线进度解锁；本字段保留以避免旧存档反序列化丢字段。
     */
    @JvmField
    var unlockedAffixIds: MutableSet<String> = LinkedHashSet()

    /**
     * 已处理过“接受/生成舰队补丁”的 bounty key，避免重复重建 fleet。
     */
    @JvmField
    var patchedBountyKeys: MutableSet<String> = LinkedHashSet()

    /**
     * 已处理过“结算”的 bounty key（含失败终态，失败单随后会被重置重新挂出）。
     */
    @JvmField
    var concludedBountyKeys: MutableSet<String> = LinkedHashSet()

    /**
     * 已成功结清的 bounty key（批次/章节结清判定与 gating 的事实来源）。
     */
    @JvmField
    var succeededBountyKeys: MutableSet<String> = LinkedHashSet()

    /** 已赢下战斗、尚待实地回收或返回分局核销的工单。 */
    @JvmField
    var defeatedBountyKeys: MutableSet<String> = LinkedHashSet()

    /** 接取时锁定的星币报酬；终端报价、交付实发与读档后均使用同一值。 */
    @JvmField
    var quotedRewards: MutableMap<String, Int> = LinkedHashMap()

    /** 终端已确认交付、等待赏金管理脚本在主线程推进的工单。 */
    @JvmField
    var settlementRequests: MutableSet<String> = LinkedHashSet()

    /**
     * 已结清的结清组 id（批次/线路/章），见 [MainBounties.groups]。
     */
    @JvmField
    var clearedGroupIds: MutableSet<String> = LinkedHashSet()

    /**
     * 已结清的章节序号（0=序章，1..4=第一~四章）。
     */
    @JvmField
    var completedChapters: MutableSet<Int> = LinkedHashSet()

    /**
     * 清算序列进度（%）：由各章回执的剧本化读数推进（含第三章的反常回跳），
     * 章末回执显示；抵达 100 触发终局事件（档案处置申请）。
     */
    @JvmField
    var liquidationProgress: Float = 0f

    /**
     * 终局：第四章结清后「档案处置申请」已受理，等待玩家到分局空间站终端签署。
     */
    @JvmField
    var archivalPending: Boolean = false

    /**
     * 归档三选结果：""=未签署；"public"=公开 / "sealed"=封存 / "traded"=交易。
     * 对应 [BountyKeys.MEM_ARCHIVE_CHOICE]。
     */
    @JvmField
    var archiveChoice: String = ""

    /**
     * 交易选的定向移交对象势力 id（仅 archiveChoice == "traded" 时有值）。
     */
    @JvmField
    var archiveTradeFactionId: String? = null

    /**
     * 归档流程完成后签发《无限期承包合同》：随机无限赏金解锁。
     */
    @JvmField
    var infiniteContractor: Boolean = false

    /**
     * 承包商编号（序章签字时由分局登记生成；空串 = 未登记）。
     */
    @JvmField
    var contractorId: String = ""

    /**
     * 注册日期文本（序章签字时的星区纪年；空串 = 未登记）。
     */
    @JvmField
    var registerCycle: String = ""

    /**
     * 履约流水账（核销报酬/结清奖金/移交报酬），终端账户页直接展示。
     */
    @JvmField
    var ledgerEntries: MutableList<BountyLedgerEntry> = ArrayList()

    /**
     * 终局「执行官」特化是否已签发并登记到终端托管状态。
     * 核心不进入货舱，因此不存在可丢弃或交易的物品对象。
     */
    @JvmField
    var executiveCoreIssued: Boolean = false

    companion object {
        @JvmStatic
        fun getOrCreate(): BountyState {
            val sector = Global.getSector() ?: return BountyState()
            val pd = sector.persistentData
            val existing = pd[BountyKeys.PERSISTENT_STATE_KEY]
            if (existing is BountyState) return existing
            val created = BountyState()
            pd[BountyKeys.PERSISTENT_STATE_KEY] = created
            return created
        }
    }
}

/**
 * 履约流水账一行（XStream 存档友好：普通字段 + 无参构造）。
 */
class BountyLedgerEntry() {

    /** 文书编号骑缝（结清奖金/移交报酬用类目抬头）。 */
    @JvmField
    var code: String = ""

    /** 星区纪年日期文本。 */
    @JvmField
    var date: String = ""

    /** 金额（星币）。 */
    @JvmField
    var amount: Long = 0L

    /** 摘要（本地化后的显示文本）。 */
    @JvmField
    var note: String = ""

    constructor(code: String, date: String, amount: Long, note: String) : this() {
        this.code = code
        this.date = date
        this.amount = amount
        this.note = note
    }
}
