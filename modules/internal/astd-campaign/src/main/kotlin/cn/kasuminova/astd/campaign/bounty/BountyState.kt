package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global

/**
 * 存档持久化的赏金系统状态。
 *
 * 注意：这里用“可序列化的普通字段 + 无参构造”以尽量兼容 Starsector 的 XStream 存档。
 *
 * v3 词缀语义：S/M 型词缀常驻开放（不再有按进度解锁的词缀池），
 * R 型词缀不由进度解锁，由赏金定义侧的 allowRAffixes 开关控制（仅第三章赏金与结局后无限赏金）。
 */
class BountyState {

    /**
     * 已完成的主线数量（框架保留，内容重做后接入驱动源）。
     */
    @JvmField
    var mainCompleted: Int = 0

    /**
     * 已处理过“接受/生成舰队补丁”的 bounty key，避免重复重建 fleet。
     */
    @JvmField
    var patchedBountyKeys: MutableSet<String> = LinkedHashSet()

    /**
     * 已处理过“结算”的 bounty key。主线多阶段工单以 `key#stageIndex` 记录各阶段。
     */
    @JvmField
    var concludedBountyKeys: MutableSet<String> = LinkedHashSet()

    // ─── 主线状态（docs/story 02~13） ───

    /**
     * 承包商等级：0=未注册，序章核销后注册为一级，一/二/三/四章末递升至五级；
     * 结局「归档」后转 [indefiniteContractor]（无限期承包商，第五章实装）。
     */
    @JvmField
    var contractorLevel: Int = 0

    /** 无限期承包商认证标记（第五章「归档」流程签发，本阶段仅预留）。 */
    @JvmField
    var indefiniteContractor: Boolean = false

    /**
     * 当前开放章节：0=序章未结清，1~4=对应章节开放中，5=四章结清后归档挂起。
     */
    @JvmField
    var currentChapter: Int = 0

    /** 已结清的结清组 id（[MainBounties.groups]）。 */
    @JvmField
    var clearedGroups: MutableSet<String> = LinkedHashSet()

    /**
     * 清算序列进度（%）。基准 97.3% 为清算程序两百年自动推进的读数（02 文档口径），
     * 玩家核销的工单在其上按条目增量推进；三章单 2 后按定稿发生 -0.5% 反常跳动。
     * 仅在章末/回执上显示，显示逻辑由 UI 层消费本值。
     */
    @JvmField
    var liquidationProgress: Float = MainlineProgression.LIQUIDATION_BASELINE

    /** 四章末终局事件后置位：档案处置申请已受理，待经办人签署（→ 第五章「归档」）。 */
    @JvmField
    var archivalPending: Boolean = false

    /** 当前已挂出（激活中）的主线工单 key。 */
    @JvmField
    var postedWorkOrders: MutableSet<String> = LinkedHashSet()

    /** 已击毁、等待分局终端交付核销的主线工单 key（settleWorkOrder 的输入集）。 */
    @JvmField
    var destroyedWorkOrders: MutableSet<String> = LinkedHashSet()

    /** 已核销（交付结算完成）的主线工单 key。 */
    @JvmField
    var settledWorkOrders: MutableSet<String> = LinkedHashSet()

    /** 多阶段工单当前执行阶段下标（key → stageIndex；缺省 0）。 */
    @JvmField
    var workOrderStageIndex: MutableMap<String, Int> = HashMap()

    /**
     * 接取时锁定的报价（`key#stageIndex` → 星币），结算按锁定值发放。
     */
    @JvmField
    var quotedRewards: MutableMap<String, Int> = HashMap()

    /**
     * 接取（挂出）时锁定的舰队组建（lockKey → [LockedFleetPlan]；主线 lockKey = `key#stageIndex`，
     * 无限赏金 lockKey = 工单 key 本身即含换代序号）。锁定内含舰载核心表与核心打捞表，
     * 保证「掉落的正是舰队里装的」；失败重挂/重复构建沿用首次锁定（与 [quotedRewards] 同模式）。
     */
    @JvmField
    var lockedFleetPlans: MutableMap<String, LockedFleetPlan> = HashMap()

    /** 各结清组奖金发放记录（组 id → 实发金额），用于回执明细与防重复发放。 */
    @JvmField
    var grantedGroupBonuses: MutableMap<String, Int> = HashMap()

    /** 已触发的章末钩子标记（封存类目解锁 / 识别码重挂 / 半行工单 / 终局回执）。 */
    @JvmField
    var chapterHooks: MutableSet<String> = LinkedHashSet()

    /**
     * 触发章节结清的工单（章节号 → 核销该章末单的工单 key）。
     * 终端「重打回执」的章末确认行按此回放，与原结算回执口径一致。
     */
    @JvmField
    var chapterClearingOrders: MutableMap<Int, String> = HashMap()

    // ─── 第五章「归档」结局状态（docs/story/13） ───

    /** 归档三选结果（EndingProgression.Choice 名；null=未签署）。签署后不可反悔。 */
    @JvmField
    var archivalChoice: String? = null

    /** 交易选的对象势力 id（仅 TRADE；候选见 EndingProgression.TRADE_CANDIDATES）。 */
    @JvmField
    var tradeFactionId: String? = null

    /** 交易选一次性报酬（实发额，回执明细用）。 */
    @JvmField
    var tradePayout: Int = 0

    /** 档案室转入「已归档」只读（公开/交易选；封存选玩家保留全部访问权）。 */
    @JvmField
    var archivesReadOnly: Boolean = false

    /** 已生效的势力强度修正（factionId → 幅度）：EndingEffects 幂等挂载市场 stat 的事实源。 */
    @JvmField
    var appliedStrengthPct: MutableMap<String, Float> = HashMap()

    /** 延迟生效的势力强度条目（factionId → 待生效效果；到期由结局管理脚本激活）。 */
    @JvmField
    var pendingStrengthEffects: MutableMap<String, PendingStrengthEffect> = HashMap()

    /** 「执行官」特化方向（EndingProgression.ExecutorSpec 名；签发时二选一，选定不可更改）。 */
    @JvmField
    var executorSpec: String? = null

    /** 「执行官」是否已签发（签发即入玩家货舱，特化选择与其同事务）。 */
    @JvmField
    var executorIssued: Boolean = false

    /** 战斗特化：指挥舰 FleetMember id（舰上任执行官以 captain.aiCoreId 举证，FleetMember 无 memory 通道）。 */
    @JvmField
    var executorCommandShipId: String? = null

    /** 行政特化：任命的殖民地市场 id。 */
    @JvmField
    var executorAdminMarketId: String? = null

    /** 无限赏金槽位（《无限期承包合同》签署后常驻 [InfiniteBountyGenerator.SLOT_COUNT] 槽）。 */
    @JvmField
    var infiniteSlots: MutableList<InfiniteSlotState> = ArrayList()

    /** 无限赏金换代计数（全部槽位共享的单调序号，文书编号与种子源）。 */
    @JvmField
    var infiniteGeneration: Int = 0

    /** 无限赏金核销流水（账户页流水展示；与主线 quotedRewards 口径分离）。 */
    @JvmField
    var infiniteSettlements: MutableList<InfiniteSettleRecord> = ArrayList()

    /**
     * 旧存档迁移：XStream 反序列化不调用构造函数，旧版本存档取出的实例可能缺失
     * 后加的集合/Map 字段（运行时为 null）。取档后统一调用本方法重建为空实例。
     *
     * @Suppress：字段声明为非空类型是 Kotlin 侧口径；XStream 注入的 null 只有运行时可见，
     * 此处的 null 判定对新档恒 false、对旧档为必要修复，并非冗余检查。
     */
    @Suppress("SENSELESS_COMPARISON")
    internal fun normalize() {
        if (patchedBountyKeys == null) patchedBountyKeys = LinkedHashSet()
        if (concludedBountyKeys == null) concludedBountyKeys = LinkedHashSet()
        if (clearedGroups == null) clearedGroups = LinkedHashSet()
        if (postedWorkOrders == null) postedWorkOrders = LinkedHashSet()
        if (destroyedWorkOrders == null) destroyedWorkOrders = LinkedHashSet()
        if (settledWorkOrders == null) settledWorkOrders = LinkedHashSet()
        if (workOrderStageIndex == null) workOrderStageIndex = HashMap()
        if (quotedRewards == null) quotedRewards = HashMap()
        if (lockedFleetPlans == null) lockedFleetPlans = HashMap()
        if (grantedGroupBonuses == null) grantedGroupBonuses = HashMap()
        if (chapterHooks == null) chapterHooks = LinkedHashSet()
        if (chapterClearingOrders == null) chapterClearingOrders = HashMap()
        if (appliedStrengthPct == null) appliedStrengthPct = HashMap()
        if (pendingStrengthEffects == null) pendingStrengthEffects = HashMap()
        if (infiniteSlots == null) infiniteSlots = ArrayList()
        if (infiniteSettlements == null) infiniteSettlements = ArrayList()
    }

    companion object {
        @JvmStatic
        fun getOrCreate(): BountyState {
            val sector = Global.getSector() ?: return BountyState()
            val pd = sector.persistentData
            val existing = pd[BountyKeys.PERSISTENT_STATE_KEY]
            if (existing is BountyState) {
                existing.normalize()
                return existing
            }
            val created = BountyState()
            pd[BountyKeys.PERSISTENT_STATE_KEY] = created
            return created
        }
    }
}

/**
 * 延迟生效的势力强度条目（13 文档：封存=延迟数周期；交易=其余势力延迟）。
 *
 * XStream 存档口径：可序列化普通字段 + 无参构造。
 */
class PendingStrengthEffect() {
    /** 目标势力 id。 */
    @JvmField
    var factionId: String = ""

    /** 强度提升幅度（如 0.12 = +12%；13 文档提案值）。 */
    @JvmField
    var pct: Float = 0f

    /** 激活时刻（战役时钟 timestamp；到期由结局管理脚本激活并移出待生效表）。 */
    @JvmField
    var activateTimestamp: Long = 0L

    constructor(factionId: String, pct: Float, activateTimestamp: Long) : this() {
        this.factionId = factionId
        this.pct = pct
        this.activateTimestamp = activateTimestamp
    }
}

/**
 * 无限赏金槽位状态（《无限期承包合同》常驻工单；交付核销后换代重挂）。
 *
 * XStream 存档口径：可序列化普通字段 + 无参构造。
 */
class InfiniteSlotState() {
    /** 槽位序号（0..[InfiniteBountyGenerator.SLOT_COUNT]-1；换代不变）。 */
    @JvmField
    var index: Int = 0

    /** 换代序号（签署时初始化为 1 起；每次核销换代 +1）。 */
    @JvmField
    var generation: Int = 0

    /** 危险等级（1~5；驱动 FP 档位与 R 型词缀开放数）。 */
    @JvmField
    var danger: Int = 1

    /** 预设 FP（800~2800，按危险级线性档位）。 */
    @JvmField
    var fp: Int = 800

    /** 本代随机种子（报价/词缀/目标势力的确定性源）。 */
    @JvmField
    var seed: Long = 0L

    /** 接取时锁定（此处即生成时锁定）的报价。 */
    @JvmField
    var quotedReward: Int = 0

    /** 目标势力 id（辖区安全维护条款口径的常规威胁势力）。 */
    @JvmField
    var targetFactionId: String = "pirates"

    /** 旗舰 variant（挂出时由桥接层从模组舰船池按种子选定并写回）。 */
    @JvmField
    var flagshipVariantId: String = ""

    /** 本代词缀 id 表（生成时抽取并锁定；文书「追加条款」栏与舰队挂载共用此表）。 */
    @JvmField
    var affixIds: MutableList<String> = ArrayList()

    /** 生命周期："" = 待接取；"POSTED" = 执行中；"DESTROYED" = 已击毁待核销。 */
    @JvmField
    var lifecycle: String = ""

    constructor(
        index: Int,
        generation: Int,
        danger: Int,
        fp: Int,
        seed: Long,
        quotedReward: Int,
        targetFactionId: String,
        affixIds: List<String>,
    ) : this() {
        this.index = index
        this.generation = generation
        this.danger = danger
        this.fp = fp
        this.seed = seed
        this.quotedReward = quotedReward
        this.targetFactionId = targetFactionId
        this.affixIds = ArrayList(affixIds)
    }
}

/**
 * 挂出时锁定的舰队组建快照（[BountyState.lockedFleetPlans] 的值）。
 *
 * 用途：buildSpec（挂出构造 MagicBountySpec，填 job_item_reward 核心打捞表）与
 * BountyCampaignManager 的舰队重建（接取后 patch）共享同一份组建结果，
 * 保证「掉落的正是舰队里装的」；同一工单同一阶段失败重挂沿用首次锁定。
 *
 * XStream 存档口径：可序列化普通字段 + 无参构造。
 */
class LockedFleetPlan() {
    /** 选中的 variant id 表（索引 0 = 旗舰）。 */
    @JvmField
    var pickedVariantIds: MutableList<String> = ArrayList()

    /** 编队词缀 hullmod id 表。 */
    @JvmField
    var affixHullMods: MutableList<String> = ArrayList()

    /** 旗舰专属词缀 hullmod id 表。 */
    @JvmField
    var flagshipAffixHullMods: MutableList<String> = ArrayList()

    /** 舰载核心 commodity id 表（与 [pickedVariantIds] 同下标对齐）。 */
    @JvmField
    var officerCoreIds: MutableList<String> = ArrayList()

    /** 难度系数 k（0..1）。 */
    @JvmField
    var k: Float = 0f

    /** 总缩放倍率。 */
    @JvmField
    var totalMult: Float = 1f

    /** 核心打捞表（物品 id → 数量；MagicBounty job_item_reward 语义，锁定时滚动定型）。 */
    @JvmField
    var coreLoot: MutableMap<String, Int> = LinkedHashMap()

    constructor(comp: FleetComposer.Composition, coreLoot: Map<String, Int>) : this() {
        this.pickedVariantIds = ArrayList(comp.pickedVariantIds)
        this.affixHullMods = ArrayList(comp.affixHullMods)
        this.flagshipAffixHullMods = ArrayList(comp.flagshipAffixHullMods)
        this.officerCoreIds = ArrayList(comp.officerCoreIds)
        this.k = comp.k
        this.totalMult = comp.totalMult
        this.coreLoot = LinkedHashMap(coreLoot)
    }

    /** 还原为 FleetComposer 组建结果（舰队重建 patch 用）。 */
    fun toComposition(): FleetComposer.Composition = FleetComposer.Composition(
        pickedVariantIds = pickedVariantIds,
        affixHullMods = affixHullMods,
        flagshipAffixHullMods = flagshipAffixHullMods,
        officerCoreIds = officerCoreIds,
        k = k,
        totalMult = totalMult,
    )
}

/** 无限赏金核销流水行（账户页展示）。XStream 存档口径同上。 */
class InfiniteSettleRecord() {
    /** 文书编号（含换代序号）。 */
    @JvmField
    var serial: String = ""

    /** 实发金额（锁定报价）。 */
    @JvmField
    var amount: Int = 0

    constructor(serial: String, amount: Int) : this() {
        this.serial = serial
        this.amount = amount
    }
}
