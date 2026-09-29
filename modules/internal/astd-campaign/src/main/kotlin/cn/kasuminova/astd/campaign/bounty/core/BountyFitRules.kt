package cn.kasuminova.astd.campaign.bounty.core

import com.fs.starfarer.api.combat.ShipAPI

/**
 * 赏金舰队额外装配规则。
 *
 * 覆盖 SMod 处理及之后的多余装配点利用：SMod 候选统一优先级、余 OP 普通船插优先级、
 * 拆辐能寄存器/耗散通道回收 OP 的舰级预算、SHU（Special Hullmod Upgrades）软联动加权候选。
 * 只提供候选与预算数据；船插存在性、内建冲突、适用性判定、OP 过滤与安装由调用方完成。
 */
interface BountyFitRules {

    /** SMod 候选统一优先级（高 → 低）；不适用条目由调用方跳过并取下一个。 */
    fun smodPriority(): List<String>

    /** SMod 处理后剩余 OP 的普通船插候选优先级（高 → 低）；装不下取下一个。 */
    fun extraModPriority(): List<String>

    /**
     * OP 回收预算（按舰级）：额外装配 OP 不足时，允许拆辐能寄存器与耗散通道
     * 改装回收的 OP 上限，回收总量不得超过该值。
     */
    fun reclaimBudget(size: ShipAPI.HullSize): Int

    /**
     * SHU 软联动加权候选（hullmod id → 权重）。
     * 条件不满足的条目权重按表调整为 0 后不返回；权重全 0 时返回空表（不安装）。
     */
    fun shuCandidates(size: ShipAPI.HullSize, carrier: Boolean): List<Pair<String, Float>>
}
