package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger

/**
 * 赏金核心打捞发放。
 *
 * MagicBounty 数据侧的 job_item_reward 只支持静态物品表，无法满足「按舰队实际装舰核心
 * 滚动、数量随舰队规模伸缩」的需求；ActiveBounty 成功时写入的 $<bountyId>_succeeded 全局
 * 记忆键是数据侧之外唯一可靠的完成信号，故用本脚本轮询该键触发发放：
 * 读取 BountyFleetTunerImpl 生成舰队时写下的装舰核心表，经 StandardCores.rollCoreLoot
 * 滚动后直接放入玩家货舱。发放以 \$astd_bounty_loot_paid 键去重，读档重打不重复发放。
 */
class BountyCoreLootScript : EveryFrameScript {

    private val log: Logger = Global.getLogger(BountyCoreLootScript::class.java)
    private var timer: Float = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        timer += amount
        if (timer < SCAN_INTERVAL) return
        timer = 0f

        val sector = Global.getSector() ?: return
        val memory = sector.memoryWithoutUpdate
        for (bountyKey in BountyPoolConfig.THREAT_TIERS.keys) {
            if (!memory.getBoolean(bountyKey + SUCCEEDED_MEMKEY_SUFFIX)) continue
            if (memory.getBoolean(PAID_MEMKEY_PREFIX + bountyKey)) continue

            val raw = memory.get(BountyFleetTunerImpl.CORES_MEMKEY_PREFIX + bountyKey)
            if (raw !is List<*>) {
                // 赏金已成功但没有装舰表（旧存档或舰队生成阶段异常），不再重试，避免每帧刷错。
                log.error("[ASTD] 赏金 $bountyKey 已成功但缺少装舰核心表，无法发放核心打捞")
                memory.set(PAID_MEMKEY_PREFIX + bountyKey, true)
                continue
            }

            val installed = raw.filterIsInstance<String>()
            val loot = StandardCores.rollCoreLoot(installed, installed.hashCode().toLong())
            if (loot.isEmpty()) {
                log.warn("[ASTD] 赏金 $bountyKey 装舰核心均不可打捞（全 O 档），无核心掉落")
            } else {
                val cargo = sector.playerFleet.cargo
                loot.forEach { (coreId, count) -> cargo.addCommodity(coreId, count.toFloat()) }
                log.info("[ASTD] 赏金 $bountyKey 核心打捞发放：$loot（装舰 ${installed.size} 枚）")
            }
            memory.set(PAID_MEMKEY_PREFIX + bountyKey, true)
            memory.unset(BountyFleetTunerImpl.CORES_MEMKEY_PREFIX + bountyKey)
        }
    }

    companion object {
        const val SCAN_INTERVAL: Float = 1f

        /** ActiveBounty 成功时写入的全局记忆键后缀（完整键 = bountyKey + 后缀）。 */
        const val SUCCEEDED_MEMKEY_SUFFIX: String = "_succeeded"

        /** 打捞发放去重键前缀（完整键 = 前缀 + bountyKey）。 */
        const val PAID_MEMKEY_PREFIX: String = "\$astd_bounty_loot_paid_"
    }
}
