package cn.kasuminova.astd.campaign.bounty.core

/**
 * 赏金舰队 SMod 内插优先级预设。
 *
 * 为本模组舰船提供“按优先级内插 SMod”的候选清单机制：调用方按返回顺序逐个尝试内插，
 * 直到达到该舰 SMod 上限或候选耗尽。返回纯船插 id 清单，存在性、内建冲突与 OP 过滤由调用方处理。
 */
interface BountySmodPresets {

    /**
     * 按优先级（高 → 低）返回候选 SMod id。
     *
     * [hullId] 用于个别舰体的专属覆盖清单；[phase] / [carrier] / [capital] 决定走哪一类通用清单。
     */
    fun prioritiesFor(hullId: String, phase: Boolean, carrier: Boolean, capital: Boolean): List<String>
}
