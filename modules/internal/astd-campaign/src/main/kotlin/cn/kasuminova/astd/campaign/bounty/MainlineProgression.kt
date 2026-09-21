package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.MainlineProgression.REWARD_SCALE_CAP
import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 赏金主线推进逻辑（纯函数层，不触碰 Global，可直接单测）。
 *
 * 职责：
 * - 批次/章节 gating 链（序章 → 一章三批 → 二章双线并行 → 三章 → 四章 → 归档挂起）；
 * - 清算序列进度节拍（基准 97.3%，含三章单 2 后 -0.5% 反常跳动）；
 * - 承包商等级递升（序章注册一级 → 四章末五级）；
 * - 报酬报价锁定与结算计算（区间、难度系数缩放、5× 封顶）。
 *
 * 副作用（发钱、刷舰队、写 memory）全部在游戏侧的 [MainBountyBridge] / [BountyCampaignManager]。
 */
object MainlineProgression {

    /** 清算序列进度基准：清算程序自 c+209 启动以来两百年自动推进的读数（02 文档口径）。 */
    const val LIQUIDATION_BASELINE: Float = 97.3f

    /** 清算序列进度上限（100% 触发终局事件）。 */
    const val LIQUIDATION_MAX: Float = 100f

    /** 归档挂起判定的浮点容差：节拍理论值恰为 100.0，容差只吸收 float 累计误差。 */
    const val ARCHIVAL_PROGRESS_EPSILON: Float = 1e-3f

    /** 报酬缩放封顶倍数（难度系数 k_s 超界时按 5× 封顶）。 */
    const val REWARD_SCALE_CAP: Float = 5f

    /** 章末钩子：一章末——封存工单类目解锁（05 文档）。 */
    const val HOOK_SEALED_CATEGORIES = "sealed_categories_unlocked"

    /** 章末钩子：二章末——序章目标识别码重复挂出 + 清算进度 97.3% 首现（07/08 文档）。 */
    const val HOOK_DUPLICATE_TARGET = "duplicate_target_reposted"

    /** 章末钩子：三章末——无编号半行工单「依第 X 号战斗群保障条例……」（09/10 文档）。 */
    const val HOOK_HALF_LINE_ORDER = "half_line_work_order"

    /** 章末钩子：四章末——终局回执（清算序列 100%，「注销知情者」检索无在编目标，12 文档）。 */
    const val HOOK_FINAL_RECEIPT = "final_receipt"

    /** 章节结清时触发的章末钩子（序章无钩子，注册确认函即其回执）。 */
    private val CHAPTER_HOOKS: Map<Int, String> = mapOf(
        1 to HOOK_SEALED_CATEGORIES,
        2 to HOOK_DUPLICATE_TARGET,
        3 to HOOK_HALF_LINE_ORDER,
        4 to HOOK_FINAL_RECEIPT,
    )

    /**
     * 当前可挂出的主线工单（gating 满足、未挂出、未击毁、未核销）。
     *
     * 序章工单 boardPosted=false，不在此列——它由酒馆对话接取（触发点见
     * [MainBountyBridge.acceptPrologueWorkOrder] 注释）。
     */
    fun postableOrders(state: BountyState): List<MainBounties.WorkOrder> = MainBounties.all.filter { def ->
        def.boardPosted &&
                def.key !in state.postedWorkOrders &&
                def.key !in state.destroyedWorkOrders &&
                def.key !in state.settledWorkOrders &&
                state.clearedGroups.containsAll(def.requiresGroups) &&
                state.settledWorkOrders.containsAll(def.requiresOrders)
    }

    /** 组是否已结清（组内工单全部核销）。 */
    fun isGroupCleared(state: BountyState, groupId: String): Boolean =
        groupId in state.clearedGroups ||
                MainBounties.ordersOfGroup(groupId).all { it.key in state.settledWorkOrders }

    /** 章节是否已结清（章内结清组全部结清）。 */
    fun isChapterCleared(state: BountyState, chapter: Int): Boolean =
        MainBounties.groupsOfChapter(chapter).all { isGroupCleared(state, it.id) }

    /** 待核销（已击毁未交付）的工单列表，供终端 UI 查询。 */
    fun pendingSettlement(state: BountyState): List<MainBounties.WorkOrder> =
        state.destroyedWorkOrders.mapNotNull { MainBounties.byKey(it) }

    /** 报价锁定的存储键（整单一键：多阶段工单不按阶段拆分报价，L5 口径）。 */
    fun quoteKey(orderKey: String): String = orderKey

    /**
     * 整单报价：区间内按种子取基值，乘难度系数（封顶 [REWARD_SCALE_CAP]×）。
     * 同一种子下报价确定，且 k_s=5 恰为 k_s=1 的 5 倍。
     *
     * 多阶段工单（ZW/ZQ）同样只锁一份整单报价：文书酬金栏按整单核定，
     * 不按阶段数打折或累乘（L5 口径），结算时一次性发放。
     */
    fun quoteOrderReward(def: MainBounties.WorkOrder, kS: Float, seed: Long): Int {
        val rnd = Random(seed)
        val base = def.rewardMin + rnd.nextInt(def.rewardMax - def.rewardMin + 1)
        return (base * rewardScale(kS)).roundToInt()
    }

    /** 结清奖金缩放：基数 × 难度系数（封顶 5×）。 */
    fun scaledBonus(bonusBase: Int, kS: Float): Int = (bonusBase * rewardScale(kS)).roundToInt()

    private fun rewardScale(kS: Float): Float = min(kS.coerceAtLeast(1f), REWARD_SCALE_CAP)

    /**
     * 多阶段工单某阶段被击毁后的推进结果。
     */
    sealed interface StageDestroy {
        /** 阶段推进：下一执行阶段下标（同一工单 key 重新生成舰队）。 */
        data class Advance(val nextStageIndex: Int) : StageDestroy

        /** 最终阶段击毁：工单进入「待核销」（等待分局终端交付）。 */
        data object FinalStage : StageDestroy
    }

    /**
     * 登记一次阶段击毁：推进清算进度（阶段增量）、推进内部阶段或标记待核销。
     *
     * 幂等性由调用方保证（同一阶段不重复登记）。
     */
    fun onStageDestroyed(state: BountyState, key: String): StageDestroy {
        val def = MainBounties.byKey(key)
            ?: error("onStageDestroyed: 未知主线工单 $key")
        val idx = (state.workOrderStageIndex[key] ?: 0).coerceIn(0, def.stages.lastIndex)
        val stage = def.stages[idx]

        state.liquidationProgress =
            (state.liquidationProgress + stage.liquidationDelta).coerceIn(0f, LIQUIDATION_MAX)

        return if (idx < def.stages.lastIndex) {
            state.workOrderStageIndex[key] = idx + 1
            StageDestroy.Advance(idx + 1)
        } else {
            state.destroyedWorkOrders.add(key)
            state.postedWorkOrders.remove(key)
            StageDestroy.FinalStage
        }
    }

    /**
     * 失败终态登记：工单回到未挂出状态（由桥接层重新挂出，防死档）。
     * 阶段进度不回退（重打当前阶段）。
     *
     * 幂等：工单不在挂出集中时返回 null（重复失败回调/已核销/已击毁均不再触发重挂），
     * 避免同一失败终态在 intel 收尾前的多个 tick 里被反复重置。
     *
     * @return 需要重挂的工单定义；非主线/未挂出/已终结返回 null。
     */
    fun markFailed(state: BountyState, key: String): MainBounties.WorkOrder? {
        val def = MainBounties.byKey(key) ?: return null
        if (key in state.settledWorkOrders || key in state.destroyedWorkOrders) return null
        if (!state.postedWorkOrders.remove(key)) return null
        return def
    }

    /**
     * 引力节点拔除驱动的阶段同步（ZW 工单阶段 0~2，07 文档：阶段一~三 = 拔除 3 座引力节点）。
     *
     * 口径：拔除进度与工单阶段取 max——工单挂出前拔除的节点同样计入
     * （挂出时由 MainBountyBridge.postWorkOrder 先同步一次，此后拔除进度由
     * tickPosted 周期性消费），不倒退、不死档。节点驱动阶段不产生清算进度增量
     * （ZW 各阶段 liquidationDelta 均为 0），本函数只推进阶段下标。
     *
     * 幂等：[pulledCount] 不超过当前阶段下标时不产生任何写入。
     *
     * @return 推进后的阶段下标；未推进/工单非节点驱动/已进入战斗阶段/已终结返回 null。
     */
    fun syncNodePulledStage(state: BountyState, key: String, pulledCount: Int): Int? {
        val def = MainBounties.byKey(key) ?: return null
        if (def.nodeDrivenStages <= 0) return null
        if (key in state.destroyedWorkOrders || key in state.settledWorkOrders) return null
        val cur = (state.workOrderStageIndex[key] ?: 0).coerceIn(0, def.stages.lastIndex)
        if (cur >= def.nodeDrivenStages) return null // 已进入战斗阶段（核心守备），节点拔除不再驱动
        val target = pulledCount.coerceIn(0, def.nodeDrivenStages)
        if (target <= cur) return null
        state.workOrderStageIndex[key] = target
        return target
    }

    /**
     * 结算结果（纯数据；发钱/回执展示/写 sector memory 由调用方在本次返回后立即执行）。
     */
    data class SettleResult(
        /** 是否结算成功（false 时其余字段无意义，见 [rejectReason]）。 */
        val success: Boolean,
        val rejectReason: String? = null,
        /** 单票报酬（整单锁定报价，多阶段工单不按阶段数放大）。 */
        val orderPayout: Int = 0,
        /** 本次触发的结清组奖金（组 id → 金额）。 */
        val groupBonus: Pair<String, Int>? = null,
        /** 本次结清的章节（触发章末钩子/升级时非空）。 */
        val chapterCleared: Int? = null,
        /** 本次结算新触发的章末钩子（无则 null；桥接层据此立即写 sector memory）。 */
        val newHook: String? = null,
        /** 本次结算后是否处于归档挂起（四章末且清算进度达 100% 才为 true）。 */
        val archivalPending: Boolean = false,
        /** 结算后的清算序列进度（%）。 */
        val liquidationProgress: Float = LIQUIDATION_BASELINE,
    ) {
        /** 本次发放总额（单票 + 结清奖金）。 */
        val totalPayout: Int get() = orderPayout + (groupBonus?.second ?: 0)
    }

    /**
     * 交付核销结算（「分局终端交付」的核心逻辑，阶段 4 终端 UI 调用 [MainBountyBridge.settleWorkOrder]，
     * 后者内部走本函数）。
     *
     * 流程：校验已击毁且未核销 → 按锁定报价发放单票报酬 → 推进清算进度（单阶段工单增量）→
     * 结清组判定（发放结清奖金、记录）→ 章节判定（等级递升、章末钩子、四章末置 archivalPending）。
     */
    fun settle(state: BountyState, key: String, kS: Float): SettleResult {
        val def = MainBounties.byKey(key)
            ?: return SettleResult(false, "unknown:$key")
        if (key in state.settledWorkOrders) return SettleResult(false, "already_settled:$key")
        if (key !in state.destroyedWorkOrders) return SettleResult(false, "not_destroyed:$key")

        // 单票报酬（按接取时锁定的整单报价；缺报价视为数据异常，拒绝结算并留痕）
        val payout = state.quotedRewards[quoteKey(key)]
            ?: return SettleResult(false, "missing_quote:$key")

        state.settledWorkOrders.add(key)
        state.destroyedWorkOrders.remove(key)
        state.postedWorkOrders.remove(key)
        state.mainCompleted++

        // 清算序列进度：单阶段工单在核销时推进（多阶段工单已在各阶段击毁时推进）
        if (!def.multiStage) {
            state.liquidationProgress =
                (state.liquidationProgress + def.liquidationDelta).coerceIn(0f, LIQUIDATION_MAX)
        }

        // 结清组判定
        var groupBonus: Pair<String, Int>? = null
        val group = MainBounties.group(def.groupId)
        if (group != null && def.groupId !in state.clearedGroups && isGroupCleared(state, def.groupId)) {
            state.clearedGroups.add(def.groupId)
            val bonus = scaledBonus(group.bonusBase, kS)
            state.grantedGroupBonuses[def.groupId] = bonus
            groupBonus = def.groupId to bonus
        }

        // 章节判定：等级递升（序章注册一级 → 四章末五级）、章末钩子、四章末归档挂起
        var chapterCleared: Int? = null
        var newHook: String? = null
        if (def.chapter >= state.currentChapter && isChapterCleared(state, def.chapter)) {
            chapterCleared = def.chapter
            state.contractorLevel = (def.chapter + 1).coerceIn(1, 5)
            state.currentChapter = def.chapter + 1
            state.chapterClearingOrders[def.chapter] = key
            newHook = CHAPTER_HOOKS[def.chapter]
            newHook?.let { state.chapterHooks.add(it) }
            // 硬校验：清算序列进度达 100% 才置归档挂起（12 文档终局口径）。
            // 容差仅覆盖浮点累计误差（节拍理论值恰为 100.0）；99.6 等未达读数不置位。
            if (def.chapter == 4 && state.liquidationProgress >= LIQUIDATION_MAX - ARCHIVAL_PROGRESS_EPSILON) {
                state.archivalPending = true
            }
        }

        return SettleResult(
            success = true,
            orderPayout = payout,
            groupBonus = groupBonus,
            chapterCleared = chapterCleared,
            newHook = newHook,
            archivalPending = state.archivalPending,
            liquidationProgress = state.liquidationProgress,
        )
    }
}
