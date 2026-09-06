package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.world.GravityNodes

/**
 * 档案室注册表（doc 05 第一层 / doc 07 第二层 / doc 09 第三层 / doc 11 第四层 + 最终档案）。
 *
 * 解锁口径（均与文档「随进度逐份开放」对齐）：
 * - 第一层 7 份：批次一结清 → #1~2，批次二结清 → #3~5，批次三结清 → #6~7（doc 05 建议节奏）；
 * - 第二层 6 份：星坠线三单逐份 → #1~3；紫菀线引力节点拔除 1/2 个 → #4~5；双线结清 → #6 残页（doc 07）；
 * - 第三层 3 份：ZX 三单逐份核销（doc 09）；
 * - 第四层 3 份：ZQ 三阶段逐段推进（doc 11 口径「随三单逐份」——ZQ 为单工单三阶段）；
 * - 最终档案（清算令原件）：ZQ 核销后开放。
 *
 * 文案：标题/正文取 strings.json `story.archive.<id>.title/.body`（公文体，按各章档案表补写）。
 * 查询入口 [unlocked] 供分局站档案室对话与阶段 4 全屏 UI 复用。
 */
object StoryArchives {

    /**
     * 一份档案。
     *
     * @property id 档案 id（i18n 键段 `story.archive.<id>.*`）
     * @property layer 层数（1~4；最终档案为 5，终端置顶展示）
     */
    data class ArchiveDef(val id: String, val layer: Int)

    /** 全部档案（声明顺序即层内顺序）。 */
    val all: List<ArchiveDef> = listOf(
        // 第一层（doc 05）
        ArchiveDef("l1_charter", 1),
        ArchiveDef("l1_councils_memo", 1),
        ArchiveDef("l1_tritech_bulletin", 1),
        ArchiveDef("l1_annual_inspection", 1),
        ArchiveDef("l1_coordinator_regulation", 1),
        ArchiveDef("l1_battlegroup_index", 1),
        ArchiveDef("l1_contractor_policy", 1),
        // 第二层（doc 07）
        ArchiveDef("l2_design_catalog", 2),
        ArchiveDef("l2_mothball_fleet_list", 2),
        ArchiveDef("l2_defense_log_lastpage", 2),
        ArchiveDef("l2_admin_core_whitepaper", 2),
        ArchiveDef("l2_ethics_review", 2),
        ArchiveDef("l2_joint_memo_fragment", 2),
        // 第三层（doc 09）
        ArchiveDef("l3_mothball_order", 3),
        ArchiveDef("l3_target_registry_log", 3),
        ArchiveDef("l3_liquidation_review_frontpage", 3),
        // 第四层（doc 11）
        ArchiveDef("l4_liquidation_review_full", 4),
        ArchiveDef("l4_vote_tally", 4),
        ArchiveDef("l4_deregister_execution_summary", 4),
        // 最终档案（doc 11：清算令原件）
        ArchiveDef("final_liquidation_order", 5),
    )

    /** 当前已解锁的档案（按层/声明顺序）。 */
    fun unlocked(state: BountyState): List<ArchiveDef> = all.filter { isUnlocked(it, state) }

    /** 单份档案的解锁判定（游戏侧：引力节点拔除数实时读取）。 */
    fun isUnlocked(def: ArchiveDef, state: BountyState): Boolean =
        isUnlocked(def, state, GravityNodes.pulledCount())

    /**
     * 单份档案解锁判定的纯逻辑口径（[pulledCount] 注入引力节点拔除数，不触碰 Global，可单测）。
     */
    fun isUnlocked(def: ArchiveDef, state: BountyState, pulledCount: Int): Boolean = when (def.id) {
        // 第一层：批次结清递进（doc 05）
        "l1_charter", "l1_councils_memo" ->
            MainBounties.GROUP_CH1_BATCH1 in state.clearedGroups
        "l1_tritech_bulletin", "l1_annual_inspection", "l1_coordinator_regulation" ->
            MainBounties.GROUP_CH1_BATCH2 in state.clearedGroups
        "l1_battlegroup_index", "l1_contractor_policy" ->
            MainBounties.GROUP_CH1_BATCH3 in state.clearedGroups

        // 第二层：星坠线三单逐份；紫菀线随节点拔除；残页须双线结清（doc 07）
        "l2_design_catalog" -> MainBounties.KEY_XC_0216 in state.settledWorkOrders
        "l2_mothball_fleet_list" -> MainBounties.KEY_XC_0217 in state.settledWorkOrders
        "l2_defense_log_lastpage" -> MainBounties.KEY_XC_0221 in state.settledWorkOrders
        "l2_admin_core_whitepaper" -> pulledCount >= 1
        "l2_ethics_review" -> pulledCount >= 2
        "l2_joint_memo_fragment" ->
            MainBounties.GROUP_CH2_XC in state.clearedGroups && MainBounties.GROUP_CH2_ZW in state.clearedGroups

        // 第三层：ZX 三单逐份核销（doc 09）
        "l3_mothball_order" -> MainBounties.KEY_ZX_1001 in state.settledWorkOrders
        "l3_target_registry_log" -> MainBounties.KEY_ZX_0344 in state.settledWorkOrders
        "l3_liquidation_review_frontpage" -> MainBounties.KEY_ZX_0002 in state.settledWorkOrders

        // 第四层：ZQ 三阶段逐段（doc 11「随三单逐份」之于单工单三阶段的等价口径）
        "l4_liquidation_review_full" -> (state.workOrderStageIndex[MainBounties.KEY_ZQ_0001] ?: 0) >= 1
        "l4_vote_tally" -> (state.workOrderStageIndex[MainBounties.KEY_ZQ_0001] ?: 0) >= 2
        "l4_deregister_execution_summary" -> (state.workOrderStageIndex[MainBounties.KEY_ZQ_0001] ?: 0) >= 3 ||
            MainBounties.KEY_ZQ_0001 in state.destroyedWorkOrders ||
            MainBounties.KEY_ZQ_0001 in state.settledWorkOrders

        // 最终档案：ZQ 核销后开放（doc 11）
        "final_liquidation_order" -> MainBounties.KEY_ZQ_0001 in state.settledWorkOrders

        else -> false
    }
}
