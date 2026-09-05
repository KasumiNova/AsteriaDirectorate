package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global
import java.util.LinkedHashSet

/**
 * 无限赏金（终局归档后《无限期承包合同》解锁）的存档持久化状态。
 *
 * 与 [BountyState] 的分工：主线状态不动，无限赏金自管三个稳定在册工单槽。
 * 与主线相同的约束：可序列化普通字段 + 无参构造，兼容 XStream 存档；
 * 新增字段必须带默认值，旧存档反序列化时保持默认。
 *
 * 内存口径：每个槽位只持有「当前一代」工单；账单只保留最近 [InfiniteBounties.BILL_CAP] 条，
 * 不累积无限历史。
 */
class InfiniteBountyState() {

    /**
     * 在册工单槽（固定 [InfiniteBounties.SLOT_COUNT] 个）。
     * 槽位稳定、内容滚动：一单交付后同槽刷新新一代随机工单。
     */
    @JvmField
    var slots: MutableList<InfiniteBountySlot> = mutableListOf(
        InfiniteBountySlot(), InfiniteBountySlot(), InfiniteBountySlot(),
    )

    /** 已击破目标、等待返回分局交付的工单 key（交付走 [InfiniteBounties.onSettled]）。 */
    @JvmField
    var pendingDelivery: MutableSet<String> = LinkedHashSet()

    /** 履约账单（续展类目）：只保留最近若干条，终端账户页展示用。 */
    @JvmField
    var bills: MutableList<InfiniteBountyBill> = ArrayList()

    /** 累计签发流水号（文书编号骑缝用，单调递增不复用）。 */
    @JvmField
    var totalSerials: Int = 0

    /** 记一笔账单并截断到上限（只留最近 [InfiniteBounties.BILL_CAP] 条）。 */
    fun addBill(bill: InfiniteBountyBill) {
        bills.add(bill)
        while (bills.size > InfiniteBounties.BILL_CAP) {
            bills.removeAt(0)
        }
    }

    companion object {
        /** Sector persistentData key。 */
        const val PERSISTENT_STATE_KEY: String = "astd_infinite_bounty_state"

        @JvmStatic
        fun getOrCreate(): InfiniteBountyState {
            val sector = Global.getSector() ?: return InfiniteBountyState()
            val pd = sector.persistentData
            val existing = pd[PERSISTENT_STATE_KEY]
            if (existing is InfiniteBountyState) return existing
            val created = InfiniteBountyState()
            pd[PERSISTENT_STATE_KEY] = created
            return created
        }
    }
}

/**
 * 一个在册工单槽的当前代内容（XStream 友好：普通字段 + 无参构造）。
 *
 * 字段完整保留重建 [BountyDef] 与发放报价所需的全部数据，
 * 读档后不依赖任何运行时缓存即可还原定义。
 */
class InfiniteBountySlot() {

    /** 当前代 MagicBounty 注册键（`astd_inf_s<槽位>_g<代次>`，每代唯一）；空串 = 尚未签发。 */
    @JvmField
    var key: String = ""

    /** 当前代次（该槽第几单，从 1 起）。 */
    @JvmField
    var generation: Int = 0

    /** 文书编号骑缝（如 WX-c209-0007／续展-01）。 */
    @JvmField
    var code: String = ""

    /** 文书危险等级（1..5）。 */
    @JvmField
    var dangerLevel: Int = 0

    /** 预设 FP（800..2800，走 02 全局缩放算法）。 */
    @JvmField
    var baselineFP: Int = 0

    /** 目标旗舰 variant id（模组遗存池）。 */
    @JvmField
    var flagshipVariantId: String = ""

    /** R 型词缀数量区间（随危险等级搭配）。 */
    @JvmField
    var affixRMin: Int = 0

    @JvmField
    var affixRMax: Int = 0

    /** 报酬区间（未乘 k_s 的基数）。 */
    @JvmField
    var rewardMin: Int = 0

    @JvmField
    var rewardMax: Int = 0

    /** 签发时锁定的报价（× k_s 后），终端展示与交付实发同值。 */
    @JvmField
    var quotedReward: Int = 0
}

/** 续展类目履约账单一行（XStream 友好：普通字段 + 无参构造）。 */
class InfiniteBountyBill() {

    /** 文书编号骑缝。 */
    @JvmField
    var code: String = ""

    /** 星区纪年日期文本。 */
    @JvmField
    var date: String = ""

    /** 实发金额（星币）。 */
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
