package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.BountyDef
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.ui.WorkOrderStatus

/**
 * 工单终端映射的纯计算层：把主线赏金运行时状态（结清/执行中/门槛）映射为终端工单状态。
 *
 * 与游戏环境解耦（gating 判定以函数注入），供单元测试直接驱动；
 * 实际接线见 [BountyTerminalDataSource]。
 */
object StoryTerminalMapping {

    /**
     * 一单在工单终端的可见状态；null = 未挂出（出现门槛未满足且未结清），终端不列出。
     *
     * @param succeeded 已成功结清的 bounty key
     * @param activeAccepted 当前已被接取（Accepted 阶段）的 bounty key
     * @param gating memKey 门槛判定（sector memory）
     */
    fun visibleStatus(
        def: BountyDef,
        succeeded: Set<String>,
        activeAccepted: Set<String>,
        gating: (String) -> Boolean,
    ): WorkOrderStatus? = when {
        def.key in succeeded -> WorkOrderStatus.SETTLED
        def.key in activeAccepted -> WorkOrderStatus.ACTIVE
        def.requiredMemKeys.all(gating) -> WorkOrderStatus.AVAILABLE
        else -> null
    }

    /** 批次（结清组）是否可见：组内至少一单可见才挂出该批次。 */
    fun batchVisible(
        group: MainBounties.GroupDef,
        statusOf: (String) -> WorkOrderStatus?,
    ): Boolean = (MainBounties.groupMembers[group.id] ?: emptyList()).any { statusOf(it) != null }
}
